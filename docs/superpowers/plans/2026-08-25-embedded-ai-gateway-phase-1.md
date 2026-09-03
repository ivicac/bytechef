# Embedded AI Gateway — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the AI Gateway reachable on behalf of a connected user, with an embedded default routing policy and per-connected-user spend attribution — the fallback tier the whole resolution chain terminates in.

**Architecture:** Four pieces, none of which change automation behaviour. A read path for null-scoped routing policies (which does not exist today). An embedded settings record persisted as one platform `Property` row at `Property.Scope.EMBEDDED`. A `ConnectedUserResolver` SPI in `platform-ai-gateway-api`, implemented by an embedded module, so automation never depends on embedded. And an `X-ByteChef-External-User-Id` header on the existing gateway route, resolved through that seam.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-25-embedded-ai-gateway-design.md`

**Scope:** Phase 1 only, per spec §10. Phase 2 — the `connected_user_id` column, per-connected-user policy binding, per-customer budgets, per-customer provider credentials (BYOK), and the embedded-gateway `ApiKey` type that makes the external-id header mandatory — gets its own plan after this lands.

Do not implement Phase 2 items here. A task that finds itself adding `connected_user_id`, a new `ApiKey` type, or provider-credential scoping has drifted. In particular: **Phase 1 must not reject header-less requests.** Enforcement is decided (spec ⚑3) but depends on a key type that does not exist yet, and rejecting header-less calls before it does would break every existing automation caller of the gateway.

## Global Constraints

- **Enterprise licence header + `@version ee` Javadoc tag on every new file.** All new code is under `server/ee/`. Spotless selects the header from the `@version ee` tag in the file **content**, not the path.
- **`@Bean` declarations and SPI interfaces consumed across deployments go in `*-api`, never `*-service`.** Distributed EE apps carry `*-api` + `*-remote-client` without `*-service`; a bean declared in `-service` fails their context at boot. `ResourceVisibilityPolicyRegistry` already bit this project once.
- **Automation modules must never depend on embedded modules.** This is the constraint that produced the `ConnectedUserResolver` seam (spec §5). If a task finds itself adding `embedded-connected-user-api` to an `automation-ai-gateway-*` build file, stop — the design has been violated.
- **Nullable `Long`, never primitive**, for every scope field. Null is a real state.
- **Enum ordinals are persisted as INT.** `Property.Scope` is `PLATFORM, AUTOMATION, EMBEDDED, WORKSPACE, PROJECT, INTEGRATION`. Never reorder it; `EMBEDDED` is already there and is the value to use.
- **No new Liquibase changelog for the settings row.** `uk_property_key_scope_environment_null_scope_id` (changelog `20260825000001_platform_configuration_property_unique_null_scope_id.xml`) already covers `property (key, scope, environment) WHERE scope_id IS NULL`. Verified present. Do not add a duplicate index.
- **Checkstyle:** test method names camelCase without underscores, for *every* method in test sources including private helpers. Empty blocks forbidden — a comment does not satisfy the rule. `TODO:` comments forbidden.
- **Java style:** one blank line before `if`/`for`/`while`/`switch`/`try`; one blank line between a variable modification and the next statement using it; no blank line before a class's closing brace; no `_` prefix on private methods; descriptive names throughout.
- **Adding a constructor collaborator to a scanned `@Service` breaks other modules' hand-assembled `@SpringBootTest(classes=...)` contexts.** After Tasks 4 and 5, grep for `*IntTestConfiguration` / `@TestConfiguration` classes that assemble the touched impl and add mock `@Bean`s there.
- **Run `./gradlew spotlessApply` before every commit.** Never judge a Gradle run piped into `tail`/`grep` — redirect to a file, check `$?` on its own line, then grep for `^> Task .* FAILED`.
- **Commit messages:** `<ticket> <description>`; no ticket exists, so use `embedded-gateway` as the prefix token.

---

## File Structure

| File | Responsibility |
|---|---|
| `platform-ai-gateway-api/.../repository/AiGatewayRoutingPolicyRepository.java` | + `findAllByWorkspaceIdIsNull()` |
| `platform-ai-gateway-api/.../service/AiGatewayRoutingPolicyService.java` | + `getDefaultRoutingPolicies()` |
| `platform-ai-gateway-service/.../service/AiGatewayRoutingPolicyServiceImpl.java` | implements it |
| `platform-ai-gateway-api/.../domain/AiGatewayEmbeddedSettings.java` | the settings record |
| `platform-ai-gateway-api/.../service/AiGatewayEmbeddedSettingsService.java` | its service interface |
| `platform-ai-gateway-service/.../service/AiGatewayEmbeddedSettingsServiceImpl.java` | Property-backed impl |
| `platform-ai-gateway-api/.../connecteduser/ConnectedUserResolver.java` | the SPI seam |
| `embedded-ai/embedded-ai-gateway-connected-user/...` | the embedded implementation |
| `automation-ai-gateway-service/.../facade/AiGatewayFacadeImpl.java` | resolution order + attribution |
| `automation-ai-gateway-public-rest/.../AiGatewayChatCompletionApiController.java` | reads the header |

---

## Task 1: The null-scope read path

The dead branch from spec §3.3. Everything else depends on it.

**Files:**
- Modify: `platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/repository/AiGatewayRoutingPolicyRepository.java`
- Modify: `platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyService.java`
- Modify: `platform-ai-gateway-service/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyServiceImpl.java`
- Test: `platform-ai-gateway-service/src/test/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayRoutingPolicyServiceTest.java` (create if absent)

**Interfaces:**
- Produces: `AiGatewayRoutingPolicyService.getDefaultRoutingPolicies()` → `List<AiGatewayRoutingPolicy>`, returning policies whose `workspaceId` is null.

- [ ] **Step 1: Write the failing test**

`AiGatewayRoutingPolicy`'s no-arg constructor is **private**; the public one is
`AiGatewayRoutingPolicy(String name, AiGatewayRoutingStrategyType strategy)`. Use that — an earlier draft
of this snippet called a no-arg constructor plus `setName`, which does not compile.

```java
@Test
void testGetDefaultRoutingPoliciesReturnsNullScopedPolicies() {
    AiGatewayRoutingPolicy defaultPolicy =
        new AiGatewayRoutingPolicy("default", AiGatewayRoutingStrategyType.SIMPLE);

    when(aiGatewayRoutingPolicyRepository.findAllByWorkspaceIdIsNull()).thenReturn(List.of(defaultPolicy));

    List<AiGatewayRoutingPolicy> policies = aiGatewayRoutingPolicyService.getDefaultRoutingPolicies();

    assertThat(policies).containsExactly(defaultPolicy);
}

@Test
void testGetRoutingPoliciesByWorkspaceIdDoesNotReturnNullScopedPolicies() {
    when(aiGatewayRoutingPolicyRepository.findAllByWorkspaceId(1L)).thenReturn(List.of());

    assertThat(aiGatewayRoutingPolicyService.getRoutingPoliciesByWorkspaceId(1L)).isEmpty();

    verify(aiGatewayRoutingPolicyRepository, never()).findAllByWorkspaceIdIsNull();
}
```

The second test is the one that matters: it pins that the workspace lookup did not quietly widen to include the default tier. Spec §12 requires it.

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test > /tmp/eg-t1.log 2>&1; echo "exit=$?"
```

Expected: FAIL — `findAllByWorkspaceIdIsNull` and `getDefaultRoutingPolicies` do not exist.

- [ ] **Step 3: Add the repository method**

In `AiGatewayRoutingPolicyRepository`, beside the existing `findAllByWorkspaceId(long workspaceId)`:

```java
    List<AiGatewayRoutingPolicy> findAllByWorkspaceIdIsNull();
```

Spring Data JDBC derives this from the method name; no `@Query` is needed.

- [ ] **Step 4: Add the service method**

In `AiGatewayRoutingPolicyService`, beside `getRoutingPoliciesByWorkspaceId`:

```java
    /**
     * Returns the default-tier policies — those bound to no workspace. Deliberately a separate method rather than a
     * nullable parameter on {@link #getRoutingPoliciesByWorkspaceId(long)}: overloading null onto a scope lookup
     * would make the automation path's primitive signature dishonest.
     */
    List<AiGatewayRoutingPolicy> getDefaultRoutingPolicies();
```

And in `AiGatewayRoutingPolicyServiceImpl`:

```java
    @Override
    @Transactional(readOnly = true)
    public List<AiGatewayRoutingPolicy> getDefaultRoutingPolicies() {
        return aiGatewayRoutingPolicyRepository.findAllByWorkspaceIdIsNull();
    }
```

Match the `@Transactional` annotation style already used on `getRoutingPoliciesByWorkspaceId` in that file — read it before writing, and copy whatever is there.

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test > /tmp/eg-t1.log 2>&1; echo "exit=$?"
```

Expected: exit=0.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply > /tmp/eg-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway
git commit -m "embedded-gateway Add a read path for default-tier routing policies"
```

---

## Task 2: The embedded settings record

**Files:**
- Create: `platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/domain/AiGatewayEmbeddedSettings.java`
- Create: `platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayEmbeddedSettingsService.java`
- Create: `platform-ai-gateway-service/src/main/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayEmbeddedSettingsServiceImpl.java`
- Test: `platform-ai-gateway-service/src/test/java/com/bytechef/ee/platform/ai/gateway/service/AiGatewayEmbeddedSettingsServiceTest.java`

**Interfaces:**
- Produces: `AiGatewayEmbeddedSettingsService.find()` → `Optional<AiGatewayEmbeddedSettings>`; `.upsert(AiGatewayEmbeddedSettings)` → `AiGatewayEmbeddedSettings`.

**Read first:** `AiGatewayWorkspaceSettingsServiceImpl` in `automation-ai-gateway-service`. This task mirrors it almost exactly — `propertyService.fetchProperty(KEY, Scope, scopeId)`, `propertyService.save(KEY, value, Scope, scopeId)`, a `toMap`/`toSettings` pair and typed `intValue`/`longValue` helpers. Copy its shape rather than inventing one, but **place the new files in `platform-ai-gateway-*`, not `automation-*`** — embedded must not reach into an automation module.

- [ ] **Step 1: Write the record**

```java
/**
 * Embedded-scope AI Gateway overrides. Persisted as a single {@link com.bytechef.platform.configuration.domain.Property}
 * row (scope={@code EMBEDDED}, scopeId={@code null}, key={@value #PROPERTY_KEY}). All fields are nullable; a null value
 * means "inherit from the system default".
 *
 * <p>
 * The null {@code scopeId} is why this record depends on the partial unique index
 * {@code uk_property_key_scope_environment_null_scope_id} — Postgres treats every null as distinct, so the
 * non-partial constraint never fires for these rows.
 *
 * @version ee
 */
public record AiGatewayEmbeddedSettings(
    Long environmentId,
    Integer retryCount,
    Integer timeoutMs,
    Boolean cacheEnabled,
    Integer cacheTtlSeconds,
    Integer logRetentionDays,
    Long defaultRoutingPolicyId,
    Integer softBudgetWarningPct) {

    public static final String PROPERTY_KEY = "ai_gateway_embedded_settings";

    public AiGatewayEmbeddedSettings {
        if (softBudgetWarningPct != null && (softBudgetWarningPct < 0 || softBudgetWarningPct > 100)) {
            throw new IllegalArgumentException(
                "softBudgetWarningPct must be between 0 and 100: " + softBudgetWarningPct);
        }
    }
}
```

Two deliberate differences from the workspace record. There is **no `workspaceId`** — embedded rows carry a null `scopeId`. But there **is** an `environmentId`, because the row is per environment: the partial unique index is on `(key, scope, environment) WHERE scope_id IS NULL`, so one row exists per environment, and `PropertyService`'s four-argument overloads are the ones to call. The workspace impl uses the three-argument overloads and is *not* per-environment — do not copy that part of its shape.

- [ ] **Step 2: Write the failing test**

```java
private static final long ENVIRONMENT_ID = 1L;

@Test
void testFindReturnsEmptyWhenNoPropertyRowExists() {
    when(propertyService.fetchProperty(
        AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, ENVIRONMENT_ID))
            .thenReturn(Optional.empty());

    assertThat(aiGatewayEmbeddedSettingsService.find(ENVIRONMENT_ID)).isEmpty();
}

@Test
void testFindMapsStoredValues() {
    Property property = mock(Property.class);

    when(property.getValue()).thenReturn(Map.of("defaultRoutingPolicyId", 7, "retryCount", 3));
    when(propertyService.fetchProperty(
        AiGatewayEmbeddedSettings.PROPERTY_KEY, Property.Scope.EMBEDDED, null, ENVIRONMENT_ID))
            .thenReturn(Optional.of(property));

    AiGatewayEmbeddedSettings settings = aiGatewayEmbeddedSettingsService.find(ENVIRONMENT_ID)
        .orElseThrow();

    assertThat(settings.defaultRoutingPolicyId()).isEqualTo(7L);
    assertThat(settings.retryCount()).isEqualTo(3);
    assertThat(settings.timeoutMs()).isNull();
}

@Test
void testUpsertSavesAtEmbeddedScopeWithNullScopeIdAndAnEnvironment() {
    AiGatewayEmbeddedSettings settings =
        new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 9L, null);

    aiGatewayEmbeddedSettingsService.upsert(settings);

    verify(propertyService).save(
        eq(AiGatewayEmbeddedSettings.PROPERTY_KEY), anyMap(), eq(Property.Scope.EMBEDDED), isNull(),
        eq(ENVIRONMENT_ID));
}

@Test
void testRejectsOutOfRangeSoftBudgetWarningPct() {
    assertThatThrownBy(
        () -> new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, null, 101))
            .isInstanceOf(IllegalArgumentException.class);
}
```

`testFindMapsStoredValues` stores `7` as an `Integer` and asserts a `Long` comes back — that is deliberate. Values round-trip through JSON, so the `longValue` helper must widen rather than cast, which is the bug the workspace impl's helpers already avoid.

Note the mock returns `Optional.of(property)`, not `Optional.of(map)`: `PropertyService.fetchProperty` returns `Optional<Property>`, and the value map is reached through `property.getValue()`. Mocking it as a bare map does not compile.

- [ ] **Step 3: Run to verify it fails, then write the service and impl**

Interface:

```java
public interface AiGatewayEmbeddedSettingsService {

    Optional<AiGatewayEmbeddedSettings> find(long environmentId);

    AiGatewayEmbeddedSettings upsert(AiGatewayEmbeddedSettings settings);
}
```

Implementation: mirror `AiGatewayWorkspaceSettingsServiceImpl` — same `KEY_*` constants, same `toMap`/`toSettings`/`intValue`/`longValue` shape — but with `Property.Scope.EMBEDDED`, a `null` scope id, an explicit `environmentId` passed to the **four-argument** `fetchProperty`/`save` overloads, and no `workspaceId` handling. Annotate the class `@Service`, `@ConditionalOnEEVersion`, and with the same `@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")` the other gateway beans carry — read one of them and copy it exactly.

Catch `DataIntegrityViolationException` in `upsert` and translate it, per spec §6; do not let the raw constraint violation reach the caller.

- [ ] **Step 4: Run to verify it passes, format, commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test > /tmp/eg-t2.log 2>&1; echo "exit=$?"
./gradlew spotlessApply > /tmp/eg-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway
git commit -m "embedded-gateway Add embedded-scope gateway settings"
```

---

## Task 3: The ConnectedUserResolver seam

The constraint that forces this task's existence: automation must not depend on embedded (spec §5).

**Files:**
- Create: `platform-ai-gateway-api/src/main/java/com/bytechef/ee/platform/ai/gateway/connecteduser/ConnectedUserResolver.java`
- Create: `server/ee/libs/embedded/embedded-ai/embedded-ai-gateway-connected-user/build.gradle.kts` and its `EmbeddedConnectedUserResolver`
- Modify: `settings.gradle.kts`
- Test: covered by Task 4's resolution tests and Task 6's integration test.

**Interfaces:**
- Produces: `ConnectedUserResolver.resolve(String externalId, long environmentId)` → `Optional<Long>` (the connected user id).

- [ ] **Step 1: Write the SPI**

```java
/**
 * Resolves an embedded {@code externalId} to a connected user id for gateway requests. Implemented by the embedded
 * edition; automation-only deployments register no implementation and the seam resolves to empty.
 *
 * <p>
 * This interface exists so that {@code automation-ai-gateway-*} never depends on {@code embedded-*}. It lives in
 * {@code -api} rather than {@code -service} because distributed EE apps carry {@code -api} without {@code -service}.
 *
 * <p>
 * The seam is fail-open by construction: an app whose classpath lacks an implementation resolves nothing and attempts
 * nothing. Callers MUST log a warning when a request carries an external-id header and no resolver is registered,
 * because that combination is a misconfiguration that would otherwise route to tenant defaults silently.
 *
 * @version ee
 */
public interface ConnectedUserResolver {

    Optional<Long> resolve(String externalId, long environmentId);
}
```

- [ ] **Step 2: Write the embedded implementation**

**Create a new module** at `server/ee/libs/embedded/embedded-ai/embedded-ai-gateway-connected-user`, the one named in the File Structure table, and register it in `settings.gradle.kts` beside the other `embedded-ai` includes (locate them by content, not line number).

Do **not** reuse an existing module. The only `embedded-ai` module that currently depends on `embedded-connected-user-api` is `embedded-ai-mcp-server`, whose concern is MCP; hanging a gateway resolver off it couples two unrelated features and drags MCP onto the classpath of anything wanting the resolver.

Its `build.gradle.kts` needs `embedded-connected-user-api` and `platform-ai-gateway-api`. That direction — embedded depending on platform — is fine, and is the whole point of the seam.

```java
@Component
@ConditionalOnEEVersion
class EmbeddedConnectedUserResolver implements ConnectedUserResolver {

    private final ConnectedUserService connectedUserService;

    EmbeddedConnectedUserResolver(ConnectedUserService connectedUserService) {
        this.connectedUserService = connectedUserService;
    }

    @Override
    public Optional<Long> resolve(String externalId, long environmentId) {
        return connectedUserService.fetchConnectedUser(externalId, environmentId)
            .filter(ConnectedUser::isEnabled)
            .map(ConnectedUser::getId);
    }
}
```

`fetchConnectedUser(String, long)` is an existing method on `ConnectedUserService` — verified. Use the `fetch` variant, never `getConnectedUser`, which throws, and **never** `createConnectedUser`. Auto-creation is the phantom-user failure mode spec §3.6 records.

`ConnectedUser.isEnabled()` is verified present (2026-08-25), so `ConnectedUser::isEnabled` compiles as written.

- [ ] **Step 3: Verify the layering did not invert**

```bash
grep -rn "embedded" server/ee/libs/automation/automation-ai/automation-ai-gateway/*/build.gradle.kts; echo "(empty = automation still clean)"
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-api:compileJava > /tmp/eg-t3.log 2>&1; echo "exit=$?"
```

Expected: no embedded dependency in any automation gateway build file, and exit=0.

- [ ] **Step 4: Format and commit**

```bash
./gradlew spotlessApply > /tmp/eg-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway Add the connected user resolver seam"
```

---

## Task 4: Resolution order in the facade

**Files:**
- Modify: `automation-ai-gateway-service/src/main/java/com/bytechef/ee/automation/ai/gateway/facade/AiGatewayFacadeImpl.java`
- Create: an exception type beside the module's existing gateway exceptions
- Modify: `AiGatewayExceptionHandler`
- Test: `automation-ai-gateway-service/src/test/java/com/bytechef/ee/automation/ai/gateway/facade/AiGatewayFacadeTest.java`

**Interfaces:**
- Consumes: `getDefaultRoutingPolicies()` (Task 1), `AiGatewayEmbeddedSettingsService.find(long)` (Task 2), `ConnectedUserResolver.resolve(String, long)` (Task 3).

**Read the facade before writing anything.** `AiGatewayFacadeImpl` already has a private
`applyRoutingPolicyPrecedence(AiGatewayChatCompletionRequest)` that implements the precedence chain
today: **request-specified → model default → direct routing**. Its Javadoc says, verbatim, that
"Workspace and project defaults would extend this chain once their resolution paths are wired". That is
this task's extension point.

**Do NOT invent a new `resolveRoutingPolicy` method.** An earlier draft of this plan specified one; that
was written without reading the facade and is withdrawn. Extend the existing chain instead.

**The chain after this task:** request-specified → model default → **embedded default (new)** → direct
routing. The embedded default goes *after* the model default deliberately: a model default is attached to
the specific model requested and is therefore more specific than a tenant-wide embedded default.
Appending rather than inserting also means every existing automation path is byte-for-byte unchanged,
which is how spec §13's promise is kept structurally rather than by careful implementation.

The facade gains two optional collaborators, injected as `ObjectProvider` so an app lacking them still boots:

```java
    private final ObjectProvider<ConnectedUserResolver> connectedUserResolverProvider;
    private final ObjectProvider<AiGatewayEmbeddedSettingsService> embeddedSettingsServiceProvider;
```

Phase 1 resolves only the **embedded default**. Spec §4's step 1 — a policy bound to the connected user —
does not exist yet and is Phase 2. Resolving the connected user still happens in Phase 1, because spend
attribution (Task 5) needs the id and because an unknown external id must be rejected.

- [ ] **Step 1: Write the failing tests**

Add to `AiGatewayFacadeTest`. These exercise a package-private
`resolveEmbeddedDefaultRoutingPolicyId(String externalUserId, long environmentId)` returning `Long`,
which Step 2 extracts — testing the private precedence method through the public entry point would
require mocking the entire completion path for no extra assurance.

```java
@Test
void testEmbeddedDefaultPolicyIsResolvedForAKnownConnectedUser() {
    when(connectedUserResolver.resolve("customer-1", ENVIRONMENT_ID)).thenReturn(Optional.of(5L));
    when(embeddedSettingsService.find(ENVIRONMENT_ID))
        .thenReturn(Optional.of(
            new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null)));

    assertThat(aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId("customer-1", ENVIRONMENT_ID))
        .isEqualTo(42L);
}

@Test
void testNoEmbeddedSettingsYieldsNoEmbeddedDefault() {
    when(connectedUserResolver.resolve("customer-1", ENVIRONMENT_ID)).thenReturn(Optional.of(5L));
    when(embeddedSettingsService.find(ENVIRONMENT_ID)).thenReturn(Optional.empty());

    assertThat(aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId("customer-1", ENVIRONMENT_ID))
        .isNull();
}

@Test
void testUnknownExternalUserIdIsRejected() {
    when(connectedUserResolver.resolve("ghost", ENVIRONMENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId("ghost", ENVIRONMENT_ID))
        .isInstanceOf(AiGatewayConnectedUserNotFoundException.class);
}

@Test
void testNoResolverRegisteredFallsThroughInsteadOfFailing() {
    when(connectedUserResolverProvider.getIfAvailable()).thenReturn(null);

    assertThat(aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId("customer-1", ENVIRONMENT_ID))
        .isNull();
}

@Test
void testNullExternalUserIdSkipsEmbeddedResolutionEntirely() {
    assertThat(aiGatewayFacade.resolveEmbeddedDefaultRoutingPolicyId(null, ENVIRONMENT_ID)).isNull();

    verifyNoInteractions(connectedUserResolver);
}
```

The last two encode spec §11 exactly, and they mean opposite things — an **unknown id** is an error, a
**missing resolver** is not. Do not collapse them. The final test is the one that guarantees automation
traffic is untouched: with no header there is no external user id, and embedded resolution must not run
at all.

- [ ] **Step 2: Run to verify they fail, then implement**

Extract `resolveEmbeddedDefaultRoutingPolicyId` as a package-private method and call it from
`applyRoutingPolicyPrecedence` as the step after `resolveModelDefaultRoutingPolicyId` returns null.
Follow the shape the neighbouring `resolveModelDefaultRoutingPolicyId` already uses, including its
fall-through-and-WARN treatment of a policy that cannot be resolved.

Resolve the collaborators through `ObjectProvider#getIfAvailable`, mirroring how `WorkflowVariablesResolver`
is consumed in `PrincipalJobFacadeImpl` — read that call site and copy its null-handling shape. When the
provider yields null **and** an external user id was supplied, log a WARN once (an
**instance** `final AtomicBoolean` guard, matching `WorkflowVariablesResolverImpl`'s actual
`private final AtomicBoolean failureLogged` — an earlier draft of this plan said `static final`, which
that class does not do. For a singleton bean the two are equivalent, but a static guard would suppress a
legitimate WARN in a second `ApplicationContext` sharing the JVM, such as across Gradle test workers)
and fall through.

Add `AiGatewayConnectedUserNotFoundException` beside the module's existing exceptions and map it to
**403** in `AiGatewayExceptionHandler` — read that handler and follow the mapping style already there.

- [ ] **Step 3: Run to verify they pass**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check --continue > /tmp/eg-t4.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/eg-t4.log; echo "(empty = green)"
```

- [ ] **Step 4: Repair other modules' hand-assembled test contexts**

Adding constructor collaborators to `AiGatewayFacadeImpl` breaks `@SpringBootTest(classes=...)` contexts elsewhere that assemble it by hand.

```bash
grep -rln "AiGatewayFacadeImpl" --include='*IntTestConfiguration.java' --include='*TestConfiguration.java' server
```

Add mock `@Bean`s for the two new collaborators to every file that turns up. Then re-run the check above plus the public-rest module's.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply > /tmp/eg-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway Resolve the embedded default routing policy"
```

## Task 5: The header and spend attribution

**Files:**
- Modify: `automation-ai-gateway-public-rest/src/main/java/com/bytechef/ee/automation/ai/gateway/public_/web/rest/AiGatewayChatCompletionApiController.java`
- Modify: the spend rollup and observability span writers in `automation-ai-gateway-service`
- Test: `automation-ai-gateway-public-rest/src/test/.../AiGatewayChatCompletionApiControllerTest.java`

**Read first:** `AiGatewayScoreApiController`, which already declares
`@RequestHeader(name = "X-ByteChef-Workspace-Id", required = false) String workspaceHeader`. Copy that declaration style exactly.

**This task must close a seam Task 4 deliberately left open.** Task 4 appended an embedded-default step to `applyRoutingPolicyPrecedence`, but its production call site passes `resolveEmbeddedDefaultRoutingPolicyId(null, 0L)` — inert, because header threading did not exist yet. **Until you replace those literals with the real external user id and environment id, the entire embedded routing feature is dead code that passes every test.**

So this task's definition of done includes:

- the `(null, 0L)` literals at that call site are gone, replaced by values threaded from the request;
- the Task 4 comment explaining why they were inert is removed, since it no longer applies;
- **a test proves the wiring**, not merely that the header is read. Assert that a request carrying `X-ByteChef-External-User-Id` actually reaches `resolveEmbeddedDefaultRoutingPolicyId` with that value — a test that only checks the controller passes the header to the facade would still pass if the facade dropped it on the floor.

Verify with a grep before you commit:

```bash
grep -n "resolveEmbeddedDefaultRoutingPolicyId(null, 0L)" server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/main/java/com/bytechef/ee/automation/ai/gateway/facade/AiGatewayFacadeImpl.java
```

Expected: empty. A hit means the feature is still inert.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testRequestWithoutTheHeaderRoutesAsBefore() throws Exception {
    mockMvc.perform(post("/api/ai-gateway/v1/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY))
        .andExpect(status().isOk());

    verify(aiGatewayFacade).chatCompletion(any(), isNull());
}

@Test
void testHeaderIsPassedThroughToTheFacade() throws Exception {
    mockMvc.perform(post("/api/ai-gateway/v1/chat/completions")
        .header("X-ByteChef-External-User-Id", "customer-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY))
        .andExpect(status().isOk());

    verify(aiGatewayFacade).chatCompletion(any(), eq("customer-1"));
}

@Test
void testUnknownExternalUserYieldsForbidden() throws Exception {
    when(aiGatewayFacade.chatCompletion(any(), eq("ghost")))
        .thenThrow(new AiGatewayConnectedUserNotFoundException("ghost"));

    mockMvc.perform(post("/api/ai-gateway/v1/chat/completions")
        .header("X-ByteChef-External-User-Id", "ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REQUEST_BODY))
        .andExpect(status().isForbidden());
}
```

The first test is the load-bearing one: it pins spec §13's promise that behaviour with no header is byte-for-byte what it is today.

- [ ] **Step 2: Implement, run, verify**

Add the header parameter to both the streaming and non-streaming mappings — the controller has a `/chat/completions` mapping producing `TEXT_EVENT_STREAM_VALUE` as well as the JSON one. Missing the streaming path is the easy mistake here; check the file for every mapping before you finish.

Thread the resolved connected user id into the spend rollup key and the observability span attributes. Do not add a new span type — spec §9.

- [ ] **Step 3: Format and commit**

```bash
./gradlew spotlessApply > /tmp/eg-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway Accept and attribute the external user id header"
```

---

## Task 6: Wiring and the integration test

**Files:**
- Modify: `server/apps/server-app/build.gradle.kts`
- Modify: `settings.gradle.kts` (if Task 3 created a new module)
- Test: `automation-ai-gateway-service/src/test/.../EmbeddedAiGatewayIntTest.java`

- [ ] **Step 1: Wire the embedded resolver module into server-app**

Add the module from Task 3 to `server-app/build.gradle.kts` as an `implementation(project(...))`, placed with the other `server:ee:libs:embedded:embedded-ai:*` entries — locate them by content, not line number.

Monolith only, per spec ⚑4. Do **not** add it to any `server/ee/apps/*` build file.

- [ ] **Step 2: Write the integration test**

An `IntTest` (Testcontainers, real Postgres) covering the chain end to end:

- a connected user exists; the embedded settings row names a default policy; a gateway request carrying that user's external id resolves to that policy;
- with no embedded settings row, the same request falls through to a null-scoped policy;
- a policy written with a null workspace id is returned by `getDefaultRoutingPolicies()` and **not** by `getRoutingPoliciesByWorkspaceId(...)` — spec §12's null-scope assertion against a real database, where Spring Data's derived query is actually exercised;
- an external id belonging to a different environment does not resolve, and creates no `ConnectedUser` row. Assert the row count before and after.

Name it `EmbeddedAiGatewayIntTest` — the `IntTest` suffix is what routes it to `testIntegration` rather than `test`.

- [ ] **Step 3: Run it**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration > /tmp/eg-t6.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/eg-t6.log; echo "(empty = green)"
```

Docker must be running. On this machine Testcontainers needs the OrbStack socket — `/var/run/docker.sock` is a dangling symlink.

- [ ] **Step 4: Full check, format, commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:check :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check --continue > /tmp/eg-check.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/eg-check.log; echo "(empty = green)"
./gradlew spotlessApply > /tmp/eg-spotless.log 2>&1; echo "exit=$?"
git add server
git commit -m "embedded-gateway Wire the embedded gateway into server-app"
```

SpotBugs reports are read from `build/reports/spotbugs/*.html` — the XML report is disabled in this project and is never rewritten, so a stale XML will lie to you.

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| §3.3 null-scope read path | Task 1 |
| §4 resolution order (phase 1 subset) | Task 4 |
| §4 check constraint, mutual exclusivity | **Phase 2** — the column it constrains does not exist yet |
| §5 header, controller-side resolution | Task 5 |
| §5 `ConnectedUserResolver` seam, no automation→embedded dependency | Task 3, verified mechanically in Task 3 Step 3 |
| §6 embedded settings at `Scope.EMBEDDED`, one row per environment | Task 2 |
| §7 no new settings table, no changelog | Task 2 (nothing added) |
| §8 isolation within tenant and environment | Task 6 integration test |
| §9 spend and observability attribution | Task 5 |
| §10 phase 1 boundary | Plan scope statement |
| §11 unknown id → 403; missing resolver → fall through + WARN | Task 4 Steps 1–2 |
| §12 testing | Tasks 1, 4, 5, 6 |
| §13 header-absent is byte-for-byte unchanged | Task 5 Step 1, first test |

**Deliberate omissions:** everything Phase 2 — the `connected_user_id` column, its check constraint, per-connected-user policy binding, and per-customer budgets. Spec §4's step 1 is therefore unimplemented after this plan, and the resolution chain has two links rather than three. That is the phasing spec §10 asks for, not a gap.

**Placeholder scan:** no TBD, TODO, or "similar to Task N". Three steps deliberately say "read X and copy its shape" rather than reproducing code — Task 2's mirror of `AiGatewayWorkspaceSettingsServiceImpl`, Task 4's mirror of the `WorkflowVariablesResolver` call site, and Task 5's mirror of the existing `@RequestHeader` declaration. In each case the file to read is named exactly and the thing to copy is stated. Reproducing those bodies here would duplicate code the repo already owns and would rot against it.

**Type consistency:** `getDefaultRoutingPolicies()` → `List<AiGatewayRoutingPolicy>` is declared in Task 1 and consumed identically in Tasks 4 and 6. `ConnectedUserResolver.resolve(String, long)` → `Optional<Long>` is declared in Task 3 and consumed in Task 4. `AiGatewayEmbeddedSettings`'s seven components are declared in Task 2 and constructed positionally in Task 4's tests with the same arity and order. `AiGatewayConnectedUserNotFoundException` is introduced in Task 4 and reused in Task 5.

**A risk that was flagged and then closed before execution.** An earlier draft of Task 4 targeted a `resolveRoutingPolicy(String, long)` method that does not exist, having been written without reading the facade. Reading it resolved the risk favourably: `applyRoutingPolicyPrecedence` already implements a precedence chain and its Javadoc explicitly anticipates being extended with further defaults. Task 4 now extends that chain instead of inventing a method, which is both smaller and closer to the code's own intent.

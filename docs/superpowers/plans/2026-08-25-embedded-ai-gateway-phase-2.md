# Embedded AI Gateway — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the connected user the unit of gateway configuration — its own routing policy, its own budget, optionally its own model credentials — and require every embedded request to say whose behalf it is on.

**Architecture:** Three additions that share one mechanism. The embedded endpoint resolves a connected user id from the authenticated principal and passes it to the facade as a parameter; Phase 2 hangs a scoping column off that id on three tables (`ai_gateway_routing_policy`, `ai_gateway_provider`, `ai_gateway_spend_summary`) and prepends one step to the resolution chain. *(Was "four additions"; the fourth was header enforcement — see the pre-flight below.)*

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, JUnit 5, AssertJ, Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-25-embedded-ai-gateway-phase-2-design.md`

**Prerequisite:** Phase 1 must be landed. This plan consumes `AiGatewayEmbeddedSettings` and `getDefaultRoutingPolicies()` as existing code. If either is absent, stop — executing this against a missing Phase 1 produces a resolution chain with a hole in the middle.

**Revised 2026-09-02.** An earlier version also listed `ConnectedUserResolver` as a prerequisite and told you to stop if it was absent. That SPI has since been **deleted deliberately**: embedded traffic moved to its own endpoint where identity comes from authentication, so the seam it provided is no longer needed (embedded design spec §5, ⚑8). Its absence is the expected state, not a missing prerequisite — do not stop on it, and do not recreate it. ⚑3's header-enforcement work is likewise dropped: with a dedicated endpoint there is no header to enforce.

## Pre-flight, 2026-09-03

Every symbol, signature and precondition in this plan was checked against the tree before execution. Findings, in the order an implementer meets them.

**Still true, re-verified:**

- The gateway schema is unreleased — `ai_gateway_init` is absent from `v0.31.4` (latest release tag by date). Task 1 may edit init in place. Task 1 Step 1 re-checks anyway; keep it.
- All three domain classes exist and carry `workspaceId` to copy the accessor style from.
- `AiGatewayRoutingPolicyRepository` and `AiGatewayRoutingPolicyService` are in `platform-ai-gateway-api`, as Task 2 states.
- `AiGatewaySpendRollupJob` is at the path Task 6 gives, and it rolls up from `AiLlmUsage`.
- `ProjectBeforeDeleteEventListener` exists as the pattern for Task 3.
- Task 6's claims all hold: `AiGatewayProvider.apiKey` is an `EncryptedStringWrapper`, `AiGatewayEmbeddingModelFactoryImpl.getEmbeddingModel(AiGatewayProvider)` is the entry point, and it **already** calls `AiObservabilityUrlValidator.validateExternalUrl(baseUrl)`. So "run through the same factory path" is not aspirational — the guard is there, and Task 6 only has to avoid bypassing it.

**Task 4 is struck.** See below.

**Corrections an implementer would otherwise hit as a compile error:**

- **Task 2's test snippet does not compile.** It calls `connectedUserResolver.resolve(...)` — that SPI is deleted, and no class named `ConnectedUserResolver` exists — and `aiGatewayFacade.resolveRoutingPolicy(...)`, which never existed (zero occurrences). The real seam is `applyRoutingPolicyPrecedence(request, connectedUserId, environmentId)`, private, reached by calling `chatCompletion`/`chatCompletionStream` with a `connectedUserId` argument. Rewrite the snippet against that; the *intent* — including the `verify(embeddedSettingsService, never())` that pins short-circuiting — is unchanged and still the point of the task.
- **Task 3 throws `AiGatewayConnectedUserNotFoundException`**, which was deleted in the separate-endpoint rework; only a past-tense Javadoc mention survives. Either reinstate it for the binding API or use the existing not-found exception its neighbours throw. Whichever you pick, the requirement stands: a cross-tenant id must be indistinguishable from a missing one.
- **Task 5's `AiGatewayBudgetExceededException` does not exist.** The class is `BudgetExceededException`, in `platform-ai-gateway-api/.../domain/`, and the exception handler already maps it to 402.
- **Task 5's facade call `chatCompletion(request, "customer-1", PlatformType.EMBEDDED)` has the wrong shape.** The signature is `chatCompletion(request, tracingHeaders, promptHeaders, connectedUserId)`. There is no external-user-id string and no `PlatformType` parameter.

**One risk closed, not carried forward.** The self-review's remaining open risk was that the usage record feeding the rollup might not carry a resolved connected user id, which would have made Task 5 a re-scope. It does carry it: `AiGatewayFacadeImpl` calls `requestLog.setUserId(connectedUserId)` at three sites, `requestLog` is an `AiLlmUsage`, and `AiGatewaySpendRollupJob` reads `AiLlmUsage`. Task 5 can populate the summary from `AiLlmUsage.getUserId()`. Do not derive it from `apiKeyId` — that prohibition stands.

**One thing that became true after this plan was written.** Sync and streaming now share a single `applyRoutingPolicyPrecedence`. Task 2 says "prepend the step in the facade's resolution method", singular — correct today, and it was not when written, since there were two divergent chains.

---

## Global Constraints

- **Enterprise licence header + `@version ee` Javadoc tag on every new file.** Spotless selects the header from the `@version ee` tag in the content, not the path.
- **Never add a `PlatformType` value.** It is `AUTOMATION, EMBEDDED`, platform-wide, persisted as an INT ordinal and switched on across the codebase. A task that finds itself editing that enum has misread the spec. *(This constraint previously pointed at spec §6's enforcement, which is struck; the prohibition on touching the enum stands on its own.)*
- **Never attribute spend from `apiKeyId`.** It is present on `AiGatewaySpendSummary` and would appear to work. Spec §3.3 and ⚑3 explain why it silently encodes a different product model. Use the resolved connected user id.
- **Nullable `Long`, never primitive**, for every scope field. Null is a real state.
- **The gateway schema is unreleased** — `platform-ai-gateway` does not appear in `v0.31.4`, verified 2026-08-25 — so `00000000000001_ai_gateway_init.xml` may be edited in place rather than gaining new changesets. Task 1 re-verifies before relying on it.
- **In-place init edits break local dev databases two ways** (schema drift and stale md5sums). `scripts/dev/sync-local-schema-after-collapse.sh` patches both, idempotently. **All worktrees on this machine share one Postgres**, so announce before running it and do not start another worktree's migration until it has been applied.
- **Automation behaviour must not change.** Every task's tests include an assertion covering automation traffic, which reaches the facade with a null `connectedUserId`. *(Was phrased as "an automation-type API key without the header still succeeds"; there is no header any more — see the pre-flight.)*
- **BYOK `baseUrl` must pass `AiObservabilityUrlValidator.validateExternalUrl`.** A customer-supplied URL is strictly more hostile than a tenant-supplied one; skipping the guard is a vulnerability, not an omission.
- **Checkstyle:** test method names camelCase without underscores, for every method in test sources. Empty blocks forbidden. `TODO:` comments forbidden.
- **Run `./gradlew spotlessApply` before every commit.** Never judge a Gradle run through a pipe — redirect to a file, check `$?` on its own line, grep for `^> Task .* FAILED`.
- **Commit prefix:** `embedded-gateway-p2`.

---

## Task 1: Schema and domain scoping

**Files:**
- Modify: `platform-ai-gateway-service/src/main/resources/config/liquibase/changelog/platform/ai/gateway/00000000000001_ai_gateway_init.xml`
- Modify: `platform-ai-gateway-api/.../domain/AiGatewayRoutingPolicy.java`, `.../domain/AiGatewayProvider.java`, `.../domain/AiGatewaySpendSummary.java`
- Test: `platform-ai-gateway-service/src/test/.../AiGatewayScopingIntTest.java`

**Interfaces:**
- Produces: `getConnectedUserId()` / `setConnectedUserId(@Nullable Long)` on all three domain classes.

- [ ] **Step 1: Re-verify the schema is unreleased**

```bash
t=$(git tag --sort=-creatordate --list 'v*' | head -1); echo "latest release tag: $t"
git ls-tree -r --name-only "$t" | grep -c 'ai_gateway_init'
```

Expected: `0`. If it is non-zero the schema shipped since this plan was written — **stop editing init** and add new changesets instead, per the repo's rule about never rewriting what customers have run.

- [ ] **Step 2: Add the columns and constraints to the init changelog**

Three columns, all `BIGINT` nullable with no default:

- `ai_gateway_routing_policy.connected_user_id` — plus a **partial unique** index on `connected_user_id WHERE connected_user_id IS NOT NULL` (spec ⚑4), and a check constraint `workspace_id IS NULL OR connected_user_id IS NULL`.

  There is deliberately **no environment column and no composite key**: the gateway schema has none, and `ConnectedUser` already carries environment, so the same external id in two environments is two connected user rows with two ids. Do not add one (spec ⚑8).
- `ai_gateway_provider.connected_user_id` — plus the same check constraint.
- `ai_gateway_spend_summary.connected_user_id` — plus an index supporting the existing period queries.

Match the surrounding changeset style in that file exactly — author attribute, id convention, and whether constraints are declared inline or as separate changesets. Read it before writing.

- [ ] **Step 3: Add the domain fields**

On each of the three classes, beside the existing `workspaceId`:

```java
    @Nullable
    private Long connectedUserId;
```

with the accessor style already used for `workspaceId` in that file. Never a primitive.

- [ ] **Step 4: Write the integration test**

An `IntTest`, because two of the three guarantees are database constraints and cannot be tested any other way:

- a policy with both `workspace_id` and `connected_user_id` set is rejected by the check constraint;
- a second policy for the same `connected_user_id` is rejected by the partial unique index;
- two policies bound to two *different* connected users both insert — the partial index must not over-constrain. Because connected users are per-environment, this case also covers "the same customer in two environments";
- an existing row with both columns null still inserts, proving the constraint permits the default tier.

- [ ] **Step 5: Run it, sync local schema, commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:testIntegration > /tmp/p2-t1.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-t1.log; echo "(empty = green)"
```

Testcontainers builds the schema from scratch, so this is the real verification. Docker must be running; on this machine Testcontainers needs the OrbStack socket, since `/var/run/docker.sock` is a dangling symlink.

Then, and only after telling the user because the dev Postgres is shared across worktrees:

```bash
./scripts/dev/sync-local-schema-after-collapse.sh
```

```bash
./gradlew spotlessApply > /tmp/p2-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs/platform/platform-ai/platform-ai-gateway
git commit -m "embedded-gateway-p2 Add connected user scoping columns and constraints"
```

---

## Task 2: Resolution step 1

**Files:**
- Modify: `platform-ai-gateway-api/.../repository/AiGatewayRoutingPolicyRepository.java`, `.../service/AiGatewayRoutingPolicyService.java`
- Modify: `platform-ai-gateway-service/.../service/AiGatewayRoutingPolicyServiceImpl.java`
- Modify: `automation-ai-gateway-service/.../facade/AiGatewayFacadeImpl.java`
- Test: the facade and service tests from Phase 1

**Interfaces:**
- Produces: `AiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(long connectedUserId)` → `Optional<AiGatewayRoutingPolicy>`.

- [ ] **Step 1: Write the failing tests**

Extend Phase 1's facade test with the full chain:

```java
@Test
void testConnectedUserPolicyWinsOverTheEmbeddedDefault() {
    when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(5L))
        .thenReturn(Optional.of(connectedUserPolicy));

    aiGatewayFacade.chatCompletion(request, null, null, 5L);

    verify(embeddedSettingsService, never()).find(anyLong());
}

@Test
void testDisabledConnectedUserPolicyFallsThroughToTheEmbeddedDefault() {
    connectedUserPolicy.setEnabled(false);

    when(aiGatewayRoutingPolicyService.fetchRoutingPolicyByConnectedUserId(5L))
        .thenReturn(Optional.of(connectedUserPolicy));
    when(embeddedSettingsService.find(ENVIRONMENT_ID))
        .thenReturn(Optional.of(
            new AiGatewayEmbeddedSettings(ENVIRONMENT_ID, null, null, null, null, null, 42L, null)));

    aiGatewayFacade.chatCompletion(request, null, null, 5L);

    verify(aiGatewayRoutingPolicyService).getRoutingPolicy(42L);
}
```

*(Rewritten 2026-09-03. The original snippet stubbed a `connectedUserResolver` that no longer exists and asserted
on a `resolveRoutingPolicy` method that never did. Resolution is not directly callable — it is private, reached by
calling the facade with a `connectedUserId` — so these assert through the facade and verify which level was
consulted, the shape Phase 1's own tests already use.)*

The `verify(..., never())` in the first test is the point: step 2 must not be consulted once step 1 resolves. Without it, a chain that evaluates every level and picks the first non-null would pass while doing needless work on every request.

Phase 1's existing fall-through tests must still pass unchanged — do not edit them.

- [ ] **Step 2: Implement**

Repository method, derived by Spring Data from its name:

```java
    Optional<AiGatewayRoutingPolicy> findByConnectedUserId(long connectedUserId);
```

No environment parameter: `AiGatewayRoutingPolicy` has no environment field and the gateway schema has no environment column — re-verified 2026-09-03. Environment arrives through the connected user id, which is already per-environment (spec §4, ⚑8).

**Decide where the new step sits, and say so in the commit.** This plan describes a three-step chain, but the chain in the code already has three levels of its own: request-specified → the model's `defaultRoutingPolicyId` → the embedded default. Prepending the connected-user policy makes four, and the plan never says whether it outranks the *model* default or only the embedded one. Both readings are defensible — a customer's own policy arguably beats a model-level default, but a model default is a deliberate operator choice about that specific model. Pick one, pin it with a `never()` assertion the way the tests above do, and record the reasoning; do not leave it to fall out of statement order.

Prepend the step in the facade's resolution method. Keep the three levels as three plainly readable branches rather than a stream chain; this is the code someone will read while debugging a wrong-model incident.

- [ ] **Step 3: Run, format, commit**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:test :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:test --continue > /tmp/p2-t2.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-t2.log; echo "(empty = green)"
./gradlew spotlessApply > /tmp/p2-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway-p2 Resolve the connected user policy first"
```

---

## Task 3: Binding management

**Files:**
- Modify: the routing policy API facade and its interface (both, per the repo's API-facade rule)
- Create: a `ConnectedUserBeforeDeleteEventListener` in the embedded tree
- Test: facade authorization test + a delete-cascade test

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testBindRejectsAPolicyFromAnotherTenant() {
    assertThatThrownBy(() -> aiGatewayRoutingPolicyApiFacade.bind(OTHER_TENANT_POLICY_ID, CONNECTED_USER_ID))
        .isInstanceOf(AiGatewayRoutingPolicyNotFoundException.class);
}

@Test
void testBindRejectsAConnectedUserFromAnotherTenant() {
    assertThatThrownBy(() -> aiGatewayRoutingPolicyApiFacade.bind(POLICY_ID, OTHER_TENANT_CONNECTED_USER_ID))
        .isInstanceOf(AiGatewayConnectedUserNotFoundException.class);
}

@Test
void testDeletingAConnectedUserUnbindsButKeepsThePolicy() {
    connectedUserService.deleteConnectedUser(CONNECTED_USER_ID);

    AiGatewayRoutingPolicy policy = aiGatewayRoutingPolicyService.getRoutingPolicy(POLICY_ID);

    assertThat(policy).isNotNull();
    assertThat(policy.getConnectedUserId()).isNull();
}
```

The first two must throw the *not-found* exception, not a forbidden one — a cross-tenant id must be indistinguishable from a missing one, or the error message becomes an enumeration oracle.

- [ ] **Step 2: Implement**

Add `bind`/`unbind` to **both** the API facade interface and the shared facade interface if the shared one is used at runtime — per the repo rule, adding to only one silently removes the ownership check for one caller. Guard the API facade with the same `checkOwnerOrAdmin`-style expression its neighbours use; read one before writing.

For the unbind-on-delete, follow the project's `*BeforeDeleteEventListener` pattern rather than an FK cascade — spec §5. Look at `ProjectBeforeDeleteEventListener` for the shape.

- [ ] **Step 3: Run, format, commit**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check --continue > /tmp/p2-t3.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-t3.log; echo "(empty = green)"
./gradlew spotlessApply > /tmp/p2-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway-p2 Add connected user policy binding management"
```

---

## ~~Task 4: Header enforcement~~ — STRUCK 2026-09-03

**Do not execute this task. It is kept here, struck rather than deleted, so the reversal stays legible.**

This task made `X-ByteChef-External-User-Id` mandatory for embedded-type API keys, behind an enforcement
setting that shipped off. It rested on ⚑3: embedded traffic shared the automation gateway's endpoint, the
caller named its customer in a header, and a caller that forgot got tenant-default routing and wrong billing
attribution — silently. Enforcement was the fix for that silence.

Embedded traffic now has its own endpoint,
`POST /api/embedded/v1/{externalUserId}/ai-gateway/chat/completions`, where the identity is a path parameter
checked against the authenticated principal. **There is no header, so there is nothing to enforce, and the
misconfiguration this task existed to catch is not expressible.** See the embedded design spec §5 and ⚑8/⚑9.

Struck along with it: `AiGatewayExternalUserIdRequiredException` (never created — the spec's §11 400 code has
no remaining trigger), the enforcement flag on `AiGatewayEmbeddedSettings`, and threading `PlatformType` into
`chatCompletion`. Do not add any of them.

What genuinely carried over is already done: an unknown or disabled connected user is rejected with **403**,
on both the JSON and streaming mappings, and never auto-created.

The global constraint "every task's tests include an assertion that an automation-type API key without the
header still succeeds" is likewise moot as written. Its intent survives and still binds: **Tasks 1, 2, 3, 5
and 6 must not change automation behaviour**, and each should carry an assertion to that effect — now phrased
as automation traffic passing a null `connectedUserId`, which is what the automation path actually does.

---

## Task 5: Spend attribution and budgets

**Files:**
- Modify: `automation-ai-gateway-service/.../spend/AiGatewaySpendRollupJob.java`
- Modify: the spend summary repository and `AiGatewaySpendServiceImpl`
- Modify: `AiGatewayEmbeddedSettings` (+ per-connected-user default cap)
- Test: rollup test + a budget enforcement test

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testRollupAttributesSpendToTheResolvedConnectedUser() {
    aiGatewaySpendRollupJob.rollup();

    AiGatewaySpendSummary summary = capturedSummary();

    assertThat(summary.getConnectedUserId()).isEqualTo(5L);
}

@Test
void testRollupDoesNotDeriveTheConnectedUserFromTheApiKey() {
    usage.setApiKeyId(99L);
    usage.setConnectedUserId(null);

    aiGatewaySpendRollupJob.rollup();

    assertThat(capturedSummary().getConnectedUserId()).isNull();
}

@Test
void testRequestOverCapIsRejectedRatherThanDowngraded() {
    when(spendService.getSpendForConnectedUser(5L, ENVIRONMENT_ID)).thenReturn(new BigDecimal("100.00"));

    assertThatThrownBy(() -> aiGatewayFacade.chatCompletion(request, null, null, 5L))
        .isInstanceOf(BudgetExceededException.class);

    verify(aiGatewayRoutingPolicyService, never()).getDefaultRoutingPolicies();
}
```

The second test pins spec ⚑3 — attribution must not fall back to `apiKeyId` when the connected user is absent, because that would silently redefine "customer". The third's `never()` pins ⚑5: over budget rejects, it does not quietly reroute to a cheaper tier.

- [ ] **Step 2: Implement, run, commit**

Populate `connectedUserId` on the summary from the resolved identity carried on the usage record. Add the cap to the settings record as another nullable field, and check it before routing rather than after — a rejected request should not consume a routing decision or an LLM call.

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check --continue > /tmp/p2-t5.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-t5.log; echo "(empty = green)"
./gradlew spotlessApply > /tmp/p2-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway-p2 Attribute spend per connected user and enforce caps"
```

---

## Task 6: BYOK — per-customer provider credentials

**Files:**
- Modify: `platform-ai-gateway-api/.../service/AiGatewayProviderService.java` and its impl
- Modify: `AiGatewayEmbeddingModelFactoryImpl` and the chat model factory call sites, if provider selection lives there
- Test: provider resolution tests + an SSRF guard test

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testConnectedUserProviderWinsOverTheTenantProvider() {
    when(aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(5L, OPENAI))
        .thenReturn(Optional.of(customerProvider));

    assertThat(aiGatewayProviderResolver.resolve(5L, OPENAI)).isEqualTo(customerProvider);
}

@Test
void testFallsBackToTheTenantProviderWhenTheCustomerHasNone() {
    when(aiGatewayProviderService.fetchProviderByConnectedUserIdAndType(5L, OPENAI))
        .thenReturn(Optional.empty());

    assertThat(aiGatewayProviderResolver.resolve(5L, OPENAI)).isEqualTo(tenantProvider);
}

@Test
void testCustomerSuppliedBaseUrlIsValidated() {
    customerProvider.setBaseUrl("http://169.254.169.254/latest/meta-data/");

    assertThatThrownBy(() -> aiGatewayEmbeddingModelFactory.getEmbeddingModel(customerProvider))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void testCredentialsAreNeverReturnedByAReadPath() {
    assertThat(aiGatewayProviderApiFacade.getProvider(PROVIDER_ID).apiKey()).isNull();
}
```

The third test uses the cloud metadata endpoint deliberately — that is the canonical SSRF target, and a customer-supplied `baseUrl` is exactly the untrusted input the existing `AiObservabilityUrlValidator` guard exists for.

- [ ] **Step 2: Implement**

Add the scoping column's service and repository methods mirroring the policy ones from Task 2. Resolution is customer-then-tenant, and it must run through the **same** factory path as tenant providers so the SSRF validation and credential decryption are not duplicated or bypassed.

Do not add a new encryption surface — `AiGatewayProvider.apiKey` is already an `EncryptedStringWrapper`.

- [ ] **Step 3: Run, format, commit**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:check --continue > /tmp/p2-t6.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-t6.log; echo "(empty = green)"
./gradlew spotlessApply > /tmp/p2-spotless.log 2>&1; echo "exit=$?"
git add server/ee/libs
git commit -m "embedded-gateway-p2 Resolve per connected user provider credentials"
```

---

## Task 7: End-to-end integration and repair

**Files:**
- Test: `automation-ai-gateway-service/src/test/.../EmbeddedAiGatewayPhase2IntTest.java`
- Modify: any `*IntTestConfiguration` / `@TestConfiguration` broken by Tasks 2–6

- [ ] **Step 1: Repair hand-assembled test contexts**

Tasks 2, 5 and 6 each added constructor collaborators to scanned `@Service` implementations, which breaks other modules' `@SpringBootTest(classes=...)` contexts with missing-bean errors.

```bash
grep -rln "AiGatewayFacadeImpl\|AiGatewayProviderServiceImpl\|AiGatewaySpendRollupJob" --include='*IntTestConfiguration.java' --include='*TestConfiguration.java' server
```

Add mock `@Bean`s to every file that turns up.

- [ ] **Step 2: Write the end-to-end integration test**

Against a real database, exercising what unit tests cannot:

- the full three-step chain, with a connected-user policy, an embedded default, and a system default all present, asserting each level wins in turn as the one above is removed or disabled;
- BYOK: a connected user's provider is chosen over the tenant's;
- spend rows carry the connected user, and rows written before the change keep null;
- cross-tenant ids are indistinguishable from missing for policy, provider and spend.

- [ ] **Step 3: Full check**

```bash
./gradlew :server:ee:libs:platform:platform-ai:platform-ai-gateway:platform-ai-gateway-service:check :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:check --continue > /tmp/p2-check.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-check.log; echo "(empty = green)"
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:testIntegration > /tmp/p2-int.log 2>&1; echo "exit=$?"
grep "^> Task .* FAILED" /tmp/p2-int.log; echo "(empty = green)"
```

Read SpotBugs findings from `build/reports/spotbugs/*.html` — the XML report is disabled in this project and never rewritten, so a stale XML will lie.

- [ ] **Step 4: Format and commit**

```bash
./gradlew spotlessApply > /tmp/p2-spotless.log 2>&1; echo "exit=$?"
git add server
git commit -m "embedded-gateway-p2 Add phase 2 integration coverage"
```

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| §4 column, indexes, check constraint | Task 1 |
| §4 three-step resolution | Task 2 |
| §5 binding management, scope-resolved ids | Task 3 |
| §5 unbind on connected user delete | Task 3 |
| ~~§6 enforcement keyed on `PlatformType.EMBEDDED`~~ | ~~Task 4~~ — struck, no header exists |
| §7 spend column, not from `apiKeyId` | Task 5 |
| §7 cap rejects rather than downgrades | Task 5 |
| §8 BYOK scoping, fallback, SSRF guard | Task 6 |
| §9 isolation | Tasks 3, 6, 7 |
| §11 error codes — 403 unknown id (the 400 had only the struck enforcement as its trigger) | done in phase 1 |
| §12 testing | Tasks 1–7 |
| ~~§13 enforcement ships off behind a setting~~ | ~~Task 4~~ — struck |

**Not covered, deliberately:** §11's two known gaps — BYOK cost figures being estimates, and Anthropic BYOK being unable to serve embedding-based scoring — are documented limitations, not work items. Neither has an implementation task because neither has a fix within this scope.

**Placeholder scan:** no TBD, TODO, or "similar to Task N". Several steps say "read file X and match its style" rather than reproducing code — the changeset style in the init changelog, the accessor style on the domain classes, the guard expression on neighbouring API facade methods, and the `*BeforeDeleteEventListener` shape. In each case the file is named and the property to copy is stated; reproducing those here would duplicate code the repo owns and rot against it.

**Type consistency**, as corrected by the 2026-09-03 pre-flight: `connectedUserId` is `@Nullable Long` on all three domain classes (Task 1) and consumed as `Long` throughout. `fetchRoutingPolicyByConnectedUserId(long)` → `Optional<AiGatewayRoutingPolicy>` is declared in Task 2 and used in Tasks 3 and 7. The `PlatformType`-threading claim is withdrawn with Task 4. Task 5 throws the existing `BudgetExceededException`, already mapped to 402, rather than a new one; `AiGatewayExternalUserIdRequiredException` is not created at all.

**A risk that was flagged and then closed during self-review.** An earlier draft assumed `AiGatewayRoutingPolicy` carried an `environment` field and specified `(connected_user_id, environment)` keys. It does not — the gateway schema has no environment column anywhere. Rather than leaving that as a caveat for the implementer, it was checked and resolved: environment scoping is inherited through the connected user id, which is already per-environment, so `connected_user_id` alone is the correct key. Tasks 1 and 2 and spec §4 were corrected, and spec ⚑8 records the decision not to add the column.

**~~One risk that remains open:~~ Closed 2026-09-03 by the pre-flight.** Task 5 assumed the usage record feeding `AiGatewaySpendRollupJob` might not carry a resolved connected user id, and told the implementer to stop and re-scope if so. It does carry it — `AiGatewayFacadeImpl` sets it on the `AiLlmUsage` at three sites and the rollup reads `AiLlmUsage` — so Task 5 proceeds as written. The prohibition on deriving it from `apiKeyId` (⚑3) is unaffected and still binds.

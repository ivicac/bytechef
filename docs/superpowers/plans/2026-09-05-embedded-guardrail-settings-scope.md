# Embedded guardrail settings scope — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the seven inert controls on the embedded guardrails settings page actually govern embedded model calls, by giving the runtime read path a way to express the `EMBEDDED` settings scope it already stores but can never resolve.

**Architecture:** A new `AiGuardrailsSettingsTarget` record carries `(scope, workspaceId)` in place of the bare `@Nullable Long workspaceId` that `AiGuardrails`'s eleven settings-reading methods take today. `AiGuardrailsAdvisorProviderImpl` — which already holds `platformType` and currently discards it — builds the target, so an embedded run resolves `EMBEDDED` instead of collapsing to the tenant-default `PLATFORM` row. `AiGuardrails#findSettings` gains the one branch that was missing, dispatching to the already-existing `fetchEmbeddedSettings()`. The shared `JobPrincipalWorkspaceResolver` is deliberately untouched.

**Tech Stack:** Java 25, Spring Boot 4, Spring AI advisors, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-05-embedded-guardrail-settings-scope-design.md`

## Global Constraints

- **`JobPrincipalWorkspaceResolver` must not change** (spec D6). It lives in `platform-ai-workspace` and serves `WorkspaceSystemPromptAdvisorProviderImpl` and `ComponentRuleEnforcerImpl` as well as guardrails; neither of those has an embedded scope. Its `null` for an embedded run is the honest answer to the question it asks.
- **`resolveEmbeddedMcpOutboundPolicy` keeps its own read and its fail-CLOSED behaviour** (spec D3). Every other settings read here is fail-open. A lookup failure during a `tools/call` must end in a tool error, never in raw customer records with `isError` false.
- **Fail-open is preserved on every branch of `findSettings`** — no row, an unparseable row, or a thrown lookup all mean "no workspace override", never "guardrails off" and never an exception on the request path.
- **`EMBEDDED` never falls back to `PLATFORM`** (spec D2). A null field on an embedded row unions with the GLOBAL `bytechef.ai.gateway.guardrails.*` properties, exactly as a `WORKSPACE` row's does.
- **Pre-existing embedded rows take effect immediately** (spec D4) — no marker column, no re-save requirement, no forced observe mode.
- Every file under `server/ee/` carries the **ByteChef Enterprise license header** (not Apache 2.0) and a `@version ee` Javadoc tag. Copy both from a sibling in the same directory. Everything this plan touches is under `server/ee/`.
- Enum ordinals are persisted as INT — append new values at the end, never reorder. (`AiGuardrailsSettingsScope` is persisted by `name()`, but the append-only rule still applies.)
- Run `./gradlew spotlessApply` before every commit.
- Judge a Gradle run by `$?` on its own line plus `grep '^> Task .* FAILED'` on a redirected log file — never by a piped `tail`, whose exit code is the filter's.
- Gradle needs `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce`; shells here do not source SDKMAN. Bash calls need `timeout: 600000`.
- Commit messages: `732 <description>` for server, `732 docs - <description>` for documentation.
- **Never amend, never `git reset`, never `git stash`, never `git branch -f`** — the maintainer commits in parallel on this repo and the stash stack is shared across worktrees. Fix a bad commit with a follow-up commit.
- Stage **by path**, never `git add -A` or `git add .`.

---

### Task 1: The settings target

**Files:**
- Create: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/main/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsSettingsTarget.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api/src/test/java/com/bytechef/ee/platform/ai/guardrails/domain/AiGuardrailsSettingsTargetTest.java` (create)

**Interfaces:**
- Consumes: `AiGuardrailsSettingsScope` (existing enum in the same package), `com.bytechef.platform.constant.PlatformType`.
- Produces: `AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope scope, @Nullable Long workspaceId)` with static factories `workspace(long)`, `platform()`, `embedded()`, and `resolve(@Nullable PlatformType, @Nullable Long)`.

- [ ] **Step 1: Write the failing test**

```java
class AiGuardrailsSettingsTargetTest {

    @Test
    void testWorkspaceCarriesItsId() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.workspace(7L);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.WORKSPACE);
        assertThat(target.workspaceId()).isEqualTo(7L);
    }

    @Test
    void testPlatformAndEmbeddedCarryNoWorkspace() {
        assertThat(AiGuardrailsSettingsTarget.platform().workspaceId()).isNull();
        assertThat(AiGuardrailsSettingsTarget.embedded().workspaceId()).isNull();
    }

    @Test
    void testAWorkspaceScopeWithoutAnIdIsRefused() {
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.WORKSPACE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testANonWorkspaceScopeWithAnIdIsRefused() {
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.EMBEDDED, 7L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.PLATFORM, 7L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testANullScopeIsRefused() {
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testResolveMapsAnEmbeddedRunToTheEmbeddedScope() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(PlatformType.EMBEDDED, null);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
    }

    @Test
    void testResolveIgnoresAWorkspaceIdOnAnEmbeddedRun() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(PlatformType.EMBEDDED, 7L);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
        assertThat(target.workspaceId()).isNull();
    }

    @Test
    void testResolveMapsAResolvedWorkspaceToTheWorkspaceScope() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(PlatformType.AUTOMATION, 7L);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.WORKSPACE);
        assertThat(target.workspaceId()).isEqualTo(7L);
    }

    @Test
    void testResolveMapsAnUnresolvedWorkspaceToThePlatformScope() {
        assertThat(AiGuardrailsSettingsTarget.resolve(PlatformType.AUTOMATION, null).scope())
            .isEqualTo(AiGuardrailsSettingsScope.PLATFORM);
        assertThat(AiGuardrailsSettingsTarget.resolve(null, null).scope())
            .isEqualTo(AiGuardrailsSettingsScope.PLATFORM);
    }
}
```

`testResolveIgnoresAWorkspaceIdOnAnEmbeddedRun` is the one that matters most: `resolve` must not construct a `WORKSPACE` target for an embedded run just because a non-null id happened to be passed. Embedded has no workspaces, and honouring an id there would resolve some automation tenant's row for an embedded caller.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:test --tests '*AiGuardrailsSettingsTargetTest' > /tmp/e1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e1.log
```

Expected: FAIL — the class does not exist.

- [ ] **Step 3: Write the record**

EE license header (copy from `AiGuardrailsWorkspaceSettings.java` in the same package) and a `@version ee` tag.

```java
public record AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope scope, @Nullable Long workspaceId) {

    public AiGuardrailsSettingsTarget {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }

        if ((scope == AiGuardrailsSettingsScope.WORKSPACE) != (workspaceId != null)) {
            throw new IllegalArgumentException(
                "workspaceId must be non-null exactly when scope is WORKSPACE, got scope=" + scope +
                    ", workspaceId=" + workspaceId);
        }
    }

    public static AiGuardrailsSettingsTarget workspace(long workspaceId) {
        return new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.WORKSPACE, workspaceId);
    }

    public static AiGuardrailsSettingsTarget platform() {
        return new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.PLATFORM, null);
    }

    public static AiGuardrailsSettingsTarget embedded() {
        return new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.EMBEDDED, null);
    }

    public static AiGuardrailsSettingsTarget resolve(
        @Nullable PlatformType platformType, @Nullable Long workspaceId) {

        if (platformType == PlatformType.EMBEDDED) {
            return embedded();
        }

        return workspaceId == null ? platform() : workspace(workspaceId);
    }
}
```

The invariant is copied deliberately from `AiGuardrailsWorkspaceSettings`'s compact constructor rather than invented — the two types describe the same three-scope model, and a target that could not address a settings row would be useless.

Class Javadoc must say why the type exists, since a bare `Long` looks sufficient until you know the history:

```java
/**
 * Which settings row a guardrail decision should read: the tenant default, one workspace's, or the embedded
 * deployment's.
 *
 * <p>
 * Replaces the bare {@code @Nullable Long workspaceId} that every settings-reading method here used to take.
 * That parameter could not express the third case: {@code null} meant both "an automation run with no
 * resolvable workspace" and "an embedded run", and both resolved the tenant-default {@code PLATFORM} row. The
 * embedded settings page has written an {@code EMBEDDED} row since it shipped, and nothing on the agent path
 * could ever read it -- seven of that page's eight controls did nothing at all.
 * </p>
 *
 * <p>
 * {@link #resolve(PlatformType, Long)} is the only place that interpretation lives. It deliberately ignores a
 * non-null {@code workspaceId} on an embedded run: embedded has no workspaces, so honouring one would resolve
 * some automation tenant's row for an embedded caller.
 * </p>
 *
 * @version ee
 */
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-api:test --tests '*AiGuardrailsSettingsTargetTest' > /tmp/e1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e1.log
```

Expected: PASS, all nine.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-api
git commit -m "732 Add a settings target that can address the embedded scope"
```

---

### Task 2: Dispatch on the target, behaviour unchanged

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsSettingsScopeDispatchTest.java` (create)

**Interfaces:**
- Consumes: `AiGuardrailsSettingsTarget` and its factories (Task 1).
- Produces: every one of `AiGuardrails`'s eleven public settings-reading methods gains an `AiGuardrailsSettingsTarget`-taking form. The existing `@Nullable Long` forms remain, delegating via `AiGuardrailsSettingsTarget.resolve(null, workspaceId)`, so no caller changes yet. Task 3 removes them.

The eleven methods, by line number in the current file: `applyToInputs` (348), `scanResponseText` (589), `newStreamingResponseRedactor` (637), `isViolationRecordingEnabled` (781), `resolveBlockingMode` (792), `resolveMinConfidence` (813), `resolveToolBoundaryPolicy` (836 and 856 — two arities), `isRestoreIntoWorkflowOutput` (877), `resolveMcpOutboundPolicy` (951), `isActive` (995).

- [ ] **Step 1: Write the failing test**

```java
class AiGuardrailsSettingsScopeDispatchTest {

    @Test
    void testAnEmbeddedTargetReadsTheEmbeddedRow() {
        // settings service: fetchEmbeddedSettings() -> a row with redactPii = false
        //                   fetchSettings(any())    -> a row with redactPii = true
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        assertThat(aiGuardrails.isActive(AiGuardrailsSettingsTarget.embedded())).isTrue();

        verify(aiGuardrailsWorkspaceSettingsService).fetchEmbeddedSettings();
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchSettings(any());
    }

    @Test
    void testAPlatformTargetReadsTheTenantDefaultRowAndNotTheEmbeddedOne() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        aiGuardrails.isActive(AiGuardrailsSettingsTarget.platform());

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(null);
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchEmbeddedSettings();
    }

    @Test
    void testAWorkspaceTargetReadsThatWorkspacesRow() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        aiGuardrails.isActive(AiGuardrailsSettingsTarget.workspace(7L));

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(7L);
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchEmbeddedSettings();
    }

    @Test
    void testAnEmbeddedLookupFailureStillFailsOpen() {
        // fetchEmbeddedSettings() throws
        AiGuardrails aiGuardrails = aiGuardrailsWithThrowingEmbeddedRow();

        assertThatCode(() -> aiGuardrails.isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget.embedded()))
            .doesNotThrowAnyException();
        assertThat(aiGuardrails.isRestoreIntoWorkflowOutput(AiGuardrailsSettingsTarget.embedded())).isTrue();
    }

    @Test
    void testTheLongFormStillResolvesExactlyAsBefore() {
        AiGuardrails aiGuardrails = aiGuardrailsWithBothRows();

        aiGuardrails.isActive((Long) null);

        verify(aiGuardrailsWorkspaceSettingsService).fetchSettings(null);
        verify(aiGuardrailsWorkspaceSettingsService, never()).fetchEmbeddedSettings();
    }
}
```

Both `never()` assertions are load-bearing. A fix that made every target read the embedded row would satisfy a one-directional test, and this is the bug's mirror image — equally silent, equally wrong.

Build the `AiGuardrails` instances with a mocked `AiGuardrailsWorkspaceSettingsService`, copying the `@Mock` set and constructor call from the neighbouring `AiGuardrails*Test` classes in the same package rather than inventing a new construction.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsSettingsScopeDispatchTest' > /tmp/e2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e2.log
```

Expected: FAIL — no target-taking overloads exist.

- [ ] **Step 3: Give `findSettings` the missing branch**

```java
    private @Nullable AiGuardrailsWorkspaceSettings findSettings(AiGuardrailsSettingsTarget target) {
        try {
            Optional<AiGuardrailsWorkspaceSettings> settingsOptional =
                target.scope() == AiGuardrailsSettingsScope.EMBEDDED
                    ? aiGuardrailsWorkspaceSettingsService.fetchEmbeddedSettings()
                    : aiGuardrailsWorkspaceSettingsService.fetchSettings(target.workspaceId());

            return settingsOptional.orElse(null);
        } catch (Exception exception) {
            // A settings lookup failure must not take the request path down; global guardrails still apply.
            log.warn(
                "Failed to load AI guardrails workspace settings for {}: {}", target, exception.getMessage());

            return null;
        }
    }
```

The `catch` stays exactly as broad as it was. The new branch is inside the `try`, so an embedded lookup fails open on the same terms as every other one.

- [ ] **Step 4: Add the target-taking overloads**

For each of the eleven methods, add an overload taking `AiGuardrailsSettingsTarget` and move the body onto it. Turn each existing `@Nullable Long` form into a one-line delegation:

```java
    public boolean isActive(@Nullable Long workspaceId) {
        return isActive(AiGuardrailsSettingsTarget.resolve(null, workspaceId));
    }
```

Passing `null` for the platform type is correct here and not a placeholder: these are the legacy forms, reached only from callers that already resolved a workspace and have no embedded case. Task 3 deletes them.

The private helpers `resolvePolicy` and `resolveCustomRules` take the target too. `resolveCustomRules` keeps passing `target.workspaceId()` to the custom-rule service — custom rules stay workspace-scoped, and an embedded target's null id resolves the tenant-wide set, exactly as today. **Do not make custom rules embedded-scoped; that is a non-goal.**

`resolveEmbeddedMcpOutboundPolicy` is **not** converted — it reads the settings service directly and must keep its fail-closed behaviour (spec D3). Add a comment on it pointing at `findSettings` so its duplication reads as deliberate:

```java
    // Deliberately NOT routed through findSettings/AiGuardrailsSettingsTarget: that path is fail-OPEN by
    // design, and this one must fail closed -- see this method's javadoc.
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --continue > /tmp/e2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e2.log
```

Expected: the new tests pass and the module's existing suite stays green — no caller has changed yet, so a pre-existing test going red means the delegation is not equivalent.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "732 Dispatch guardrail settings reads on an explicit scope target"
```

---

### Task 3: Build the target from the platform type — embedded starts working

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderImpl.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java` (delete the legacy `Long` forms)
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/agent/AiHubSpringAIAgent.java`
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/subagent/WorkspaceAdvisorContributor.java`
- Modify: `server/ee/libs/automation/automation-ai/automation-ai-gateway/automation-ai-gateway-service/src/main/java/com/bytechef/ee/automation/ai/gateway/guardrail/AiGatewayGuardrails.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderScopeTest.java` (create)

**Interfaces:**
- Consumes: `AiGuardrailsSettingsTarget.resolve(PlatformType, Long)` (Task 1); the target-taking methods on `AiGuardrails` (Task 2).
- Produces: `AiGuardrailsAdvisor` carries an `AiGuardrailsSettingsTarget target` field in place of `@Nullable Long workspaceId`. `AiGuardrails`'s `Long`-taking forms no longer exist.

- [ ] **Step 1: Write the failing test**

```java
class AiGuardrailsAdvisorProviderScopeTest {

    @Test
    void testAnEmbeddedRunResolvesTheEmbeddedTarget() {
        provider.getAdvisor(PlatformType.EMBEDDED, 42L, GuardrailSurface.AI_AGENT);

        assertThat(capturedTarget().scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
    }

    @Test
    void testAnAutomationRunWithAWorkspaceResolvesThatWorkspace() {
        // jobPrincipalWorkspaceResolver.resolve(AUTOMATION, 42L) -> 7L
        provider.getAdvisor(PlatformType.AUTOMATION, 42L, GuardrailSurface.AI_AGENT);

        assertThat(capturedTarget()).isEqualTo(AiGuardrailsSettingsTarget.workspace(7L));
    }

    @Test
    void testAnAutomationRunWithoutAWorkspaceResolvesTheTenantDefault() {
        // jobPrincipalWorkspaceResolver.resolve(AUTOMATION, 42L) -> null
        provider.getAdvisor(PlatformType.AUTOMATION, 42L, GuardrailSurface.AI_AGENT);

        assertThat(capturedTarget().scope()).isEqualTo(AiGuardrailsSettingsScope.PLATFORM);
    }

    @Test
    void testGetMetricsResolvesTheSameTargetAsGetAdvisor() {
        provider.getMetrics(PlatformType.EMBEDDED, 42L, GuardrailSurface.AI_AGENT);

        assertThat(capturedActiveCheckTarget().scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
    }
}
```

Capture the target with an `ArgumentCaptor` on the `AiGuardrails#isActive(AiGuardrailsSettingsTarget)` call the provider already makes through `buildMetricsIfActive`.

The fourth test exists because `getAdvisor` and `getMetrics` resolve independently — the file has two separate `jobPrincipalWorkspaceResolver.resolve(...)` calls, and a fix applied to one and not the other would give an embedded run a correctly-scoped advisor and a wrongly-scoped metrics instance, or vice versa.

- [ ] **Step 2: Run the test to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorProviderScopeTest' > /tmp/e3.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e3.log
```

Expected: FAIL — the embedded test resolves `PLATFORM`, which is the bug.

- [ ] **Step 3: Build the target at both provider call sites**

In `AiGuardrailsAdvisorProviderImpl`, at both places that currently do `Long workspaceId = jobPrincipalWorkspaceResolver.resolve(platformType, jobPrincipalId);`:

```java
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(
            platformType, jobPrincipalWorkspaceResolver.resolve(platformType, jobPrincipalId));
```

`buildMetricsIfActive` takes the target instead of the `Long`. `AiGuardrailMetrics` itself is unchanged — it never took a workspace id, only the meter registry and the surface, so metric tags are unaffected.

`getAdvisorForWorkspace(Long workspaceId, String surface)` and `getMetricsForWorkspace` keep their signatures — those are the Copilot and AI-Hub-subagent paths, which have already resolved a real workspace and have no embedded case. They build `AiGuardrailsSettingsTarget.resolve(null, workspaceId)`.

Update the class Javadoc: it currently says the provider resolves "the workspace" of a run. It now resolves which settings row applies, which is a different and larger claim.

- [ ] **Step 4: Carry the target on the advisor**

Replace `AiGuardrailsAdvisor`'s `private final @Nullable Long workspaceId` with `private final AiGuardrailsSettingsTarget target`, and pass it to every `aiGuardrails.*` call in the class. There are five construction sites repo-wide — the two in `AiGuardrailsAdvisorProviderImpl`, plus `AiHubSpringAIAgent`, `WorkspaceAdvisorContributor`, and the guardrails-service test helpers. Find them with:

```bash
grep -rn "new AiGuardrailsAdvisor(" server --include='*.java' | grep -v '/build/'
```

The two AI Hub sites pass `AiGuardrailsSettingsTarget.resolve(null, workspaceId)` — both are chat surfaces with an already-resolved workspace.

- [ ] **Step 5: Convert the remaining external callers and delete the legacy forms**

`AiGatewayGuardrails` calls `resolveMinConfidence(workspaceId)` and `scanResponseText(content, workspaceId)`. It has an already-resolved workspace, so it builds `AiGuardrailsSettingsTarget.resolve(null, workspaceId)` once and reuses it.

Then **delete the eleven `@Nullable Long` delegating forms** added in Task 2. Deleting them is the point of D1: while both exist, a future caller can pick the wrong one and silently resolve the wrong row. The compiler now finds every remaining caller.

- [ ] **Step 6: Compile the whole tree and run the affected suites**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/e3c.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e3c.log
```

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:ee:libs:automation:automation-ai:automation-ai-gateway:automation-ai-gateway-service:test --continue > /tmp/e3t.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e3t.log
```

Expected: green. `ai-hub-service` has one **pre-existing, unrelated** `spotbugsTest` failure (`AiHubPresenceRegistryTest`, presence module) — that is not yours; do not chase it, and do not let it mask a real failure in the same run.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails server/ee/libs/ai/ai-hub server/ee/libs/automation/automation-ai
git commit -m "732 Resolve the embedded settings row for embedded runs"
```

---

### Task 4: Prove the seven controls are live

**Files:**
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrailsEmbeddedSettingsTest.java` (create)

**Interfaces:**
- Consumes: everything from Tasks 1-3. Adds no production code.

This task is the actual bug. Seven controls silently did nothing, and no test noticed — so the deliverable here is the coverage that would have caught it.

- [ ] **Step 1: Write the tests**

One test per control, each over an `EMBEDDED` row that sets the control to a non-default value, asserting the behaviour changes. Use the same fixture shape as `AiGuardrailsTest` in the same package.

```java
    @Test
    void testAnEmbeddedRowGovernsPiiRedaction() {
        // EMBEDDED row: redactPii = false; PLATFORM row: redactPii = true
        AiGuardrails aiGuardrails = aiGuardrailsWithEmbeddedRow(settings -> settings.redactPii(false));

        String scanned = aiGuardrails.scanResponseText(
            "reach me at ada@example.com", AiGuardrailsSettingsTarget.embedded());

        assertThat(scanned).contains("ada@example.com");
    }

    @Test
    void testAnEmbeddedRowGovernsSecretRedaction() {
        // EMBEDDED row: redactSecrets = false; PLATFORM row: redactSecrets = true
        // assert a secret-shaped value survives scanResponseText unredacted
    }

    @Test
    void testAnEmbeddedRowGovernsResponseScanning() {
        // EMBEDDED row: scanResponses = false; PLATFORM row: scanResponses = true
        // assert scanResponseText returns its input unchanged
    }

    @Test
    void testAnEmbeddedRowGovernsModeration() {
        // EMBEDDED row: moderationEnabled = true; PLATFORM row: moderationEnabled = false
        // assert the moderation classifier IS consulted -- verify() on the mock, since the
        // classifier's verdict is not otherwise observable from this entry point
    }

    @Test
    void testAnEmbeddedRowGovernsInjectionDetection() {
        // EMBEDDED row: injectionDetectionEnabled = true; PLATFORM row: false
        // assert the injection classifier IS consulted -- verify() on the mock, same reason
    }

    @Test
    void testAnEmbeddedRowGovernsBlockedTerms() {
        // EMBEDDED row: blockedTerms = "zebra"; PLATFORM row: blockedTerms = "giraffe"
        // assert input containing "zebra" is caught and input containing "giraffe" is not --
        // this pair is what proves the EMBEDDED row was read rather than the PLATFORM one
    }

    @Test
    void testAnEmbeddedRowGovernsTheBlockingMode() {
        AiGuardrails aiGuardrails = aiGuardrailsWithEmbeddedRow(
            settings -> settings.blockingMode(BlockingMode.BLOCK));

        assertThat(aiGuardrails.resolveBlockingMode(AiGuardrailsSettingsTarget.embedded()))
            .isEqualTo(BlockingMode.BLOCK);
    }
```

The first and last are written out in full; the five in between follow the identical shape — build an
`EMBEDDED` row with the stated value, call the corresponding entry point with
`AiGuardrailsSettingsTarget.embedded()`, and assert the behaviour that value implies. Model each on the
existing coverage for the same field in `AiGuardrailsTest`, which already exercises every one of these
controls against a `WORKSPACE` row; the only change is the target.

Each test's PLATFORM row must set the **opposite** value, so a test cannot pass by accidentally reading the
tenant default. Two of the seven (moderation, injection detection) have no observable output difference from
this entry point, so they assert on the classifier mock being consulted rather than on returned text.

- [ ] **Step 2: Add the union test (spec D2)**

```java
    @Test
    void testAnEmbeddedRowsNullFieldUnionsWithTheGlobalPropertyAndNotWithThePlatformRow() {
        // EMBEDDED row: redactPii = null (not set at this level)
        // PLATFORM row: redactPii = true
        // GLOBAL property: redactPii = false
        AiGuardrails aiGuardrails = aiGuardrailsWithEmbeddedRow(settings -> settings.redactPii(null));

        String scanned = aiGuardrails.scanResponseText(
            "reach me at ada@example.com", AiGuardrailsSettingsTarget.embedded());

        assertThat(scanned).contains("ada@example.com");
    }
```

This is the one a naive implementation gets wrong by reusing a fallback. `resolvePolicy` has no PLATFORM fallback today, so this test should pass on the code as written — it exists to keep it that way, since "embedded falls back to the tenant default" is a plausible-sounding change someone will propose.

- [ ] **Step 3: Add the fail-closed regression test (spec D3)**

```java
    @Test
    void testTheEmbeddedMcpOutboundPolicyStillFailsClosed() {
        // fetchEmbeddedSettings() throws
        AiGuardrails aiGuardrails = aiGuardrailsWithThrowingEmbeddedRow();

        assertThatThrownBy(aiGuardrails::resolveEmbeddedMcpOutboundPolicy)
            .isInstanceOf(RuntimeException.class);
    }
```

`resolveEmbeddedMcpOutboundPolicy` is the one read here that must **not** become fail-open while its neighbours are refactored around it.

- [ ] **Step 4: Run them**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsEmbeddedSettingsTest' > /tmp/e4.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e4.log
```

Expected: all pass. **If any of the seven passes before you check it against Task 3's commit, verify it fails when reverted to `AiGuardrailsSettingsTarget.platform()`** — a test that passes for both targets is testing nothing.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test
git commit -m "732 Pin that every embedded guardrail control now governs an embedded run"
```

---

### Task 5: Resolve the restoration setting once per call

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisor.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/test/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorRestorationDestinationTest.java` (existing — append)

**Interfaces:**
- Consumes: the target field on `AiGuardrailsAdvisor` (Task 3).
- Produces: nothing later tasks rely on.

This is finding I5 from the restoration-boundary branch's review, folded in here because it rewrites the same resolution path (spec D5).

`withSessionInToolContext` and `applyResponseGuardrails` each call `aiGuardrails.isRestoreIntoWorkflowOutput(...)` separately, against an uncached `PropertyService` row. On a canvas agent that is two extra reads per model call — roughly forty over a twenty-step tool loop. It also means the request and response halves of one call can disagree: flip the toggle mid-call and the tool arguments are restored while the response is withheld.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testTheRestorationSettingIsReadOncePerCall() {
        // an adviseCall over a canvas agent that makes a tool call
        verify(aiGuardrails, times(1)).isRestoreIntoWorkflowOutput(any());
    }

    @Test
    void testTheRequestAndResponseHalvesCannotDisagree() {
        when(aiGuardrails.isRestoreIntoWorkflowOutput(any()))
            .thenReturn(false, true); // a toggle flipped between the request and the response

        // adviseCall over a WORKFLOW_OUTPUT destination whose response echoes a resolvable token
        assertThat(responseText()).doesNotContain("ada@example.com");
    }
```

The second test is the one that would fail today for a subtle reason: with two independent reads, the
request half sees `false` and the response half sees `true`, so the tool arguments are withheld while the
response is restored. After the fix the single read returns `false` and both halves agree. Note that it
also passes trivially if you resolve once but resolve it in the WRONG half — pair it with the first test,
which pins that exactly one read happens.

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --tests '*AiGuardrailsAdvisorRestorationDestinationTest' > /tmp/e5.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e5.log
```

Expected: the first fails asserting 1 but finding 2.

- [ ] **Step 3: Resolve once at the top of `adviseCall`**

Resolve the value once in `adviseCall`, before `withSessionInToolContext`, and thread it into both that method and `applyResponseGuardrails` as a parameter. Do **not** cache it on a field — the advisor instance is per-call today, but a field would silently become wrong if that ever changed, and a parameter cannot.

`adviseStream` does not need it: per the restoration-boundary design's D8, a streamed response restores unconditionally and never consults the setting. Leave that path alone.

- [ ] **Step 4: Run them to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --continue > /tmp/e5.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/e5.log
```

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service
git commit -m "732 Resolve the workflow-output restoration setting once per call"
```

---

### Task 6: Write down what changed, including the upgrade risk

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `docs/superpowers/specs/2026-09-05-embedded-guardrail-settings-scope-design.md` (status line only)

**Interfaces:**
- Consumes: everything above. Produces nothing.

- [ ] **Step 1: Document the scope model**

`.agents/ai-guardrails.md` records invariants whose violation is silent, which is exactly what this was. Add a subsection covering:

- The three settings scopes and which run resolves which: `EMBEDDED` for `PlatformType.EMBEDDED`, `WORKSPACE` for an automation run with a resolvable workspace, `PLATFORM` otherwise.
- That `AiGuardrailsSettingsTarget.resolve` is the only place that interpretation lives, and that `JobPrincipalWorkspaceResolver` deliberately does **not** know about it (spec D6) because it is shared with workspace prompts and Component Rules.
- That `EMBEDDED` unions with the GLOBAL properties and never falls back to `PLATFORM` (spec D2), so an embedded deployment's settings are genuinely independent of the tenant default.
- That `resolveEmbeddedMcpOutboundPolicy` is the one deliberate exception, reading the row itself to stay fail-closed (spec D3).
- The history in one sentence — seven controls wrote a row nothing read — because that is what stops someone "simplifying" the target back to a `Long`.

- [ ] **Step 2: Write the upgrade note**

Add a clearly-marked upgrade paragraph, since spec D4 chose immediate effect with no migration:

> **Upgrading:** embedded guardrail settings take effect on upgrade. Before this change, seven of the eight controls on the embedded guardrails settings page were written but never read, so any value saved there — including a blocking mode set long ago on a page that did nothing — begins governing embedded model calls immediately. Review the embedded guardrails settings page before upgrading.

- [ ] **Step 3: Update the spec status line**

Change it to record that the design is implemented, keeping the D4 and D6 attributions and the plan pointer intact.

- [ ] **Step 4: Commit**

```bash
git add .agents/ai-guardrails.md docs/superpowers/specs/2026-09-05-embedded-guardrail-settings-scope-design.md
git commit -m "732 docs - Record the guardrail settings scope model and its upgrade risk"
```

---

## Final verification

- [ ] Whole tree compiles:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/efinal.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/efinal.log
```

- [ ] No production caller resolves guardrail settings from a bare `Long` any more — the compiler proves this once Task 3's deletions land, but confirm no new overload crept back:

```bash
grep -rn "public .*Long workspaceId" server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/AiGuardrails.java
```

Expected: no matches.

- [ ] `cd client && npm run check` — no client change is planned, so this is a regression check only. Give it a 600000 ms timeout.
- [ ] `./gradlew spotlessApply` re-run after any late edit; formatters run first in CI, and a late fix that skips them fails the build on formatting rather than on substance.

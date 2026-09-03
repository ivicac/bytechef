# Component Rules Workspace Scoping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scope component rules to a workspace or to all workspaces, move observe mode and the approval expiry
to per-workspace settings with a tenant default, and relocate the Rules page to a third tab under AI Agents
settings.

**Architecture:** A nullable `workspace_id` on `component_rule` (null = every workspace). The CE tool-callback
wrapper passes the job principal and platform type; the EE enforcer resolves the workspace from them through a
resolver extracted from Guardrails, caches it, and degrades to the tenant-wide rule set on any failure. Settings
move to the Guardrails storage shape. The client page moves beside Guardrails and binds to the current
workspace.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, Caffeine, GraphQL, React 19 + TypeScript +
Vitest, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-03-component-rules-workspace-scoping-design.md`

## Global Constraints

- Work in the worktree `/Volumes/Data/bytechef/bytechef/.claude/worktrees/component-rules-approval` on branch
  `component-rules-approval`. Never `cd` to the main checkout. Never use bare `git stash`.
- **Pass `timeout: 600000` on every Gradle and npm command.** The Bash tool defaults to 120 seconds and
  auto-backgrounds anything slower; agents stall permanently waiting on the backgrounded result. Never
  background a build yourself and never wait on one.
- Files under `server/ee/` use the ByteChef Enterprise license header and a `@version ee` Javadoc tag; files
  under `server/libs/` use Apache 2.0.
- Java style: one blank line before `if`/`for`/`while`/`try`/`switch` (not after an opening brace, not before
  `} else {`); one blank line between a variable mutation and the next statement using it; no blank line before
  a class's closing brace; no `_` prefix on private methods; descriptive names, never single letters.
- No `TODO:` comments (Checkstyle forbids them). No code comments explaining what changed.
- Test class names end in `Test`, integration ones in `IntTest`; method names camelCase, no underscores.
- Enum and error-key values are append-only.
- Client: alphabetical object keys (`sort-keys`, not auto-fixable) and alphabetical named imports;
  `Icon`-suffixed lucide icons; `twMerge` never `cn()`; interfaces end in `I` or `Props`; hook order is
  `useState` → `useRef` → store hooks → other hooks → memos → **all** `useEffect` last; `vi.hoisted` for any
  module-scope value a `vi.mock` factory references.
- `./gradlew spotlessApply` before committing server changes; `npm run check` from `client/` for client changes.
- Commit messages: `<description>` for server, `client - <description>` for client, ending with
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## Decisions already made — do not relitigate

- A rule names one workspace **or** all of them (nullable column). Existing rows read as null and stay
  tenant-wide.
- Settings are per-workspace with a tenant default, in the Guardrails storage shape.
- The Components settings Rules tab is removed and its route redirects.
- Workspace resolution is **extracted and shared** with Guardrails rather than copied. If review prefers a
  private copy, only Task 1 changes.

## ⚑ One decision this plan surfaces and cannot make for you

The current Rules route (`client/src/routes.tsx:523`) admits `[AUTHORITIES.ADMIN, AUTHORITIES.USER]`. The
AI Agents route (`routes.tsx:287`) admits `[AUTHORITIES.ADMIN]` only. **Moving the page therefore removes
non-admin access.** Task 6 assumes that is acceptable, because rule authoring is already `ROLE_ADMIN`-gated at
the GraphQL mutations and a non-admin could only ever read the list. If you want workspace members to keep a
read-only view, say so and Task 6 relaxes the route to `[ADMIN, USER]` and gates the mutation controls on the
workspace scope instead — the same pattern the Variables page uses.

## File Structure

**Server, new:**
- `server/ee/libs/platform/platform-ai/.../workspace/JobPrincipalWorkspaceResolver.java` — shared resolution,
  extracted from `AiGuardrailsAdvisorProviderImpl`.

**Server, modified:**
- `.../component_rule/20260831000001_component_rule_init.xml` — `workspace_id` column (unreleased, edit in place).
- `platform-component-rule-api/.../ComponentRule.java` — `workspaceId` field and accessors.
- `platform-component-rule-api/.../ComponentRuleService.java` — workspace-aware reads.
- `platform-component-rule-api/.../ComponentRuleSettingsService.java` — `@Nullable Long workspaceId` on both methods.
- `platform-component-rule-service/.../ComponentRuleServiceImpl.java`, `ComponentRuleSettingsServiceImpl.java`,
  `ComponentRuleEnforcerImpl.java`, `repository/ComponentRuleRepository.java`.
- `platform-component-api/.../rule/ComponentRuleEnforcer.java` — `ToolCall` gains two components.
- `ai/llm/.../tool/RuleEnforcingToolCallback.java` — populates them.
- `platform-component-rule-graphql/.../component-rule.graphqls`, `ComponentRuleGraphQlController.java`.
- `platform-ai-guardrails-service/.../AiGuardrailsAdvisorProviderImpl.java` — uses the shared resolver.

**Client, modified:**
- `ee/pages/settings/automation/ai/agents/AiAgents.tsx` — third tab.
- `ee/pages/settings/platform/component-rules/**` — moves to `ee/pages/settings/automation/ai/rules/**`.
- `graphql/platform/component-rule/*.graphql` — workspace arguments.
- `routes.tsx`, `ee/pages/settings/platform/components/Components.tsx`.

---

### Task 1: Extract the workspace resolver shared with Guardrails

**Files:**
- Create: `server/ee/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/ee/platform/ai/workspace/JobPrincipalWorkspaceResolver.java`
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-service/src/main/java/com/bytechef/ee/platform/ai/guardrails/advisor/AiGuardrailsAdvisorProviderImpl.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-api/src/test/java/com/bytechef/ee/platform/ai/workspace/JobPrincipalWorkspaceResolverTest.java`

**Interfaces:**
- Consumes: `ProjectDeploymentService`, `ProjectService`, both via `ObjectProvider`.
- Produces: `JobPrincipalWorkspaceResolver.resolve(@Nullable PlatformType platformType, @Nullable Long jobPrincipalId)`
  returning `@Nullable Long`, cached internally.

**Read first.** `AiGuardrailsAdvisorProviderImpl` currently owns this logic: a `WorkspaceCacheKey(platformType,
jobPrincipalId)` record, a Caffeine `Cache<WorkspaceCacheKey, Optional<Long>>`, `resolveWorkspaceId(...)` and
`fetchWorkspaceId(long)`. Read all four before moving anything, and preserve their behaviour exactly — the class
Javadoc explains why each degradation is deliberate.

- [ ] **Step 1: Write the failing test**

```java
class JobPrincipalWorkspaceResolverTest {

    private final ProjectDeploymentService projectDeploymentService = mock(ProjectDeploymentService.class);
    private final ProjectService projectService = mock(ProjectService.class);

    @Test
    void testResolvesTheWorkspaceOfAnAutomationJobPrincipal() {
        ProjectDeployment projectDeployment = mock(ProjectDeployment.class);
        Project project = mock(Project.class);

        when(projectDeployment.getProjectId()).thenReturn(7L);
        when(project.getWorkspaceId()).thenReturn(42L);
        when(projectDeploymentService.getProjectDeployment(3L)).thenReturn(projectDeployment);
        when(projectService.getProject(7L)).thenReturn(project);

        assertThat(newResolver().resolve(PlatformType.AUTOMATION, 3L)).isEqualTo(42L);
    }

    @Test
    void testANonAutomationPlatformTypeResolvesToTheTenantDefault() {
        assertThat(newResolver().resolve(PlatformType.EMBEDDED, 3L)).isNull();

        verifyNoInteractions(projectDeploymentService);
    }

    @Test
    void testANullJobPrincipalResolvesToTheTenantDefault() {
        assertThat(newResolver().resolve(PlatformType.AUTOMATION, null)).isNull();
    }

    @Test
    void testAResolutionFailureDegradesToTheTenantDefaultRatherThanThrowing() {
        when(projectDeploymentService.getProjectDeployment(3L))
            .thenThrow(new IllegalStateException("deployment was deleted"));

        // Only the workspace SCOPE degrades. Callers must still apply their tenant-wide configuration; a lookup
        // failure must never be mistaken for "nothing configured".
        assertThat(newResolver().resolve(PlatformType.AUTOMATION, 3L)).isNull();
    }

    @Test
    void testTheLookupIsCachedPerJobPrincipal() {
        ProjectDeployment projectDeployment = mock(ProjectDeployment.class);
        Project project = mock(Project.class);

        when(projectDeployment.getProjectId()).thenReturn(7L);
        when(project.getWorkspaceId()).thenReturn(42L);
        when(projectDeploymentService.getProjectDeployment(3L)).thenReturn(projectDeployment);
        when(projectService.getProject(7L)).thenReturn(project);

        JobPrincipalWorkspaceResolver resolver = newResolver();

        resolver.resolve(PlatformType.AUTOMATION, 3L);
        resolver.resolve(PlatformType.AUTOMATION, 3L);

        verify(projectDeploymentService, times(1)).getProjectDeployment(3L);
    }

    @SuppressWarnings("unchecked")
    private JobPrincipalWorkspaceResolver newResolver() {
        ObjectProvider<ProjectDeploymentService> projectDeploymentServiceProvider = mock(ObjectProvider.class);
        ObjectProvider<ProjectService> projectServiceProvider = mock(ObjectProvider.class);

        when(projectDeploymentServiceProvider.getIfAvailable()).thenReturn(projectDeploymentService);
        when(projectServiceProvider.getIfAvailable()).thenReturn(projectService);

        return new JobPrincipalWorkspaceResolver(projectDeploymentServiceProvider, projectServiceProvider);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-ai:platform-ai-api:compileTestJava > /tmp/w1.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/w1.log | head`

Expected: FAIL, `cannot find symbol: class JobPrincipalWorkspaceResolver`.

- [ ] **Step 3: Create the resolver by moving the logic**

Move `WorkspaceCacheKey`, the Caffeine cache, `resolveWorkspaceId` and `fetchWorkspaceId` out of
`AiGuardrailsAdvisorProviderImpl` into the new class, renaming `resolveWorkspaceId` to `resolve` and making it
public. Keep the cache configuration identical. Carry the explanatory Javadoc across and generalise its wording
from guardrails to "callers", since two features now depend on it — in particular keep the sentence explaining
that only the workspace scope degrades, never whether the caller's configuration applies.

Annotate the class `@Component` and `@ConditionalOnEEVersion`, matching its neighbours.

- [ ] **Step 4: Make Guardrails use it**

Replace `AiGuardrailsAdvisorProviderImpl`'s own resolution with a constructor-injected
`JobPrincipalWorkspaceResolver`, calling `resolve(platformType, jobPrincipalId)` where it previously called its
private method. Delete the moved members and any now-unused imports and fields (`Caffeine`, `Cache`, the two
`ObjectProvider` fields if nothing else uses them).

- [ ] **Step 5: Run both modules' tests**

Run: `./gradlew :server:ee:libs:platform:platform-ai:platform-ai-api:test :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-service:test --continue > /tmp/w1.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w1.log`

Expected: exit=0. The guardrails tests must pass **unmodified** — they are the proof the extraction preserved
behaviour. If you find yourself editing them, stop and report: that means behaviour changed.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai
git commit -m "$(cat <<'EOF'
Extract the job-principal workspace resolver shared with guardrails

Component rules need the same cached, fail-open resolution guardrails already
does. Two independent copies would drift and the stale one is the one nobody
notices, so the logic moves out rather than being duplicated.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add the workspace column and workspace-aware reads

**Files:**
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml`
- Modify: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRule.java`
- Modify: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleService.java`
- Modify: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/repository/ComponentRuleRepository.java`
- Modify: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceImpl.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceTest.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/repository/ComponentRuleRepositoryIntTest.java`

**Interfaces:**
- Produces: `ComponentRule.getWorkspaceId()` / `setWorkspaceId(@Nullable Long)`; DB column
  `component_rule.workspace_id`;
  `ComponentRuleService.getEnabledComponentRules(String componentName, @Nullable Long workspaceId)` replacing the
  single-argument form; `ComponentRuleRepository.findAllByComponentNameAndEnabledAndWorkspaceIdIsNullOrComponentNameAndEnabledAndWorkspaceId(...)`
  — or, if that derived name proves unwieldy, an `@Query` with
  `WHERE component_name = :componentName AND enabled = TRUE AND (workspace_id IS NULL OR workspace_id = :workspaceId)`.
  Prefer the `@Query`; say which you used.

- [ ] **Step 1: Write the failing tests**

In `ComponentRuleServiceTest`:

```java
    @Test
    void testGetEnabledComponentRulesReturnsTheWorkspacesRulesAndTheTenantWideOnes() {
        ComponentRule tenantWideComponentRule = newComponentRule(RulePhase.BEFORE, RuleAction.TAG, "true");
        ComponentRule workspaceComponentRule = newComponentRule(RulePhase.BEFORE, RuleAction.BLOCK, "true");

        workspaceComponentRule.setWorkspaceId(42L);

        when(componentRuleRepository.findAllEnabledForWorkspace("slack", 42L))
            .thenReturn(List.of(tenantWideComponentRule, workspaceComponentRule));

        assertThat(componentRuleService.getEnabledComponentRules("slack", 42L))
            .containsExactly(tenantWideComponentRule, workspaceComponentRule);
    }

    @Test
    void testANullWorkspaceReturnsOnlyTheTenantWideRules() {
        ComponentRule tenantWideComponentRule = newComponentRule(RulePhase.BEFORE, RuleAction.TAG, "true");

        when(componentRuleRepository.findAllEnabledForWorkspace("slack", null))
            .thenReturn(List.of(tenantWideComponentRule));

        // An unresolvable workspace must narrow the scope to tenant-wide rules, never widen it to another
        // workspace's and never collapse it to none.
        assertThat(componentRuleService.getEnabledComponentRules("slack", null))
            .containsExactly(tenantWideComponentRule);
    }
```

In `ComponentRuleRepositoryIntTest`, a real-Postgres test that the query actually filters:

```java
    @Test
    void testFindAllEnabledForWorkspaceReturnsTheWorkspacesRulesAndTheTenantWideOnesOnly() {
        componentRuleRepository.save(newRule("sendMessage", null));
        componentRuleRepository.save(newRule("deleteMessage", 42L));
        componentRuleRepository.save(newRule("archiveMessage", 99L));

        List<ComponentRule> componentRules = componentRuleRepository.findAllEnabledForWorkspace("slack", 42L);

        assertThat(componentRules)
            .extracting(ComponentRule::getToolName)
            .containsExactlyInAnyOrder("sendMessage", "deleteMessage");
    }

    private static ComponentRule newRule(String toolName, Long workspaceId) {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setComponentName("slack");
        componentRule.setToolName(toolName);
        componentRule.setPhase(RulePhase.BEFORE);
        componentRule.setRuleAction(RuleAction.TAG);
        componentRule.setCondition("true");
        componentRule.setEnabled(true);
        componentRule.setWorkspaceId(workspaceId);

        return componentRule;
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava > /tmp/w2.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/w2.log | head`

Expected: FAIL, `cannot find symbol: method setWorkspaceId`.

- [ ] **Step 3: Add the column**

In the init changelog, after the `tool_name` column:

```xml
            <column name="workspace_id" type="BIGINT"/>
```

Nullable by omission, which is the intent: null means every workspace.

- [ ] **Step 4: Add the field**

In `ComponentRule.java`:

```java
    @Column("workspace_id")
    private @Nullable Long workspaceId;
```

```java
    /**
     * The workspace this rule governs, or {@code null} for every workspace in the tenant. A boxed {@link Long}
     * because null is a real state here, not a missing value.
     */
    public @Nullable Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(@Nullable Long workspaceId) {
        this.workspaceId = workspaceId;
    }
```

Extend the class Javadoc's scoping paragraph to say a rule is scoped to one workspace or to all.

- [ ] **Step 5: Add the query and service method**

In `ComponentRuleRepository`:

```java
    @Query("""
        SELECT * FROM component_rule
        WHERE component_name = :componentName
          AND enabled = TRUE
          AND (workspace_id IS NULL OR workspace_id = :workspaceId)
        """)
    List<ComponentRule> findAllEnabledForWorkspace(
        @Param("componentName") String componentName, @Param("workspaceId") @Nullable Long workspaceId);
```

A null `workspaceId` makes the second disjunct false for every row, so only tenant-wide rules return — which is
the required degradation, achieved without a second query.

In `ComponentRuleServiceImpl`, replace `getEnabledComponentRules(String)` with the two-argument form delegating
to it. Copy `workspaceId` on the update path in `saveComponentRule`, beside the existing `setStrict` copy — a
field not copied there persists on create and silently fails to save on edit.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/w2.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w2.log`

Then the integration test, which is what proves the SQL actually filters:

Run: `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock; ./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:testIntegration > /tmp/w2i.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w2i.log`

Expected: exit=0 for both.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-component-rule
git commit -m "$(cat <<'EOF'
Scope a component rule to a workspace or to all of them

A null workspace means every workspace, so existing rows keep their current
tenant-wide behaviour. The enforcement query returns the workspace's rules
unioned with the tenant-wide ones; a null workspace narrows to tenant-wide only.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Resolve the workspace at enforcement time

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/rule/ComponentRuleEnforcer.java`
- Modify: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/RuleEnforcingToolCallback.java`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerImpl.java`
- Modify: `.../platform-component-rule-service/build.gradle.kts`
- Test: `server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/tool/RuleEnforcingToolCallbackTest.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`

**Interfaces:**
- Consumes: `JobPrincipalWorkspaceResolver.resolve(...)` from Task 1;
  `ComponentRuleService.getEnabledComponentRules(String, Long)` from Task 2.
- Produces: `ToolCall` gains trailing components `@Nullable Long jobPrincipalId, @Nullable PlatformType platformType`;
  the enforcer's cache key becomes `TenantComponentWorkspaceKey(String tenantId, String componentName, @Nullable Long workspaceId)`.

**Context.** The wrapper is in a CE module and must not reach EE services, so it passes facts and the EE
enforcer decides — the seam the SPI already has. `ActionContextAware` exposes `getJobPrincipalId()` and
`getPlatformType()` through `JobContextAware`; both are already available where the wrapper builds its
`ToolCall`.

- [ ] **Step 1: Write the failing tests**

In `ComponentRuleEnforcerTest`:

```java
    @Test
    void testRulesAreFetchedForTheResolvedWorkspace() {
        when(jobPrincipalWorkspaceResolver.resolve(PlatformType.AUTOMATION, 5L)).thenReturn(42L);
        when(componentRuleService.getEnabledComponentRules("slack", 42L)).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforeCall(inWorkspace(5L))).isInstanceOf(Decision.Block.class);
    }

    @Test
    void testAnUnresolvableWorkspaceStillEvaluatesTenantWideRules() {
        when(jobPrincipalWorkspaceResolver.resolve(any(), any())).thenReturn(null);
        when(componentRuleService.getEnabledComponentRules("slack", null)).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        // Degrading the SCOPE must never degrade to no governance at all.
        assertThat(componentRuleEnforcer.checkBeforeCall(inWorkspace(5L))).isInstanceOf(Decision.Block.class);
    }

    @Test
    void testTheCacheIsScopedByWorkspace() {
        when(jobPrincipalWorkspaceResolver.resolve(PlatformType.AUTOMATION, 5L)).thenReturn(42L);
        when(jobPrincipalWorkspaceResolver.resolve(PlatformType.AUTOMATION, 6L)).thenReturn(99L);
        when(componentRuleService.getEnabledComponentRules("slack", 42L)).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));
        when(componentRuleService.getEnabledComponentRules("slack", 99L)).thenReturn(List.of());

        // Workspace 99 must not be served workspace 42's cached blocking rule — the same reason the key already
        // carries the tenant.
        assertThat(componentRuleEnforcer.checkBeforeCall(inWorkspace(5L))).isInstanceOf(Decision.Block.class);
        assertThat(componentRuleEnforcer.checkBeforeCall(inWorkspace(6L))).isInstanceOf(Decision.Allow.class);
    }

    private static ToolCall inWorkspace(long jobPrincipalId) {
        return new ToolCall(
            "slack", "sendMessage", "SLACK_SEND_MESSAGE", Map.of("channel", "C05QG7RF30A"), 3L, 42L, null, null,
            jobPrincipalId, PlatformType.AUTOMATION);
    }
```

Add `private final JobPrincipalWorkspaceResolver jobPrincipalWorkspaceResolver = mock(JobPrincipalWorkspaceResolver.class);`
to the fixture, pass it to both enforcer constructions, and update every existing `SEND_MESSAGE_TO_C05`-style
constant to the ten-component `ToolCall`.

In `RuleEnforcingToolCallbackTest`:

```java
    @Test
    void testTheToolCallCarriesTheJobPrincipalAndPlatformType() {
        when(actionContext.getJobPrincipalId()).thenReturn(5L);
        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());

        newToolCallback().call("{}", null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        ToolCall toolCall = toolCallCaptor.getValue();

        assertThat(toolCall.jobPrincipalId()).isEqualTo(5L);
        assertThat(toolCall.platformType()).isEqualTo(PlatformType.AUTOMATION);
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:libs:modules:components:ai:llm:compileTestJava :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava --continue > /tmp/w3.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/w3.log | head`

Expected: FAIL — the `ToolCall` constructor takes eight arguments, not ten.

- [ ] **Step 3: Widen the SPI record**

Append two components to `ToolCall`, with Javadoc:

```java
     * @param jobPrincipalId  the job principal this call runs under — a project-deployment id under
     *                        {@link PlatformType#AUTOMATION} — or {@code null} when there is none. An
     *                        implementation resolves the governing workspace from this; the wrapper does not,
     *                        because it lives in a CE module and the resolution is an EE concern.
     * @param platformType    the platform this call runs under, or {@code null}. Only
     *                        {@link PlatformType#AUTOMATION} carries a workspace.
```

- [ ] **Step 4: Populate them in the wrapper**

In `RuleEnforcingToolCallback.toToolCall`, add `actionContext.getJobPrincipalId()` and
`actionContext.getPlatformType()` as the two trailing arguments. No other change.

- [ ] **Step 5: Resolve and re-key in the enforcer**

Inject `JobPrincipalWorkspaceResolver` through both constructors (the public one and the package-private
`Ticker` one the TTL test uses). Replace `TenantComponentKey` with:

```java
    private record TenantComponentWorkspaceKey(
        String tenantId, String componentName, @Nullable Long workspaceId) {
    }
```

`getCachedComponentRules` takes the resolved workspace, keys on all three, and loads through
`componentRuleService.getEnabledComponentRules(componentName, workspaceId)`. Resolve the workspace **once** per
`checkBeforeCall` and per `recordAfterCall`, into a local — not per lookup.

Add to `platform-component-rule-service/build.gradle.kts`, alphabetically:

```kotlin
    implementation(project(":server:ee:libs:platform:platform-ai:platform-ai-api"))
```

Verify it is not already present before adding.

- [ ] **Step 6: Run the tests and a whole-server compile**

Run: `./gradlew :server:libs:modules:components:ai:llm:test :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test --continue > /tmp/w3.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w3.log`

Then, because the `ToolCall` record is constructed in several places:

Run: `./gradlew compileJava compileTestJava --continue > /tmp/w3c.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w3c.log | head`

Expected: exit=0 for both.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add server
git commit -m "$(cat <<'EOF'
Enforce a rule against the workspace its agent runs in

The wrapper passes the job principal and platform type; the EE enforcer resolves
the workspace from them and keys its cache on it, so one workspace's rules are
never served to another. An unresolvable workspace narrows to the tenant-wide
rules rather than to none.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Make the settings per-workspace

**Files:**
- Modify: `.../platform-component-rule-api/.../ComponentRuleSettings.java` (Javadoc only)
- Modify: `.../platform-component-rule-api/.../ComponentRuleSettingsService.java`
- Modify: `.../platform-component-rule-service/.../service/ComponentRuleSettingsServiceImpl.java`
- Modify: `.../platform-component-rule-service/.../ComponentRuleEnforcerImpl.java` (settings cache key)
- Test: `.../platform-component-rule-service/src/test/java/.../service/ComponentRuleSettingsServiceTest.java`

**Interfaces:**
- Produces: `ComponentRuleSettingsService.getSettings(@Nullable Long workspaceId)` and
  `saveSettings(ComponentRuleSettings, @Nullable Long workspaceId)`; the enforcer's settings cache keyed by
  `(tenantId, workspaceId)`.

**Precedent to follow exactly.** `AiGuardrailsWorkspaceSettingsServiceImpl` stores a tenant default at
`Scope.PLATFORM` with a null `scopeId` and a workspace override at `Scope.WORKSPACE` with the workspace id, via
`scopeOf(workspaceId)`. Read its class Javadoc: it explains why a `Scope.WORKSPACE` row with a sentinel `0L`
would be wrong. Use the same shape.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testAWorkspaceOverrideWinsOverTheTenantDefault() {
        when(propertyService.fetchProperty(PROPERTY_KEY, Scope.WORKSPACE, 42L))
            .thenReturn(Optional.of(property(Map.of("observeMode", true, "approvalExpiresInHours", 24))));

        ComponentRuleSettings componentRuleSettings = componentRuleSettingsService.getSettings(42L);

        assertThat(componentRuleSettings.observeMode()).isTrue();
        assertThat(componentRuleSettings.approvalExpiresInHours()).isEqualTo(24);

        verify(propertyService, never()).fetchProperty(PROPERTY_KEY, Scope.PLATFORM, null);
    }

    @Test
    void testAbsentAnOverrideTheTenantDefaultApplies() {
        when(propertyService.fetchProperty(PROPERTY_KEY, Scope.WORKSPACE, 42L)).thenReturn(Optional.empty());
        when(propertyService.fetchProperty(PROPERTY_KEY, Scope.PLATFORM, null))
            .thenReturn(Optional.of(property(Map.of("observeMode", true, "approvalExpiresInHours", 12))));

        assertThat(componentRuleSettingsService.getSettings(42L)
            .observeMode()).isTrue();
    }

    @Test
    void testAbsentBothTheDocumentedDefaultApplies() {
        when(propertyService.fetchProperty(PROPERTY_KEY, Scope.WORKSPACE, 42L)).thenReturn(Optional.empty());
        when(propertyService.fetchProperty(PROPERTY_KEY, Scope.PLATFORM, null)).thenReturn(Optional.empty());

        assertThat(componentRuleSettingsService.getSettings(42L)).isEqualTo(ComponentRuleSettings.DEFAULT);
    }

    @Test
    void testSavingAWorkspaceOverrideWritesAWorkspaceScopedRow() {
        componentRuleSettingsService.saveSettings(new ComponentRuleSettings(true, 12), 42L);

        verify(propertyService).save(
            eq(PROPERTY_KEY), eq(Map.of("observeMode", true, "approvalExpiresInHours", 12)),
            eq(Scope.WORKSPACE), eq(42L));
    }

    @Test
    void testSavingTheTenantDefaultWritesAPlatformScopedRowWithANullScopeId() {
        componentRuleSettingsService.saveSettings(new ComponentRuleSettings(true, 12), null);

        verify(propertyService).save(
            eq(PROPERTY_KEY), eq(Map.of("observeMode", true, "approvalExpiresInHours", 12)),
            eq(Scope.PLATFORM), isNull());
    }
```

Keep the existing malformed-and-partial-map tests, adapting them to the new signature.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava > /tmp/w4.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/w4.log | head`

Expected: FAIL — `getSettings()` takes no arguments.

- [ ] **Step 3: Widen the interface and implementation**

```java
    /**
     * The settings governing {@code workspaceId}: its own override if it has one, else the tenant default, else
     * {@link ComponentRuleSettings#DEFAULT}. A {@code null} workspace asks for the tenant default directly.
     */
    ComponentRuleSettings getSettings(@Nullable Long workspaceId);

    ComponentRuleSettings saveSettings(ComponentRuleSettings componentRuleSettings, @Nullable Long workspaceId);
```

In the implementation add the `scopeOf` helper copied from the guardrails precedent, and have `getSettings` try
the workspace scope first, then fall back to the platform scope, then to `DEFAULT`. Keep the existing
per-field `instanceof` degradation for a malformed stored map.

- [ ] **Step 4: Re-key the enforcer's settings cache**

Change `Cache<String, ComponentRuleSettings>` to a key carrying the workspace — reuse a small record rather than
concatenating strings — and pass the resolved workspace from `checkBeforeCall`. The workspace is already
resolved once there by Task 3; use that local rather than resolving again.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/w4.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w4.log`

Expected: exit=0.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-component-rule
git commit -m "$(cat <<'EOF'
Make observe mode and the approval expiry per-workspace

A workspace override beats the tenant default, which beats the documented
default — the storage shape guardrails already uses. One workspace can now run
in observe mode while its neighbour enforces, which is the rollout story observe
mode exists for.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Expose the workspace over GraphQL, with authorization

**Files:**
- Modify: `.../platform-component-rule-graphql/src/main/resources/graphql/component-rule.graphqls`
- Modify: `.../platform-component-rule-graphql/src/main/java/.../web/graphql/ComponentRuleGraphQlController.java`
- Test: `.../platform-component-rule-graphql/src/test/java/.../web/graphql/ComponentRuleGraphQlControllerTest.java`

**Interfaces:**
- Produces: `ComponentRule.workspaceId: ID`; `componentRules(componentName: String, workspaceId: ID)`;
  `saveComponentRule(..., workspaceId: ID)`; `componentRuleSettings(workspaceId: ID)`;
  `updateComponentRuleSettings(observeMode: Boolean!, approvalExpiresInHours: Int!, workspaceId: ID)`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testSaveComponentRuleCarriesTheWorkspace() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        controller.saveComponentRule(
            null, "slack", "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true", null, true, false, "42");

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        assertThat(componentRuleCaptor.getValue()
            .getWorkspaceId()).isEqualTo(42L);
    }

    @Test
    void testANullWorkspaceSavesATenantWideRule() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        controller.saveComponentRule(
            null, "slack", "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true", null, true, false, null);

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        assertThat(componentRuleCaptor.getValue()
            .getWorkspaceId()).isNull();
    }

    @Test
    void testComponentRuleSettingsReadsTheRequestedWorkspace() {
        when(componentRuleSettingsService.getSettings(42L)).thenReturn(new ComponentRuleSettings(true, 24));

        assertThat(controller.componentRuleSettings("42")).isEqualTo(new ComponentRuleSettings(true, 24));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:compileTestJava > /tmp/w5.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/w5.log | head`

Expected: FAIL on argument count.

- [ ] **Step 3: Extend the schema**

Add `workspaceId: ID` to the `ComponentRule` type and to `saveComponentRule`'s arguments (after `strict`), a
`workspaceId: ID` argument to `componentRules`, and to both settings operations. Document on each that null
means every workspace, or the tenant default for settings.

- [ ] **Step 4: Extend the controller**

Add the arguments, parse `workspaceId` with `Long.parseLong` when non-blank, set it on the domain object, and
carry it into `ComponentRuleItem`.

**Authorization.** Keep the existing `ROLE_ADMIN` `@PreAuthorize` as the floor. Add, at the top of
`saveComponentRule`, a guard that only a tenant admin may create or modify a rule whose `workspaceId` is null:

```java
        // A tenant-wide rule governs every workspace, so authoring one is a tenant-level act even though the
        // page it is authored from is workspace-scoped.
        if (workspaceId == null && !SecurityUtils.hasAuthority(AuthorityConstants.ADMIN)) {
            throw new ConfigurationException(
                "Only a tenant administrator may create a rule that applies to every workspace.",
                ComponentRuleErrorType.TENANT_WIDE_RULE_REQUIRES_ADMIN);
        }
```

Append `TENANT_WIDE_RULE_REQUIRES_ADMIN = 104` to `ComponentRuleErrorType`. If `SecurityUtils` exposes no such
helper, use the idiom the neighbouring EE controllers use for a role check and say which in your report.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:test > /tmp/w5.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/w5.log`

Expected: exit=0.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-component-rule
git commit -m "$(cat <<'EOF'
Expose the rule workspace over GraphQL

Authoring a rule that applies to every workspace is a tenant-level act even
though the page is workspace-scoped, so it requires a tenant administrator.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Move the page under AI Agents and bind it to the workspace

**Files:**
- Move: `client/src/ee/pages/settings/platform/component-rules/**` → `client/src/ee/pages/settings/automation/ai/rules/**` (use `git mv`)
- Modify: `client/src/ee/pages/settings/automation/ai/agents/AiAgents.tsx`
- Modify: `client/src/routes.tsx:274-288` and `:523`
- Modify: `client/src/ee/pages/settings/platform/components/Components.tsx`
- Modify: `client/src/graphql/platform/component-rule/*.graphql`

**Interfaces:**
- Consumes: the GraphQL surface from Task 5.
- Produces: route `/automation/settings/ai/agents/rules`; the Components `rules` route redirects to it.

**⚑ Read the plan's authorization note before starting.** The AI Agents route admits `[AUTHORITIES.ADMIN]`
only, while the current Rules route admits `[ADMIN, USER]`. This task assumes admin-only is acceptable. If the
plan owner said otherwise, relax the route instead and gate the mutation controls on the workspace scope, the
way the Variables page does.

- [ ] **Step 1: Update the GraphQL operations and regenerate**

Add `workspaceId` to the `ComponentRules` query selection set and as a `$workspaceId: ID` variable on it, to
`SaveComponentRule`'s variables and arguments, and to both settings operations. Then:

Run: `cd client && npx graphql-codegen` (timeout 600000)

- [ ] **Step 2: Write the failing tests**

In a new `client/src/ee/pages/settings/automation/ai/rules/ComponentRulesTab.test.tsx` (moved with the page),
add:

```tsx
    it('passes the current workspace to the rules query', async () => {
        render(<ComponentRulesTab />);

        await waitFor(() => {
            expect(useComponentRulesQueryMock).toHaveBeenCalledWith(
                expect.objectContaining({workspaceId: '1'})
            );
        });
    });

    it('shows the apply-to-all-workspaces control only for a tenant admin', () => {
        const {rerender} = render(<ComponentRuleDialog onOpenChange={vi.fn()} open />);

        expect(screen.getByLabelText('Apply to all workspaces')).toBeInTheDocument();

        setCurrentUserAuthorities(['ROLE_USER']);

        rerender(<ComponentRuleDialog onOpenChange={vi.fn()} open />);

        expect(screen.queryByLabelText('Apply to all workspaces')).not.toBeInTheDocument();
    });

    it('labels an inherited settings value as inherited', () => {
        renderTabWithSettings({inherited: true, observeMode: false});

        // "off because this workspace chose off" and "off because the tenant default is off" behave differently
        // when the tenant default changes, so the control must say which it is.
        expect(screen.getByText(/inherited from the tenant default/i)).toBeInTheDocument();
    });
```

Use `vi.hoisted` for any mock refs the `vi.mock` factories reference.

- [ ] **Step 3: Move the page**

```bash
git mv client/src/ee/pages/settings/platform/component-rules client/src/ee/pages/settings/automation/ai/rules
```

Update every import path that referenced the old location — find them with
`grep -rn "settings/platform/component-rules" client/src`.

- [ ] **Step 4: Bind to the workspace**

In `ComponentRulesTab`, read the workspace the way `AiGuardrails.tsx` does:

```tsx
const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
```

Pass `workspaceId: currentWorkspaceId != null ? String(currentWorkspaceId) : undefined` to the rules query, the
settings query, and both mutations, guarding on `currentWorkspaceId != null` the way the sibling does.

In the dialog, add an "Apply to all workspaces" checkbox rendered only for a tenant admin, which sends a null
`workspaceId`. In the list, mark tenant-wide rows and disable their edit control for a non-admin.

In the settings control, when the workspace has no override show the inherited tenant value with an explicit
"inherited from the tenant default" note beside it.

- [ ] **Step 5: Add the tab**

In `AiAgents.tsx`, the current tab is chosen with a two-way ternary. Replace it with a resolution that admits
three values, keeping `guardrails` as the default for an unknown tab:

```tsx
const RULES_TAB = 'rules';
const SYSTEM_PROMPT_TAB = 'system-prompt';

const currentTab = tab === SYSTEM_PROMPT_TAB || tab === RULES_TAB ? tab : 'guardrails';
```

Add `<TabsTrigger value={RULES_TAB}>Rules</TabsTrigger>` after System Prompt, and render `<ComponentRulesTab />`
for it. Extend the page description to mention which tool calls agents in this workspace may make.

- [ ] **Step 6: Redirect the old route**

In `routes.tsx`, replace the `path: 'rules'` block at line 523 with a redirect:

```tsx
                {
                    element: <Navigate replace to="/automation/settings/ai/agents/rules" />,
                    path: 'rules',
                },
```

Remove `'rules'` from `ComponentsTabType` and its `TabsTrigger` and body branch in `Components.tsx`.

- [ ] **Step 7: Run the client check**

Run: `cd client && npm run check` (timeout 600000)

Expected: exit 0. `sort-keys` violations are not auto-fixable — fix them by hand. If tests fail, confirm
`node --version` is 22.19+ or 24 (not 26) and that `node_modules` is current before concluding anything.

- [ ] **Step 8: Commit**

```bash
git add client
git commit -m "$(cat <<'EOF'
client - Move Rules under AI Agents settings and scope it to the workspace

Rules govern agent tool calls, which is what guardrails govern, so the page
belongs beside them rather than under Components. An inherited settings value is
labelled as inherited, because "off by choice" and "off by inheritance" behave
differently when the tenant default changes.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Update the documentation

**Files:**
- Modify: `.agents/component-rules.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-09-02-component-rules-tool-governance-design.md`

- [ ] **Step 1: Update the deep-dive**

In `.agents/component-rules.md`, add a "Workspace scoping" section covering: a rule names one workspace or all
of them; enforcement unions the workspace's rules with the tenant-wide ones; the workspace is resolved from the
job principal via the shared resolver and **any failure narrows to tenant-wide rules rather than to none**; the
enforcement cache is keyed by tenant, component and workspace, for the same reason it already carries the
tenant; settings are a tenant default with per-workspace overrides.

Correct the "What a rule governs" section, which currently says a rule is keyed on `(componentName, toolName)` —
it is now `(componentName, toolName, workspaceId)`.

Correct the page location: it is no longer under Components settings.

- [ ] **Step 2: Update CLAUDE.md**

In the Component Rules section, add one line that rules are workspace-scoped with a nullable column where null
means every workspace, and that the page lives under AI Agents settings. Keep it to the cross-cutting facts;
the detail belongs in the deep-dive.

- [ ] **Step 3: Note the superseded non-goal**

At the top of the previous spec, under its Status line, add:

```markdown
**Amended by:** `2026-09-03-component-rules-workspace-scoping-design.md`, which makes rules workspace-scoped.
This spec inherited "tenant-wide only" from its own predecessor by silence rather than by decision.
```

- [ ] **Step 4: Verify and commit**

Run: `./gradlew spotlessApply` (timeout 600000), then:

```bash
git add .agents CLAUDE.md docs
git commit -m "$(cat <<'EOF'
Document workspace-scoped component rules

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Full verification**

Run: `./gradlew check --continue > /tmp/wfinal.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/wfinal.log`

Compare any failure against the branch's known-unrelated set before attributing it: `LiquibaseMarkRanRollbackTest`
in `server-app:test` and four `IdentityProviderServiceIntTest` failures in `platform-user-service:testIntegration`
both fail independently of this work. Report which failures you attribute to this plan and which you do not.

Run: `cd client && npm run check` (timeout 600000).

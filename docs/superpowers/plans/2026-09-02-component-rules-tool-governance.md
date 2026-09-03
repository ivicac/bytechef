# Component Rules Tool Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-scope Component Rules from workflow action executions to AI agent tool calls, and add approval as a
third enforcement outcome, a tenant-wide observe mode, per-rule fail-closed evaluation, and a risk level on tools.

**Architecture:** Enforcement moves out of `ActionDefinitionServiceImpl` into a `RuleEnforcingToolCallback` wrapper
applied in `ClusterElementToolCallbacks.build`, the one place every agent tool callback is produced. The CE SPI
`ComponentRuleEnforcer` is reshaped from "return a refusal string" to "return a Decision" (Allow / Block /
RequireApproval). RequireApproval reuses the approval gate's suspend-and-resume protocol through a helper extracted
from `ApprovalGateToolCallback`, so a rule approval resumes through the gate's existing resume branch.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC, Liquibase, Spring AI (ToolCallback / ToolCallingManager),
Caffeine, GraphQL (Spring for GraphQL), React 19 + TypeScript + Vitest, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-02-component-rules-tool-governance-design.md`

## Global Constraints

- Working directory is the worktree `/Volumes/Data/bytechef/bytechef/.claude/worktrees/component-rules-approval`,
  branch `component-rules-approval`. Never `cd` to the main checkout. Never use bare `git stash`.
- Files under `server/ee/` use the **ByteChef Enterprise license header** (copy the header from a neighbouring EE
  file) and carry a `@version ee` Javadoc tag. Files under `server/libs/` and `sdks/` use the Apache 2.0 header.
- Java style: one blank line before `if` / `for` / `while` / `try` / `switch` (not after an opening brace, not
  before `} else {`); one blank line between a variable mutation and the next statement that uses it; no blank line
  before a class's closing brace; no `_` prefix on private methods; descriptive variable names, never `u` or `o`.
- No `TODO:` comments (Checkstyle `TodoComment` forbids them). No code comments explaining *what changed* — put
  rationale in the commit message.
- Test class names end in `Test`; integration test class names end in `IntTest`. Test method names are camelCase
  with no underscores, including private helpers.
- Enum values persisted as INT ordinals are **append-only**: add at the end, never reorder.
- Client: object keys sorted alphabetically (ESLint `sort-keys`, not auto-fixable); named imports sorted inside
  `{}`; lucide icons imported with the `Icon` suffix; `twMerge` for class merging, never `cn()`; interfaces end in
  `I` or `Props`; hook order is `useState` → `useRef` → store hooks → other hooks → `useMemo`/`useCallback` →
  `useEffect` → `return`.
- Before committing server changes: `./gradlew spotlessApply`. Never judge a Gradle run piped into `tail`/`grep` —
  redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- Before committing client changes: `cd client && npm run check` with a tool timeout of at least 600000 ms.
- Commit message style: `<ticket_number> <description>` for server, `<ticket_number> client - <description>` for
  client. This work has no ticket number, so use a plain imperative description.
- Every commit message ends with:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## File Structure

**Renamed / re-scoped (EE rule module, `server/ee/libs/platform/platform-component-rule/`):**
- `platform-component-rule-api/.../ComponentRule.java` — `actionName` → `toolName`, `strict` added,
  `RuleAction.REQUIRE_APPROVAL` appended.
- `platform-component-rule-api/.../ComponentRuleSettings.java` — **new**, tenant-wide settings record.
- `platform-component-rule-api/.../ComponentRuleSettingsService.java` — **new**.
- `platform-component-rule-api/.../ComponentRuleConditionStubContext.java` — root keys follow the new evaluation
  context (`toolName`, `toolCallName`).
- `platform-component-rule-api/.../ComponentRuleErrorType.java` — `APPROVAL_AFTER_UNSUPPORTED` appended.
- `platform-component-rule-service/.../ComponentRuleEnforcerImpl.java` — implements the reshaped SPI.
- `platform-component-rule-service/.../service/ComponentRuleSettingsServiceImpl.java` — **new**, `PropertyService`-backed.
- `platform-component-rule-service/.../audit/ComponentRuleAuditEvent.java` — four events appended.
- `platform-component-rule-service/.../audit/ComponentRuleAuditPublisher.java` — payload gains fields.
- `.../resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml` —
  edited in place (unreleased schema).

**CE platform:**
- `platform-component-api/.../rule/ComponentRuleEnforcer.java` — reshaped SPI (`ToolCall`, `Decision`).
- `platform-component-api/.../rule/ToolRiskLevelResolver.java` — **new**, declared-or-inferred risk.
- `platform-component-service/.../ActionDefinitionServiceImpl.java` — all rule code removed.
- `platform-component-service/.../ClusterElementDefinitionServiceImpl.java` — per-operation visibility on the tool path.
- `platform-component-api/.../domain/ClusterElementDefinition.java` — `riskLevel` field.

**Agent / LLM modules:**
- `ai/llm/.../tool/RuleEnforcingToolCallback.java` — **new**, the enforcement wrapper.
- `ai/llm/.../tool/ToolApprovalRequests.java` — **new**, raise-an-approval helper extracted from the gate.
- `ai/llm/.../tool/ClusterElementToolCallbacks.java` — wraps everything it builds.
- `ai/agent/utils/.../cluster/ApprovalGateToolCallback.java` — delegates raising to the helper.
- `ai/agent/.../action/AbstractAiAgentChatAction.java` — resume passes `APPROVED_BY`, records rule resolutions.

**SDK:** `component-api/.../definition/RiskLevel.java` (**new**), `ActionDefinition.java`,
`ClusterElementDefinition.java`, `ComponentDsl.java`.

**GraphQL:** `component-rule.graphqls`, `ComponentRuleGraphQlController.java`, `cluster-element-definition.graphqls`.

**Client:** `ee/pages/settings/platform/component-rules/**`, `graphql/platform/component-rule/*.graphql`.

---

### Task 1: Rename `actionName` to `toolName` in the rule module

Rules will govern tool cluster elements, so the column and every field carrying an action name is renamed first,
while enforcement still runs on the old chokepoint. The schema is unreleased (the module exists only on `0_732`),
so the init changelog is edited in place rather than migrated.

**Files:**
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml:14`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRule.java`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleConditionStubContext.java`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceImpl.java:103`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerImpl.java`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/audit/ComponentRuleAuditPublisher.java`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-graphql/src/main/resources/graphql/component-rule.graphqls`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-graphql/src/main/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlController.java`
- Test: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/repository/ComponentRuleRepositoryIntTest.java`
- Test: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceTest.java`
- Test: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`
- Test: `server/ee/libs/platform/platform-component-rule/platform-component-rule-graphql/src/test/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlControllerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ComponentRule.getToolName()` / `setToolName(@Nullable String)` / `appliesToTool(String toolName)`;
  DB column `component_rule.tool_name`; GraphQL field `toolName` on `ComponentRule` and argument `toolName` on
  `saveComponentRule`; `ComponentRuleGraphQlController.ComponentRuleItem` component `toolName`.

- [ ] **Step 1: Change the failing test first — rename the column expectation in the repository IntTest**

In `ComponentRuleRepositoryIntTest.java`, replace every `setActionName(` with `setToolName(` and every
`getActionName()` with `getToolName()`. Three occurrences of each across the three test methods, plus this
assertion at the end of `testFindAllByComponentNameAndEnabledReturnsOnlyEnabledRows`:

```java
        assertThat(enabledComponentRules.getFirst()
            .getToolName()).isEqualTo("sendMessage");
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t1.log | head`

Expected: FAIL, `cannot find symbol: method setToolName`.

- [ ] **Step 3: Rename the column in the init changelog**

In `20260831000001_component_rule_init.xml`, line 14, change:

```xml
            <column name="action_name" type="VARCHAR(256)"/>
```

to:

```xml
            <column name="tool_name" type="VARCHAR(256)"/>
```

- [ ] **Step 4: Rename the field on the domain class**

In `ComponentRule.java`, replace the field, both accessors and the predicate:

```java
    @Column("tool_name")
    private @Nullable String toolName;
```

```java
    public @Nullable String getToolName() {
        return toolName;
    }

    public void setToolName(@Nullable String toolName) {
        this.toolName = toolName;
    }
```

```java
    /**
     * Whether this rule governs the given tool: either it names that tool, or it names none and therefore governs
     * every tool of its component.
     */
    public boolean appliesToTool(String toolName) {
        return this.toolName == null || this.toolName.equals(toolName);
    }
```

Update the class Javadoc's second paragraph to read:

```java
 * <p>
 * {@code toolName} is nullable: null means the rule applies to every tool of the component. There is deliberately no
 * composite unique key — one component/tool pair may carry several unrelated rules.
 * </p>
```

- [ ] **Step 5: Update the remaining server call sites**

`ComponentRuleServiceImpl.java:103` — `persistedComponentRule.setToolName(componentRule.getToolName());`

`ComponentRuleEnforcerImpl.java` — in `getMatchingComponentRules`, change
`!componentRule.appliesToAction(actionCall.actionName())` to
`!componentRule.appliesToTool(actionCall.actionName())`. (The SPI still says `actionName()`; Task 2 reshapes it.)

`ComponentRuleAuditPublisher.java` — rename the record component and its map key:

```java
    public record ComponentRuleAuditPayload(
        long ruleId, String componentName, String toolName, String phase, @Nullable Long jobId,
        @Nullable Long taskExecutionId) {

        Map<String, Object> toMap() {
            Map<String, Object> data = new HashMap<>();

            data.put("ruleId", String.valueOf(ruleId));
            data.put("componentName", componentName);
            data.put("toolName", toolName);
            data.put("phase", phase);
```

`ComponentRuleConditionStubContext.get()` — replace the `actionName` key with two keys, so the stub matches the
context the enforcer will build in Task 2:

```java
        context.put("inputParameters", Map.of());
        context.put("componentName", "");
        context.put("toolName", "");
        context.put("toolCallName", "");
        context.put("connectionId", null);
        context.put("output", Map.of());
```

`component-rule.graphqls` — rename `actionName: String` to `toolName: String` in the `ComponentRule` type and the
`saveComponentRule` argument list.

`ComponentRuleGraphQlController.java` — rename the `@Argument @Nullable String actionName` parameter to `toolName`,
the `componentRule.setActionName(...)` call to `setToolName(toolName)`, the `ComponentRuleItem` component from
`actionName` to `toolName`, and the `toItem` argument from `componentRule.getActionName()` to `getToolName()`.

- [ ] **Step 6: Update the remaining tests**

In `ComponentRuleServiceTest.java`, `ComponentRuleEnforcerTest.java` and `ComponentRuleGraphQlControllerTest.java`,
rename every `setActionName` → `setToolName`, `getActionName` → `getToolName`, `actionName()` →
`toolName()` on `ComponentRuleItem`, and the `newComponentRule(long id, String actionName, ...)` helper parameter
to `toolName`. In `ComponentRuleEnforcerTest`, also rename `testNullActionNameRuleAppliesToEveryAction` to
`testNullToolNameRuleAppliesToEveryTool` and `testRuleScopedToAnotherActionDoesNotFire` to
`testRuleScopedToAnotherToolDoesNotFire`. In `ComponentRuleGraphQlControllerTest`, the two `saveComponentRule`
calls keep their positional arguments unchanged.

- [ ] **Step 7: Run the module's tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:test :server:ee:libs:platform:platform-component-rule:platform-component-rule-api:test --continue > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t1.log`

Expected: exit=0, no FAILED tasks.

- [ ] **Step 8: Run the repository integration test**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:testIntegration > /tmp/t1i.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t1i.log`

Expected: exit=0. Docker must be running (Testcontainers). If the socket is missing, point `DOCKER_HOST` at the
OrbStack socket: `export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-component-rule
git commit -m "$(cat <<'EOF'
Rename the component rule action name to tool name

Rules are about to govern agent tool calls rather than workflow action
executions, so the column and every field carrying an action name is renamed.
The schema is unreleased, so the init changelog is edited in place.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Reshape the SPI and remove the action chokepoint

The SPI moves from "return a refusal string for an action call" to "return a Decision for a tool call", and
`ActionDefinitionServiceImpl` stops enforcing rules entirely. After this task nothing enforces rules — Task 3
re-attaches enforcement on the tool path.

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/rule/ComponentRuleEnforcer.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/service/ActionDefinitionServiceImpl.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/exception/ActionDefinitionErrorType.java`
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerImpl.java`
- Delete: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceImplRuleTest.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceImplVisibilityTest.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServicePolyglotPerformTest.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceEnvironmentContextTest.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceWebSocketPerformTest.java`
- Test: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`

**Interfaces:**
- Consumes: `ComponentRule.appliesToTool(String)` from Task 1.
- Produces: `ComponentRuleEnforcer.ToolCall(String componentName, String toolName, String toolCallName,
  Map<String, ?> inputParameters, @Nullable Long connectionId, @Nullable Long jobId, @Nullable Long taskExecutionId,
  @Nullable String approvedBy)`; sealed `Decision` with `Allow`, `Block(String reason)`,
  `RequireApproval(List<Long> ruleIds, String title, String description, Instant expiresAt)`;
  `Decision checkBeforeCall(ToolCall)`, `void recordAfterCall(ToolCall, @Nullable Object output)`,
  `void recordApprovalResolution(List<Long> ruleIds, ToolCall toolCall, boolean approved, @Nullable String approvedBy)`.
  `ActionDefinitionServiceImpl` constructor drops its `List<ComponentRuleEnforcer>` parameter, leaving
  `(ComponentDefinitionRegistry, ContextFactory, List<ComponentVisibilityProvider>)`.

- [ ] **Step 1: Write the failing test — rewrite the enforcer test against the new SPI**

Replace the whole of `ComponentRuleEnforcerTest.java` below the imports. Keep the `FakeTicker` class verbatim. The
new constant and the four representative tests (the rest of the existing tests are mechanically converted the same
way: `checkBeforePerform` → `checkBeforeCall`, `recordAfterPerform` → `recordAfterCall`, and a null return becomes
`isInstanceOf(Decision.Allow.class)`):

```java
    private static final ToolCall SEND_MESSAGE_TO_C05 = new ToolCall(
        "slack", "sendMessage", "SLACK_SEND_MESSAGE", Map.of("channel", "C05QG7RF30A", "text", "hello"), 3L, 42L, 7L,
        null);

    @Test
    void testNoRulesAllows() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Allow.class);

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testMatchingBeforeBlockRuleReturnsABlockDecision() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK,
                    "inputParameters['channel'] == 'C05QG7RF30A'")));

        Decision decision = componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05);

        assertThat(decision).isInstanceOfSatisfying(
            Decision.Block.class, block -> assertThat(block.reason()).contains("blocked by an administrator rule"));

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_BLOCKED), any());
    }

    @Test
    void testToolCallNameIsAvailableInTheConditionContext() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    13L, null, RulePhase.BEFORE, RuleAction.BLOCK, "toolCallName == 'SLACK_SEND_MESSAGE'")));

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Block.class);
    }

    @Test
    void testUnresolvableConditionDoesNotBlock() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    7L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "missingRoot['whatever'] == 1")));

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Allow.class);
    }
```

Add the import `com.bytechef.platform.component.rule.ComponentRuleEnforcer.Decision` and change the `ActionCall`
import to `...ComponentRuleEnforcer.ToolCall`. Rename the helper's second parameter from `actionName` to `toolName`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/t2.log | head`

Expected: FAIL, `cannot find symbol: class ToolCall`.

- [ ] **Step 3: Reshape the SPI**

Replace the body of `ComponentRuleEnforcer.java` (keep the Apache header and package):

```java
package com.bytechef.platform.component.rule;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Extension point for administrative, conditional governance of a single AI agent tool call. CE ships no
 * implementation, so every call is a no-op and behaviour is unchanged; EE ships a persistence-backed implementation
 * that evaluates admin-authored conditions against the call's actual input (and, after the fact, its output).
 *
 * <p>
 * This governs tool calls, not workflow action executions: the risky calls are the ones a model chooses at runtime.
 * The enforcement point is the tool callback wrapper, not the action-execution chokepoint, because a tool produced
 * dynamically by a provider element never passes through the latter.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleEnforcer {

    /**
     * One tool invocation, reduced to the facts a rule can be written against.
     *
     * @param componentName   the component the tool element belongs to
     * @param toolName        the cluster element name — the name a rule is keyed on
     * @param toolCallName    the name the model invoked, which differs from {@code toolName} for a tool produced by a
     *                        provider element; a rule on the provider element governs all of its tools
     * @param inputParameters the tool's resolved input for this call
     * @param connectionId    the id of the first connection wired to this call, or {@code null} when there is none
     * @param jobId           the id of the job this call runs under, or {@code null} when there is none
     * @param taskExecutionId the id of the task execution this call belongs to, or {@code null}
     * @param approvedBy      the verified reviewer of a human-approved re-execution, or {@code null} on a first
     *                        attempt. When set, a matching approval rule is already satisfied; block and tag rules
     *                        are still evaluated.
     */
    @SuppressFBWarnings("EI")
    record ToolCall(
        String componentName, String toolName, String toolCallName, Map<String, ?> inputParameters,
        @Nullable Long connectionId, @Nullable Long jobId, @Nullable Long taskExecutionId,
        @Nullable String approvedBy) {
    }

    /**
     * What the caller must do with the call. Precedence among matching rules is fixed: any block wins, else any
     * approval wins, else tags are recorded and the call proceeds.
     */
    sealed interface Decision permits Decision.Allow, Decision.Block, Decision.RequireApproval {

        /**
         * Run the tool.
         */
        record Allow() implements Decision {
        }

        /**
         * Refuse the tool, reporting {@code reason} to the model.
         */
        record Block(String reason) implements Decision {
        }

        /**
         * Pause for a human. {@code ruleIds} carries every matching approval rule, so one decision satisfies all of
         * them.
         */
        @SuppressFBWarnings("EI")
        record RequireApproval(List<Long> ruleIds, String title, String description, Instant expiresAt)
            implements Decision {
        }
    }

    /**
     * Runs before the tool executes. Never throws: an enforcement failure must not fail an agent turn.
     */
    Decision checkBeforeCall(ToolCall toolCall);

    /**
     * Runs after the tool returns, with its output. Never throws: the tool has already run and its side effects
     * already happened.
     */
    void recordAfterCall(ToolCall toolCall, @Nullable Object output);

    /**
     * Records a human's decision on an approval this enforcer required. Never throws.
     */
    void recordApprovalResolution(
        List<Long> ruleIds, ToolCall toolCall, boolean approved, @Nullable String approvedBy);
}
```

- [ ] **Step 4: Strip rule enforcement from the action service**

In `ActionDefinitionServiceImpl.java`, delete:
- the import `com.bytechef.platform.component.rule.ComponentRuleEnforcer` (line 71);
- the field `componentRuleEnforcers` and its constructor parameter and assignment (lines 100, 106, 111), leaving:

```java
    @SuppressFBWarnings("EI2")
    public ActionDefinitionServiceImpl(
        @Lazy ComponentDefinitionRegistry componentDefinitionRegistry, ContextFactory contextFactory,
        List<ComponentVisibilityProvider> componentVisibilityProviders) {

        this.componentDefinitionRegistry = componentDefinitionRegistry;
        this.contextFactory = contextFactory;
        this.componentVisibilityProviders = componentVisibilityProviders;
    }
```

- in `doExecutePerform`, the `actionCall` local, the `checkRulesBeforePerform` call, and the
  `if (!(result instanceof ActionContext.Suspend))` guard around `recordRulesAfterPerform`, so the method reads:

```java
        checkComponentVisible(componentName);
        checkActionVisible(componentName, actionName);

        return doExecutePerformInternal(
            componentName, componentVersion, actionName, jobPrincipalId, jobPrincipalWorkflowId, jobId,
            taskExecutionId, workflowId, inputParameters, componentConnections, extensions, environmentId,
            editorEnvironment, type, continueParameters, resumeData, suspendExpiresAt);
```

- in `executePerformForPolyglot`, the `actionCall` local, the `checkRulesBeforePerform` call and both
  `recordRulesAfterPerform` calls, so each branch returns its result directly:

```java
        if (basePerformFunction instanceof PerformFunction performFunction) {
            return executeSingleConnectionPerform(performFunction, inputParameters, componentConnection, context);
        }
```

and, for the multi-connection branch, `return executeMultipleConnectionsPerform(performFunction, inputParameters,
connections, extensions, context);`

- the three private methods `toActionCall`, `checkRulesBeforePerform` and `recordRulesAfterPerform` (lines 1005-1033).

If `ActionContext` becomes an unused import after removing the `instanceof Suspend` guard, leave it — it is used
elsewhere in the file; let the compiler decide.

In `ActionDefinitionErrorType.java`, delete the `RULE_BLOCKED` constant.

- [ ] **Step 5: Update the four action-service tests**

Each of `ActionDefinitionServicePolyglotPerformTest`, `ActionDefinitionServiceEnvironmentContextTest`,
`ActionDefinitionServiceWebSocketPerformTest` and `ActionDefinitionServiceImplVisibilityTest` (four call sites)
constructs `new ActionDefinitionServiceImpl(...)` with a trailing `List.of()` for the enforcers. Delete that
trailing argument from all seven constructor calls. Delete `ActionDefinitionServiceImplRuleTest.java`.

- [ ] **Step 6: Rewrite the enforcer implementation against the new SPI**

In `ComponentRuleEnforcerImpl.java`, replace the two public methods and the two private helpers. Keep the
constructors, the cache and `getCachedComponentRules` unchanged:

```java
    @Override
    public Decision checkBeforeCall(ToolCall toolCall) {
        // The cache is consulted before the evaluation context is built, so a component with no rules at all costs
        // one cache hit and nothing else.
        if (getCachedComponentRules(toolCall.componentName())
            .isEmpty()) {

            return ALLOW;
        }

        List<ComponentRule> matchingComponentRules = getMatchingComponentRules(
            toolCall, RulePhase.BEFORE, buildEvaluationContext(toolCall, null));

        if (matchingComponentRules.isEmpty()) {
            return ALLOW;
        }

        for (ComponentRule componentRule : matchingComponentRules) {
            if (componentRule.getRuleAction() == RuleAction.BLOCK) {
                publish(ComponentRuleAuditEvent.RULE_BLOCKED, componentRule, toolCall, RulePhase.BEFORE);

                return new Decision.Block(
                    "Tool '%s' of component '%s' was blocked by an administrator rule."
                        .formatted(toolCall.toolName(), toolCall.componentName()));
            }
        }

        // Only reached when nothing blocked — block wins over tag, and a refused call has nothing to review.
        for (ComponentRule componentRule : matchingComponentRules) {
            publish(ComponentRuleAuditEvent.RULE_TAGGED, componentRule, toolCall, RulePhase.BEFORE);
        }

        return ALLOW;
    }

    @Override
    public void recordAfterCall(ToolCall toolCall, @Nullable Object output) {
        // The tool has already run; nothing here may fail the turn.
        try {
            List<ComponentRule> matchingComponentRules = getMatchingComponentRules(
                toolCall, RulePhase.AFTER, buildEvaluationContext(toolCall, output));

            for (ComponentRule componentRule : matchingComponentRules) {
                publish(ComponentRuleAuditEvent.RULE_TAGGED, componentRule, toolCall, RulePhase.AFTER);
            }
        } catch (RuntimeException exception) {
            log.warn(
                "Could not evaluate AFTER-phase rules for {}#{}", toolCall.componentName(), toolCall.toolName(),
                exception);
        }
    }

    @Override
    public void recordApprovalResolution(
        List<Long> ruleIds, ToolCall toolCall, boolean approved, @Nullable String approvedBy) {

        log.debug("Approval resolution for component rules {}: approved={}", ruleIds, approved);
    }
```

Nothing raises an approval yet, so this method is unreachable until Task 7 gives it its audit events. It logs at
debug rather than staying empty, because Checkstyle's `EmptyBlock` rule is not satisfied by a comment alone.

Add the constant `private static final Decision ALLOW = new Decision.Allow();` beside the other constants, and
change the two private helpers to take a `ToolCall`:

```java
    private List<ComponentRule> getMatchingComponentRules(
        ToolCall toolCall, RulePhase rulePhase, Map<String, Object> evaluationContext) {

        List<ComponentRule> componentRules = getCachedComponentRules(toolCall.componentName());

        if (componentRules.isEmpty()) {
            return List.of();
        }

        List<ComponentRule> matchingComponentRules = new ArrayList<>();

        for (ComponentRule componentRule : componentRules) {
            if (componentRule.getPhase() != rulePhase || !componentRule.appliesToTool(toolCall.toolName())) {
                continue;
            }

            if (matches(componentRule, evaluationContext)) {
                matchingComponentRules.add(componentRule);
            }
        }

        return matchingComponentRules;
    }

    private static Map<String, Object> buildEvaluationContext(ToolCall toolCall, @Nullable Object output) {
        Map<String, Object> evaluationContext = new HashMap<>();

        evaluationContext.put("inputParameters", toolCall.inputParameters());
        evaluationContext.put("componentName", toolCall.componentName());
        evaluationContext.put("toolName", toolCall.toolName());
        evaluationContext.put("toolCallName", toolCall.toolCallName());
        evaluationContext.put("connectionId", toolCall.connectionId());

        if (output != null) {
            evaluationContext.put("output", output);
        }

        return evaluationContext;
    }
```

`publish` takes a `ToolCall` and passes `toolCall.toolName()` into the payload's `toolName` component.

Update the class Javadoc's first paragraph to say "an agent tool call on a component with no rules" instead of "an
action execution".

- [ ] **Step 7: Run the affected module tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test :server:libs:platform:platform-component:platform-component-service:test --continue > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t2.log`

Expected: exit=0, no FAILED tasks.

- [ ] **Step 8: Compile the whole server to catch other call sites**

Run: `./gradlew compileJava compileTestJava --continue > /tmp/t2c.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t2c.log | head -30`

Expected: exit=0.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply
git add server
git commit -m "$(cat <<'EOF'
Move component rule enforcement off the action execution chokepoint

The SPI now answers with a decision about a tool call rather than a refusal
string about an action call, and ActionDefinitionServiceImpl no longer consults
it. Nothing enforces rules until the tool callback wrapper lands.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Enforce rules on agent tool callbacks

`RuleEnforcingToolCallback` wraps every callback `ClusterElementToolCallbacks.build` produces. It is deliberately
NOT a `DelegatingToolCallback`: `DelegatingToolCallback.unwrap` is recursive and the gate-resume path uses it to
bypass the gate, so the rule layer must survive that unwrap and stay innermost.

**Files:**
- Create: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/RuleEnforcingToolCallback.java`
- Modify: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/ClusterElementToolCallbacks.java`
- Modify: `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java:193`
- Modify: `server/libs/modules/components/ai/llm/build.gradle.kts`
- Test: `server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/tool/RuleEnforcingToolCallbackTest.java`

**Interfaces:**
- Consumes: `ComponentRuleEnforcer.ToolCall` / `Decision` from Task 2.
- Produces: `RuleEnforcingToolCallback(ToolCallback delegate, ClusterElement clusterElement,
  List<ComponentRuleEnforcer> componentRuleEnforcers, @Nullable Long connectionId, ActionContext actionContext)`;
  `ClusterElementToolCallbacks(AiAgentToolFacade, ClusterElementDefinitionService, List<ComponentRuleEnforcer>)`.

- [ ] **Step 1: Write the failing test**

Create `RuleEnforcingToolCallbackTest.java` (Apache header, package `com.bytechef.component.ai.llm.tool`):

```java
class RuleEnforcingToolCallbackTest {

    private final ToolCallback delegate = mock(ToolCallback.class);
    private final ComponentRuleEnforcer componentRuleEnforcer = mock(ComponentRuleEnforcer.class);

    private ActionContextAware actionContext;

    private static final ClusterElement CLUSTER_ELEMENT = new ClusterElement(
        null, null, Map.of(), null, "slack/v1/sendMessage", Map.of(), "slack_1");

    @BeforeEach
    void setUp() {
        actionContext = mock(
            ActionContextAware.class,
            withSettings().extraInterfaces(ActionContext.class));

        when(delegate.getToolDefinition()).thenReturn(
            DefaultToolDefinition.builder()
                .name("SLACK_SEND_MESSAGE")
                .description("Send a Slack message")
                .inputSchema("{}")
                .build());
    }

    @Test
    void testAllowExecutesTheDelegateAndRecordsTheOutput() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());
        when(delegate.call(anyString(), any())).thenReturn("sent");

        assertThat(newToolCallback().call("{\"channel\": \"#general\"}", null)).isEqualTo("sent");

        verify(componentRuleEnforcer).recordAfterCall(any(), eq("sent"));
    }

    @Test
    void testBlockReturnsADenialAndNeverExecutesTheDelegate() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Block("nope"));

        String result = newToolCallback().call("{}", null);

        assertThat(result)
            .contains("\"blocked\":true")
            .contains("nope");

        verify(delegate, never()).call(anyString(), any());
        verify(componentRuleEnforcer, never()).recordAfterCall(any(), any());
    }

    @Test
    void testTheToolCallCarriesTheElementNameAndTheModelChosenArguments() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());

        newToolCallback().call("{\"channel\": \"#general\"}", null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        ToolCall toolCall = toolCallCaptor.getValue();

        assertThat(toolCall.componentName()).isEqualTo("slack");
        assertThat(toolCall.toolName()).isEqualTo("sendMessage");
        assertThat(toolCall.toolCallName()).isEqualTo("SLACK_SEND_MESSAGE");
        assertThat(toolCall.inputParameters()).containsEntry("channel", "#general");
    }

    @Test
    void testAnEnforcerFailureNeverFailsTheToolCall() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenThrow(new IllegalStateException("database is down"));
        when(delegate.call(anyString(), any())).thenReturn("sent");

        assertThat(newToolCallback().call("{}", null)).isEqualTo("sent");
    }

    @Test
    void testUnwrapDoesNotStripTheRuleLayer() {
        // The gate-resume path unwraps to the innermost non-delegating callback to bypass the gate. The rule layer
        // must survive that, so a human-approved re-execution is still rule-checked.
        RuleEnforcingToolCallback toolCallback = newToolCallback();

        assertThat(DelegatingToolCallback.unwrap(toolCallback)).isSameAs(toolCallback);
    }

    private RuleEnforcingToolCallback newToolCallback() {
        return new RuleEnforcingToolCallback(
            delegate, CLUSTER_ELEMENT, List.of(componentRuleEnforcer), 3L, (ActionContext) actionContext);
    }
}
```

Imports: `static org.assertj.core.api.Assertions.assertThat`, the Mockito statics (`any`, `anyString`, `eq`,
`mock`, `never`, `verify`, `when`, `withSettings`), `com.bytechef.component.definition.ActionContext`,
`com.bytechef.platform.component.definition.ActionContextAware`,
`com.bytechef.platform.component.rule.ComponentRuleEnforcer`,
`com.bytechef.platform.component.rule.ComponentRuleEnforcer.Decision`,
`com.bytechef.platform.component.rule.ComponentRuleEnforcer.ToolCall`,
`com.bytechef.platform.configuration.domain.ClusterElement`, `java.util.List`, `java.util.Map`, the JUnit
annotations, `org.mockito.ArgumentCaptor`, `org.springframework.ai.tool.ToolCallback`,
`org.springframework.ai.tool.definition.DefaultToolDefinition`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:modules:components:ai:llm:test --tests '*RuleEnforcingToolCallbackTest' > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/t3.log | head`

Expected: FAIL, `cannot find symbol: class RuleEnforcingToolCallback`.

- [ ] **Step 3: Write the wrapper**

Create `RuleEnforcingToolCallback.java` (Apache header, package `com.bytechef.component.ai.llm.tool`):

```java
/**
 * Applies administrative component rules to one agent tool call. Wraps every callback
 * {@link ClusterElementToolCallbacks} produces, so a tool contributed by a provider element is governed exactly like
 * a plain component action attached as a tool.
 *
 * <p>
 * It reports the delegate's tool definition as its own, but is deliberately NOT a {@link DelegatingToolCallback}:
 * {@code DelegatingToolCallback.unwrap} is recursive and the gate-resume path uses it to execute an approved tool
 * without re-raising the gate. The rule layer must survive that unwrap and stay innermost, so a human-approved
 * re-execution is still rule-checked.
 * </p>
 *
 * <p>
 * A blocked call returns a denial to the model rather than throwing, mirroring the gate's rejection: the agent can
 * explain the refusal and replan instead of failing the run. Enforcement never fails a turn — an enforcer that
 * throws is logged and the call proceeds.
 * </p>
 *
 * @author Ivica Cardic
 */
public class RuleEnforcingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RuleEnforcingToolCallback.class);

    private final ActionContextAware actionContext;
    private final ClusterElement clusterElement;
    private final List<ComponentRuleEnforcer> componentRuleEnforcers;

    @Nullable
    private final Long connectionId;

    private final ToolCallback delegate;

    @SuppressFBWarnings("EI2")
    public RuleEnforcingToolCallback(
        ToolCallback delegate, ClusterElement clusterElement, List<ComponentRuleEnforcer> componentRuleEnforcers,
        @Nullable Long connectionId, ActionContext actionContext) {

        this.delegate = delegate;
        this.clusterElement = clusterElement;
        this.componentRuleEnforcers = List.copyOf(componentRuleEnforcers);
        this.connectionId = connectionId;
        this.actionContext = (ActionContextAware) actionContext;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        if (componentRuleEnforcers.isEmpty()) {
            return delegate.call(toolInput, toolContext);
        }

        ToolCall toolCall = toToolCall(toolInput, toolContext);
        Decision decision = checkBeforeCall(toolCall);

        if (decision instanceof Decision.Block block) {
            return JsonUtils.write(Map.of("blocked", true, "reason", block.reason()));
        }

        String result = delegate.call(toolInput, toolContext);

        recordAfterCall(toolCall, result);

        return result;
    }

    private Decision checkBeforeCall(ToolCall toolCall) {
        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            try {
                Decision decision = componentRuleEnforcer.checkBeforeCall(toolCall);

                if (!(decision instanceof Decision.Allow)) {
                    return decision;
                }
            } catch (RuntimeException exception) {
                log.warn(
                    "Could not evaluate component rules for tool '{}'; allowing the call",
                    toolCall.toolCallName(), exception);
            }
        }

        return new Decision.Allow();
    }

    private void recordAfterCall(ToolCall toolCall, @Nullable Object result) {
        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            try {
                componentRuleEnforcer.recordAfterCall(toolCall, result);
            } catch (RuntimeException exception) {
                log.warn("Could not record AFTER-phase rules for tool '{}'", toolCall.toolCallName(), exception);
            }
        }
    }

    private ToolCall toToolCall(String toolInput, @Nullable ToolContext toolContext) {
        ToolDefinition toolDefinition = delegate.getToolDefinition();

        return new ToolCall(
            clusterElement.getComponentName(), clusterElement.getClusterElementName(), toolDefinition.name(),
            parseToolInput(toolInput), connectionId, actionContext.getJobId(), actionContext.getTaskExecutionId(),
            fetchApprovedBy(toolContext));
    }

    /**
     * The model's arguments as a map, so a condition reads them the way it reads a workflow node's input. Malformed
     * JSON yields an empty map rather than failing the call: the delegate is the authority on its own input.
     */
    private static Map<String, ?> parseToolInput(String toolInput) {
        try {
            return JsonUtils.readMap(toolInput);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    @Nullable
    private static String fetchApprovedBy(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Map<String, Object> context = toolContext.getContext();

        return (String) context.get(AiAgentToolContextKey.APPROVED_BY);
    }
}
```

`AiAgentToolContextKey.APPROVED_BY` does not exist yet — add it now, in
`server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/constant/AiAgentToolContextKey.java`,
alphabetically first:

```java
    public static final String ACTION_CONTEXT = "actionContext";
    public static final String APPROVED_BY = "approvedBy";
    public static final String SSE_BUFFERED_EVENTS = "sseBufferedEvents";
    public static final String SSE_EMITTER_REFERENCE = "sseEmitterReference";
```

If `ActionContextAware` has no `getTaskExecutionId()`, use `null` for that argument and note it in the commit
message; check with:
`grep -n "getTaskExecutionId\|getJobId" server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/ActionContextAware.java`

- [ ] **Step 4: Wrap in the builder**

In `ClusterElementToolCallbacks.java`, add the enforcer list to the constructor and wrap every return:

```java
    private final AiAgentToolFacade aiAgentToolFacade;
    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final List<ComponentRuleEnforcer> componentRuleEnforcers;

    @SuppressFBWarnings("EI")
    public ClusterElementToolCallbacks(
        AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
        List<ComponentRuleEnforcer> componentRuleEnforcers) {

        this.aiAgentToolFacade = aiAgentToolFacade;
        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.componentRuleEnforcers = List.copyOf(componentRuleEnforcers);
    }
```

Rename the existing `build` to `doBuild` (same body, same four branches) and add the wrapping `build` in front of
it:

```java
    public List<ToolCallback> build(
        ClusterElement clusterElement, Map<String, ComponentConnection> componentConnections,
        boolean editorEnvironment, ActionContext context) {

        List<ToolCallback> toolCallbacks = doBuild(
            clusterElement, componentConnections, editorEnvironment, context);

        if (componentRuleEnforcers.isEmpty()) {
            return toolCallbacks;
        }

        ComponentConnection componentConnection = componentConnections.get(clusterElement.getWorkflowNodeName());
        Long connectionId = componentConnection == null ? null : componentConnection.connectionId();

        List<ToolCallback> ruleEnforcingToolCallbacks = new ArrayList<>();

        for (ToolCallback toolCallback : toolCallbacks) {
            ruleEnforcingToolCallbacks.add(
                new RuleEnforcingToolCallback(
                    toolCallback, clusterElement, componentRuleEnforcers, connectionId, context));
        }

        return ruleEnforcingToolCallbacks;
    }
```

In `AbstractAiAgentChatAction.java:193`, the two-argument construction becomes three. Add a
`List<ComponentRuleEnforcer> componentRuleEnforcers` parameter to the longest protected constructor, default it to
`List.of()` in the shorter overloads, and pass it through:

```java
        this.clusterElementToolCallbacks =
            new ClusterElementToolCallbacks(aiAgentToolFacade, clusterElementDefinitionService, componentRuleEnforcers);
```

Find every other construction with
`grep -rn "new ClusterElementToolCallbacks(" server --include='*.java'` and pass `List.of()` where the caller has
no enforcers (tests, the gate's own configuration if it builds one).

Add to `server/libs/modules/components/ai/llm/build.gradle.kts`, keeping the list alphabetical — it already has
`platform-component-api`, so no new dependency is needed; verify with
`grep -n platform-component-api server/libs/modules/components/ai/llm/build.gradle.kts` and only add if missing.

- [ ] **Step 5: Run the test**

Run: `./gradlew :server:libs:modules:components:ai:llm:test :server:libs:modules:components:ai:agent:test :server:libs:modules:components:ai:agent:utils:test --continue > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t3.log`

Expected: exit=0.

- [ ] **Step 6: Compile everything**

Run: `./gradlew compileJava compileTestJava --continue > /tmp/t3c.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t3c.log | head -30`

Expected: exit=0.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add server
git commit -m "$(cat <<'EOF'
Enforce component rules on agent tool callbacks

Every callback the cluster element builder produces is wrapped, so a tool from a
provider element is governed like a plain action attached as a tool. The wrapper
is not a DelegatingToolCallback, so the gate resume path's unwrap cannot strip it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Apply per-operation visibility on the tool path

`doExecuteTool` checks only component visibility, so a per-action policy that disables an operation does not stop
an agent from calling it as a tool. Same hole, same fix location.

**Files:**
- Modify: `server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/service/ClusterElementDefinitionServiceImpl.java`
- Test: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ClusterElementDefinitionServiceImplVisibilityTest.java`

**Interfaces:**
- Consumes: `ComponentVisibilityProvider.isActionVisible(String, String)`.
- Produces: nothing new; `executeTool` now throws `ConfigurationException` with
  `ClusterElementDefinitionErrorType.ACTION_DISABLED` for a hidden operation.

- [ ] **Step 1: Write the failing test**

Create `ClusterElementDefinitionServiceImplVisibilityTest.java`, modelled on the existing
`ActionDefinitionServiceImplVisibilityTest` in the same package (read it first for the exact constructor shape and
mocks):

```java
    @Test
    void testExecuteToolRefusesAnOperationHiddenByPolicy() {
        ComponentVisibilityProvider componentVisibilityProvider = mock(ComponentVisibilityProvider.class);

        when(componentVisibilityProvider.isVisible("slack")).thenReturn(true);
        when(componentVisibilityProvider.isActionVisible("slack", "sendMessage")).thenReturn(false);

        ClusterElementDefinitionServiceImpl service = new ClusterElementDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(componentVisibilityProvider),
            meterRegistryObjectProvider);

        assertThatThrownBy(() -> service.executeTool("slack", 1, "sendMessage", Map.of(), null, false))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("disabled by an administrator");
    }

    @Test
    void testExecuteToolAllowsAVisibleOperation() {
        ComponentVisibilityProvider componentVisibilityProvider = mock(ComponentVisibilityProvider.class);

        when(componentVisibilityProvider.isVisible("slack")).thenReturn(true);
        when(componentVisibilityProvider.isActionVisible("slack", "sendMessage")).thenReturn(true);

        ClusterElementDefinitionServiceImpl service = new ClusterElementDefinitionServiceImpl(
            componentDefinitionRegistry, contextFactory, List.of(componentVisibilityProvider),
            meterRegistryObjectProvider);

        // Reaching the registry lookup (and failing there) proves the visibility guard let the call through.
        assertThatThrownBy(() -> service.executeTool("slack", 1, "sendMessage", Map.of(), null, false))
            .isNotInstanceOf(ConfigurationException.class);
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-service:test --tests '*ClusterElementDefinitionServiceImplVisibilityTest' > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E "FAILED|expected" /tmp/t4.log | head`

Expected: FAIL on the first test — no exception is thrown.

- [ ] **Step 3: Add the guard**

In `ClusterElementDefinitionServiceImpl.java`, add beside `checkComponentVisible`:

```java
    private void checkClusterElementVisible(String componentName, String clusterElementName) {
        boolean visible = componentVisibilityProviders.stream()
            .allMatch(componentVisibilityProvider -> componentVisibilityProvider.isActionVisible(
                componentName, clusterElementName));

        if (!visible) {
            throw new ConfigurationException(
                "Operation '%s' of component '%s' is disabled by an administrator and cannot be executed."
                    .formatted(clusterElementName, componentName),
                ClusterElementDefinitionErrorType.ACTION_DISABLED);
        }
    }
```

Call it right after each of the three `checkComponentVisible(componentName);` calls inside the `executeTool`
overloads (lines 283, 300, 327), passing `clusterElementName`. Do NOT add it to `executeApprovalChannel` — an
approval channel is not a model-invoked call.

If `ClusterElementDefinitionErrorType` has no `ACTION_DISABLED`, add it as the next unused error key, following the
existing constants in that file.

- [ ] **Step 4: Run the test**

Run: `./gradlew :server:libs:platform:platform-component:platform-component-service:test > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t4.log`

Expected: exit=0.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-component
git commit -m "$(cat <<'EOF'
Apply per-operation visibility to agent tool execution

The tool path checked only component visibility, so a per-action policy that
disabled an operation still let an agent call it as a tool.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Add REQUIRE_APPROVAL, strict, and tenant settings to the model

Schema and validation only. Nothing raises an approval yet.

**Files:**
- Modify: `.../platform-component-rule-service/src/main/resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml`
- Modify: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRule.java`
- Modify: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleErrorType.java`
- Create: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleSettings.java`
- Create: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleSettingsService.java`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleSettingsServiceImpl.java`
- Modify: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceImpl.java`
- Modify: `.../platform-component-rule-service/build.gradle.kts`
- Test: `.../platform-component-rule-api/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnumOrdinalTest.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceTest.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleSettingsServiceTest.java`

**Interfaces:**
- Consumes: `PropertyService.fetchProperty(String, Scope, Long)` and `save(String, Map<String, ?>, Scope, Long)`.
- Produces: `RuleAction.REQUIRE_APPROVAL` (ordinal 2); `ComponentRule.isStrict()` / `setStrict(boolean)`;
  `record ComponentRuleSettings(boolean observeMode, int approvalExpiresInHours)` with
  `PROPERTY_KEY = "component_rule_settings"` and `DEFAULT = new ComponentRuleSettings(false, 1440)`;
  `ComponentRuleSettingsService.getSettings()` / `saveSettings(ComponentRuleSettings)`;
  `ComponentRuleErrorType.APPROVAL_AFTER_UNSUPPORTED` (error key 103).

- [ ] **Step 1: Write the failing tests**

In `ComponentRuleEnumOrdinalTest.java`, replace `testRuleActionOrdinalsAreStable`:

```java
    @Test
    void testRuleActionOrdinalsAreStable() {
        assertThat(RuleAction.BLOCK.ordinal()).isEqualTo(0);
        assertThat(RuleAction.TAG.ordinal()).isEqualTo(1);
        assertThat(RuleAction.REQUIRE_APPROVAL.ordinal()).isEqualTo(2);
        assertThat(RuleAction.values()).hasSize(3);
    }
```

In `ComponentRuleServiceTest.java`, add:

```java
    @Test
    void testSaveRejectsRequireApprovalInAfterPhase() {
        ComponentRule componentRule = newComponentRule(RulePhase.AFTER, RuleAction.REQUIRE_APPROVAL, "true");

        assertThatThrownBy(() -> componentRuleService.saveComponentRule(componentRule))
            .isInstanceOfSatisfying(ConfigurationException.class, configurationException -> {
                assertThat(configurationException.getEntityClass()).isEqualTo(ComponentRule.class);
                assertThat(configurationException.getErrorKey())
                    .isEqualTo(ComponentRuleErrorType.APPROVAL_AFTER_UNSUPPORTED.getErrorKey());
            });

        verify(componentRuleRepository, never()).save(any());
    }

    @Test
    void testSaveCarriesTheStrictFlagOntoThePersistedInstance() {
        ComponentRule persistedComponentRule = newComponentRule(RulePhase.BEFORE, RuleAction.BLOCK, "true");

        persistedComponentRule.setId(5L);

        when(componentRuleRepository.findById(5L)).thenReturn(Optional.of(persistedComponentRule));
        when(componentRuleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ComponentRule detachedComponentRule = newComponentRule(RulePhase.BEFORE, RuleAction.BLOCK, "true");

        detachedComponentRule.setId(5L);
        detachedComponentRule.setStrict(true);

        componentRuleService.saveComponentRule(detachedComponentRule);

        assertThat(persistedComponentRule.isStrict()).isTrue();
    }
```

Create `ComponentRuleSettingsServiceTest.java` (EE header, `@version ee`):

```java
class ComponentRuleSettingsServiceTest {

    private final PropertyService propertyService = mock(PropertyService.class);
    private final ComponentRuleSettingsServiceImpl componentRuleSettingsService =
        new ComponentRuleSettingsServiceImpl(propertyService);

    @Test
    void testGetSettingsFallsBackToTheDefaultWhenNoRowExists() {
        when(propertyService.fetchProperty(ComponentRuleSettings.PROPERTY_KEY, Scope.PLATFORM, null))
            .thenReturn(Optional.empty());

        assertThat(componentRuleSettingsService.getSettings()).isEqualTo(ComponentRuleSettings.DEFAULT);
    }

    @Test
    void testGetSettingsReadsThePlatformScopedRow() {
        Property property = mock(Property.class);

        when(property.getValue()).thenReturn(Map.of("observeMode", true, "approvalExpiresInHours", 24));
        when(propertyService.fetchProperty(ComponentRuleSettings.PROPERTY_KEY, Scope.PLATFORM, null))
            .thenReturn(Optional.of(property));

        ComponentRuleSettings componentRuleSettings = componentRuleSettingsService.getSettings();

        assertThat(componentRuleSettings.observeMode()).isTrue();
        assertThat(componentRuleSettings.approvalExpiresInHours()).isEqualTo(24);
    }

    @Test
    void testSaveSettingsWritesAPlatformScopedRowWithANullScopeId() {
        componentRuleSettingsService.saveSettings(new ComponentRuleSettings(true, 12));

        // Scope.PLATFORM + a null scopeId is the established shape for a tenant-wide row; Scope.WORKSPACE with a
        // sentinel id would alias a real workspace.
        verify(propertyService).save(
            eq(ComponentRuleSettings.PROPERTY_KEY),
            eq(Map.of("observeMode", true, "approvalExpiresInHours", 12)), eq(Scope.PLATFORM), isNull());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava :server:ee:libs:platform:platform-component-rule:platform-component-rule-api:compileTestJava --continue > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/t5.log | head`

Expected: FAIL, `cannot find symbol: REQUIRE_APPROVAL`.

- [ ] **Step 3: Extend the schema and the domain**

In the init changelog, add after the `enabled` column block:

```xml
            <column name="strict" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
```

In `ComponentRule.java`:

```java
    /**
     * What a firing rule does. INT ordinal persisted — append new values at the end, never reorder.
     */
    public enum RuleAction {
        BLOCK, TAG, REQUIRE_APPROVAL
    }
```

```java
    @Column("strict")
    private boolean strict;
```

```java
    /**
     * Whether an unevaluable condition counts as a match. Default false: the lenient evaluator returns the source
     * text for an unresolved reference, so a mis-authored rule does not fire and a BLOCK rule fails open. A strict
     * rule inverts that for the calls where fail-closed is worth the risk.
     */
    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }
```

In `ComponentRuleErrorType.java`, append `public static final ComponentRuleErrorType APPROVAL_AFTER_UNSUPPORTED =
new ComponentRuleErrorType(103);`

- [ ] **Step 4: Extend save validation**

In `ComponentRuleServiceImpl.saveComponentRule`, replace the BLOCK+AFTER guard with a guard covering both
enforcing actions, and copy `strict` on the update path:

```java
        if (componentRule.getPhase() == RulePhase.AFTER && componentRule.getRuleAction() == RuleAction.BLOCK) {
            throw new ConfigurationException(
                "A rule evaluated in the AFTER phase cannot BLOCK — the action has already run. Use TAG instead.",
                ComponentRuleErrorType.BLOCK_AFTER_UNSUPPORTED);
        }

        if (componentRule.getPhase() == RulePhase.AFTER &&
            componentRule.getRuleAction() == RuleAction.REQUIRE_APPROVAL) {

            throw new ConfigurationException(
                "A rule evaluated in the AFTER phase cannot require approval — the tool has already run. Use TAG " +
                    "instead.",
                ComponentRuleErrorType.APPROVAL_AFTER_UNSUPPORTED);
        }
```

and, after `persistedComponentRule.setEnabled(componentRule.isEnabled());`:

```java
        persistedComponentRule.setStrict(componentRule.isStrict());
```

- [ ] **Step 5: Add the settings record, interface and service**

`ComponentRuleSettings.java` (EE header, `@version ee`):

```java
package com.bytechef.ee.platform.component.rule;

/**
 * Tenant-wide component rule settings, stored as a single {@code Property} row rather than a table.
 *
 * @param observeMode            when true every rule is evaluated and none is enforced: a would-be block or
 *                               approval is audited as observed and the call proceeds. The way to turn rules on in
 *                               a live tenant without risking a mis-authored rule.
 * @param approvalExpiresInHours how long a rule-raised approval request stays valid. Defaults to 60 days, matching
 *                               the approval gate and the standalone Approval action.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public record ComponentRuleSettings(boolean observeMode, int approvalExpiresInHours) {

    public static final String PROPERTY_KEY = "component_rule_settings";

    public static final ComponentRuleSettings DEFAULT = new ComponentRuleSettings(false, 1440);
}
```

`ComponentRuleSettingsService.java`:

```java
public interface ComponentRuleSettingsService {

    /**
     * The tenant's settings, or {@link ComponentRuleSettings#DEFAULT} when none were ever saved.
     */
    ComponentRuleSettings getSettings();

    ComponentRuleSettings saveSettings(ComponentRuleSettings componentRuleSettings);
}
```

`ComponentRuleSettingsServiceImpl.java` (EE header, `@version ee`, `@Service @Transactional
@ConditionalOnEEVersion`), with a class Javadoc explaining the scope choice the way
`AiGuardrailsWorkspaceSettingsServiceImpl` does:

```java
    private static final String KEY_APPROVAL_EXPIRES_IN_HOURS = "approvalExpiresInHours";
    private static final String KEY_OBSERVE_MODE = "observeMode";

    private final PropertyService propertyService;

    public ComponentRuleSettingsServiceImpl(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @Override
    @Transactional(readOnly = true)
    public ComponentRuleSettings getSettings() {
        return propertyService.fetchProperty(ComponentRuleSettings.PROPERTY_KEY, Scope.PLATFORM, null)
            .map(property -> toSettings(property.getValue()))
            .orElse(ComponentRuleSettings.DEFAULT);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRuleSettings saveSettings(ComponentRuleSettings componentRuleSettings) {
        propertyService.save(
            ComponentRuleSettings.PROPERTY_KEY, toMap(componentRuleSettings), Scope.PLATFORM, null);

        return componentRuleSettings;
    }

    private static Map<String, Object> toMap(ComponentRuleSettings componentRuleSettings) {
        return Map.of(
            KEY_OBSERVE_MODE, componentRuleSettings.observeMode(),
            KEY_APPROVAL_EXPIRES_IN_HOURS, componentRuleSettings.approvalExpiresInHours());
    }

    private static ComponentRuleSettings toSettings(Map<String, ?> value) {
        Object observeMode = value.get(KEY_OBSERVE_MODE);
        Object approvalExpiresInHours = value.get(KEY_APPROVAL_EXPIRES_IN_HOURS);

        return new ComponentRuleSettings(
            observeMode instanceof Boolean observeModeBoolean
                ? observeModeBoolean : ComponentRuleSettings.DEFAULT.observeMode(),
            approvalExpiresInHours instanceof Number approvalExpiresInHoursNumber
                ? approvalExpiresInHoursNumber.intValue() : ComponentRuleSettings.DEFAULT.approvalExpiresInHours());
    }
```

Add to `platform-component-rule-service/build.gradle.kts`, keeping the list alphabetical:

```kotlin
    implementation(project(":server:libs:platform:platform-configuration:platform-configuration-api"))
    implementation(project(":server:libs:platform:platform-security:platform-security-api"))
```

Check whether `platform-security-api` is already pulled in transitively (the service already uses
`AuthorityConstants`) with
`grep -n "platform-security\|AuthorityConstants" server/ee/libs/platform/platform-component-rule/platform-component-rule-service/build.gradle.kts`
and only add what is missing.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-api:test :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test --continue > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t5.log`

Expected: exit=0.

- [ ] **Step 7: Run the repository integration test to prove the new column round-trips**

Add to `ComponentRuleRepositoryIntTest.testInsertRoundTripsEveryColumn`, before the save:
`componentRule.setStrict(true);` and after the reload:
`assertThat(reloadedComponentRule.isStrict()).isTrue();`

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:testIntegration > /tmp/t5i.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t5i.log`

Expected: exit=0.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-component-rule
git commit -m "$(cat <<'EOF'
Add the approval rule action, strict rules and tenant rule settings

REQUIRE_APPROVAL is appended to the rule action enum and rejected in the AFTER
phase for the same reason BLOCK is. Observe mode and the approval expiry live in
one platform-scoped property row rather than a new table.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Extract the approval-raising helper from the gate

Pure refactor. The gate's behaviour must not change; its existing tests are the proof.

**Files:**
- Create: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/ToolApprovalRequests.java`
- Modify: `server/libs/modules/components/ai/agent/utils/src/main/java/com/bytechef/component/ai/agent/utils/cluster/ApprovalGateToolCallback.java`
- Test: `server/libs/modules/components/ai/agent/utils/src/test/java/com/bytechef/component/ai/agent/utils/cluster/ApprovalGateToolCallbackTest.java` (unchanged — it must keep passing)

**Interfaces:**
- Consumes: `ClusterElementDefinitionService.executeApprovalChannel(...)`, `ActionContextAware`,
  `ToolSuspendConstants`.
- Produces: `ToolApprovalRequests.raise(ToolApprovalRequests.Request request)` where
  `Request(String toolName, String toolInput, String title, String description, Instant expiresAt,
  List<ClusterElement> approvalChannelClusterElements, Map<String, ComponentConnection> componentConnections,
  Map<String, Object> additionalContinueParameters, ClusterElementDefinitionService clusterElementDefinitionService,
  ActionContextAware actionContext, @Nullable ToolContext toolContext)`; it returns
  `ToolSuspendConstants.SUSPENDED_SENTINEL` after suspending, and throws `IllegalStateException` when every channel
  fails or the resume URL is missing.

- [ ] **Step 1: Confirm the gate tests pass before the refactor**

Run: `./gradlew :server:libs:modules:components:ai:agent:utils:test --tests '*ApprovalGateToolCallbackTest' > /tmp/t6.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t6.log`

Expected: exit=0. These tests are the refactor's safety net; if they already fail, stop and report.

- [ ] **Step 2: Create the helper**

Create `ToolApprovalRequests.java` (Apache header, package `com.bytechef.component.ai.llm.tool`). Move, verbatim,
the bodies of `ApprovalGateToolCallback`'s `deliverApprovalRequest` and `sendEditorApprovalRequestEvent`, plus the
resume-URL/form-URL/suspend sequence from `call`. The class is final with a private constructor and one static
entry point:

```java
/**
 * Raises one human-approval request for a tool call and suspends the agent turn, so the request is delivered and
 * resumed identically whether it came from an approval gate on the canvas or from an administrative component rule.
 *
 * <p>
 * The suspend carries the gate's continue parameters ({@code GATED_TOOL_NAME}, {@code GATED_TOOL_INPUT},
 * {@code formUrl}) whatever raised it, because {@code AbstractAiAgentChatAction.buildPatchedRequestSpec} routes on
 * {@code GATED_TOOL_NAME} — one resume branch serves both. A caller adds its own keys through
 * {@code additionalContinueParameters}.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class ToolApprovalRequests {

    private static final String CHAT_APPROVAL_CHANNEL_COMPONENT = "chat";
    private static final String CHAT_APPROVAL_CHANNEL_NAME = "chat";
    private static final String FORM_URL = "formUrl";

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalRequests.class);

    private ToolApprovalRequests() {
    }

    @SuppressFBWarnings("EI")
    public record Request(
        String toolName, String toolInput, String title, String description, Instant expiresAt,
        List<ClusterElement> approvalChannelClusterElements,
        Map<String, ComponentConnection> componentConnections, Map<String, Object> additionalContinueParameters,
        ClusterElementDefinitionService clusterElementDefinitionService, ActionContextAware actionContext,
        @Nullable ToolContext toolContext) {
    }

    /**
     * Delivers the request and suspends.
     *
     * @return the sentinel a suspending tool returns as its result
     */
    public static String raise(Request request) {
        ActionContextAware actionContext = request.actionContext();

        String resumeUrl = actionContext.getResumeUrl();

        if (resumeUrl == null) {
            throw new IllegalStateException(
                "Cannot raise an approval request for tool '" + request.toolName() + "'. Ensure the server's public " +
                    "URL is configured and the workflow is running in a proper execution context.");
        }

        String formUrl = resumeUrl.replace("/job/resume/", "/resume/");

        if (actionContext.isEditorEnvironment()) {
            sendEditorApprovalRequestEvent(request, formUrl);
        } else {
            deliverApprovalRequest(request, formUrl);
        }

        Map<String, Object> continueParameters = new HashMap<>(request.additionalContinueParameters());

        continueParameters.put(ToolSuspendConstants.GATED_TOOL_NAME, request.toolName());
        continueParameters.put(ToolSuspendConstants.GATED_TOOL_INPUT, request.toolInput());
        continueParameters.put(FORM_URL, formUrl);
        continueParameters.put(FORM_TITLE, request.title());
        continueParameters.put(FORM_DESCRIPTION, request.description());

        actionContext.suspend(new ActionContext.Suspend(continueParameters, request.expiresAt()));

        return ToolSuspendConstants.SUSPENDED_SENTINEL;
    }
```

`deliverApprovalRequest(Request request, String formUrl)` and `sendEditorApprovalRequestEvent(Request request,
String formUrl)` are the gate's methods with `getName()` replaced by `request.toolName()`, the hard-coded title and
description replaced by `request.title()` and `request.description()`, and `toolContext` read from the request. The
`FORM_TITLE` / `FORM_DESCRIPTION` / `EXPIRES_AT` static imports come from
`com.bytechef.component.definition.approval.ApprovalChannelFunction`.

Note the two extra continue-parameter keys (`FORM_TITLE`, `FORM_DESCRIPTION`) — Task 8 reads them for the hosted
form. They are additive; the gate's own tests assert on the two gated keys only.

- [ ] **Step 3: Make the gate delegate to the helper**

In `ApprovalGateToolCallback.java`, replace everything in `call` after the deferral guard with:

```java
        Instant expiresAt = Instant.now()
            .plus(approvalExpiry != null ? approvalExpiry : DEFAULT_APPROVAL_EXPIRY);

        String result = ToolApprovalRequests.raise(
            new ToolApprovalRequests.Request(
                getName(), toolInput, "Approve tool call: " + getName(),
                "The AI agent wants to call the tool '" + getName() + "' with these arguments:\n\n" + toolInput,
                expiresAt, approvalChannelClusterElements, componentConnections, Map.of(),
                clusterElementDefinitionService, actionContext, toolContext));

        recordGateRaised();

        return result;
```

Delete `deliverApprovalRequest`, `sendEditorApprovalRequestEvent`, the `CHAT_APPROVAL_CHANNEL_*` constants and the
`FORM_URL` constant from the gate. Keep `getName`, `recordGateRaised`, the deferral guard and the constructors.

Add to `server/libs/modules/components/ai/agent/utils/build.gradle.kts`, alphabetically:

```kotlin
    implementation(project(":server:libs:modules:components:ai:llm"))
```

Check first — the gate already imports `com.bytechef.component.ai.llm.tool.DelegatingToolCallback`, so the
dependency is probably already there.

- [ ] **Step 4: Run the gate tests unchanged**

Run: `./gradlew :server:libs:modules:components:ai:agent:utils:test :server:libs:modules:components:ai:llm:test --continue > /tmp/t6.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t6.log`

Expected: exit=0. All eight `ApprovalGateToolCallbackTest` tests pass without modification — that is the proof the
extraction preserved behaviour.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/modules/components/ai
git commit -m "$(cat <<'EOF'
Extract the tool approval request helper from the approval gate

Raising an approval, delivering it over the configured channels and suspending
the turn are about to have a second caller. The gate's own tests pass unchanged.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Raise and resolve approvals from rules

**Files:**
- Modify: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/RuleEnforcingToolCallback.java`
- Modify: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/ClusterElementToolCallbacks.java`
- Modify: `server/libs/platform/platform-ai/platform-ai-api/src/main/java/com/bytechef/platform/ai/constant/ToolSuspendConstants.java`
- Modify: `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatAction.java`
- Modify: `.../platform-component-rule-service/.../ComponentRuleEnforcerImpl.java`
- Modify: `.../platform-component-rule-service/.../audit/ComponentRuleAuditEvent.java`
- Modify: `.../platform-component-rule-service/.../audit/ComponentRuleAuditPublisher.java`
- Modify: `server/libs/platform/platform-tool-execution/platform-tool-execution-api/src/main/java/com/bytechef/platform/tool/execution/ToolExecutionOutcome.java`
- Test: `server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/tool/RuleEnforcingToolCallbackTest.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`
- Test: `server/libs/modules/components/ai/agent/src/test/java/com/bytechef/component/ai/agent/action/AbstractAiAgentChatActionResumeGateTest.java`

**Interfaces:**
- Consumes: `ToolApprovalRequests.raise(Request)` from Task 6; `ComponentRuleSettings` from Task 5;
  `RuleEnforcingToolCallback`'s five-argument constructor from Task 3, which this task widens to eight —
  `(ToolCallback delegate, ClusterElement clusterElement, List<ComponentRuleEnforcer> componentRuleEnforcers,
  @Nullable Long connectionId, ActionContext actionContext, List<ClusterElement> approvalChannelClusterElements,
  Map<String, ComponentConnection> componentConnections,
  ClusterElementDefinitionService clusterElementDefinitionService)`. Update the `newToolCallback()` helper in
  `RuleEnforcingToolCallbackTest` to match, passing `List.of()`, `Map.of()` and a mocked service.
- Produces: `ToolSuspendConstants.RULE_IDS = "__bytechef_rule_ids__"`;
  `ClusterElementToolCallbacks.recordRuleApprovalResolution(List<Long> ruleIds, String toolCallName, boolean
  approved, @Nullable String approvedBy)`; `ClusterElementToolCallbacks.build(...)` gains trailing parameters
  `List<ClusterElement> approvalChannelClusterElements`; audit events `RULE_APPROVAL_REQUESTED`, `RULE_APPROVED`,
  `RULE_REJECTED`; `ToolExecutionOutcome.RULE_BLOCKED`.

- [ ] **Step 1: Write the failing tests**

Add to `RuleEnforcingToolCallbackTest.java`:

```java
    @Test
    void testRequireApprovalSuspendsWithTheGateContinueParametersPlusTheRuleIds() {
        when(actionContext.getSuspend()).thenReturn(null);
        when(actionContext.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
        when(actionContext.isEditorEnvironment()).thenReturn(true);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(
            new Decision.RequireApproval(
                List.of(7L, 9L), "Approve tool call", "because the rule says so",
                Instant.now()
                    .plusSeconds(3600)));

        assertThat(newToolCallback().call("{\"channel\": \"#general\"}", null))
            .isEqualTo(ToolSuspendConstants.SUSPENDED_SENTINEL);

        ArgumentCaptor<ActionContext.Suspend> suspendCaptor = ArgumentCaptor.forClass(ActionContext.Suspend.class);

        verify(actionContext).suspend(suspendCaptor.capture());

        assertThat(suspendCaptor.getValue()
            .continueParameters())
                .containsEntry(ToolSuspendConstants.GATED_TOOL_NAME, "SLACK_SEND_MESSAGE")
                .containsEntry(ToolSuspendConstants.RULE_IDS, List.of(7L, 9L));

        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void testRequireApprovalDefersWhenAnotherToolAlreadySuspendedThisRound() {
        when(actionContext.getSuspend()).thenReturn(new ActionContext.Suspend(Map.of(), Instant.now()));
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(
            new Decision.RequireApproval(List.of(7L), "t", "d", Instant.now()));

        assertThat(newToolCallback().call("{}", null)).contains("deferred");

        verify(actionContext, never()).suspend(any());
        verify(delegate, never()).call(anyString(), any());
    }

    @Test
    void testAnApprovedReExecutionPassesTheReviewerToTheEnforcer() {
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());
        when(delegate.call(anyString(), any())).thenReturn("sent");

        ToolContext toolContext = new ToolContext(Map.of(AiAgentToolContextKey.APPROVED_BY, "@jane"));

        newToolCallback().call("{}", toolContext);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        assertThat(toolCallCaptor.getValue()
            .approvedBy()).isEqualTo("@jane");
    }
```

Add to `ComponentRuleEnforcerTest.java`:

```java
    @Test
    void testMatchingApprovalRuleReturnsEveryMatchingRuleId() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.REQUIRE_APPROVAL, "true"),
                newComponentRule(2L, null, RulePhase.BEFORE, RuleAction.REQUIRE_APPROVAL, "true")));

        Decision decision = componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05);

        // One human decision must satisfy every approval rule that matched, or the agent would raise a second
        // request the moment the first is approved.
        assertThat(decision).isInstanceOfSatisfying(
            Decision.RequireApproval.class,
            requireApproval -> assertThat(requireApproval.ruleIds()).containsExactlyInAnyOrder(1L, 2L));

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_APPROVAL_REQUESTED), any());
    }

    @Test
    void testBlockWinsOverApproval() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.REQUIRE_APPROVAL, "true"),
                newComponentRule(2L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Block.class);
    }

    @Test
    void testAnApprovedCallDoesNotRaiseTheApprovalAgain() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.REQUIRE_APPROVAL, "true")));

        ToolCall approvedToolCall = new ToolCall(
            "slack", "sendMessage", "SLACK_SEND_MESSAGE", Map.of("channel", "C05QG7RF30A"), 3L, 42L, 7L, "@jane");

        assertThat(componentRuleEnforcer.checkBeforeCall(approvedToolCall)).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void testAnApprovedCallStillBlocks() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        ToolCall approvedToolCall = new ToolCall(
            "slack", "sendMessage", "SLACK_SEND_MESSAGE", Map.of("channel", "C05QG7RF30A"), 3L, 42L, 7L, "@jane");

        // A human approving one rule's request does not license a call another rule forbids outright.
        assertThat(componentRuleEnforcer.checkBeforeCall(approvedToolCall)).isInstanceOf(Decision.Block.class);
    }

    @Test
    void testRecordApprovalResolutionAudits() {
        componentRuleEnforcer.recordApprovalResolution(List.of(1L), SEND_MESSAGE_TO_C05, true, "@jane");

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_APPROVED), any());
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:libs:modules:components:ai:llm:compileTestJava :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:compileTestJava --continue > /tmp/t7.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/t7.log | head`

Expected: FAIL, `cannot find symbol: RULE_IDS`.

- [ ] **Step 3: Add the constant and the audit events**

In `ToolSuspendConstants.java`, append:

```java
    /**
     * {@code continueParameters} key holding the ids of the component rules that required this approval, as a
     * {@code List<Long>}. Present only when an administrative rule raised the request; the gate does not set it.
     */
    public static final String RULE_IDS = "__bytechef_rule_ids__";
```

In `ComponentRuleAuditEvent.java`, append three constants (keep `RULE_BLOCKED` and `RULE_TAGGED` first):

```java
    /**
     * A {@code BEFORE}-phase {@code REQUIRE_APPROVAL} rule matched and the agent turn was suspended for a human.
     */
    RULE_APPROVAL_REQUESTED(false),

    /**
     * A human approved a request an approval rule raised. Carries {@code approvedBy} when the resolving channel
     * established an identity.
     */
    RULE_APPROVED(false),

    /**
     * A human rejected a request an approval rule raised.
     */
    RULE_REJECTED(false);
```

In `ComponentRuleAuditPublisher.java`, extend the payload:

```java
    public record ComponentRuleAuditPayload(
        long ruleId, String componentName, String toolName, @Nullable String toolCallName, String phase,
        @Nullable Long jobId, @Nullable Long taskExecutionId, @Nullable String approvedBy) {

        Map<String, Object> toMap() {
            Map<String, Object> data = new HashMap<>();

            data.put("ruleId", String.valueOf(ruleId));
            data.put("componentName", componentName);
            data.put("toolName", toolName);
            data.put("phase", phase);

            if (toolCallName != null) {
                data.put("toolCallName", toolCallName);
            }

            if (jobId != null) {
                data.put("jobId", String.valueOf(jobId));
            }

            if (taskExecutionId != null) {
                data.put("taskExecutionId", String.valueOf(taskExecutionId));
            }

            if (approvedBy != null) {
                data.put("approvedBy", approvedBy);
            }

            return data;
        }
    }
```

Update the enum's class Javadoc payload paragraph to name the new keys. Update
`ComponentRuleEnforcerImpl.publish` to pass `toolCall.toolCallName()` and `null` for `approvedBy`, and
`ComponentRuleEnforcerTest.testAuditPayloadCarriesTheFiringRule` to assert `toolName()` and `toolCallName()`.

In `ToolExecutionOutcome.java`, append `RULE_BLOCKED` after `APPROVAL_DENIED`.

- [ ] **Step 4: Implement approval in the enforcer**

In `ComponentRuleEnforcerImpl.checkBeforeCall`, after the block loop and before the tag loop:

```java
        List<Long> approvalRuleIds = new ArrayList<>();

        for (ComponentRule componentRule : matchingComponentRules) {
            if (componentRule.getRuleAction() == RuleAction.REQUIRE_APPROVAL) {
                approvalRuleIds.add(componentRule.getId());
            }
        }

        // A human-approved re-execution carries the reviewer, so the approval those rules asked for already
        // happened; raising it again would loop.
        if (!approvalRuleIds.isEmpty() && toolCall.approvedBy() == null) {
            for (ComponentRule componentRule : matchingComponentRules) {
                if (componentRule.getRuleAction() == RuleAction.REQUIRE_APPROVAL) {
                    publish(
                        ComponentRuleAuditEvent.RULE_APPROVAL_REQUESTED, componentRule, toolCall, RulePhase.BEFORE);
                }
            }

            return new Decision.RequireApproval(
                List.copyOf(approvalRuleIds), "Approve tool call: " + toolCall.toolCallName(),
                buildApprovalDescription(matchingComponentRules, toolCall), Instant.now()
                    .plus(Duration.ofHours(componentRuleSettingsService.getSettings()
                        .approvalExpiresInHours())));
        }
```

`buildApprovalDescription` joins the matching approval rules' descriptions (falling back to their conditions) into
one string prefixed with the tool name. Inject `ComponentRuleSettingsService` through both constructors.

Implement `recordApprovalResolution`:

```java
    @Override
    public void recordApprovalResolution(
        List<Long> ruleIds, ToolCall toolCall, boolean approved, @Nullable String approvedBy) {

        // The turn is already resuming; an audit failure must not fail it.
        try {
            ComponentRuleAuditEvent componentRuleAuditEvent = approved
                ? ComponentRuleAuditEvent.RULE_APPROVED : ComponentRuleAuditEvent.RULE_REJECTED;

            for (Long ruleId : ruleIds) {
                componentRuleAuditPublisher.publish(
                    componentRuleAuditEvent,
                    new ComponentRuleAuditPayload(
                        ruleId, toolCall.componentName(), toolCall.toolName(), toolCall.toolCallName(),
                        RulePhase.BEFORE.name(), toolCall.jobId(), toolCall.taskExecutionId(), approvedBy));
            }
        } catch (RuntimeException exception) {
            log.warn("Could not record the approval resolution for rules {}", ruleIds, exception);
        }
    }
```

- [ ] **Step 5: Raise the approval from the wrapper**

In `RuleEnforcingToolCallback`, add `approvalChannelClusterElements`,
`componentConnections` and `clusterElementDefinitionService` constructor fields (passed by
`ClusterElementToolCallbacks`), and handle the third decision in `call`, between the block branch and the delegate
call:

```java
        if (decision instanceof Decision.RequireApproval requireApproval) {
            // Only ONE suspend may exist per tool round; another tool already suspended, so defer this one the way
            // the approval gate does and let the model retry after the pending approval resolves.
            if (actionContext.getSuspend() != null) {
                return "{\"deferred\": true, \"reason\": \"Another tool call is awaiting approval. Retry this tool " +
                    "call after the pending approval is resolved.\"}";
            }

            return ToolApprovalRequests.raise(
                new ToolApprovalRequests.Request(
                    toolCall.toolCallName(), toolInput, requireApproval.title(), requireApproval.description(),
                    requireApproval.expiresAt(), approvalChannelClusterElements, componentConnections,
                    Map.of(
                        ToolSuspendConstants.RULE_IDS, requireApproval.ruleIds(),
                        ToolSuspendConstants.RULE_COMPONENT_NAME, toolCall.componentName(),
                        ToolSuspendConstants.RULE_TOOL_NAME, toolCall.toolName()),
                    clusterElementDefinitionService, actionContext, toolContext));
        }
```

Add a public static resolution recorder used by the agent action:

```java
    /**
     * Records a human's decision on an approval a rule raised. Called from the agent's resume branch, which must not
     * depend on the rule module.
     */
    public static void recordApprovalResolution(
        List<ComponentRuleEnforcer> componentRuleEnforcers, ToolCall toolCall, List<Long> ruleIds, boolean approved,
        @Nullable String approvedBy) {

        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            try {
                componentRuleEnforcer.recordApprovalResolution(ruleIds, toolCall, approved, approvedBy);
            } catch (RuntimeException exception) {
                log.warn("Could not record the approval resolution for rules {}", ruleIds, exception);
            }
        }
    }
```

In `ClusterElementToolCallbacks`, thread the approval channels through `build` (new trailing parameter
`List<ClusterElement> approvalChannelClusterElements`) into the wrapper, and expose:

```java
    /**
     * Records a rule-raised approval's outcome. The agent action calls this rather than the SPI, so it never imports
     * the rule module.
     */
    public void recordRuleApprovalResolution(
        List<Long> ruleIds, String componentName, String toolName, String toolCallName, boolean approved,
        @Nullable String approvedBy, @Nullable Long jobId) {

        RuleEnforcingToolCallback.recordApprovalResolution(
            componentRuleEnforcers,
            new ToolCall(componentName, toolName, toolCallName, Map.of(), null, jobId, null, approvedBy), ruleIds,
            approved, approvedBy);
    }
```

Update every `build(...)` call site: `AbstractAiAgentChatAction.buildElementToolCallbacks` passes the agent node's
`APPROVAL_CHANNELS` elements (read from the same `ClusterElementMap` the gate uses), and
`AiAgentUtilsApprovalGateTool.buildGatedToolCallbacks` passes its own `approvalChannelClusterElements`.

- [ ] **Step 6: Wire the resume branch**

In `AbstractAiAgentChatAction.resolveGatedToolResumeData`, after `approvedBy` is read and before the `if
(!approved)` branch, capture the rule ids:

```java
        List<Long> ruleIds = continueParameters.getList(ToolSuspendConstants.RULE_IDS, Long.class, List.of());
```

In the rejection branch, before returning the denial:

```java
        if (!ruleIds.isEmpty()) {
            clusterElementToolCallbacks.recordRuleApprovalResolution(
                ruleIds, ruleComponentName, ruleToolName,
                continueParameters.getRequiredString(ToolSuspendConstants.GATED_TOOL_NAME), false, approvedBy,
                ((ActionContextAware) context).getJobId());
        }
```

and the mirror call with `true` in the approval branch, right after the tool callback is resolved.

The continue parameters carry only the Spring AI tool name, not the component and element names, so add both when
the rule wrapper raises the request — in Task 7 Step 5, put
`ToolSuspendConstants.RULE_COMPONENT_NAME` and `ToolSuspendConstants.RULE_TOOL_NAME` into
`additionalContinueParameters` beside `RULE_IDS`, and declare them next to `RULE_IDS`:

```java
    /** {@code continueParameters} key holding the component of the tool a rule required approval for. */
    public static final String RULE_COMPONENT_NAME = "__bytechef_rule_component_name__";

    /** {@code continueParameters} key holding the cluster element name of that tool. */
    public static final String RULE_TOOL_NAME = "__bytechef_rule_tool_name__";
```

The resume branch then reads all three:

```java
        String ruleComponentName = continueParameters.getString(ToolSuspendConstants.RULE_COMPONENT_NAME, "");
        String ruleToolName = continueParameters.getString(ToolSuspendConstants.RULE_TOOL_NAME, "");
```

Add `AiAgentToolContextKey.APPROVED_BY` to the `ToolContext` built for the approved re-execution:

```java
        Map<String, Object> toolContextMap = new HashMap<>();

        toolContextMap.put(AiAgentToolContextKey.ACTION_CONTEXT, context);

        if (hasApprovedBy) {
            toolContextMap.put(AiAgentToolContextKey.APPROVED_BY, approvedBy);
        }

        ToolContext toolContext = new ToolContext(toolContextMap);
```

- [ ] **Step 7: Add the composition test**

In `AbstractAiAgentChatActionResumeGateTest.java`, add:

```java
    @Test
    void testApprovedReExecutionCarriesTheReviewerIntoTheToolContext() throws Exception {
        when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

        action.resolveGatedToolResumeData(
            NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
            dataWithApprovedBy(true, "@jane"), Map.of(), EXTENSIONS, context);

        ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);

        verify(toolCallback).call(eq(GATED_TOOL_INPUT), toolContextCaptor.capture());

        // The rule layer survives the gate's unwrap, so it re-checks this call; the reviewer is how it knows the
        // approval it required has already happened and must not be raised again.
        Map<String, Object> toolContextMap = toolContextCaptor.getValue()
            .getContext();

        assertThat(toolContextMap).containsEntry(AiAgentToolContextKey.APPROVED_BY, "@jane");
    }
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew :server:libs:modules:components:ai:llm:test :server:libs:modules:components:ai:agent:test :server:libs:modules:components:ai:agent:utils:test :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test --continue > /tmp/t7.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t7.log`

Expected: exit=0.

- [ ] **Step 9: Compile everything and commit**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t7c.log 2>&1; echo "exit=$?"
grep -E "^> Task .* FAILED|error:" /tmp/t7c.log | head -30
./gradlew spotlessApply
git add server
git commit -m "$(cat <<'EOF'
Suspend the agent turn for a rule that requires approval

A REQUIRE_APPROVAL rule raises the request through the same helper the approval
gate uses, so it resumes through the gate's existing branch. The reviewer is
carried into the re-execution's tool context, which is how the rule layer knows
not to raise the request a second time.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Show the tool and its arguments on the hosted approval form

`ApprovalFormFacadeImpl` renders the suspended task execution's parameters, which for an agent suspend are the
agent node's own configuration. The suspend's continue parameters carry the right title and description (Task 6
writes them), so they win when present.

**Files:**
- Modify: `server/libs/platform/platform-workflow/platform-workflow-execution/platform-workflow-execution-service/src/main/java/com/bytechef/platform/workflow/execution/facade/ApprovalFormFacadeImpl.java`
- Test: `server/libs/platform/platform-workflow/platform-workflow-execution/platform-workflow-execution-service/src/test/java/com/bytechef/platform/workflow/execution/facade/ApprovalFormFacadeTest.java`

**Interfaces:**
- Consumes: `TaskStateService.fetchValue(JobResumeId)` returning the stored `ActionContext.Suspend`.
- Produces: no new types; `getApprovalForm` overlays `formTitle` / `formDescription` from the stored suspend.

- [ ] **Step 1: Write the failing test**

Create (or extend) `ApprovalFormFacadeTest.java`. Mirror the existing mocks in the module's other facade tests:

```java
    @Test
    void testTheSuspendContinueParametersOverrideTheTaskExecutionParameters() {
        // An agent suspend's task execution is the agent node, whose parameters describe the prompt, not the tool
        // awaiting approval. The suspend carries the right title and description, so it wins.
        stubStoppedJobWithTaskExecutionParameters(
            Map.of("formTitle", "AI Agent", "formDescription", "Answer the user"));
        stubStoredSuspend(
            Map.of("formTitle", "Approve tool call: SLACK_SEND_MESSAGE", "formDescription", "channel: #general"));

        Map<String, ?> approvalForm = approvalFormFacade.getApprovalForm("token");

        assertThat(approvalForm)
            .containsEntry("formTitle", "Approve tool call: SLACK_SEND_MESSAGE")
            .containsEntry("formDescription", "channel: #general");
    }

    @Test
    void testTaskExecutionParametersAreKeptWhenTheSuspendCarriesNoOverride() {
        stubStoppedJobWithTaskExecutionParameters(
            Map.of("formTitle", "Approve the refund", "formDescription", "over 1000"));
        stubStoredSuspend(Map.of("formUrl", "https://example.com/resume/abc"));

        Map<String, ?> approvalForm = approvalFormFacade.getApprovalForm("token");

        assertThat(approvalForm).containsEntry("formTitle", "Approve the refund");
    }
```

Write the two `stub*` helpers against whatever collaborators the facade takes; read the impl (lines 40-89) and the
module's existing tests first.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :server:libs:platform:platform-workflow:platform-workflow-execution:platform-workflow-execution-service:test --tests '*ApprovalFormFacadeTest' > /tmp/t8.log 2>&1; echo "exit=$?"; grep -E "FAILED|expected" /tmp/t8.log | head`

Expected: FAIL — the agent node's title comes back.

- [ ] **Step 3: Add the overlay**

In `ApprovalFormFacadeImpl.getApprovalForm`, after the `environmentId` copy and before `return result;`:

```java
            // A suspend raised for one tool call (an approval gate or a component rule) describes that call in its
            // continue parameters. The task execution is the agent node, whose own parameters describe the prompt,
            // so the suspend's title and description win when it carries them.
            taskStateService.<ActionContext.Suspend>fetchValue(jobResumeId)
                .map(ActionContext.Suspend::continueParameters)
                .ifPresent(continueParameters -> {
                    copyIfPresent(continueParameters, result, FORM_TITLE);
                    copyIfPresent(continueParameters, result, FORM_DESCRIPTION);
                });
```

with:

```java
    private static void copyIfPresent(Map<String, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);

        if (value != null) {
            target.put(key, value);
        }
    }
```

Constants `FORM_TITLE = "formTitle"` and `FORM_DESCRIPTION = "formDescription"` beside
`ENVIRONMENT_ID_METADATA_KEY`. Inject `TaskStateService` through the constructor (add it to the
`@SuppressFBWarnings("EI")` constructor and to every construction site — find them with
`grep -rn "new ApprovalFormFacadeImpl(" server --include='*.java'`).

Note: `TaskStateService.delete` runs in `SuspendTaskDispatcherPreSendProcessor` on resume, so the state exists for
exactly as long as the form is reachable. If `fetchValue` returns empty the form falls back to the task
execution's parameters, which is the pre-existing behaviour.

- [ ] **Step 4: Run the test**

Run: `./gradlew :server:libs:platform:platform-workflow:platform-workflow-execution:platform-workflow-execution-service:test > /tmp/t8.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t8.log`

Expected: exit=0.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add server/libs/platform/platform-workflow
git commit -m "$(cat <<'EOF'
Show the awaiting tool call on the hosted approval form

An agent suspend's task execution is the agent node, so the form showed the
prompt configuration instead of the tool and arguments awaiting approval.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Observe mode and strict rules

**Files:**
- Modify: `.../platform-component-rule-service/.../ComponentRuleEnforcerImpl.java`
- Modify: `.../platform-component-rule-service/.../audit/ComponentRuleAuditEvent.java`
- Modify: `.../platform-component-rule-service/.../audit/ComponentRuleAuditPublisher.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`

**Interfaces:**
- Consumes: `ComponentRuleSettingsService.getSettings()` from Task 5; `ComponentRule.isStrict()`.
- Produces: `ComponentRuleAuditEvent.RULE_OBSERVED`; audit payload gains `wouldHave` and `strictFallback`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testObserveModeAllowsAndAuditsAWouldBeBlock() {
        when(componentRuleSettingsService.getSettings()).thenReturn(new ComponentRuleSettings(true, 1440));
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Allow.class);

        ArgumentCaptor<ComponentRuleAuditPublisher.ComponentRuleAuditPayload> payloadCaptor =
            ArgumentCaptor.forClass(ComponentRuleAuditPublisher.ComponentRuleAuditPayload.class);

        verify(componentRuleAuditPublisher).publish(
            eq(ComponentRuleAuditEvent.RULE_OBSERVED), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue()
            .wouldHave()).isEqualTo("BLOCK");
    }

    @Test
    void testObserveModeNeverRaisesAnApproval() {
        when(componentRuleSettingsService.getSettings()).thenReturn(new ComponentRuleSettings(true, 1440));
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.REQUIRE_APPROVAL, "true")));

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Allow.class);

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_OBSERVED), any());
        verify(componentRuleAuditPublisher, never())
            .publish(eq(ComponentRuleAuditEvent.RULE_APPROVAL_REQUESTED), any());
    }

    @Test
    void testAStrictRuleFiresWhenItsConditionCannotBeResolved() {
        ComponentRule componentRule = newComponentRule(
            7L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "missingRoot['whatever'] == 1");

        componentRule.setStrict(true);

        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of(componentRule));

        // Fail closed: the admin asked for this rule to fire rather than fail open when it cannot be evaluated.
        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Block.class);
    }

    @Test
    void testAStrictFallbackIsMarkedInTheAuditPayload() {
        ComponentRule componentRule = newComponentRule(
            8L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "missingRoot['whatever'] == 1");

        componentRule.setStrict(true);

        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of(componentRule));

        componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05);

        ArgumentCaptor<ComponentRuleAuditPublisher.ComponentRuleAuditPayload> payloadCaptor =
            ArgumentCaptor.forClass(ComponentRuleAuditPublisher.ComponentRuleAuditPayload.class);

        verify(componentRuleAuditPublisher).publish(eq(ComponentRuleAuditEvent.RULE_TAGGED), payloadCaptor.capture());

        // A reviewer must be able to tell a genuine match from a rule that only fired because it could not be read.
        assertThat(payloadCaptor.getValue()
            .strictFallback()).isTrue();
    }

    @Test
    void testANonStrictRuleStillFailsOpen() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    9L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "missingRoot['whatever'] == 1")));

        assertThat(componentRuleEnforcer.checkBeforeCall(SEND_MESSAGE_TO_C05)).isInstanceOf(Decision.Allow.class);
    }
```

Add `private final ComponentRuleSettingsService componentRuleSettingsService =
mock(ComponentRuleSettingsService.class);` to the fixture, pass it to both enforcer constructions, and default it
in a `@BeforeEach`: `when(componentRuleSettingsService.getSettings()).thenReturn(ComponentRuleSettings.DEFAULT);`

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test --tests '*ComponentRuleEnforcerTest' > /tmp/t9.log 2>&1; echo "exit=$?"; grep -E "FAILED|error:" /tmp/t9.log | head`

Expected: FAIL, `cannot find symbol: RULE_OBSERVED`.

- [ ] **Step 3: Add the observed event and the payload fields**

Append `RULE_OBSERVED(false)` to `ComponentRuleAuditEvent` with a Javadoc saying it records a rule that matched
while observe mode was on, and that `wouldHave` carries the enforcement it did not apply.

Extend `ComponentRuleAuditPayload` with two trailing components, `@Nullable String wouldHave` and `boolean
strictFallback`, mapping `wouldHave` only when non-null and `strictFallback` only when true. Update every existing
construction site.

- [ ] **Step 4: Implement strict matching**

Replace `ComponentRuleEnforcerImpl.matches` with a method returning a small result, so a fallback is
distinguishable from a genuine match:

```java
    private record MatchResult(boolean matched, boolean strictFallback) {

        private static final MatchResult NO_MATCH = new MatchResult(false, false);
        private static final MatchResult MATCHED = new MatchResult(true, false);
        private static final MatchResult STRICT_FALLBACK = new MatchResult(true, true);
    }

    /**
     * Evaluates one condition. Three outcomes matter, not two: TRUE, FALSE, and unevaluable — the lenient evaluator
     * returns the source text for an unresolved reference rather than throwing. A strict rule treats unevaluable as
     * a match (fail closed); a non-strict rule does not (fail open), so a mis-authored rule cannot take a tenant's
     * agents offline.
     */
    private MatchResult matches(ComponentRule componentRule, Map<String, Object> evaluationContext) {
        try {
            Map<String, ?> evaluated = evaluator.evaluate(
                Map.of(CONDITION_KEY, FORMULA_PREFIX + componentRule.getCondition()), evaluationContext, true);

            Object condition = evaluated.get(CONDITION_KEY);

            if (Boolean.TRUE.equals(condition)) {
                return MatchResult.MATCHED;
            }

            if (Boolean.FALSE.equals(condition)) {
                return MatchResult.NO_MATCH;
            }

            return componentRule.isStrict() ? MatchResult.STRICT_FALLBACK : MatchResult.NO_MATCH;
        } catch (RuntimeException exception) {
            log.warn(
                "Could not evaluate the condition of component rule id={}", componentRule.getId(), exception);

            return componentRule.isStrict() ? MatchResult.STRICT_FALLBACK : MatchResult.NO_MATCH;
        }
    }
```

`getMatchingComponentRules` returns `List<MatchedComponentRule>` where
`record MatchedComponentRule(ComponentRule componentRule, boolean strictFallback)`, and every loop over matches
carries the flag into `publish`.

- [ ] **Step 5: Implement observe mode**

At the top of `checkBeforeCall`, after the matching rules are computed and before the block loop:

```java
        if (componentRuleSettingsService.getSettings()
            .observeMode()) {

            // Observe mode is how a tenant turns rules on without risking a mis-authored one: every rule is
            // evaluated, none is enforced, and what would have happened is on the Audit Events page.
            for (MatchedComponentRule matchedComponentRule : matchingComponentRules) {
                publish(
                    ComponentRuleAuditEvent.RULE_OBSERVED, matchedComponentRule, toolCall, RulePhase.BEFORE,
                    matchedComponentRule.componentRule()
                        .getRuleAction()
                        .name());
            }

            return ALLOW;
        }
```

`recordAfterCall` is untouched: an AFTER tag never enforced anything, so it keeps emitting `RULE_TAGGED`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/t9.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t9.log`

Expected: exit=0.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-component-rule
git commit -m "$(cat <<'EOF'
Add observe mode and fail-closed rules

Observe mode evaluates every rule and enforces none, so a tenant can turn rules
on and read what would have happened. A strict rule fires when its condition
cannot be evaluated, inverting the fail-open default for the calls where that is
worth the risk.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Risk level in the SDK and on the platform definition

**Files:**
- Create: `sdks/backend/java/component-api/src/main/java/com/bytechef/component/definition/RiskLevel.java`
- Modify: `sdks/backend/java/component-api/src/main/java/com/bytechef/component/definition/ActionDefinition.java`
- Modify: `sdks/backend/java/component-api/src/main/java/com/bytechef/component/definition/ClusterElementDefinition.java`
- Modify: `sdks/backend/java/component-api/src/main/java/com/bytechef/component/definition/ComponentDsl.java`
- Create: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/rule/ToolRiskLevelResolver.java`
- Modify: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/domain/ClusterElementDefinition.java`
- Test: `server/libs/platform/platform-component/platform-component-api/src/test/java/com/bytechef/platform/component/rule/ToolRiskLevelResolverTest.java`
- Test: `sdks/backend/java/component-api/src/test/java/com/bytechef/component/definition/ComponentDslToolRiskLevelTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }`;
  `Optional<RiskLevel> ActionDefinition.getRiskLevel()` and `ClusterElementDefinition.getRiskLevel()`, both
  defaulting to empty; `ModifiableActionDefinition.riskLevel(RiskLevel)` and
  `ModifiableClusterElementDefinition.riskLevel(RiskLevel)`;
  `ToolRiskLevelResolver.resolve(@Nullable RiskLevel declared, String toolName)` returning a non-null `RiskLevel`;
  `com.bytechef.platform.component.domain.ClusterElementDefinition.getRiskLevel()` returning a non-null `RiskLevel`.

- [ ] **Step 1: Write the failing tests**

`ToolRiskLevelResolverTest.java`:

```java
class ToolRiskLevelResolverTest {

    @Test
    void testADeclaredLevelWinsOverInference() {
        assertThat(ToolRiskLevelResolver.resolve(RiskLevel.LOW, "deleteRecord")).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void testDestructiveAndMonetaryNamesAreCritical() {
        assertThat(ToolRiskLevelResolver.resolve(null, "deleteRecord")).isEqualTo(RiskLevel.CRITICAL);
        assertThat(ToolRiskLevelResolver.resolve(null, "purge_all")).isEqualTo(RiskLevel.CRITICAL);
        assertThat(ToolRiskLevelResolver.resolve(null, "createRefund")).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void testOutwardFacingNamesAreHigh() {
        assertThat(ToolRiskLevelResolver.resolve(null, "sendMessage")).isEqualTo(RiskLevel.HIGH);
        assertThat(ToolRiskLevelResolver.resolve(null, "publishPost")).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void testReadNamesAreLow() {
        assertThat(ToolRiskLevelResolver.resolve(null, "getRecord")).isEqualTo(RiskLevel.LOW);
        assertThat(ToolRiskLevelResolver.resolve(null, "searchCustomers")).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void testAnUnknownNameIsMedium() {
        assertThat(ToolRiskLevelResolver.resolve(null, "frobnicate")).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ToolRiskLevelResolver.resolve(null, "createRecord")).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void testTheHighestMatchingTokenWins() {
        // "deleteAndNotify" both deletes and notifies; the destructive half decides.
        assertThat(ToolRiskLevelResolver.resolve(null, "deleteAndNotify")).isEqualTo(RiskLevel.CRITICAL);
    }
}
```

`ComponentDslToolRiskLevelTest.java`:

```java
class ComponentDslToolRiskLevelTest {

    @Test
    void testToolCopiesTheActionsRiskLevel() {
        ModifiableActionDefinition actionDefinition = ComponentDsl.action("deleteRecord")
            .title("Delete Record")
            .riskLevel(RiskLevel.CRITICAL);

        ModifiableClusterElementDefinition<ToolFunction> clusterElementDefinition = ComponentDsl.tool(
            actionDefinition);

        assertThat(clusterElementDefinition.getRiskLevel()).contains(RiskLevel.CRITICAL);
    }

    @Test
    void testAnActionWithoutADeclaredRiskLevelProducesAToolWithoutOne() {
        ModifiableClusterElementDefinition<ToolFunction> clusterElementDefinition = ComponentDsl.tool(
            ComponentDsl.action("getRecord")
                .title("Get Record"));

        assertThat(clusterElementDefinition.getRiskLevel()).isEmpty();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :sdks:backend:java:component-api:compileTestJava :server:libs:platform:platform-component:platform-component-api:compileTestJava --continue > /tmp/t10.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/t10.log | head`

Expected: FAIL, `cannot find symbol: class RiskLevel`.

- [ ] **Step 3: Add the SDK enum and accessors**

`RiskLevel.java` (Apache header, package `com.bytechef.component.definition`):

```java
/**
 * How much damage one call of an action or tool can do. Declared by a component author; when undeclared the platform
 * infers a level from the operation's name. Governance surfaces use it to steer attention — it does not itself
 * enforce anything.
 *
 * @author Ivica Cardic
 */
public enum RiskLevel {

    /**
     * Reads. No state changes outside ByteChef.
     */
    LOW,

    /**
     * Ordinary writes: creating or updating a record the caller owns.
     */
    MEDIUM,

    /**
     * Reaches other people or systems: sending, publishing, granting, deploying.
     */
    HIGH,

    /**
     * Destroys data or moves money.
     */
    CRITICAL
}
```

In `ActionDefinition.java`, add beside the other defaults (alphabetically after `getProperties`):

```java
    /**
     * Returns the declared risk of one call of this action.
     *
     * @return an {@code Optional} containing the risk level if declared, or an empty {@code Optional} otherwise
     */
    default Optional<RiskLevel> getRiskLevel() {
        return Optional.empty();
    }
```

The identical method goes on `ClusterElementDefinition`.

In `ComponentDsl.ModifiableActionDefinition`, add the field `private RiskLevel riskLevel;`, the getter override
returning `Optional.ofNullable(riskLevel)`, and:

```java
        public ModifiableActionDefinition riskLevel(RiskLevel riskLevel) {
            this.riskLevel = riskLevel;

            return this;
        }
```

The same three additions go on `ModifiableClusterElementDefinition<T>`. In `ComponentDsl.tool(...)`, after the
`.properties(properties)` chain:

```java
        actionDefinition.getRiskLevel()
            .ifPresent(clusterElementDefinition::riskLevel);
```

- [ ] **Step 4: Add the resolver**

`ToolRiskLevelResolver.java` (Apache header, package `com.bytechef.platform.component.rule`):

```java
/**
 * Resolves the risk of one tool: the level its author declared, or one inferred from its name. Inference exists
 * because almost no component declares a level yet, and a governance surface that shows nothing for most tools is
 * not worth reading.
 *
 * <p>
 * The name is split on camel-case boundaries and underscores and matched token by token, most severe class first,
 * so {@code deleteAndNotify} is CRITICAL rather than HIGH. An unrecognised name is MEDIUM: unknown is not safe.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class ToolRiskLevelResolver {

    private static final Set<String> CRITICAL_TOKENS = Set.of(
        "delete", "remove", "destroy", "purge", "drop", "truncate", "wipe", "pay", "charge", "refund", "transfer",
        "payout", "withdraw", "revoke");

    private static final Set<String> HIGH_TOKENS = Set.of(
        "send", "post", "publish", "email", "message", "reply", "notify", "invite", "execute", "run", "deploy",
        "cancel", "void", "archive", "approve", "reject", "grant", "share", "submit", "upload");

    private static final Set<String> LOW_TOKENS = Set.of(
        "get", "list", "search", "find", "read", "fetch", "retrieve", "query", "count", "download", "export",
        "check", "lookup", "describe", "exists");

    private static final Pattern TOKEN_BOUNDARY = Pattern.compile("[_\\-\\s]+|(?<=[a-z0-9])(?=[A-Z])");

    private ToolRiskLevelResolver() {
    }

    public static RiskLevel resolve(@Nullable RiskLevel declaredRiskLevel, String toolName) {
        if (declaredRiskLevel != null) {
            return declaredRiskLevel;
        }

        Set<String> tokens = Arrays.stream(TOKEN_BOUNDARY.split(toolName))
            .map(token -> token.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        if (!Collections.disjoint(tokens, CRITICAL_TOKENS)) {
            return RiskLevel.CRITICAL;
        }

        if (!Collections.disjoint(tokens, HIGH_TOKENS)) {
            return RiskLevel.HIGH;
        }

        if (!Collections.disjoint(tokens, LOW_TOKENS)) {
            return RiskLevel.LOW;
        }

        return RiskLevel.MEDIUM;
    }
}
```

- [ ] **Step 5: Carry it onto the platform DTO**

In `com.bytechef.platform.component.domain.ClusterElementDefinition`, add `private RiskLevel riskLevel;`, set it in
the SDK-taking constructor:

```java
        this.riskLevel = ToolRiskLevelResolver.resolve(
            clusterElementDefinition.getRiskLevel()
                .orElse(null),
            this.name);
```

copy it in the prepended-properties constructor, add `getRiskLevel()` returning it, and add it to `equals`,
`hashCode` and `toString`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :sdks:backend:java:component-api:test :server:libs:platform:platform-component:platform-component-api:test --continue > /tmp/t10.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t10.log`

Expected: exit=0.

- [ ] **Step 7: Regenerate component definition snapshots if they drifted**

Run: `./gradlew :server:libs:modules:components:slack:test > /tmp/t10s.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t10s.log`

Expected: exit=0. Adding an `Optional` getter that defaults to empty does not change any serialized definition; if
a snapshot test fails, delete the `.json` from BOTH `src/test/resources/definition/` and
`build/resources/test/definition/` and run twice (the first run writes the file and then fails on the missing
classpath copy — that NPE is the expected midpoint).

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add sdks server/libs/platform/platform-component
git commit -m "$(cat <<'EOF'
Add a risk level to actions and tools

A component author declares how much damage one call can do; an undeclared level
is inferred from the operation name so governance surfaces have something to show
for the components that have not declared one.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Expose rules settings, the new rule fields and tool risk over GraphQL

**Files:**
- Modify: `.../platform-component-rule-graphql/src/main/resources/graphql/component-rule.graphqls`
- Modify: `.../platform-component-rule-graphql/src/main/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlController.java`
- Modify: `server/libs/platform/platform-configuration/platform-configuration-graphql/src/main/resources/graphql/cluster-element-definition.graphqls`
- Modify: `server/libs/platform/platform-configuration/platform-configuration-rest/platform-configuration-rest-impl/openapi.yaml:1965,1983`
- Test: `.../platform-component-rule-graphql/src/test/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlControllerTest.java`

**Interfaces:**
- Consumes: Tasks 5, 9, 10.
- Produces: GraphQL `ComponentRuleActionType.REQUIRE_APPROVAL`; `ComponentRule.strict` and
  `ComponentRule.toolRiskLevel`; `enum RiskLevel`; `Query.componentRuleSettings`;
  `Mutation.updateComponentRuleSettings(observeMode: Boolean!, approvalExpiresInHours: Int!)`;
  `ClusterElementDefinition.riskLevel`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void testSaveComponentRuleCarriesStrictAndRequireApproval() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        controller.saveComponentRule(
            null, "slack", "sendMessage", RulePhase.BEFORE, RuleAction.REQUIRE_APPROVAL,
            "inputParameters['x'] == 1", "approve when x is one", true, true);

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getRuleAction()).isEqualTo(RuleAction.REQUIRE_APPROVAL);
        assertThat(componentRule.isStrict()).isTrue();
    }

    @Test
    void testComponentRuleSettingsRoundTrip() {
        when(componentRuleSettingsService.getSettings()).thenReturn(new ComponentRuleSettings(true, 24));

        assertThat(controller.componentRuleSettings()).isEqualTo(new ComponentRuleSettings(true, 24));

        controller.updateComponentRuleSettings(false, 12);

        verify(componentRuleSettingsService).saveSettings(new ComponentRuleSettings(false, 12));
    }
```

Add `componentRuleSettingsService` to the fixture and to the controller construction.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:compileTestJava > /tmp/t11.log 2>&1; echo "exit=$?"; grep -E "error:" /tmp/t11.log | head`

Expected: FAIL — `saveComponentRule` takes eight arguments, not nine.

- [ ] **Step 3: Extend the schema**

In `component-rule.graphqls`:

```graphql
enum ComponentRuleActionType {
    BLOCK
    TAG
    REQUIRE_APPROVAL
}

enum RiskLevel {
    LOW
    MEDIUM
    HIGH
    CRITICAL
}

type ComponentRule {
    id: ID!
    componentName: String!
    componentTitle: String
    componentIcon: String
    toolName: String
    toolRiskLevel: RiskLevel
    phase: ComponentRulePhase!
    ruleAction: ComponentRuleActionType!
    condition: String!
    description: String
    enabled: Boolean!
    strict: Boolean!
}

type ComponentRuleSettings {
    observeMode: Boolean!
    approvalExpiresInHours: Int!
}
```

Add to `extend type Query`:

```graphql
    """
    Tenant-wide rule settings. Admin-only.
    """
    componentRuleSettings: ComponentRuleSettings!
```

Add `strict: Boolean!` to the `saveComponentRule` argument list (after `enabled`), and to `extend type Mutation`:

```graphql
    """
    Sets tenant-wide rule settings. Observe mode evaluates every rule and enforces none. Admin-only.
    """
    updateComponentRuleSettings(observeMode: Boolean!, approvalExpiresInHours: Int!): ComponentRuleSettings!
```

If `RiskLevel` collides with a type of the same name in another schema file in the same GraphQL registry, keep this
one and delete the duplicate declaration; run the controller test to confirm the schema still builds.

In `cluster-element-definition.graphqls`, add `riskLevel: RiskLevel` to `type ClusterElementDefinition`, and
declare `enum RiskLevel { LOW MEDIUM HIGH CRITICAL }` in that file if the platform-configuration schema has none.

- [ ] **Step 4: Extend the controller**

Add the `strict` argument to `saveComponentRule` (after `enabled`) and `componentRule.setStrict(strict);` to the
mapping. Add `strict` and `toolRiskLevel` to `ComponentRuleItem` and to `toItem`. An all-tools rule has no single tool, so
its risk level is null; a tool-scoped rule resolves the level from the tool's own definition, falling back to
name inference when the element cannot be loaded:

```java
    @Nullable
    private RiskLevel toToolRiskLevel(
        ComponentRule componentRule, @Nullable ComponentDefinition componentDefinition) {

        String toolName = componentRule.getToolName();

        if (toolName == null) {
            return null;
        }

        if (componentDefinition == null) {
            return ToolRiskLevelResolver.resolve(null, toolName);
        }

        try {
            ClusterElementDefinition clusterElementDefinition =
                clusterElementDefinitionService.getClusterElementDefinition(
                    componentRule.getComponentName(), componentDefinition.getVersion(), toolName);

            return clusterElementDefinition.getRiskLevel();
        } catch (RuntimeException exception) {
            // The rule may name a tool the component no longer ships; the rule row still renders, with the level
            // its name implies.
            return ToolRiskLevelResolver.resolve(null, toolName);
        }
    }
```

Inject `ClusterElementDefinitionService` through the constructor and add a mock for it in the controller test.

Add the two settings mappings:

```java
    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRuleSettings componentRuleSettings() {
        return componentRuleSettingsService.getSettings();
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRuleSettings updateComponentRuleSettings(
        @Argument boolean observeMode, @Argument int approvalExpiresInHours) {

        return componentRuleSettingsService.saveSettings(
            new ComponentRuleSettings(observeMode, approvalExpiresInHours));
    }
```

Add `ComponentRuleSettingsService` to the constructor.

Expose `riskLevel` on the cluster element GraphQL type by adding the getter to whatever projection
`ClusterElementDefinitionGraphQlController` returns — find it with
`grep -rn "class ClusterElementDefinitionGraphQlController" -A 40 server/libs/platform/platform-configuration/platform-configuration-graphql/src/main/java`
and follow the shape it already uses for `title` and `description`.

- [ ] **Step 4b: Expose it over REST too**

The client's component-definition query is REST-generated, so the field must exist there as well. In
`server/libs/platform/platform-configuration/platform-configuration-rest/platform-configuration-rest-impl/openapi.yaml`,
add to the `ClusterElementDefinition` schema (around line 1965) and to `ClusterElementDefinitionBasic` (around
line 1983):

```yaml
        riskLevel:
          type: string
          enum:
            - LOW
            - MEDIUM
            - HIGH
            - CRITICAL
          description: How much damage one call of this tool can do.
```

Then regenerate the REST models and check the MapStruct mapper picks the new property up:

```bash
./gradlew :server:libs:platform:platform-configuration:platform-configuration-rest:platform-configuration-rest-api:generateOpenApi > /tmp/t11r.log 2>&1; echo "exit=$?"
grep -E "^> Task .* FAILED" /tmp/t11r.log
```

If that task name does not exist, find the right one with
`./gradlew :server:libs:platform:platform-configuration:platform-configuration-rest:platform-configuration-rest-api:tasks --all | grep -i openapi`.
Regeneration at 7.24.0 adds `@JsonInclude(NON_NULL)` to models generated at 7.22.0 — if the diff shows that
annotation appearing on unrelated models, revert those files and hand-edit only the two cluster element models,
per the generator-drift note in CLAUDE.md.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:test :server:libs:platform:platform-configuration:platform-configuration-graphql:test --continue > /tmp/t11.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t11.log`

Expected: exit=0.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add server
git commit -m "$(cat <<'EOF'
Expose rule settings, strict rules and tool risk over GraphQL

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Update the copilot rule tools

**Files:**
- Rename: `.../automation-ai-tool/.../componentrule/DescribeComponentActionParametersToolCallback.java` →
  `DescribeComponentToolParametersToolCallback.java`
- Modify: `.../componentrule/CreateComponentRuleToolCallback.java`
- Modify: `.../componentrule/ProposeComponentRuleConditionToolCallback.java`
- Modify: `.../componentrule/ListComponentRulesToolCallback.java`
- Modify: `.../componentrule/ComponentRuleToolCallbacksFactory.java`
- Modify: `server/ee/libs/automation/automation-ai/automation-ai-copilot/src/main/resources/prompt_component_rule_ask.txt`
- Modify: `server/ee/libs/automation/automation-ai/automation-ai-copilot/src/main/resources/prompt_component_rule_build.txt`
- Test: `.../automation-ai-tool/src/test/java/com/bytechef/ee/automation/ai/tool/componentrule/ComponentRuleToolCallbacksFactoryTest.java`

**Interfaces:**
- Consumes: Tasks 5 and 10.
- Produces: tool `describeComponentToolParameters` with input `{componentName, toolName}`;
  `createComponentRule` accepting `toolName`, `strict`, and `ruleAction` `REQUIRE_APPROVAL`.

- [ ] **Step 1: Write the failing test**

In `ComponentRuleToolCallbacksFactoryTest.java`, update the read-tool name assertion to expect
`describeComponentToolParameters`, and add:

```java
    @Test
    void testCreateComponentRuleAcceptsRequireApprovalAndStrict() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> {
            ComponentRule componentRule = invocation.getArgument(0);

            componentRule.setId(5L);

            return componentRule;
        });

        CreateComponentRuleToolCallback toolCallback = new CreateComponentRuleToolCallback(componentRuleService);

        String result = toolCallback.call(
            """
                {"componentName": "slack", "toolName": "sendMessage", "phase": "BEFORE",
                 "ruleAction": "REQUIRE_APPROVAL", "condition": "true", "strict": true}""");

        assertThat(result).contains("5");

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getToolName()).isEqualTo("sendMessage");
        assertThat(componentRule.getRuleAction()).isEqualTo(RuleAction.REQUIRE_APPROVAL);
        assertThat(componentRule.isStrict()).isTrue();
    }

    @Test
    void testCreateComponentRuleRejectsRequireApprovalInTheAfterPhase() {
        CreateComponentRuleToolCallback toolCallback = new CreateComponentRuleToolCallback(componentRuleService);

        String result = toolCallback.call(
            """
                {"componentName": "slack", "phase": "AFTER", "ruleAction": "REQUIRE_APPROVAL",
                 "condition": "true"}""");

        // The model gets a corrigible message in the same turn instead of a stack trace from the service guard.
        assertThat(result).contains("AFTER");

        verify(componentRuleService, never()).saveComponentRule(any());
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test --tests '*ComponentRuleToolCallbacksFactoryTest' > /tmp/t12.log 2>&1; echo "exit=$?"; grep -E "FAILED|error:" /tmp/t12.log | head`

Expected: FAIL.

- [ ] **Step 3: Rename and re-point the describe tool**

`git mv` the file to `DescribeComponentToolParametersToolCallback.java`, rename the class, set
`TOOL_NAME = "describeComponentToolParameters"`, rename the `DescribeInput` component `actionName` to `toolName`,
and change the lookup to read the component's TOOLS cluster elements, falling back to an action of the same name:

```java
    private static final String DESCRIPTION = """
        Describe the input parameters of one component tool: each parameter's name, type, whether it is required,
        and its description, plus the tool's risk level. Call this BEFORE proposing a condition, so the condition
        references parameter names that actually exist. Read-only.""";
```

```java
    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {
                "componentName": {"type": "string", "description": "The component that owns the tool"},
                "toolName": {"type": "string", "description": "The tool whose parameters to describe"}
            },
            "required": ["componentName", "toolName"]
        }""";
```

Replace the `actionDefinitionService.getActionDefinition(...)` call with a
`clusterElementDefinitionService.getClusterElementDefinition(componentName, version, toolName)` call, catching the
same `RuntimeException` and returning the same style of tool error. Take
`ClusterElementDefinitionService` in the constructor in place of `ActionDefinitionService`, and add the tool's
`getRiskLevel()` to the returned JSON as a sibling of the property list:

```java
            return jsonMapper.writeValueAsString(
                Map.of(
                    "riskLevel", clusterElementDefinition.getRiskLevel()
                        .name(),
                    "parameters", properties.stream()
                        .map(DescribeComponentToolParametersToolCallback::toSummary)
                        .toList()));
```

- [ ] **Step 4: Update the write and propose tools**

In `CreateComponentRuleToolCallback`: rename the input component `actionName` → `toolName` and the schema property
with it, add `"strict": {"type": "boolean", "description": "Fire when the condition cannot be evaluated. Default
false."}`, change the `ruleAction` description to `"BLOCK, TAG or REQUIRE_APPROVAL. AFTER rules must be TAG."`,
change the error strings accordingly, and extend the AFTER guard:

```java
            if (phase == RulePhase.AFTER && ruleAction != RuleAction.TAG) {
                return toolError(
                    "A rule evaluated in the AFTER phase must use TAG — the tool has already run, so neither a "
                        + "block nor an approval can prevent anything.");
            }
```

plus `componentRule.setToolName(input.toolName());` and
`componentRule.setStrict(Boolean.TRUE.equals(input.strict()));`, with `@Nullable Boolean strict` appended to
`CreateComponentRuleInput`.

In `ProposeComponentRuleConditionToolCallback` and `ListComponentRulesToolCallback`, rename any `actionName`
references to `toolName` and update the prose that says "action" to say "tool". Update
`ComponentRuleToolCallbacksFactory` to construct the renamed class with its new collaborator.

- [ ] **Step 5: Update the two prompts**

In both prompt files, replace "action" with "tool" where it means the governed operation, and add a paragraph:

```
Rules govern the tool calls an AI agent makes, not the actions a workflow runs. A REQUIRE_APPROVAL rule pauses the
agent and asks a human to approve that exact call; the agent continues with the tool's result once approved, or
with a denial it can replan around. Prefer REQUIRE_APPROVAL over BLOCK for a HIGH or CRITICAL risk tool the team
still needs in the common case, and reserve BLOCK for calls that must never happen.
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test :server:ee:libs:automation:automation-ai:automation-ai-copilot:test --continue > /tmp/t12.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t12.log`

Expected: exit=0.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/automation
git commit -m "$(cat <<'EOF'
Point the rule copilot tools at tools rather than actions

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Client — tool picker, approval, strict, observe mode, risk badges

**Files:**
- Modify: `client/src/graphql/platform/component-rule/componentRules.graphql`
- Modify: `client/src/graphql/platform/component-rule/saveComponentRule.graphql`
- Create: `client/src/graphql/platform/component-rule/componentRuleSettings.graphql`
- Create: `client/src/graphql/platform/component-rule/updateComponentRuleSettings.graphql`
- Modify: `client/src/ee/pages/settings/platform/component-rules/ComponentRulesTab.tsx`
- Modify: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.tsx`
- Modify: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.tsx`
- Modify: `client/src/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot.ts`
- Test: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.test.tsx`
- Test: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.test.tsx`
- Test: `client/src/ee/pages/settings/platform/component-rules/ComponentRulesTab.test.tsx`

**Interfaces:**
- Consumes: the GraphQL surface from Task 11.
- Produces: generated hooks `useComponentRuleSettingsQuery`, `useUpdateComponentRuleSettingsMutation`; the dialog's
  tool picker fed by the component definition's `clusterElements` filtered to type `TOOLS`.

- [ ] **Step 1: Update the GraphQL operations and regenerate**

In `componentRules.graphql` and `saveComponentRule.graphql`, rename `actionName` to `toolName` in the selection
sets and the variable lists, add `strict` and `toolRiskLevel` to both selection sets, and add `$strict: Boolean!`
to the mutation's variables and `strict: $strict` to its arguments.

Create `componentRuleSettings.graphql`:

```graphql
query ComponentRuleSettings {
    componentRuleSettings {
        observeMode
        approvalExpiresInHours
    }
}
```

Create `updateComponentRuleSettings.graphql`:

```graphql
mutation UpdateComponentRuleSettings($observeMode: Boolean!, $approvalExpiresInHours: Int!) {
    updateComponentRuleSettings(observeMode: $observeMode, approvalExpiresInHours: $approvalExpiresInHours) {
        observeMode
        approvalExpiresInHours
    }
}
```

Run: `cd client && npx graphql-codegen`

Expected: `src/shared/middleware/graphql.ts` and `graphql-types.ts` regenerate with the new hooks and the
`ComponentRuleActionType.RequireApproval` member.

- [ ] **Step 2: Write the failing client tests**

In `ComponentRuleDialog.test.tsx`, add (matching the file's existing mocking style, and using `vi.hoisted` for any
module-scope refs a `vi.mock` factory touches):

```tsx
    it('offers the component tools rather than its actions', async () => {
        render(<ComponentRuleDialog onOpenChange={vi.fn()} open />);

        await userEvent.click(screen.getByLabelText('Component'));
        await userEvent.click(screen.getByText('Slack'));
        await userEvent.click(screen.getByLabelText('Tool'));

        expect(screen.getByText('sendMessage')).toBeInTheDocument();
        expect(screen.queryByText('actionOnlyOperation')).not.toBeInTheDocument();
    });

    it('disables Require approval in the After phase', async () => {
        render(<ComponentRuleDialog onOpenChange={vi.fn()} open />);

        await userEvent.click(screen.getByLabelText('After'));

        expect(screen.getByLabelText('Require approval')).toBeDisabled();
        expect(screen.getByLabelText('Block')).toBeDisabled();
    });

    it('round-trips the fail closed checkbox', async () => {
        render(<ComponentRuleDialog onOpenChange={vi.fn()} open />);

        await userEvent.click(screen.getByLabelText('Fail closed'));

        expect(screen.getByLabelText('Fail closed')).toBeChecked();
    });
```

In `ComponentRulesTab.test.tsx` (new file), a test that the observe switch calls the mutation and that the banner
renders while observe mode is on.

- [ ] **Step 3: Run them to verify they fail**

Run: `cd client && npx vitest run src/ee/pages/settings/platform/component-rules` (tool timeout 600000)

Expected: FAIL.

- [ ] **Step 4: Update the dialog**

Rename every `actionName` state and prop to `toolName`, `ALL_ACTIONS_VALUE` to `ALL_TOOLS_VALUE` (`'__all_tools__'`),
and the label from Action to Tool. Replace the `actionNames` memo with a tools memo reading the component
definition's cluster elements:

```tsx
    const toolNames = useMemo(
        () =>
            (componentDefinition?.clusterElements ?? [])
                .filter((clusterElement) => clusterElement.type?.name === 'TOOLS')
                .map((clusterElement) => clusterElement.name),
        [componentDefinition?.clusterElements]
    );
```

If `useGetComponentDefinitionQuery` does not return `clusterElements`, add the field to its query or call the
cluster-element-definitions endpoint for type `TOOLS`; check with
`grep -rn "clusterElements" client/src/shared/queries/platform/componentDefinitions.queries.ts`.

Add a third enforcement radio:

```tsx
                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Require approval"
                                        disabled={phase === ComponentRulePhase.After}
                                        id="component-rule-action-require-approval"
                                        value={ComponentRuleActionType.RequireApproval}
                                    />

                                    <Label htmlFor="component-rule-action-require-approval">Require approval</Label>
                                </div>
```

Add a `strict` state, a `Checkbox` labelled "Fail closed" with the helper text "Fire this rule when its condition
cannot be evaluated. Off by default, so a mis-authored rule does not block your agents.", include `strict` in the
mutation payload and in the open-reset effect, and extend the AFTER effect so it also moves
`RequireApproval` to `Tag`:

```tsx
    useEffect(() => {
        if (
            phase === ComponentRulePhase.After &&
            (ruleAction === ComponentRuleActionType.Block ||
                ruleAction === ComponentRuleActionType.RequireApproval)
        ) {
            setRuleAction(ComponentRuleActionType.Tag);
        }
    }, [phase, ruleAction]);
```

Update the dialog description to "Conditionally block, tag, or require human approval for a single agent tool call
based on the values it was invoked with."

- [ ] **Step 5: Update the list and the tab**

In `ComponentRuleList.tsx`, rename the Action column to Tool, read `toolName`, render a risk badge from
`toolRiskLevel` (a small `Badge` with a per-level class, CRITICAL and HIGH emphasised), render a "Require approval"
badge for the new enforcement value, and a "Fail closed" indicator when `strict`.

In `ComponentRulesTab.tsx`, add the observe switch above the list and the banner:

```tsx
            {componentRuleSettings?.observeMode && (
                <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
                    Observe mode is on. Rules are evaluated and recorded on the Audit Events page, but nothing is
                    blocked and no approval is requested.
                </div>
            )}
```

with a `Switch` bound to `useUpdateComponentRuleSettingsMutation`, invalidating `['ComponentRuleSettings']` on
success. Follow the hook-ordering rule: `useState` first, store/query hooks next, `useMemo` after, `useEffect`
last.

In `useComponentRuleCopilot.ts`, rename the `actionName` parameter to `toolName`.

- [ ] **Step 6: Run the client checks**

Run: `cd client && npm run check` (tool timeout 600000)

Expected: exit 0. `sort-keys` violations are not auto-fixable — fix them by hand.

- [ ] **Step 7: Commit**

```bash
git add client
git commit -m "$(cat <<'EOF'
client - Author rules against tools, with approval, fail closed and observe mode

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: Wire the rule module into the distributed worker and document the feature

**Files:**
- Modify: `server/ee/apps/worker-app/build.gradle.kts`
- Modify: `server/apps/server-app/build.gradle.kts`
- Create: `.agents/component-rules.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-12-component-rules-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: `platform-component-rule-service` on worker-app's classpath, so the rule wrapper finds an enforcer
  where the agent actually runs.

- [ ] **Step 1: Check how the policy module reaches each app**

Run: `grep -rn "platform-component-policy\|platform-component-rule" server/apps/server-app/build.gradle.kts server/ee/apps/*/build.gradle.kts`

Expected: the policy and rule service modules are on server-app only. Note which apps run the AI agent —
worker-app runs task execution, so it needs the enforcer.

- [ ] **Step 2: Add the dependency to worker-app**

In `server/ee/apps/worker-app/build.gradle.kts`, add in the EE block, alphabetically among the
`server:ee:libs:platform:` entries:

```kotlin
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-service"))
```

Confirm server-app already carries both the service and the graphql module; add
`platform-component-rule-graphql` and `platform-component-rule-service` if either is missing.

- [ ] **Step 3: Boot worker-app's context in its integration test**

Run: `./gradlew :server:ee:apps:worker-app:test :server:ee:apps:worker-app:testIntegration --continue > /tmp/t14.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t14.log`

Expected: exit=0. A missing-bean failure here means `ComponentRuleSettingsServiceImpl`'s `PropertyService` is not
available in worker-app — in that case add
`":server:ee:libs:platform:platform-configuration:platform-configuration-remote-client"` (already present) and
verify the remote client exposes `PropertyService`; if it does not, add a `@ConditionalOnEEVersion` stub in that
remote-client module that throws `UnsupportedOperationException`, and make `ComponentRuleSettingsServiceImpl`
fall back to `ComponentRuleSettings.DEFAULT` when the call throws.

- [ ] **Step 4: Write the agent-facing deep dive**

Create `.agents/component-rules.md` with a header comment explaining it was extracted from CLAUDE.md, and cover:
rules govern agent tool calls only, keyed on (component, TOOLS cluster element name); the wrapper is not a
`DelegatingToolCallback` and why; block returns a denial to the model rather than throwing; precedence block >
approval > tag; approval reuses the gate's suspend protocol and resume branch, and `approvedBy` is how the rule
layer knows not to re-raise; observe mode and strict; the tenant-scoped settings property row; risk levels are
advisory; the enforcement cache is keyed by (tenantId, componentName).

- [ ] **Step 5: Replace the CLAUDE.md section**

Replace the existing "Component Rules (EE)" section with a shortened version pointing at the new file, and add a
row to the deep-dives table:

```markdown
| `.agents/component-rules.md` | Component Rules: agent tool governance, approval, observe mode, strict, risk levels |
```

Keep in CLAUDE.md only the cross-cutting facts: rules govern agent tool calls (not workflow actions); the
condition is a formula body, not free SpEL; a non-strict rule fails open by design; the cache is keyed by tenant
and component.

- [ ] **Step 6: Mark the v1 spec superseded**

At the top of `docs/superpowers/specs/2026-08-12-component-rules-design.md`, under the Status line, add:

```markdown
**Superseded by:** `2026-09-02-component-rules-tool-governance-design.md`, which re-scopes rules from workflow
action executions to AI agent tool calls and adds approval, observe mode, strict evaluation and risk levels.
```

- [ ] **Step 7: Full verification**

```bash
./gradlew spotlessApply
./gradlew check --continue > /tmp/final.log 2>&1; echo "exit=$?"
grep -E "^> Task .* FAILED" /tmp/final.log
```

Expected: exit=0. Compare any failure against the known-failing list before treating it as caused by this work.

```bash
cd client && npm run check
```

Expected: exit 0 (tool timeout 600000).

- [ ] **Step 8: Commit**

```bash
git add server CLAUDE.md .agents docs
git commit -m "$(cat <<'EOF'
Run component rule enforcement on the distributed worker

The agent runs on worker-app, which had no enforcer on its classpath, so rules
were silently inert in a distributed deployment.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

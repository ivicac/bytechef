# Component Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an admin author conditional, SpEL-evaluated rules that block or tag a single component action call based on its actual input (and, post-execution, its output), with AI-assisted authoring of the condition.

**Architecture:** A new EE module trio `platform-component-rule` (`-api`/`-service`/`-graphql`) owns the `component_rule` table, exactly mirroring `platform-component-policy`'s shape. Enforcement hangs off a **new CE SPI** `ComponentRuleEnforcer` in `platform-component-api`, injected into `ActionDefinitionServiceImpl` as a `List<>` beside the existing `List<ComponentVisibilityProvider>` — CE ships no implementation, so CE behavior is byte-identical. The EE implementation evaluates conditions through the platform `Evaluator` bean, caches per `(tenant, component)` with a 10s Caffeine TTL, and emits audit events through an `ApplicationEventPublisher` in the `ConnectionAuditPublisher` style. AI authoring follows the current (post-ticket-732) copilot shape: a `ComponentRuleToolCallbacksFactory` whose reads register flat on the AI Hub agents and whose one write is catalog-demoted — **not** a `component_rule_agent` delegate.

**Tech Stack:** Java 25 / Spring Boot 4, Spring Data JDBC, Liquibase, Spring GraphQL, Caffeine, Spring AI `ToolCallback`, React 19 + TypeScript + TanStack Query + graphql-codegen, Vitest.

**Spec:** [docs/superpowers/specs/2026-08-12-component-rules-design.md](../specs/2026-08-12-component-rules-design.md)

## Deviations from the spec (read before starting)

The spec was written against `master` in August, before its two predecessors merged. Four of its statements are now wrong or under-specified against the code in this worktree. Each deviation below is deliberate; do not "correct" the code back toward the spec.

1. **The predecessor caveat is resolved.** The spec's opening warns that `platform-component-policy` does not exist yet. It does — `server/ee/libs/platform/platform-component-policy/` with all three submodules, `component_policy` + `component_operation_policy` changelogs, and `ComponentPolicyVisibilityProvider`. Plan against the real module.

2. **No `component_rule_agent` delegate (spec §5).** The spec says to register a delegate callback in `ai-copilot-tool` and wire it through `AiHubConfiguration.wrapDelegate`. That pattern was removed in ticket 732's CRUD-delegate unwind — see the javadoc on `ContextStoreAgentConfiguration` and the comment block above `AiHubConfiguration#mcpServerFlatCrudToolCallbacks`, which name all five dissolved delegates. Component Rules is self-contained CRUD, so per CLAUDE.md ("Self-contained CRUD goes flat") its reads register flat on both AI Hub agents and its write is catalog-demoted. Tasks 8 and 9 implement this.

3. **The enforcement cache must be keyed by tenant, not by component name (spec §2, "Caching").** The spec says "a Caffeine cache keyed by `componentName`". Component names are global across tenants, and `ComponentPolicyVisibilityProvider` — the class the spec says to mirror — keys its cache by `TenantContext.getCurrentTenantId()`. A component-name-only key would serve one tenant's rules to another. Task 4 keys by a `TenantComponentKey(tenantId, componentName)` record.

4. **A condition is a *formula body*, not free SpEL (spec is silent).** The platform `Evaluator` (`SpelEvaluator`) parses full SpEL only when the string starts with `=` (`FORMULA_PREFIX`); everything else is treated as a `${...}` template. Its `validateFormulaExpression` additionally rejects `T(`, any `.method(` call, and `new`. So:
   - The stored `condition` is the formula **body** (no leading `=`); every evaluation and every parse-check prepends `=`.
   - Rule authors and the copilot must use ByteChef's whitelisted evaluator functions (`contains`, `equalsIgnoreCase`, `length`, `size`, `indexOf`, `split`, …) rather than Java methods. `contains(inputParameters['channel'], 'C05')` is valid; `inputParameters['channel'].startsWith('C05')` is not.
   - `SpelEvaluator` returns the **original string** for an unresolved reference rather than null, so only `Boolean.TRUE.equals(result)` counts as a match. A BLOCK rule whose condition cannot resolve therefore does not block. This fail-open behavior is deliberate and is covered by a test in Task 3.

5. **~~`connectionId` is not available at the chokepoint~~ — WITHDRAWN, the spec was right.** This plan originally claimed `ComponentConnection` carries no connection id. That was an authoring error: there are TWO records with that name, and the plan read the wrong one. `com.bytechef.platform.configuration.domain.ComponentConnection` is `(componentName, componentVersion, workflowNodeName, key, required)`, but the one `ActionDefinitionServiceImpl` actually imports is `com.bytechef.platform.component.ComponentConnection`, which is `(String componentName, int version, long connectionId, Map<String, ?> parameters, @Nullable AuthorizationType authorizationType)` — it has `connectionId()` and `getConnectionId()`. The evaluation context therefore exposes `connectionId` exactly as spec §2 requires. `ActionCall` carries `@Nullable Long connectionId` (null when the action takes no connection); there is no `connectionKey` and no `workflowNodeName`.

6. **Client tab placement (spec §6 explicitly defers this).** The Policies tab is mounted at `components/policies` but has no `TabsTrigger` — it was hidden when Component Visibility was promoted to a top-level tab of the Components page. This plan makes Rules a **top-level `components/rules` tab**, a sibling of Component Visibility, and leaves `ComponentPoliciesTab` untouched. Rationale: re-showing a Policies tab whose only visible child would be Rules recreates the single-child problem that got it hidden.

## Global Constraints

- **Every new file under `server/ee/`** uses the ByteChef Enterprise license header (copy the header verbatim from `server/ee/libs/platform/platform-component-policy/platform-component-policy-api/src/main/java/com/bytechef/ee/platform/component/policy/ComponentPolicy.java`) and carries a `@version ee` Javadoc tag. Spotless selects the EE header by that tag's **content**, not by path — a missing tag fails `spotlessCheck`.
- **Every new file under `server/libs/`** uses the Apache 2.0 header (copy from `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/visibility/ComponentVisibilityProvider.java`) and has **no** `@version ee` tag.
- **Author tag:** `@author Ivica Cardic` on every new Java type.
- **Enum ordinals are persisted as INT** — `RulePhase.BEFORE=0, AFTER=1`; `RuleAction.BLOCK=0, TAG=1`. Append-only, never reorder.
- **Java blank-line rules:** one blank line before `if`/`for`/`while`/`try`/`switch` (except immediately after an opening `{`); one blank line between a variable modification and the statement that uses it; no blank line before a class's closing `}`.
- **No `_`-prefixed private methods. No short/cryptic variable names**, including lambda parameters (`componentRule`, not `r`).
- **Client:** object keys in ascending alphabetical order (`sort-keys`, not auto-fixable); named imports sorted alphabetically inside `{}`; interface names end in `I` or `Props`; lucide icons imported with the `Icon` suffix; `twMerge` not `cn()`; hook order `useState` → `useRef` → store hooks → other hooks → `useMemo`/`useCallback` → `useEffect` → `return`.
- **Client tests:** module-scope refs used inside a `vi.mock` factory must be declared with `vi.hoisted(...)`.
- **Test naming:** unit tests end in `Test`, integration tests in `IntTest`; test method names are camelCase with no underscores; the rule applies to private helpers too.
- **No `TODO:` comments** — Checkstyle's `TodoComment` rule fails the build.
- **Before every server commit:** `./gradlew spotlessApply`. Never judge a Gradle run through a pipe — redirect to a file, check `$?` on its own line, then grep the file for `^> Task .* FAILED`.
- **Before every client commit:** run from `client/`, `npm run check`, with the tool timeout set to 600000 ms (it is auto-backgrounded at 120s otherwise).

---

## File Structure

**New EE module `server/ee/libs/platform/platform-component-rule`** (three Gradle submodules, registered in `settings.gradle.kts`):

| File | Responsibility |
|------|----------------|
| `platform-component-rule-api/.../ComponentRule.java` | Spring Data JDBC aggregate for `component_rule`, plus the nested `RulePhase` / `RuleAction` enums |
| `platform-component-rule-api/.../ComponentRuleService.java` | Service contract: list, enabled-lookup for enforcement, save, delete |
| `platform-component-rule-api/.../ComponentRuleErrorType.java` | Typed errors for the two save rejections |
| `platform-component-rule-service/.../repository/ComponentRuleRepository.java` | `CrudRepository` + the two derived finders |
| `platform-component-rule-service/.../service/ComponentRuleServiceImpl.java` | Validation (`BLOCK`+`AFTER`, SpEL parse) + persistence, admin-gated mutations |
| `platform-component-rule-service/.../ComponentRuleEnforcerImpl.java` | The EE `ComponentRuleEnforcer`: per-tenant-per-component cache, condition evaluation, block/tag decision |
| `platform-component-rule-service/.../audit/ComponentRuleAuditEvent.java` | `RULE_BLOCKED` / `RULE_TAGGED`, both non-strict |
| `platform-component-rule-service/.../audit/ComponentRuleAuditPublisher.java` | Best-effort `AuditApplicationEvent` publish |
| `platform-component-rule-service/.../config/ComponentRuleJdbcRepositoryConfiguration.java` | `@EnableJdbcRepositories` autoconfiguration |
| `platform-component-rule-service/src/main/resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml` | The table |
| `platform-component-rule-graphql/.../ComponentRuleGraphQlController.java` | Admin-gated query + two mutations |
| `platform-component-rule-graphql/src/main/resources/graphql/component-rule.graphqls` | Schema |

**Modified CE files:**

| File | Change |
|------|--------|
| `server/libs/platform/platform-component/platform-component-api/.../visibility/ComponentRuleEnforcer.java` | **New.** The SPI seam. CE ships no implementation. |
| `server/libs/platform/platform-component/platform-component-service/.../ActionDefinitionServiceImpl.java` | Inject `List<ComponentRuleEnforcer>`; call before/after hooks in `doExecutePerform` and `executePerformForPolyglot` |
| `server/libs/platform/platform-component/platform-component-service/.../exception/ActionDefinitionErrorType.java` | Add `RULE_BLOCKED = 108` |
| `server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml` | One `includeAll` for the new changelog directory |
| `server/apps/server-app/build.gradle.kts` | Depend on the new `-service` and `-graphql` modules |
| `settings.gradle.kts` | Three `include(...)` lines |

**AI-authoring files:**

| File | Responsibility |
|------|----------------|
| `server/ee/libs/automation/automation-ai/automation-ai-tool/.../componentrule/ListComponentRulesToolCallback.java` | Read tool |
| `.../componentrule/ProposeComponentRuleConditionToolCallback.java` | Generates a condition from prose + the action's real parameter schema; returns it, saves nothing |
| `.../componentrule/CreateComponentRuleToolCallback.java` | The one write tool |
| `.../componentrule/ComponentRuleToolCallbacksFactory.java` | `readToolCallbacks()` / `writeToolCallbacks()` |
| `server/ee/libs/automation/automation-ai/automation-ai-copilot/.../config/ComponentRuleAgentConfiguration.java` | Panel ASK/BUILD agents + the factory bean |
| `.../automation-ai-copilot/src/main/resources/prompt_component_rule_{ask,build}.txt` | Prompts, carrying the formula-body contract |
| `server/libs/ai/ai-copilot/ai-copilot-service/.../util/Source.java` | Add `COMPONENT_RULE` |
| `server/ee/libs/ai/ai-hub/ai-hub-service/.../config/AiHubConfiguration.java` | Flat reads on both agents; write catalog-demoted on BUILD |

**Client files:**

| File | Responsibility |
|------|----------------|
| `client/src/graphql/platform/component-rule/{componentRules,saveComponentRule,deleteComponentRule}.graphql` | Operations for codegen |
| `client/src/ee/pages/settings/platform/component-rules/ComponentRulesTab.tsx` | The tab: list + Add button |
| `.../component-rules/components/ComponentRuleList.tsx` | Flat list row rendering + optimistic enabled toggle |
| `.../component-rules/components/ComponentRuleDialog.tsx` | Add/Edit dialog |
| `.../component-rules/hooks/useComponentRuleCopilot.ts` | "Generate with AI" → condition textarea |
| `client/src/ee/pages/settings/platform/components/Components.tsx` | New `rules` tab trigger + content |
| `client/src/routes.tsx` | `components/rules` route |
| `client/src/shared/components/copilot/stores/useCopilotStore.ts` | Add `COMPONENT_RULE` to `Source` |
| `client/codegen.ts` | Add the new `.graphqls` glob |

---

## Task 1: `platform-component-rule-api` — domain, enums, service contract

**Files:**
- Create: `settings.gradle.kts` (modify — 3 lines)
- Create: `server/ee/libs/platform/platform-component-rule/platform-component-rule-api/build.gradle.kts`
- Create: `server/ee/libs/platform/platform-component-rule/platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRule.java`
- Create: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleService.java`
- Create: `.../platform-component-rule-api/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleErrorType.java`
- Test: `.../platform-component-rule-api/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnumOrdinalTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ComponentRule` with `Long getId()`, `String getComponentName()`, `@Nullable String getActionName()`, `RulePhase getPhase()`, `RuleAction getRuleAction()`, `String getCondition()`, `@Nullable String getDescription()`, `boolean isEnabled()`, matching setters (`setId`, `setComponentName`, `setActionName`, `setPhase(RulePhase)`, `setRuleAction(RuleAction)`, `setCondition`, `setDescription`, `setEnabled`), and a no-arg constructor.
  - `ComponentRule.RulePhase { BEFORE, AFTER }`, `ComponentRule.RuleAction { BLOCK, TAG }`.
  - `ComponentRuleService` with `List<ComponentRule> getComponentRules()`, `List<ComponentRule> getComponentRules(String componentName)`, `List<ComponentRule> getEnabledComponentRules(String componentName)`, `ComponentRule saveComponentRule(ComponentRule componentRule)`, `void deleteComponentRule(long id)`.
  - `ComponentRuleErrorType.BLOCK_AFTER_UNSUPPORTED` (errorKey 100) and `ComponentRuleErrorType.INVALID_CONDITION` (errorKey 101).

> Note on `getEnabledComponentRules(String componentName)`: the spec's signature takes `actionName` and `phase` too. This plan fetches **all** enabled rules for a component in one query and filters by action/phase in the enforcer, because the spec's own caching section requires "one query per component, not per action". Narrowing in SQL would defeat the cache.

- [ ] **Step 1: Register the three modules in `settings.gradle.kts`**

Insert alphabetically, immediately after the three `platform-component-policy` lines (currently lines 812-814):

```kotlin
include("server:ee:libs:platform:platform-component-rule:platform-component-rule-api")
include("server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql")
include("server:ee:libs:platform:platform-component-rule:platform-component-rule-service")
```

- [ ] **Step 2: Create the api module's `build.gradle.kts`**

`server/ee/libs/platform/platform-component-rule/platform-component-rule-api/build.gradle.kts`:

```kotlin
dependencies {
    api("org.springframework.data:spring-data-commons")

    implementation("org.springframework.data:spring-data-relational")
    implementation(project(":server:libs:core:error:error-api"))

    testImplementation("org.assertj:assertj-core")
}
```

If `:server:libs:core:error:error-api` does not resolve, find the module that owns `com.bytechef.exception.AbstractErrorType` with:

```bash
grep -rl "package com.bytechef.exception;" --include=AbstractErrorType.java server/libs
```

and use that module's Gradle path instead.

- [ ] **Step 3: Write the failing ordinal-stability test**

`.../platform-component-rule-api/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnumOrdinalTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleEnumOrdinalTest {

    @Test
    void testRulePhaseOrdinalsAreStable() {
        assertThat(RulePhase.BEFORE.ordinal()).isEqualTo(0);
        assertThat(RulePhase.AFTER.ordinal()).isEqualTo(1);
        assertThat(RulePhase.values()).hasSize(2);
    }

    @Test
    void testRuleActionOrdinalsAreStable() {
        assertThat(RuleAction.BLOCK.ordinal()).isEqualTo(0);
        assertThat(RuleAction.TAG.ordinal()).isEqualTo(1);
        assertThat(RuleAction.values()).hasSize(2);
    }

    @Test
    void testPhaseAndRuleActionRoundTripThroughOrdinalColumns() {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setPhase(RulePhase.AFTER);
        componentRule.setRuleAction(RuleAction.TAG);

        assertThat(componentRule.getPhase()).isEqualTo(RulePhase.AFTER);
        assertThat(componentRule.getRuleAction()).isEqualTo(RuleAction.TAG);
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run:

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-api:test > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t1.log | head
```

Expected: FAIL — `cannot find symbol: class ComponentRule`.

- [ ] **Step 5: Write `ComponentRule`**

`.../ComponentRule.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A conditional, tenant-wide governance rule over a single component action. Unlike
 * {@code ComponentOperationPolicy}, which is a pure deny-list whose row presence is the whole signal, a rule carries a
 * payload: the phase it runs in, what it does when it fires, and the SpEL condition that decides whether it fires at
 * all. That payload has to survive a temporary disable, which is why {@code enabled} is a column rather than row
 * presence.
 *
 * <p>
 * {@code actionName} is nullable: null means the rule applies to every action of the component. There is deliberately
 * no composite unique key — one component/action pair may carry several unrelated rules.
 * </p>
 *
 * <p>
 * {@code condition} stores a ByteChef <b>formula body</b> — the text that follows the {@code =} prefix
 * {@code SpelEvaluator} requires before it will parse full SpEL. Callers prepend the {@code =} at evaluation and
 * parse-check time; it is never stored. Because {@code SpelEvaluator.validateFormulaExpression} rejects {@code T(},
 * {@code .method(} calls and {@code new}, a condition composes the evaluator's own whitelisted functions
 * ({@code contains}, {@code equalsIgnoreCase}, {@code size}, …) rather than Java methods.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("component_rule")
public class ComponentRule {

    /**
     * When the rule is evaluated relative to the action's {@code perform}. INT ordinal persisted — append new values at
     * the end, never reorder.
     */
    public enum RulePhase {
        BEFORE, AFTER
    }

    /**
     * What a firing rule does. INT ordinal persisted — append new values at the end, never reorder.
     */
    public enum RuleAction {
        BLOCK, TAG
    }

    @Id
    private Long id;

    @Column("component_name")
    private String componentName;

    @Column("action_name")
    private @Nullable String actionName;

    @Column("phase")
    private int phase;

    @Column("rule_action")
    private int ruleAction;

    @Column("condition")
    private String condition;

    @Column("description")
    private @Nullable String description;

    @Column("enabled")
    private boolean enabled = true;

    @CreatedBy
    @Column("created_by")
    private String createdBy;

    @CreatedDate
    @Column("created_date")
    private LocalDateTime createdDate;

    @LastModifiedBy
    @Column("last_modified_by")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column("last_modified_date")
    private LocalDateTime lastModifiedDate;

    @Version
    private int version;

    public ComponentRule() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getComponentName() {
        return componentName;
    }

    public void setComponentName(String componentName) {
        this.componentName = componentName;
    }

    public @Nullable String getActionName() {
        return actionName;
    }

    public void setActionName(@Nullable String actionName) {
        this.actionName = actionName;
    }

    public RulePhase getPhase() {
        return RulePhase.values()[phase];
    }

    public void setPhase(RulePhase phase) {
        this.phase = phase.ordinal();
    }

    public RuleAction getRuleAction() {
        return RuleAction.values()[ruleAction];
    }

    public void setRuleAction(RuleAction ruleAction) {
        this.ruleAction = ruleAction.ordinal();
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVersion() {
        return version;
    }

    /**
     * Whether this rule governs the given action: either it names that action, or it names none and therefore governs
     * every action of its component.
     */
    public boolean appliesToAction(String actionName) {
        return this.actionName == null || this.actionName.equals(actionName);
    }
}
```

- [ ] **Step 6: Write `ComponentRuleErrorType`**

`.../ComponentRuleErrorType.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import com.bytechef.exception.AbstractErrorType;

/**
 * Typed save-time rejections, so the GraphQL surface can report which rule was refused and why instead of a generic
 * 500.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class ComponentRuleErrorType extends AbstractErrorType {

    public static final ComponentRuleErrorType BLOCK_AFTER_UNSUPPORTED = new ComponentRuleErrorType(100);
    public static final ComponentRuleErrorType INVALID_CONDITION = new ComponentRuleErrorType(101);

    private ComponentRuleErrorType(int errorKey) {
        super(ComponentRule.class, errorKey);
    }
}
```

- [ ] **Step 7: Write `ComponentRuleService`**

`.../ComponentRuleService.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.util.List;

/**
 * Tenant-wide component rule operations.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleService {

    /**
     * Every rule in the tenant, enabled or not — the flat list the Rules page renders.
     */
    List<ComponentRule> getComponentRules();

    List<ComponentRule> getComponentRules(String componentName);

    /**
     * The enforcement-path query: every enabled rule for one component, both phases and both actions-scoped and
     * all-actions rules. Deliberately not narrowed by action or phase, because the enforcer caches one entry per
     * component and narrowing in SQL would make that cache useless.
     */
    List<ComponentRule> getEnabledComponentRules(String componentName);

    /**
     * Validates and persists. Rejects a {@code BLOCK}+{@code AFTER} combination and a condition that does not parse as
     * a formula expression, both before touching the database.
     */
    ComponentRule saveComponentRule(ComponentRule componentRule);

    void deleteComponentRule(long id);
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run:

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-api:test > /tmp/t1.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t1.log
```

Expected: `exit=0`, no FAILED lines.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add settings.gradle.kts server/ee/libs/platform/platform-component-rule && git commit -m "5xxx Add component rule domain model and service contract"
```

Replace `5xxx` with the real ticket number before committing.

---

## Task 2: `platform-component-rule-service` — repository, validation, schema

**Files:**
- Create: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/build.gradle.kts`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/repository/ComponentRuleRepository.java`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceImpl.java`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/config/ComponentRuleJdbcRepositoryConfiguration.java`
- Create: `.../platform-component-rule-service/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `.../platform-component-rule-service/src/main/resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml`
- Modify: `server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml` (one line after the `component_policy` include on line 32)
- Modify: `server/apps/server-app/build.gradle.kts` (after line 403)
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceTest.java`

**Interfaces:**
- Consumes: `ComponentRule`, `ComponentRule.RulePhase`, `ComponentRule.RuleAction`, `ComponentRuleService`, `ComponentRuleErrorType` from Task 1; the `Evaluator` bean (`com.bytechef.evaluator.Evaluator`) declared by `EvaluatorConfiguration`.
- Produces:
  - `ComponentRuleRepository extends CrudRepository<ComponentRule, Long>` with `List<ComponentRule> findAllByComponentName(String componentName)` and `List<ComponentRule> findAllByComponentNameAndEnabled(String componentName, boolean enabled)`.
  - `ComponentRuleServiceImpl(ComponentRuleRepository componentRuleRepository, Evaluator evaluator)`.
  - `ComponentRuleServiceImpl.FORMULA_PREFIX` is private; the parse check is `evaluator.evaluate(Map.of("condition", "=" + condition), Map.of(), false)`.

- [ ] **Step 1: Create the service module's `build.gradle.kts`**

`.../platform-component-rule-service/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.data:spring-data-jdbc")
    implementation("org.springframework.security:spring-security-core")
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-api"))
    implementation(project(":server:libs:core:evaluator:evaluator-api"))
    implementation(project(":server:libs:core:tenant:tenant-api"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":server:libs:core:evaluator:evaluator-impl"))
}
```

- [ ] **Step 2: Write the failing service test**

`.../src/test/java/com/bytechef/ee/platform/component/rule/service/ComponentRuleServiceTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleErrorType;
import com.bytechef.ee.platform.component.rule.repository.ComponentRuleRepository;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.exception.ConfigurationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleServiceTest {

    private final ComponentRuleRepository componentRuleRepository = mock(ComponentRuleRepository.class);
    private final Evaluator evaluator = SpelEvaluator.builder()
        .build();
    private final ComponentRuleServiceImpl componentRuleService =
        new ComponentRuleServiceImpl(componentRuleRepository, evaluator);

    @Test
    void testSaveRejectsBlockInAfterPhase() {
        ComponentRule componentRule = newComponentRule(RulePhase.AFTER, RuleAction.BLOCK, "output['ok'] == false");

        assertThatThrownBy(() -> componentRuleService.saveComponentRule(componentRule))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("AFTER");

        verify(componentRuleRepository, never()).save(any());
    }

    @Test
    void testSaveRejectsUnparseableCondition() {
        ComponentRule componentRule = newComponentRule(RulePhase.BEFORE, RuleAction.BLOCK, "inputParameters[[[");

        assertThatThrownBy(() -> componentRuleService.saveComponentRule(componentRule))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("condition");

        verify(componentRuleRepository, never()).save(any());
    }

    @Test
    void testSaveRejectsConditionCallingAJavaMethod() {
        ComponentRule componentRule = newComponentRule(
            RulePhase.BEFORE, RuleAction.TAG, "inputParameters['channel'].startsWith('C05')");

        assertThatThrownBy(() -> componentRuleService.saveComponentRule(componentRule))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("condition");
    }

    @Test
    void testSaveAcceptsAnEvaluatorFunctionCondition() {
        ComponentRule componentRule = newComponentRule(
            RulePhase.BEFORE, RuleAction.TAG, "contains(inputParameters['channel'], 'C05')");

        when(componentRuleRepository.save(componentRule)).thenReturn(componentRule);

        assertThat(componentRuleService.saveComponentRule(componentRule)).isSameAs(componentRule);
    }

    @Test
    void testSaveAcceptsBlockInBeforePhase() {
        ComponentRule componentRule = newComponentRule(
            RulePhase.BEFORE, RuleAction.BLOCK, "inputParameters['recordId'] != null");

        when(componentRuleRepository.save(componentRule)).thenReturn(componentRule);

        assertThat(componentRuleService.saveComponentRule(componentRule)).isSameAs(componentRule);
    }

    @Test
    void testGetEnabledComponentRulesFiltersOnEnabled() {
        ComponentRule componentRule = newComponentRule(RulePhase.BEFORE, RuleAction.TAG, "true");

        when(componentRuleRepository.findAllByComponentNameAndEnabled("slack", true))
            .thenReturn(List.of(componentRule));

        assertThat(componentRuleService.getEnabledComponentRules("slack")).containsExactly(componentRule);
    }

    @Test
    void testDeleteDelegatesToRepository() {
        componentRuleService.deleteComponentRule(7L);

        verify(componentRuleRepository).deleteById(7L);
    }

    @Test
    void testErrorTypeOnBlockAfterRejection() {
        ComponentRule componentRule = newComponentRule(RulePhase.AFTER, RuleAction.BLOCK, "true");

        assertThatThrownBy(() -> componentRuleService.saveComponentRule(componentRule))
            .isInstanceOfSatisfying(
                ConfigurationException.class,
                configurationException -> assertThat(configurationException.getErrorType())
                    .isEqualTo(ComponentRuleErrorType.BLOCK_AFTER_UNSUPPORTED));
    }

    private static ComponentRule newComponentRule(RulePhase rulePhase, RuleAction ruleAction, String condition) {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
        componentRule.setPhase(rulePhase);
        componentRule.setRuleAction(ruleAction);
        componentRule.setCondition(condition);
        componentRule.setEnabled(true);

        return componentRule;
    }
}
```

If `ConfigurationException` exposes the error type under a different accessor than `getErrorType()`, check the real name with:

```bash
grep -n "ErrorType" server/libs/core/exception/exception-api/src/main/java/com/bytechef/exception/AbstractException.java
```

and adjust `testErrorTypeOnBlockAfterRejection` to match.

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t2.log | head
```

Expected: FAIL — `cannot find symbol: class ComponentRuleServiceImpl`.

- [ ] **Step 4: Write the repository**

`.../repository/ComponentRuleRepository.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.repository;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Repository
@ConditionalOnEEVersion
public interface ComponentRuleRepository extends CrudRepository<ComponentRule, Long> {

    List<ComponentRule> findAllByComponentName(String componentName);

    List<ComponentRule> findAllByComponentNameAndEnabled(String componentName, boolean enabled);
}
```

- [ ] **Step 5: Write `ComponentRuleServiceImpl`**

`.../service/ComponentRuleServiceImpl.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.service;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleErrorType;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.ee.platform.component.rule.repository.ComponentRuleRepository;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
public class ComponentRuleServiceImpl implements ComponentRuleService {

    private static final String CONDITION_KEY = "condition";
    private static final String FORMULA_PREFIX = "=";

    private final ComponentRuleRepository componentRuleRepository;
    private final Evaluator evaluator;

    public ComponentRuleServiceImpl(ComponentRuleRepository componentRuleRepository, Evaluator evaluator) {
        this.componentRuleRepository = componentRuleRepository;
        this.evaluator = evaluator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getComponentRules() {
        return StreamSupport.stream(
            componentRuleRepository.findAll()
                .spliterator(),
            false)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getComponentRules(String componentName) {
        return componentRuleRepository.findAllByComponentName(componentName);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getEnabledComponentRules(String componentName) {
        return componentRuleRepository.findAllByComponentNameAndEnabled(componentName, true);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRule saveComponentRule(ComponentRule componentRule) {
        // A block only means anything before the action runs. After `perform` has already produced output, whatever
        // side effect the block was meant to prevent has happened, so the combination is refused at save rather than
        // silently degraded to a tag at execution.
        if (componentRule.getPhase() == RulePhase.AFTER && componentRule.getRuleAction() == RuleAction.BLOCK) {
            throw new ConfigurationException(
                "A rule evaluated in the AFTER phase cannot BLOCK — the action has already run. Use TAG instead.",
                ComponentRuleErrorType.BLOCK_AFTER_UNSUPPORTED);
        }

        validateCondition(componentRule.getCondition());

        return componentRuleRepository.save(componentRule);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void deleteComponentRule(long id) {
        componentRuleRepository.deleteById(id);
    }

    /**
     * Parses the condition the same way the enforcer will evaluate it — as a formula expression, with the {@code =}
     * prefix prepended and an empty context. A syntax error, a banned construct ({@code T(}, a {@code .method(} call,
     * {@code new}) or an unknown function surfaces here, at save time, instead of at the next execution. An empty
     * context is enough: unresolved references are not errors in the evaluator, only bad syntax is.
     */
    private void validateCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            throw new ConfigurationException(
                "A rule condition must not be empty.", ComponentRuleErrorType.INVALID_CONDITION);
        }

        try {
            evaluator.evaluate(Map.of(CONDITION_KEY, FORMULA_PREFIX + condition), Map.of(), false);
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                "The rule condition is not a valid expression: " + exception.getMessage(), exception,
                ComponentRuleErrorType.INVALID_CONDITION);
        }
    }
}
```

- [ ] **Step 6: Write the JDBC autoconfiguration and its registration**

`.../config/ComponentRuleJdbcRepositoryConfiguration.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@AutoConfiguration(afterName = "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration")
@ConditionalOnBean(AbstractJdbcConfiguration.class)
@EnableJdbcRepositories(basePackages = "com.bytechef.ee.platform.component.rule.repository")
public class ComponentRuleJdbcRepositoryConfiguration {
}
```

`.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (single line, no trailing newline issues):

```
com.bytechef.ee.platform.component.rule.config.ComponentRuleJdbcRepositoryConfiguration
```

- [ ] **Step 7: Write the Liquibase changelog**

`.../src/main/resources/config/liquibase/changelog/platform/component_rule/20260831000001_component_rule_init.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                   http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <changeSet id="20260831000001" author="Ivica Cardic">
        <createTable tableName="component_rule">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="component_name" type="VARCHAR(256)">
                <constraints nullable="false"/>
            </column>
            <column name="action_name" type="VARCHAR(256)"/>
            <column name="phase" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="rule_action" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="condition" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="description" type="TEXT"/>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="created_by" type="VARCHAR(50)">
                <constraints nullable="false"/>
            </column>
            <column name="last_modified_date" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="last_modified_by" type="VARCHAR(50)">
                <constraints nullable="false"/>
            </column>
            <column name="version" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <createIndex tableName="component_rule" indexName="idx_component_rule_component_name">
            <column name="component_name"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

There is deliberately **no** unique constraint: one component/action pair may carry several rules. The index exists because `findAllByComponentNameAndEnabled` runs on every cache miss of every action execution.

`condition` is a reserved word in some SQL dialects but not in PostgreSQL, which is the only supported database; the `@Column("condition")` mapping quotes it consistently with the rest of the schema. If a future dialect complains, rename the column and the `@Column` annotation together.

- [ ] **Step 8: Register the changelog in `master.xml`**

In `server/libs/config/liquibase-config/src/main/resources/config/liquibase/master.xml`, immediately after the existing `component_policy` line:

```xml
    <includeAll path="classpath:config/liquibase/changelog/platform/component_rule/" relativeToChangelogFile="false" errorIfMissingOrEmpty="false" contextFilter="mono or configuration or multitenant" />
```

- [ ] **Step 9: Add the server-app dependency**

In `server/apps/server-app/build.gradle.kts`, after the two `platform-component-policy` lines:

```kotlin
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-service"))
```

(The `-graphql` module is added in Task 5, once it exists.)

- [ ] **Step 10: Run the test to verify it passes**

Run:

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/t2.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t2.log
```

Expected: `exit=0`, no FAILED lines. If `testSaveRejectsConditionCallingAJavaMethod` passes unexpectedly at the parse step rather than the validation step, that is still a pass — the assertion only requires the rejection, not which of the two guards produced it.

- [ ] **Step 11: Prove the schema builds from scratch**

The `liquibase` Spring profile does not apply migrations via `bootRun`. Verify with an existing Testcontainers integration test instead, which builds the schema from zero:

```bash
./gradlew :server:ee:libs:platform:platform-component-policy:platform-component-policy-service:testIntegration > /tmp/t2int.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t2int.log
```

Expected: `exit=0`. That module's `ComponentPolicyRepositoryIntTest` loads the full `master.xml`, so a malformed `component_rule` changelog fails it. Docker must be running; if Testcontainers cannot find a socket, point it at OrbStack's.

- [ ] **Step 12: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add server/ee/libs/platform/platform-component-rule server/libs/config/liquibase-config server/apps/server-app/build.gradle.kts && git commit -m "5xxx Add component rule persistence and save-time validation"
```

---

## Task 3: The CE enforcement seam

This task adds the SPI and the call sites but ships **no implementation** — CE behaviour must be bit-identical, so the tests here assert both "an enforcer that blocks does block" and "no enforcers means nothing changes".

**Files:**
- Create: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/rule/ComponentRuleEnforcer.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/exception/ActionDefinitionErrorType.java`
- Modify: `server/libs/platform/platform-component/platform-component-service/src/main/java/com/bytechef/platform/component/service/ActionDefinitionServiceImpl.java`
- Test: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceImplRuleTest.java`

**Interfaces:**
- Consumes: `ConfigurationException`, `ActionDefinitionErrorType` (existing).
- Produces:
  - `com.bytechef.platform.component.rule.ComponentRuleEnforcer` with nested `record ActionCall(String componentName, String actionName, Map<String, ?> inputParameters, @Nullable Long connectionId, @Nullable Long jobId, @Nullable Long taskExecutionId)`, and two methods: `@Nullable String checkBeforePerform(ActionCall actionCall)` and `void recordAfterPerform(ActionCall actionCall, @Nullable Object output)`.
  - **`checkBeforePerform` returns a refusal reason rather than throwing.** `ActionDefinitionErrorType` lives in `platform-component-service`, not `-api`, so an EE implementation that threw `ConfigurationException(..., RULE_BLOCKED)` itself would have to depend on the whole CE component-service module. Returning `null` to allow and a message to refuse keeps the exception type on the CE side that owns it, and leaves the EE side owning only the decision.
  - `ActionDefinitionErrorType.RULE_BLOCKED` (errorKey 108).
  - `ActionDefinitionServiceImpl`'s constructor gains a fourth parameter: `List<ComponentRuleEnforcer> componentRuleEnforcers`. **Every existing `new ActionDefinitionServiceImpl(...)` call site must be updated** — Step 6 enumerates them.

- [ ] **Step 1: Write the failing enforcement test**

`.../platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceImplRuleTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.component.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.ComponentDefinitionRegistry;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.constant.PlatformType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ActionDefinitionServiceImplRuleTest {

    /**
     * Records what it was asked about, and optionally refuses the call — enough to prove the chokepoint calls the SPI
     * with the right facts, without standing up the EE module.
     */
    private static final class RecordingComponentRuleEnforcer implements ComponentRuleEnforcer {

        private final List<ActionCall> beforeCalls = new ArrayList<>();
        private final List<ActionCall> afterCalls = new ArrayList<>();
        private final List<Object> afterOutputs = new ArrayList<>();
        private final boolean blocking;

        private RecordingComponentRuleEnforcer(boolean blocking) {
            this.blocking = blocking;
        }

        @Override
        public @Nullable String checkBeforePerform(ActionCall actionCall) {
            beforeCalls.add(actionCall);

            if (blocking) {
                return "Action '%s' of component '%s' was blocked by an administrator rule."
                    .formatted(actionCall.actionName(), actionCall.componentName());
            }

            return null;
        }

        @Override
        public void recordAfterPerform(ActionCall actionCall, @Nullable Object output) {
            afterCalls.add(actionCall);
            afterOutputs.add(output);
        }
    }

    @Test
    void testExecutePerformIsBlockedByAFiringRule() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of("channel", "C05"), Map.of(), Map.of(),
                1L, false, PlatformType.AUTOMATION, null, null, null))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("blocked by an administrator rule");

        assertThat(componentRuleEnforcer.beforeCalls).hasSize(1);

        ComponentRuleEnforcer.ActionCall actionCall = componentRuleEnforcer.beforeCalls.getFirst();

        assertThat(actionCall.componentName()).isEqualTo("slack");
        assertThat(actionCall.actionName()).isEqualTo("sendMessage");
        assertThat(actionCall.inputParameters()).containsEntry("channel", "C05");
        assertThat(actionCall.jobId()).isEqualTo(1L);
        assertThat(actionCall.taskExecutionId()).isEqualTo(1L);
    }

    @Test
    void testBlockedActionNeverReachesTheRegistry() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(), Map.of(), Map.of(), 1L, false,
                PlatformType.AUTOMATION, null, null, null))
                    .isInstanceOf(ConfigurationException.class);

        // Resolving the action definition is the first thing doExecutePerform does after the guards, so an untouched
        // registry proves perform was never reached.
        org.mockito.Mockito.verifyNoInteractions(componentDefinitionRegistry);

        assertThat(componentRuleEnforcer.afterCalls).isEmpty();
    }

    @Test
    void testPolyglotPerformIsBlockedByAFiringRule() {
        RecordingComponentRuleEnforcer componentRuleEnforcer = new RecordingComponentRuleEnforcer(true);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            mock(ComponentDefinitionRegistry.class), mock(ContextFactory.class), List.of(),
            List.of(componentRuleEnforcer));

        assertThatThrownBy(
            () -> service.executePerformForPolyglot(
                "slack", 1, "sendMessage", Map.of(), null, Map.of(), Map.of(), null, mock(ActionContext.class)))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("blocked by an administrator rule");
    }

    @Test
    void testNoEnforcersMeansNoGuardAndNoBehaviourChange() {
        ComponentDefinitionRegistry componentDefinitionRegistry = mock(ComponentDefinitionRegistry.class);

        ActionDefinitionServiceImpl service = new ActionDefinitionServiceImpl(
            componentDefinitionRegistry, mock(ContextFactory.class), List.of(), List.of());

        // With no enforcers the call proceeds to the registry, which is a bare mock and so cannot supply a perform
        // function — the failure is the pre-existing one, not a rule rejection.
        assertThatThrownBy(
            () -> service.executePerform(
                "slack", 1, "sendMessage", 1L, 1L, 1L, 1L, "workflow1", Map.of(), Map.of(), Map.of(), 1L, false,
                PlatformType.AUTOMATION, null, null, null))
                    .isNotInstanceOf(ConfigurationException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :server:libs:platform:platform-component:platform-component-service:test --tests '*ActionDefinitionServiceImplRuleTest*' > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t3.log | head
```

Expected: FAIL — `package com.bytechef.platform.component.rule does not exist`.

- [ ] **Step 3: Write the SPI**

`server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/rule/ComponentRuleEnforcer.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.component.rule;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Extension point for administrative, conditional governance of a single action call. CE ships no implementation, so
 * every call is a no-op and behaviour is unchanged; EE ships a persistence-backed implementation that evaluates
 * admin-authored conditions against the call's actual input (and, after the fact, its output).
 *
 * <p>
 * This is the conditional sibling of {@link com.bytechef.platform.component.visibility.ComponentVisibilityProvider}:
 * visibility answers "may this action ever run", a rule answers "may <em>this</em> call run". They are separate
 * interfaces because visibility is also consulted on listing paths, where there is no call to inspect.
 * </p>
 *
 * @author Ivica Cardic
 */
public interface ComponentRuleEnforcer {

    /**
     * One action invocation, reduced to the facts a rule can be written against.
     *
     * @param connectionId the id of the first connection wired to this call, or {@code null} when the action takes
     *                     no connection. Note that {@code com.bytechef.platform.component.ComponentConnection} — the
     *                     record this chokepoint uses — carries a primitive {@code long connectionId}; the boxed,
     *                     nullable type here encodes "no connection", not "connection without an id".
     */
    record ActionCall(
        String componentName, String actionName, Map<String, ?> inputParameters, @Nullable Long connectionId,
        @Nullable Long jobId, @Nullable Long taskExecutionId) {
    }

    /**
     * Runs before the action's {@code perform}.
     *
     * @return {@code null} to allow the call, or a human-readable reason to refuse it. The caller turns a non-null
     *         reason into a {@code ConfigurationException} carrying
     *         {@code ActionDefinitionErrorType.RULE_BLOCKED}; the implementation does not throw, because the error
     *         type lives in the CE service module and an implementation should not have to depend on it. An
     *         implementation that only tags records the match and returns {@code null}.
     */
    @Nullable
    String checkBeforePerform(ActionCall actionCall);

    /**
     * Runs after {@code perform} returns, with its output. Must never throw: the action has already run and its side
     * effects already happened, so failing here would turn an observability feature into a spurious execution failure.
     */
    void recordAfterPerform(ActionCall actionCall, @Nullable Object output);
}
```

- [ ] **Step 4: Add the error type**

In `ActionDefinitionErrorType.java`, after the `ACTION_DISABLED` line:

```java
    public static final ActionDefinitionErrorType RULE_BLOCKED = new ActionDefinitionErrorType(108);
```

- [ ] **Step 5: Wire the chokepoint**

In `ActionDefinitionServiceImpl.java`:

Add the imports:

```java
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
```

Add the field beside the existing three, and extend the constructor:

```java
    private final ComponentDefinitionRegistry componentDefinitionRegistry;
    private final ContextFactory contextFactory;
    private final List<ComponentVisibilityProvider> componentVisibilityProviders;
    private final List<ComponentRuleEnforcer> componentRuleEnforcers;

    @SuppressFBWarnings("EI2")
    public ActionDefinitionServiceImpl(
        @Lazy ComponentDefinitionRegistry componentDefinitionRegistry, ContextFactory contextFactory,
        List<ComponentVisibilityProvider> componentVisibilityProviders,
        List<ComponentRuleEnforcer> componentRuleEnforcers) {

        this.componentDefinitionRegistry = componentDefinitionRegistry;
        this.contextFactory = contextFactory;
        this.componentVisibilityProviders = componentVisibilityProviders;
        this.componentRuleEnforcers = componentRuleEnforcers;
    }
```

In `doExecutePerform`, replace the two guard lines at the top of the method body:

```java
        checkComponentVisible(componentName);
        checkActionVisible(componentName, actionName);
```

with:

```java
        checkComponentVisible(componentName);
        checkActionVisible(componentName, actionName);

        ComponentRuleEnforcer.ActionCall actionCall = toActionCall(
            componentName, actionName, inputParameters, componentConnections, jobId, taskExecutionId);

        checkRulesBeforePerform(actionCall);
```

Then change the method to capture its result and run the after hook. `doExecutePerform` currently has several `return` statements; rather than touching each, rename the existing method body to `doExecutePerformInternal` and add a wrapper:

```java
    private Object doExecutePerform(
        String componentName, int componentVersion, String actionName, Long jobPrincipalId,
        Long jobPrincipalWorkflowId, Long jobId, @Nullable Long taskExecutionId, String workflowId,
        Map<String, ?> inputParameters, Map<String, ComponentConnection> componentConnections,
        Map<String, ?> extensions, @Nullable Long environmentId, boolean editorEnvironment, PlatformType type,
        @Nullable Map<String, ?> continueParameters, @Nullable Map<String, ?> resumeData,
        @Nullable Instant suspendExpiresAt) {

        ComponentRuleEnforcer.ActionCall actionCall = toActionCall(
            componentName, actionName, inputParameters, componentConnections, jobId, taskExecutionId);

        checkRulesBeforePerform(actionCall);

        Object result = doExecutePerformInternal(
            componentName, componentVersion, actionName, jobPrincipalId, jobPrincipalWorkflowId, jobId,
            taskExecutionId, workflowId, inputParameters, componentConnections, extensions, environmentId,
            editorEnvironment, type, continueParameters, resumeData, suspendExpiresAt);

        recordRulesAfterPerform(actionCall, result);

        return result;
    }
```

and rename the original `doExecutePerform` to `doExecutePerformInternal`, leaving its `checkComponentVisible`/`checkActionVisible` calls exactly where they are. The `ActionCall` is built once and shared by both hooks so the AFTER-phase context sees the same inputs the BEFORE phase judged.

In `executePerformForPolyglot`, after the two existing visibility guards:

```java
        checkComponentVisible(componentName);
        checkActionVisible(componentName, actionName);

        ComponentRuleEnforcer.ActionCall actionCall = new ComponentRuleEnforcer.ActionCall(
            componentName, actionName, inputParameters,
            componentConnection == null ? null : componentConnection.key(),
            componentConnection == null ? null : componentConnection.connectionId(), null, null);

        checkRulesBeforePerform(actionCall);
```

and wrap each of its three `return`/`throw` exits so the two returning branches run the after hook. Concretely, replace

```java
        if (basePerformFunction instanceof PerformFunction performFunction) {
            return executeSingleConnectionPerform(performFunction, inputParameters, componentConnection, context);
        }
```

with

```java
        if (basePerformFunction instanceof PerformFunction performFunction) {
            Object result = executeSingleConnectionPerform(
                performFunction, inputParameters, componentConnection, context);

            recordRulesAfterPerform(actionCall, result);

            return result;
        }
```

and apply the same shape to the `MultipleConnectionsPerformFunction` branch. The final `throw` needs no after hook — nothing ran.

Add the three private helpers next to `checkActionVisible`, before the `ConvertResult` record:

```java
    private static ComponentRuleEnforcer.ActionCall toActionCall(
        String componentName, String actionName, Map<String, ?> inputParameters,
        Map<String, ComponentConnection> componentConnections, @Nullable Long jobId,
        @Nullable Long taskExecutionId) {

        ComponentConnection componentConnection = getFirstComponentConnection(componentConnections);

        return new ComponentRuleEnforcer.ActionCall(
            componentName, actionName, inputParameters,
            componentConnection == null ? null : componentConnection.key(),
            componentConnection == null ? null : componentConnection.connectionId(), jobId, taskExecutionId);
    }

    private void checkRulesBeforePerform(ComponentRuleEnforcer.ActionCall actionCall) {
        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            String blockedReason = componentRuleEnforcer.checkBeforePerform(actionCall);

            if (blockedReason != null) {
                throw new ConfigurationException(blockedReason, ActionDefinitionErrorType.RULE_BLOCKED);
            }
        }
    }

    private void recordRulesAfterPerform(
        ComponentRuleEnforcer.ActionCall actionCall, @Nullable Object result) {

        for (ComponentRuleEnforcer componentRuleEnforcer : componentRuleEnforcers) {
            componentRuleEnforcer.recordAfterPerform(actionCall, result);
        }
    }
```

`checkRulesBeforePerform` throws on the first non-null reason — that is the block; `ConfigurationException` and `ActionDefinitionErrorType` are already imported in this file. `recordAfterPerform` is contractually forbidden from throwing (see the SPI javadoc), so this loop does not swallow; the EE implementation owns its own try/catch, which keeps a bug in the loop visible rather than silently absorbed.

- [ ] **Step 6: Update every `ActionDefinitionServiceImpl` construction site**

Find them:

```bash
grep -rn "new ActionDefinitionServiceImpl(" --include=*.java server client | grep -v "/build/"
```

Every hit needs a fourth argument. Production Spring wiring is by constructor injection and needs no edit, but hand-assembled test contexts and the five sibling tests in `platform-component-service/src/test/` (`ActionDefinitionServiceEnvironmentContextTest`, `ActionDefinitionServiceImplVisibilityTest`, `ActionDefinitionServicePolyglotPerformTest`, `ActionDefinitionServiceTest`, `ActionDefinitionServiceWebSocketPerformTest`) each construct it directly. Pass `List.of()` in all of them.

Also check for `@SpringBootTest(classes = ...)` contexts that assemble the impl by hand:

```bash
grep -rln "ActionDefinitionServiceImpl" --include=*.java server | grep -v "/build/" | grep "IntTestConfiguration\|TestConfiguration"
```

- [ ] **Step 7: Run the test to verify it passes**

Run:

```bash
./gradlew :server:libs:platform:platform-component:platform-component-service:test > /tmp/t3.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t3.log
```

Expected: `exit=0`. The whole module's test task runs, not just the new class, because Step 6 touched five sibling tests.

- [ ] **Step 8: Verify nothing else broke**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t3compile.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t3compile.log
```

Expected: `exit=0`, no FAILED lines. This is the step that catches a missed construction site in another module.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add server/libs/platform/platform-component && git commit -m "5xxx Add component rule enforcement seam to action execution"
```

---

## Task 4: The EE enforcer — evaluation, cache, audit

**Files:**
- Modify: `server/ee/libs/platform/platform-component-rule/platform-component-rule-service/build.gradle.kts`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/audit/ComponentRuleAuditEvent.java`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/audit/ComponentRuleAuditPublisher.java`
- Create: `.../platform-component-rule-service/src/main/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerImpl.java`
- Test: `.../platform-component-rule-service/src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`

**Interfaces:**
- Consumes: `ComponentRuleEnforcer` + `ComponentRuleEnforcer.ActionCall` (Task 3), `ComponentRuleService` (Task 1), `Evaluator`, `TenantContext.getCurrentTenantId()`. Deliberately **not** `ActionDefinitionErrorType` — that type lives in the CE `platform-component-service` module, and depending on it here would drag the whole CE service module into an EE platform module.
- Produces:
  - `ComponentRuleAuditEvent { RULE_BLOCKED(false), RULE_TAGGED(false) }` with `boolean isStrictAudit()`.
  - `ComponentRuleAuditPublisher` with `void publish(ComponentRuleAuditEvent componentRuleAuditEvent, ComponentRuleAuditPayload payload)` and `record ComponentRuleAuditPayload(long ruleId, String componentName, String actionName, String phase, @Nullable Long jobId, @Nullable Long taskExecutionId)`.
  - `ComponentRuleEnforcerImpl(ComponentRuleService componentRuleService, Evaluator evaluator, ComponentRuleAuditPublisher componentRuleAuditPublisher)`, plus a package-private overload taking a Caffeine `Ticker` for the TTL test.
  - `ComponentRuleEnforcerImpl.checkBeforePerform` returns the refusal message `"Action '<action>' of component '<component>' was blocked by an administrator rule."`, or `null`. It does not throw — see Task 3's Produces note.

- [ ] **Step 1: Add the dependencies the enforcer needs**

In `.../platform-component-rule-service/build.gradle.kts`, add to the existing `dependencies` block (keep the entries alphabetical within their group):

```kotlin
    implementation("org.springframework.boot:spring-boot-actuator")
```

`caffeine`, `evaluator-api`, `tenant-api` and `platform-component-api` are already there from Task 2.

- [ ] **Step 2: Write the failing enforcer test**

`.../src/test/java/com/bytechef/ee/platform/component/rule/ComponentRuleEnforcerTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditEvent;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditPublisher;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.ActionCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleEnforcerTest {

    private static final ActionCall SEND_MESSAGE_TO_C05 = new ActionCall(
        "slack", "sendMessage", Map.of("channel", "C05QG7RF30A", "text", "hello"), 3L, 42L, 7L);

    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final ComponentRuleAuditPublisher componentRuleAuditPublisher = mock(ComponentRuleAuditPublisher.class);
    private final Evaluator evaluator = SpelEvaluator.builder()
        .build();
    private final ComponentRuleEnforcerImpl componentRuleEnforcer =
        new ComponentRuleEnforcerImpl(componentRuleService, evaluator, componentRuleAuditPublisher);

    @Test
    void testNoRulesIsANoOp() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testMatchingBeforeBlockRuleReturnsARefusalReason() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK,
                    "inputParameters['channel'] == 'C05QG7RF30A'")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05))
            .contains("blocked by an administrator rule");

        verify(componentRuleAuditPublisher).publish(
            org.mockito.ArgumentMatchers.eq(ComponentRuleAuditEvent.RULE_BLOCKED), any());
    }

    @Test
    void testNonMatchingBeforeBlockRuleAllowsTheCall() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK,
                    "inputParameters['channel'] == 'C99NOMATCH'")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testMatchingBeforeTagRuleAllowsTheCallAndAudits() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    2L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG,
                    "contains(inputParameters['channel'], 'C05')")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();

        verify(componentRuleAuditPublisher).publish(
            org.mockito.ArgumentMatchers.eq(ComponentRuleAuditEvent.RULE_TAGGED), any());
    }

    @Test
    void testBlockWinsOverTagOnTheSameCall() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(2L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "true"),
                newComponentRule(1L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNotNull();

        verify(componentRuleAuditPublisher).publish(
            org.mockito.ArgumentMatchers.eq(ComponentRuleAuditEvent.RULE_BLOCKED), any());
        verify(componentRuleAuditPublisher, never()).publish(
            org.mockito.ArgumentMatchers.eq(ComponentRuleAuditEvent.RULE_TAGGED), any());
    }

    @Test
    void testNullActionNameRuleAppliesToEveryAction() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(3L, null, RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNotNull();
    }

    @Test
    void testRuleScopedToAnotherActionDoesNotFire() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(4L, "deleteMessage", RulePhase.BEFORE, RuleAction.BLOCK, "true")));

        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();
    }

    @Test
    void testAfterRuleSeesOutputInItsContext() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    5L, "sendMessage", RulePhase.AFTER, RuleAction.TAG, "output['ok'] == false")));

        componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of("ok", false));

        verify(componentRuleAuditPublisher).publish(
            org.mockito.ArgumentMatchers.eq(ComponentRuleAuditEvent.RULE_TAGGED), any());
    }

    @Test
    void testBeforeRuleIsNotEvaluatedInTheAfterPhase() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(6L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "true")));

        componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of());

        verify(componentRuleAuditPublisher, never()).publish(any(), any());
    }

    @Test
    void testAfterPhaseNeverThrows() {
        when(componentRuleService.getEnabledComponentRules("slack"))
            .thenThrow(new IllegalStateException("database is down"));

        assertThatCode(() -> componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void testUnresolvableConditionDoesNotBlock() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(
                newComponentRule(
                    7L, "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "missingRoot['whatever'] == 1")));

        // SpelEvaluator returns the original string for an unresolved reference rather than null or false, so the
        // result is not Boolean.TRUE and the rule does not fire. A BLOCK rule that cannot be evaluated fails open.
        assertThat(componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05)).isNull();
    }

    @Test
    void testRulesAreFetchedOncePerComponentAcrossBothPhases() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);
        componentRuleEnforcer.recordAfterPerform(SEND_MESSAGE_TO_C05, Map.of());
        componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        verify(componentRuleService, times(1)).getEnabledComponentRules("slack");
    }

    @Test
    void testAuditPayloadCarriesTheFiringRule() {
        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(
            List.of(newComponentRule(9L, "sendMessage", RulePhase.BEFORE, RuleAction.TAG, "true")));

        componentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        org.mockito.ArgumentCaptor<ComponentRuleAuditPublisher.ComponentRuleAuditPayload> payloadCaptor =
            org.mockito.ArgumentCaptor.forClass(ComponentRuleAuditPublisher.ComponentRuleAuditPayload.class);

        verify(componentRuleAuditPublisher).publish(
            org.mockito.ArgumentMatchers.eq(ComponentRuleAuditEvent.RULE_TAGGED), payloadCaptor.capture());

        ComponentRuleAuditPublisher.ComponentRuleAuditPayload payload = payloadCaptor.getValue();

        assertThat(payload.ruleId()).isEqualTo(9L);
        assertThat(payload.componentName()).isEqualTo("slack");
        assertThat(payload.actionName()).isEqualTo("sendMessage");
        assertThat(payload.phase()).isEqualTo("BEFORE");
        assertThat(payload.jobId()).isEqualTo(42L);
        assertThat(payload.taskExecutionId()).isEqualTo(7L);
    }

    @Test
    void testTheCacheRefetchesAfterItsTtlExpires() {
        FakeTicker fakeTicker = new FakeTicker();
        ComponentRuleEnforcerImpl expiringComponentRuleEnforcer = new ComponentRuleEnforcerImpl(
            componentRuleService, evaluator, componentRuleAuditPublisher, fakeTicker);

        when(componentRuleService.getEnabledComponentRules("slack")).thenReturn(List.of());

        expiringComponentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        fakeTicker.advanceSeconds(9);

        expiringComponentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        verify(componentRuleService, times(1)).getEnabledComponentRules("slack");

        fakeTicker.advanceSeconds(2);

        expiringComponentRuleEnforcer.checkBeforePerform(SEND_MESSAGE_TO_C05);

        verify(componentRuleService, times(2)).getEnabledComponentRules("slack");
    }

    /**
     * Caffeine reads elapsed time through a {@link com.github.benmanes.caffeine.cache.Ticker}, so a fake one makes
     * the 10-second TTL testable without a sleep.
     */
    private static final class FakeTicker implements com.github.benmanes.caffeine.cache.Ticker {

        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        private void advanceSeconds(long seconds) {
            nanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        }
    }

    private static ComponentRule newComponentRule(
        long id, String actionName, RulePhase rulePhase, RuleAction ruleAction, String condition) {

        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(id);
        componentRule.setComponentName("slack");
        componentRule.setActionName(actionName);
        componentRule.setPhase(rulePhase);
        componentRule.setRuleAction(ruleAction);
        componentRule.setCondition(condition);
        componentRule.setEnabled(true);

        return componentRule;
    }
}
```

`testRulesAreFetchedOncePerComponentAcrossBothPhases` relies on `TenantContext.getCurrentTenantId()` returning a stable value with no tenant bound. If it throws instead, wrap the three calls in the tenant helper the repo already uses in unit tests — find it with `grep -rn "TenantContext.setCurrentTenantId" --include=*.java server | grep /src/test/ | head -3` — and set a fixed tenant id in a `@BeforeEach`.

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t4.log | head
```

Expected: FAIL — `cannot find symbol: class ComponentRuleEnforcerImpl`.

- [ ] **Step 4: Write the audit event enum**

`.../audit/ComponentRuleAuditEvent.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.audit;

/**
 * Audit event types emitted through {@link ComponentRuleAuditPublisher}.
 *
 * <p>
 * The payload key contract is documented per constant below. Both events carry {@code ruleId},
 * {@code componentName}, {@code actionName}, {@code phase} and, when the call came from a workflow execution,
 * {@code jobId} and {@code taskExecutionId}. The contract is convention-enforced rather than type-checked, so a change
 * must be applied at every emitter.
 * </p>
 *
 * <p>
 * Both are {@code strictAudit = false}, a deliberate departure from {@code ConnectionAuditEvent}'s strict events.
 * Those are low-frequency admin actions where a lost trail is a compliance problem. These fire on live workflow
 * executions, potentially once per action call, and making an audit-capture failure fail the underlying job would turn
 * an observability feature into an availability risk.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public enum ComponentRuleAuditEvent {

    /**
     * A {@code BEFORE}-phase {@code BLOCK} rule matched and the action call was refused. Published synchronously in
     * the same guard that throws — there is no commit to defer to, because the call is refused rather than committed.
     */
    RULE_BLOCKED(false),

    /**
     * A {@code TAG} rule matched. Emitted for both {@code BEFORE}-phase and {@code AFTER}-phase matches; the
     * {@code phase} payload field distinguishes them. Never emitted for a call that a {@code BLOCK} rule refused —
     * that call never happened, so there is nothing to review.
     */
    RULE_TAGGED(false);

    private final boolean strictAudit;

    ComponentRuleAuditEvent(boolean strictAudit) {
        this.strictAudit = strictAudit;
    }

    /**
     * Always {@code false} for this enum — see the class javadoc. The accessor exists so these events read the same
     * way as {@code ConnectionAuditEvent} to anything that inspects audit metadata generically.
     */
    public boolean isStrictAudit() {
        return strictAudit;
    }
}
```

- [ ] **Step 5: Write the audit publisher**

`.../audit/ComponentRuleAuditPublisher.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.audit;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.util.SecurityUtils;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes audit events for component-rule matches. Failures must NOT propagate: this runs on the action-execution
 * hot path, and a lost audit row is preferable to a failed workflow. If the security context cannot be resolved the
 * principal falls back to {@code "SYSTEM"}, which is the common case here — most rule matches happen on a worker
 * thread with no authenticated user.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ComponentRuleAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(ComponentRuleAuditPublisher.class);

    /**
     * The typed payload every emitter declares, so a misspelled key cannot be dropped silently.
     */
    public record ComponentRuleAuditPayload(
        long ruleId, String componentName, String actionName, String phase, @Nullable Long jobId,
        @Nullable Long taskExecutionId) {

        Map<String, Object> toMap() {
            Map<String, Object> data = new HashMap<>();

            data.put("ruleId", String.valueOf(ruleId));
            data.put("componentName", componentName);
            data.put("actionName", actionName);
            data.put("phase", phase);

            if (jobId != null) {
                data.put("jobId", String.valueOf(jobId));
            }

            if (taskExecutionId != null) {
                data.put("taskExecutionId", String.valueOf(taskExecutionId));
            }

            return data;
        }
    }

    private final ApplicationEventPublisher applicationEventPublisher;

    public ComponentRuleAuditPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void publish(ComponentRuleAuditEvent componentRuleAuditEvent, ComponentRuleAuditPayload payload) {
        try {
            String principal = SecurityUtils.fetchCurrentUserLogin()
                .orElse("SYSTEM");

            AuditEvent auditEvent = new AuditEvent(principal, componentRuleAuditEvent.name(), payload.toMap());

            applicationEventPublisher.publishEvent(new AuditApplicationEvent(auditEvent));
        } catch (RuntimeException exception) {
            log.warn(
                "Could not publish audit event {} for component rule id={}", componentRuleAuditEvent,
                payload.ruleId(), exception);
        }
    }
}
```

- [ ] **Step 6: Write the enforcer**

`.../ComponentRuleEnforcerImpl.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditEvent;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditPublisher;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditPublisher.ComponentRuleAuditPayload;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EE implementation of {@link ComponentRuleEnforcer} backed by the {@code component_rule} table.
 *
 * <p>
 * The rule list is cached per tenant AND per component for a short window, so an action execution on a component with
 * no rules costs one cache hit rather than a query. The tenant is part of the key because component names are global:
 * keying on the component name alone would serve one tenant's rules to another.
 * </p>
 *
 * <p>
 * A condition is a ByteChef formula body, so it is evaluated as {@code "=" + condition} through the platform
 * {@link Evaluator} in lenient mode. Lenient matters: an unresolved reference makes the evaluator return the original
 * string rather than throw, and only {@code Boolean.TRUE} counts as a match — so a rule whose condition cannot be
 * resolved against this particular call simply does not fire. A {@code BLOCK} rule therefore fails open. That is the
 * deliberate trade: a mis-authored rule must not be able to take a tenant's whole workflow estate offline, and the
 * save-time parse check in {@code ComponentRuleServiceImpl} already rejects conditions that are outright invalid.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ComponentRuleEnforcerImpl implements ComponentRuleEnforcer {

    private static final String CONDITION_KEY = "condition";
    private static final String FORMULA_PREFIX = "=";

    private static final Logger log = LoggerFactory.getLogger(ComponentRuleEnforcerImpl.class);

    private record TenantComponentKey(String tenantId, String componentName) {
    }

    private final Cache<TenantComponentKey, List<ComponentRule>> componentRulesCache;
    private final ComponentRuleAuditPublisher componentRuleAuditPublisher;
    private final ComponentRuleService componentRuleService;
    private final Evaluator evaluator;

    public ComponentRuleEnforcerImpl(
        ComponentRuleService componentRuleService, Evaluator evaluator,
        ComponentRuleAuditPublisher componentRuleAuditPublisher) {

        this(componentRuleService, evaluator, componentRuleAuditPublisher, Ticker.systemTicker());
    }

    /**
     * Package-private, for the TTL test: Caffeine reads elapsed time through a {@link Ticker}, so injecting a fake one
     * is the only way to assert the expiry without a sleep.
     */
    ComponentRuleEnforcerImpl(
        ComponentRuleService componentRuleService, Evaluator evaluator,
        ComponentRuleAuditPublisher componentRuleAuditPublisher, Ticker ticker) {

        this.componentRuleService = componentRuleService;
        this.evaluator = evaluator;
        this.componentRuleAuditPublisher = componentRuleAuditPublisher;
        this.componentRulesCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10))
            .ticker(ticker)
            .build();
    }

    @Override
    public @Nullable String checkBeforePerform(ActionCall actionCall) {
        List<ComponentRule> matchingComponentRules = getMatchingComponentRules(
            actionCall, RulePhase.BEFORE, buildEvaluationContext(actionCall, null));

        if (matchingComponentRules.isEmpty()) {
            return null;
        }

        for (ComponentRule componentRule : matchingComponentRules) {
            if (componentRule.getRuleAction() == RuleAction.BLOCK) {
                publish(ComponentRuleAuditEvent.RULE_BLOCKED, componentRule, actionCall, RulePhase.BEFORE);

                return "Action '%s' of component '%s' was blocked by an administrator rule."
                    .formatted(actionCall.actionName(), actionCall.componentName());
            }
        }

        // Only reached when nothing blocked — block wins over tag, and a refused call has nothing to review.
        for (ComponentRule componentRule : matchingComponentRules) {
            publish(ComponentRuleAuditEvent.RULE_TAGGED, componentRule, actionCall, RulePhase.BEFORE);
        }

        return null;
    }

    @Override
    public void recordAfterPerform(ActionCall actionCall, @Nullable Object output) {
        // The action has already run; nothing here may fail the execution.
        try {
            List<ComponentRule> matchingComponentRules = getMatchingComponentRules(
                actionCall, RulePhase.AFTER, buildEvaluationContext(actionCall, output));

            for (ComponentRule componentRule : matchingComponentRules) {
                publish(ComponentRuleAuditEvent.RULE_TAGGED, componentRule, actionCall, RulePhase.AFTER);
            }
        } catch (RuntimeException exception) {
            log.warn(
                "Could not evaluate AFTER-phase rules for {}#{}", actionCall.componentName(),
                actionCall.actionName(), exception);
        }
    }

    private List<ComponentRule> getMatchingComponentRules(
        ActionCall actionCall, RulePhase rulePhase, Map<String, Object> evaluationContext) {

        List<ComponentRule> componentRules = getCachedComponentRules(actionCall.componentName());

        if (componentRules.isEmpty()) {
            return List.of();
        }

        List<ComponentRule> matchingComponentRules = new ArrayList<>();

        for (ComponentRule componentRule : componentRules) {
            if (componentRule.getPhase() != rulePhase || !componentRule.appliesToAction(actionCall.actionName())) {
                continue;
            }

            if (matches(componentRule, evaluationContext)) {
                matchingComponentRules.add(componentRule);
            }
        }

        return matchingComponentRules;
    }

    private List<ComponentRule> getCachedComponentRules(String componentName) {
        TenantComponentKey tenantComponentKey = new TenantComponentKey(
            TenantContext.getCurrentTenantId(), componentName);

        return componentRulesCache.get(
            tenantComponentKey, key -> componentRuleService.getEnabledComponentRules(key.componentName()));
    }

    private boolean matches(ComponentRule componentRule, Map<String, Object> evaluationContext) {
        try {
            Map<String, ?> evaluated = evaluator.evaluate(
                Map.of(CONDITION_KEY, FORMULA_PREFIX + componentRule.getCondition()), evaluationContext, true);

            return Boolean.TRUE.equals(evaluated.get(CONDITION_KEY));
        } catch (RuntimeException exception) {
            log.warn(
                "Could not evaluate the condition of component rule id={}; treating it as not matching",
                componentRule.getId(), exception);

            return false;
        }
    }

    private static Map<String, Object> buildEvaluationContext(ActionCall actionCall, @Nullable Object output) {
        Map<String, Object> evaluationContext = new HashMap<>();

        evaluationContext.put("inputParameters", actionCall.inputParameters());
        evaluationContext.put("componentName", actionCall.componentName());
        evaluationContext.put("actionName", actionCall.actionName());
        evaluationContext.put("connectionId", actionCall.connectionId());

        if (output != null) {
            evaluationContext.put("output", output);
        }

        return evaluationContext;
    }

    private void publish(
        ComponentRuleAuditEvent componentRuleAuditEvent, ComponentRule componentRule, ActionCall actionCall,
        RulePhase rulePhase) {

        componentRuleAuditPublisher.publish(
            componentRuleAuditEvent,
            new ComponentRuleAuditPayload(
                componentRule.getId(), actionCall.componentName(), actionCall.actionName(), rulePhase.name(),
                actionCall.jobId(), actionCall.taskExecutionId()));
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-service:test > /tmp/t4.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t4.log
```

Expected: `exit=0` — 13 enforcer tests plus the 8 service tests from Task 2.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add server/ee/libs/platform/platform-component-rule && git commit -m "5xxx Enforce component rules on action execution with tag and block audit events"
```

---

## Task 5: GraphQL surface

**Files:**
- Create: `server/ee/libs/platform/platform-component-rule/platform-component-rule-graphql/build.gradle.kts`
- Create: `.../platform-component-rule-graphql/src/main/resources/graphql/component-rule.graphqls`
- Create: `.../platform-component-rule-graphql/src/main/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlController.java`
- Modify: `server/apps/server-app/build.gradle.kts`
- Modify: `client/codegen.ts`
- Create: `client/src/graphql/platform/component-rule/componentRules.graphql`
- Create: `client/src/graphql/platform/component-rule/saveComponentRule.graphql`
- Create: `client/src/graphql/platform/component-rule/deleteComponentRule.graphql`
- Test: `.../platform-component-rule-graphql/src/test/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlControllerTest.java`

**Interfaces:**
- Consumes: `ComponentRuleService`, `ComponentRule` (Task 1); `ComponentDefinitionService` (`com.bytechef.platform.component.service.ComponentDefinitionService`) for the component title/icon on each row; `ActionDefinitionService` for the action list the dialog's action picker needs.
- Produces:
  - `ComponentRuleGraphQlController(ComponentDefinitionService componentDefinitionService, ComponentRuleService componentRuleService)`.
  - `record ComponentRuleItem(String id, String componentName, @Nullable String componentTitle, @Nullable String componentIcon, @Nullable String actionName, ComponentRule.RulePhase phase, ComponentRule.RuleAction ruleAction, String condition, @Nullable String description, boolean enabled)`.
  - Query `componentRules(componentName: String): [ComponentRule!]!` — a **null** `componentName` returns every rule in the tenant, which is what the flat Rules list renders.
  - Mutations `saveComponentRule(...)` and `deleteComponentRule(id: ID!): Boolean!`.
  - Generated client hooks (after codegen): `useComponentRulesQuery`, `useSaveComponentRuleMutation`, `useDeleteComponentRuleMutation`, and types `ComponentRulesQuery`, `ComponentRulePhase`, `ComponentRuleActionType`.

> The query takes an **optional** `componentName`, which is a widening of the spec's `componentName: String!`. The spec's §6 UI is "a flat list of configured rules across all components"; a required argument would force the client to fan out one query per component to render it.

- [ ] **Step 1: Create the graphql module's `build.gradle.kts`**

```kotlin
dependencies {
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.graphql:spring-graphql")
    implementation("org.springframework.security:spring-security-core")
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-api"))
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-service"))
    implementation(project(":server:libs:platform:platform-api"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}
```

- [ ] **Step 2: Write the failing controller test**

`.../src/test/java/com/bytechef/ee/platform/component/rule/web/graphql/ComponentRuleGraphQlControllerTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.ee.platform.component.rule.web.graphql.ComponentRuleGraphQlController.ComponentRuleItem;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleGraphQlControllerTest {

    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final ComponentRuleGraphQlController controller =
        new ComponentRuleGraphQlController(componentDefinitionService, componentRuleService);

    @Test
    void testComponentRulesWithoutAComponentNameListsEveryRule() {
        when(componentRuleService.getComponentRules()).thenReturn(List.of(newComponentRule(1L)));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        List<ComponentRuleItem> result = controller.componentRules(null);

        assertThat(result).hasSize(1);

        ComponentRuleItem componentRuleItem = result.getFirst();

        assertThat(componentRuleItem.id()).isEqualTo("1");
        assertThat(componentRuleItem.componentName()).isEqualTo("slack");
        assertThat(componentRuleItem.componentTitle()).isEqualTo("Slack");
        assertThat(componentRuleItem.actionName()).isEqualTo("sendMessage");
        assertThat(componentRuleItem.phase()).isEqualTo(RulePhase.BEFORE);
        assertThat(componentRuleItem.ruleAction()).isEqualTo(RuleAction.TAG);
        assertThat(componentRuleItem.enabled()).isTrue();
    }

    @Test
    void testComponentRulesWithAComponentNameNarrows() {
        when(componentRuleService.getComponentRules("slack")).thenReturn(List.of(newComponentRule(1L)));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        assertThat(controller.componentRules("slack")).hasSize(1);

        verify(componentRuleService).getComponentRules("slack");
    }

    @Test
    void testSaveComponentRuleWithoutAnIdCreates() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        controller.saveComponentRule(
            null, "slack", "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "inputParameters['x'] == 1",
            "block when x is one", true);

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getId()).isNull();
        assertThat(componentRule.getComponentName()).isEqualTo("slack");
        assertThat(componentRule.getCondition()).isEqualTo("inputParameters['x'] == 1");
        assertThat(componentRule.getDescription()).isEqualTo("block when x is one");
    }

    @Test
    void testSaveComponentRuleWithAnIdUpdatesInPlace() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(slackComponentDefinition()));

        controller.saveComponentRule(
            "5", "slack", null, RulePhase.AFTER, RuleAction.TAG, "output['ok'] == false", null, false);

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getId()).isEqualTo(5L);
        assertThat(componentRule.getActionName()).isNull();
        assertThat(componentRule.getPhase()).isEqualTo(RulePhase.AFTER);
        assertThat(componentRule.isEnabled()).isFalse();
    }

    @Test
    void testDeleteComponentRuleDelegates() {
        assertThat(controller.deleteComponentRule("5")).isTrue();

        verify(componentRuleService).deleteComponentRule(5L);
    }

    private static ComponentDefinition slackComponentDefinition() {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn("slack");
        when(componentDefinition.getTitle()).thenReturn("Slack");
        when(componentDefinition.getIcon()).thenReturn("slack.svg");
        when(componentDefinition.getVersion()).thenReturn(1);

        return componentDefinition;
    }

    private static ComponentRule newComponentRule(long id) {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(id);
        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
        componentRule.setPhase(RulePhase.BEFORE);
        componentRule.setRuleAction(RuleAction.TAG);
        componentRule.setCondition("true");
        componentRule.setEnabled(true);

        return componentRule;
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:test > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t5.log | head
```

Expected: FAIL — `cannot find symbol: class ComponentRuleGraphQlController`.

- [ ] **Step 4: Write the schema**

`.../src/main/resources/graphql/component-rule.graphqls`:

```graphql
enum ComponentRulePhase {
    BEFORE
    AFTER
}

enum ComponentRuleActionType {
    BLOCK
    TAG
}

type ComponentRule {
    id: ID!
    componentName: String!
    componentTitle: String
    componentIcon: String
    actionName: String
    phase: ComponentRulePhase!
    ruleAction: ComponentRuleActionType!
    condition: String!
    description: String
    enabled: Boolean!
}

extend type Query {
    """
    Lists configured component rules. Omit componentName to list every rule in the tenant, which is what the flat
    Rules list renders. Admin-only.
    """
    componentRules(componentName: String): [ComponentRule!]!
}

extend type Mutation {
    """
    Creates a rule when id is absent, updates that rule when it is present. Rejects a BLOCK rule in the AFTER phase,
    and a condition that is not a valid ByteChef formula expression, as typed errors. Admin-only.
    """
    saveComponentRule(
        id: ID
        componentName: String!
        actionName: String
        phase: ComponentRulePhase!
        ruleAction: ComponentRuleActionType!
        condition: String!
        description: String
        enabled: Boolean!
    ): ComponentRule!

    """
    Deletes a rule. Admin-only.
    """
    deleteComponentRule(id: ID!): Boolean!
}
```

The two enum names are prefixed `ComponentRule*` because GraphQL has a single flat type namespace and `ComponentOperationType` already lives there from `component-policy.graphqls`.

- [ ] **Step 5: Write the controller**

`.../web/graphql/ComponentRuleGraphQlController.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.web.graphql;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL surface for tenant-wide component rules. Admin-only.
 *
 * <p>
 * Each row is decorated with the component's title and icon so the flat Rules list can render a recognisable row
 * without a second round trip per component.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
public class ComponentRuleGraphQlController {

    private final ComponentDefinitionService componentDefinitionService;
    private final ComponentRuleService componentRuleService;

    @SuppressFBWarnings("EI2")
    public ComponentRuleGraphQlController(
        ComponentDefinitionService componentDefinitionService, ComponentRuleService componentRuleService) {

        this.componentDefinitionService = componentDefinitionService;
        this.componentRuleService = componentRuleService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<ComponentRuleItem> componentRules(@Argument @Nullable String componentName) {
        List<ComponentRule> componentRules = componentName == null
            ? componentRuleService.getComponentRules()
            : componentRuleService.getComponentRules(componentName);

        if (componentRules.isEmpty()) {
            return List.of();
        }

        Map<String, ComponentDefinition> componentDefinitionsByName = getComponentDefinitionsByName();

        return componentRules.stream()
            .map(componentRule -> toItem(componentRule, componentDefinitionsByName))
            .sorted(
                Comparator.comparing(ComponentRuleItem::sortKey, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ComponentRuleItem::id))
            .toList();
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRuleItem saveComponentRule(
        @Argument @Nullable String id, @Argument String componentName, @Argument @Nullable String actionName,
        @Argument RulePhase phase, @Argument RuleAction ruleAction, @Argument String condition,
        @Argument @Nullable String description, @Argument boolean enabled) {

        ComponentRule componentRule = new ComponentRule();

        if (id != null && !id.isBlank()) {
            componentRule.setId(Long.valueOf(id));
        }

        componentRule.setComponentName(componentName);
        componentRule.setActionName(actionName);
        componentRule.setPhase(phase);
        componentRule.setRuleAction(ruleAction);
        componentRule.setCondition(condition);
        componentRule.setDescription(description);
        componentRule.setEnabled(enabled);

        ComponentRule savedComponentRule = componentRuleService.saveComponentRule(componentRule);

        return toItem(savedComponentRule, getComponentDefinitionsByName());
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public boolean deleteComponentRule(@Argument String id) {
        componentRuleService.deleteComponentRule(Long.parseLong(id));

        return true;
    }

    private Map<String, ComponentDefinition> getComponentDefinitionsByName() {
        // The registry can hold several definitions per name (multiple versions; a component and a same-named cluster
        // element), so collapse to one per name the way ComponentPolicyGraphQlController does.
        return componentDefinitionService.getComponentDefinitions()
            .stream()
            .collect(
                Collectors.toMap(
                    ComponentDefinition::getName, Function.identity(),
                    ComponentRuleGraphQlController::preferHighestVersion));
    }

    private static ComponentDefinition preferHighestVersion(
        ComponentDefinition firstComponentDefinition, ComponentDefinition secondComponentDefinition) {

        return firstComponentDefinition.getVersion() >= secondComponentDefinition.getVersion()
            ? firstComponentDefinition
            : secondComponentDefinition;
    }

    private static ComponentRuleItem toItem(
        ComponentRule componentRule, Map<String, ComponentDefinition> componentDefinitionsByName) {

        ComponentDefinition componentDefinition = componentDefinitionsByName.get(componentRule.getComponentName());

        return new ComponentRuleItem(
            String.valueOf(componentRule.getId()), componentRule.getComponentName(),
            componentDefinition == null ? null : componentDefinition.getTitle(),
            componentDefinition == null ? null : componentDefinition.getIcon(), componentRule.getActionName(),
            componentRule.getPhase(), componentRule.getRuleAction(), componentRule.getCondition(),
            componentRule.getDescription(), componentRule.isEnabled());
    }

    public record ComponentRuleItem(
        String id, String componentName, @Nullable String componentTitle, @Nullable String componentIcon,
        @Nullable String actionName, RulePhase phase, RuleAction ruleAction, String condition,
        @Nullable String description, boolean enabled) {

        String sortKey() {
            return componentTitle == null ? componentName : componentTitle;
        }
    }
}
```

A component whose definition is no longer in the registry (uninstalled custom component) yields a null title and icon rather than dropping the row — an orphaned rule must stay visible so an admin can delete it.

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql:test > /tmp/t5.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t5.log
```

Expected: `exit=0`.

- [ ] **Step 7: Wire the module into server-app**

In `server/apps/server-app/build.gradle.kts`, beside the `-service` line added in Task 2:

```kotlin
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-graphql"))
```

- [ ] **Step 8: Add the schema to codegen and write the client operations**

In `client/codegen.ts`, after the `platform-component-policy` glob (currently line 94):

```ts
        '../server/ee/libs/platform/platform-component-rule/platform-component-rule-graphql/src/main/resources/graphql/*.graphqls',
```

`client/src/graphql/platform/component-rule/componentRules.graphql`:

```graphql
query ComponentRules($componentName: String) {
    componentRules(componentName: $componentName) {
        id
        componentName
        componentTitle
        componentIcon
        actionName
        phase
        ruleAction
        condition
        description
        enabled
    }
}
```

`client/src/graphql/platform/component-rule/saveComponentRule.graphql`:

```graphql
mutation SaveComponentRule(
    $id: ID
    $componentName: String!
    $actionName: String
    $phase: ComponentRulePhase!
    $ruleAction: ComponentRuleActionType!
    $condition: String!
    $description: String
    $enabled: Boolean!
) {
    saveComponentRule(
        id: $id
        componentName: $componentName
        actionName: $actionName
        phase: $phase
        ruleAction: $ruleAction
        condition: $condition
        description: $description
        enabled: $enabled
    ) {
        id
        componentName
        componentTitle
        componentIcon
        actionName
        phase
        ruleAction
        condition
        description
        enabled
    }
}
```

`client/src/graphql/platform/component-rule/deleteComponentRule.graphql`:

```graphql
mutation DeleteComponentRule($id: ID!) {
    deleteComponentRule(id: $id)
}
```

- [ ] **Step 9: Regenerate the client GraphQL types**

```bash
cd client && npx graphql-codegen
```

Expected: `client/src/shared/middleware/graphql.ts` gains `useComponentRulesQuery`, `useSaveComponentRuleMutation`, `useDeleteComponentRuleMutation`, `ComponentRulePhase`, `ComponentRuleActionType`.

- [ ] **Step 10: Type-check the client**

```bash
cd client && npm run typecheck
```

Expected: exit 0. (Set the tool timeout to 600000 ms.)

- [ ] **Step 11: Format and commit — two commits, server and client separately**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add server/ee/libs/platform/platform-component-rule server/apps/server-app/build.gradle.kts && git commit -m "5xxx Add component rule GraphQL surface"
```

```bash
git add client/codegen.ts client/src/graphql/platform/component-rule client/src/shared/middleware/graphql.ts && git commit -m "5xxx client - Generate component rule GraphQL operations"
```

Committing the operations and the generated file separately from the server change matches the repo's GraphQL workflow.

---

## Task 6: Client — the Rules tab and its list

**Files:**
- Create: `client/src/ee/pages/settings/platform/component-rules/ComponentRulesTab.tsx`
- Create: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.tsx`
- Modify: `client/src/ee/pages/settings/platform/components/Components.tsx`
- Modify: `client/src/routes.tsx`
- Test: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.test.tsx`

**Interfaces:**
- Consumes: `useComponentRulesQuery`, `useSaveComponentRuleMutation`, `useDeleteComponentRuleMutation`, `ComponentRulesQuery`, `ComponentRulePhase`, `ComponentRuleActionType` from `@/shared/middleware/graphql` (Task 5).
- Produces:
  - `ComponentRuleItemType = ComponentRulesQuery['componentRules'][number]` — exported from `ComponentRuleList.tsx` and reused by the dialog in Task 7.
  - `ComponentRuleListProps { componentRules: ComponentRuleItemType[]; onEdit: (componentRule: ComponentRuleItemType) => void; }`.
  - `ComponentsTabType` gains the `'rules'` member.

- [ ] **Step 1: Write the failing list test**

`client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.test.tsx`:

```tsx
import ComponentRuleList from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleList';
import {ComponentRuleActionType, ComponentRulePhase} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {deleteMutateMock, saveMutateMock} = vi.hoisted(() => ({
    deleteMutateMock: vi.fn(),
    saveMutateMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useDeleteComponentRuleMutation: () => ({mutate: deleteMutateMock}),
        useSaveComponentRuleMutation: () => ({mutate: saveMutateMock}),
    };
});

const blockRule = {
    actionName: 'deleteRecord',
    componentIcon: null,
    componentName: 'salesforce',
    componentTitle: 'Salesforce',
    condition: "inputParameters['ownerId'] != null",
    description: 'Block deleting an owned record',
    enabled: true,
    id: '1',
    phase: ComponentRulePhase.Before,
    ruleAction: ComponentRuleActionType.Block,
};

const tagRule = {
    actionName: null,
    componentIcon: null,
    componentName: 'slack',
    componentTitle: 'Slack',
    condition: "contains(inputParameters['channel'], 'C05')",
    description: null,
    enabled: false,
    id: '2',
    phase: ComponentRulePhase.After,
    ruleAction: ComponentRuleActionType.Tag,
};

const renderList = (onEdit = vi.fn()) =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <ComponentRuleList componentRules={[blockRule, tagRule]} onEdit={onEdit} />
        </QueryClientProvider>
    );

describe('ComponentRuleList', () => {
    beforeEach(() => {
        deleteMutateMock.mockReset();
        saveMutateMock.mockReset();
    });

    it('renders the action name for a scoped rule and a placeholder for an all-actions rule', () => {
        renderList();

        expect(screen.getByText('deleteRecord')).toBeInTheDocument();
        expect(screen.getByText('All actions')).toBeInTheDocument();
    });

    it('renders the phase and enforcement badges', () => {
        renderList();

        expect(screen.getByText('Before')).toBeInTheDocument();
        expect(screen.getByText('After')).toBeInTheDocument();
        expect(screen.getByText('Block')).toBeInTheDocument();
        expect(screen.getByText('Tag')).toBeInTheDocument();
    });

    it('reflects the stored enabled flag on each switch', () => {
        renderList();

        expect(screen.getByRole('switch', {name: 'Salesforce deleteRecord'})).toBeChecked();
        expect(screen.getByRole('switch', {name: 'Slack all actions'})).not.toBeChecked();
    });

    it('saves the whole rule with the flipped enabled flag when a switch is toggled', async () => {
        renderList();

        await userEvent.click(screen.getByRole('switch', {name: 'Salesforce deleteRecord'}));

        expect(saveMutateMock).toHaveBeenCalledWith({
            actionName: 'deleteRecord',
            componentName: 'salesforce',
            condition: "inputParameters['ownerId'] != null",
            description: 'Block deleting an owned record',
            enabled: false,
            id: '1',
            phase: ComponentRulePhase.Before,
            ruleAction: ComponentRuleActionType.Block,
        });
    });

    it('calls onEdit with the clicked rule', async () => {
        const onEdit = vi.fn();

        renderList(onEdit);

        await userEvent.click(screen.getByRole('button', {name: 'Edit Salesforce deleteRecord rule'}));

        expect(onEdit).toHaveBeenCalledWith(blockRule);
    });

    it('deletes by id', async () => {
        renderList();

        await userEvent.click(screen.getByRole('button', {name: 'Delete Salesforce deleteRecord rule'}));

        expect(deleteMutateMock).toHaveBeenCalledWith({id: '1'});
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd client && npx vitest run src/ee/pages/settings/platform/component-rules
```

Expected: FAIL — cannot resolve `ComponentRuleList`.

- [ ] **Step 3: Write `ComponentRuleList`**

`client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.tsx`:

```tsx
import LazyLoadSVG from '@/components/LazyLoadSVG/LazyLoadSVG';
import Switch from '@/components/Switch/Switch';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {
    type ComponentRulesQuery,
    useDeleteComponentRuleMutation,
    useSaveComponentRuleMutation,
} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {PencilIcon, Trash2Icon} from 'lucide-react';

export type ComponentRuleItemType = ComponentRulesQuery['componentRules'][number];

interface ComponentRuleListProps {
    componentRules: ComponentRuleItemType[];
    onEdit: (componentRule: ComponentRuleItemType) => void;
}

const COMPONENT_RULES_QUERY_KEY = ['ComponentRules', {componentName: undefined}];

const ComponentRuleList = ({componentRules, onEdit}: ComponentRuleListProps) => {
    const queryClient = useQueryClient();

    const deleteComponentRuleMutation = useDeleteComponentRuleMutation({
        onSettled: () => {
            queryClient.invalidateQueries({queryKey: ['ComponentRules']});
        },
    });

    const saveComponentRuleMutation = useSaveComponentRuleMutation<
        unknown,
        {previous?: ComponentRulesQuery}
    >({
        onError: (_error, _variables, context) => {
            if (context?.previous) {
                queryClient.setQueryData(COMPONENT_RULES_QUERY_KEY, context.previous);
            }
        },
        onMutate: async ({enabled, id}) => {
            await queryClient.cancelQueries({queryKey: COMPONENT_RULES_QUERY_KEY});

            const previous = queryClient.getQueryData<ComponentRulesQuery>(COMPONENT_RULES_QUERY_KEY);

            queryClient.setQueryData<ComponentRulesQuery>(COMPONENT_RULES_QUERY_KEY, (current) =>
                current
                    ? {
                          componentRules: current.componentRules.map((componentRule) =>
                              componentRule.id === id ? {...componentRule, enabled} : componentRule
                          ),
                      }
                    : current
            );

            return {previous};
        },
        onSettled: () => {
            queryClient.invalidateQueries({queryKey: ['ComponentRules']});
        },
    });

    return (
        <ul className="divide-y rounded-md border">
            {componentRules.map((componentRule) => {
                const componentLabel = componentRule.componentTitle ?? componentRule.componentName;
                const actionLabel = componentRule.actionName ?? 'all actions';

                return (
                    <li className="flex items-center justify-between gap-3 px-4 py-3" key={componentRule.id}>
                        <div className="flex min-w-0 items-center gap-3">
                            {componentRule.componentIcon ? (
                                <LazyLoadSVG className="size-6 flex-none" src={componentRule.componentIcon} />
                            ) : (
                                <span className="size-6 flex-none rounded bg-muted" />
                            )}

                            <div className="flex min-w-0 flex-col">
                                <span className="text-sm font-semibold">
                                    {componentLabel}

                                    <span className="ml-2 font-normal text-muted-foreground">
                                        {componentRule.actionName ?? 'All actions'}
                                    </span>
                                </span>

                                <Tooltip>
                                    <TooltipTrigger asChild>
                                        <span className="truncate font-mono text-xs text-muted-foreground">
                                            {componentRule.condition}
                                        </span>
                                    </TooltipTrigger>

                                    <TooltipContent className="max-w-md break-all font-mono">
                                        {componentRule.condition}
                                    </TooltipContent>
                                </Tooltip>
                            </div>
                        </div>

                        <div className="flex flex-none items-center gap-2">
                            <Badge variant="secondary">
                                {componentRule.phase === 'BEFORE' ? 'Before' : 'After'}
                            </Badge>

                            <Badge variant={componentRule.ruleAction === 'BLOCK' ? 'destructive' : 'outline'}>
                                {componentRule.ruleAction === 'BLOCK' ? 'Block' : 'Tag'}
                            </Badge>

                            <Switch
                                aria-label={`${componentLabel} ${actionLabel}`}
                                checked={componentRule.enabled}
                                onCheckedChange={(checked) =>
                                    saveComponentRuleMutation.mutate({
                                        actionName: componentRule.actionName,
                                        componentName: componentRule.componentName,
                                        condition: componentRule.condition,
                                        description: componentRule.description,
                                        enabled: checked,
                                        id: componentRule.id,
                                        phase: componentRule.phase,
                                        ruleAction: componentRule.ruleAction,
                                    })
                                }
                            />

                            <Button
                                aria-label={`Edit ${componentLabel} ${actionLabel} rule`}
                                onClick={() => onEdit(componentRule)}
                                size="icon"
                                variant="ghost"
                            >
                                <PencilIcon className="size-4" />
                            </Button>

                            <Button
                                aria-label={`Delete ${componentLabel} ${actionLabel} rule`}
                                onClick={() => deleteComponentRuleMutation.mutate({id: componentRule.id})}
                                size="icon"
                                variant="ghost"
                            >
                                <Trash2Icon className="size-4" />
                            </Button>
                        </div>
                    </li>
                );
            })}
        </ul>
    );
};

export default ComponentRuleList;
```

The toggle sends the whole rule, not a partial patch, because `saveComponentRule` is an upsert by id — a partial would null out `condition`, which the schema requires.

If `Badge`'s available `variant` values differ, check them with `grep -n "variant" client/src/components/ui/badge.tsx` and pick the closest destructive/outline pair.

- [ ] **Step 4: Write `ComponentRulesTab`**

`client/src/ee/pages/settings/platform/component-rules/ComponentRulesTab.tsx`:

```tsx
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import {Button} from '@/components/ui/button';
import ComponentRuleDialog from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog';
import ComponentRuleList, {
    type ComponentRuleItemType,
} from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleList';
import {useComponentRulesQuery} from '@/shared/middleware/graphql';
import {ShieldCheckIcon} from 'lucide-react';
import {useState} from 'react';

/**
 * Tenant-wide conditional governance of individual action calls. A rule names a component (and optionally one of its
 * actions), a phase, an enforcement action, and a condition evaluated against the call's real input.
 */
const ComponentRulesTab = () => {
    const [dialogOpen, setDialogOpen] = useState(false);
    const [editedComponentRule, setEditedComponentRule] = useState<ComponentRuleItemType | undefined>(undefined);

    const {data, error, isLoading} = useComponentRulesQuery({componentName: undefined});

    const componentRules = data?.componentRules ?? [];

    const handleEdit = (componentRule: ComponentRuleItemType) => {
        setEditedComponentRule(componentRule);
        setDialogOpen(true);
    };

    const handleAdd = () => {
        setEditedComponentRule(undefined);
        setDialogOpen(true);
    };

    return (
        <PageLoader errors={[error]} loading={isLoading}>
            <div className="mt-4 flex flex-col gap-4">
                {componentRules.length > 0 ? (
                    <>
                        <div className="flex justify-end">
                            <Button onClick={handleAdd}>Add Rule</Button>
                        </div>

                        <ComponentRuleList componentRules={componentRules} onEdit={handleEdit} />
                    </>
                ) : (
                    <div className="flex flex-1 items-center justify-center py-12">
                        <EmptyList
                            button={<Button onClick={handleAdd}>Add Rule</Button>}
                            icon={<ShieldCheckIcon className="size-12 text-content-neutral-tertiary" />}
                            message="Rules conditionally block or tag individual action calls based on their input."
                            title="No Component Rules"
                        />
                    </div>
                )}
            </div>

            <ComponentRuleDialog
                componentRule={editedComponentRule}
                onOpenChange={setDialogOpen}
                open={dialogOpen}
            />
        </PageLoader>
    );
};

export default ComponentRulesTab;
```

`ComponentRuleDialog` does not exist yet — Task 7 creates it. Write this file now and expect the type error until Task 7 lands; the two are one reviewable unit only if committed together, so **do not commit until Task 7's Step 6**.

- [ ] **Step 5: Add the tab and the route**

In `client/src/ee/pages/settings/platform/components/Components.tsx`:

Extend the tab union:

```tsx
export type ComponentsTabType = 'api-connectors' | 'component-visibility' | 'custom' | 'policies' | 'rules';
```

Add the import (keeping imports alphabetical):

```tsx
import ComponentRulesTab from '@/ee/pages/settings/platform/component-rules/ComponentRulesTab';
```

Add the trigger after the Component Visibility trigger, inside the `showTabs` block:

```tsx
                        <TabsTrigger value="rules">Rules</TabsTrigger>
```

Add the content after the `component-visibility` `TabsContent`:

```tsx
                <TabsContent value="rules">
                    <div className="w-full px-6 3xl:mx-auto 3xl:w-4/5">
                        <ComponentRulesTab />
                    </div>
                </TabsContent>
```

In `client/src/routes.tsx`, after the `component-visibility` route object:

```tsx
                {
                    element: (
                        <PrivateRoute hasAnyAuthorities={[AUTHORITIES.ADMIN, AUTHORITIES.USER]}>
                            <EEVersion>
                                <LazyLoadWrapper>
                                    <Components tab="rules" />
                                </LazyLoadWrapper>
                            </EEVersion>
                        </PrivateRoute>
                    ),
                    path: 'rules',
                },
```

The route allows `USER` as well as `ADMIN` to match its siblings, but every query and mutation behind it is `@PreAuthorize(ADMIN)` — a non-admin sees the tab and an error toast, exactly as they do on Component Visibility today.

- [ ] **Step 6: Run the list test to verify it passes**

```bash
cd client && npx vitest run src/ee/pages/settings/platform/component-rules/components/ComponentRuleList.test.tsx
```

Expected: 6 passing.

- [ ] **Step 7: Commit is deferred to Task 7**

`ComponentRulesTab` imports a dialog that does not exist yet. Continue straight into Task 7.

---

## Task 7: Client — the Add/Edit Rule dialog

**Files:**
- Create: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.tsx`
- Test: `client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.test.tsx`

**Interfaces:**
- Consumes: `ComponentRuleItemType` (Task 6); `useComponentPoliciesQuery` (existing, the component picker's source — it already returns `{name, title, icon, version}` for every registry component); `useGetComponentDefinitionQuery` from `@/shared/queries/platform/componentDefinitions.queries` (existing, whose result carries `actions: {name, title}[]`); `useSaveComponentRuleMutation`, `ComponentRulePhase`, `ComponentRuleActionType` (Task 5).
- Produces: `ComponentRuleDialogProps { componentRule?: ComponentRuleItemType; onOpenChange: (open: boolean) => void; open: boolean; }` — the shape `ComponentRulesTab` already calls it with.

- [ ] **Step 1: Write the failing dialog test**

`client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.test.tsx`:

```tsx
import ComponentRuleDialog from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog';
import {ComponentRuleActionType, ComponentRulePhase} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {saveMutateMock} = vi.hoisted(() => ({
    saveMutateMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useComponentPoliciesQuery: () => ({
            data: {
                componentPolicies: [
                    {description: null, enabled: true, icon: null, name: 'slack', title: 'Slack', version: 1},
                ],
            },
        }),
        useSaveComponentRuleMutation: () => ({isPending: false, mutate: saveMutateMock}),
    };
});

vi.mock('@/shared/queries/platform/componentDefinitions.queries', () => ({
    ComponentDefinitionKeys: {componentDefinition: () => ['componentDefinition']},
    useGetComponentDefinitionQuery: () => ({
        data: {actions: [{name: 'sendMessage', title: 'Send Message'}], name: 'slack'},
    }),
}));

const existingRule = {
    actionName: 'sendMessage',
    componentIcon: null,
    componentName: 'slack',
    componentTitle: 'Slack',
    condition: "contains(inputParameters['channel'], 'C05')",
    description: 'Flag messages to the incident channel',
    enabled: true,
    id: '3',
    phase: ComponentRulePhase.Before,
    ruleAction: ComponentRuleActionType.Tag,
};

const renderDialog = (componentRule?: typeof existingRule) =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <ComponentRuleDialog componentRule={componentRule} onOpenChange={vi.fn()} open={true} />
        </QueryClientProvider>
    );

describe('ComponentRuleDialog', () => {
    beforeEach(() => {
        saveMutateMock.mockReset();
    });

    it('disables the Block option when the After phase is selected', async () => {
        renderDialog();

        expect(screen.getByRole('radio', {name: 'Block'})).toBeEnabled();

        await userEvent.click(screen.getByRole('radio', {name: 'After'}));

        expect(screen.getByRole('radio', {name: 'Block'})).toBeDisabled();
    });

    it('falls back to Tag when After is selected while Block was chosen', async () => {
        renderDialog();

        await userEvent.click(screen.getByRole('radio', {name: 'Block'}));
        await userEvent.click(screen.getByRole('radio', {name: 'After'}));

        expect(screen.getByRole('radio', {name: 'Tag'})).toBeChecked();
    });

    it('prefills every field when editing an existing rule', () => {
        renderDialog(existingRule);

        expect(screen.getByLabelText('Condition')).toHaveValue("contains(inputParameters['channel'], 'C05')");
        expect(screen.getByLabelText('Description')).toHaveValue('Flag messages to the incident channel');
        expect(screen.getByRole('radio', {name: 'Before'})).toBeChecked();
        expect(screen.getByRole('radio', {name: 'Tag'})).toBeChecked();
    });

    it('submits the id when editing so the save updates in place', async () => {
        renderDialog(existingRule);

        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(saveMutateMock).toHaveBeenCalledWith(
            expect.objectContaining({
                actionName: 'sendMessage',
                componentName: 'slack',
                enabled: true,
                id: '3',
                phase: ComponentRulePhase.Before,
                ruleAction: ComponentRuleActionType.Tag,
            }),
            expect.anything()
        );
    });

    it('submits without an id when adding', async () => {
        renderDialog();

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Slack'}));
        await userEvent.type(screen.getByLabelText('Condition'), 'true');
        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(saveMutateMock).toHaveBeenCalledWith(
            expect.objectContaining({actionName: null, componentName: 'slack', id: undefined}),
            expect.anything()
        );
    });

    it('keeps Save disabled until a component and a condition are supplied', async () => {
        renderDialog();

        expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled();

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Slack'}));

        expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled();

        await userEvent.type(screen.getByLabelText('Condition'), 'true');

        expect(screen.getByRole('button', {name: 'Save'})).toBeEnabled();
    });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd client && npx vitest run src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.test.tsx
```

Expected: FAIL — cannot resolve `ComponentRuleDialog`.

- [ ] **Step 3: Write the dialog**

`client/src/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog.tsx`:

```tsx
import {Button} from '@/components/ui/button';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {Input} from '@/components/ui/input';
import {Label} from '@/components/ui/label';
import {RadioGroup, RadioGroupItem} from '@/components/ui/radio-group';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select';
import {Textarea} from '@/components/ui/textarea';
import {type ComponentRuleItemType} from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleList';
import useComponentRuleCopilot from '@/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot';
import {
    ComponentRuleActionType,
    ComponentRulePhase,
    useComponentPoliciesQuery,
    useSaveComponentRuleMutation,
} from '@/shared/middleware/graphql';
import {useGetComponentDefinitionQuery} from '@/shared/queries/platform/componentDefinitions.queries';
import {useQueryClient} from '@tanstack/react-query';
import {SparklesIcon} from 'lucide-react';
import {useEffect, useMemo, useState} from 'react';

const ALL_ACTIONS_VALUE = '__all_actions__';

interface ComponentRuleDialogProps {
    componentRule?: ComponentRuleItemType;
    onOpenChange: (open: boolean) => void;
    open: boolean;
}

const ComponentRuleDialog = ({componentRule, onOpenChange, open}: ComponentRuleDialogProps) => {
    const [actionName, setActionName] = useState<string>(ALL_ACTIONS_VALUE);
    const [componentName, setComponentName] = useState<string>('');
    const [condition, setCondition] = useState<string>('');
    const [description, setDescription] = useState<string>('');
    const [phase, setPhase] = useState<ComponentRulePhase>(ComponentRulePhase.Before);
    const [ruleAction, setRuleAction] = useState<ComponentRuleActionType>(ComponentRuleActionType.Block);

    const queryClient = useQueryClient();

    const {data: componentPoliciesData} = useComponentPoliciesQuery();

    const selectedComponentPolicy = useMemo(
        () =>
            (componentPoliciesData?.componentPolicies ?? []).find(
                (componentPolicy) => componentPolicy.name === componentName
            ),
        [componentPoliciesData?.componentPolicies, componentName]
    );

    const {data: componentDefinition} = useGetComponentDefinitionQuery(
        {componentName, componentVersion: selectedComponentPolicy?.version ?? 1},
        !!componentName
    );

    const actionNames = useMemo(
        () => (componentDefinition?.actions ?? []).map((action) => action.name),
        [componentDefinition?.actions]
    );

    const saveComponentRuleMutation = useSaveComponentRuleMutation();

    const {openCopilot} = useComponentRuleCopilot({
        actionName: actionName === ALL_ACTIONS_VALUE ? undefined : actionName,
        componentName,
        currentCondition: condition,
        onConditionGenerated: setCondition,
    });

    const saveDisabled = !componentName || !condition.trim();

    const handleSave = () => {
        saveComponentRuleMutation.mutate(
            {
                actionName: actionName === ALL_ACTIONS_VALUE ? null : actionName,
                componentName,
                condition,
                description: description || null,
                enabled: componentRule ? componentRule.enabled : true,
                id: componentRule?.id,
                phase,
                ruleAction,
            },
            {
                onSuccess: () => {
                    queryClient.invalidateQueries({queryKey: ['ComponentRules']});

                    onOpenChange(false);
                },
            }
        );
    };

    // Reset the form whenever the dialog opens, so an Add after an Edit does not inherit the edited rule's values.
    useEffect(() => {
        if (!open) {
            return;
        }

        setActionName(componentRule?.actionName ?? ALL_ACTIONS_VALUE);
        setComponentName(componentRule?.componentName ?? '');
        setCondition(componentRule?.condition ?? '');
        setDescription(componentRule?.description ?? '');
        setPhase(componentRule?.phase ?? ComponentRulePhase.Before);
        setRuleAction(componentRule?.ruleAction ?? ComponentRuleActionType.Block);
    }, [componentRule, open]);

    // A block only means anything before the action runs; the server refuses BLOCK + AFTER outright, so the form
    // moves the selection to TAG rather than letting the admin submit a combination that cannot be saved.
    useEffect(() => {
        if (phase === ComponentRulePhase.After && ruleAction === ComponentRuleActionType.Block) {
            setRuleAction(ComponentRuleActionType.Tag);
        }
    }, [phase, ruleAction]);

    return (
        <Dialog onOpenChange={onOpenChange} open={open}>
            <DialogContent className="max-w-xl">
                <DialogHeader>
                    <DialogTitle>{componentRule ? 'Edit Rule' : 'Add Rule'}</DialogTitle>

                    <DialogDescription>
                        Conditionally block or tag a single action call based on the values it was invoked with.
                    </DialogDescription>
                </DialogHeader>

                <fieldset className="flex flex-col gap-4 border-0">
                    <div className="flex flex-col gap-2">
                        <Label htmlFor="component-rule-component">Component</Label>

                        <Select onValueChange={setComponentName} value={componentName}>
                            <SelectTrigger aria-label="Component" id="component-rule-component">
                                <SelectValue placeholder="Select a component" />
                            </SelectTrigger>

                            <SelectContent>
                                {(componentPoliciesData?.componentPolicies ?? []).map((componentPolicy) => (
                                    <SelectItem key={componentPolicy.name} value={componentPolicy.name}>
                                        {componentPolicy.title ?? componentPolicy.name}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="component-rule-action">Action</Label>

                        <Select disabled={!componentName} onValueChange={setActionName} value={actionName}>
                            <SelectTrigger aria-label="Action" id="component-rule-action">
                                <SelectValue />
                            </SelectTrigger>

                            <SelectContent>
                                <SelectItem value={ALL_ACTIONS_VALUE}>All actions</SelectItem>

                                {actionNames.map((name) => (
                                    <SelectItem key={name} value={name}>
                                        {name}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="flex gap-8">
                        <div className="flex flex-col gap-2">
                            <Label>Phase</Label>

                            <RadioGroup
                                className="flex gap-4"
                                onValueChange={(value) => setPhase(value as ComponentRulePhase)}
                                value={phase}
                            >
                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Before"
                                        id="component-rule-phase-before"
                                        value={ComponentRulePhase.Before}
                                    />

                                    <Label htmlFor="component-rule-phase-before">Before</Label>
                                </div>

                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="After"
                                        id="component-rule-phase-after"
                                        value={ComponentRulePhase.After}
                                    />

                                    <Label htmlFor="component-rule-phase-after">After</Label>
                                </div>
                            </RadioGroup>
                        </div>

                        <div className="flex flex-col gap-2">
                            <Label>Enforcement</Label>

                            <RadioGroup
                                className="flex gap-4"
                                onValueChange={(value) => setRuleAction(value as ComponentRuleActionType)}
                                value={ruleAction}
                            >
                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Block"
                                        disabled={phase === ComponentRulePhase.After}
                                        id="component-rule-action-block"
                                        value={ComponentRuleActionType.Block}
                                    />

                                    <Label htmlFor="component-rule-action-block">Block</Label>
                                </div>

                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Tag"
                                        id="component-rule-action-tag"
                                        value={ComponentRuleActionType.Tag}
                                    />

                                    <Label htmlFor="component-rule-action-tag">Tag</Label>
                                </div>
                            </RadioGroup>
                        </div>
                    </div>

                    {phase === ComponentRulePhase.After && (
                        <p className="text-xs text-muted-foreground">
                            An After rule can only tag — the action has already run, so a block would not undo its
                            side effects.
                        </p>
                    )}

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="component-rule-description">Description</Label>

                        <Input
                            id="component-rule-description"
                            onChange={(event) => setDescription(event.target.value)}
                            placeholder="Block deleting a record that still has an owner"
                            value={description}
                        />
                    </div>

                    <div className="flex flex-col gap-2">
                        <div className="flex items-center justify-between">
                            <Label htmlFor="component-rule-condition">Condition</Label>

                            <Button
                                disabled={!componentName}
                                onClick={openCopilot}
                                size="sm"
                                type="button"
                                variant="ghost"
                            >
                                <SparklesIcon className="mr-1 size-4" />
                                Generate with AI
                            </Button>
                        </div>

                        <Textarea
                            className="font-mono text-xs"
                            id="component-rule-condition"
                            onChange={(event) => setCondition(event.target.value)}
                            placeholder="inputParameters['ownerId'] != null"
                            rows={4}
                            value={condition}
                        />

                        <p className="text-xs text-muted-foreground">
                            A ByteChef formula expression over <code>inputParameters</code>
                            {phase === ComponentRulePhase.After ? (
                                <>
                                    {' '}
                                    and <code>output</code>
                                </>
                            ) : null}
                            . Use the built-in functions (<code>contains</code>, <code>equalsIgnoreCase</code>,{' '}
                            <code>size</code>) rather than Java method calls.
                        </p>
                    </div>
                </fieldset>

                <DialogFooter>
                    <Button onClick={() => onOpenChange(false)} variant="outline">
                        Cancel
                    </Button>

                    <Button disabled={saveDisabled} onClick={handleSave}>
                        Save
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default ComponentRuleDialog;
```

`useComponentRuleCopilot` does not exist yet — Task 10 creates it. Until then, stub it so this task's tests can run: create `client/src/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot.ts` containing

```ts
interface UseComponentRuleCopilotParamsI {
    actionName?: string;
    componentName: string;
    currentCondition: string;
    onConditionGenerated: (condition: string) => void;
}

interface UseComponentRuleCopilotResultI {
    openCopilot: () => void;
}

const useComponentRuleCopilot = (
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    params: UseComponentRuleCopilotParamsI
): UseComponentRuleCopilotResultI => ({openCopilot: () => {}});

export default useComponentRuleCopilot;
```

Task 10 replaces the body; the signature stays.

Check the second argument of `useGetComponentDefinitionQuery` before wiring it — some generated query hooks take `(variables, options)` and some take `(variables, enabled)`:

```bash
grep -n "export const useGetComponentDefinitionQuery" -A 12 client/src/shared/queries/platform/componentDefinitions.queries.ts
```

Adjust the call to match.

- [ ] **Step 4: Run the dialog test to verify it passes**

```bash
cd client && npx vitest run src/ee/pages/settings/platform/component-rules
```

Expected: 12 passing (6 list + 6 dialog).

- [ ] **Step 5: Run the full client check**

```bash
cd client && npm run check
```

Expected: exit 0. Set the tool timeout to 600000 ms. Common failures here are `sort-keys` (not auto-fixable — reorder the object literal by hand) and `bytechef/sort-import-destructures`.

- [ ] **Step 6: Commit Tasks 6 and 7 together**

```bash
cd client && npm run format
```

```bash
git add client/src/ee/pages/settings/platform/component-rules client/src/ee/pages/settings/platform/components/Components.tsx client/src/routes.tsx && git commit -m "5xxx client - Add Component Rules tab with add and edit dialog"
```

---

## Task 8: AI tool callbacks

Four tools, in the shape the codebase settled on after ticket 732: reads that ground the model, one render tool that hands a proposal back to the UI without saving, and exactly one write.

The spec says the write tool "internally calls the same generation step the panel button uses, so AI Hub chat and panel button produce identical output through one code path". This plan achieves that guarantee differently and more simply: **there is no generation step inside any tool.** The agent's LLM composes the condition, grounded by `describeComponentActionParameters`; `proposeComponentRuleCondition` validates and returns it; `createComponentRule` persists a condition it is given. Both surfaces therefore run literally the same three tools against the same prompt, which is a stronger form of the same guarantee than two call sites sharing one helper.

**Files:**
- Modify: `server/ee/libs/automation/automation-ai/automation-ai-tool/build.gradle.kts`
- Create: `.../automation-ai-tool/src/main/java/com/bytechef/ee/automation/ai/tool/componentrule/ListComponentRulesToolCallback.java`
- Create: `.../componentrule/DescribeComponentActionParametersToolCallback.java`
- Create: `.../componentrule/ProposeComponentRuleConditionToolCallback.java`
- Create: `.../componentrule/CreateComponentRuleToolCallback.java`
- Create: `.../componentrule/ComponentRuleToolCallbacksFactory.java`
- Test: `.../automation-ai-tool/src/test/java/com/bytechef/ee/automation/ai/tool/componentrule/ComponentRuleToolCallbacksFactoryTest.java`

**Interfaces:**
- Consumes: `ComponentRuleService`, `ComponentRule`, `ComponentRule.RulePhase`, `ComponentRule.RuleAction` (Task 1); `ActionDefinitionService.getActionDefinition(String, int, String)` and its `ActionDefinition.getProperties()`; `ComponentDefinitionService.getComponentDefinitions()`; `Evaluator`.
- Produces:
  - `ComponentRuleToolCallbacksFactory(ComponentRuleService componentRuleService, ComponentDefinitionService componentDefinitionService, ActionDefinitionService actionDefinitionService, Evaluator evaluator)` with `List<ToolCallback> readToolCallbacks()` and `List<ToolCallback> writeToolCallbacks()`.
  - Tool names, which Task 9's AI Hub registration and Task 10's client handler both key off, verbatim: `listComponentRules`, `describeComponentActionParameters`, `proposeComponentRuleCondition`, `createComponentRule`.
  - `ProposeComponentRuleConditionToolCallback` returns the JSON object `{"condition": "...", "valid": true, "explanation": "..."}` on success and `{"condition": "...", "valid": false, "error": "..."}` when the condition does not parse. **The client's tool-result handler in Task 10 reads the `condition` key of this exact shape.**
  - `readToolCallbacks()` returns `listComponentRules`, `describeComponentActionParameters`, `proposeComponentRuleCondition`. `writeToolCallbacks()` returns those three plus `createComponentRule`.

- [ ] **Step 1: Add the module dependency**

In `.../automation-ai-tool/build.gradle.kts`, add to the EE project block:

```kotlin
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-api"))
    implementation(project(":server:libs:core:evaluator:evaluator-api"))
```

`platform-component-api` (which owns `ActionDefinitionService` and `ComponentDefinitionService`) is already declared. Add `testImplementation("org.assertj:assertj-core")`, `testImplementation("org.junit.jupiter:junit-jupiter")`, `testImplementation("org.mockito:mockito-core")` and `testImplementation(project(":server:libs:core:evaluator:evaluator-impl"))` if the module does not already carry them.

- [ ] **Step 2: Write the failing factory test**

`.../src/test/java/com/bytechef/ee/automation/ai/tool/componentrule/ComponentRuleToolCallbacksFactoryTest.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.componentrule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.platform.component.service.ActionDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleToolCallbacksFactoryTest {

    private final ActionDefinitionService actionDefinitionService = mock(ActionDefinitionService.class);
    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final Evaluator evaluator = SpelEvaluator.builder()
        .build();
    private final ComponentRuleToolCallbacksFactory factory = new ComponentRuleToolCallbacksFactory(
        componentRuleService, componentDefinitionService, actionDefinitionService, evaluator);

    @Test
    void testReadToolCallbacksAreGroundingOnly() {
        assertThat(toolNames(factory.readToolCallbacks()))
            .containsExactlyInAnyOrder(
                "listComponentRules", "describeComponentActionParameters", "proposeComponentRuleCondition");
    }

    @Test
    void testWriteToolCallbacksAddExactlyOneMutation() {
        assertThat(toolNames(factory.writeToolCallbacks()))
            .containsExactlyInAnyOrder(
                "listComponentRules", "describeComponentActionParameters", "proposeComponentRuleCondition",
                "createComponentRule");
    }

    @Test
    void testProposeReturnsTheConditionWhenItParses() {
        String result = callTool(
            factory.readToolCallbacks(), "proposeComponentRuleCondition",
            """
                {"condition": "contains(inputParameters['channel'], 'C05')", \
                "explanation": "flags the incident channel"}""");

        assertThat(result).contains("\"valid\":true");
        assertThat(result).contains("C05");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testProposeReportsAnInvalidConditionWithoutThrowing() {
        String result = callTool(
            factory.readToolCallbacks(), "proposeComponentRuleCondition",
            """
                {"condition": "inputParameters['channel'].startsWith('C05')", "explanation": "bad"}""");

        assertThat(result).contains("\"valid\":false");
    }

    @Test
    void testCreateComponentRulePersists() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> {
            ComponentRule componentRule = invocation.getArgument(0);

            componentRule.setId(11L);

            return componentRule;
        });

        String result = callTool(
            factory.writeToolCallbacks(), "createComponentRule",
            """
                {"componentName": "slack", "actionName": "sendMessage", "phase": "BEFORE", \
                "ruleAction": "TAG", "condition": "true", "description": "always"}""");

        assertThat(result).contains("11");

        verify(componentRuleService).saveComponentRule(any());
    }

    @Test
    void testCreateComponentRuleRejectsBlockInAfterPhaseBeforeReachingTheService() {
        String result = callTool(
            factory.writeToolCallbacks(), "createComponentRule",
            """
                {"componentName": "slack", "phase": "AFTER", "ruleAction": "BLOCK", "condition": "true"}""");

        assertThat(result).containsIgnoringCase("after");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testListComponentRulesRendersTheStoredRules() {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(1L);
        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
        componentRule.setPhase(RulePhase.BEFORE);
        componentRule.setRuleAction(RuleAction.BLOCK);
        componentRule.setCondition("true");
        componentRule.setEnabled(true);

        when(componentRuleService.getComponentRules()).thenReturn(List.of(componentRule));

        String result = callTool(factory.readToolCallbacks(), "listComponentRules", "{}");

        assertThat(result).contains("slack");
        assertThat(result).contains("sendMessage");
        assertThat(result).contains("BLOCK");
    }

    private static String callTool(List<ToolCallback> toolCallbacks, String toolName, String toolInput) {
        ToolCallback toolCallback = toolCallbacks.stream()
            .filter(candidate -> {
                ToolDefinition toolDefinition = candidate.getToolDefinition();

                return toolName.equals(toolDefinition.name());
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tool named " + toolName));

        return toolCallback.call(toolInput);
    }

    private static List<String> toolNames(List<ToolCallback> toolCallbacks) {
        return toolCallbacks.stream()
            .map(toolCallback -> {
                ToolDefinition toolDefinition = toolCallback.getToolDefinition();

                return toolDefinition.name();
            })
            .toList();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test > /tmp/t8.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED|error:" /tmp/t8.log | head
```

Expected: FAIL — `cannot find symbol: class ComponentRuleToolCallbacksFactory`.

- [ ] **Step 4: Write `ProposeComponentRuleConditionToolCallback`** (the load-bearing one — write it first)

`.../componentrule/ProposeComponentRuleConditionToolCallback.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.componentrule;

import com.bytechef.evaluator.Evaluator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hands a composed condition back for human review. It saves nothing: the Rules dialog drops the returned
 * {@code condition} into its editor, and AI Hub chat shows it so the admin can copy or confirm it. Persisting is
 * {@link CreateComponentRuleToolCallback}'s job alone.
 *
 * <p>
 * The condition is parse-checked here, using the same {@link Evaluator} and the same {@code =} formula prefix the
 * enforcer and the save-time validator use. That closes the loop for the model: a condition written with a Java method
 * call — {@code inputParameters['channel'].startsWith(...)}, the shape an LLM reaches for by default — comes back
 * {@code valid: false} with the reason, in the same turn, instead of being rejected minutes later at Save.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class ProposeComponentRuleConditionToolCallback implements ToolCallback {

    static final String TOOL_NAME = "proposeComponentRuleCondition";

    private static final String CONDITION_KEY = "condition";
    private static final String FORMULA_PREFIX = "=";

    private static final String DESCRIPTION = """
        Propose a ByteChef formula condition for a component rule and return it for the admin to review. \
        Saves nothing. Supply condition (the formula BODY, with no leading '=') and a one-sentence \
        explanation of what it matches. The condition is parse-checked and comes back with valid: true, or \
        valid: false plus the parse error, in which case rewrite it and call this tool again. Conditions may \
        reference inputParameters, componentName, actionName, connectionId, and — in the \
        AFTER phase only — output. They may NOT call Java methods (no .startsWith(...), no T(...), no new); \
        use ByteChef's own functions instead: contains, equalsIgnoreCase, indexOf, length, size, split, \
        substring, join.""";

    private static final String INPUT_SCHEMA = """
        {
            "type": "object",
            "properties": {
                "condition": {
                    "type": "string",
                    "description": "The formula body, without a leading '='"
                },
                "explanation": {
                    "type": "string",
                    "description": "One sentence describing what this condition matches"
                }
            },
            "required": ["condition"]
        }""";

    private final Evaluator evaluator;
    private final JsonMapper jsonMapper = new JsonMapper();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ProposeComponentRuleConditionToolCallback(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        ProposeInput input;

        try {
            input = jsonMapper.readValue(toolInput, ProposeInput.class);
        } catch (RuntimeException exception) {
            return jsonMapper.writeValueAsString(
                Map.of("valid", false, "error", "Could not read the tool input: " + exception.getMessage()));
        }

        String condition = input.condition();

        if (condition == null || condition.isBlank()) {
            return jsonMapper.writeValueAsString(Map.of("valid", false, "error", "condition is required"));
        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put(CONDITION_KEY, condition);

        try {
            evaluator.evaluate(Map.of(CONDITION_KEY, FORMULA_PREFIX + condition), Map.of(), false);

            result.put("valid", true);
            result.put("explanation", input.explanation() == null ? "" : input.explanation());
        } catch (RuntimeException exception) {
            result.put("valid", false);
            result.put(
                "error",
                "The condition is not a valid ByteChef formula expression: " + exception.getMessage()
                    + ". Rewrite it using ByteChef functions (contains, equalsIgnoreCase, size) rather than Java "
                    + "method calls.");
        }

        return jsonMapper.writeValueAsString(result);
    }

    private record ProposeInput(@Nullable String condition, @Nullable String explanation) {
    }
}
```

- [ ] **Step 5: Write the two grounding read tools**

`.../componentrule/ListComponentRulesToolCallback.java` — takes an optional `componentName`, calls `componentRuleService.getComponentRules()` (or the narrowed overload), and returns a JSON array of `{id, componentName, actionName, phase, ruleAction, condition, description, enabled}`. Description:

```
List the component rules configured for this tenant. Optionally narrow to one component with componentName.
Returns each rule's id, component, action (null means every action of that component), phase, enforcement
action, condition and enabled flag. Read-only.
```

Input schema:

```json
{
    "type": "object",
    "properties": {
        "componentName": {"type": "string", "description": "Optional — narrow to one component"}
    },
    "required": []
}
```

`.../componentrule/DescribeComponentActionParametersToolCallback.java` — this is what satisfies the spec's prompt contract ("takes the target action's parameter schema — names/types from the component definition, not just the description"). It takes `componentName` and `actionName`, resolves the component's highest version through `componentDefinitionService.getComponentDefinitions()` (collapsing by name, preferring the highest `getVersion()` — the same reduction `ComponentRuleGraphQlController` does), calls `actionDefinitionService.getActionDefinition(componentName, componentVersion, actionName)`, and returns a JSON array of `{name, type, required, description}` from `actionDefinition.getProperties()`. Description:

```
Describe the input parameters of one component action: each parameter's name, type, whether it is required,
and its description. Call this BEFORE proposing a condition, so the condition references parameter names that
actually exist. Read-only.
```

Check the accessor names on `com.bytechef.platform.component.domain.Property` before writing the mapping:

```bash
grep -n "public .* get\|public boolean" server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/domain/Property.java
```

Both classes follow `CreateApiCollectionToolCallback`'s shape exactly: `static final String TOOL_NAME`, a `DESCRIPTION` and `INPUT_SCHEMA` constant, `getToolDefinition()`, `call(String)` delegating to `call(String, ToolContext)`, a private input record, and a `toolError(String)` helper that returns `ToolErrors`-formatted JSON. Copy that file's structure rather than inventing a new one.

- [ ] **Step 6: Write `CreateComponentRuleToolCallback`**

Same shape. Input schema:

```json
{
    "type": "object",
    "properties": {
        "componentName": {"type": "string", "description": "The component the rule governs"},
        "actionName": {"type": "string", "description": "Optional — omit to govern every action of the component"},
        "phase": {"type": "string", "description": "BEFORE or AFTER"},
        "ruleAction": {"type": "string", "description": "BLOCK or TAG. AFTER rules must be TAG."},
        "condition": {"type": "string", "description": "The formula body, validated by proposeComponentRuleCondition first"},
        "description": {"type": "string", "description": "The plain-English intent, kept for later re-editing"}
    },
    "required": ["componentName", "phase", "ruleAction", "condition"]
}
```

Description:

```
Create a component rule. Call proposeComponentRuleCondition first and confirm the condition with the admin
before calling this — this tool writes to the database. An AFTER-phase rule must use TAG; BLOCK is rejected,
because the action has already run by then. Returns the new rule's id.
```

The body validates `componentName`/`condition` non-blank, parses `phase` and `ruleAction` through `RulePhase.valueOf` / `RuleAction.valueOf` (returning a tool error, not an exception, on an unknown value), rejects `AFTER` + `BLOCK` with a tool error **before** calling the service (so the model gets a usable message rather than a stack trace), builds a `ComponentRule` with no id, calls `componentRuleService.saveComponentRule`, and returns `{"componentRuleId": <id>}`.

Rejecting `AFTER`+`BLOCK` here duplicates the service's guard on purpose: the service guard is the real invariant and stays, this one exists to give the model a corrigible message in the same turn.

- [ ] **Step 7: Write the factory**

`.../componentrule/ComponentRuleToolCallbacksFactory.java`:

```java
/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.componentrule;

import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.service.ActionDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Builds the Component Rule tool-callback lists shared by the Copilot panel agents
 * ({@code component_rule_ask} / {@code component_rule_build}), the AI Hub's flat and catalog registrations, and any
 * future MCP contributor. Read list feeds ASK; write list feeds BUILD.
 *
 * <p>
 * {@code proposeComponentRuleCondition} sits on the READ list even though authoring a condition sounds like a write:
 * it persists nothing, and putting it on both agents is what lets the ASK agent answer "how would I express this as a
 * rule" without a mode switch. Only {@code createComponentRule} touches the database.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class ComponentRuleToolCallbacksFactory {

    private final ActionDefinitionService actionDefinitionService;
    private final ComponentDefinitionService componentDefinitionService;
    private final ComponentRuleService componentRuleService;
    private final Evaluator evaluator;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ComponentRuleToolCallbacksFactory(
        ComponentRuleService componentRuleService, ComponentDefinitionService componentDefinitionService,
        ActionDefinitionService actionDefinitionService, Evaluator evaluator) {

        this.componentRuleService = componentRuleService;
        this.componentDefinitionService = componentDefinitionService;
        this.actionDefinitionService = actionDefinitionService;
        this.evaluator = evaluator;
    }

    public List<ToolCallback> readToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        toolCallbacks.add(new ListComponentRulesToolCallback(componentRuleService));
        toolCallbacks.add(
            new DescribeComponentActionParametersToolCallback(
                componentDefinitionService, actionDefinitionService));
        toolCallbacks.add(new ProposeComponentRuleConditionToolCallback(evaluator));

        return toolCallbacks;
    }

    public List<ToolCallback> writeToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>(readToolCallbacks());

        toolCallbacks.add(new CreateComponentRuleToolCallback(componentRuleService));

        return toolCallbacks;
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-tool:test > /tmp/t8.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t8.log
```

Expected: `exit=0`, 7 passing.

- [ ] **Step 9: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add server/ee/libs/automation/automation-ai/automation-ai-tool && git commit -m "5xxx Add component rule AI tool callbacks"
```

---

## Task 9: Copilot panel agents and AI Hub registration

**Files:**
- Modify: `server/libs/ai/ai-copilot/ai-copilot-api/src/main/java/com/bytechef/ai/copilot/util/Source.java`
- Modify: `server/libs/ai/ai-copilot/ai-copilot-tool/src/main/java/com/bytechef/ai/copilot/tool/CopilotAgentType.java`
- Create: `server/ee/libs/automation/automation-ai/automation-ai-copilot/src/main/java/com/bytechef/ee/automation/ai/copilot/config/ComponentRuleAgentConfiguration.java`
- Create: `.../automation-ai-copilot/src/main/resources/prompt_component_rule_ask.txt`
- Create: `.../automation-ai-copilot/src/main/resources/prompt_component_rule_build.txt`
- Modify: `server/ee/libs/automation/automation-ai/automation-ai-copilot/build.gradle.kts`
- Modify: `server/ee/libs/ai/ai-hub/ai-hub-service/src/main/java/com/bytechef/ee/ai/hub/config/AiHubConfiguration.java`
- Modify: `client/src/shared/components/copilot/stores/useCopilotStore.ts`
- Test: `.../automation-ai-copilot/src/test/java/com/bytechef/ee/automation/ai/copilot/config/ComponentRuleAgentConfigurationConditionTest.java`

**Interfaces:**
- Consumes: `ComponentRuleToolCallbacksFactory` (Task 8); `ComponentRuleService` (Task 1); `ActionDefinitionService`, `ComponentDefinitionService`, `Evaluator` (existing beans).
- Produces:
  - `Source.COMPONENT_RULE` (server enum) and `Source.COMPONENT_RULE = 'COMPONENT_RULE'` (client enum).
  - `CopilotAgentType.COMPONENT_RULE_ASK("component_rule_ask", false)`, `COMPONENT_RULE_BUILD("component_rule_build", false)`, `COMPONENT_RULE("component_rule", true)`.
  - Beans `componentRuleToolCallbacksFactory`, `componentRuleAskSpringAIAgent`, `componentRuleBuildSpringAIAgent`.
  - `AiHubConfiguration.componentRuleFlatCrudToolCallbacks(ObjectProvider<ComponentRuleToolCallbacksFactory>)` and `AiHubConfiguration.componentRuleCatalogToolCallbacks(ObjectProvider<ComponentRuleToolCallbacksFactory>)`, both package-private static, mirroring the `contextStore*` pair.

- [ ] **Step 1: Add the enum members**

`Source.java` — append `COMPONENT_RULE` to the enum's last line (append-only; this enum is consumed by name).

`CopilotAgentType.java` — add the standard ASK/BUILD/parent triple after the `CONTEXT_STORE` triple:

```java
    COMPONENT_RULE_ASK("component_rule_ask", false),
    COMPONENT_RULE_BUILD("component_rule_build", false),
    COMPONENT_RULE("component_rule", true),
```

`useCopilotStore.ts` — add to the UPPER_CASE block (not the lowercase legacy block; the comment in that file is explicit about which convention new sources follow):

```ts
    COMPONENT_RULE = 'COMPONENT_RULE',
```

- [ ] **Step 2: Write the two prompt files**

`.../automation-ai-copilot/src/main/resources/prompt_component_rule_ask.txt`:

```
You help a ByteChef administrator understand and author Component Rules.

A Component Rule conditionally governs a single action call. It names a component, optionally one of that
component's actions (omit the action to govern all of them), a phase, an enforcement action, and a condition.

- Phase BEFORE runs before the action executes. Its condition sees inputParameters, componentName, actionName,
  connectionId.
- Phase AFTER runs after the action returns. Its condition additionally sees output.
- Enforcement BLOCK refuses the call. It is only valid in the BEFORE phase — after the action has run, a block
  cannot undo what it already did.
- Enforcement TAG lets the call proceed and records an audit event for later review.
- If both a BLOCK and a TAG rule match the same call, only the block takes effect.

CONDITION SYNTAX. A condition is a ByteChef formula expression body, written WITHOUT the leading "=".
It is Spring Expression Language with three hard restrictions:

- No Java method calls. `inputParameters['channel'].startsWith('C05')` is INVALID.
- No type references. `T(java.lang.String)` is INVALID.
- No constructors. `new java.util.Date()` is INVALID.

Use ByteChef's own functions instead, called bare: contains, equalsIgnoreCase, indexOf, length, size, split,
substring, join, concat, format. So write `contains(inputParameters['channel'], 'C05')`.

Map access uses bracket-and-quote form: `inputParameters['recordId']`. Comparison, null checks and boolean
operators work as usual: `inputParameters['ownerId'] != null and inputParameters['force'] == true`.

WORKFLOW. Before proposing any condition, call describeComponentActionParameters for the target component and
action, so the condition references parameter names that actually exist rather than names you guessed. Then call
proposeComponentRuleCondition with the condition and a one-sentence explanation. If it comes back valid: false,
read the error, rewrite the condition and call it again. Never present a condition you have not validated.

You are in ASK mode: you may read and propose, but you cannot save. Tell the admin to press Save in the Rules
dialog, or to switch to BUILD mode, when they want the rule persisted.
```

`.../automation-ai-copilot/src/main/resources/prompt_component_rule_build.txt`: the same text with the final paragraph replaced by:

```
You are in BUILD mode and can persist rules with createComponentRule. Do not call it until you have
(1) validated the condition through proposeComponentRuleCondition, and (2) shown the admin the complete rule —
component, action, phase, enforcement and condition — and had them confirm it. A rule governs live workflow
executions; creating one the admin has not read is not acceptable. If anything about the target is ambiguous,
ask rather than guess.
```

The two files are separate because the modes differ in what they may do, which is the same reason every other domain here ships an ask/build pair.

- [ ] **Step 3: Write the agent configuration**

Copy `ContextStoreAgentConfiguration.java` and adapt. Concretely, `.../config/ComponentRuleAgentConfiguration.java` declares:

- `@Configuration`, `@ConditionalOnEEVersion`, and
  `@ConditionalOnExpression("${bytechef.ai.copilot.enabled:false} or ${bytechef.ai.hub.enabled:false}")`.
  There is no third feature-flag conjunct: unlike Context Store, Component Rules has no
  `bytechef.<feature>.enabled` property of its own — the module ships whenever EE does.
- `@Value("classpath:prompt_component_rule_ask.txt")` / `..._build.txt` resources and a `private final State state = new State();`.
- A `@Bean ComponentRuleToolCallbacksFactory componentRuleToolCallbacksFactory(ComponentRuleService componentRuleService, ComponentDefinitionService componentDefinitionService, ActionDefinitionService actionDefinitionService, Evaluator evaluator)`.
- Two `@Bean ContextStoreSpringAIAgent`-shaped agents. Use whichever concrete `SpringAIAgent` subclass the sibling configurations in this package use for a plain domain agent — check with
  `grep -n "SpringAIAgent" server/ee/libs/automation/automation-ai/automation-ai-copilot/src/main/java/com/bytechef/ee/automation/ai/copilot/config/ApiCollectionAgentConfiguration.java`
  and use that class, not `ContextStoreSpringAIAgent` (which is context-store-specific).
  Agent ids are `Source.COMPONENT_RULE.name() + "_" + Mode.ASK.name()` lowercased, and `..._BUILD` likewise — matching the `CopilotAgentType` values added in Step 1.
- The same private `wrapToolCallbacks(SecurityContextRehydrator, List<ToolCallback>)` helper (wrapping each callback in `RehydrateContextToolCallback.wrap`) and `readPrompt(Resource)` helper as `ContextStoreAgentConfiguration`, with the error message naming component rules.

ASK gets `componentRuleToolCallbacksFactory.readToolCallbacks()`; BUILD gets `writeToolCallbacks()`.

Add to `.../automation-ai-copilot/build.gradle.kts`:

```kotlin
    implementation(project(":server:ee:libs:platform:platform-component-rule:platform-component-rule-api"))
    implementation(project(":server:libs:core:evaluator:evaluator-api"))
```

- [ ] **Step 4: Write the configuration-condition test**

Model it on `ContextStoreAgentConfigurationConditionTest`:

```bash
cat server/ee/libs/automation/automation-ai/automation-ai-copilot/src/test/java/com/bytechef/ee/automation/ai/copilot/config/ContextStoreAgentConfigurationConditionTest.java
```

`ComponentRuleAgentConfigurationConditionTest` asserts the same three things for `ComponentRuleAgentConfiguration`, minus the feature-flag conjunct: the class carries `@ConditionalOnEEVersion`; its `@ConditionalOnExpression` value mentions both `bytechef.ai.copilot.enabled` and `bytechef.ai.hub.enabled`; and it does **not** mention a third property. That last assertion is the one worth having — it pins the deliberate difference from the sibling configuration this file was copied from.

- [ ] **Step 5: Register on the AI Hub surface**

In `AiHubConfiguration.java`:

1. Add an `ObjectProvider<ComponentRuleToolCallbacksFactory> componentRuleToolCallbacksFactoryProvider` parameter to the two agent-bean methods that already take `contextStoreToolCallbacksFactoryProvider` (the ASK agent around line 303 and the BUILD agent around line 492), and to the BUILD global catalog method (around line 718).
2. Add, next to each `contextStoreFlatCrudToolCallbacks(...)` call:
   ```java
        toolCallbacks.addAll(componentRuleFlatCrudToolCallbacks(componentRuleToolCallbacksFactoryProvider));
   ```
3. Add, next to the `contextStoreCatalogToolCallbacks(...)` call:
   ```java
        toolCallbacks.addAll(componentRuleCatalogToolCallbacks(componentRuleToolCallbacksFactoryProvider));
   ```
4. Add the two package-private static helpers beside `contextStoreFlatCrudToolCallbacks` / `contextStoreCatalogToolCallbacks`:

```java
    /**
     * The three read-side Component Rule tools flattened onto both AI Hub agents: {@code listComponentRules},
     * {@code describeComponentActionParameters} and {@code proposeComponentRuleCondition}. All three are pinned
     * rather than catalog-demoted because they are grounding calls — "what rules do I have", "what parameters does
     * this action take", "is this condition valid" — that an agent needs before it can say anything useful about a
     * rule, and forcing a {@code searchTool} round trip in front of each would cost a turn per question.
     *
     * <p>
     * {@code proposeComponentRuleCondition} is on the read list despite sounding like authoring: it persists nothing.
     * The one mutation is catalog-demoted, see {@link #componentRuleCatalogToolCallbacks}.
     * </p>
     *
     * <p>
     * An absent factory bean (Copilot and AI Hub both disabled, or a non-{@code ee} edition) resolves to an empty
     * list — the same silent-skip degrade every other Copilot-domain registration in this class follows.
     * </p>
     */
    static List<ToolCallback> componentRuleFlatCrudToolCallbacks(
        ObjectProvider<ComponentRuleToolCallbacksFactory> componentRuleToolCallbacksFactoryProvider) {

        ComponentRuleToolCallbacksFactory componentRuleToolCallbacksFactory =
            componentRuleToolCallbacksFactoryProvider.getIfAvailable();

        if (componentRuleToolCallbacksFactory == null) {
            return List.of();
        }

        return componentRuleToolCallbacksFactory.readToolCallbacks();
    }

    /**
     * {@code createComponentRule}, catalog-demoted rather than pinned on BUILD — matching how every other domain's
     * mutations are registered since the CRUD-delegate unwind. Creating a rule is rare next to asking about one, and
     * a pinned tool costs schema on every request whether or not it is used.
     */
    static List<ToolCallback> componentRuleCatalogToolCallbacks(
        ObjectProvider<ComponentRuleToolCallbacksFactory> componentRuleToolCallbacksFactoryProvider) {

        ComponentRuleToolCallbacksFactory componentRuleToolCallbacksFactory =
            componentRuleToolCallbacksFactoryProvider.getIfAvailable();

        if (componentRuleToolCallbacksFactory == null) {
            return List.of();
        }

        List<ToolCallback> readToolCallbacks = componentRuleToolCallbacksFactory.readToolCallbacks();

        return componentRuleToolCallbacksFactory.writeToolCallbacks()
            .stream()
            .filter(toolCallback -> !readToolCallbacks.contains(toolCallback))
            .toList();
    }
```

The filter cannot rely on instance identity, because `writeToolCallbacks()` constructs a fresh read list. Compare tool **names** instead:

```java
        Set<String> readToolNames = componentRuleToolCallbacksFactory.readToolCallbacks()
            .stream()
            .map(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .collect(Collectors.toSet());

        return componentRuleToolCallbacksFactory.writeToolCallbacks()
            .stream()
            .filter(toolCallback -> !readToolNames.contains(
                toolCallback.getToolDefinition()
                    .name()))
            .toList();
```

Use the name-based version; the identity-based one above is shown only to make the trap explicit.

Add to `.../ai-hub-service/build.gradle.kts` if not already present:

```kotlin
    implementation(project(":server:ee:libs:automation:automation-ai:automation-ai-tool"))
```

- [ ] **Step 6: Compile and run the affected modules' tests**

```bash
./gradlew :server:ee:libs:automation:automation-ai:automation-ai-copilot:test :server:ee:libs:ai:ai-hub:ai-hub-service:test :server:libs:ai:ai-copilot:ai-copilot-tool:test --continue > /tmp/t9.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t9.log
```

Expected: `exit=0`. If `AutomationCopilotMcpContributorConfigurationTest` fails, it enumerates registered sources — add `COMPONENT_RULE` to its expectation.

- [ ] **Step 7: Verify the whole server still compiles**

```bash
./gradlew compileJava compileTestJava --continue > /tmp/t9compile.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/t9compile.log
```

Expected: `exit=0`.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply > /tmp/spotless.log 2>&1; echo "exit=$?"
```

```bash
git add server/libs/ai server/ee/libs/automation/automation-ai server/ee/libs/ai/ai-hub && git commit -m "5xxx Register component rule copilot agents and AI Hub tools"
```

```bash
git add client/src/shared/components/copilot/stores/useCopilotStore.ts && git commit -m "5xxx client - Add COMPONENT_RULE copilot source"
```

---

## Task 10: Client — "Generate with AI" wiring

Replaces the Task 7 stub with the real hook, following `useSampleOutputCopilot` — the established pattern for a copilot panel that returns a value into a form field rather than performing an action.

**Files:**
- Modify: `client/src/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot.ts`
- Test: `client/src/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot.test.ts`

**Interfaces:**
- Consumes: `useCopilotStore` (with `Source.COMPONENT_RULE` from Task 9), `useCopilotStateContributorRegistry`, `useCopilotToolResultHandlerRegistry`, `useCopilotPostTurnRegistry`, `MODE`; `parseJson` from `@/shared/components/ai-chat/messages/toToolResultDataPart`; the `proposeComponentRuleCondition` result shape from Task 8.
- Produces: the same `UseComponentRuleCopilotParamsI` / `UseComponentRuleCopilotResultI` signature the Task 7 stub declared, so `ComponentRuleDialog` needs no change.

- [ ] **Step 1: Write the failing hook test**

`client/src/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot.test.ts`:

```ts
import useComponentRuleCopilot from '@/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot';
import useCopilotToolResultHandlerRegistry from '@/shared/components/copilot/stores/useCopilotToolResultHandlerRegistry';
import {Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
import {act, renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

describe('useComponentRuleCopilot', () => {
    beforeEach(() => {
        useCopilotStore.setState({context: {mode: 'ASK', parameters: {}, source: Source.WORKFLOW_EDITOR}} as never);
    });

    it('opens the panel scoped to the component and action', () => {
        const {result} = renderHook(() =>
            useComponentRuleCopilot({
                actionName: 'sendMessage',
                componentName: 'slack',
                currentCondition: '',
                onConditionGenerated: vi.fn(),
            })
        );

        act(() => {
            result.current.openCopilot();
        });

        const {context} = useCopilotStore.getState();

        expect(context.source).toBe(Source.COMPONENT_RULE);
        expect(context.parameters).toEqual({actionName: 'sendMessage', componentName: 'slack'});
    });

    it('applies a valid proposed condition', () => {
        const onConditionGenerated = vi.fn();

        renderHook(() =>
            useComponentRuleCopilot({
                actionName: 'sendMessage',
                componentName: 'slack',
                currentCondition: '',
                onConditionGenerated,
            })
        );

        act(() => {
            useCopilotToolResultHandlerRegistry
                .getState()
                .runFor(
                    'proposeComponentRuleCondition',
                    JSON.stringify({condition: "contains(inputParameters['channel'], 'C05')", valid: true})
                );
        });

        expect(onConditionGenerated).toHaveBeenCalledWith("contains(inputParameters['channel'], 'C05')");
    });

    it('ignores a proposal the server marked invalid', () => {
        const onConditionGenerated = vi.fn();

        renderHook(() =>
            useComponentRuleCopilot({
                componentName: 'slack',
                currentCondition: '',
                onConditionGenerated,
            })
        );

        act(() => {
            useCopilotToolResultHandlerRegistry
                .getState()
                .runFor(
                    'proposeComponentRuleCondition',
                    JSON.stringify({condition: 'bad(', error: 'nope', valid: false})
                );
        });

        expect(onConditionGenerated).not.toHaveBeenCalled();
    });
});
```

The registry's state is `{handlers: Partial<Record<string, ToolResultHandlerType>>, register, runFor}` — `handlers` is a PLAIN OBJECT, not a `Map`, so `.handlers.get(name)` would throw. The tests above use the registry's own `runFor(toolName, content)` method instead, which is both the public API and exactly what the production dispatch path calls, so the test drives the handler the same way the app does.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd client && npx vitest run src/ee/pages/settings/platform/component-rules/hooks
```

Expected: FAIL — the stub returns a no-op `openCopilot` and registers nothing.

- [ ] **Step 3: Write the hook**

`client/src/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot.ts`:

```ts
import {parseJson} from '@/shared/components/ai-chat/messages/toToolResultDataPart';
import useCopilotPostTurnRegistry from '@/shared/components/copilot/stores/useCopilotPostTurnRegistry';
import useCopilotStateContributorRegistry from '@/shared/components/copilot/stores/useCopilotStateContributorRegistry';
import {MODE, Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
import useCopilotToolResultHandlerRegistry from '@/shared/components/copilot/stores/useCopilotToolResultHandlerRegistry';
import {useCallback, useEffect, useRef} from 'react';

const APPLIED_MESSAGE = '✓ Applied the condition to the rule editor.';
const PROPOSE_TOOL_NAME = 'proposeComponentRuleCondition';

interface UseComponentRuleCopilotParamsI {
    actionName?: string;
    componentName: string;
    currentCondition: string;
    onConditionGenerated: (condition: string) => void;
}

interface UseComponentRuleCopilotResultI {
    openCopilot: () => void;
}

/**
 * Opens the Copilot panel scoped to one component/action and drops the condition it proposes into the rule editor.
 * Nothing is saved here: the panel proposes, the admin reviews in the textarea, and Save is a separate, deliberate
 * click.
 */
const useComponentRuleCopilot = ({
    actionName,
    componentName,
    currentCondition,
    onConditionGenerated,
}: UseComponentRuleCopilotParamsI): UseComponentRuleCopilotResultI => {
    const conversationTokenRef = useRef<string | null>(null);
    const pendingAppliedRef = useRef<boolean>(false);

    const openCopilot = useCallback(() => {
        const {context, generateConversationId, resetMessages, saveConversationState, setContext} =
            useCopilotStore.getState();

        conversationTokenRef.current = saveConversationState();

        resetMessages();
        generateConversationId();

        setContext({
            ...context,
            mode: MODE.ASK,
            parameters: {actionName, componentName},
            source: Source.COMPONENT_RULE,
        });
    }, [actionName, componentName]);

    useEffect(() => {
        const unregisterContributor = useCopilotStateContributorRegistry.getState().register(() => ({
            actionName,
            componentName,
            currentCondition,
        }));

        const unregisterToolResult = useCopilotToolResultHandlerRegistry
            .getState()
            .register(PROPOSE_TOOL_NAME, (content) => {
                const result = parseJson<{condition?: string; valid?: boolean}>(
                    content,
                    `${PROPOSE_TOOL_NAME} result`
                );

                // An invalid proposal is the model's problem to fix on its next turn — never put a condition the
                // server already rejected into the editor, where Save would reject it a second time.
                if (result?.valid !== true || !result.condition) {
                    return;
                }

                pendingAppliedRef.current = true;

                onConditionGenerated(result.condition);
            });

        const unregisterPostTurn = useCopilotPostTurnRegistry.getState().register(Source.COMPONENT_RULE, () => {
            if (!pendingAppliedRef.current) {
                return;
            }

            pendingAppliedRef.current = false;

            useCopilotStore.getState().appendToLastAssistantMessage(APPLIED_MESSAGE);
        });

        return () => {
            unregisterContributor();
            unregisterToolResult();
            unregisterPostTurn();
        };
    }, [actionName, componentName, currentCondition, onConditionGenerated]);

    return {openCopilot};
};

export default useComponentRuleCopilot;
```

`useSampleOutputCopilot` also owns a `copilotPanelOpen` boolean and a `handleCopilotClose` that restores the saved conversation. This hook does not, because the panel it opens is the page-level Copilot panel already mounted by the settings shell, not a locally rendered one. `conversationTokenRef` is still captured so a future close handler can restore it; if `npm run check` flags it as unused, add the `handleCopilotClose` callback and wire it to the dialog's `onOpenChange(false)` rather than deleting the ref.

- [ ] **Step 4: Run the hook test to verify it passes**

```bash
cd client && npx vitest run src/ee/pages/settings/platform/component-rules
```

Expected: 15 passing (6 list + 6 dialog + 3 hook).

- [ ] **Step 5: Run the full client check**

```bash
cd client && npm run check
```

Expected: exit 0. Set the tool timeout to 600000 ms.

- [ ] **Step 6: Format and commit**

```bash
cd client && npm run format
```

```bash
git add client/src/ee/pages/settings/platform/component-rules && git commit -m "5xxx client - Generate component rule conditions with Copilot"
```

---

## Task 11: Full verification and documentation

**Files:**
- Modify: `CLAUDE.md` (one entry under "Cross-cutting rules from those docs" is not warranted; instead add a short section — see Step 3)

- [ ] **Step 1: Run the whole server check**

```bash
./gradlew check --continue > /tmp/check.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/check.log
```

Expected: only pre-existing failures. Compare against the known-failing baseline before blaming this change — re-run the same command on the merge-base commit if anything looks unfamiliar. Note that SpotBugs' XML report is disabled in this repo: read `build/reports/spotbugs/*.html`, not the stale XML.

- [ ] **Step 2: Run the integration tests that touch the schema**

```bash
./gradlew :server:ee:libs:platform:platform-component-policy:platform-component-policy-service:testIntegration > /tmp/checkint.log 2>&1; echo "exit=$?"; grep -E "^> Task .* FAILED" /tmp/checkint.log
```

Expected: `exit=0`. Docker must be running.

- [ ] **Step 3: Document the feature in CLAUDE.md**

Add this section to `CLAUDE.md` after the "Variables (workspace / embedded organization, EE)" section:

```markdown
### Component Rules (EE)

`server/ee/libs/platform/platform-component-rule/` (`-api`/`-service`/`-graphql`), sibling to
`platform-component-policy`. One `component_rule` row conditionally governs one component action: phase
(`BEFORE`/`AFTER`), enforcement (`BLOCK`/`TAG`), and a SpEL condition. `action_name` null means every action.
Enforcement hangs off the CE SPI `ComponentRuleEnforcer` (`platform-component-api`, no CE implementation), called
from `ActionDefinitionServiceImpl.doExecutePerform` and `executePerformForPolyglot` beside the existing visibility
guards.

**The stored `condition` is a formula BODY, not free SpEL.** `SpelEvaluator` parses full SpEL only behind a `=`
prefix, and `validateFormulaExpression` rejects `T(`, any `.method(` call, and `new`. So conditions use ByteChef's
whitelisted evaluator functions (`contains`, `equalsIgnoreCase`, `size`, …) — `contains(inputParameters['c'], 'x')`,
never `inputParameters['c'].startsWith('x')`. Every evaluation and every parse-check prepends the `=`.

**A condition that cannot be resolved does not fire, so a BLOCK rule fails open.** `SpelEvaluator` returns the
original string for an unresolved reference rather than null, and only `Boolean.TRUE` counts as a match. This is
deliberate: a mis-authored rule must not take a tenant's whole workflow estate offline.

The enforcement cache is keyed by **(tenantId, componentName)**, not componentName alone — component names are
global, so a component-only key would serve one tenant's rules to another.

Rules see `connectionId`. Beware: two records are named `ComponentConnection` — the chokepoint imports
`com.bytechef.platform.component.ComponentConnection` (which has `connectionId`), NOT
`com.bytechef.platform.configuration.domain.ComponentConnection` (which has `key`/`workflowNodeName`). Tag matches
surface as `RULE_TAGGED` audit events on the existing Audit Events page — there is no dedicated review UI.

AI authoring is a flat/catalog copilot slice, NOT a delegate: `ComponentRuleToolCallbacksFactory`'s three read tools
(`listComponentRules`, `describeComponentActionParameters`, `proposeComponentRuleCondition`) are pinned on both AI
Hub agents; `createComponentRule` is catalog-demoted on BUILD. `proposeComponentRuleCondition` persists nothing —
it validates and returns, and the client drops the result into the dialog's editor.

Spec: `docs/superpowers/specs/2026-08-12-component-rules-design.md`.
Plan: `docs/superpowers/plans/2026-08-31-component-rules-plan.md`.
```

- [ ] **Step 4: Manual smoke test**

Start the infrastructure and the server:

```bash
cd server && docker compose -f docker-compose.dev.infra.yml up -d
```

```bash
./gradlew -p server/apps/server-app bootRun
```

```bash
cd client && npm run dev
```

Sign in as `admin@localhost.com` / `admin`, go to Settings → Components → Rules, and verify each of:

1. Add a rule: component `Slack`, action `sendMessage`, phase Before, enforcement Block, condition `inputParameters['channel'] == 'C05QG7RF30A'`. It saves and appears in the list.
2. Switch the phase to After in the dialog — the Block radio goes disabled and the selection falls back to Tag.
3. Enter `inputParameters['channel'].startsWith('C05')` and Save — the request fails with a typed error toast naming the condition, not a generic 500. (`GlobalDataFetcherExceptionResolver` maps `ConfigurationException` to `BAD_REQUEST` and forwards `entityClass`/`errorKey`/`errorCode` in the GraphQL error extensions, so no extra server work is needed for this.)
4. Toggle a rule off and on — the switch responds immediately (optimistic) and survives a page reload.
5. Press "Generate with AI" with a component selected — the Copilot panel opens, and a proposed condition lands in the textarea without being saved.
6. Build a workflow with the Slack `sendMessage` node targeting that channel and run it — the execution fails with the block message, and an Audit Events row appears with event `RULE_BLOCKED`.
7. Change the rule to Tag and re-run — the execution succeeds and an audit row appears with event `RULE_TAGGED`.
8. Delete the rule and re-run — the execution succeeds with no audit row. Allow up to 10 seconds for the cache TTL.

- [ ] **Step 5: Commit the documentation**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-08-12-component-rules-design.md docs/superpowers/plans/2026-08-31-component-rules-plan.md && git commit -m "5xxx Document component rules"
```

---

## Spec coverage

Every numbered section of the spec, and where it lands.

| Spec | Where |
|------|-------|
| §1 table columns, no composite unique key, ordinal-stability tests | Task 1 (domain + enums + ordinal test), Task 2 (changelog) |
| §1 `getComponentRules(componentName)` | Task 1 contract, Task 2 impl |
| §1 `getEnabledComponentRules(componentName, actionName, phase)` | Task 1 — **narrowed to `(componentName)` only**; the spec's own caching requirement ("one query per component, not per action") makes the extra arguments counterproductive. Filtering by action and phase moved to the enforcer (Task 4) |
| §1 `saveComponentRule` rejects BLOCK+AFTER and unparseable SpEL | Task 2, both guards + 4 tests |
| §1 `deleteComponentRule` | Task 2 |
| §1 "why not reuse `component_operation_policy`" | Honoured — a separate table, separate module |
| §2 chokepoint at `executePerform` / `executePerformForPolyglot` | Task 3 |
| §2 evaluation context (`inputParameters`, `componentName`, `actionName`, `connectionId`) | Task 4 — fully met, including `connectionId`. Deviation 5 was withdrawn mid-execution once the correct `ComponentConnection` record was identified |
| §2 BLOCK throws `RULE_BLOCKED`, perform never runs | Task 3 (error type + test asserting the registry is untouched), Task 4 (the throw) |
| §2 block wins over tag, tag not separately audited | Task 4, `testBlockWinsOverTagOnTheSameCall` |
| §2 AFTER phase sees `output`, never fails the execution | Task 4, `testAfterRuleSeesOutputInItsContext` + `testAfterPhaseNeverThrows` |
| §2 Caffeine cache, 10s TTL, one query per component | Task 4 — **keyed by (tenant, component)**, see Deviation 3 |
| §3 `ComponentRuleAuditEvent` own file, both non-strict, payload shape | Task 4 |
| §3 no new UI for tag review | Nothing to build — the existing Audit Events page reads these rows |
| §4 GraphQL enums, type, query, mutations, admin gating, optional `id` | Task 5 |
| §4 typed errors rather than 500s | Free: `GlobalDataFetcherExceptionResolver` maps `ConfigurationException` to `BAD_REQUEST` with `entityClass`/`errorKey`/`errorCode` extensions. Verified in Task 11 Step 4 item 3 |
| §5 shared read/write tool callbacks in `automation-ai-tool` | Task 8 |
| §5 `ComponentRuleAgentConfiguration` in `automation-ai-copilot`, ask/build prompts | Task 9 |
| §5 `component_rule_agent` delegate via `wrapDelegate` | **Not implemented** — the delegate pattern was removed in ticket 732. Flat reads + catalog-demoted write instead, Task 9. See Deviation 2 |
| §5 `Source` enum entry on client and server | Task 9 |
| §5 prompt receives the action's real parameter schema | Task 8 (`describeComponentActionParameters`) + Task 9 (the prompt instructs the model to call it before proposing) |
| §5 nothing saved without explicit Save, from either surface | Task 8 (`proposeComponentRuleCondition` persists nothing, asserted), Task 9 (BUILD prompt requires confirmation before `createComponentRule`), Task 10 (the client only fills the editor) |
| §5 "interactive-question mechanism" for the AI Hub write path | **Partially** — enforced by prompt instruction rather than by the `askUserQuestion` tool. The AI Hub BUILD agent already has that tool pinned, so the prompt directs it there; there is no code-level gate forcing confirmation. If that turns out to be too weak in practice, the follow-up is a `confirmed: true` required field on `createComponentRule`'s input schema |
| §6 flat list, badges, enabled toggle, truncated condition with tooltip | Task 6 |
| §6 Add Rule dialog with component/action pickers, phase/action radios, condition textarea, Generate with AI | Task 7 |
| §6 tab placement left to the implementer | Task 6 — top-level `components/rules`, see Deviation 6 |
| §7 ordinal-stability tests | Task 1 |
| §7 `ComponentRuleServiceTest` | Task 2 |
| §7 enforcement tests (block, tag, block-wins, after-sees-output, no-rule no-op) | Tasks 3 and 4 |
| §7 cache test: one miss loads both phases; TTL expiry re-fetches | Task 4, `testRulesAreFetchedOncePerComponentAcrossBothPhases` + `testTheCacheRefetchesAfterItsTtlExpires` |
| §7 GraphQL controller test | Task 5 |
| §7 copilot subagent test | Task 8 (`testProposeReturnsTheConditionWhenItParses` asserts nothing is saved; `testWriteToolCallbacksAddExactlyOneMutation` pins the write surface at one tool) |
| §7 client Vitest: After-disables-Block, optimistic toggle | Task 7 (dialog), Task 6 (toggle) |
| Non-goal: no BLOCK on AFTER | Enforced at save (Task 2), in the tool (Task 8), and in the form (Task 7) |
| Non-goal: no trigger governance | The SPI names actions only; triggers are untouched |
| Non-goal: raw SpEL, no structured builder | The dialog's condition field is a monospace textarea |
| Non-goal: tenant-wide only | No `workspace_id` column, per the CLAUDE.md placement rule — this is a platform-package entity but deliberately tenant-scoped, matching `component_policy` |
| Non-goal: no ordering beyond block-wins-over-tag | The enforcer iterates the list twice — blocks first, then tags — with no priority column |
| Non-goal: no flagged-executions UI | Not built |

## Execution order and dependencies

Tasks 1 → 2 → 3 → 4 → 5 are a strict chain. Task 6 depends on Task 5's codegen. Task 7 must land with Task 6 (one commit). Task 8 depends on Tasks 1 and 5. Task 9 depends on Task 8. Task 10 depends on Task 9. Task 11 last.

The server is usable after Task 5 (GraphQL over a working enforcement engine) and the product is complete without AI after Task 7 — Tasks 8-10 are an additive slice that can be deferred without leaving anything half-built.

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-31-component-rules-plan.md`.

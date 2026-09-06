# Environment-Scoped Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Where a caller supplies the environment as an argument, make the authorization gate consult it, so a member holding a scope in one environment can no longer read or write in another.

**Architecture:** No new mechanism. `AutomationMethodSecurityExpressionRoot` already carries `hasWorkflowScopeInEnvironment(String, String, Long)` and `hasWorkspaceScopeInEnvironment(long, String, Environment)`. This plan adds `hasWorkspaceScopeInEnvironmentId(long, String, Long)` — a **distinct name**, not an overload — then re-points 20 annotations, then adds two coverage tests so the twenty-first site cannot regress silently.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring Security method security (custom `MethodSecurityExpressionRoot`), Gradle 9.7, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-03-environment-scoped-authorization-design.md` — read **D1 amended** before Task 1; it reverses two rulings an earlier draft of this plan carried.

**Reworked 2026-09-06** after a pre-execution review. Three blocking findings and seven important ones are folded in; the review is at `.superpowers/sdd/2026-09-06-environment-scoped-authorization/pre-execution-review.md`.

## Global Constraints

- **The gap is demonstrated.** `ProjectDeploymentCrossEnvironmentReadReproductionIntTest` and `PermissionServiceCrossEnvironmentUnionGapTest` (commit `807d4c10fc3`) prove it. Do not re-litigate whether the work is needed.
- **The new expression is named `hasWorkspaceScopeInEnvironmentId`.** Never add a `(long, String, Long)` sibling to the existing `(long, String, Environment)` method: that is ambiguous in Java for a null literal and resolved by *reflection order* in SpEL, where a null selects the `Environment` overload and NPEs on `environment.ordinal()`. Both reproduced during review.
- **Do not call `PrincipalEnvironment#resolveEffectiveEnvironmentId` in the workspace gate.** Substituting the principal's environment there creates the gate-vs-body divergence this work exists to remove. The gate checks the ordinal the caller sent, which is the same value the body reads.
- **A null ordinal keeps today's union check.** It routes to the environment-unaware `hasWorkspaceScope(workspaceId, scope)`. It must never deny and must never require every environment — the clients routinely send no environment, and denying would 403 ordinary pages for exactly the members this change protects.
- **Never widen a gate, and never lose the connected-user denial.** `ResourceMembershipDecider` must be consulted, because a governed principal reaching a type no resolver claims resolves `NOT_APPLICABLE` → DENY, and that denial exists today only by virtue of the `hasPermission` route.
- **CE is unaffected.** `hasWorkspaceScope` short-circuits on `isTenantAdmin()` and CE is admin-only. Do not add CE-specific branches.
- Files under `server/ee/` carry the ByteChef Enterprise license header and a `@version ee` Javadoc tag. Spotless applies the EE header only to files already containing the literal tag — for a new EE file, write the Javadoc with its tag FIRST, then run `spotlessApply`.
- `org.jspecify.annotations.Nullable`, never `org.springframework.lang.Nullable`.
- Checkstyle: test method names are camelCase with no underscores; `TODO:` comments are forbidden; no blank line before a class's closing brace.
- Run `./gradlew spotlessApply` before committing.
- `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce`.
- **Judge a Gradle run by `$?` on its own line plus `grep '^> Task .* FAILED'` on a redirected log file** — never a piped `tail` or `grep`. Use `--continue`. A suite Gradle reports "up-to-date" did not run; force it with `--rerun-tasks` when the result matters.
- Bash calls need `timeout: 600000`.
- Commit messages: `732 <description>`. Stage **by path**; never `git add -A` or `git add .`.
- **Never amend, never `git reset`, never `git stash`, never `git branch -f`** — the maintainer commits in parallel on this branch.

## The 20 sites

Enumerated by a multi-line-aware scan, plus one the scan missed. An independent scan during review returned the same 19 rows with no false positives; site 20 was found by sweeping the object-taking gates the scan cannot see.

| # | File | Method | Scope | Environment argument | Task |
|---|---|---|---|---|---|
| 20 | `WorkspaceApiKeyFacadeImpl` | `create` | `API_KEY_CREATE`* | **inside `ApiKey`** | 2 |
| 1 | `WorkspaceDataTableFacadeImpl` | `createTable` | `DATA_TABLE_CREATE` | `long environmentId` | 2 |
| 2 | `WorkspaceKnowledgeBaseFacadeImpl` | `createWorkspaceKnowledgeBase` | `KNOWLEDGE_BASE_CREATE` | `long environmentId` | 2 |
| 3 | `WorkspaceMcpServerFacadeImpl` | `createWorkspaceMcpServer` (6-arg) | `MCP_CREATE` | `Environment environment` | 2 |
| 4 | `WorkspaceMcpServerFacadeImpl` | `createWorkspaceMcpServer` (7-arg) | `MCP_CREATE` | `Environment environment` | 2 |
| 5 | `WorkspaceVariableGraphQlController` | `createWorkspaceVariable` | `VARIABLE_MANAGE` | `long environmentId` | 3 |
| 6 | `WorkspaceVariableGraphQlController` | `updateWorkspaceVariable` | `VARIABLE_MANAGE` | `long environmentId` | 3 |
| 7 | `WorkspaceVariableGraphQlController` | `deleteWorkspaceVariable` | `VARIABLE_MANAGE` | `long environmentId` | 3 |
| 8 | `WorkspaceVariableGraphQlController` | `workspaceVariables` | `VARIABLE_VIEW` | `long environmentId` | 3 |
| 9 | `ProjectDeploymentFacadeImpl` | `getWorkspaceChatWorkflows` | `WORKFLOW_VIEW` | `long environmentId` | 4 |
| 10 | `ProjectDeploymentFacadeImpl` | `getWorkspaceProjectDeployments` (4-arg) | `DEPLOYMENT_VIEW` | `long environmentId` | 4 |
| 11 | `WorkspaceApiKeyFacadeImpl` | `getApiKeys` | `API_KEY_VIEW` | `long environmentId` | 4 |
| 12 | `AiAgentFacadeImpl` | `getWorkspaceChatAgents` | `AGENT_VIEW` | `long environmentId` | 4 |
| 13 | `WorkspaceDataTableFacadeImpl` | `listTables` | `DATA_TABLE_VIEW` | `long environmentId` | 4 |
| 14 | `WorkspaceKnowledgeBaseFacadeImpl` | `getWorkspaceKnowledgeBases` | `KNOWLEDGE_BASE_VIEW` | `long environmentId` | 4 |
| 15 | `ProjectDeploymentFacadeImpl` | `getWorkspaceProjectDeployments` (5-arg, param `#id`) | `DEPLOYMENT_VIEW` | `Long` — **nullable** | 5 |
| 16 | `WorkspaceConnectionFacadeImpl` | `getConnections` | `CONNECTION_VIEW` | `Long` — **nullable** | 5 |
| 17 | `ProjectWorkflowExecutionFacadeImpl` | `getWorkflowExecutions` | `EXECUTION_VIEW` | `Long` — **nullable** | 6 |
| 18 | `McpServerPromotionHandler` | `preview` | `MCP_CREATE` | `Environment targetEnvironment` | 7 |
| 19 | `McpServerPromotionHandler` | `promote` | `MCP_CREATE` | `Environment targetEnvironment` | 7 |

\* Site 20's current scope must be read from the code, not assumed. Locate every site by method name; line numbers drift as tasks land.

## Rulings

**R1 (revised) — a null `environmentId` keeps today's union check.** An earlier draft routed null to `hasWorkspaceScopeInEveryEnvironment`. That would 403 live pages: `ProjectListItem.tsx`, `DeployButton.tsx`, `SelectConnectionMessage.tsx` and `ListProjectDeploymentsToolCallback` all send no `environmentId`, and an implicit-mode member passes while an **explicit** per-environment member — the population this work protects — is denied. This design closes *forgery*; a null forges nothing. Null therefore routes to `hasWorkspaceScope(workspaceId, scope)`, unchanged from today. The residual union leak on unfiltered listings is a stated limitation with its own ticket (spec §Out of scope). *Cost if wrong:* the unfiltered listing keeps a pre-existing leak one release longer.

**R2 (reversed) — `ResourceMembershipDecider` MUST be consulted.** The earlier ruling said not to copy it, because no `ResourceMembershipResolver` claims `"Workspace"`. The fact holds; the conclusion was backwards. The decider turns governed + `NOT_APPLICABLE` into **DENY**, so a `'Workspace'` gate denies an embedded connected user today precisely because nothing claims that type. Moving 20 gates off `hasPermission` without the decider would silently drop that denial from all of them. *Cost if wrong:* a connected user gains access to workspace-scoped automation surfaces, which is a cross-tenant failure — this is the highest-consequence line in the plan.

**R3 — promotion gates the TARGET environment only.** The target is the caller-supplied argument this design is about. Whether the caller must also hold the scope in the *source* is a genuine widening and belongs to the follow-up ticket. State the boundary in the code. *Cost if wrong:* a caller who can read the source only via today's union can still preview a promotion out of it — the pre-existing behaviour, no worse.

---

### Task 1: The new expression

**Files:**
- Modify: `server/libs/automation/automation-configuration/automation-configuration-service/src/main/java/com/bytechef/automation/configuration/security/AutomationMethodSecurityExpressionRoot.java`
- Test: the existing test for that class if there is one; otherwise create `AutomationMethodSecurityExpressionRootTest` beside it

**Interfaces:**
- Produces: `boolean hasWorkspaceScopeInEnvironmentId(long workspaceId, String scope, @Nullable Long environmentId)`, callable from SpEL as `hasWorkspaceScopeInEnvironmentId(#workspaceId, 'SCOPE', #environmentId)`. Tasks 2-6 consume it. Task 7 does not.
- Consumes: `PermissionService#hasWorkspaceScope(long, String)` and `#hasWorkspaceScope(long, String, Environment)`, `ResourceMembershipDecider`, `AutomationAuthorizationContext#isSkipChecks()`.

- [ ] **Step 1: Read the existing expressions first**

Read `hasWorkflowScopeInEnvironment` and `hasWorkspaceScopeInEnvironment` in full, including javadoc. Your method borrows the decider call and the range check from the first, and deliberately does **not** borrow the `PrincipalEnvironment` substitution. Note how the decider is obtained (`resourceMembershipResolverProvider`) — reuse that field, do not add a second.

- [ ] **Step 2: Write the failing tests**

Four branches, four tests. Use the class's existing test fixtures if a test already exists.

```java
@Test
void testANullEnvironmentIdKeepsTheEnvironmentUnawareCheck() {
    when(permissionService.hasWorkspaceScope(1L, "DEPLOYMENT_VIEW")).thenReturn(true);

    assertThat(expressionRoot.hasWorkspaceScopeInEnvironmentId(1L, "DEPLOYMENT_VIEW", null)).isTrue();

    verify(permissionService).hasWorkspaceScope(1L, "DEPLOYMENT_VIEW");
    verify(permissionService, never()).hasWorkspaceScope(anyLong(), anyString(), any(Environment.class));
}

@Test
void testAnOrdinalOutOfRangeDeniesRatherThanDefaulting() {
    assertThat(expressionRoot.hasWorkspaceScopeInEnvironmentId(1L, "DEPLOYMENT_VIEW", 99L)).isFalse();

    verify(permissionService, never()).hasWorkspaceScope(anyLong(), anyString(), any(Environment.class));
}

@Test
void testAResolvableOrdinalChecksThatEnvironmentAlone() {
    when(permissionService.hasWorkspaceScope(1L, "DEPLOYMENT_VIEW", Environment.PRODUCTION)).thenReturn(true);

    assertThat(
        expressionRoot.hasWorkspaceScopeInEnvironmentId(
            1L, "DEPLOYMENT_VIEW", (long) Environment.PRODUCTION.ordinal()))
                .isTrue();

    verify(permissionService, never()).hasWorkspaceScope(anyLong(), anyString());
}

@Test
void testAGovernedPrincipalIsDeniedBeforeAnyScopeIsRead() {
    // Mirrors what hasPermission(#workspaceId, 'Workspace', ...) does today: no resolver claims
    // "Workspace", so a governed principal resolves NOT_APPLICABLE, which the decider turns into DENY.
    // Losing this is how moving off hasPermission would have opened the surface to connected users.
    ...set up a governed ResourceMembershipResolver in the provider...

    assertThat(expressionRoot.hasWorkspaceScopeInEnvironmentId(1L, "DEPLOYMENT_VIEW", 0L)).isFalse();

    verifyNoInteractions(permissionService);
}
```

For the fourth test, copy the governed-principal fixture from an existing test that exercises `ResourceMembershipDecider` — `ConnectedUserResourceMembershipResolverTest` and the expression root's own tests are the places to look. Do not invent a fixture shape.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:libs:automation:automation-configuration:automation-configuration-service:test --tests '*AutomationMethodSecurityExpressionRoot*' > /tmp/t1.log 2>&1; echo $?
grep '^> Task .* FAILED' /tmp/t1.log
```

Expected: FAILED (a compile error on the missing method is an acceptable first failure).

- [ ] **Step 4: Write the method**

Place it directly beneath `hasWorkspaceScopeInEnvironment(long, String, Environment)`.

```java
    /**
     * Requires {@code scope} in the environment the caller named, for callers that supply it as a raw ordinal rather
     * than a resolved {@link Environment} — the case {@code hasPermission(#workspaceId, 'Workspace', ...)} cannot
     * express, because a workspace has no environment of its own for a {@code ResourceEnvironmentResolver} to supply
     * and the environment-unaware check therefore unions every environment the caller can reach.
     * <p>
     * <b>Named differently from {@link #hasWorkspaceScopeInEnvironment(long, String, Environment)} on purpose.</b> A
     * same-name, same-arity sibling taking {@code Long} is ambiguous in Java for a {@code null} literal, and in SpEL a
     * null argument matches both by reflection order — selecting the {@code Environment} overload and failing with an
     * NPE on {@code environment.ordinal()}. Do not merge the two.
     * <p>
     * <b>A {@code null} ordinal keeps the environment-unaware check</b> rather than denying or requiring every
     * environment. The listings that pass one use {@code null} as "no environment filter", and the clients routinely
     * send nothing, so denying would refuse ordinary pages to exactly the members per-environment roles protect. This
     * gate closes forgery — naming an environment the caller holds no role in — and a {@code null} names nothing. The
     * unfiltered listing still returns rows from every environment; that is a pre-existing union leak, recorded as a
     * limitation in the spec and not closed here.
     * <p>
     * <b>{@link PrincipalEnvironment#resolveEffectiveEnvironmentId(Long)} is deliberately NOT called</b>, unlike in
     * {@link #hasWorkflowScopeInEnvironment(String, String, Long)}. Substituting a confined principal's own
     * environment would authorise one environment while the guarded method's body, reading the raw argument, acts on
     * another — the exact divergence this gate exists to remove. The substitution is right for a workflow run, which
     * happens in the principal's environment whatever the request said; a listing returns what the argument names.
     * Nothing is lost: a confined principal has no {@code user} row, so the scope check fails closed regardless.
     */
    public boolean hasWorkspaceScopeInEnvironmentId(long workspaceId, String scope, @Nullable Long environmentId) {
        // Consulted for the same reason hasWorkflowScopeInEnvironment consults it, and with more at stake: no
        // resolver claims "Workspace", so a governed principal resolves NOT_APPLICABLE, which the decider turns into
        // DENY. That denial is what hasPermission gave these gates before they moved here, and dropping it would
        // open every workspace surface below to an embedded connected user.
        Outcome outcome = ResourceMembershipDecider.decide(
            resourceMembershipResolverProvider, workspaceId, "Workspace", scope);

        if (outcome == Outcome.DENY) {
            return false;
        }

        if (environmentId == null) {
            return hasWorkspaceScope(workspaceId, scope);
        }

        if (AutomationAuthorizationContext.isSkipChecks()) {
            return true;
        }

        Environment[] environments = Environment.values();

        if (environmentId < 0 || environmentId >= environments.length) {
            return false;
        }

        return permissionService.hasWorkspaceScope(workspaceId, scope, environments[environmentId.intValue()]);
    }
```

If `hasWorkspaceScope(long, String)` is not already a method on this expression root, call `permissionService.hasWorkspaceScope(workspaceId, scope)` behind the same skip check the sibling expressions use, and say in your report which you did. If `ResourceMembershipDecider.decide` does not accept a `long` id for a `"Workspace"` type, report the real signature rather than forcing a cast.

- [ ] **Step 5: Run the tests, then prove each can fail**

Invert one assertion per test, confirm each goes red on its own method, revert, confirm green. Record both runs.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server/libs/automation/automation-configuration/automation-configuration-service
git commit -m "732 Add the caller-supplied-ordinal workspace environment gate"
```

---

### Task 2: The five writes

Writes first: a forged environment on a write *places* data in an environment the caller may hold no role in.

**Files:**
- Modify: `WorkspaceApiKeyFacadeImpl#create` — **site 20, the credential-minting one**
- Modify: `WorkspaceDataTableFacadeImpl#createTable`
- Modify: `WorkspaceKnowledgeBaseFacadeImpl#createWorkspaceKnowledgeBase`
- Modify: `WorkspaceMcpServerFacadeImpl#createWorkspaceMcpServer` (both overloads)
- Test: the `*AuthorizationTest` beside each facade — `WorkspaceKnowledgeBaseFacadeAuthorizationTest` already exists and its `KNOWLEDGE_BASE_CREATE` pin at ~line 71 **will go red**; update it rather than loosening it

- [ ] **Step 1: Site 20 — read before writing**

`WorkspaceApiKeyFacadeImpl#create(long workspaceId, ApiKey apiKey)` takes the environment **inside the object**; `WorkspaceApiKeyGraphQlController` sets it from an `@Argument Long environmentId`. A DEVELOPMENT-only member can mint a PRODUCTION API key today.

Read both classes and choose ONE, then say which and why in your report:

- **(a)** gate on the object's field: `hasWorkspaceScopeInEnvironmentId(#workspaceId, '<SCOPE>', #apiKey.environment?.ordinal())` — check what `ApiKey`'s accessor actually returns and whether SpEL can reach it; a null-safe navigation must not silently become the null branch when the object *does* carry an environment.
- **(b)** gate at the GraphQL controller, where `environmentId` is a plain argument.

Prefer **(b)** if the controller is the only caller that supplies an environment, because it keeps the expression trivially readable. Verify the facade has no other caller that bypasses the controller before choosing it — if one exists, (a) is required.

- [ ] **Step 2: Re-point the two `long environmentId` writes**

```java
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'DATA_TABLE_CREATE', #environmentId)")
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'KNOWLEDGE_BASE_CREATE', #environmentId)")
```

- [ ] **Step 3: Re-point the two `Environment` writes to the PRE-EXISTING expression**

Both `createWorkspaceMcpServer` overloads hold a resolved `Environment`, so they take the three-argument form, not Task 1's:

```java
    @PreAuthorize("hasWorkspaceScopeInEnvironment(#workspaceId, 'MCP_CREATE', #environment)")
```

Confirm each parameter's real name before writing `#environment` — SpEL binds by name and a mismatch throws at evaluation time, not compile time.

- [ ] **Step 4: Update the pins beside each facade, and add one per newly-gated method**

Pins live beside the facade they guard, **not** in `PreAuthorizeAnnotationTest` — that class's module does not depend on most of these modules.

- [ ] **Step 5: Run the affected modules' tests with `--continue`, then commit**

```bash
git commit -m "732 Gate the workspace creates on the caller's environment"
```

---

### Task 3: The variable controller's four gates

Kept at the controller by spec D2.

**Files:**
- Modify: `server/ee/libs/platform/platform-variable/platform-variable-graphql/src/main/java/com/bytechef/ee/platform/variable/web/graphql/WorkspaceVariableGraphQlController.java`
- Test: `WorkspaceVariableGraphQlControllerTest` — its pins at ~lines 48 and 65 **will go red**; update them

- [ ] **Step 1: Re-point all four**

```java
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'VARIABLE_VIEW', #environmentId)")     // workspaceVariables
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'VARIABLE_MANAGE', #environmentId)")   // create
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'VARIABLE_MANAGE', #environmentId)")   // update
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'VARIABLE_MANAGE', #environmentId)")   // delete
```

All four take `@Argument long environmentId` — primitive, never null — so R1's null branch is unreachable here.

- [ ] **Step 2: Verify this module can resolve the expression**

`platform-variable-graphql` is EE and may not carry `automation-configuration-service`, where the expression root lives. **Check its `build.gradle.kts` and the app modules that host it before assuming the annotation works.** If it cannot resolve, report it — this is the same class of problem as Task 6 and must not be papered over.

- [ ] **Step 3: Record the deferred two-door decision in the class javadoc**

One sentence: the two-door split the asset-file work adopted was considered and deferred (spec D2), because this service's runtime caller only reads and is fail-open by design.

- [ ] **Step 4: Update the pins, run the module's tests, commit**

---

### Task 4: The six non-nullable reads

**Files:** `ProjectDeploymentFacadeImpl` (`getWorkspaceChatWorkflows`, 4-arg `getWorkspaceProjectDeployments`), `WorkspaceApiKeyFacadeImpl#getApiKeys`, `AiAgentFacadeImpl#getWorkspaceChatAgents`, `WorkspaceDataTableFacadeImpl#listTables`, `WorkspaceKnowledgeBaseFacadeImpl#getWorkspaceKnowledgeBases`

- [ ] **Step 1: Re-point all six**

```java
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, '<SCOPE>', #environmentId)")
```

Scopes in table order: `WORKFLOW_VIEW`, `DEPLOYMENT_VIEW`, `API_KEY_VIEW`, `AGENT_VIEW`, `DATA_TABLE_VIEW`, `KNOWLEDGE_BASE_VIEW`. Site 14 takes `Long workspaceId` with a primitive `long environmentId` — check both names.

- [ ] **Step 2: `AiAgentFacadeAuthorizationTest` needs care**

Around lines 158-192 it asserts `startsWith("hasPermission(#workspaceId, 'Workspace', ")` as a tripwire against ungated methods. **Do not simply loosen it.** Widen it to accept either the `hasPermission` form or `hasWorkspaceScopeInEnvironmentId`, so it still fails for a method with no gate at all. Say in your report what the assertion became.

- [ ] **Step 3: Update every red pin**

Known: `WorkspaceKnowledgeBaseFacadeAuthorizationTest` (~line 37). Find the rest by running the affected modules' tests and reading the failures — do not guess.

- [ ] **Step 4: Run, commit**

---

### Task 5: The two nullable listings that build locally

**Files:** `ProjectDeploymentFacadeImpl` (5-arg `getWorkspaceProjectDeployments`, first parameter named `id`), `WorkspaceConnectionFacadeImpl#getConnections`

Site 17 is deliberately **not** here — see Task 6.

- [ ] **Step 1: Re-point, minding the parameter names**

```java
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#id, 'DEPLOYMENT_VIEW', #environmentId)")
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'CONNECTION_VIEW', #environmentId)")
```

- [ ] **Step 2: Add a null-passes test at each site**

R1 says a null keeps today's behaviour. Assert it: a member with the scope in DEVELOPMENT only, calling with `environmentId = null`, is **allowed** — and the same member naming PRODUCTION is **denied**. The pair is what proves R1 landed rather than a null accidentally denying.

- [ ] **Step 3: Close the reproduction**

`ProjectDeploymentCrossEnvironmentReadReproductionIntTest` exercises site 15 and currently asserts the **vulnerable** behaviour with a comment saying so (~line 169). Flip it to assert denial and remove the comment. **This is the task's acceptance criterion.** Report its before/after output. Also update the stale javadoc reference in `PermissionServiceCrossEnvironmentUnionGapTest` (~line 59).

- [ ] **Step 4: Update `WorkspaceConnectionFacadeResourceAuthorizationTest` (~line 59), run, commit**

---

### Task 6: Site 17, and the module-reachability question

`ProjectWorkflowExecutionFacadeImpl` lives in `automation-workflow-execution-service`. `execution-app` carries that module but only `automation-configuration-remote-client` — **not** `automation-configuration-service`, where `AutomationMethodSecurityExpressionRoot` lives. A custom expression method that cannot resolve turns a 403 into a 500.

- [ ] **Step 1: Establish what resolves there today, before changing anything**

Answer, with evidence, and write it in the report:

1. Does `execution-app` register the custom `MethodSecurityExpressionHandler` at all? Find where it is registered and which module provides it.
2. Does `hasPermission(#workspaceId, 'Workspace', 'EXECUTION_VIEW')` currently resolve on `execution-app` — i.e. is there an `AutomationPermissionEvaluator` bean there?

**If neither resolves today, site 17's gate is already inert on `execution-app`.** That is a separate and larger finding than this plan's subject: report it, do not fix it here, and leave site 17's annotation alone pending a decision.

- [ ] **Step 2: Only if the expression root does resolve there, re-point site 17**

```java
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'EXECUTION_VIEW', #environmentId)")
```

Then add the null-passes / named-environment-denies pair as in Task 5, and update `ProjectWorkflowExecutionFacadeAuthorizationTest` (~line 46).

- [ ] **Step 3: If it does not resolve, stop and report**

Do not add a module dependency to `execution-app` on your own judgement — that changes a distributed deployment's classpath and is the maintainer's call. Record the finding, leave site 17 in the coverage test's exempt list with this reason, and move on.

- [ ] **Step 4: Commit whatever the outcome, including the report**

---

### Task 7: The two promotion handlers

R3 governs. These use the **pre-existing** `Environment` expression and do not depend on Task 1.

**Files:** `server/ee/libs/automation/automation-promotion/automation-promotion-service/src/main/java/com/bytechef/ee/automation/promotion/handler/McpServerPromotionHandler.java`; test `PromotionHandlerAuthorizationTest` (pins at ~lines 92 and 99 **will go red**)

- [ ] **Step 1: Re-point both, keeping the resolved-workspace bean call**

```java
    @PreAuthorize(
        "hasWorkspaceScopeInEnvironment(@promotionAuthorizer.workspaceIdOfMcpServer(#sourceId), 'MCP_CREATE', " +
            "#targetEnvironment)")
```

- [ ] **Step 2: State R3's boundary in each method's javadoc**

The gate authorises the **target** environment, the one the promotion writes into; whether the caller must also hold the scope in the source is deliberately not decided here.

- [ ] **Step 3: Confirm the `EnvironmentContext` trap does not bite**

The expression's javadoc warns the environment must come from the guarded method's arguments, "never from `EnvironmentContext`, which holds the source environment during a promotion". Promotion is precisely that case. Confirm by reading that neither handler reaches `EnvironmentContext` for this decision; say so in the report.

- [ ] **Step 4: Update the two pins, run the module's tests, commit**

---

### Task 8: The two coverage tests

**Files:**
- Create: `EnvironmentAwareGateCoverageTest` and `ResourceEnvironmentResolverCoverageTest`, both in a module that can actually see the sources they scan

- [ ] **Step 1: Choose the module by what it can reach, not by convenience**

The review found `GuardrailSurfaceCoverageTest`'s own javadoc records that a negative control run from a *different* module reports green — Gradle will not rebuild what the test does not depend on. Put each coverage test where it can see the code it scans, or make it scan source **files** by path rather than classes. Say which approach you chose.

> **Amended 2026-09-06, after Task 7's sweep.** The criterion below is not hypothetical caution — it
> was validated by finding sites the plan's own enumeration missed. The scan filtered on annotations
> containing `'Workspace'`; the sweep found the same defect keyed on other resource types:
>
> - **4 `'Project'` sites** — `ProjectDeploymentPromotionHandler#preview/#promote` and
>   `ApiCollectionPromotionHandler#preview/#promote`. Confirmed vulnerable end to end: no
>   `ResourceEnvironmentResolver` for `"Project"` (pinned deliberately by
>   `ResourceEnvironmentResolverProjectGuardTest`), so the gate falls to the union overload while both
>   bodies write into `targetEnvironment`.
> - **~20 `'DataTable'` sites** in `WorkspaceDataTableFacadeImpl` — fully unmitigated.
> - **~30 `'Workflow'` sites** across eight platform-configuration facades — mitigated only for
>   api-key principals, since `PrincipalEnvironment#resolveEffectiveEnvironmentId` returns empty for an
>   ordinary session member.
>
> **These are NOT this plan's to fix** — that is a scope change for the maintainer. They go in
> `KNOWN_EXEMPT` with their reasons, so the test is a live register of what is open rather than a
> claim that nothing is.

- [ ] **Step 2: Write the gate coverage test — keyed on the method, not the annotation**

Scan production sources for methods that declare an `environmentId` or `Environment` parameter **or take an object carrying one** (site 20 is why). Fail when such a method has a `@PreAuthorize` that is not environment-aware, **and** when it has no `@PreAuthorize` at all. Restricting the scan to expressions containing `'Workspace'` would let an ungated method pass, which is the hole this test exists to close.

Maintain a `KNOWN_EXEMPT` set with a reason string per entry; seed it empty except for site 17 if Task 6 left it unchanged.

- [ ] **Step 3: Negative-control it in both directions, in the same module**

Revert one of Task 4's annotations; the test must fail and name that method. Restore; green. **A coverage test not watched to fail is not evidence.** Record both runs and confirm the failing run really executed (`--rerun-tasks`).

- [ ] **Step 4: Write the resolver-registry coverage test**

Enumerate `ResourceEnvironmentResolver` implementations and the `resourceType()` each claims — today `ProjectDeployment`, `Connection`, `McpServer`. Fail for a type reachable through `hasResourceScope` with no environment resolver that is not in `KNOWN_NO_ENVIRONMENT` with a reason. `"Workspace"` goes in that set: a workspace has no environment of its own, so no resolver could supply one, and the argument-supplied gate is what covers it.

- [ ] **Step 5: Negative-control it too, then commit**

---

### Task 9: Whole-tree verification and the release note

- [ ] **Step 1: Whole-tree compile**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/t9-compile.log 2>&1; echo $?
grep '^> Task .* FAILED' /tmp/t9-compile.log
```

- [ ] **Step 2: Run every touched module's tests with `--rerun-tasks`, tallying from the JUnit XML**

- [ ] **Step 3: Re-run the enumerating scan**

Expected: every remaining row is either site 17 (if Task 6 deferred it) or nothing. Report the actual list, not a claim of zero.

- [ ] **Step 4: Write the release note in `.agents/resource-visibility.md`**

Record plainly: **this denies access that works today** — a member with a role in one environment and not another currently sees both and afterwards sees one, wherever the environment is named. State that an unfiltered listing is unchanged and still returns the union, and that closing it has its own ticket. CE is unaffected.

- [ ] **Step 5: Commit**

# Environment-Scoped Authorization, Remaining Families — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the environment-scoped authorization defect on the three resource-type families the predecessor's enumeration missed, then close the union leak on unfiltered listings.

**Architecture:** One new general expression rather than one per family. `PermissionServiceImpl#hasResourceScope` performs five checks before it reaches the environment question; only the sixth differs. The fix is that method with the caller's environment substituted for the resolver's lookup, exposed through the existing custom `MethodSecurityExpressionRoot`. `'Workflow'` re-points to `hasWorkflowScopeInEnvironment`, which already exists.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring Security method security (custom `MethodSecurityExpressionRoot`), Gradle 9.7, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-06-environment-scoped-authorization-remaining-families-design.md` — approved 2026-09-06, D3 **target only**, D4 **filter results**, D5 **fact-finding only**.

**Predecessor spec:** `docs/superpowers/specs/2026-09-03-environment-scoped-authorization-design.md`. Its **D1 amended** binds here unchanged.

## Global Constraints

- **The defect:** `hasResourceScope(id, type, scope)` resolves a `ResourceEnvironmentResolver` for `type` and, finding none, falls through to `hasWorkspaceScope(workspaceId, scope)` — which **unions the caller's scopes across every environment**. Exactly three types register a resolver: `Connection`, `ProjectDeployment`, `McpServer`.
- **Preserve all five preconditions.** Any new environment-aware path keeps: the `ResourceMembershipDecider` call *ahead of* the skip and tenant-admin bypasses; the skip bypass; the tenant-admin bypass; **`isResourceVisible` as a precondition**; and ownership resolution. Dropping visibility is silent — nothing fails when it is missing.
- **Consult `ResourceMembershipDecider` directly.** `hasResourceScope`'s own comment requires it: *"A new Environment-taking overload must do the same"*, because delegating would discard the explicit environment.
- **A null ordinal keeps today's union check.** Never deny on null, never require every environment. The clients routinely send none.
- **Never substitute the principal's environment** in the new expression. That would authorise one environment while the body acts on another. `hasWorkflowScopeInEnvironment` does substitute, and that is correct only for runs — see Task 1 Step 1.
- **Never widen a gate**, and never lose the connected-user denial.
- **CE is unaffected** — `hasWorkspaceScope` short-circuits on `isTenantAdmin()` and CE is admin-only.
- **The acceptance criterion for every family task is the exempt list shrinking.** Remove that family's entries from `EnvironmentAwareGateCoverageTest#KNOWN_EXEMPT` and the test must still pass. A task that gates the sites but leaves the entries is not done.
- Files under `server/ee/` carry the ByteChef Enterprise license header and a `@version ee` Javadoc tag; `server/libs/` carries Apache 2.0. For a new EE file write the Javadoc with its tag FIRST, then `spotlessApply`.
- `org.jspecify.annotations.Nullable`, never `org.springframework.lang.Nullable`.
- Checkstyle: test method names camelCase with no underscores; `TODO:` comments forbidden; no blank line before a class's closing brace; one blank line before control statements. No short or cryptic variable names.
- Run `./gradlew spotlessApply` before committing.
- `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce`.
- **Judge a Gradle run by `$?` on its own line plus `grep '^> Task .* FAILED'` on a redirected log file** — never a piped `tail` or `grep`. Use `--continue`; `--rerun-tasks` where the result matters. Tally from the JUnit XML and **label each module explicitly** — the CE and EE `automation-configuration-service` modules both happen to report 325 tests, and an earlier report swapped two modules' counts.
- Bash calls need `timeout: 600000`.
- Commit messages: `732 <description>`, ending with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Stage **by path**; never `git add -A` or `git add .`.
- **Never amend, never `git reset`, never `git stash`, never `git branch -f`** — the maintainer commits in parallel on this branch.

## The 58 sites

Enumerated by a multi-line-aware scan over `@PreAuthorize` annotations naming `'DataTable'`, `'Project'` or `'Workflow'` on public methods taking an `environmentId` or `Environment`. Locate by method name; line numbers drift.

**`'DataTable'` — 24, all in `WorkspaceDataTableFacadeImpl`** (Task 2)

`addColumn`, `dropTable`, `duplicateTable`, `removeColumn`, `renameColumn`, `renameTable`, `insertRow`×2, `updateRow`×2, `deleteRow`, `importCsv`, `upsertRow`, `deleteRowByExternalId`, `insertRows`, `deleteRows`, `clearRows` — `DATA_TABLE_EDIT`.
`listRows`×2, `exportCsv`, `listWebhooks`, `getTable`, `getRow`, `fetchRowByExternalId` — `DATA_TABLE_VIEW`.

`getDataTableTags` and `getTagsByTableId` carry `'Workspace'` gates and take **no** environment. Out of scope — do not touch.

**`'Project'` — 4** (Task 3)

`ProjectDeploymentPromotionHandler#preview`, `#promote`; `ApiCollectionPromotionHandler#preview`, `#promote`. All `DEPLOYMENT_PUSH`, all taking `Environment targetEnvironment`.

**`'Workflow'` — 30** (Tasks 4 and 5)

Task 4, the six `WorkflowNode*FacadeImpl` classes in `platform-configuration-service` — 21 sites:

| Class | Methods |
|---|---|
| `WorkflowNodeDescriptionFacadeImpl` | `getClusterElementWorkflowNodeDescription`, `getWorkflowNodeDescription` |
| `WorkflowNodeDynamicPropertiesFacadeImpl` | `getClusterElementDynamicProperties`, `getWorkflowNodeDynamicProperties` |
| `WorkflowNodeOptionFacadeImpl` | `getClusterElementNodeOptions`, `getWorkflowNodeOptions` |
| `WorkflowNodeOutputFacadeImpl` | `getClusterElementOutput`, `getWorkflowNodeOutput`, `getPreviousWorkflowNodeOutputs`, `getPreviousWorkflowNodeSampleOutputs`, `checkWorkflowCache` |
| `WorkflowNodeParameterFacadeImpl` | `deleteClusterElementParameter`, `deleteWorkflowNodeParameter`, `getClusterElementDisplayConditions`, `getWorkflowNodeDisplayConditions`, `updateClusterElementParameter`, `updateWorkflowNodeParameter` |
| `WorkflowNodeScriptFacadeImpl` | `getClusterElementScriptInput`, `getWorkflowNodeScriptInput`, `testClusterElementScript`, `testWorkflowNodeScript` |

Task 5, the nine remaining, which are **not** the same shape:

| Class | Methods | Why it differs |
|---|---|---|
| `WorkflowTestConfigurationFacadeImpl` | `deleteWorkflowTestConfigurationConnection`, `fetchWorkflowTestConfiguration`, `getWorkflowTestConfigurationConnections`, `saveClusterElementTestConfigurationConnection`, `saveWorkflowTestConfigurationConnection`, `saveWorkflowTestConfigurationInputs` | a sibling `saveWorkflowTestConfiguration` takes its environment **inside the request object** — check whether any of these six do too |
| `WorkflowNodeTestOutputServiceImpl` | `checkWorkflowNodeTestOutputExists` | a `ServiceImpl`, not a facade; its own comment already calls it *"an existence oracle across every environment"* |
| `WebhookTriggerTestApiFacadeImpl` | `enableTrigger`, `disableTrigger` | different module (`automation-configuration-service`), and these are **runs**, not reads |

---

### Task 1: The general expression, and the read-vs-run question

**Files:**
- Modify: `server/ee/libs/automation/automation-configuration/automation-configuration-service/src/main/java/com/bytechef/ee/automation/configuration/service/PermissionServiceImpl.java`
- Modify: its interface in `automation-configuration-api` (`PermissionService`)
- Modify: `server/libs/automation/automation-configuration/automation-configuration-service/src/main/java/com/bytechef/automation/configuration/security/AutomationMethodSecurityExpressionRoot.java`
- Test: the existing tests beside each

**Interfaces:**
- Produces: `PermissionService#hasResourceScopeInEnvironment(Serializable, String, String, Environment)` and the SpEL `hasResourceScopeInEnvironmentId(id, 'Type', 'SCOPE', environmentId)`. Tasks 2, 3 and 6 consume the second.
- Consumes: `ResourceMembershipDecider`, `isResourceVisible`, `resourceOwnershipResolvers`, `hasWorkspaceScope(long, String)` and `(long, String, Environment)`.

- [ ] **Step 1: Answer the read-vs-run question before writing anything**

Spec D2 leaves this open and Task 4 depends on it. `hasWorkflowScopeInEnvironment` calls `PrincipalEnvironment#resolveEffectiveEnvironmentId`, which substitutes the principal's own environment for an `AbstractApiKeyAuthenticationToken` and returns the requested one for a session principal.

Trace **one read site end to end** — `WorkflowNodeOutputFacadeImpl#getWorkflowNodeOutput` is the clearest — and answer: when an api-key principal reads a node output naming environment X while confined to Y, the gate authorises Y and the body reads… which? Read the body, do not infer.

- If gate and body agree, `hasWorkflowScopeInEnvironment` is right for reads and Task 4 uses it.
- If they diverge, reads take `hasResourceScopeInEnvironmentId(#workflowId, 'Workflow', …)` instead, and `hasWorkflowScopeInEnvironment` stays for runs alone.

**Record the answer in your report and in the expression's javadoc.** This is the one question that changes another task's shape.

- [ ] **Step 2: Write the failing tests**

On `PermissionServiceImpl`'s test, mirroring the existing `hasResourceScope` tests' fixture shape:

```java
@Test
void testAnEnvironmentAwareResourceScopeChecksTheNamedEnvironmentAlone() {
    // ownership resolves to workspace 1; caller holds the scope in DEVELOPMENT only
    assertThat(permissionService.hasResourceScopeInEnvironment(9L, "DataTable", "DATA_TABLE_EDIT", Environment.PRODUCTION)).isFalse();
    assertThat(permissionService.hasResourceScopeInEnvironment(9L, "DataTable", "DATA_TABLE_EDIT", Environment.DEVELOPMENT)).isTrue();
}

@Test
void testAnInvisibleResourceIsDeniedBeforeAnyScopeIsRead() {
    // the visibility precondition, which a bespoke per-family expression would lose silently
    assertThat(permissionService.hasResourceScopeInEnvironment(9L, "Connection", "CONNECTION_EDIT", Environment.DEVELOPMENT)).isFalse();
    verify(workspaceScopeCacheService, never()).getWorkspaceScopes(anyLong(), anyLong(), any(Environment.class));
}

@Test
void testAnUnresolvableOwnerIsDenied() { ... }

@Test
void testAGovernedPrincipalIsAnsweredFromMembershipAheadOfTheBypasses() { ... }
```

And on the expression root:

```java
@Test
void testANullEnvironmentIdKeepsTheEnvironmentUnawareResourceCheck() { ... }

@Test
void testAnOrdinalOutOfRangeDeniesRatherThanDefaulting() { ... }

@Test
void testAResolvableOrdinalChecksThatEnvironmentAlone() { ... }
```

Copy fixture shapes from the existing tests in each class rather than inventing them.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:automation:automation-configuration:automation-configuration-service:test :server:libs:automation:automation-configuration:automation-configuration-service:test --continue --rerun-tasks > /tmp/f1.log 2>&1; echo $?
grep '^> Task .* FAILED' /tmp/f1.log
```

Expected: FAILED (a compile error on the missing method is an acceptable first failure).

- [ ] **Step 4: Write `hasResourceScopeInEnvironment`**

Read `hasResourceScope` in full first and mirror it exactly, changing only the environment step. Its five preconditions are load-bearing; the javadoc must say why each is repeated rather than delegated.

- [ ] **Step 5: Write the expression-root wrapper**

```java
public boolean hasResourceScopeInEnvironmentId(
    Serializable id, String resourceType, String scope, @Nullable Long environmentId)
```

Same three branches as `hasWorkspaceScopeInEnvironmentId`: null → the environment-unaware `hasResourceScope`; out of range → deny; resolvable → `hasResourceScopeInEnvironment`. Do **not** call `PrincipalEnvironment`. Do not consult the decider here — `hasResourceScope` and its new sibling both do it themselves, and doing it twice would double-answer a governed principal.

- [ ] **Step 6: Run the tests, then prove each can fail**

Invert one assertion per test, confirm each goes red on its own method, revert, confirm green. Record both runs.

- [ ] **Step 7: Commit**

---

### Task 2: `'DataTable'` — 24 sites

The only fully unmitigated family, and the one where the environment reaches the data directly: `dataTableRef(dataTableId, environmentId)` builds a `DataTableRef` whose rows differ per environment, so an unchecked ordinal reads or writes another environment's rows under the same table id.

**Files:** `server/libs/automation/automation-data-table/automation-data-table-service/src/main/java/com/bytechef/automation/data/table/configuration/facade/WorkspaceDataTableFacadeImpl.java`; tests beside it.

- [ ] **Step 1: Re-point all 24**

```java
@PreAuthorize("hasResourceScopeInEnvironmentId(#dataTableId, 'DataTable', '<SCOPE>', #environmentId)")
```

Keep each method's existing scope constant — `DATA_TABLE_EDIT` or `DATA_TABLE_VIEW` as listed above. Verify each parameter name against its own signature; SpEL binds by name and a mismatch throws at evaluation time, not compile time. Several of these methods have overloads (`listRows`, `insertRow`, `updateRow` each appear twice) — disambiguate by arity, and change **both**.

- [ ] **Step 2: Add a discriminating test pair**

A member holding `DATA_TABLE_VIEW` in DEVELOPMENT only: naming DEVELOPMENT is allowed, naming PRODUCTION is denied, **same member fixture**. Model on `WorkspaceConnectionEnvironmentScopedReadRegressionIntTest` (real `@EnableMethodSecurity`, real `PermissionServiceImpl`, only the repository stubbed). One pair for the family is enough; pin the remaining 23 by exact-string annotation assertions.

- [ ] **Step 3: Update the existing pins**, found by running the module's tests and reading the failures. Do not guess.

- [ ] **Step 4: Drain the exempt list**

Remove every `WorkspaceDataTableFacadeImpl` entry from `EnvironmentAwareGateCoverageTest#KNOWN_EXEMPT` and confirm the test still passes. **This is the acceptance criterion.**

- [ ] **Step 5: Run, commit**

---

### Task 3: `'Project'` promotion — 4 sites

D3 is decided: **gate the target environment only.** The source-side question is unscheduled, not rejected.

**Files:** `ProjectDeploymentPromotionHandler`, `ApiCollectionPromotionHandler` (both in `automation-promotion-service`); `PromotionHandlerAuthorizationTest`.

- [ ] **Step 1: Re-point all four**

```java
@PreAuthorize(
    "hasResourceScopeInEnvironmentId(@promotionAuthorizer.projectIdOfProjectDeployment(#sourceId), 'Project', " +
        "'DEPLOYMENT_PUSH', #targetEnvironment.ordinal())")
```

`targetEnvironment` is a resolved `Environment`, not an ordinal. Decide and report which you used: `.ordinal()` in the expression, or a `PermissionService#hasResourceScopeInEnvironment` call through an `Environment`-taking expression-root wrapper. **Prefer the second** if it reads more clearly — an `.ordinal()` call inside a SpEL string is easy to mistype and impossible to check at compile time. If you add that wrapper, it is a Task 1-shaped addition: test it the same way.

Use `projectIdOfApiCollection` for the `ApiCollectionPromotionHandler` pair. Verify both bean method names.

- [ ] **Step 2: Record D3's boundary in each method's javadoc** — the gate authorises the target, the environment the promotion writes into; the source-side question is deliberately not decided here, and would need a different mechanism (keying on the deployment, which *does* have an environment resolver).

- [ ] **Step 3: Confirm the `EnvironmentContext` trap does not bite** — the environment must come from the method's own arguments, never from `EnvironmentContext`, which holds the **source** environment during a promotion. Read both bodies and say so.

- [ ] **Step 4: Update the two pins, drain the four exempt entries, run, commit**

---

### Task 4: `'Workflow'` — the six `WorkflowNode*FacadeImpl` classes, 21 sites

**Gated on Task 1 Step 1's answer.** If gate and body agree under the substituting expression, use `hasWorkflowScopeInEnvironment(#workflowId, '<SCOPE>', #environmentId)`. If they diverge, use `hasResourceScopeInEnvironmentId(#workflowId, 'Workflow', '<SCOPE>', #environmentId)`. Do not choose without that answer.

- [ ] **Step 1: Re-point all 21**, keeping each method's existing `WORKFLOW_VIEW` / `WORKFLOW_EDIT` scope.
- [ ] **Step 2: Add one discriminating pair** for the family, plus exact-string pins for the rest.
- [ ] **Step 3: Update existing pins; drain the exempt entries; run; commit.**

---

### Task 5: `'Workflow'` — the nine that differ, 9 sites

- [ ] **Step 1: `WorkflowTestConfigurationFacadeImpl`, 6 sites.** First check whether any of the six takes its environment **inside the request object** rather than as a parameter, the way its sibling `saveWorkflowTestConfiguration` does. If so, that one needs the object-field form and the coverage test's `ENVIRONMENT_CARRYING_PARAMETER_TYPES` may need the request type added — report before changing.
- [ ] **Step 2: `WorkflowNodeTestOutputServiceImpl#checkWorkflowNodeTestOutputExists`.** A `ServiceImpl`, not a facade — confirm method security actually applies to it (is it a Spring bean invoked across the proxy boundary?) before gating. Its own comment calls it *"an existence oracle across every environment"*; quote that comment in the commit message. If method security does not apply, report rather than adding an annotation that never fires.
- [ ] **Step 3: `WebhookTriggerTestApiFacadeImpl#enableTrigger`/`#disableTrigger`.** These are **runs**, so `hasWorkflowScopeInEnvironment` is right regardless of Task 1's answer. Confirm the body resolves the environment the same way the gate does.
- [ ] **Step 4: Drain the exempt entries, run, commit.**

---

### Task 6: `ApiCollectionFacadeImpl#getApiCollections` — the environment half

Its workspace gate landed in `19b8d00d90e`; `environmentId` is still unchecked.

- [ ] **Step 1:** Re-point to `hasWorkspaceScopeInEnvironmentId(#workspaceId, 'WORKSPACE_VIEW', #environmentId)` — a `'Workspace'`-keyed gate, so it takes the predecessor's expression, not Task 1's.
- [ ] **Step 2:** Update `ApiCollectionFacadeAuthorizationTest`'s pin; drain the exempt entry; run; commit.

---

### Task 7: D4 — per-environment result filtering on the three nullable listings

**Decided: filter results.** With no environment named, a listing returns rows from every environment in the workspace, including ones the caller holds no role in. The gate correctly permits the call; the body must narrow the result.

**Sites:** `ProjectDeploymentFacadeImpl#getWorkspaceProjectDeployments` (5-arg), `WorkspaceConnectionFacadeImpl#getConnections`, `ProjectWorkflowExecutionFacadeImpl#getWorkflowExecutions`.

- [ ] **Step 1: Establish how a body learns which environments the caller holds.** `PermissionService` can answer per environment; there may or may not be an existing "which environments" query. **Design this step and report it before implementing** — a new `PermissionService` method is likely, and it must not become a per-row lookup.
- [ ] **Step 2:** Filter each listing to the environments the caller holds the relevant scope in, when `environmentId` is null. When it is non-null the gate has already checked it and no filtering is needed.
- [ ] **Step 3:** A test per site: a member holding the scope in DEVELOPMENT only, calling with null, receives **only** DEVELOPMENT rows — not the union, and not empty.
- [ ] **Step 4:** Update `.agents/resource-visibility.md`'s release note, which currently records this leak as open.
- [ ] **Step 5:** Site 17 is deferred (Task 8), so it keeps its exempt entry; the other two lose theirs.

**As executed — three corrections to the steps above, recorded rather than silently absorbed:**

1. **Step 5 was written against a wrong premise.** The other two listings never had `KNOWN_EXEMPT`
   entries to lose: their *gates* were already environment-aware after Task 3, and `KNOWN_EXEMPT`
   registers gates. Only site 17 is exempt, and it stays exempt — its gate is unchanged. Its entry was
   amended instead, to say that the body now filters and the annotation still does not.
2. **Site 17 is filtered, not deferred.** The deferral was about the annotation on `execution-app`,
   which evaluates no `@PreAuthorize` at all. A body filter runs wherever the code runs, so on that
   deployment it is this listing's *only* protection — the deferral's own reasoning argues for
   filtering it, not against.
3. **A hole in the deferral's reasoning, found while acting on it.** It argues entirely about
   `execution-app`. `server-app` carries `automation-workflow-execution-service` *and*
   `security-config`, so the annotation **is** evaluated in the monolith, where it remains the
   environment-unaware `hasPermission(#workspaceId, 'Workspace', 'EXECUTION_VIEW')`. Left alone,
   because the deferral is a recorded decision and this is the maintainer's call — but recorded in
   `KNOWN_EXEMPT` and `.agents/resource-visibility.md` so it is not mistaken for settled.

**Two design points worth carrying forward:**

- The "which environments" query must go through `hasWorkspaceScope(workspaceId, scope, environment)`,
  never the caller's membership rows. That path falls back to the member's implicit row, so a member in
  implicit mode — the default — resolves in every environment and is not narrowed. Reading rows
  directly would see only the explicit ones and narrow such a member to **nothing**, emptying every
  listing for the common case.
- A permissive answer is `true` for a boolean check but **everything** for a set-valued filter. CE's
  `getMyWorkspaceScopes` returns the empty set and is right to; `getMyWorkspaceScopeEnvironments`
  returns all three and would blank every CE listing if it copied its neighbour.

---

### Task 8: D5 — is `execution-app` reachable? Fact-finding only

**Read-only. Change no code, no annotation, no dependency.** `execution-app` depends on neither `security-config` (which hosts the only production `@EnableMethodSecurity`) nor `automation-configuration-service`, so `@PreAuthorize` is never evaluated there.

- [ ] **Step 1:** Establish whether `execution-app`'s REST routes — it carries `automation-workflow-execution-rest`, `embedded-execution-public-rest`, `platform-workflow-execution-rest-impl` and others — are reachable by an ordinary authenticated caller, or sit behind the api-gateway with its own enforcement. Read the gateway's routing configuration, the Helm chart, and any ingress definition.
- [ ] **Step 2:** Establish what authentication, if any, `execution-app` applies to its own routes given it has no `security-config`.
- [ ] **Step 3:** Enumerate how many `@PreAuthorize` sites are reachable through modules on its classpath — the blast radius if the routes are exposed.
- [ ] **Step 4:** Report with evidence and a recommendation. **Do not act on it.**

---

### Task 9: Verification, exempt-list audit, and the release note

- [ ] **Step 1:** Whole-tree `compileJava compileTestJava --continue`.
- [ ] **Step 2:** Re-run every touched module with `--rerun-tasks`, deriving the module list from `git diff --name-only` over this plan's full range. Tally from the JUnit XML; label each module explicitly.
- [ ] **Step 3:** Read `EnvironmentAwareGateCoverageTest#KNOWN_EXEMPT` in full and confirm every remaining entry is genuinely still open. **Report the final list.** It should be materially shorter; anything still there needs a reason that survives reading.
- [ ] **Step 4:** Update `.agents/resource-visibility.md` — what closed, what remains, and what the union-leak paragraph now says after Task 7.
- [ ] **Step 5:** Commit.

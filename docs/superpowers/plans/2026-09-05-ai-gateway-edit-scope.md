# AI_GATEWAY_EDIT scope — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a workspace admin edit their own workspace's guardrail settings and custom rules, which today require tenant `ROLE_ADMIN` only because no edit scope exists to gate on.

**Architecture:** `AiGatewayPermissionScope` gains `AI_GATEWAY_EDIT`, mapped to `WorkspaceRole.ADMIN` by `AiGatewayPermissionScopeProvider` exactly as `VariablePermissionScopeProvider` maps `VARIABLE_MANAGE`. Five admin-only mutations in `platform-ai-guardrails-graphql` then gate on it in addition to `ROLE_ADMIN`. Nothing outside that module changes.

**Tech Stack:** Java 25, Spring Boot 4, Spring Security `@PreAuthorize` + SpEL, Spring for GraphQL, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-05-ai-gateway-edit-scope-design.md`

## Global Constraints

- **Scope is guardrails only** (spec D1, decided by the maintainer). Exactly five mutations, all in `platform-ai-guardrails-graphql`. The workspace system prompt is **excluded** — it shares the `AI_GATEWAY_VIEW` scope but is a different and more powerful feature, and its single mutation stays `ROLE_ADMIN`. The 54 AI-gateway sites and the eval/prompt/observability sites are likewise untouched.
- **`AI_GATEWAY_EDIT` is appended** to the enum, never inserted. `AiGatewayPermissionScope` implements `PermissionScopeType`, and this codebase's rule is that persisted enums are append-only.
- **`AI_GATEWAY_VIEW` keeps its `VIEWER` mapping.** This plan adds a scope; it changes none.
- **Every gate keeps its `hasAuthority('ROLE_ADMIN') or …` prefix.** A tenant admin must keep working everywhere they work today; this widens access, it never narrows it.
- **The settings mutation's gate must name every argument its body branches on** (spec §3). Its body dispatches on `scopeOf(input)`, not on `workspaceId` alone, so a gate keyed only on `#input.workspaceId` would authorize a different request than the one that runs.
- Files under `server/ee/` carry the **ByteChef Enterprise license header** and a `@version ee` Javadoc tag. Spotless applies the EE header only to files that already contain the literal `@version ee` and stamps Apache 2.0 on everything else — so for any new EE file, write the class Javadoc with its tag FIRST, then run `spotlessApply`.
- Use `org.jspecify.annotations.Nullable`, never `org.springframework.lang.Nullable` (deprecated since Spring 7.0).
- Run `./gradlew spotlessApply` before every commit.
- Judge a Gradle run by `$?` on its own line plus `grep '^> Task .* FAILED'` on a redirected log file — never by a piped `tail`, whose exit code is the filter's.
- Gradle needs `JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce`; shells here do not source SDKMAN. Bash calls need `timeout: 600000`.
- Commit messages: `732 <description>`.
- **Never amend, never `git reset`, never `git stash`, never `git branch -f`** — the maintainer commits in parallel and the stash stack is shared across worktrees.
- Stage **by path**, never `git add -A` or `git add .`.

---

### Task 1: The scope and its role mapping

**Files:**
- Modify: `server/ee/libs/automation/automation-configuration/automation-configuration-service/src/main/java/com/bytechef/ee/automation/configuration/security/scope/AiGatewayPermissionScope.java`
- Modify: `server/ee/libs/automation/automation-configuration/automation-configuration-service/src/main/java/com/bytechef/ee/automation/configuration/security/scope/AiGatewayPermissionScopeProvider.java`
- Test: `server/ee/libs/automation/automation-configuration/automation-configuration-service/src/test/java/com/bytechef/ee/automation/configuration/service/PermissionScopeRegistryTest.java` (extend)

**Interfaces:**
- Consumes: `PermissionScopeType`, `WorkspaceRole`, `ScopeDefinition` — all existing.
- Produces: `AiGatewayPermissionScope.AI_GATEWAY_EDIT`, resolvable through the registry at `WorkspaceRole.ADMIN`.

- [ ] **Step 1: Write the failing test**

Extend `PermissionScopeRegistryTest` with a test asserting the registry resolves `AI_GATEWAY_EDIT` at `ADMIN` and **not** at `VIEWER`. Model it on whatever that file already does for `VARIABLE_MANAGE` — read the existing tests first and match their shape rather than inventing one.

The `not at VIEWER` half is the load-bearing one: a scope accidentally mapped to `VIEWER` would hand every workspace member the ability to disable their workspace's guardrails, which is the opposite of this change's intent and would pass a one-directional test.

- [ ] **Step 2: Run it to verify it fails**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:automation:automation-configuration:automation-configuration-service:test --tests '*PermissionScopeRegistryTest' > /tmp/a1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/a1.log
```

Expected: FAIL — `AI_GATEWAY_EDIT` does not exist.

- [ ] **Step 3: Append the enum value**

```java
public enum AiGatewayPermissionScope implements PermissionScopeType {

    AI_GATEWAY_VIEW,
    AI_GATEWAY_EDIT
}
```

Appended, not inserted, per the Global Constraints.

- [ ] **Step 4: Map it in the provider**

```java
    @Override
    public Set<ScopeDefinition> scopeDefinitions() {
        return Set.of(
            new ScopeDefinition(AiGatewayPermissionScope.AI_GATEWAY_VIEW, WorkspaceRole.VIEWER),
            new ScopeDefinition(AiGatewayPermissionScope.AI_GATEWAY_EDIT, WorkspaceRole.ADMIN));
    }
```

This mirrors `VariablePermissionScopeProvider`'s `VARIABLE_VIEW`/`VARIABLE_MANAGE` pair exactly.

- [ ] **Step 5: Run it to verify it passes**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:automation:automation-configuration:automation-configuration-service:test --continue > /tmp/a1.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/a1.log
```

Expected: green, including `PermissionScopeProviderEditionGatingTest`, which enumerates providers and may assert on scope counts.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/automation/automation-configuration/automation-configuration-service
git commit -m "732 Add the AI_GATEWAY_EDIT permission scope at the workspace admin role"
```

---

### Task 2: Gate the four custom-rule mutations

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/java/com/bytechef/ee/platform/ai/guardrails/web/graphql/AiGuardrailCustomRuleGraphQlController.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/test/java/com/bytechef/ee/platform/ai/guardrails/web/graphql/AiGuardrailCustomRuleGraphQlControllerTest.java` (extend)

**Interfaces:**
- Consumes: `AI_GATEWAY_EDIT` (Task 1).
- Produces: nothing later tasks rely on.

- [ ] **Step 1: Write the failing tests**

The existing test class already exercises these mutations; read it first and follow its authorization-test shape. Add, for each of the four mutations:

- a workspace **admin** of the target workspace is allowed;
- a workspace **member** (viewer) of the target workspace is refused;
- a workspace admin of a **different** workspace is refused.

The third is what proves the gate is per-workspace rather than a blanket grant, and it is the one a `hasPermission` typo would silently pass.

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:test --tests '*AiGuardrailCustomRuleGraphQlControllerTest' > /tmp/a2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/a2.log
```

Expected: the workspace-admin-allowed cases FAIL (currently refused, since only `ROLE_ADMIN` passes). The two refusal cases pass already — they are regression guards, not the proof.

- [ ] **Step 3: Widen the four gates**

Three take `@Argument long workspaceId` and read identically:

```java
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasPermission(#workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')")
```

applied to `updateAiGuardrailCustomRulePattern`, `setAiGuardrailCustomRuleEnabled` and `deleteAiGuardrailCustomRule`.

`createAiGuardrailCustomRule` takes a record, so its gate reaches through it:

```java
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or "
        + "hasPermission(#input.workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')")
```

Leave the `aiGuardrailCustomRules` query alone — it already carries the `AI_GATEWAY_VIEW` form.

- [ ] **Step 4: Run them to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:test --continue > /tmp/a2.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/a2.log
```

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql
git commit -m "732 Let a workspace admin manage their workspace's guardrail custom rules"
```

---

### Task 3: Gate the settings mutation, mirroring the query's scope condition

**Files:**
- Modify: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/main/java/com/bytechef/ee/platform/ai/guardrails/web/graphql/AiGuardrailsWorkspaceSettingsGraphQlController.java`
- Test: `server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql/src/test/java/com/bytechef/ee/platform/ai/guardrails/web/graphql/AiGuardrailsWorkspaceSettingsGraphQlControllerTest.java` (extend)

**Interfaces:**
- Consumes: `AI_GATEWAY_EDIT` (Task 1).
- Produces: nothing.

This task is separate from Task 2 because its gate is not the same shape, and treating it as a fifth copy of Task 2's edit is precisely the mistake to avoid.

- [ ] **Step 1: Write the failing tests**

Alongside the allowed/refused/other-workspace trio from Task 2, add **the escalation test**:

A workspace admin of workspace 7 sends `updateAiGuardrailsWorkspaceSettings` with `workspaceId: 7` **and** `scope: EMBEDDED`, and is **refused by the authorization gate**.

Assert on the authorization failure specifically, not merely that the call throws. The body's `validateScopeWorkspaceIdPairing` would also reject this input, so a test that accepts any exception passes whether or not the gate is correct — and the gate is the thing under test. If the harness makes the two indistinguishable, assert instead that the service was never called, and say so in a comment.

- [ ] **Step 2: Run them to verify they fail**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:test --tests '*AiGuardrailsWorkspaceSettingsGraphQlControllerTest' > /tmp/a3.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/a3.log
```

- [ ] **Step 3: Widen the gate, mirroring the query's**

```java
    @MutationMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or (#input.scope != "
        + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).EMBEDDED "
        + "&& #input.workspaceId != null "
        + "&& hasPermission(#input.workspaceId, 'Workspace', 'AI_GATEWAY_EDIT'))")
```

Add a Javadoc on the mutation pointing at the query's, which already explains why the `scope` condition is load-bearing rather than defensive: this gate keys on the workspace id while the body dispatches on `scopeOf(input)`, so any argument the body branches on has to appear here too.

Verify the four input shapes agree with `scopeOf`:

| `scope` | `workspaceId` | gate | `scopeOf` resolves | agree? |
|---|---|---|---|---|
| null | 7 | passes (`null != EMBEDDED` is true in SpEL) | `WORKSPACE` | yes |
| null | null | fails the non-null test → `ROLE_ADMIN` | `PLATFORM` | yes |
| `WORKSPACE` | 7 | passes | `WORKSPACE` | yes |
| `EMBEDDED` | anything | fails → `ROLE_ADMIN` | `EMBEDDED` | yes |

- [ ] **Step 4: Run them to verify they pass**

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew :server:ee:libs:platform:platform-ai:platform-ai-guardrails:platform-ai-guardrails-graphql:test --continue > /tmp/a3.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/a3.log
```

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add server/ee/libs/platform/platform-ai/platform-ai-guardrails/platform-ai-guardrails-graphql
git commit -m "732 Let a workspace admin edit their workspace's guardrail settings"
```

---

### Task 4: Record the scope and what it deliberately excludes

**Files:**
- Modify: `.agents/ai-guardrails.md`
- Modify: `docs/superpowers/specs/2026-09-05-ai-gateway-edit-scope-design.md` (status line only)

**Interfaces:**
- Consumes: everything above. Produces nothing.

- [ ] **Step 1: Document the scope**

Add a short subsection covering:

- `AI_GATEWAY_VIEW` → `VIEWER`, `AI_GATEWAY_EDIT` → `ADMIN`, both on the workspace role.
- Which five mutations `AI_GATEWAY_EDIT` gates.
- **What it deliberately does not gate, and why** — the 54 AI-gateway sites (provider credentials, routing policy, model catalog), the eval/prompt/observability sites, and the workspace system prompt. Say that these stay `ROLE_ADMIN` by decision, not by omission, so nobody "finishes the job" without a new decision. This is the part most worth writing down.
- The settings mutation's mirrored gate, and the rule it follows: any argument the body branches on must appear in the gate.

- [ ] **Step 2: Update the spec status line**

Record the design as implemented, keeping the D1 attribution and the plan pointer.

- [ ] **Step 3: Commit**

```bash
git add .agents/ai-guardrails.md docs/superpowers/specs/2026-09-05-ai-gateway-edit-scope-design.md
git commit -m "732 docs - Record the AI_GATEWAY_EDIT scope and what it deliberately excludes"
```

---

## Final verification

- [ ] Whole tree compiles:

```bash
JAVA_HOME=/Volumes/Data/Users/ivicac2/.sdkman/candidates/java/25.2.4-graalce ./gradlew compileJava compileTestJava --continue > /tmp/afinal.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' /tmp/afinal.log
```

- [ ] No surface outside D1 gained the scope:

```bash
grep -rn "AI_GATEWAY_EDIT" server --include='*.java' | grep -v '/build/' | grep -v '/src/test/'
```

Expected: the enum, the provider, and the five mutations in `platform-ai-guardrails-graphql`. Anything else is a leak.

- [ ] `./gradlew spotlessApply` re-run after any late edit — formatters run first in CI.

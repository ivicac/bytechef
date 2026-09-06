# AI_GATEWAY_EDIT scope — design

**Status:** **Implemented, 2026-09-05.** D1 decided by the maintainer, 2026-09-05: guardrails only.
Plan at `docs/superpowers/plans/2026-09-05-ai-gateway-edit-scope.md`.
**Ticket:** wants its own ticket
**Date:** 2026-09-05
**Relates to:** `2026-09-02-guardrails-settings-scope-design.md` (introduced `AI_GATEWAY_VIEW`), the workspace membership/roles work

## 1. The problem

`AiGatewayPermissionScope` has exactly one value:

```java
public enum AiGatewayPermissionScope implements PermissionScopeType {

    AI_GATEWAY_VIEW
}
```

There is no edit counterpart, so **every mutation in the AI-gateway family is gated on tenant `ROLE_ADMIN`** —
not because anyone decided a workspace admin shouldn't perform them, but because there is no scope to gate
on. A workspace admin can *read* every one of these surfaces (`AI_GATEWAY_VIEW` maps to `VIEWER`) and
*change* none of them.

Counted across production code, under both spellings the codebase uses for the tenant-admin authority
(`hasAuthority('ROLE_ADMIN')` and `AuthorityConstants.ADMIN`):

| Module | Admin-only guards |
|---|---|
| `automation-ai-gateway-service` | 54 |
| `automation-ai-eval-service` | 9 |
| `platform-ai-guardrails-graphql` | 8 |
| `automation-ai-prompt-service` | 8 |
| `automation-ai-observability-service` | 3 |
| `platform-ai-workspace-prompt-graphql` | 2 |
| `automation-ai-eval-experiment-graphql` | 1 |
| **Total** | **85** |

Every sibling domain has both halves. The dominant naming is `_EDIT` — `AGENT_EDIT`, `CONNECTION_EDIT`,
`DATA_TABLE_EDIT`, `DEPLOYMENT_EDIT`, `KNOWLEDGE_BASE_EDIT`, `MCP_EDIT`, `WORKFLOW_EDIT` — with
`VARIABLE_MANAGE` and `WORKSPACE_MANAGE` the minority. So the name is `AI_GATEWAY_EDIT`.

## 2. Why this is not a small change

The mechanism is trivial: one enum value, one `ScopeDefinition` mapping it to `WorkspaceRole.ADMIN`, and a
`@PreAuthorize` edit at each site. `VariablePermissionScopeProvider` is the two-line model.

The **decision** is not trivial, and it is the whole of this document. Applying the scope *grants*
capability that nobody has today. Each of the 85 sites is a question of the form "should a workspace admin
be able to do this, or is this deliberately tenant-wide?" — and the answers plainly differ:

- **Guardrail settings and custom rules** are already workspace-scoped in their data model. A workspace
  admin editing their own workspace's guardrails is the obviously intended shape, and the current
  admin-only gate is the accident.
- **Gateway routing policy, provider credentials, model catalog** decide which upstream provider a
  tenant's traffic goes to and on whose API key. A workspace admin changing those may be exactly what a
  tenant does *not* want, and 54 of the 85 sites live here.
- **Observability export jobs and webhook subscriptions** send tenant data to an external endpoint. That
  is arguably a tenant-level trust decision regardless of workspace role.

Guessing across 85 sites would silently widen authorization on surfaces nobody reviewed. That is why this
spec stops at D1.

## 3. The one authorization trap, already documented in this codebase

`AiGuardrailsWorkspaceSettingsGraphQlController`'s **query** carries this javadoc, and it is the single most
important thing to carry into any edit gate here:

> The `scope` condition is load-bearing, not defensive. This gate keys on `workspaceId` while the body below
> dispatches on `scope`, so without it a caller passing their *own* workspace id together with
> `scope: EMBEDDED` satisfied the membership check and was then handed the tenant-wide embedded row — a row
> whose writer requires `ROLE_ADMIN`. **Any argument the body branches on has to appear here too, or the
> gate is authorizing a different request than the one that runs.**

`updateAiGuardrailsWorkspaceSettings` has exactly that shape: the gate would key on
`#input.workspaceId` while the body dispatches on `scopeOf(input)`. A naive
`hasPermission(#input.workspaceId, 'Workspace', 'AI_GATEWAY_EDIT')` therefore lets a workspace admin
attempt a write to the **tenant-wide embedded row** by sending their own workspace id with
`scope: EMBEDDED`.

Today that specific attempt is stopped — but by accident of ordering, not by the gate:
`validateScopeWorkspaceIdPairing` runs in the body and rejects `EMBEDDED` paired with a non-null
`workspaceId`. Relying on a body-side validation to backstop an authorization gate is precisely the
anti-pattern the query's javadoc warns about, and it breaks the moment someone relaxes the pairing rule.

**The edit gate must mirror the query's**, naming every argument the body branches on:

```java
@PreAuthorize("hasAuthority('ROLE_ADMIN') or (#input.scope != "
    + "T(com.bytechef.ee.platform.ai.guardrails.domain.AiGuardrailsSettingsScope).EMBEDDED "
    + "&& #input.workspaceId != null && hasPermission(#input.workspaceId, 'Workspace', 'AI_GATEWAY_EDIT'))")
```

Checked against all four input shapes: a null `scope` with a non-null `workspaceId` passes and resolves
`WORKSPACE` (correct — `null != EMBEDDED` is true in SpEL); a null `scope` with a null `workspaceId` fails
the non-null test and falls to `ROLE_ADMIN`, matching `scopeOf`'s `PLATFORM`; `EMBEDDED` fails whatever the
id; `WORKSPACE` with an id passes. All four agree with what the body does.

## 4. D1 — which surfaces does `AI_GATEWAY_EDIT` gate? **Maintainer decision required.**

Three defensible answers:

**(a) Guardrails only — 10 sites.** `platform-ai-guardrails-graphql` (8) and
`platform-ai-workspace-prompt-graphql` (2). These are workspace-scoped in their data model already, and
the guardrails settings page is the surface a workspace admin is most obviously meant to own. Smallest
blast radius; leaves 75 sites admin-only, which is today's behaviour and therefore not a regression.

**(b) Guardrails, eval and prompt — 28 sites.** Adds `automation-ai-eval-service` (9),
`automation-ai-prompt-service` (8) and `automation-ai-eval-experiment-graphql` (1). These are
workspace-authored content — rules, prompts, experiments — rather than tenant infrastructure.

**(c) Everything — 85 sites.** Includes gateway provider credentials, routing policy, the model catalog
and observability exports. Treats "AI gateway" as one workspace-administered domain.

**Recommendation: (a).** It fixes the case that prompted this — a workspace admin cannot currently edit
their own workspace's guardrail settings, which reads as a bug — without granting anything over provider
credentials or outbound data exports, where admin-only may well be deliberate. (b) and (c) can follow, per
subsystem, once someone has actually reviewed those surfaces. Widening authorization is easy to do later
and awkward to undo.

### D1 decided, 2026-09-05: (a), guardrails only — and narrower than (a) as first drafted

The maintainer chose **guardrails only**. Option (a) above was drafted as "10 sites", counting
`platform-ai-guardrails-graphql` (8) **and** `platform-ai-workspace-prompt-graphql` (2). Those counts were
of `PreAuthorize` occurrences, not of admin-only mutations, and they bundled a surface that is not
guardrails.

**The workspace system prompt is excluded.** It shares the `AI_GATEWAY_VIEW` scope but is a different
feature: a per-workspace prompt injected into every model call in that workspace. That is arguably a more
powerful control than any guardrail toggle — it shapes what the model does, rather than what is scrubbed
from what it sees — and "guardrails only" does not name it. Excluding it costs nothing: its single mutation
stays `ROLE_ADMIN`, which is today's behaviour, and it can be added later under its own decision.

Counting admin-**only** mutations rather than `PreAuthorize` occurrences, the actual scope is **five
mutations, all in `platform-ai-guardrails-graphql`**:

| Controller | Mutation | Workspace argument |
|---|---|---|
| `AiGuardrailCustomRuleGraphQlController` | `createAiGuardrailCustomRule` | `#input.workspaceId` |
| `AiGuardrailCustomRuleGraphQlController` | `updateAiGuardrailCustomRulePattern` | `#workspaceId` |
| `AiGuardrailCustomRuleGraphQlController` | `setAiGuardrailCustomRuleEnabled` | `#workspaceId` |
| `AiGuardrailCustomRuleGraphQlController` | `deleteAiGuardrailCustomRule` | `#workspaceId` |
| `AiGuardrailsWorkspaceSettingsGraphQlController` | `updateAiGuardrailsWorkspaceSettings` | §3's mirrored gate |

`AiGuardrailViolationGraphQlController` has no admin-only mutation — violations are query-only — so it is
untouched.

## 5. What changes (under any answer to D1)

- `AiGatewayPermissionScope` gains `AI_GATEWAY_EDIT`, **appended** — the enum implements
  `PermissionScopeType` and the codebase's rule is that persisted enums are append-only.
- `AiGatewayPermissionScopeProvider` gains
  `new ScopeDefinition(AiGatewayPermissionScope.AI_GATEWAY_EDIT, WorkspaceRole.ADMIN)`, mirroring
  `VariablePermissionScopeProvider`'s `VARIABLE_MANAGE`.
- Each in-scope mutation's `@PreAuthorize` becomes
  `hasAuthority('ROLE_ADMIN') or hasPermission(<the workspace argument>, 'Workspace', 'AI_GATEWAY_EDIT')`,
  with §3's mirroring rule applied wherever the body branches on more than the workspace id.
- Three of the four custom-rule mutations take `@Argument long workspaceId` directly;
  `createAiGuardrailCustomRule` takes a record, so its gate reads `#input.workspaceId`.
- `PermissionScopeProviderEditionGatingTest` and `PermissionScopeRegistryTest` cover the new value.

## 6. Testing

- A workspace admin can edit their own workspace's guardrail settings and custom rules; a workspace
  **member** cannot. This is the test that fails today and is the point of the change.
- A workspace admin of workspace A cannot edit workspace B's — the scope is per-workspace, not a blanket
  grant.
- **The §3 trap, explicitly:** a workspace admin sending their own `workspaceId` with `scope: EMBEDDED` is
  refused by the *gate*, not merely by the body's pairing validation. Assert on the authorization failure,
  and keep it passing if the pairing validation is removed.
- Every surface D1 leaves out stays `ROLE_ADMIN`-only — a test that the scope did not leak into them.

## 7. Non-goals

- Changing `AI_GATEWAY_VIEW`'s mapping. It stays `VIEWER`.
- A UI for assigning the scope; it flows through the existing workspace-role machinery.
- Any surface outside D1's answer.

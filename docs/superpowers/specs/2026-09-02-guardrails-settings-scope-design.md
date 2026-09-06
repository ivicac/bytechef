# Guardrails settings scope — design

**Status:** **Implemented.** All nine tasks executed and reviewed; see
`docs/superpowers/plans/2026-09-02-guardrails-settings-scope.md`. The status line said "approved, not
implemented" until 2026-09-05, when a reconciliation pass against the code found the work had long
since landed. One filed residual and one falsified claim are recorded at the end of this document.
**Ticket:** 732
**Date:** 2026-09-02

## Problem

The Guardrails settings page writes a workspace-scoped settings row. Several guardrail surfaces
never read that row, so their toggles have never done anything.

Verified chain for Copilot: `CopilotGuardrailsAdvisorFactory` → `DeferredGuardrailsAdvisor` calls
`getAdvisor(null, null, surface)` → `AiGuardrailsAdvisorProviderImpl.resolveWorkspaceId` returns
null whenever `platformType != AUTOMATION` → `findSettings(null)` → `scopeOf(null)` =
`Property.Scope.PLATFORM` — the tenant-default row. `AiGuardrailsWorkspaceSettingsGraphQlController`
is the only writer of any row, and the settings page always sends a non-null `workspaceId`, so the
PLATFORM row is only writable by hand-crafting a mutation with `workspaceId: null`.

| Surface | Reads | Settings page reaches it? |
|---|---|---|
| Canvas AI Agent (workflow run) | real workspace, via `jobPrincipalId` → project → workspace | yes |
| Automation MCP | real workspace, via `mcpServerId` → workspace | yes |
| AI Hub main agent | real workspace, from `VERIFIED_WORKSPACE_ID` | yes |
| **Copilot — all 52 attachment sites** | **tenant default** | **no** |
| **AI Hub delegation sub-agents** (through the Copilot factory) | **tenant default** | **no** |
| **Embedded MCP** | **tenant default** | **no** |

A workspace row does **not** inherit from the tenant-default row — `AiGuardrails#resolvePolicy`
unions a workspace's settings with the global `bytechef.ai.gateway.guardrails.*` properties only,
never with the PLATFORM row. So for the bottom three surfaces the application properties are the
sole working switch.

The fix is not merely to give the tenant-default row a UI. **Copilot is workspace-based** — a
Copilot session runs inside a workspace, the way AI Hub does — so reading a tenant-wide row was the
wrong model, not just an unwired one.

## Scope

**In:**

- Copilot and AI Hub delegation sub-agents resolve the workspace their session runs in.
- Copilot's workspace id is server-verified before anything scopes off it.
- Embedded MCP gets its own `Scope.EMBEDDED` settings row and a settings page.

**Out:**

- The canvas AI Agent and automation MCP — already correct.
- Any change to how a workspace row unions with the global properties.
- Inheritance from the PLATFORM row into a workspace row. Adding it would silently change what
  every already-configured workspace resolves to.

## 1. Verify the workspace first

This is a precondition, not a step that can follow. Scoping a security control by a client-supplied
workspace id is not a control: a user names a workspace whose guardrails are off and the guardrails
are off.

`CopilotConstants.STATE_WORKSPACE_ID` is read straight from the request state by
`CopilotToolContextUtils.toToolContext`. `CopilotApiController` never touches `workspaceId` —
verified by grep, no match in that file — so the value is whatever the client sent.

AI Hub already solves this and its solution is the model to copy:

- `AiHubApiController.enforceWorkspaceAccess(agUiParameters, userId)` reads the client-supplied id
  and enforces that the authenticated user is a member of that workspace. Its javadoc names the
  attack it exists to stop: a user setting `state.workspaceId=<victim_ws>` and having tool
  callbacks operate against a workspace they have no access to.
- `injectAuthenticatedContext` then writes `AiHubStateKeys.VERIFIED_WORKSPACE_ID` from the
  authenticated session, and **defensively overwrites the unverified `WORKSPACE_ID`** so a later
  regression that reads the wrong key still gets server-controlled data.

Copilot gains the same two steps: membership enforcement — landed on `CopilotChatFacadeImpl`, not
`CopilotApiController`, since the facade already owned the pre-existing (workflow-keyed) gate this
work replaces and is where the rest of the run-state handling already lives — and a new verified
state key that `CopilotToolContextUtils` and the guardrails resolution read **instead of**
`STATE_WORKSPACE_ID`. Follow AI Hub in also overwriting the unverified key.

### The wider question this raised — answered by Task 1's Step 0, before any other work started

That same unverified `STATE_WORKSPACE_ID` flowed through `CopilotToolContextUtils` into both
`AgentToolInvocationContext` and `AutomationToolInvocationContext`, and thence into workspace-scoped
Copilot tools (project deployments, asset files, data tables, knowledge base).

**Answer: yes, for at least one tool family, and the hole was live.** Authorization on Copilot's
workspace-scoped tools turned out to be per-tool, not per-surface. `ListProjectDeploymentsToolCallback`
lands on `ProjectDeploymentFacadeImpl.getWorkspaceProjectDeployments`, which carries
`@PreAuthorize("hasPermission(#id, 'Workspace', 'DEPLOYMENT_VIEW')")` — cross-workspace was already
denied there, decided by the rehydrated `Authentication` and `ResourceMembershipDecider` rather than by
the tool-context id, exactly as hoped. But `ListAssetFilesToolCallback` (and the write tools on the same
BUILD agent) called straight through to `AssetFileFacadeImpl`, which carries **zero** `@PreAuthorize`
annotations and authorizes nothing beyond a plain repository query keyed on `workspace_id`. So before
this work, a member of workspace A could read *and write* workspace B's asset files by posting
`{"workspaceId": B}` with `agentId=asset_file` to `POST /internal/ai/chat/{agentId}` — no membership in
B required. Cross-workspace within one tenant, not cross-tenant; a tenant admin passes every workspace
gate by design, so the finding is about non-admin members.

This was exactly the attack AI Hub's `enforceWorkspaceAccess` javadoc names as the reason it exists,
confirmed real on Copilot too, on the one tool family nothing above it happened to gate. §1's fix closes
the Copilot route to it: the verified workspace id now reaches every Copilot tool, including the
ungated asset-file ones. It does **not** gate `AssetFileFacadeImpl` itself, which remains reachable,
still with zero authorization, from the AI Hub and from the `asset-file` workflow component — both of
which supply their own workspace id with nothing above them to check it. See
`.superpowers/sdd/2026-09-02-guardrails-settings-scope/task-1-report.md` for the full chain with line
numbers, and `.agents/ai-guardrails.md`'s "Copilot workspace authorization" section for the durable
record of this finding and why closing `AssetFileFacadeImpl` itself is not a safe drive-by.

## 2. Copilot and AI Hub sub-agents resolve their workspace

`DeferredGuardrailsAdvisor.adviseCall`/`adviseStream` receive the `ChatClientRequest`. The
verified workspace is reachable from it without new plumbing: every `*SpringAIAgent` overrides
`toolContext(RunAgentInput)` → `CopilotToolContextUtils.toToolContext(input.state())`, which writes
`AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` into the map that becomes the prompt's
`ToolCallingChatOptions.getToolContext()`. That is the same channel `AiGuardrailsAdvisor` already
reads and writes for the `PiiTokenSession`.

So `DeferredGuardrailsAdvisor` reads the workspace off the prompt options and passes it down.

`AiGuardrailsAdvisorProvider` needs an entry point that accepts a known workspace.
`getAdvisor(@Nullable PlatformType, @Nullable Long jobPrincipalId, String surface)` *derives* the
workspace and has no way to express "I already know it". Add an overload or a distinct method
taking the workspace directly, and have the existing one keep deriving. **Do not overload on two
nullable reference types** — `getAdvisor(null, null, surface)` would become ambiguous at every
existing call site. Give the new entry point its own name.

`getMetrics` needs the same treatment, since `CopilotGuardrailsAdvisorFactory` resolves it for the
tool boundary through the same provider.

## 3. Embedded MCP gets a `Scope.EMBEDDED` row

Embedded is per-connected-user, not per-workspace, so it has no workspace to resolve.
`Property.Scope.EMBEDDED` already exists and embedded variables already use it —
`VariableServiceImpl` maps its embedded scope to `Property.Scope.EMBEDDED` with a null `scopeId`.
Guardrails follow the same precedent.

`AiGuardrailsWorkspaceSettings` currently carries a `Long workspaceId` and
`AiGuardrailsWorkspaceSettingsServiceImpl.scopeOf` derives the scope from it —
`workspaceId == null ? PLATFORM : WORKSPACE`. A `Long` cannot express "embedded", and overloading a
third meaning onto null is the defect this whole document is about.

The record therefore gains an explicit discriminator: a new `AiGuardrailsSettingsScope` enum
(`PLATFORM`, `WORKSPACE`, `EMBEDDED`) in the same `-api` package, with `workspaceId` non-null only
for `WORKSPACE`. `scopeOf` maps that enum to `Property.Scope` instead of inferring anything from
`workspaceId`.

A dedicated enum rather than reusing `Property.Scope` directly: that type is persistence-layer and
carries values (`AUTOMATION`, `PROJECT`, `INTEGRATION`) meaningless here, and a settings record in
an `-api` module should not take a dependency on the property store's shape. It is stored by
`name()`, as `blockingMode` already is in this same value map, so it is not ordinal-sensitive.

A settings page goes beside `api-keys` / `signing-keys` / `variables` under
`client/src/ee/pages/settings/embedded/`.

## 4. Fallback when no workspace resolves

Fall back to the PLATFORM row — today's behaviour, so no surface regresses — and pin with a test
that the verified key is always injected, so the fallback is unreachable in practice rather than a
silent hole.

Deliberately **not** fail-closed. Guardrails are off by default; failing closed on a missing key
would break Copilot entirely for any request shaped slightly differently, trading a silent
non-application for a loud total outage on a feature most tenants have switched off. The test that
the key is always present is what makes this safe, not the fallback itself.

## What becomes of the PLATFORM row

After this change it is read only when no workspace and no embedded scope resolves. It stays the
last-resort default and stays writable through the API. Giving it a UI is **not** in scope, and a
settings page for a row nothing normally reads would be its own kind of misleading.

> **Correction, 2026-09-05.** This section originally continued: "with Copilot workspace-scoped and
> embedded on its own row, no surface depends on it in normal operation." **That is false**, and the
> sentence propagated into `.agents/ai-guardrails.md` before being caught by the whole-branch review of
> the 2026-09-05 embedded-settings-scope work.
>
> The **embedded Copilot** depends on the PLATFORM row for every call. §2 above treated Copilot as
> uniformly workspace-based — "a Copilot session runs inside a workspace, the way AI Hub does" — which is
> true of the automation Copilot this spec fixed, and false of the embedded one.
> `EmbeddedCopilotConfiguration` attaches the guardrails advisors to every embedded Copilot agent, and
> that module carries no workspace id at all, so `getAdvisorForWorkspace(null, …)` resolves the
> tenant default. Reachable in production through `ConnectedUserCopilotApiController`.
>
> This is a case this spec did not consider, not a task it failed to execute. Closing it needs its own
> decision — either the SPI grows a `PlatformType`, or `embedded-ai-copilot` gets its own embedded-aware
> entry point — and that decision is not taken here. Recorded in `.agents/ai-guardrails.md` under the
> embedded-Copilot known gap.

## Filed residual — since closed

This spec's final review filed, rather than fixed, a data-loss residual: **neither settings page sent
`minConfidence`, and `saveSettings` replaces the whole value map**, so a page save silently cleared a
value set through the API. Declining to fix it as a drive-by was right — it needed the field added to
both the query selection and the mutation input, a `graphql-codegen` regeneration, and, per CLAUDE.md,
the operations and the generated file committed separately.

**It was closed in `07dfdfc0222`** ("client - Stop the guardrails pages erasing an API-set
minConfidence"). Both pages now carry the fetched value straight through to the save payload, the
operation selects it in query and mutation, and both pages carry a regression test named for the reason
it exists — *"preserves an API-set minConfidence across a save, since the page has no control for it"*.

Recorded here because the residual was filed in an ignored scratch ledger while its fix landed in a
different branch's commit group: neither half was discoverable from this document, so a later reader had
no way to tell an open data-loss bug from a closed one. That gap is the point of this note, not the bug.

## Testing

- Copilot rejects a request whose state names a workspace the authenticated user is not a member
  of — the AI Hub `enforceWorkspaceAccess` test is the model.
- The verified key, not the client-supplied one, is what reaches the tool context and the
  guardrails resolution. A test that passes a *different* client-supplied id and asserts the
  verified one wins.
- A Copilot request in a workspace with `redactPii` on resolves an advisor; the same request in a
  workspace with it off resolves none. This is the test that would have failed before this work and
  is the point of it.
- Embedded MCP reads its `Scope.EMBEDDED` row and is unaffected by any workspace row.
- A request with no verified workspace falls back to the PLATFORM row rather than throwing.
- The settings round-trip for the embedded scope, mirroring the existing workspace round-trip test.

## Consequence worth stating plainly

Once this lands, an admin who enables PII redaction on the Guardrails page changes Copilot's
behaviour for that workspace. Today it does not. Tenants currently relying on
`bytechef.ai.gateway.guardrails.*` properties are unaffected — those still union in — but tenants
who set workspace toggles expecting nothing to happen to Copilot will now get redaction there.
That is the intended fix, and it is a behaviour change on an existing surface, so it belongs in
release notes rather than being discovered.

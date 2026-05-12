# Asset-file authorization — design

**Status:** implemented
**Ticket:** 732 (follow-on; wants its own ticket)
**Date:** 2026-09-02

## Problem

`AssetFileFacadeImpl` carries **zero** `@PreAuthorize` annotations and performs no authorization of
any kind. It is the common sink of all three cross-workspace vulnerabilities found while implementing
the guardrails settings scope:

| # | Surface | What was trusted | Closed by |
|---|---|---|---|
| 1 | Copilot asset-file agents | `state.workspaceId`, unverified | `6129202` |
| 2 | Copilot workflow-execution agent | `state.parameters.workspaceId`, overwriting an already-verified value | `d1b2ad821e3` |
| 3 | Management MCP tool callbacks | the `workspaceId` tool argument, with an API key binding no workspace | `7b64ca2a683` |

Each was closed at its own surface. None closed the facade. A fourth surface is cheap to add, and the
per-surface pattern has now failed three times.

The gap is wider than workspace-scoping. Of the facade's 20 methods, **11 take a bare `id` and no
workspace at all** — `delete`, `rename`, `updateContent`, `restoreVersion`, `enablePublicLink`,
`createSignedDownloadToken`, `downloadContent`, `getVersions`, `updateDescription`, `findById` among
them. Any caller holding an id operates on it. `findByIdInWorkspace` is the single self-gating method.

A guard already exists at one layer: `AssetFileGraphQlAccessGuard` resolves the owning workspace and
checks the caller's membership. `AssetFileRestController` duplicates that logic privately. Neither
covers the component actions, the AI tool callbacks, or the GraphQL `assetFileTags` query, which has
no guard at all.

## Why the obvious fix does not work

**A `@PreAuthorize` on the facade would throw, not deny, on three caller families.** This corrects an
earlier ruling in `.superpowers/sdd/2026-09-02-guardrails-settings-scope/progress.md`, which held
that annotating the facade would deny on AI Hub and component paths. Half of that was wrong:

- Copilot and AI Hub **tool callbacks do carry a principal**. Every asset-file callback is wrapped in
  `RehydrateContextToolCallback` (`AssetFileAgentConfiguration:158`, `AiHubConfiguration:298,534`),
  which calls `SecurityUtils.runAs` with the `Authentication` captured in the tool context. A check
  there evaluates correctly.
- The **component actions** genuinely have none. `AssetFileContextResolver:31-32` states it: they run
  on workflow-execution workers with no user `SecurityContext`.
- The **AG-UI agent-turn threads** genuinely have none either, and this was not previously known.
  `LocalAgent.runAgent` hands the agent body to a bare `CompletableFuture.runAsync` with no executor,
  so it runs on a `ForkJoinPool.commonPool()` worker inheriting no thread-local
  (`AiHubAgentTenantBinder:21-27`). `AiHubAgentTenantBinder` binds **tenant only** — never an
  `Authentication`. Verified for `WebhookBridgeAgent:1221`; `AiHubRoutingAgent:221` extends the same
  `LocalAgent` and contains no `SecurityUtils`/`runAs`/`SecurityContext` reference.

On those paths `userService.getCurrentUser()` is `fetchCurrentUser().orElseThrow(UserNotFoundException::new)`,
so a membership check does not return false — it throws, breaking every workflow-chat attachment
upload.

**`@PreAuthorize` is also the wrong primitive.** In CE, `PermissionServiceImpl.hasWorkspaceScope`
returns `SecurityUtils.isAuthenticated()`, so a `hasPermission` token buys authentication only. Real
workspace isolation comes from the `getUserWorkspaces` membership test either way.

The runtime escape hatch is not an option either: `AutomationAuthorizationContext.isSkipChecks()` is
armed only by `SkipAutomationAuthorizationAspect`, on five embedded connected-user facades, none of
which reference `AssetFileFacade`.

## Decisions

1. **The facade guards itself**, rather than delegating authorization to an API facade above it. The
   repo's `*ApiFacade` / `*Facade` convention (`AiSkillApiFacade`) was considered and rejected: it
   leaves an unguarded facade reachable, and that reachability is exactly how all three
   vulnerabilities happened.
2. **Membership only** — no new permission scope. Asset files are the only workspace-scoped domain
   without one, but adding `ASSET_FILE_VIEW`/`ASSET_FILE_EDIT` buys nothing in CE and is a
   role-model decision that should not be coupled to a security fix. Filed separately.
3. **A separate system entry point** for callers with no principal, rather than an annotation-and-
   aspect bypass. An ambient thread-local is the mechanism that made the current situation hard to
   reason about.

## Architecture

Two interfaces, two implementations, two beans — and, importantly, **two different checks**. Neither
door is unchecked:

- **`AssetFileFacade` — membership.** Is the current user a member of the workspace that owns this
  file? This is exactly what `AssetFileGraphQlAccessGuard` already does.
- **`AssetFileSystemFacade` — ownership.** Does this file belong to the server-derived workspace the
  caller was handed? Every id-taking method on it takes `(id, workspaceId)` and self-gates, the shape
  `findByIdInWorkspace` already uses.

Ownership is not a new concept this design introduces. It is **described** by one column,
`AssetFile.workspaceId`, and **checked** by `findByIdInWorkspace` (`AssetFileFacadeImpl:326`), which
already has the four properties the system door needs: it throws on a null `workspaceId` rather than
degrading to unscoped; it refuses a file whose own `workspaceId` is null rather than treating it as a
wildcard; it returns the identical message for a cross-workspace id and an unknown one, so it cannot
be used to probe; and it compares the loaded row's column rather than relying on a repository filter
that could silently return nothing.

Nor are the `*InWorkspace` methods new machinery: each is `findByIdInWorkspace(id, workspaceId)`
followed by the operation, which is **exactly what the component actions already do by hand** —
`RenameAction:83,85`, `DeleteAction:83,85`, `DownloadAction:78,80` and `UpdateContentAction:92,100`
each call `findByIdInWorkspace` before invoking the bare-id mutator. This design takes a convention
four actions already follow voluntarily and makes it unskippable, so the fifth cannot forget.

The system facade therefore **substitutes** an authorization appropriate to a caller with no user; it
does not skip one. Its contract, stated once on the interface:

> The workspace must be server-derived, and the caller's access to it must have been verified upstream
> on a thread that had a principal.

Both AG-UI paths satisfy the second clause — `AiHubApiController.enforceWorkspaceAccess` plus
`enforceThreadOwnership` ran on the request thread, and the workspace comes from the chat row. The
component actions satisfy it structurally: `AssetFileContextResolver` derives the workspace from
job → project → workspace, never from caller input.

Three methods appear on both interfaces — `createFromUpload`, `findAllByWorkspaceIdAndEnvironment` and
`findByIdInWorkspace`. They are not duplicated logic: the guarded copy performs the membership check and
then delegates to the system copy, which performs the ownership check. A caller holding the guarded bean
therefore always gets both; a caller holding the system bean gets ownership only, which is the whole
distinction between the two doors.

**Two implementation classes, not one implementing both interfaces.** A single class implementing
both lets a caller cast from the guarded door to the system one, which would make the caller-set scan
decorative. The guarded impl delegates to the system impl after its membership check.

### Error shape

Preserved from the existing guard, where the distinction is deliberate:

- by-id failure → `AssetFileNotFoundException` (404-shaped), so a probe cannot confirm an id exists in
  another workspace;
- explicit-`workspaceId` failure → `AccessDeniedException`, because the caller asserted membership and
  deserves to be told it is false.

## Method split

| Method | Guarded | System | Note |
|---|---|---|---|
| `createFromUpload(workspaceId, …)` | ✓ | ✓ | REST upload vs component action / AG-UI turns |
| `findAllByWorkspaceIdAndEnvironment(workspaceId, …)` | ✓ | ✓ | `FindAction` |
| `findByIdInWorkspace(id, workspaceId)` | ✓ | ✓ | Already self-gating |
| `renameInWorkspace(id, workspaceId, …)` | — | ✓ | System-side replacement for `rename(id)` |
| `deleteInWorkspace(id, workspaceId)` | — | ✓ | |
| `downloadContentInWorkspace(id, workspaceId)` | — | ✓ | |
| `updateContentInWorkspace(id, workspaceId, …)` | — | ✓ | |
| `fetchByPublicLinkToken(token)` | — | ✓ | Anonymous by design; the token is the authorization |
| `createFromAi`, `createBinaryFromAi` | ✓ | — | Generators run rehydrated |
| `delete`, `rename`, `updateContent`, `updateDescription`, `restoreVersion`, `getVersions`, `findById`, `downloadContent` | ✓ | — | Bare-id: resolve owner, then membership |
| `enablePublicLink`, `disablePublicLink`, `createSignedDownloadToken` | ✓ | — | Privilege-granting — see below |
| `cloneToEnvironment(id, workspaceId, …)` | ✓ | — | Already verifies source against workspace |
| `getMaxFileSizeBytes()` | ✓ | — | Returns config, touches no data; deliberately unchecked |
| `getOwningWorkspaceId(id)` | removed | — | See below |

**The privilege-granting trio is membership-only, deliberately.** `enablePublicLink` and
`createSignedDownloadToken` mint *anonymous* access. A workflow action or an AG-UI turn must never be
able to do that on a server-derived workspace alone — it converts "this job may touch this file" into
"anyone with the URL may".

**`getOwningWorkspaceId` leaves the public interface.** Given any id it reveals the owning workspace —
a membership oracle. Its only callers are the two guards this design deletes
(`AssetFileGraphQlAccessGuard:62`, `AssetFileRestController:201`); it becomes an implementation detail
of the check.

**`AssetFileGraphQlAccessGuard` and the REST controller's private duplicate are removed.** Two copies
of one membership rule can drift, which is how `assetFileTags` ended up unguarded beside its guarded
neighbours.

## Testing

- **A caller-set scan for `AssetFileSystemFacade`, with an explicit allowlist.** Not a heuristic: this
  session's workspace-key scan missed a live vulnerability twice, once because a constant name was not
  in a hand-maintained list and once because a helper was not in a call-name allowlist, and a reviewer
  bypassed its file-granular rule in a single edit. The threat is not a typo — it is someone with a
  perfectly good principal reaching for the system facade because it is less trouble than getting the
  workspace right. The scan reads files outside its own module, so it runs from a never-up-to-date
  Gradle task wired into `check` with its own `@Timeout`, mirroring `toolContextWorkspaceScan`.
- **A refusal test per guarded method**, each of which must fail against the current code. The current
  code has no check at all, so this is arrangeable for every method; the count of tests that go red
  before the change is the evidence the gate is on the path rather than beside it.
- **Regression tests for all three no-principal families** — a component action, an AG-UI turn, and an
  anonymous public download. These paths throw rather than deny when this is got wrong, so they are
  the tests that catch a fix which looks clean and breaks chat file upload.
- **A test pinning the system interface's method set**, so the privilege-granting trio cannot be added
  to it later without a deliberate change.

Deliberately not tested: that `getMaxFileSizeBytes()` is unguarded (a test would restate the code),
and CE behaviour, where `getUserWorkspaces` returns every workspace and the check is permissive by
construction, exactly as `WorkspaceAccessGuard` already is.

## Consequences

- **In EE this is a real behaviour change on an existing surface.** Callers that today reach any asset
  file by id will be refused unless they are members of its workspace. That is the point, and it
  belongs in release notes rather than being discovered.
- **In CE nothing changes**, because `getUserWorkspaces` returns everything.
- One extra `getOwningWorkspaceId` query per bare-id guarded call.
- Four new `*InWorkspace` methods (`rename`, `delete`, `downloadContent`, `updateContent`). The system
  facade's other four — `createFromUpload`, `findAllByWorkspaceIdAndEnvironment`, `findByIdInWorkspace`,
  `fetchByPublicLinkToken` — already exist and move across unchanged, so it carries eight in total.
- Ten call sites rewired: seven component actions, two AG-UI agents, one public-download controller.

## Out of scope

- **`ASSET_FILE_*` permission scope** and read/write role differentiation. Filed separately.
- **The `assetFileTags` GraphQL query's missing guard** — it calls `AssetFileTagService`, not this
  facade, so it is not fixed by this change and needs its own.
- **A live functional bug found while tracing:** neither `SlideBuilderToolCallback` nor
  `ImageGeneratorToolCallback` calls `.toolContext(...)` on its sub-agent prompt, unlike
  `ResearchToolCallback:119` and the six Copilot delegates. So
  `AutomationToolInvocationContext.fromToolContext` returns null and the asset-file callbacks
  short-circuit with "Workspace context unavailable" — `slide_builder` and `image_generator` cannot
  create or read asset files at all. Not security; a feature that silently does not work.
- **Whether other facades share this shape.** Asset files were found by following three
  vulnerabilities; nothing has established that no sibling facade is equally unguarded.

# AI Hub shared sessions and presence

Date: 2026-09-02
Status: draft, unreviewed. Decisions marked ⚑ were made without the author present and need a
yes or a redirect before planning.
Branch context: 0_732. Companion spec: `2026-09-02-ai-hub-tool-approval-gate-design.md`.

## Problem

An AI Hub chat belongs to exactly one person. `ai_hub_chat.user_id` is NOT NULL, every list query
filters on `(workspace_id, user_id)`, and the three REST entry points (`POST /ai/chat/ai_hub`,
`GET …/{threadId}/attach`, `GET …/in-flight`) all enforce thread ownership. There is no way to
show a colleague a conversation, let alone let them continue it, and nothing tells anyone who else
is looking at a chat or whose turn is running.

Channel-born agent chats make this worse. When a Slack thread reaches an AI Agent,
`AiHubAgentConversationRecorder` writes one `ai_hub_chat` row stamped with a single
`creatorUserId`, so a conversation that three people took part in on Slack shows up in one
person's sidebar and nobody else's.

OpenClaw 2.0 framed this as the point of its release: agent context becomes a shared work artifact
rather than a private conversation. ByteChef's positioning deliberately keeps the Hub out of the
internal-ChatGPT race, so this spec is narrow: make a chat shareable the way a connection or a
project already is, let an invited person follow and contribute, and show who is present. It is
not a collaboration suite.

## Goals

1. **Share a chat** with the workspace or with named people, using the resource-visibility model
   the platform already has (`visibility` column + `resource_grant`), with the same GraphQL shape
   and audit events connections and projects use.
2. **Follow live.** A person the chat reaches sees the transcript, artifacts and any in-flight run
   as it streams, through the attach endpoint that already exists for reloads.
3. **Contribute.** When the owner allows it, a person the chat reaches can send turns, with each
   turn attributed to its author and one turn in flight per chat.
4. **Presence.** Who has the chat open, who is typing, whose turn is running, who resolved an
   approval.
5. **Channel-born agent chats are workspace-visible by default**, because a Slack channel was never
   private to the person who happened to send the first message. ⚑

## Non-goals

- Hostile-tenant isolation inside a workspace. A workspace is one trust domain, exactly as one
  OpenClaw gateway is. Tenants are already schema-isolated.
- `ORGANIZATION` reach. A chat belongs to one workspace; the model has no way to express one that
  reaches outside it (the same reasoning that keeps projects at `PRIVATE`/`WORKSPACE`).
- Co-editing artifacts, shared composer drafts, cursors. Presence is indicators, not CRDTs.
- Sharing an owner's tool attachments or connections beyond what a turn needs (see "Whose tools").
- Per-person participation modes (view for Ana, participate for Ivan). One mode per chat in v1. ⚑
- Multi-instance live fan-out. The existing `InFlightAiHubRunRegistry` is process-local by design
  and this spec rides on it; the Redis pub/sub design its Javadoc calls for is a separate piece of
  work that would carry presence along with it.
- Workflow chats and agent chats started from the composer keep working exactly as they do; only
  their visibility changes. The webhook bridge is untouched.

## Design

### Model

Two columns on `ai_hub_chat`, both additive:

| column | type | default | meaning |
|---|---|---|---|
| `visibility` | INT NOT NULL | `PRIVATE` (0) | `ResourceVisibility` ordinal; `PRIVATE` or `WORKSPACE` |
| `participation` | INT NOT NULL | `VIEW` (0) | `AiHubChatParticipation`: `VIEW` = 0, `PARTICIPATE` = 1 (append-only) |

"Specific people" is `PRIVATE` plus `resource_grant` rows with `resource_type = "AiHubChat"`,
exactly as for connections and projects. A grant conveys visibility only; `participation` decides
what everyone the chat reaches may do. The owner is never subject to either.

**Chats are created `PRIVATE`.** This is a deliberate departure from the platform rule that every
resource is created `WORKSPACE`-visible. ⚑ The rule exists so shared infrastructure (a
connection, a project) is usable by the team that owns it without a ceremony; a chat is a person's
working conversation, and a `WORKSPACE` default would expose every member's chats to every other
member on the day the migration runs. `AiHubChatVisibilityPolicy` declares
`supportedVisibilities() = {PRIVATE, WORKSPACE}` and `defaultVisibility() = PRIVATE`; the policy is
the sanctioned place to say so, so no facade force-writes anything.

`AiHubAgentConversationRecorder` writes channel-born rows with `visibility = WORKSPACE` and
`participation = VIEW`. ⚑ The composer-created `AGENT_CHAT` rows it must never adopt stay
`PRIVATE` — same kind, opposite default, keyed on the existing two-signal check (`aiAgentId`
non-null, `workflowExecutionId` null).

`user_id` keeps meaning **owner**. It is not renamed; "owner" is the word in every new interface.

### Authorization

`AiHubChatVisibilityProvider` registers resource type `"AiHubChat"` with the chat id as the record
id, so `hasResourceScope` takes the visibility branch and grants resolve against
`("AiHubChat", chatId)`. Every by-id path in `AiHubChatServiceImpl` that today compares
`chat.getUserId() != userId` routes instead through one EE component:

```java
interface AiHubChatAccessPolicy {
    boolean canView(AiHubChat chat, long userId);          // owner, admin, WORKSPACE reach, or a grant
    boolean canParticipate(AiHubChat chat, long userId);   // canView && (owner || admin || participation == PARTICIPATE)
    boolean canManage(AiHubChat chat, long userId);        // owner || workspace admin
}
```

| operation | requires |
|---|---|
| read transcript, artifacts, asset files, tool list, in-flight status, attach | `canView` |
| `POST /ai/chat/ai_hub` (send a turn), `askUserQuestion` answers | `canParticipate` |
| rename, archive, delete, share settings, attach/detach tools, resolve tool approvals | `canManage` |

Every failure that could enumerate — unknown chat, chat in another workspace, grantee not a
workspace member — returns the existing not-found error; an unsupported visibility rung keeps its
own `UNSUPPORTED_VISIBILITY`. The three REST gates (`enforceThreadOwnership`,
`enforceThreadOwnershipForAttach`, `isOwnedInFlightThread`) become `canParticipate`, `canView`,
`canView` respectively. The `in-flight` probe still answers only for ids the caller supplied, so
the enumeration argument in its Javadoc still holds.

**A shared chat does not share what it points at.** Artifacts are references to resources
(workflows, data tables, knowledge bases, files) with visibility of their own. A viewer sees the
artifact chip; opening it runs the artifact's own check and fails closed, the way a shared project
does not make its private connections readable.

### Whose tools, whose identity

A turn sent by a participant runs **as the participant** for authorization (`@PreAuthorize`
facades see the sender), with this tool set:

- pinned and catalog tools: the sender's own permissions, as for any turn of their own;
- **chat-scoped** attached tools (`ai_hub_chat_component.chat_id = this chat`): available, because
  the owner attached them to this conversation on purpose, with the same "use plus existence"
  semantics a `WORKSPACE`-visible connection has — the participant can invoke, never read or
  repoint the connection;
- the owner's **user-global** connectors (`chat_id IS NULL`): **not** available. They were added to
  "every chat I start", which is a statement about the owner's chats, not about anyone who can see
  them;
- the participant's own user-global connectors: **not** available either, so the chat's capability
  set is the same for everyone reading it. ⚑

Model selection and guardrails are workspace-level and unaffected. Session memory is keyed by
`threadId` under the fixed `AiHubSessionMemory.SESSION_USER_ID`, so nothing about memory changes
when a second person writes into the thread.

The chat-scoped tools a participant's turn may call are executed through the owner's connection
bindings — that is precisely the case the companion approval-gate spec exists for, and a workspace
`REQUIRE` rule on an outbound connector is the recommended pairing.

### Attribution

Each user message is stored with `userId` in `Message.getMetadata()`; the session repository
persists metadata as JSON. ⚑ Verify during planning that the `springaicommunity` session
family round-trips metadata; if it does not, fall back to a slim `ai_hub_chat_turn` table
(`chat_id`, `message_ordinal`, `user_id`, `created_date`) written beside the memory advisor.
`AiHubChatMessage` gains `authorUserId` and `authorName`, and the client shows the author label
only when a chat has more than one author, so single-author chats look as they do today.

Tool executions in a participant's turn are recorded through `ToolExecutionRecorder` with the
participant as `userId`, so the usage ledger and cost attribution follow the person who caused the
call.

### One turn at a time

Standard chats today rely on the in-flight registry's race protection for the same user. With
several people able to send, the rule is explicit: **one in-flight turn per chat**. A `POST` that
finds a run registered for the thread returns a typed `TURN_IN_FLIGHT` error carrying the running
user's name; the client disables the composer with "Ana's turn is running" until the poll (below)
clears it. `WorkflowChatGuard` already does this for workflow chats through the shared
`CacheManager`; the standard-chat check uses the same cache so it is correct across instances even
though the stream itself is not.

### Presence

Presence is a per-thread map `userId → {state: VIEWING | TYPING, lastSeen}` in an
`AiHubPresenceRegistry` backed by the Spring `CacheManager` (Caffeine locally, Redis when
configured — the same dual backend `WorkflowChatGuard` uses), with a 45-second TTL per entry.

Transport is **polling**, not a new push channel:

- The existing `GET /ai/chat/ai_hub/in-flight` becomes `GET /ai/chat/ai_hub/status?threadIds=…`
  and returns, per thread the caller may view: `inFlight`, `runningUserId`, `messageCount`,
  `updatedAt`, `presence[]`. The sidebar already polls the old endpoint on mount and environment
  switch; it now polls every 20 s, and the focused chat every 5 s.
- `POST /ai/chat/ai_hub/{threadId}/presence` with `{state}` is the heartbeat: sent on open and
  every 20 s while the chat is focused, on typing start (debounced 1 s) and typing stop, and on
  close (best-effort). Requires `canView`.

A viewer learns that someone else's turn started because `inFlight` flips; the client then opens
the attach stream exactly as it does after a reload and receives the replay prefix plus the live
tail. When the run finishes, `messageCount` moves and the client refetches messages. So the whole
"follow live" goal is the existing attach machinery plus one field in a poll — the reason this
spec needs no SSE chat-event stream. ⚑ A push stream (`GET …/{threadId}/events`) is the natural
phase 4 if 5-second typing indicators prove too coarse, and it would be the same place a
multi-instance fan-out lands.

### Listing

The sidebar gains a **Shared with me** section under the user's own chats:

```graphql
aiHubSharedChats(workspaceId: Long!, environment: Int!): [AiHubChat!]!
```

returns active chats in the workspace the caller can view and does not own — `WORKSPACE` rows plus
granted `PRIVATE` rows — ordered by `updated_at`. Channel-born agent chats land here for everyone
but their creator, grouped under the agent's title as the composer-created ones already are.
`AiHubChat` gains `visibility`, `participation`, `ownerUserId`, `ownerName` and `isOwner`; the
existing `userId` field stays for compatibility and is documented as the owner.

Index: `(workspace_id, visibility, status, updated_at DESC)` on `ai_hub_chat` so the shared list
does not scan every private row.

### Sharing surface

```graphql
setAiHubChatVisibility(workspaceId: Long!, chatId: Long!, visibility: ResourceVisibility!, participation: AiHubChatParticipation!): AiHubChat!
grantAiHubChatAccess(workspaceId: Long!, chatId: Long!, userId: Long!): AiHubChat!
revokeAiHubChatAccess(workspaceId: Long!, chatId: Long!, userId: Long!): AiHubChat!
aiHubChatGrants(workspaceId: Long!, chatId: Long!): [AiHubChatGrant!]!
```

All four on a separate `AiHubChatSharingFacade` (owner-or-admin, `@PreAuthorize` on the impl),
mirroring `ProjectSharingFacade`. Grantee must be a member of the workspace; the grant insert is
`ON CONFLICT DO NOTHING`. Grants are deleted with the chat in `AiHubChatServiceImpl.delete`
because `resource_id` is polymorphic and has no foreign key. Narrowing to `PRIVATE` while another
user's turn is in flight is allowed — the run finishes, the viewer's next poll returns not-found
and the client closes the chat.

### Client

- **Share** button in the chat header (owner/admin only): a `ResourceVisibilityPicker` generalised
  from `ConnectionVisibilityPicker` (Private / Shared with workspace / Specific people) plus a
  participation switch "People with access can send messages". The picker is the third caller of
  the connection picker's shape; generalising it is in scope, retheming it is not.
- **Presence strip** in the header: avatars of everyone with a live presence entry, typing dots on
  the ones in `TYPING`, and "Ana's turn is running" while `inFlight` names someone else.
- **Composer states**: enabled; disabled "View only" (`participation = VIEW`, not owner); disabled
  "Ana's turn is running"; disabled "Waiting for owner's approval" when a tool approval is pending
  and the viewer cannot resolve it.
- **Author labels** on user bubbles once a chat has more than one author.
- **Shared with me** sidebar section, with the same running / paused pulses as own chats, driven by
  the status poll.
- Everything above is EE-only, gated the same way the connection and project sharing UI is
  (`useIsVisibilityEditionEnabled`).

### With the approval gate

The tool-approval card renders for everyone who can view; `resolveAiHubToolApproval` requires
`canManage`, so participants see it disabled with the owner's name. A participant's gated call is
requested in their name and, once approved, executes under the identity the companion spec
assigns (participant for catalog tools, owner for chat-scoped attached tools). The presence strip
shows "Waiting for Ivica's approval" while a row is `PENDING`.

### Audit and metrics

- `AiHubAuditEvent`: `AI_HUB_CHAT_VISIBILITY_CHANGED(true)`, `AI_HUB_CHAT_ACCESS_GRANTED(false)`,
  `AI_HUB_CHAT_ACCESS_REVOKED(true)`. Payload: `chatId`, `visibility`, `participation`,
  `granteeUserId`. Turns are not audited individually (per-turn events would flood the log; the
  audit-emitter spec made the same call for agent turns).
- Metrics: `bytechef_ai_hub_chat_share{visibility}` counter,
  `bytechef_ai_hub_chat_presence` gauge (live entries), `bytechef_ai_hub_chat_turn_conflict`
  counter for `TURN_IN_FLIGHT` rejections.

### Distributed EE

Visibility, grants, the turn lock and presence all live in tables or in the shared cache, so they
are correct across instances. The attach stream is not: a viewer whose poll lands on instance A
while the run lives on instance B gets a 404 from attach and renders history-only with a running
pulse — the same behaviour a reload has today in a multi-instance deployment. The spec does not
fix that and says so in the UI copy ("Running on another node — refresh when done") rather than
pretending to stream.

## Error handling

| Situation | Behaviour |
|---|---|
| Viewer opens a chat that was just narrowed to `PRIVATE` | next poll returns not-found for the thread; client closes the tab with "This chat is no longer shared with you". |
| Participant sends while a run is in flight | `TURN_IN_FLIGHT` with the running user's name; composer disabled until the poll clears. |
| Presence heartbeat fails | ignored; the entry expires after 45 s and the avatar disappears. Never blocks a turn. |
| Grant to a non-member | not-found-shaped error (no enumeration). |
| Chat deleted while shared | grants removed in the same transaction; viewers' next poll returns not-found. |
| Attach 404 in multi-instance | history-only render with a running pulse and the "another node" hint. |

## Testing

- `AiHubChatAccessPolicyTest`: owner / admin / workspace-visible / granted / stranger for each of
  the three predicates; `VIEW` vs `PARTICIPATE`.
- `AiHubChatSharingFacadeTest`: rung validation, non-member grantee, idempotent grant, delete
  cascade of grants.
- `AiHubChatServiceImplTest`: shared list excludes own and private-ungranted rows; recorder writes
  `WORKSPACE` for channel-born rows and leaves composer rows `PRIVATE`.
- `AiHubApiControllerTest`: participant `POST` accepted under `PARTICIPATE`, rejected under `VIEW`;
  `TURN_IN_FLIGHT` on a second sender; status endpoint answers only for viewable ids.
- `AiHubPresenceRegistryTest`: TTL expiry, state transitions, per-thread isolation.
- `PermissionServiceVisibilityTest` gains the `"AiHubChat"` provider case, per the rule that this
  EE test is the regression guard for every visibility-wired type.
- Client: header share picker, presence strip, composer state matrix, shared-sidebar section,
  author labels appearing only with two authors.

## Phases

1. **Reach.** Columns, policy, provider, access policy replacing the ownership checks, sharing
   facade + GraphQL, shared sidebar section, share picker. Viewing only (`participation` stays
   `VIEW`).
2. **Contribute.** `PARTICIPATE`, attribution, the turn lock, composer states.
3. **Presence.** Registry, heartbeat, status poll replacing `in-flight`, presence strip.
4. **Push.** Chat-event SSE stream and multi-instance fan-out — only if 3 proves too coarse, and
   designed together with the in-flight registry's Redis pub/sub replacement.

Channel-born `WORKSPACE` default ships with phase 1 behind the same flag; it is one line in the
recorder and the whole reason phase 1 is useful on its own.

## Rollout

Client flag `ff-ai-hub-shared-chats`. The migration is additive with defaults, so rows created
before the release are `PRIVATE` / `VIEW` and nothing becomes visible to anyone new until an owner
shares it — except channel-born agent chats, which the migration leaves `PRIVATE`; only rows
recorded after the release get `WORKSPACE`. ⚑ Backfilling existing channel-born rows to `WORKSPACE`
is a one-line update an operator can choose to run; it is not done automatically because it widens
the audience of data that already exists.

## Open decisions (⚑)

1. Chats created `PRIVATE`, against the platform-wide `WORKSPACE` default.
2. Channel-born agent chats `WORKSPACE`-visible by default; existing rows not backfilled.
3. One participation mode per chat rather than per grantee.
4. Participants get chat-scoped attached tools and nothing user-global — including their own.
5. Polling presence in v1; push stream deferred to phase 4.
6. Attribution via message metadata, with the `ai_hub_chat_turn` fallback if the session store
   drops it.

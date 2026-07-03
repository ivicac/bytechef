# AI Hub tool approval gate

Date: 2026-09-02
Status: draft, unreviewed. Decisions marked ⚑ were made without the author present and need a
yes or a redirect before planning.
Branch context: 0_732 (the AI Hub lives only there). Companion spec:
`2026-09-02-ai-hub-shared-sessions-presence-design.md` — the two are written to compose, and the
sections marked "with shared sessions" say how.

## Problem

Every tool the AI Hub agent calls executes the moment the model asks for it. Two classes of tool
carry side effects that a person would normally want to confirm first:

- **Attached component tools.** A chat can attach any component action through `ai_hub_chat_tool`
  (chat-scoped rows) or the user's Connectors page (user-global rows in `ai_hub_chat_component`
  with `chat_id IS NULL`). "Send email", "post message", "create record" all run unconfirmed.
- **Destructive catalog tools on BUILD.** The intelligent-tool catalog the BUILD agent searches is
  shared with the management MCP surface and includes `deleteProject`, `deleteWorkflow`,
  `dropDataTable`, `deleteKnowledgeBase`, `deleteCustomComponent`, `deleteProjectDeployment` and
  their relatives. One `searchTool` hit away, no gate.

The HITL design (`2026-07-21-agent-hitl-approval-chat-design.md`) put the Hub out of scope on the
grounds that Hub tools are "user-driven CRUD executed inside the conversation the user is already
steering". That held for the ASK agent of July. It no longer describes BUILD with attached
connectors: the user is present, but presence is not consent — by the time the tool-call chip
renders, the action has already run.

The existing gate cannot be reused. `ApprovalGateToolCallback` works by calling
`actionContext.suspend(...)` and returning `ToolSuspendConstants.SUSPENDED_SENTINEL`, which
`SuspendableToolCallingManager` turns into a suspended atlas job; resume goes through
`JobResumeFacade` and re-executes the tool with the original arguments. A Hub turn is an AG-UI
stream from `AiHubSpringAIAgent`. There is no job, no `ActionContext`, no resume id to sign, and
none of the expiry / reminder / escalation sweeps in `platform-coordinator` see it.

## Goals

1. A flagged tool never executes from the model's call. It executes only from a human decision
   delivered through a server-verified mutation — never from chat text, never from a second model
   call.
2. The decision executes the tool **with the arguments the model chose**, unchanged, so what the
   person approved is what runs.
3. Safe by default for the destructive catalog tools, with a workspace-level policy an admin owns
   and a per-tool switch the chat owner owns.
4. The card looks and behaves like the workflow approval card the Hub already renders for workflow
   chats (`ApprovalRequestMessage`), so one mental model covers both.

## Non-goals

- Multi-channel delivery (Slack, email, SMS, approval tasks). The requester is looking at the chat;
  the card is the channel. A chat nobody is looking at is the shared-sessions spec's problem, and a
  scheduled agent already runs as a workflow and gets the real gate.
- Expiry sweeps, reminders, escalation. Expiry is enforced lazily at resolve time only.
- The Copilot panels and the management MCP surface. The wrapper is written surface-agnostic (see
  "Surface independence") so Copilot can adopt it in a follow-up; MCP has no person present and
  keeps its URL-elicitation flow.
- Workflow chats and agent chats (`AiHubChatKind#isWebhookBridged()`): they already carry the
  workflow gate and its `approval_request` AG-UI event. This spec touches `STANDARD` chats only.
- Editing arguments before approval. Approve or reject with a comment, exactly like the field-less
  workflow card. Edited fields are a later addition if the workflow card grows them first.

## Design

### Policy: what is gated

Three inputs, evaluated per tool call, **most restrictive wins**:

1. **Built-in default list** (`AiHubToolApprovalDefaults`, code, EE). The destructive catalog tools
   named above plus every catalog tool whose name starts with `delete`, `drop`, `rollback` or
   `promote`. ⚑ Prefix matching is deliberate so a new destructive tool is gated the day it is
   registered rather than the day someone remembers the list; the cost is that a harmless tool with
   an unlucky name is gated until exempted.
2. **Workspace rules** — new table `ai_hub_tool_approval_rule`:

   | column | type | notes |
   |---|---|---|
   | `id` | BIGINT | |
   | `workspace_id` | BIGINT NOT NULL | a column, not a relation table, per the platform convention; NOT NULL because a rule without a workspace has no meaning |
   | `tool_kind` | INT | `CATALOG` = 0, `COMPONENT` = 1 (ordinal, append-only) |
   | `component_name` | VARCHAR(255) NULL | COMPONENT only |
   | `tool_name` | VARCHAR(255) NOT NULL | catalog tool name, or the component operation name; `*` matches every operation of a component |
   | `mode` | INT | `REQUIRE` = 0, `EXEMPT` = 1 |
   | audit columns | | `created_by`, `created_date`, `last_modified_by`, `last_modified_date`, `version` |

   Unique on `(workspace_id, tool_kind, component_name, tool_name)`. `EXEMPT` is the only way to
   switch off a built-in default, and only an admin (`WORKSPACE_MANAGE`) writes rules.
3. **Chat owner switch** — `requires_approval BOOLEAN NOT NULL DEFAULT false` added to
   `ai_hub_chat_tool`. Applies to chat-scoped and user-global rows alike, so a user can mark their
   Gmail connector "always ask" once. The owner can add gating; the owner cannot remove a
   workspace `REQUIRE` or a built-in default. A gated tool row shows a shield badge in the tool
   panel — the cluster-element gate spec (`2026-08-07-approval-gate-cluster-element-design.md`)
   replaced a hidden boolean with a visible structure precisely because an invisible gate is an
   undiscoverable one; the badge is the Hub's equivalent.

`AiHubToolApprovalPolicy` (EE service) resolves the three into a `Set<String>` of gated tool names
for a `(workspaceId, userId, chatId)` at turn start. It is cached per turn, not per process:
a rule edit takes effect on the next turn.

### Mechanism: the wrapper

`AiHubApprovalGateToolCallback` (EE, `com.bytechef.ee.ai.hub.approval`) implements
`DelegatingToolCallback` and is inserted by `AiHubSpringAIAgent.wrapToolCallback` between the
context rehydrator and the empty-return guard:

```
RehydrateContextToolCallback            outermost — tenant + SecurityContext
  └─ AiHubApprovalGateToolCallback      this spec
       └─ NonEmptyToolCallback
            └─ delegate                 catalog tool | ClusterElementToolCallback | MCP tool
```

Both wrapping paths (`additionalToolCallbacks` for per-request bindings and the static builder path
in `AiHubConfiguration`) go through `wrapToolCallback`, so the gate covers pinned tools, catalog
tools surfaced by `searchTool`, attached component tools and MCP-server tools with one insertion
point. A tool whose name is not in the policy set is passed straight through — the wrapper costs a
set lookup for ungated tools.

On a gated call:

1. If another approval is already pending for this turn, return
   `{"deferred": true, "reason": "Another tool call is awaiting approval. Retry after it is resolved."}`
   — the same one-pending-per-round rule the workflow gate enforces, for the same reason: two
   pending cards on one turn have no defined resolution order. ⚑
2. Insert a row in `ai_hub_tool_approval`:

   | column | type | notes |
   |---|---|---|
   | `id` | BIGINT | |
   | `chat_id` | BIGINT NOT NULL | |
   | `thread_id` | VARCHAR(255) NOT NULL | denormalised for the continuation turn |
   | `run_id` | VARCHAR(255) NOT NULL | AG-UI run that raised it |
   | `requested_by_user_id` | BIGINT NOT NULL | the user whose turn called the tool |
   | `tool_kind` | INT | as above |
   | `tool_name` | VARCHAR(255) NOT NULL | |
   | `component_name` / `component_version` / `connection_id` | | COMPONENT only |
   | `arguments` | TEXT | the model's JSON input, verbatim |
   | `status` | INT | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `SUPERSEDED`, `FAILED` (append-only) |
   | `decided_by_user_id` / `decided_at` / `comment` | | |
   | `expires_at` | TIMESTAMP NOT NULL | now + 24h ⚑ |
   | `execution_error` | TEXT NULL | when `FAILED` |
   | `created_date` / `version` | | |

   Index on `(chat_id, status)`.
3. Return an envelope as the tool result — the model sees it, the client renders it, the session
   store persists it inside the ordinary tool-call/tool-result pair (so provider pairing rules for
   Anthropic and OpenAI stay satisfied and nothing has to be patched into memory later):

   ```json
   {"kind": "tool-approval-request", "approvalId": 1234, "toolName": "sendEmail",
    "componentName": "gmail", "arguments": {}, "expiresAt": "2026-09-03T10:00:00Z",
    "awaitingApproval": true}
   ```

   `kind` joins `ask-user-question` as a second value the client's data-part renderer dispatches
   on (`aiChatDataComponents.tsx`).
4. The system prompt gains one paragraph, mirroring the `askUserQuestion` instruction: when a tool
   returns `awaitingApproval: true`, say in one sentence what you asked to do and stop the turn;
   never retry the tool and never claim the action happened.

The wrapper never executes on a persistence failure: the failure is logged and the tool is
**refused** with `{"error": "approval could not be recorded"}`. Failing closed here is the opposite
of the component-rules decision (`ComponentRuleEnforcer` fails open) and deliberately so: a rule is
a tenant-wide policy whose misfire takes an estate offline, while this is one tool call in one chat
whose refusal costs one retry.

### Resolution: a mutation executes the tool

```graphql
resolveAiHubToolApproval(workspaceId: Long!, approvalId: Long!, approved: Boolean!, comment: String): AiHubToolApprovalResolution!
aiHubToolApprovals(workspaceId: Long!, chatId: Long!): [AiHubToolApproval!]!
```

`AiHubToolApprovalFacade.resolve` (EE, `@PreAuthorize` on the impl so every caller is covered):

1. Load the row; the caller must be the chat owner or a workspace admin, and the row must be
   `PENDING` and unexpired. Unknown id, wrong workspace and foreign chat all collapse to the same
   not-found error so ids cannot be probed. An expired row flips to `EXPIRED` on this read and
   returns a distinct, non-enumerating `APPROVAL_EXPIRED`.
2. **Reject:** set `REJECTED`, then start a continuation turn (step 4) whose message says the tool
   was rejected and carries the comment.
3. **Approve:** set `APPROVED`, rebuild the tool callback for `(chat, tool_name)` — the same
   `chatToolBindingResolver` / catalog lookup the turn used, with the same wrappers minus this
   gate — and execute it with the stored `arguments` **under the requesting user's security context**
   (`SecurityUtils.runAs`, the way the embedded generate-workflow path does), because the attached
   tools and their connections are bound to that user. In v1 the requester is always the chat
   owner; "With shared sessions" below says how that splits once a participant can request. The
   approver is recorded, not impersonated.
   Execution is timed and reported through `ToolExecutionRecorder` with `approvedBy` in the event
   metadata. A thrown execution sets `FAILED` with the message and still continues (step 4).
4. **Continuation turn.** Start a fresh agent run on the same `threadId` through
   `AiHubChatStreamer.runAgent`, with a synthetic user message the client renders as a status
   line rather than a bubble (message metadata `{"hidden": true, "approvalId": 1234}`):

   > Tool approval 1234 (`gmail/sendEmail`) was **approved** by Ana Kovač and executed. Result: `{}`

   or the rejected / failed equivalent. The model reads it as the next thing that happened and
   carries on. The mutation returns once the run is registered; the client attaches through the
   existing `GET /ai/chat/ai_hub/{threadId}/attach`, exactly as it does after a reload, so replay,
   sidebar pulse and Stop all work unchanged.

Why the mutation executes rather than the model re-calling the tool (the `askUserQuestion` shape):
with a re-call, approval would be advisory — the model could re-issue the call with different
arguments, or not at all, and the wrapper would have to compare argument hashes and open a second
card on mismatch. Executing from the mutation makes the approved arguments the only arguments that
ever run, which is goal 2 and the property the workflow gate already has.

Typing in the chat never resolves an approval (the HITL D4 rule holds). It **abandons** one: a new
user turn on a chat with a `PENDING` row marks it `SUPERSEDED` before the run starts, and the card
re-renders as superseded. ⚑ The alternative — lock the composer until resolved — mirrors the
workflow chat, but there the run is genuinely suspended; here nothing is, and a user who changed
their mind should not have to reject first.

### Client

- `ToolApprovalRequestMessage` renders the envelope: tool title (component icon + operation title
  when COMPONENT, catalog title otherwise), arguments as a key/value table with long values
  collapsed, optional comment, Approve / Reject. Built on the same primitives as
  `ApprovalRequestMessage`; resolution goes through the GraphQL mutation via a second method on
  `ApprovalResolutionContextI` (`resolveToolApproval(approvalId, approved, comment)`) rather than the
  job-resume endpoint.
- State after reload: the envelope in the transcript has no status, so on chat open the client
  fetches `aiHubToolApprovals(chatId)` and paints `APPROVED by …`, `REJECTED`, `EXPIRED`,
  `SUPERSEDED` or `FAILED` over each card by `approvalId`.
- Sidebar: reuse the `paused` activity state (`useAiHubChatsStore.chatActivity`) with the label
  "Needs your approval"; cleared on the continuation's `RUN_STARTED` like the ask-question flow.
- Tool panel: shield badge on gated rows; "Require approval" toggle in the row's `⋮` menu, disabled
  with a tooltip when the workspace already requires it.
- Workspace settings → AI Hub → **Tool approvals**: built-in defaults listed read-only with an
  Exempt switch; workspace `REQUIRE` rules added by picking a catalog tool or a component +
  operation. Mutation controls gated on `WORKSPACE_MANAGE`.

### With shared sessions

When the companion spec lands: the card is visible to every participant; only the chat owner or a
workspace admin can resolve (participants see the buttons disabled with "Waiting for <owner>"), and
a participant's turn that hits a gated tool creates the row with `requested_by_user_id` = the
participant, so approval executes with the **participant's** context if the tool is a catalog tool
and with the **owner's** if it is a chat-scoped attached tool — the same split the companion spec
makes for un-gated tools. ⚑

### Surface independence

The wrapper depends on three interfaces, none Hub-specific: `AiHubToolApprovalPolicy` (which names
are gated), `PendingToolApprovalStore` (insert / lookup) and `ToolApprovalEnvelopeWriter` (the
result JSON). The Copilot panels can wire the same wrapper with a store keyed on their own
conversation id and a card in the Copilot thread; nothing in this spec has to change for that.

### Audit and metrics

- `AiHubAuditEvent`: `AI_HUB_TOOL_APPROVAL_REQUESTED(false)`, `AI_HUB_TOOL_APPROVAL_APPROVED(true)`,
  `AI_HUB_TOOL_APPROVAL_REJECTED(false)`, `AI_HUB_TOOL_APPROVAL_RULE_CHANGED(true)`. Approve and
  rule changes are strict because both change what a tool may do without asking.
- Payloads: `approvalId`, `chatId`, `toolName`, `componentName`, `requestedByUserId`,
  `decidedByUserId`; never the arguments (they may contain the email body).
- Metrics: `bytechef_ai_hub_tool_approval{outcome=requested|approved|rejected|expired|superseded|failed}`.

### Distributed EE

`ai_hub_tool_approval` and the rules table are ordinary tenant tables, so the mutation may land on
any instance. The continuation run registers in `InFlightAiHubRunRegistry` on the instance that
served the mutation, which is the same process-local limitation every Hub run has today (the
registry's own Javadoc names Redis pub/sub as the eventual fix). Nothing here makes it worse.

## Error handling

| Situation | Behaviour |
|---|---|
| Policy lookup throws at turn start | Turn proceeds with the built-in default list only; WARN once per turn. Never with an empty set. |
| Pending row insert fails | Tool refused with an error envelope; the model reports it; nothing executes. |
| Resolve on expired row | Row → `EXPIRED`; mutation returns `APPROVAL_EXPIRED`; card shows expired. |
| Approved execution throws | Row → `FAILED` with message; continuation turn tells the model; audit `APPROVED` still fires (the decision happened). |
| Continuation run cannot start (another turn in flight) | Row keeps its decided status; mutation returns `continuationStarted: false`; the client shows "Resolved — send a message to continue". |
| Two resolvers race | Optimistic `version` column; the loser gets the not-found-shaped conflict. |

## Testing

- `AiHubApprovalGateToolCallbackTest`: ungated pass-through; gated → row + envelope, delegate never
  called; second gated call in one turn → deferred; store failure → refused.
- `AiHubToolApprovalPolicyTest`: default list, prefix match, `EXEMPT` wins over default, chat switch
  adds, chat switch cannot remove a `REQUIRE`.
- `AiHubToolApprovalFacadeTest`: owner / admin / stranger; expired; approve executes with stored
  arguments under the requester's context; reject starts continuation without executing.
- `AiHubToolApprovalGraphQlControllerIntTest` for the two operations and the rules CRUD.
- Client: `ToolApprovalRequestMessage.test.tsx` (render, approve, reject, superseded overlay),
  store test for the paused state clearing on continuation.

## Rollout

Feature flag `ff-ai-hub-tool-approvals` on the client; server side always on. An unflagged server
with a flagged-off client would create rows and have the model report it is waiting with no card
to resolve — a visibly incomplete state — so ship both halves in one release. Migration is
additive: two new tables, one boolean column with a default.

## Open decisions (⚑)

1. Prefix-match the built-in defaults, or an explicit list only?
2. One pending approval per turn (defer the rest), or allow several cards?
3. 24-hour lazy expiry — right number?
4. New user turn supersedes a pending approval, or lock the composer?
5. Execution context when the requester is a participant rather than the owner (with shared
   sessions).

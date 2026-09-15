# Browser voice (Voice Agent cluster element)

Design: `docs/superpowers/specs/2026-09-08-voice-cluster-element-redesign-design.md` (see its
"Implementation deviations" section for where the build diverged from the plan). User docs:
`docs/voice/quickstart.md`, `docs/voice/editor-testing.md`.

## Model: two sibling slots, not nested

`browser/v1/voiceSession` (`BrowserVoiceSessionTrigger`) is a cluster root declaring **two** cluster
element types on the trigger itself: `VOICE_AGENT` (exactly one, `voiceAgent` slot) and `TOOLS` (any
number, `tools` slot) — `BrowserComponentHandler.BrowserComponentDefinitionImpl`. Tools sit *beside*
Voice Agent, not nested inside it: the spec's nested (`AiAgentChatTool`-shaped) design was overridden
during implementation because nesting would have forced Deepgram, OpenAI and ElevenLabs to become
cluster roots themselves, breaking their existing plain actions in the editor and the AI Hub.
`VoiceAgentToolsetFactoryImpl` is handed the **trigger's** extensions, not the voice agent element's
own, and reads `tools` out of them directly. Workflow JSON:
`trigger.clusterElements = {voiceAgent: {...}, tools: [{...}]}`, a sibling of `type`/`parameters` via
`WorkflowTrigger`'s existing `extensions` — no domain change was needed.

## Engine outside Atlas

`VoiceSessionEngine` (`platform-websocket-webhook-rest`) hosts the live session. A session is a
long-lived bidirectional stream ending when the caller hangs up; Atlas models "task completes →
advance" and would pin a worker thread for the whole call. `WorkflowContinuationHelper.
createContinuationJob` is the **only** way back into Atlas: runs once, when the handler decides the
session is over, with the session summary as trigger output. Nothing about a live call flows through
the task engine — no job, no step, no `TaskExecution`.

## Element contract

`VoiceAgentFunction` (`platform-component-api`, `...definition.voice`): `WebSocketHandler
apply(Parameters inputParameters, Parameters connectionParameters, VoiceAgentContext context)`.
`VoiceAgentContext` is a record `(ActionContext actionContext, VoiceAgentToolset toolset)` — not an
extension of `ClusterElementContext`, since provider components are `@AutoService` (no Spring DI) and
can't build the tool policy stack themselves. An ordinary action can no longer return a
`WebSocketHandler` — only a `VoiceAgentFunction` can, invoked only by `VoiceSessionEngine`.

Stage contract, proven by `AbstractVoiceAgentContractTest`: inbound raw PCM16 at the trigger's
`sampleRate`; outbound binary PCM16 at the element's `outputSampleRate` (sent on the `connected`
event so the browser builds playback buffers correctly); outbound JSON events `transcript_interim`,
`transcript_final {text}`, `assistant_text {text}`, `speech_start`, `tool_call {name, arguments}`,
`tool_result {name, ok, arguments, result}`, `error {message}`. `VoiceSessionEngine.record` reads
`transcript_final`/`assistant_text`/`tool_result` off the emitter's outbound stream to build the
transcript — `tool_result` must carry both `arguments` and `result`, or a call goes missing from the
continuation job's `toolCalls`. Every provider acks a tool call before anything else that could fail:
`VoiceAgentToolset#call` never throws, but the reply send can, so each provider wraps call-and-reply
in try/catch and still answers the provider frame on failure — an unanswered call hangs the turn.

## Tool policy: one place, providers stay dumb

`VoiceAgentToolsetFactoryImpl` (`components/ai/llm`, `...voice`) builds a `VoiceAgentToolset` from
the trigger's `tools` elements through `ClusterElementToolCallbacks` — the same path AI Agent tools
use — so Component Rules, the tool execution recorder and guardrail redaction apply identically, with
no tool logic in the engine or any provider. **No HITL inside a call**: `ToolApprovalRequests.raise`
throws `ApprovalUnavailableException` (an `IllegalStateException` subclass, existing catches keep
working) when `actionContext.getResumeUrl()` is null — true for every voice session, no job to
suspend. `ToolCallbackVoiceAgentToolset` catches exactly that exception and returns "This action
requires approval and is not available during a voice call." as the tool result. This replaced an
earlier plan to gate on the approval-channel's class name; the typed-exception catch also covers a
Component Rule's own approval requirement, not just an explicit approval channel. A tool that fails to
initialise fails the session start, the same as an AI Agent whose tool cannot be built — intended: a
call that silently lost one of its tools would mislead the caller mid-conversation. Duplicate tool
names keep the first tool and log a WARN (a provider calls tools by name, so only one is reachable).

## Connection resolution, `sessionId` protocol, resume, silence timeout

`VoiceSessionConnectionResolver` has two paths, keyed by element workflow-node name: **deployed**
sessions run the trigger through the platform's `TriggerDispatcherPreSendProcessor` chain — the same
code a webhook trigger execution uses — and read the `CONNECTION_IDS` it stamps. **Editor** sessions
read `WorkflowTestConfigurationService` for the workflow/trigger/environment instead. Their
environment is bound into the session token at mint: `POST
/internal/workflow-tests/{workflowId}/voice-session-token?environmentId=…` carries
`@PreAuthorize("hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)")`, the
gate the editor's own test run uses. The gate authorizes
`PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId)` — a confined api-key principal's own
environment wins — so the controller resolves that same value on the request thread and binds it into
the token. `WorkflowTestWebSocketHandler` runs the session in the token's environment only; an
`environmentId` query on the upgrade is inert and deliberately not compared (a mismatch check denies
embedded callers whose two ends default independently — the check ticket 1051 shipped and reverted).

Token mint: `POST /webhooks/{webhookId}/voice-session-token` (deployed) or
`POST /internal/workflow-tests/{workflowId}/voice-session-token` (editor), single-use; upgrade with
`?sessionToken=...`. A close the **server** did not initiate detaches the session for
`VoiceSessionRegistry.RESUME_WINDOW` = 30 seconds; a reconnect inside that window carries
`resumeSessionId=<old id>` and `WebhookWebSocketHandler.resumeSession` re-attaches the bridge to the
*same* engine session, so the provider socket and its context survive — the client retries up to
three times with backoff. A server-initiated close (`session_limit`, `silence_timeout`,
`provider_error`, `provider_closed`, `server_shutdown`), a deliberate client hang-up (code 1000 with
reason `client_closed`, kept apart from the server reasons as `CLIENT_END_REASON`) or an elapsed
resume window ends the session for good. "Server-initiated" is what the handler recorded, never what
the frame says: every server close goes through `closeForServerReason`, which stores the reason under
the socket id before closing, and `afterConnectionClosed` takes it back. A client whose close frame
carries `silence_timeout` or `session_limit` is only detached, and a server close the container
reports without a close frame still ends with the recorded reason. `provider_closed` comes from a completion listener on the
emitter: a provider that hangs up on its own completes it, and a handler-initiated stop is ignored
because `VoiceSessionRegistry.Session.markEnded()` is set under the session monitor first. A resume
is only honoured for a session registered under the same webhook id the token was minted for.
`SmartLifecycle.stop()` finalizes every live session as `server_shutdown`, then joins (up to 30 s,
logged if exceeded) the session starts and handed-off ends still running on their virtual threads —
virtual threads are daemon, so an unjoined continuation job dies with the JVM. It sets a
`shuttingDown` flag first (not `running`, which is false until the container starts the bean): new
upgrades are refused with `server_shutdown` before their token is spent, and a start that joins the
live sessions after `stop()` read them finds the flag and ends itself. Timer callbacks decide
under the session lock and hand the engine stop and continuation job to a virtual thread, so one
slow job never stalls the single `voice-session-timeout` thread. Start, end and token mint run
in the webhook's tenant (`TenantContext`), and a disabled workflow is refused at mint (404) and at
start (error frame, policy close, no continuation job). Deployed sessions build their
`ActionContext` with the webhook's job principal and the deployment's environment, so
workspace-scoped Component Rules and environment-scoped tools apply to voice tools. A trigger with no
Voice Agent is refused at start (error frame, policy close, no continuation job) on both paths; the
handlers catch `VoiceSessionEngine.NoVoiceAgentException` by type, so a provider failure whose message
happens to quote the no-Voice-Agent text is still reported as a start failure. Both refusals close
with a reason (`voice_agent_missing`, `workflow_disabled`) that the browser client treats as terminal
— a single error carrying the preceding error frame, never a reconnect or "Connection lost" — and are
counted as session errors. A start failure (the provider cannot connect) is different: it ends as
`provider_error` and still runs the post-call workflow with an empty transcript, while a refusal runs
nothing. An
upgrade without a `sessionToken` is refused — the old webhook-over-WebSocket `execute` action was
unreachable and is gone — and text frames are never echoed. A muted client sends
`{"type":"control","action":"keepalive"}` every 15 s (`VoiceControlFrame.isKeepalive`); both handlers
count it for the silence timer only and never forward it. `WorkflowTestWebSocketHandler` has the same
session-limit timer as the deployed handler. Provider sockets share one `HttpClient`
(`SharedHttpClient`). The editor test path never
resumes by design (`WorkflowTestWebSocketHandler` has no resume branch) — a dropped test session is
always over. Silence timeout: the handler tracks the last inbound-audio instant per session and
closes `silence_timeout` after `silenceTimeoutSeconds` (default 120, `0` disables) of no **inbound**
audio — assistant audio never resets it.

## Serialized provider socket

`ProviderWebSocketConnector.jdk()` wraps the JDK `WebSocket` in a `SerializedWebSocket` via
`SerializingWebSocketListener` so every listener callback and `connect()` itself return the identical
wrapped instance, never the JDK's raw socket — the JDK does not guarantee `onOpen` has run by the
time `builder.buildAsync(...).join()` returns, so reading a holder only `onOpen` populates can race
and see `null`. `serialized(WebSocket)` is idempotent and identity-keyed (`compareAndSet`): whichever
of the connector's own call or the listener's first callback runs first creates the wrapper, the
other reuses it. Payoff: every provider's listener sends through its own callback parameter with no
binding of its own, and outbound sends never overlap.

## Editor: trigger as cluster root

`ClusterRootWorkflowNode` (`platform-configuration-api`) lets every editor facade treat a trigger
root like a task root: `of(Workflow, String)` looks up a trigger by name first, falls back to a task.
`WorkflowFacadeImpl.getWorkflowDTO` builds `WorkflowTriggerDTO.clusterRoot`/`clusterElements` the
same way it already built the task DTOs. This (client type gaining those fields on triggers,
`saveWorkflowDefinition` persisting them) was **Task 25b**, added once the editor work found no
existing path for a trigger to carry cluster elements. The picker's filtering is **client-side
only** — `WorkflowNodesPopoverMenuComponentList.hasClusterElementType` filters the already-fetched
component list by `clusterElementType`; there's no server-side `clusterElementType` query param.
A trigger's cluster elements see no previous-node outputs in the editor (no data pills from other
nodes) — intended, not a gap: a trigger has no upstream nodes, and
`WorkflowNodeParameterFacadeTest.testUpdateClusterElementParameterResolvesVoiceAgentUnderTrigger`
pins that the previous-output lookup is never made for them.

## Known gaps

- **Distributed EE.** `webhook-app` carries the voice endpoints but depends on
  `platform-component-remote-client`, not `platform-component-service`: it has no `ContextFactory`,
  `RemoteClusterElementDefinitionServiceClient.getClusterElement` throws
  `UnsupportedOperationException`, and `components:ai:llm` isn't on its classpath. **Deployed voice
  sessions work only on the monolith `server-app`.** Such a node refuses at mint:
  `BrowserVoiceSessionTokenService.issue` throws `VoiceUnavailableException` when no `ContextFactory`
  is available, and the controller answers 501 with the reason, instead of minting a token that could
  only fail at start.
- **Node affinity is a deployment requirement.** Session state is node-local (in-memory): the
  provider socket, the registry entry, the transcript and the timers live on the node that accepted
  the upgrade, and a live provider socket cannot move. A resume that reaches another node finds no
  session and starts a fresh one, while the original detaches and finalizes as `client_closed` after
  `VoiceSessionRegistry.RESUME_WINDOW`, so one conversation produces two continuation jobs. With more
  than one `server-app` replica the load balancer must pin `/webhooks/{id}/wss` to one node (cookie
  affinity preferred; client-IP affinity breaks on network change). This is documented in the
  quickstart's Limits, not enforced in code. Editor test sessions never resume and are unaffected.
- **Provider errors.** A provider error maps to one `error` event and a `provider_error` close,
  including Deepgram's `{"type":"Error"}` frame. The exception is OpenAI Realtime's per-client-event
  codes (`OpenAiVoiceAgent.RECOVERABLE_ERROR_CODES`: `conversation_already_has_active_response`,
  `invalid_value`), which are logged and keep the call open. OpenAI
  tool replies send `conversation.item.create` at once but hold `response.create` until the
  `response.done` of the response that asked for the call, once all its calls are answered.
- **Provider protocol names — verified 2026-09-14** (design spec, "Verification round 2", has the
  URLs). OpenAI, per the `openai-node` realtime types: `whisper-1` is still a valid
  `audio.input.transcription.model`, and all four model options and the `alloy` default are valid
  (`marin`/`cedar` are recommended). ElevenLabs: `conversation_initiation_client_data` has **no** output-format
  override (`tts` offers only voice/model/stability/speed/similarity), so `outputSampleRate` stays a
  warn-on-mismatch input. An agent's tools live in `conversation_config.agent.prompt.tool_ids`, and the inline
  `tools` array is deprecated. The best-effort check resolves each id via `GET /v1/convai/tools/{id}` and keeps
  `tool_config.name` where `type` is `client`, still reading legacy `tools[].name`.
  `response_cancel_not_active` was dropped from the recoverable codes: no OpenAI-owned source documents it, and
  the element never sends `response.cancel`. The model picker also offers `gpt-realtime-1.5`, `-2`, `-2.1` and
  `-2.1-mini`, all listed in `RealtimeSessionCreateRequest.model`.
- **Phone calls out of scope.** Browser-only. For a number, use the provider's own telephony or a
  dedicated platform, with ByteChef as tools/post-call backend.

## Adding a provider

Extend `AbstractVoiceAgentContractTest` (`server/libs/test/voice-test-support`,
`com.bytechef.test.voice`) against a `FakeProviderWebSocket`: it asserts the stage contract above —
tool registration on start, greeting → `assistant_text`, transcription → `transcript_final`,
interruption → `speech_start`, binary audio at the declared output rate, a tool round-trip through
the fake toolset, one `error` before close — against your frame mapping.
`DeepgramVoiceAgentContractTest`, `OpenAiVoiceAgentContractTest`, `ElevenLabsVoiceAgentContractTest`
are the worked examples. Each provider also has its own frame-mapping tests and a `*ToolFailureTest`,
provider-specific and not part of the shared contract.

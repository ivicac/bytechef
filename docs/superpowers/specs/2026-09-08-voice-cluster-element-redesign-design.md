# Voice: cut the realtime runtime, make the voice agent a trigger cluster element

**Status:** Design | **Owner:** Ivica | **Created:** 2026-09-08 | **Branch:** `0_732`

## Why

Branch `0_732` carries two different voice products under one `websocketTasks` mechanism:

- **Shape A — proxy.** `browser/v1/voiceSession` → `VoiceSessionEngine` → one all-in-one provider stage
  (`deepgram/v1/voiceAgent`: STT + LLM + TTS on Deepgram's side) → browser. ByteChef moves bytes, mints
  tokens, tests in the editor, and runs the SDK chat widget's voice mode. Push-to-talk (`SttProvider` SPI)
  sits beside it.
- **Shape B — own voice runtime.** Twilio media streams (μ-law codec, stream tokens, TwiML, signature
  validation), Infobip calls, multi-stage `STT → ai/agent/v1/realtimeChat → TTS` chains
  (`WebSocketTaskChain`, `cancelTurn` fan-out), the realtime codec actions on Deepgram and ElevenLabs.

Shape B is the whole product of Vapi, Retell, LiveKit, ElevenLabs Agents, OpenAI Realtime and Deepgram
Voice Agent — turn detection, barge-in, endpointing, telephony breadth, recording, compliance — and its own
gap list (reconnect, graceful drain, silence timeout, stalled-provider detection, cost attribution, EE node
affinity, no e2e coverage) is that product's roadmap. ByteChef's buyers already have a voice vendor; what
they need from ByteChef is tools, connections and the post-call workflow. Decision (2026-09-08): **delete
shape B, keep and improve shape A, and position ByteChef as the backend behind voice-agent platforms.**

Part of shape B predates `0_732`: the Twilio call trigger/action, `platform-websocket-webhook-rest`, the
four codec actions and `WebSocketEmitter` landed on `master` on 2026-03-10 (`85494cf063a`). None of it is in
a release (`v1.1.5` contains none of those files), so deleting it on `master` too is a follow-up PR, not a
compatibility question.

Shape A also has an authoring hole: the `websocketTasks` pipeline is a JSON string on the trigger with **no
editor UI at all** — the "Real-Time Workflow" editor the quickstart describes does not exist, and the
pipeline is hand-written in the raw workflow JSON. With exactly one stage allowed, the sub-workflow concept
is over-general anyway. This spec replaces it with the cluster-element model the editor already has for the
AI Agent.

## Goals

1. Remove every shape-B file, code path and property from `0_732` in one verified commit.
2. The voice agent is a cluster element of the `browser/v1/voiceSession` trigger, authored in the standard
   cluster-element UI; `websocketTasks` and `subWorkflow` are gone.
3. Three interchangeable all-in-one providers — Deepgram Voice Agent, OpenAI Realtime, ElevenLabs
   Conversational AI — behind one stage contract.
4. Voice agents can call ByteChef tools (TOOLS cluster elements) through the same policy stack as AI-agent
   tools.
5. The post-session continuation job receives the transcript and tool calls.
6. Session hardening that applies to the proxy shape: client reconnect, server silence timeout.

## Non-goals (recorded so they do not drift back)

Telephony of any kind (Twilio ConversationRelay included); multi-stage pipelines; HITL approval inside a
call; session usage/cost attribution; authoring UI beyond the standard cluster-element panels; EE sticky
routing for `webhook-app`; WebRTC transport; recording or PII redaction of audio.

**Phone calls (decided 2026-09-08: drop).** After this change a caller cannot reach a ByteChef voice workflow
by dialling a number; `twilio/v1/inboundCall` and `twilio/v1/makeCall` are deleted. Phone voice goes through
the vendor's own telephony — ElevenLabs Conversational AI (Twilio import, SIP trunking), OpenAI Realtime
(SIP), Vapi/Retell/Bland (native) — with ByteChef as the tools and post-call backend behind it. Deepgram
Voice Agent has no native telephony, so it is browser-only here. The engine is transport-agnostic, so an
inbound-Twilio transport adapter (a second cluster-root trigger with the same `VOICE_AGENT` slot, media-stream
framing, TwiML + signature validation, stream-token gate, provider configured for μ-law/8 kHz) remains an
additive follow-up if a customer needs "ByteChef number → Deepgram agent"; nothing in this design blocks it.

## Runtime data flow

The WebSocket session still runs on `VoiceSessionEngine`, **outside Atlas**. A voice session is a
long-lived bidirectional stream that ends when the caller hangs up; Atlas models "task completes → advance".
Hosting the session inside a job pinned a worker thread for the whole call and could never start a second
task — that is why the engine exists, and nothing here changes it. What changes is *what* the engine runs
(one cluster element instead of a parsed pipeline) and *where the definition comes from* (the trigger's
`clusterElements` instead of a JSON string).

```
browser mic ──PCM16──► WebhookWebSocketHandler ──► WebSocketEmitter ──► VoiceAgentFunction.handle()
                            │                                                │
                            │   engine: ONE element, resolved from           │  provider WebSocket
                            │   trigger.clusterElements.voiceAgent           ▼
                            │                                    Deepgram / OpenAI Realtime / ElevenLabs
                            │                                    (STT + LLM + TTS on the provider's side)
                            │                                                │
speaker ◄──PCM16──────── bridge ◄── emitter.sendBinary ◄─────────────────────┘
UI      ◄──JSON events── bridge ◄── emitter.send({transcript_final | assistant_text | speech_start | tool_call …})
                                                                             │
                                          provider emits a function call ────┘
                                                    │
                                                    ▼
                                    VoiceAgentTools.call(name, argumentsJson)
                                    └─ ClusterElementToolCallbacks → Component Rules / recorder / guardrails
                                       └─ ClusterElementDefinitionService.executeTool (action or workflow-as-tool)
                                                    │
                                    result frame ───┘ back into the provider WebSocket

WS close ──► handler stops the engine session ──► WorkflowContinuationHelper.createContinuationJob(
               trigger output = {sessionId, transcript, toolCalls, endReason, …})
             └─ Atlas runs the rest of the workflow as an ordinary job
```

Per session:

1. **Upgrade.** The browser mints a single-use token (`POST /webhooks/{id}/voice-session-token`) and opens
   `wss://…/webhooks/{id}?sessionToken=…`. The handler validates it and registers a `VoiceSession`.
2. **Start.** The handler loads the published trigger, takes `ClusterElementMap.of(trigger.getExtensions())`
   and calls `voiceSessionEngine.start(...)`. The engine fetches the single `voiceAgent` element, looks up
   its `VoiceAgentFunction`, evaluates its parameters against the session inputs, registers one emitter,
   wires the bridge, then calls `function.apply(...)` and `handler.handle(emitter)`. The provider element
   opens its own WebSocket to the provider and registers emitter listeners. No pool thread is held from here
   on; everything is callback-driven on the two WebSockets.
3. **During the call.** Inbound browser audio → emitter → provider WS. Provider audio and events → emitter →
   bridge → browser. Provider function call → `VoiceAgentTools.call` on a virtual thread → ByteChef tool →
   result back into the provider WS. The provider's LLM is the brain; ByteChef supplies the prompt,
   connection, tools and the transcript sink.
4. **End.** Browser close, silence timeout, session limit, provider error or server shutdown. The handler
   stops the engine session (which closes the provider WS) and creates the continuation job with the
   session summary as trigger output. From here it is an ordinary Atlas job.

The ByteChef `aiAgent` component is **not** in the loop: its LLM, chat memory and advisors do not run
during a call (that was `ai/agent/v1/realtimeChat`, deleted here). ByteChef-side intelligence enters only
through tools and the post-call workflow.

## 1. Component model

### `VoiceAgentFunction` SPI

New in `platform-component-api`, package `com.bytechef.platform.component.definition.voice`, next to
`ModelFunction`:

```java
@FunctionalInterface
public interface VoiceAgentFunction {

    ClusterElementType VOICE_AGENT = new ClusterElementType("VOICE_AGENT", "voiceAgent", "Voice Agent", true);

    WebSocketHandler apply(
        Parameters inputParameters, Parameters connectionParameters, VoiceAgentContext context) throws Exception;
}
```

`VoiceAgentContext extends ClusterElementContext` adds what a provider needs and cannot read from
`Parameters`:

- `ClusterElementMap clusterElements()` — the element's own nested elements (its TOOLS children);
- `Map<String, ComponentConnection> connections()` — connections resolved for those children;
- `ActionContext actionContext()` — an adapter over the cluster element context (the
  `AiAgentChatTool.ActionContextAdapter` shape), which tool execution requires.

Providers do not touch these directly; they hand the context to `VoiceAgentTools` (§2).

`WebSocketHandler` / `WebSocketEmitter` stay in the SDK's `ActionDefinition` unchanged except that
`WebSocketEmitter` loses `cancelTurn` and the turn-cancel listeners. An **action** can no longer return a
`WebSocketHandler`; only a `VoiceAgentFunction` can, and only the engine invokes one.

### Implementation notes (2026-09-08, from the plan)

Two shapes below were adjusted while planning; the plan is authoritative on them:

- `VoiceAgentContext` is a record `(ActionContext actionContext, VoiceAgentToolset toolset)` rather than an
  extension of `ClusterElementContext`. Provider components are `@AutoService` (no Spring DI), so the tool
  policy stack cannot be built inside them: the engine builds the toolset through a `VoiceAgentToolsetFactory`
  SPI bean (implemented in `components/ai/llm` over `ClusterElementToolCallbacks`) and hands it over in the
  context. Providers reach `json`/`log`/`http` through `context.actionContext()`.
- `getTriggerClusterElementTypes()` is not added. The client already falls back to every declared
  `clusterElementTypes` entry when a component has no per-operation map, the index already records
  `clusterElementTypes` for any `ClusterRootComponentDefinition`, and `browser` has one cluster-root trigger
  with one type. Revisit when a component gets a second cluster-root trigger.

### Triggers as cluster roots

`ClusterRootComponentDefinition` gains
`default Map<String, List<String>> getTriggerClusterElementTypes()`, the trigger-keyed twin of
`getActionClusterElementTypes()`. The `browser` component wraps its definition
(`BrowserComponentDefinitionImpl extends AbstractComponentDefinitionWrapper implements
ClusterRootComponentDefinition`, so it takes a dependency on `platform-component-api` like every other
cluster root) and returns `{voiceSession → [VOICE_AGENT]}`; `getClusterElementTypes()` returns
`[VOICE_AGENT]`, so the existing derivation in `ComponentDefinition` yields `clusterRoot = true` with no
change to that class. `ComponentIndexGenerator` records the trigger map alongside the action map so a
stubbed `browser` in the components list reports `clusterRoot = true` (the "derived flag on a stub" rule in
CLAUDE.md).

### Voice agents are nested cluster roots for TOOLS

Each provider's `voiceAgent` cluster element declares `TOOLS` through
`getClusterElementClusterElementTypes()` — exactly the `AiAgentChatTool` shape — so the editor's nesting
(`isNestedClusterRoot`) and `ClusterElementMap`'s recursion work unchanged.

> **Overridden during implementation** (2026-09-14): Tools ended up as a sibling slot on the
> trigger, not nested inside the voice agent element. See "Implementation deviations" at the end of
> this spec.

### `browser/v1/voiceSession` properties

Keeps `sampleRate`, `echoCancellation`, `noiseSuppression`, `sessionLimitSeconds`. Adds
`silenceTimeoutSeconds` (integer, default 120, `0` disables). Drops `subWorkflow`.

### Workflow JSON

```json
"triggers": [{
  "name": "trigger_1",
  "type": "browser/v1/voiceSession",
  "parameters": {"sampleRate": 16000, "silenceTimeoutSeconds": 120},
  "clusterElements": {
    "voiceAgent": {
      "name": "voiceAgent_1",
      "type": "deepgram/v1/voiceAgent",
      "connectionId": 12,
      "parameters": {"prompt": "…", "greeting": "…", "llmModel": "gpt-4o-mini"},
      "clusterElements": {
        "tools": [{"name": "lookupOrder_1", "type": "shopify/v1/getOrder", "parameters": {}}]
      }
    }
  }
}]
```

`WorkflowTrigger` already collects unknown top-level keys into `extensions`, so
`ClusterElementMap.of(trigger.getExtensions())` works with no domain change.

### Removed from the SDK / platform surface

`WorkflowExtConstants.WEBSOCKET_TASKS` and its reserved-word contributor entry, `WorkflowTrigger.websocketTasks`
(domain, OpenAPI, REST model, generated client type), `WebsocketTasks` parser,
`MultipleConnectionsWebSocketPerformFunction` and the WebSocket perform branch in
`ActionDefinitionServiceImpl`, `RealtimeActionTaskExecutionPostOutputProcessor` and its
`PlatformWorkerConfiguration` wiring.

## 2. Engine and session lifecycle

### Single-stage `VoiceSessionEngine`

```java
VoiceSession start(
    ClusterElementMap triggerClusterElements, Map<String, ComponentConnection> connections,
    Map<String, ?> sessionInputs, @Nullable Long environmentId, @Nullable PlatformType type,
    Consumer<WebSocketEmitter> bridge);

void stop(long sessionId);
```

`start` fetches the one `VOICE_AGENT` element (missing → `IllegalStateException` the handler turns into an
`error` event + close), resolves its `VoiceAgentFunction` through
`ClusterElementDefinitionService.getClusterElement(...)`, evaluates the element's parameters against
`sessionInputs` (`sessionId`, `startedAt`, `testMode`) with the same `Evaluator` call stages use today,
resolves its connection through the cluster-element facade the way the `executeTool` overloads do,
registers one `WebSocketEmitter`, hands it to `bridge`, then calls `function.apply(...)` and
`handler.handle(emitter)`. Inbound browser frames dispatch straight to that emitter. `WebSocketTaskChain`,
`stageNames`, the JSON pipeline parser and "first stage" lookups are gone. `VoiceSession` becomes
`record VoiceSession(long sessionId, WebSocketEmitter emitter, Instant startedAt, Transcript transcript)`.

Barge-in is the provider's job (Deepgram emits `UserStartedSpeaking`, OpenAI `speech_started`, ElevenLabs
`interruption`); the element maps it to `speech_start` and the browser flushes playback. No turn-cancel
plumbing remains in the engine.

### Tools: policy in one place, providers stay dumb

The `components/ai/llm` library gains `VoiceAgentTools`, built on the existing
`ClusterElementToolCallbacks`:

```java
public final class VoiceAgentTools {
    public static VoiceAgentTools of(VoiceAgentContext context);   // reads clusterElements(), connections(), actionContext()
    public List<ToolDefinition> definitions();                     // name, description, JSON-schema input
    public String call(String name, String argumentsJson);         // blocking; run on a virtual thread by the caller
}
```

Providers depend on `components:ai:llm` (OpenAI already does; Deepgram and ElevenLabs add it) and call
these two methods only. Component Rules, the tool execution recorder and guardrail redaction therefore
wrap voice tool calls identically to AI-agent tool calls, with no tool logic in the engine or any
provider. `call` never throws to the provider: an unknown name or a failing tool returns an error string
the provider forwards as the tool result, so the agent can say so aloud.

**HITL approval inside a call is unsupported.** There is no job to suspend. An approval-gated tool returns
`"This action requires approval and is not available during a voice call."` as its result. Documented as a
limitation in `.agents/voice.md` and the user docs.

### Session-end payload

Both handlers' bridges accumulate every `transcript_final` / `assistant_text` / `tool_call` / `tool_result`
event into `VoiceSession.transcript` (capped at 2 000 entries; sessions are already time-capped). On close
the continuation job's trigger output is:

```json
{
  "sessionId": "b7…",
  "startedAt": "2026-09-08T10:15:02Z",
  "durationSeconds": 184,
  "endReason": "client_closed | session_limit | silence_timeout | provider_error | server_shutdown",
  "transcript": [
    {"role": "assistant", "text": "Hi! How can I help?", "at": "…"},
    {"role": "user", "text": "Where is my order 4411?", "at": "…"}
  ],
  "toolCalls": [{"name": "lookupOrder_1", "arguments": {"id": "4411"}, "result": "…", "at": "…"}]
}
```

The `browser/v1/voiceSession` trigger's declared output matches this shape so it is visible in the data-pill
panel. The Twilio-shaped `callSid / callStatus / closeStatusCode / closeReason / callDuration` keys go away.

### Naming and registry

`callSid` → `sessionId` end-to-end (registry, handlers, client protocol, test handler's `test-` prefix).
`CallSessionRegistry` → `VoiceSessionRegistry`; `registerPendingCall`, `updateCallSid`, `updateCallStatus`,
`isCallActive`, `actionJobId`, `mainJobId`, `subWorkflowId` and `callStatus` — all outbound-Twilio-only —
are removed. A closed session stays registered for 30 s to support resume (§4).

### Silence timeout (server half of hardening)

The handler records the last inbound-audio instant per session. A scheduled check (same executor as the
max-duration close) closes the WS with reason `silence_timeout` after `silenceTimeoutSeconds` of no inbound
audio. Provider audio arriving does not reset it — the point is a user who walked away.

### Unchanged

Engine outside Atlas; `WorkflowContinuationHelper`; `WorkflowTestWebSocketHandler` reads the draft
`Workflow` and calls the same `start`; single-use session tokens; `sessionLimitSeconds` and the global max
duration; `VoiceMetricsRecorder`. The engine no longer touches `ActionDefinitionFacade`. The known EE gap —
sessions are node-local, `webhook-app` needs sticky routing — is documented in `.agents/voice.md`, not fixed.

## 3. Providers

### Stage contract

Honoured by every `voiceAgent` element and proven once by a shared contract test (§6):

- **Inbound:** raw PCM16 binary at the trigger's `sampleRate`.
- **Outbound binary:** PCM16 at the element's declared `outputSampleRate`. The engine puts that value on the
  `connected` event so the browser builds playback buffers at the right rate. Today the client plays back at
  the capture rate and the quickstart tells users to align the two by hand; that footgun goes.
- **Outbound JSON events:** `transcript_interim`, `transcript_final {text}`, `assistant_text {text}`,
  `speech_start`, `error {message}`, plus new `tool_call {name, arguments}` and `tool_result {name, ok}`.
- **Tools:** on start read `VoiceAgentTools.definitions()` and register them in the provider session
  config; on a provider function-call event run `VoiceAgentTools.call` on a virtual thread and reply in the
  provider's frame. Unknown names get an error reply, never an exception out of the handler.
- **Close:** provider WS closed on `addCloseListener`; provider error → one `error` event, then close with
  `provider_error`.

### `deepgram/v1/voiceAgent`

The existing action re-homed as a cluster element; properties unchanged (`prompt`, `greeting`, `language`,
`llmProvider`/`llmModel`, `ttsProvider`/`ttsModel`, encodings and sample rates). Adds
`agent.think.functions[]` to the `Settings` message; handles `FunctionCallRequest` → `FunctionCallResponse`.
The `toBrowserVoiceEvent` normaliser stays.

### `openai/v1/voiceAgent`

New, in `components/ai/llm/open-ai`. Realtime API over `wss://api.openai.com/v1/realtime`, bearer from the
existing OpenAI connection. Properties: `model` (default `gpt-realtime`), `instructions`, `voice`,
`greeting` (a first `response.create` instructing the model to greet), `inputAudioTranscription` (default
on — without it there is no user transcript), server VAD. Mapping: `input_audio_buffer.speech_started` →
`speech_start`; `conversation.item.input_audio_transcription.completed` → `transcript_final`;
`response.output_audio.delta` → binary; `response.output_audio_transcript.done` → `assistant_text`;
`response.function_call_arguments.done` → tool call → `conversation.item.create(function_call_output)` +
`response.create`. Output is PCM16 at 24 kHz. Input is sent as `input_audio_buffer.append` with the session
input format set to the trigger rate where OpenAI supports it; a 16 kHz trigger is resampled to 24 kHz by a
small `Pcm16Resampler` (lifted from `TwilioAudioCodec.resamplePcm16` before that class is deleted).

### `elevenLabs/v1/voiceAgent`

New. Conversational AI over the signed-URL WebSocket (`GET /v1/convai/conversation/get-signed-url`, existing
`xi-api-key` connection, so private agents work). The agent — prompt, voice, LLM, **tools** — is configured
on ElevenLabs' side. Properties: `agentId` (dynamic options from `GET /v1/convai/agents`), optional
overrides `prompt`, `firstMessage`, `language` (sent in `conversation_initiation_client_data`),
`outputSampleRate` (default 16000). Mapping: `user_transcript` → `transcript_final`, `agent_response` →
`assistant_text`, `interruption` → `speech_start`, `audio` → binary, `ping` → `pong`. Tools by **name
matching**: the user declares client tools on the ElevenLabs agent with the same names as the ByteChef
TOOLS elements; `client_tool_call` runs the matching tool and replies `client_tool_result`; an unmatched
name replies `is_error: true`. On start the element fetches the agent once and logs a warning for every
ByteChef tool the ElevenLabs agent does not declare. It never mutates the user's agent.

### Provider protocol verification

The three mappings above are written from the providers' documentation as recalled on 2026-09-08. The
implementation plan makes "verify event names and session-config fields against the live docs" the first
step of each provider task; a mismatch changes the mapping table in this spec, not the contract.

**openai/v1/voiceAgent (Task 18, 2026-09-14):** the event names in the mapping above are confirmed by the
live docs, but `session.update`'s GA shape nests audio config under `session.audio.input.format` /
`session.audio.output.format` as an object (`{"type": "audio/pcm", "rate": 24000}`), not a bare `"pcm16"`
string, and needs `session.type: "realtime"` and `session.output_modalities: ["audio"]` alongside it —
`platform.openai.com/docs/api-reference/realtime` returned 403 and `developers.openai.com`'s reference
sub-pages 404'd, so this was cross-checked via `developers.openai.com/api/docs/guides/realtime-conversations`
and a Microsoft Learn Azure OpenAI Realtime page (which documents both the legacy flat fields and the GA
nested ones); the transcription model name (`whisper-1`) and the exact GA model/voice option lists were not
found on a fetchable page in this task; "Verification round 2" below confirms them from the official SDK types.

**elevenLabs/v1/voiceAgent (Task 19, 2026-09-14):** the signed-URL endpoint
(`GET /v1/convai/conversation/get-signed-url?agent_id=…` returning `{"signed_url": …}`), the agents-list endpoint
(`GET /v1/convai/agents`, `agents[].name`/`agent_id`, `has_more`/`next_cursor` paging), and the exact
`conversation_initiation_client_data` (`conversation_config_override.agent.{prompt.prompt, first_message,
language}`), `client_tool_call` (`client_tool_call.{tool_name, tool_call_id, parameters}`) and
`client_tool_result` (`{tool_call_id, result, is_error}`) shapes were all confirmed on a live, fetchable page
(`elevenlabs.io/docs/eleven-agents/api-reference/eleven-agents/websocket`; the `libraries/web-sockets` and several
`api-reference/conversational-ai/*` guesses 404'd first). The mapping only switches on the frame's `type` and never inspects `interruption_event`'s contents, so the
contract test's `{"event_id":1}` fixture is fine — and, as "Verification round 2" below found, it is also the
published shape (an earlier note here claimed a `reason` field; the schema has `event_id`). Two names were left unverified by this task (both resolved in "Verification round 2" below): whether `conversation_initiation_client_data` has any override for the
agent's output sample rate (no fetchable page documented one, so `outputSampleRate` is accepted as an input but
not sent on the wire), and the exact JSON path for an agent's configured client-tool names on
`GET /v1/convai/agents/{agentId}` (the fetched reference names both a `tools` array with a `name` field, marked
deprecated in favor of `tool_ids`, and `tool_ids`, which are ids rather than names — the best-effort check reads
`conversation_config.agent.prompt.tools[].name`, which is a legacy field but the only one carrying names inline
without a second lookup). **Fix round 1 (2026-09-14):** the same confirmed
`eleven-agents/api-reference/eleven-agents/websocket` page's `conversation_initiation_metadata` shape gives the
inbound-audio field name too — `conversation_initiation_metadata_event.user_input_audio_format`, a string like
`"pcm_16000"` — used to resample the browser's captured audio (16 kHz or 24 kHz, per the `browser/v1/voiceSession`
trigger) to the agent's actual input rate via `Pcm16Resampler`, defaulting to 16000 until that frame arrives or
when the field is absent/malformed. No name in this addition is unverified.

**Verification round 2 (2026-09-14).** Every name the two tasks above left open was re-checked against official
sources. OpenAI's reference pages still 403/404, so the authority is the Stainless-generated `openai-node` types, which
are built from OpenAI's own OpenAPI spec
(`raw.githubusercontent.com/openai/openai-node/master/src/resources/realtime/realtime.ts`), plus the
`developers.openai.com/api/docs/guides/realtime-*.md` guides:

- *Transcription model* — `AudioTranscription.model` lists `whisper-1`, `gpt-transcribe`, `gpt-live-transcribe`,
  `gpt-4o-mini-transcribe`, `gpt-4o-transcribe`, `gpt-4o-transcribe-diarize` and `gpt-realtime-whisper`. `whisper-1` is
  still valid; the shape `session.audio.input.transcription: {model}` is confirmed. No code change.
- *Model list* — `RealtimeSessionCreateRequest.model` still contains all four options the element offers
  (`gpt-realtime`, `gpt-realtime-mini`, `gpt-4o-realtime-preview`, `gpt-4o-mini-realtime-preview`). It also lists
  newer ids, and `gpt-realtime-1.5`, `gpt-realtime-2`, `gpt-realtime-2.1` and `gpt-realtime-2.1-mini` were **added** to
  the picker (full models, then minis, then previews); dated snapshot ids were not. The SDK states no default;
  `gpt-realtime` is kept.
- *Voice list* — `RealtimeAudioConfigOutput.voice` lists `alloy`, `ash`, `ballad`, `coral`, `echo`, `sage`,
  `shimmer`, `verse`, `marin`, `cedar`, or a custom `{id}`, and recommends `marin`/`cedar` for quality. No default is
  documented. The element's free-text `voice` default, `alloy`, is valid. No code change.
- *Events and fields* — `SessionUpdateEvent` (`type: 'session.update'`, `session: RealtimeSessionCreateRequest` with
  `type: 'realtime'`, `output_modalities`, `audio.input.format`/`audio.output.format` as `{type: 'audio/pcm', rate:
  24000}`, `audio.input.transcription`, `audio.input.turn_detection`, `tools`), `input_audio_buffer.append`,
  `conversation.item.create` with a `function_call_output` item (`call_id`, `output`), `response.create`,
  `session.updated`, `input_audio_buffer.speech_started`,
  `conversation.item.input_audio_transcription.completed` (`transcript`), `response.output_audio.delta` (`delta`),
  `response.output_audio_transcript.done` (`transcript`), `response.function_call_arguments.done` (`call_id`, `name`,
  `arguments`, `response_id`), `response.done` and `RealtimeErrorEvent.error.{code, event_id}` all match the code.
  The element sends no `turn_detection`, and the speech-to-speech default is still server VAD —
  `guides/realtime-vad.md`: "enabled by default in speech-to-speech Realtime sessions". So `speech_started` still
  arrives. No code change.
- *Recoverable error codes* — `RealtimeError.code` is typed only as `string`, so no schema enumerates the codes.
  `invalid_value` is confirmed by an example `error` payload in `guides/realtime-conversations.md`.
  `conversation_already_has_active_response` is confirmed by a server payload quoted in the official
  `openai/openai-agents-python` issue #1907. `response_cancel_not_active` appears in no OpenAI-owned source that could
  be fetched (only third-party reports such as pipecat-ai/pipecat#3755). The element never sends `response.cancel`,
  so the code was **removed** from `RECOVERABLE_ERROR_CODES` rather than kept on an unverified name. Such an error
  now ends the call like any other unlisted code.

ElevenLabs, from the Fern-generated markdown of the confirmed pages (`elevenlabs.io/docs/eleven-agents/api-reference/…
.md`):

- *Output-format override* — `ConversationInitiationClientDataConversationConfigOverrideTts` has only `model_id`,
  `voice_id`, `supported_voices`, `stability`, `speed`, `similarity_boost` and `pronunciation_dictionary_locators`. Its
  `…OverrideConversation` sibling has only `text_only` and `max_duration_seconds`. `conversation_initiation_client_data`
  therefore has **no** output audio format or sample-rate override (`…/eleven-agents/websocket.md`).
  `outputSampleRate` stays a warn-on-mismatch input checked against
  `conversation_initiation_metadata_event.agent_output_audio_format`. No code change.
- *Agent tool names* — `GET /v1/convai/agents/{agent_id}` returns `conversation_config.agent.prompt.tool_ids` ("A list
  of IDs of tools used by the agent"). The inline `prompt.tools` array is marked deprecated — "use tool_ids instead"
  (`…/agents/get.md`). Names come from `GET /v1/convai/tools/{tool_id}`, as `tool_config.name` with
  `tool_config.type: client` (`…/tools/get.md`). **Code changed:** the best-effort check now resolves every `tool_ids`
  entry through the tools endpoint, keeping only client tools and skipping any id whose lookup fails. It still reads
  the deprecated inline `tools[].name` for older agents, on the same background virtual thread, so it never blocks
  start.
- *Frame fields* — `interruption_event.event_id` (integer; no `reason`), `conversation_initiation_metadata_event.{
  conversation_id, agent_output_audio_format, user_input_audio_format}`, `ping_event.{event_id, ping_ms}` → `pong`
  with `event_id` ("ID of the ping event this pong responds to"), `user_audio_chunk` (base64),
  `agent_response_event.agent_response`, `user_transcription_event.user_transcript` and `audio_event.audio_base_64` all
  match the code. No code change.

### Removed

`deepgram/v1/realtimeListen`, `deepgram/v1/realtimeSpeak`, `elevenLabs/v1/createRealtimeSpeech`,
`elevenLabs/v1/createRealtimeTranscript` (codec stages only meaningful in a chain), `ai/agent/v1/realtimeChat`.

## 4. Client

### Editor: the trigger is a cluster root

Server first. `WorkflowTriggerDTO` gains `clusterRoot` and `clusterElements`, built in `WorkflowFacadeImpl`
the way `WorkflowTaskDTO`'s are (`componentDefinitionService.isClusterRoot`,
`ClusterElementMap.of(trigger.getExtensions())`); the REST `WorkflowTriggerModel` and the generated client
`WorkflowTrigger` type follow. `ClusterRootComponentConnectionFactory` resolves cluster-element connections
hanging off a trigger as well as a task.

Client. `convertTaskToNode(trigger, definition, true)` already yields `type: 'clusterRoot'` once
`trigger.clusterRoot` is populated, so the canvas renders the browser trigger as a cluster frame with a
required **Voice Agent** slot and, nested inside it, the **Tools** slot — the AI Agent box-mode UI. The
`trigger`-gated branches in `useWorkflowNodeDetailsPanel` (component-definition query, operation switch,
save payload) and `saveWorkflowDefinition`'s trigger branch are opened up so a trigger node can carry and
diff `clusterElements` the way the task branch does (`upsertTrigger` must not drop the key).
`WorkflowNodesPopoverMenuOperationList` already seeds `clusterElements: {}` for any cluster-root component,
trigger included. Voice agent elements are discoverable the normal way: components whose cluster elements
declare type `VOICE_AGENT` appear in the slot's picker.

### Test panel

`WorkflowTestChatPanel` drops the `websocketTasks` probe. Voice-capable = a trigger of type
`browser/v1/voiceSession` with a configured `voiceAgent` element (both on the DTO). An empty slot shows
"Add a Voice Agent to the trigger to test with voice" instead of the current server-side close.

### Protocol changes in `BrowserVoiceSession` (client and SDK copies, kept in lockstep)

`callSid` → `sessionId`; `connected` carries `outputSampleRate`, used for playback buffers; `tool_call` /
`tool_result` render in `VoiceModeLayout` as a transient status line ("Looking that up…");
`session_end {reason}` maps to a typed status so the layout can say "Ended: silence timeout" rather than a
bare close.

### Reconnect (client half of hardening)

On an `onclose` that is neither user-initiated nor a terminal `session_end`, `BrowserVoiceSession` retries
with backoff 1 s → 2 s → 4 s (three attempts), minting a fresh single-use token through the existing
endpoint and sending the previous `sessionId` on the upgrade URL. The server treats a matching session that
closed within the last 30 s as a resume: it re-attaches the bridge to the *same* engine session, so the
provider WS and its conversation context survive the blip. An unknown or expired `sessionId` starts a fresh
session. Status surfaces as `reconnecting`. Audio buffered during the gap is dropped, not replayed.

### SDK widget

`voiceWebhookUrl` stays; `useAutomationChatVoiceSession` picks up only the protocol renames. README example
becomes the cluster-element workflow. Push-to-talk is untouched.

## 5. Removal inventory and the master follow-up

One removal commit on `0_732`, first in the sequence, verified green on its own before any additive work.

| Area | Deleted |
|---|---|
| Twilio calls | `TwilioMakeCallAction`, `TwilioInboundCallTrigger`, `TwilioSignatureValidator`, `TwilioStreamToken` (+ tests), `TWILIO_INBOUND_CALL_TRIGGER_PLAN.md`, `TWILIO_OUTBOUND_CALL_ACTION_PLAN.md`; handler registrations; `twilio_v1.json` regenerated |
| Infobip calls | `InfobipMakeCallAction`, `InfobipInboundCallTrigger`, `InfobipSignatureValidator` (+ tests); registrations; `infobip_v1.json` regenerated |
| `platform-websocket-webhook-rest` | `TwimlController`, `TwilioCallbackController`, `TwilioSignatureValidator`, `TwilioAudioCodec` (after lifting `resamplePcm16` into `Pcm16Resampler`), `TwilioMediaStream`, `TwilioStreamToken`, `WebSocketTaskChain`, `WebsocketTasks`, `TriggerCompletionServiceImpl` (+ `TriggerCompletionService` if nothing else uses it), their tests; `bytechef.twilio.stream-token.secret` |
| Codec stages | `DeepgramRealtimeListenAction`, `DeepgramRealtimeSpeakAction`, `ElevenLabsCreateRealtimeSpeechAction`, `ElevenLabsCreateRealtimeTranscriptAction`; snapshots regenerated |
| AI agent / platform | `AiAgentRealtimeChatAction`, `MultipleConnectionsWebSocketPerformFunction`, the WebSocket perform branch in `ActionDefinitionServiceImpl` + `ActionDefinitionServiceWebSocketPerformTest`, `RealtimeActionTaskExecutionPostOutputProcessor` (+ test, + worker configuration wiring) |
| Configuration | `WorkflowExtConstants.WEBSOCKET_TASKS`, `WorkflowTrigger.websocketTasks`, OpenAPI/REST/generated-client field, `WebsocketTasksReservedWordTest`, reserved-word contributor entry |
| Specs and plans | The AI Hub voice family — `docs/superpowers/specs/2026-05-12-ai-hub-voice-design.md`, `…-ai-hub-voice-path-b-plan.md`, `…-ai-hub-voice-tts-strategy.md`, `2026-05-13-ai-hub-voice-cost-and-compliance-design.md`, `…-lifecycle-and-gaps-design.md`, `…-path-b-v1.3-design.md`, `…-production-readiness-design.md`, `…-ux-i18n-docs-design.md`, `…-widget-design.md` (the AI Hub realtime backend they describe was already removed in May) — and `2026-05-12-voice-agent-runtime-tier1-design.md` (the chain runtime). Specs of surviving surfaces stay, each with a one-line "superseded by this spec for the realtime half" note at the top: `2026-05-12-browser-voice-runtime-tier1-design.md`, `…-browser-voice-tier1-deepgram-plan.md`, `…-workflow-test-chat-voice-design.md`, `…-automation-chat-widget-voice-design.md`, `2026-05-19-voice-support-redesign-design.md` and its plan (still the design of record for push-to-talk) |

Carve consequences that are edits, not deletions: `WebhookWebSocketHandler` loses the Twilio media-frame
branch, `streamSid`, μ-law transcoding, the stream-token gate and the outbound-call (`subWorkflowId`) path;
`CallSessionRegistry` → `VoiceSessionRegistry` (§2); `WebSocketEmitter` loses the turn-cancel API;
`webhook-app` keeps its dependency on the module.

No revival branch is created; git history is the archive.

**Master follow-up.** A separate PR against `master` deletes the master-resident originals: the Twilio pieces
of the websocket module, `TwilioInboundCallTrigger`/`TwilioMakeCallAction`, the four codec actions,
`WebSocketStreamTaskExecutionPostOutputProcessor` (job-sync and worker), `TriggerCompletionServiceImpl`, and
regenerates `twilio_v1.mdx` / `elevenlabs_v1.mdx` / `deepgram_v1.mdx`. Filed after this lands so `0_732`'s
next rebase is deletion-vs-deletion.

## 6. Tests

**Server.**

- `VoiceAgentIntTest` becomes the engine contract test: single-element start/stop; parameter evaluation
  against session inputs; transcript accumulation; `silence_timeout` close reason; resume by `sessionId`
  re-attaches the same engine session; tool round-trip through a fake `VoiceAgentFunction` whose fake
  provider emits a function call, proving `VoiceAgentTools` runs the ByteChef tool and the result reaches
  the provider. Multi-stage and barge-in cases are removed.
- `AbstractVoiceAgentContractTest` (ai/llm test fixtures), parameterised per provider against a fake
  provider WebSocket server: greeting → `assistant_text`; user audio → `transcript_final`; interruption →
  `speech_start`; binary out at the declared `outputSampleRate`; function call → tool executed → reply
  frame; provider 4xx → single `error` then close. Each provider adds only its frame-mapping unit tests.
- `ComponentDefinitionRegistryIndexTest` / `ComponentIndexTest`: the index records trigger cluster element
  types and a stubbed `browser` reports `clusterRoot = true`.
- `WorkflowFacadeImpl` test: trigger DTO carries `clusterRoot` / `clusterElements`;
  `ClusterRootComponentConnectionFactory` resolves a connection on a trigger's element.
- Component snapshots regenerated for `browser`, `deepgram`, `elevenlabs`, `openai`, `twilio`, `infobip`,
  `ai-agent` (two-run procedure from CLAUDE.md).

**Client.** `BrowserVoiceSession` (both copies): reconnect backoff + fresh token + `sessionId` carry-over;
`outputSampleRate` playback; `tool_call` / `session_end` events. `WorkflowTestChatPanel`: voice gate on
trigger type + configured element. Editor: a trigger node with `clusterRoot: true` renders the cluster frame
and the details panel opens the Voice Agent slot; `saveWorkflowDefinition` persists `clusterElements` on a
trigger.

## 7. Docs

`docs/voice/quickstart.md`, `docs/voice/editor-testing.md` and their `docs/content/…/build/voice/` mirrors
(still `comingSoon: true`) are rewritten around the cluster-element flow: add trigger → pick Voice Agent →
optionally add Tools → test with the mic → deploy → widget; plus a provider table (where the agent is
configured, tool model, output rate). `docs/examples/voice/deepgram-voiceagent.json` regenerated in the new
shape; `openai-voiceagent.json` added. New `.agents/voice.md` deep-dive — engine-outside-Atlas invariant,
stage contract, tool-policy path, no HITL in a call, node-affinity gap, `sessionId` protocol — with a
one-line pointer in CLAUDE.md's deep-dive table. `.agents/coming-soon-inventory.md`'s voice paragraph
updated.

## 8. Open items carried to the plan

- Confirm `TriggerCompletionService` has no consumer outside the deleted Twilio path before removing the API.
- Confirm the OpenAI Realtime GA session-config field names (`audio.input.format`, transcription enable
  flag) and whether a 16 kHz input format is accepted; otherwise the resampler path is mandatory.
- Confirm ElevenLabs' default WebSocket output format for Conversational AI agents (drives the
  `outputSampleRate` default).

## Implementation deviations (2026-09-14)

Where the build diverged from this spec. Detail and rationale: `.agents/voice.md`.

- **Tools as a trigger slot, not nested.** §1 "Voice agents are nested cluster roots for TOOLS"
  planned `TOOLS` as a nested cluster-element type declared by each provider's `voiceAgent` element.
  Built instead as a second, sibling slot declared directly on the `browser/v1/voiceSession` trigger
  (`BrowserComponentHandler.BrowserComponentDefinitionImpl.getClusterElementTypes()` returns both
  `VOICE_AGENT` and `TOOLS`). Nesting would have forced Deepgram, OpenAI and ElevenLabs to become
  cluster roots themselves, breaking their existing plain actions in the editor and the AI Hub.
  `VoiceAgentToolsetFactoryImpl` reads `tools` out of the *trigger's* extensions, not the voice
  agent element's own.
- **`ApprovalUnavailableException` replaces a class-name gate check.** §2's "HITL approval inside a
  call is unsupported" is enforced by `ToolApprovalRequests.raise` throwing a typed
  `ApprovalUnavailableException` (an `IllegalStateException` subclass) whenever
  `actionContext.getResumeUrl()` is null, caught specifically by `ToolCallbackVoiceAgentToolset` to
  produce the spoken-friendly refusal. This also covers a Component Rule's own approval requirement,
  not only an explicit approval-channel tool.
- **Task 25b: editor facades resolving trigger cluster elements.** §4's "Editor: the trigger is a
  cluster root" work was not in the original task breakdown; it was added once the plan reached the
  editor and found no existing path for a trigger to carry `clusterElements` at all.
  `ClusterRootWorkflowNode` (`platform-configuration-api`) is the new seam every cluster-element
  editor facade goes through so a trigger root behaves identically to a task root.
- **The picker filter is client-side, with no server `clusterElementType` filter.** The Voice
  Agent/Tools component picker (`WorkflowNodesPopoverMenuComponentList.hasClusterElementType`)
  filters the already-fetched component list in the browser; no server endpoint narrows the fetch by
  `clusterElementType`. Acceptable at today's component count.
- **Seam-level socket serialization.** Not specified in this design. `ProviderWebSocketConnector.jdk()`
  wraps every provider WebSocket in a `SerializedWebSocket`
  (`SerializingWebSocketListener`) so every listener callback and `connect()` itself return the
  identical instance, working around the JDK not guaranteeing `onOpen` has run before
  `buildAsync(...).join()` returns.
- **More end reasons, and a terminal hang-up.** §2's `endReason` list gained `provider_closed`: a
  provider that hangs up on its own (an ElevenLabs `end_call` tool, its own duration cap) completes the
  emitter, and both handlers end the session instead of leaving the browser on a dead line.
  `server_shutdown` is produced by `WebhookWebSocketHandler`'s `SmartLifecycle.stop()`. A deliberate
  client hang-up closes with code 1000 and reason `client_closed` and ends the session at once; only a
  close without that reason is treated as a drop and held for resume. A session ends with a server
  reason only when the handler recorded closing that socket itself (`closeForServerReason`); the
  reason in a client's close frame is never trusted, so a client sending `silence_timeout` is only
  detached.
- **Refusals have their own close reasons and run nothing.** A session whose workflow is disabled, or
  whose trigger has no Voice Agent (`VoiceSessionEngine.NoVoiceAgentException`, caught by type), is
  refused: an error frame, then a close with reason `workflow_disabled` or `voice_agent_missing`
  (`POLICY_VIOLATION` deployed, `BAD_DATA` for the editor's missing agent), counted as a session error,
  with no continuation job. The browser client and SDK treat both reasons as terminal and surface the
  error frame once. A start failure is different: a provider that cannot connect ends as
  `provider_error` and still runs the post-call workflow, with an empty transcript.
- **Shutdown waits for in-flight work.** `stop()` raises a `shuttingDown` flag before it finalizes the
  live sessions, refuses new upgrades with `SERVICE_RESTARTED` / `server_shutdown` before their token
  is spent, and joins session starts and handed-off session ends for up to 30 seconds (virtual threads
  are daemon, so an unjoined continuation job dies with the JVM). A start that joins the live sessions
  after `stop()` read them finalizes itself as `server_shutdown`; one whose caller already hung up is
  stopped and dropped instead. `start()` clears the flag, so a restarted bean accepts upgrades again.
- **Editor tokens bind the principal's effective environment.** §4's editor token mint is gated by
  `hasWorkflowScopeInEnvironment(#workflowId, 'WORKFLOW_EDIT', #environmentId)`, which authorizes
  `PrincipalEnvironment.resolveEffectiveEnvironmentId(environmentId)`: a confined api-key principal's
  own environment wins over the request. The controller resolves that same value on the request thread
  and binds it into the token; `WorkflowTestWebSocketHandler` runs the session in the token's
  environment and ignores any `environmentId` query on the upgrade rather than comparing it.
- **Not every provider error is fatal.** §3's "provider error → one `error` event, then close" still
  holds, including Deepgram's `Error` frame, except for OpenAI Realtime errors about a single client
  event (`conversation_already_has_active_response`, `invalid_value`),
  which are logged and keep the call open. A rejected `session.update` is always fatal, whatever its
  code: `session.update` carries an `event_id`, and an error naming it, or any error before
  `session.updated` arrives, ends the call rather than letting it continue as a default agent with no
  instructions, voice or tools. OpenAI's `response.create` after a tool reply waits for the
  `response.done` of the response that asked for the call.
- **The distributed-EE gap is real, not theoretical.** §2's "known EE gap" note said sessions are
  node-local and `webhook-app` needs sticky routing. In practice `webhook-app` cannot run a voice
  session at all today: it depends on `platform-component-remote-client`, whose
  `RemoteClusterElementDefinitionServiceClient.getClusterElement` throws
  `UnsupportedOperationException`, and `components:ai:llm` is not on its classpath. Deployed voice
  sessions work only on the monolith `server-app`; sticky routing is a prerequisite that hasn't been
  built, not yet a gap in an otherwise-working path. Such a node now refuses at mint: the voice
  session token endpoint answers 501 when no `ContextFactory` is available, rather than minting a
  token whose session could only fail at start.
- **Node affinity is a documented deployment requirement, not a code change.** A live call stays on
  the node that accepted its WebSocket, and a resume that reaches another node starts a new call while
  the original finalizes after the resume window, running the post-call workflow twice. Multi-replica
  deployments must configure session affinity for `/webhooks/{id}/wss` at the load balancer; the
  quickstart's Limits section says how and what happens without it.

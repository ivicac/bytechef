# Voice Cluster-Element Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the shape-B voice runtime (Twilio/Infobip calls, multi-stage chains, codec actions, `realtimeChat`), make the voice agent a cluster element of the `browser/v1/voiceSession` trigger, add OpenAI Realtime and ElevenLabs Conversational AI as all-in-one providers with ByteChef tool calling, and harden the browser session (reconnect, silence timeout, transcript hand-off).

**Architecture:** `VoiceSessionEngine` stays a session host outside Atlas but runs exactly one `VOICE_AGENT` cluster element read from `trigger.clusterElements`, handed a `VoiceAgentContext` that carries a real `ActionContext` and a `VoiceAgentToolset` (SPI in `platform-component-api`, implemented in `components/ai/llm` on top of `ClusterElementToolCallbacks`, so Component Rules / recorder / guardrails wrap voice tool calls exactly like AI-agent tool calls). Providers are dumb wire-protocol adapters behind one stage contract, proven by a shared contract test in a new `voice-test-support` module. The editor renders the browser trigger as a cluster root through the same `clusterRoot` DTO flag tasks already use.

**Tech Stack:** Java 25, Spring Boot 4, JDK `java.net.http.WebSocket`, Spring AI `ToolCallback`, JUnit 5 + Mockito + AssertJ, Caffeine; React 19, TypeScript, Vitest 4, `@assistant-ui/react`.

**Spec:** [docs/superpowers/specs/2026-09-08-voice-cluster-element-redesign-design.md](../specs/2026-09-08-voice-cluster-element-redesign-design.md)

## Global Constraints

- Work happens in the worktree `.claude/worktrees/voice-cluster-element` on branch `voice-cluster-element` (based on `0_732`). Never touch `0_732` directly.
- Commit messages are **subject-only, one line, no body, no trailers** (repo convention; overrides any default attribution guidance). Server: `Voice - <description>`. Client: `Voice client - <description>`. Docs: `Voice docs - <description>`.
- Run `./gradlew spotlessApply` before every commit touching Java; `cd client && npm run format` before every commit touching client code; `cd sdks/frontend/automation/chat/library && npm run lint:fix` for the SDK.
- Never judge a Gradle run piped into `tail`/`grep`: redirect to a file under `$SCRATCH` (`/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f10717b2-2595-4bc6-89a9-143402ee9250/scratchpad`), check `$?` on its own line, then grep the file for `^> Task .* FAILED`. Use `--continue`.
- Component snapshot regeneration takes two runs: delete `src/test/resources/definition/<name>_v1.json` AND `build/resources/test/definition/`, run the component test (it fails with `NullPointerException: url`), run again (passes).
- Java: no `_` in test method names; no `TODO` comments; interface-required unused params get `@SuppressWarnings("PMD.UnusedFormalParameter")`; blank line before control statements and between a variable assignment and the statement that uses it; no `Impl` in test class names; unit tests end in `Test`, integration tests in `IntTest`.
- Client: object keys alphabetically sorted; interfaces end in `I` or `Props`; `useRef` variables end in `Ref`; Lucide icons imported with the `Icon` suffix; `twMerge` not `cn`; hooks ordered `useState → useRef → stores → custom hooks → useMemo/useCallback → useEffect`.
- **Spec deviation (recorded):** the spec's `getTriggerClusterElementTypes()` twin is **not** added. The client already falls back to every declared `clusterElementTypes` entry when a component has no per-operation map (`clusterElementsUtils.ts:29-38`), the index already records `clusterElementTypes` for any `ClusterRootComponentDefinition`, and `browser` has one cluster-root trigger with one type. Revisit when a component gets a second cluster-root trigger.
- **Spec deviation (recorded):** `VoiceAgentContext` does not extend `ClusterElementContext`. It carries an `ActionContext` (which tool execution needs anyway) plus a `VoiceAgentToolset`; providers reach `json`/`log`/`http` through `context.actionContext()`. Provider components are `@AutoService` (no Spring DI), so the tool policy stack cannot be built inside them — the engine builds the toolset through a `VoiceAgentToolsetFactory` SPI bean implemented in `components/ai/llm` and hands it over in the context.

## Phases

| Phase | Tasks | Ends green with |
|---|---|---|
| 0 Baseline + carve | 1–4 | Shape B gone; existing single-stage browser voice still works |
| 1 Component model | 5–8 | `browser` is a cluster root; trigger DTO carries `clusterRoot`/`clusterElements` |
| 2 Engine + handlers | 9–14 | Sessions start from `clusterElements.voiceAgent`; `websocketTasks` gone |
| 3 Tool bridge | 15 | `VoiceAgentToolsetFactory` bean builds policy-wrapped tools |
| 4 Providers | 16–21 | Deepgram re-homed; OpenAI + ElevenLabs added; action-perform WebSocket path deleted |
| 5 Client | 22–27 | Editor, test panel, SDK widget on the new model + reconnect |
| 6 Docs + wrap-up | 28–30 | Docs, `.agents/voice.md`, snapshots, final verification |

Throughout, `$SCRATCH` means `/private/tmp/claude-502/-Volumes-Data-bytechef-bytechef/f10717b2-2595-4bc6-89a9-143402ee9250/scratchpad` and `$WS` means `server/libs/platform/platform-webhook/platform-websocket-webhook-rest`.

---

## Phase 0 — Baseline and carve

### Task 1: Baseline the worktree

**Files:** none modified.

- [ ] **Step 1: Server compile baseline**

```bash
./gradlew compileJava compileTestJava --continue > $SCRATCH/baseline-server.log 2>&1
echo "exit=$?"
grep -c '^> Task .* FAILED' $SCRATCH/baseline-server.log
```
Expected: `exit=0`, `0`. If not, stop and report — the plan assumes a green base.

- [ ] **Step 2: Client baseline**

```bash
cd client && npm ci && npm run check > $SCRATCH/baseline-client.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`. (Node 22.19+–24 required; Node 26 fake-fails on jsdom.)

- [ ] **Step 3: SDK widget baseline**

```bash
cd sdks/frontend/automation/chat/library && npm ci && npm run lint && npx vitest run > $SCRATCH/baseline-sdk.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`.

No commit.

### Task 2: Lift `Pcm16Resampler` out of `TwilioAudioCodec`

The OpenAI provider (Task 19) needs 16→24 kHz resampling and lives in a component module, which cannot depend on the webhook module. The codec class is deleted in Task 4, so the resampler moves first.

**Files:**
- Create: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice/Pcm16Resampler.java`
- Create: `server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/voice/Pcm16ResamplerTest.java`
- Read: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/TwilioAudioCodec.java:122` (`resamplePcm16`) for the algorithm being ported

**Interfaces:**
- Produces: `public static byte[] Pcm16Resampler.resample(byte[] pcm16LittleEndian, int fromHz, int toHz)` — returns the input array itself when `fromHz == toHz`.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.component.ai.llm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class Pcm16ResamplerTest {

    @Test
    void testSameRateReturnsInputUnchanged() {
        byte[] input = pcm(1000, -1000, 500);

        assertThat(Pcm16Resampler.resample(input, 16000, 16000)).isSameAs(input);
    }

    @Test
    void testUpsamplingDoublesSampleCount() {
        byte[] input = pcm(0, 1000, 2000, 3000);

        byte[] output = Pcm16Resampler.resample(input, 8000, 16000);

        assertThat(output).hasSize(input.length * 2);
        assertThat(samples(output)).startsWith((short) 0, (short) 500, (short) 1000, (short) 1500);
    }

    @Test
    void testDownsamplingHalvesSampleCount() {
        byte[] input = pcm(0, 1000, 2000, 3000, 4000, 5000);

        byte[] output = Pcm16Resampler.resample(input, 16000, 8000);

        assertThat(output).hasSize(input.length / 2);
        assertThat(samples(output)).containsExactly((short) 0, (short) 2000, (short) 4000);
    }

    @Test
    void testOddByteCountIsTruncatedToWholeSamples() {
        byte[] output = Pcm16Resampler.resample(new byte[] {1, 2, 3}, 8000, 16000);

        assertThat(output.length % 2).isZero();
    }

    private static byte[] pcm(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2)
            .order(ByteOrder.LITTLE_ENDIAN);

        for (int value : values) {
            buffer.putShort((short) value);
        }

        return buffer.array();
    }

    private static short[] samples(byte[] pcm16) {
        short[] samples = new short[pcm16.length / 2];

        ByteBuffer.wrap(pcm16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples);

        return samples;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :server:libs:modules:components:ai:llm:test --tests 'com.bytechef.component.ai.llm.voice.Pcm16ResamplerTest' > $SCRATCH/t2.log 2>&1; echo "exit=$?"
```
Expected: `exit=1` — compilation error, `Pcm16Resampler` does not exist.

- [ ] **Step 3: Implement**

```java
package com.bytechef.component.ai.llm.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Linear-interpolation resampler for 16-bit little-endian mono PCM. Good enough for speech between the browser's
 * 16 kHz capture and a provider's 24 kHz input; not a general-purpose audio resampler.
 *
 * @author Ivica Cardic
 */
public final class Pcm16Resampler {

    private Pcm16Resampler() {
    }

    public static byte[] resample(byte[] pcm16LittleEndian, int fromHz, int toHz) {
        if (fromHz == toHz) {
            return pcm16LittleEndian;
        }

        int inputSampleCount = pcm16LittleEndian.length / 2;

        if (inputSampleCount == 0) {
            return new byte[0];
        }

        short[] input = new short[inputSampleCount];

        ByteBuffer.wrap(pcm16LittleEndian, 0, inputSampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(input);

        int outputSampleCount = (int) ((long) inputSampleCount * toHz / fromHz);
        ByteBuffer output = ByteBuffer.allocate(outputSampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN);

        for (int outputIndex = 0; outputIndex < outputSampleCount; outputIndex++) {
            double position = (double) outputIndex * fromHz / toHz;
            int leftIndex = (int) position;
            int rightIndex = Math.min(leftIndex + 1, inputSampleCount - 1);
            double fraction = position - leftIndex;

            double sample = input[leftIndex] * (1.0 - fraction) + input[rightIndex] * fraction;

            output.putShort((short) Math.round(sample));
        }

        return output.array();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes** — same command as Step 2, expected `exit=0`.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply -q
git add server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice/Pcm16Resampler.java server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/voice/Pcm16ResamplerTest.java
git commit -m "Voice - Add Pcm16Resampler to the ai llm library"
```

### Task 3: Remove Twilio and Infobip call surfaces from the components

**Files:**
- Delete: under `server/libs/modules/components/twilio/`: `src/main/java/com/bytechef/component/twilio/action/TwilioMakeCallAction.java`, `.../trigger/TwilioInboundCallTrigger.java`, `.../util/TwilioSignatureValidator.java`, `.../util/TwilioStreamToken.java`, `src/test/java/com/bytechef/component/twilio/action/TwilioMakeCallActionTest.java`, `.../util/TwilioSignatureValidatorTest.java`, `.../util/TwilioStreamTokenTest.java`, `TWILIO_INBOUND_CALL_TRIGGER_PLAN.md`, `TWILIO_OUTBOUND_CALL_ACTION_PLAN.md`
- Delete: under `server/libs/modules/components/infobip/`: `src/main/java/com/bytechef/component/infobip/action/InfobipMakeCallAction.java`, `.../trigger/InfobipInboundCallTrigger.java`, `.../util/InfobipSignatureValidator.java`, `src/test/java/com/bytechef/component/infobip/action/InfobipMakeCallActionTest.java`, `.../util/InfobipSignatureValidatorTest.java`
- Modify: `TwilioComponentHandler.java`, `InfobipComponentHandler.java`, `server/libs/modules/components/infobip/src/main/resources/README.mdx` (drop the 22-line call paragraph the voice commit added — `git show cbf534a8275 -- server/libs/modules/components/infobip/src/main/resources/README.mdx` shows exactly which lines)
- Regenerate: `twilio_v1.json`, `infobip_v1.json` snapshots

- [ ] **Step 1: Delete the files with `git rm`** (all listed above).

- [ ] **Step 2: Edit the handlers**

`TwilioComponentHandler.java`: remove the imports of `TwilioMakeCallAction` and `TwilioInboundCallTrigger`; remove `TwilioMakeCallAction.ACTION_DEFINITION,` from `.actions(...)`; remove `tool(TwilioMakeCallAction.ACTION_DEFINITION),` from `.clusterElements(...)`; change `.triggers(TwilioInboundCallTrigger.TRIGGER_DEFINITION, TwilioNewWhatsappMessageTrigger.TRIGGER_DEFINITION)` to `.triggers(TwilioNewWhatsappMessageTrigger.TRIGGER_DEFINITION)`.

`InfobipComponentHandler.java`: remove the imports of `InfobipMakeCallAction` and `InfobipInboundCallTrigger`; remove `InfobipMakeCallAction.ACTION_DEFINITION,` from `.actions(...)`; remove `InfobipInboundCallTrigger.TRIGGER_DEFINITION,` from `.triggers(...)`.

Verify: `grep -rn 'MakeCall\|InboundCall\|StreamToken\|SignatureValidator' server/libs/modules/components/twilio server/libs/modules/components/infobip --include='*.java'` prints nothing. For each constant in `TwilioConstants`/`InfobipConstants` that only the deleted classes used (grep its name), delete it.

- [ ] **Step 3: Regenerate both snapshots (two-run dance)**

```bash
rm -f server/libs/modules/components/twilio/src/test/resources/definition/twilio_v1.json server/libs/modules/components/infobip/src/test/resources/definition/infobip_v1.json
rm -rf server/libs/modules/components/twilio/build/resources/test/definition server/libs/modules/components/infobip/build/resources/test/definition
./gradlew :server:libs:modules:components:twilio:test :server:libs:modules:components:infobip:test --continue > $SCRATCH/t3a.log 2>&1; echo "first run exit=$? (NPE: url expected)"
./gradlew :server:libs:modules:components:twilio:test :server:libs:modules:components:infobip:test --continue > $SCRATCH/t3b.log 2>&1; echo "second run exit=$?"
grep '^> Task .* FAILED' $SCRATCH/t3b.log
grep -c 'inboundCall\|makeCall' server/libs/modules/components/twilio/src/test/resources/definition/twilio_v1.json server/libs/modules/components/infobip/src/test/resources/definition/infobip_v1.json
```
Expected: second run `exit=0`, no FAILED lines, both counts `0`.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/modules/components/twilio server/libs/modules/components/infobip
git commit -m "Voice - Remove Twilio and Infobip call actions and triggers"
```

### Task 4: Remove the rest of shape B (codec actions, realtimeChat, Twilio transport, specs)

The engine's chain (`WebSocketTaskChain`, `WebsocketTasks`) and the action-perform WebSocket path stay until Tasks 10 and 21: `deepgram/v1/voiceAgent` is still an action until Task 16 and the engine still parses `websocketTasks` until Task 10. Everything else goes now.

**Files:**
- Delete (codec actions): `server/libs/modules/components/deepgram/src/main/java/com/bytechef/component/deepgram/action/DeepgramRealtimeListenAction.java`, `.../DeepgramRealtimeSpeakAction.java`, `server/libs/modules/components/elevenlabs/src/main/java/com/bytechef/component/elevenlabs/action/ElevenLabsCreateRealtimeSpeechAction.java`, `.../ElevenLabsCreateRealtimeTranscriptAction.java`
- Delete (agent): `server/libs/modules/components/ai/agent/src/main/java/com/bytechef/component/ai/agent/action/AiAgentRealtimeChatAction.java`
- Delete (`$WS/src/main/java/com/bytechef/platform/webhook/`): `web/rest/TwimlController.java`, `web/rest/TwilioCallbackController.java`, `web/rest/TwilioSignatureValidator.java`, `web/service/TriggerCompletionServiceImpl.java`, `web/websocket/TwilioAudioCodec.java`, `web/websocket/TwilioMediaStream.java`, `web/websocket/TwilioStreamToken.java`; tests under `$WS/src/test/java/com/bytechef/platform/webhook/`: `web/rest/TwilioSignatureValidatorTest.java`, `web/websocket/TwilioAudioCodecTest.java`, `web/websocket/TwilioMediaStreamTest.java`, `web/websocket/TwilioStreamTokenTest.java`
- Delete (webhook api): `server/libs/platform/platform-webhook/platform-webhook-api/src/main/java/com/bytechef/platform/webhook/TriggerCompletionService.java` — first verify `grep -rn 'TriggerCompletionService' server --include='*.java' | grep -v '/build/'` lists only the interface and the impl
- Delete (specs): `docs/superpowers/specs/2026-05-12-ai-hub-voice-design.md`, `2026-05-12-ai-hub-voice-path-b-plan.md`, `2026-05-12-ai-hub-voice-tts-strategy.md`, `2026-05-13-ai-hub-voice-cost-and-compliance-design.md`, `2026-05-13-ai-hub-voice-lifecycle-and-gaps-design.md`, `2026-05-13-ai-hub-voice-path-b-v1.3-design.md`, `2026-05-13-ai-hub-voice-production-readiness-design.md`, `2026-05-13-ai-hub-voice-ux-i18n-docs-design.md`, `2026-05-13-ai-hub-voice-widget-design.md`, `2026-05-12-voice-agent-runtime-tier1-design.md`
- Modify: `DeepgramComponentHandler.java`, `ElevenLabsComponentHandler.java`, `AiAgentComponentHandler.java`, `$WS/.../web/websocket/WebhookWebSocketHandler.java`, `AbstractAiAgentChatAction.java:317-318`, `AgentToolCallingManagers.java:120`, `.agents/agents.md:704`
- Prepend a superseded note to the six surviving May files: `docs/superpowers/specs/2026-05-12-browser-voice-runtime-tier1-design.md`, `2026-05-12-browser-voice-tier1-deepgram-plan.md`, `2026-05-12-workflow-test-chat-voice-design.md`, `2026-05-12-automation-chat-widget-voice-design.md`, `2026-05-19-voice-support-redesign-design.md`, `docs/superpowers/plans/2026-05-19-voice-support-redesign.md`
- Regenerate: `deepgram_v1.json`, `elevenlabs_v1.json`, `ai-agent_v1.json`

- [ ] **Step 1: `git rm` every file in the Delete lists.**

- [ ] **Step 2: Strip the Twilio branches from `WebhookWebSocketHandler`**

1. Remove fields `twilioStreamTokenSecret` (+ its `@Value`), `TWILIO_SAMPLE_RATE_HZ`, `TWILIO_INBOUND_PCM_RATE_HZ`, `TWILIO_OUTBOUND_PCM_RATE_HZ`, `streamSidBySessionId`, and every import of a deleted class.
2. `afterConnectionEstablished`: delete the block from the comment `// Twilio media-stream path: a WebSocket upgrade cannot carry X-Twilio-Signature` through the closing brace of that `if`. Delete the `subWorkflowId` query-param read; change `startWebsocketSubflow(String callSid, String webhookIdString, @Nullable String subWorkflowId)` to `startWebsocketSubflow(String callSid, String webhookIdString)` and inside it keep only the `workflowExecutionId = WorkflowExecutionId.parse(webhookIdString); websocketSubflowDefinition = getWebsocketSubflowDefinition(workflowExecutionId);` path (delete the `if (subWorkflowId != null ...)` branch and make `workflowExecutionId` non-null).
3. `handleTextMessage`: delete the `String twilioEvent = TwilioMediaStream.eventType(request); if (twilioEvent != null) {...}` block. Delete the whole `handleTwilioMediaStreamFrame` method.
4. `attachOutboundBridge`: replace the binary listener with the plain path only:

```java
        emitter.addOutboundBinaryListener(bytes -> {
            if (!wsSession.isOpen()) {
                return;
            }

            try {
                wsSession.sendMessage(new BinaryMessage(bytes));
            } catch (IOException ioException) {
                log.warn(
                    "Failed to forward outbound binary to WS session: sessionId={}", wsSession.getId(), ioException);
            }
        });
```
and delete the `emitter.addOutboundTurnCancelListener(...)` block after it.
5. Delete `getWebsocketSubflowDefinitionByWorkflowId`. In `afterConnectionClosed` delete `streamSidBySessionId.invalidate(sessionKey);`. In the class Javadoc drop the sentence about Twilio media streams.

Verify: `grep -n 'Twilio\|twilio\|streamSid\|subWorkflowId' $WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WebhookWebSocketHandler.java` prints nothing.

- [ ] **Step 3: Edit the component handlers and the comments**

`DeepgramComponentHandler.java`: `.actions(DeepgramVoiceAgentAction.ACTION_DEFINITION)`; drop the two imports. `ElevenLabsComponentHandler.java`: drop the two realtime `ACTION_DEFINITION` entries and their imports. `AiAgentComponentHandler.java`: delete the `AiAgentRealtimeChatAction.of(...)` argument (and the comma ending the previous argument) and its import. `AbstractAiAgentChatAction.java:317-318`: rewrite the comment as `// A streaming agent's tokens go to whoever is listening right now, so that is a conversation and` followed by the existing continuation. `AgentToolCallingManagers.java:120`: delete the parenthetical starting `(including realtime,`. `.agents/agents.md:704`: delete the clause `the run went through the **realtime (voice) chat action**, which is deliberately not wired to the recorder (see the design spec's Scope section);`.

- [ ] **Step 4: Prepend the superseded note to the six surviving files**

Line to prepend (followed by a blank line): `> **Superseded (2026-09-08)** for the realtime half by [2026-09-08-voice-cluster-element-redesign-design.md](2026-09-08-voice-cluster-element-redesign-design.md): the voice agent is now a cluster element of the trigger and `websocketTasks` no longer exists.` — in the file under `docs/superpowers/plans/` link to `../specs/2026-09-08-voice-cluster-element-redesign-design.md`.

- [ ] **Step 5: Compile, regenerate the three snapshots, run the webhook module tests**

```bash
./gradlew compileJava compileTestJava --continue > $SCRATCH/t4a.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' $SCRATCH/t4a.log
```
Expected `exit=0`. Then the two-run snapshot dance for `:server:libs:modules:components:deepgram:test`, `:server:libs:modules:components:elevenlabs:test`, `:server:libs:modules:components:ai:agent:test` (delete `deepgram_v1.json`, `elevenlabs_v1.json`, `ai-agent_v1.json` and each module's `build/resources/test/definition` first). Then:
```bash
./gradlew :server:libs:platform:platform-webhook:platform-websocket-webhook-rest:test --continue > $SCRATCH/t4b.log 2>&1; echo "exit=$?"
```
Expected `exit=0` — `VoiceAgentIntTest` still passes, the chain is untouched.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/modules/components/deepgram server/libs/modules/components/elevenlabs server/libs/modules/components/ai/agent server/libs/platform/platform-webhook docs/superpowers .agents/agents.md
git commit -m "Voice - Remove the realtime voice runtime: codec actions, realtimeChat, Twilio transport"
```

---

## Phase 1 — Component model

### Task 5: `VoiceAgentFunction`, `VoiceAgentContext`, `VoiceAgentToolset` in `platform-component-api`

**Files:**
- Create under `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/voice/`: `VoiceAgentFunction.java`, `VoiceAgentContext.java`, `VoiceAgentToolset.java`, `VoiceToolDefinition.java`, `VoiceAgentToolsetFactory.java`
- Create test: `server/libs/platform/platform-component/platform-component-api/src/test/java/com/bytechef/platform/component/definition/voice/VoiceAgentToolsetTest.java`

**Interfaces (produced, used by every later server task):**

```java
package com.bytechef.platform.component.definition.voice;

@FunctionalInterface
public interface VoiceAgentFunction {
    ClusterElementType VOICE_AGENT = new ClusterElementType("VOICE_AGENT", "voiceAgent", "Voice Agent", true);

    WebSocketHandler apply(Parameters inputParameters, Parameters connectionParameters, VoiceAgentContext context)
        throws Exception;
}

public record VoiceAgentContext(ActionContext actionContext, VoiceAgentToolset toolset) {}

public interface VoiceAgentToolset {
    VoiceAgentToolset EMPTY = ...;                       // no tools; call() returns the "unknown tool" message
    List<VoiceToolDefinition> definitions();
    String call(String name, String argumentsJson);      // never throws; errors come back as the result string
}

public record VoiceToolDefinition(String name, String description, String inputSchema) {}

public interface VoiceAgentToolsetFactory {
    VoiceAgentToolset create(Map<String, ?> extensions, Map<String, ComponentConnection> connections, ActionContext context);
}
```

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.component.definition.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VoiceAgentToolsetTest {

    @Test
    void testEmptyToolsetHasNoDefinitions() {
        assertThat(VoiceAgentToolset.EMPTY.definitions()).isEmpty();
    }

    @Test
    void testEmptyToolsetAnswersUnknownToolWithoutThrowing() {
        assertThat(VoiceAgentToolset.EMPTY.call("lookupOrder", "{}"))
            .isEqualTo(VoiceAgentToolset.unknownTool("lookupOrder"));
    }

    @Test
    void testVoiceAgentClusterElementTypeIsSingleAndRequired() {
        assertThat(VoiceAgentFunction.VOICE_AGENT.key()).isEqualTo("voiceAgent");
        assertThat(VoiceAgentFunction.VOICE_AGENT.multipleElements()).isFalse();
        assertThat(VoiceAgentFunction.VOICE_AGENT.required()).isTrue();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew :server:libs:platform:platform-component:platform-component-api:test --tests '*VoiceAgentToolsetTest' > $SCRATCH/t5.log 2>&1; echo "exit=$?"
```
Expected `exit=1` (compilation error).

- [ ] **Step 3: Implement the five files**

`VoiceAgentFunction.java`:
```java
package com.bytechef.platform.component.definition.voice;

import com.bytechef.component.definition.ActionDefinition.WebSocketHandler;
import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.Parameters;

/**
 * A provider-backed voice agent: the single cluster element a {@code browser/v1/voiceSession} trigger runs for the
 * length of a session. The returned {@link WebSocketHandler} receives the caller's audio on its emitter and sends
 * audio and normalised events back on it — see the stage contract in the voice design spec.
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface VoiceAgentFunction {

    ClusterElementType VOICE_AGENT = new ClusterElementType("VOICE_AGENT", "voiceAgent", "Voice Agent", true);

    WebSocketHandler apply(Parameters inputParameters, Parameters connectionParameters, VoiceAgentContext context)
        throws Exception;
}
```

`VoiceAgentContext.java`:
```java
package com.bytechef.platform.component.definition.voice;

import com.bytechef.component.definition.ActionContext;

/**
 * What a voice agent gets beside its parameters: a real {@link ActionContext} (json, http, log, file) and the tools
 * the workflow author attached to it, already wrapped in the platform's tool policy.
 *
 * @author Ivica Cardic
 */
public record VoiceAgentContext(ActionContext actionContext, VoiceAgentToolset toolset) {
}
```

`VoiceToolDefinition.java`:
```java
package com.bytechef.platform.component.definition.voice;

/**
 * A tool as a voice provider needs to see it: name, description and a JSON-schema string for its input.
 *
 * @author Ivica Cardic
 */
public record VoiceToolDefinition(String name, String description, String inputSchema) {
}
```

`VoiceAgentToolset.java`:
```java
package com.bytechef.platform.component.definition.voice;

import java.util.List;

/**
 * The tools a voice agent may call. {@link #call} never throws: an unknown name, a failing tool or a tool that needs
 * an approval the session cannot host all come back as the result string, so the provider can forward it and the
 * agent can say so aloud.
 *
 * @author Ivica Cardic
 */
public interface VoiceAgentToolset {

    VoiceAgentToolset EMPTY = new VoiceAgentToolset() {

        @Override
        public List<VoiceToolDefinition> definitions() {
            return List.of();
        }

        @Override
        public String call(String name, String argumentsJson) {
            return unknownTool(name);
        }
    };

    static String unknownTool(String name) {
        return "Unknown tool '" + name + "'. Tell the user this action is not available.";
    }

    List<VoiceToolDefinition> definitions();

    String call(String name, String argumentsJson);
}
```

`VoiceAgentToolsetFactory.java`:
```java
package com.bytechef.platform.component.definition.voice;

import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.ComponentConnection;
import java.util.Map;

/**
 * Builds a {@link VoiceAgentToolset} from a voice agent element's own extensions (its nested {@code clusterElements},
 * above all {@code tools}) and the connections resolved for them. Implemented where the tool policy stack lives
 * ({@code components/ai/llm}); the voice engine resolves it as an optional bean and falls back to
 * {@link VoiceAgentToolset#EMPTY} when the implementation is not on the classpath.
 *
 * @author Ivica Cardic
 */
public interface VoiceAgentToolsetFactory {

    VoiceAgentToolset create(
        Map<String, ?> extensions, Map<String, ComponentConnection> connections, ActionContext context);
}
```

- [ ] **Step 4: Run the test to verify it passes** — expected `exit=0`.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply -q
git add server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/voice server/libs/platform/platform-component/platform-component-api/src/test/java/com/bytechef/platform/component/definition/voice
git commit -m "Voice - Add the VoiceAgentFunction cluster element SPI and toolset contract"
```

### Task 6: Make `browser` a cluster root and update the trigger properties

**Files:**
- Modify: `server/libs/modules/components/browser/src/main/java/com/bytechef/component/browser/BrowserComponentHandler.java`
- Modify: `server/libs/modules/components/browser/src/main/java/com/bytechef/component/browser/trigger/BrowserVoiceSessionTrigger.java`
- Create: `server/libs/modules/components/browser/src/test/java/com/bytechef/component/browser/BrowserComponentHandlerTest.java`
- Create test: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/index/ComponentIndexTriggerClusterRootTest.java`
- Regenerate: `server/libs/modules/components/browser/src/test/resources/definition/browser_v1.json`

**Interfaces:**
- Produces: `BrowserVoiceSessionTrigger.SILENCE_TIMEOUT_SECONDS = "silenceTimeoutSeconds"` (default 120); `SUB_WORKFLOW` removed; component definition implements `ClusterRootComponentDefinition` with `getClusterElementTypes() == [VOICE_AGENT]`.

- [ ] **Step 1: Write the failing component test**

```java
package com.bytechef.component.browser;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.platform.component.definition.ClusterRootComponentDefinition;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.test.jsonasssert.JsonFileAssert;
import org.junit.jupiter.api.Test;

class BrowserComponentHandlerTest {

    @Test
    void testDefinition() {
        JsonFileAssert.assertEquals("definition/browser_v1.json", new BrowserComponentHandler().getDefinition());
    }

    @Test
    void testBrowserIsAClusterRootAcceptingOneVoiceAgent() {
        ComponentDefinition componentDefinition = new BrowserComponentHandler().getDefinition();

        assertThat(componentDefinition).isInstanceOf(ClusterRootComponentDefinition.class);
        assertThat(((ClusterRootComponentDefinition) componentDefinition).getClusterElementTypes())
            .containsExactly(VoiceAgentFunction.VOICE_AGENT);
    }
}
```
(`JsonFileAssert` is in `server/libs/test/test-support`; check `browser/build.gradle.kts` has `testImplementation(project(":server:libs:test:test-support"))` — add it if missing, mirroring `deepgram/build.gradle.kts`.)

- [ ] **Step 2: Run it to verify it fails** — `./gradlew :server:libs:modules:components:browser:test > $SCRATCH/t6.log 2>&1; echo "exit=$?"` → `exit=1`.

- [ ] **Step 3: Implement**

`BrowserComponentHandler.java`:
```java
package com.bytechef.component.browser;

import static com.bytechef.component.definition.ComponentDsl.component;

import com.bytechef.component.ComponentHandler;
import com.bytechef.component.browser.trigger.BrowserVoiceSessionTrigger;
import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.ComponentCategory;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.platform.component.definition.AbstractComponentDefinitionWrapper;
import com.bytechef.platform.component.definition.ClusterRootComponentDefinition;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.google.auto.service.AutoService;
import java.util.List;

/**
 * @author Ivica Cardic
 */
@AutoService(ComponentHandler.class)
public class BrowserComponentHandler implements ComponentHandler {

    private static final ComponentDefinition COMPONENT_DEFINITION = new BrowserComponentDefinitionImpl(
        component("browser")
            .title("Browser")
            .description(
                "Triggers that surface in a customer's browser, including voice sessions answered by a voice agent.")
            .icon("path:assets/browser.svg")
            .categories(ComponentCategory.COMMUNICATION)
            .triggers(BrowserVoiceSessionTrigger.TRIGGER_DEFINITION));

    @Override
    public ComponentDefinition getDefinition() {
        return COMPONENT_DEFINITION;
    }

    /**
     * The voice session trigger owns exactly one Voice Agent cluster element; declaring the type here is what makes
     * the editor render the trigger as a cluster root and the engine find the element under
     * {@code trigger.clusterElements.voiceAgent}.
     */
    private static class BrowserComponentDefinitionImpl extends AbstractComponentDefinitionWrapper
        implements ClusterRootComponentDefinition {

        private BrowserComponentDefinitionImpl(ComponentDefinition componentDefinition) {
            super(componentDefinition);
        }

        @Override
        public List<ClusterElementType> getClusterElementTypes() {
            return List.of(VoiceAgentFunction.VOICE_AGENT);
        }
    }
}
```

`BrowserVoiceSessionTrigger.java`: delete the `SUB_WORKFLOW` constant and its `string(SUB_WORKFLOW)` property; add `public static final String SILENCE_TIMEOUT_SECONDS = "silenceTimeoutSeconds";` and, after the `SESSION_LIMIT_SECONDS` property:
```java
            integer(SILENCE_TIMEOUT_SECONDS)
                .label("Silence timeout (seconds)")
                .description(
                    "End the session after this many seconds without audio from the caller. 0 disables the timeout.")
                .defaultValue(120)
                .required(false))
```
Replace the description with `"Triggers when a browser opens a voice session against this workflow. The Voice Agent cluster element answers the caller for the length of the session; the workflow's tasks run afterwards with the session transcript as the trigger output."` and the Javadoc paragraph about `subWorkflow` with one sentence pointing at the cluster element. Replace `.output(...)` with the session-end shape:
```java
        .output(
            outputSchema(
                object()
                    .properties(
                        string("sessionId").description("ByteChef-issued session id"),
                        string("startedAt").description("ISO-8601 session start timestamp"),
                        integer("durationSeconds").description("Session length in seconds"),
                        string("endReason")
                            .description(
                                "client_closed, session_limit, silence_timeout, provider_error or server_shutdown"),
                        array("transcript")
                            .items(
                                object().properties(
                                    string("role").description("user or assistant"),
                                    string("text"),
                                    string("at").description("ISO-8601 timestamp"))),
                        array("toolCalls")
                            .items(
                                object().properties(
                                    string("name"),
                                    object("arguments"),
                                    string("result"),
                                    string("at").description("ISO-8601 timestamp"))))));
```
(add the `array` and `integer` static imports from `ComponentDsl`).

- [ ] **Step 4: Write the index stub test**

`ComponentIndexTriggerClusterRootTest.java` — proves a component whose only cluster-root surface is a trigger still reports `clusterRoot = true` from the index stub (the "derived flag on a stub" rule):

```java
package com.bytechef.platform.component.index;

import static com.bytechef.component.definition.ComponentDsl.component;
import static com.bytechef.component.definition.ComponentDsl.trigger;
import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.definition.ClusterElementDefinition.ClusterElementType;
import com.bytechef.component.definition.ComponentDefinition;
import com.bytechef.component.definition.TriggerDefinition.TriggerType;
import com.bytechef.platform.component.definition.AbstractComponentDefinitionWrapper;
import com.bytechef.platform.component.definition.ClusterRootComponentDefinition;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentIndexTriggerClusterRootTest {

    @Test
    void testTriggerOnlyClusterRootSurvivesTheIndexRoundTrip() {
        ComponentDefinition definition = new TriggerClusterRoot(
            component("browserLike")
                .triggers(trigger("voiceSession").type(TriggerType.WEBSOCKET)));

        ComponentIndex.Entry entry = ComponentIndexGenerator.toEntry(definition, "com.example.Handler", "default");

        ComponentDefinition stub = ComponentIndex.toStubDefinition(entry);

        assertThat(stub).isInstanceOf(ClusterRootComponentDefinition.class);
        assertThat(new com.bytechef.platform.component.domain.ComponentDefinition(stub).isClusterRoot()).isTrue();
        assertThat(((ClusterRootComponentDefinition) stub).getClusterElementTypes())
            .extracting(ClusterElementType::name)
            .containsExactly("VOICE_AGENT");
    }

    private static class TriggerClusterRoot extends AbstractComponentDefinitionWrapper
        implements ClusterRootComponentDefinition {

        private TriggerClusterRoot(ComponentDefinition componentDefinition) {
            super(componentDefinition);
        }

        @Override
        public List<ClusterElementType> getClusterElementTypes() {
            return List.of(VoiceAgentFunction.VOICE_AGENT);
        }
    }
}
```
Open `ComponentIndexGenerator.java:110` and `ComponentIndex.java` around the `toStub...` method (the one that builds `StubClusterRootComponentDefinition` at ~line 244) to use the real names of the entry-building and stub-building methods; if they are private, use the same pattern the existing `ComponentIndexTest.testAgentChannelsRoundTripThroughStub` uses to reach them, and mirror its arguments. No production change is expected — this test documents that trigger-only cluster roots already round-trip.

- [ ] **Step 5: Run both tests, regenerate the browser snapshot**

Two-run dance for `:server:libs:modules:components:browser:test` (delete `browser_v1.json` and `build/resources/test/definition` first), then `./gradlew :server:libs:platform:platform-component:platform-component-service:test --tests '*ComponentIndexTriggerClusterRootTest' > $SCRATCH/t6b.log 2>&1; echo "exit=$?"` → `exit=0`. Confirm `grep -c '"clusterRoot" : true' server/libs/modules/components/browser/src/test/resources/definition/browser_v1.json` is `1` and `grep -c subWorkflow` is `0`.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/modules/components/browser server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/index/ComponentIndexTriggerClusterRootTest.java
git commit -m "Voice - Make the browser component a cluster root owning one Voice Agent element"
```

### Task 7: Trigger DTO/REST/client model carry `clusterRoot` and `clusterElements`

**Files:**
- Modify: `server/libs/platform/platform-configuration/platform-configuration-api/src/main/java/com/bytechef/platform/configuration/dto/WorkflowTriggerDTO.java`
- Modify: `server/libs/platform/platform-configuration/platform-configuration-service/src/main/java/com/bytechef/platform/configuration/facade/WorkflowFacadeImpl.java:141-155`
- Modify: `server/libs/platform/platform-configuration/platform-configuration-service/src/test/java/com/bytechef/platform/configuration/facade/WorkflowFacadeTest.java`
- Modify: `server/libs/platform/platform-configuration/platform-configuration-rest/platform-configuration-rest-impl/openapi.yaml:3390-3440` (`WorkflowTrigger` schema)
- Modify: `server/libs/platform/platform-configuration/platform-configuration-rest/platform-configuration-rest-api/src/main/java/com/bytechef/platform/configuration/web/rest/mapper/WorkflowTriggerMapper.java`
- Regenerate: `WorkflowTriggerModel.java` (server, `./gradlew :server:libs:platform:platform-configuration:platform-configuration-rest:platform-configuration-rest-api:openApiGenerate` — check the task name in that module's `build.gradle.kts`), client `client/src/shared/middleware/platform/configuration/models/WorkflowTrigger.ts` (`cd client && npm run generate:api` or whatever script `client/package.json` names for the platform configuration client — grep `openapi-generator` in `client/package.json`)

**Interfaces:**
- Produces: `WorkflowTriggerDTO(ClusterElementMap clusterElements, boolean clusterRoot, List<ComponentConnection> connections, String description, Map<String, ?> metadata, String name, String label, Map<String, ?> parameters, String timeout, String type)`; secondary ctor `WorkflowTriggerDTO(WorkflowTrigger, boolean clusterRoot, ClusterElementMap, List<ComponentConnection>)`; REST `WorkflowTriggerModel.clusterRoot` (boolean, readOnly) and `.clusterElements` (object, readOnly); client `WorkflowTrigger.clusterRoot?`, `.clusterElements?`.

- [ ] **Step 1: Write the failing facade test** (add to `WorkflowFacadeTest`):

```java
    @Test
    public void testGetWorkflowWithClusterRootTrigger() {
        when(testWorkflow.getTasks(true)).thenReturn(Collections.emptyList());
        when(testWorkflow.getExtensions(eq(WorkflowExtConstants.TRIGGERS), eq(WorkflowTrigger.class), anyList()))
            .thenReturn(
                List.of(
                    new WorkflowTrigger(
                        Map.of(
                            "name", "trigger_1", "type", "browser/v1/voiceSession",
                            "clusterElements", Map.of(
                                "voiceAgent", Map.of(
                                    "name", "voiceAgent_1", "type", "deepgram/v1/voiceAgent",
                                    "parameters", Map.of("prompt", "hi")))))));
        when(componentConnectionFacade.getComponentConnections(any(WorkflowTrigger.class)))
            .thenReturn(Collections.emptyList());

        ComponentDefinition browser = mock(ComponentDefinition.class);

        when(browser.isClusterRoot()).thenReturn(true);
        when(componentDefinitionService.fetchComponentDefinition("browser", 1)).thenReturn(Optional.of(browser));

        WorkflowDTO workflowDTO = workflowFacade.getWorkflow(testWorkflow.getId());

        WorkflowTriggerDTO trigger = workflowDTO.getTriggers()
            .getFirst();

        assertThat(trigger.clusterRoot()).isTrue();
        assertThat(trigger.clusterElements()
            .getClusterElement(VoiceAgentFunction.VOICE_AGENT)
            .getType()).isEqualTo("deepgram/v1/voiceAgent");
    }
```
Use whatever constructor `WorkflowTrigger` actually exposes (open the class: it has a `WorkflowTrigger(Map<String, ?> source)` shape used by `WorkflowTrigger.of(...)`; if the ctor is package-private, build the trigger through `WorkflowTrigger.of(workflow)` with the definition JSON instead).

- [ ] **Step 2: Run it to verify it fails** — `./gradlew :server:libs:platform:platform-configuration:platform-configuration-service:test --tests '*WorkflowFacadeTest' > $SCRATCH/t7.log 2>&1; echo "exit=$?"` → `exit=1` (no `clusterRoot()` on the record).

- [ ] **Step 3: Implement**

`WorkflowTriggerDTO.java`:
```java
@SuppressFBWarnings("EI")
public record WorkflowTriggerDTO(
    ClusterElementMap clusterElements, boolean clusterRoot, List<ComponentConnection> connections,
    String description, Map<String, ?> metadata, String name, String label, Map<String, ?> parameters,
    String timeout, String type) {

    public WorkflowTriggerDTO(
        WorkflowTrigger workflowTrigger, boolean clusterRoot, ClusterElementMap clusterElements,
        List<ComponentConnection> connections) {

        this(
            clusterElements, clusterRoot, connections, workflowTrigger.getDescription(),
            workflowTrigger.getMetadata(), workflowTrigger.getName(), workflowTrigger.getLabel(),
            workflowTrigger.getParameters(), workflowTrigger.getTimeout(), workflowTrigger.getType());
    }
}
```
Fix every other caller of the old two-arg constructor (`grep -rn 'new WorkflowTriggerDTO(' server --include='*.java' | grep -v build`) by passing `false, ClusterElementMap.of(Map.of())` — or better, the same derivation as below.

`WorkflowFacadeImpl.toWorkflowDTO` trigger loop:
```java
        for (WorkflowTrigger workflowTrigger : workflowTriggers) {
            WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

            boolean clusterRoot = componentDefinitionService
                .fetchComponentDefinition(workflowNodeType.name(), workflowNodeType.version())
                .map(ComponentDefinition::isClusterRoot)
                .orElse(Boolean.FALSE);

            workflowTriggerDTOs.add(
                new WorkflowTriggerDTO(
                    workflowTrigger, clusterRoot, ClusterElementMap.of(workflowTrigger.getExtensions()),
                    componentConnectionFacade.getComponentConnections(
                        CollectionUtils.getFirst(
                            workflowTriggers,
                            curWorkflowTrigger -> Objects.equals(
                                curWorkflowTrigger.getName(), workflowTrigger.getName())))));
        }
```

`openapi.yaml` `WorkflowTrigger` schema — add after `connections`:
```yaml
        clusterRoot:
          type: boolean
          default: false
          readOnly: true
        clusterElements:
          type: object
          additionalProperties: true
          readOnly: true
```
and change the `extensions` description to `"Key-value map of trigger extensions — structural configuration that is not a component-declared trigger property."`.

`WorkflowTriggerMapper.java` — mirror the task mapper: keep the `WorkflowTrigger → WorkflowTriggerModel` converter with `@Mapping(target = "clusterRoot", ignore = true)` and `@Mapping(target = "clusterElements", ignore = true)` added, and add a second mapper interface `WorkflowTriggerDTOToWorkflowTriggerModelMapper extends Converter<WorkflowTriggerDTO, WorkflowTriggerModel>` exactly like `WorkflowTaskMapper.WorkflowTaskDTOToWorkflowTaskModelMapper` (MapStruct maps `ClusterElementMap` → `Map<String,Object>` the same way it already does for tasks — if the task mapper needed a helper for that, copy it). Check the `WorkflowModel` mapper for `triggers` and make it use the DTO converter (grep `WorkflowTriggerDTO` under `platform-configuration-rest-api/src/main/java` after adding — the workflow-level mapper is the one that lists `uses = {...}`).

Regenerate the server model and the client model per the build tasks named above.

- [ ] **Step 4: Run tests** — the facade test (`exit=0`), then `./gradlew :server:libs:platform:platform-configuration:platform-configuration-rest:platform-configuration-rest-api:compileJava :server:libs:platform:platform-configuration:platform-configuration-rest:platform-configuration-rest-impl:test --continue > $SCRATCH/t7b.log 2>&1; echo "exit=$?"` → `exit=0`. Client: `cd client && npm run typecheck` → exit 0.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply -q && (cd client && npm run format)
git add -A server/libs/platform/platform-configuration client/src/shared/middleware/platform/configuration/models
git commit -m "Voice - Carry clusterRoot and clusterElements on workflow trigger DTOs and models"
```

### Task 8: Cluster-element connections resolve for a trigger's elements

**Files:**
- Read: `server/libs/platform/platform-configuration/platform-configuration-service/src/main/java/com/bytechef/platform/configuration/facade/ComponentConnectionFacadeImpl.java:147-200`
- Create: `server/libs/platform/platform-configuration/platform-configuration-service/src/test/java/com/bytechef/platform/configuration/workflow/connection/ClusterRootComponentConnectionFactoryTest.java`
- Modify (only if the test fails): `ComponentConnectionFacadeImpl.getComponentConnections(WorkflowTrigger)`

**Interfaces:**
- Produces: `componentConnectionFacade.getComponentConnections(WorkflowTrigger)` returns, for a cluster-root trigger, one `ComponentConnection` per nested element that declares a connection, keyed `(workflowNodeName = trigger name, key = element workflowNodeName)`.

- [ ] **Step 1: Write the test**

```java
package com.bytechef.platform.configuration.workflow.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.domain.ConnectionDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterRootComponentConnectionFactoryTest {

    @Test
    void testVoiceAgentElementOnATriggerYieldsItsConnection() {
        ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
        ComponentDefinition browser = mock(ComponentDefinition.class);
        ComponentDefinition deepgram = mock(ComponentDefinition.class);

        when(browser.getConnection()).thenReturn(null);
        when(browser.isClusterRoot()).thenReturn(true);
        when(deepgram.getName()).thenReturn("deepgram");
        when(deepgram.getVersion()).thenReturn(1);
        when(deepgram.getConnection()).thenReturn(mock(ConnectionDefinition.class));
        when(deepgram.isConnectionRequired()).thenReturn(true);
        when(componentDefinitionService.getComponentDefinition("deepgram", 1)).thenReturn(deepgram);

        ClusterRootComponentConnectionFactory factory = new ClusterRootComponentConnectionFactory(
            List.of(), componentDefinitionService);

        List<ComponentConnection> connections = factory.create(
            "trigger_1",
            Map.of(
                "clusterElements", Map.of(
                    "voiceAgent", Map.of(
                        "name", "voiceAgent_1", "type", "deepgram/v1/voiceAgent", "parameters", Map.of()))),
            browser);

        assertThat(connections)
            .singleElement()
            .satisfies(connection -> {
                assertThat(connection.workflowNodeName()).isEqualTo("trigger_1");
                assertThat(connection.key()).isEqualTo("voiceAgent_1");
                assertThat(connection.componentName()).isEqualTo("deepgram");
                assertThat(connection.required()).isTrue();
            });
    }
}
```
Adjust accessor names to `ComponentConnection`'s actual record components (open `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/ComponentConnection.java`; the connection-slot record created by `ComponentConnection.of(workflowNodeName, key, componentName, version, required)` may be a different class — `ComponentConnection` in `platform-configuration-api` `domain` package. Use the one `ClusterRootComponentConnectionFactory.create` returns.)

- [ ] **Step 2: Run it** — `./gradlew :server:libs:platform:platform-configuration:platform-configuration-service:test --tests '*ClusterRootComponentConnectionFactoryTest' > $SCRATCH/t8.log 2>&1; echo "exit=$?"`. Expected `exit=0` — the factory is already generic over `(workflowNodeName, extensions)`. If it passes, the only remaining check is that `ComponentConnectionFacadeImpl.getComponentConnections(WorkflowTrigger)` routes through `componentConnectionFactoryResolver.resolve(componentDefinition)` the way the task overload does (read lines 147–200). If the trigger overload only builds the trigger's own connection, change it to the task overload's shape: resolve the factory by the trigger's component definition and call `factory.create(workflowTrigger.getName(), workflowTrigger.getExtensions(), componentDefinition)`.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/platform/platform-configuration/platform-configuration-service
git commit -m "Voice - Prove cluster element connections resolve for a trigger's elements"
```

---

## Phase 2 — Engine and handlers

### Task 9: `VoiceSessionRegistry` replaces `CallSessionRegistry`

**Files:**
- Create: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/VoiceSessionRegistry.java`
- Create: `$WS/src/test/java/com/bytechef/platform/webhook/web/websocket/VoiceSessionRegistryTest.java`
- Delete: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/CallSessionRegistry.java` (in Task 12/13 once the handlers no longer reference it — create the new class now, delete the old one at the end of Task 13)

**Interfaces:**
```java
public class VoiceSessionRegistry {                       // @Component
    public static final Duration RESUME_WINDOW = Duration.ofSeconds(30);

    public Session register(String sessionId, WebSocketSession webSocketSession, @Nullable String workflowExecutionId);
    public Optional<Session> get(String sessionId);
    /** Marks the WS gone but keeps the record for RESUME_WINDOW so a reconnect can re-attach. */
    public void detach(String sessionId);
    /** Re-binds a resumed browser WS; returns the session, or empty when unknown or outside the window. */
    public Optional<Session> resume(String sessionId, WebSocketSession webSocketSession);
    public void remove(String sessionId);
    public boolean isAttached(String sessionId);

    public static final class Session {
        String sessionId(); Instant startedAt(); @Nullable String workflowExecutionId();
        @Nullable WebSocketSession webSocketSession(); void webSocketSession(WebSocketSession);
        @Nullable Long engineSessionId(); void engineSessionId(long);
        @Nullable Instant detachedAt();
        Instant lastInboundAudioAt(); void touchInboundAudio();
    }
}
```

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.webhook.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class VoiceSessionRegistryTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-08T10:00:00Z"));
    private final VoiceSessionRegistry registry = new VoiceSessionRegistry(now::get);

    @Test
    void testRegisterThenGet() {
        WebSocketSession webSocketSession = webSocketSession("ws-1");

        registry.register("s-1", webSocketSession, "wf-exec-1");

        assertThat(registry.get("s-1"))
            .get()
            .satisfies(session -> {
                assertThat(session.webSocketSession()).isSameAs(webSocketSession);
                assertThat(session.workflowExecutionId()).isEqualTo("wf-exec-1");
                assertThat(session.startedAt()).isEqualTo(now.get());
            });
        assertThat(registry.isAttached("s-1")).isTrue();
    }

    @Test
    void testResumeWithinWindowReattachesTheSameSession() {
        registry.register("s-1", webSocketSession("ws-1"), null);
        registry.get("s-1")
            .orElseThrow()
            .engineSessionId(42L);

        registry.detach("s-1");

        assertThat(registry.isAttached("s-1")).isFalse();

        now.set(now.get()
            .plus(Duration.ofSeconds(10)));

        WebSocketSession replacement = webSocketSession("ws-2");

        assertThat(registry.resume("s-1", replacement))
            .get()
            .satisfies(session -> {
                assertThat(session.webSocketSession()).isSameAs(replacement);
                assertThat(session.engineSessionId()).isEqualTo(42L);
                assertThat(session.detachedAt()).isNull();
            });
    }

    @Test
    void testResumeAfterWindowIsRejectedAndForgotten() {
        registry.register("s-1", webSocketSession("ws-1"), null);
        registry.detach("s-1");

        now.set(now.get()
            .plus(VoiceSessionRegistry.RESUME_WINDOW)
            .plusSeconds(1));

        assertThat(registry.resume("s-1", webSocketSession("ws-2"))).isEmpty();
        assertThat(registry.get("s-1")).isEmpty();
    }

    @Test
    void testResumeOfAnAttachedSessionIsRejected() {
        registry.register("s-1", webSocketSession("ws-1"), null);

        assertThat(registry.resume("s-1", webSocketSession("ws-2"))).isEmpty();
    }

    private static WebSocketSession webSocketSession(String id) {
        WebSocketSession webSocketSession = mock(WebSocketSession.class);

        when(webSocketSession.getId()).thenReturn(id);

        return webSocketSession;
    }
}
```

- [ ] **Step 2: Run it to verify it fails** — `./gradlew :server:libs:platform:platform-webhook:platform-websocket-webhook-rest:test --tests '*VoiceSessionRegistryTest' > $SCRATCH/t9.log 2>&1; echo "exit=$?"` → `exit=1`.

- [ ] **Step 3: Implement**

```java
package com.bytechef.platform.webhook.web.websocket;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Live browser voice sessions on this node, keyed by the ByteChef-issued session id. A session outlives a single
 * WebSocket: when the browser's socket drops the record is {@link #detach detached} and kept for
 * {@link #RESUME_WINDOW}, so a reconnect carrying the same id re-attaches to the same engine session instead of
 * starting a new conversation.
 *
 * @author Ivica Cardic
 */
@Component
public class VoiceSessionRegistry {

    public static final Duration RESUME_WINDOW = Duration.ofSeconds(30);

    private final Cache<String, Session> sessions = Caffeine.newBuilder()
        .expireAfterWrite(4, TimeUnit.HOURS)
        .maximumSize(10000)
        .build();
    private final Supplier<Instant> clock;

    @Autowired
    public VoiceSessionRegistry() {
        this(Instant::now);
    }

    VoiceSessionRegistry(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public Session register(
        String sessionId, WebSocketSession webSocketSession, @Nullable String workflowExecutionId) {

        Session session = new Session(sessionId, webSocketSession, workflowExecutionId, clock.get());

        sessions.put(sessionId, session);

        return session;
    }

    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.getIfPresent(sessionId));
    }

    public boolean isAttached(String sessionId) {
        Session session = sessions.getIfPresent(sessionId);

        return session != null && session.detachedAt == null;
    }

    public void detach(String sessionId) {
        Session session = sessions.getIfPresent(sessionId);

        if (session != null) {
            session.webSocketSession = null;
            session.detachedAt = clock.get();
        }
    }

    public Optional<Session> resume(String sessionId, WebSocketSession webSocketSession) {
        Session session = sessions.getIfPresent(sessionId);

        if (session == null || session.detachedAt == null) {
            return Optional.empty();
        }

        Instant now = clock.get();

        if (session.detachedAt.plus(RESUME_WINDOW)
            .isBefore(now)) {

            sessions.invalidate(sessionId);

            return Optional.empty();
        }

        session.webSocketSession = webSocketSession;
        session.detachedAt = null;
        session.lastInboundAudioAt = now;

        return Optional.of(session);
    }

    public void remove(String sessionId) {
        sessions.invalidate(sessionId);
    }

    public final class Session {

        private final String sessionId;
        private final Instant startedAt;
        private final @Nullable String workflowExecutionId;
        private volatile @Nullable WebSocketSession webSocketSession;
        private volatile @Nullable Long engineSessionId;
        private volatile @Nullable Instant detachedAt;
        private volatile Instant lastInboundAudioAt;

        private Session(
            String sessionId, WebSocketSession webSocketSession, @Nullable String workflowExecutionId,
            Instant startedAt) {

            this.sessionId = sessionId;
            this.webSocketSession = webSocketSession;
            this.workflowExecutionId = workflowExecutionId;
            this.startedAt = startedAt;
            this.lastInboundAudioAt = startedAt;
        }

        public String sessionId() {
            return sessionId;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public @Nullable String workflowExecutionId() {
            return workflowExecutionId;
        }

        public @Nullable WebSocketSession webSocketSession() {
            return webSocketSession;
        }

        public @Nullable Long engineSessionId() {
            return engineSessionId;
        }

        public void engineSessionId(long engineSessionId) {
            this.engineSessionId = engineSessionId;
        }

        public @Nullable Instant detachedAt() {
            return detachedAt;
        }

        public Instant lastInboundAudioAt() {
            return lastInboundAudioAt;
        }

        public void touchInboundAudio() {
            lastInboundAudioAt = clock.get();
        }
    }
}
```

- [ ] **Step 4: Run the test** → `exit=0`. (Mockito may need `WebSocketSession` mocks with `mockito-core` in the module's test deps — it is already there for other tests; if not, add `testImplementation("org.mockito:mockito-core")`.)

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply -q
git add $WS/src/main/java/com/bytechef/platform/webhook/web/websocket/VoiceSessionRegistry.java $WS/src/test/java/com/bytechef/platform/webhook/web/websocket/VoiceSessionRegistryTest.java
git commit -m "Voice - Add VoiceSessionRegistry with a 30s resume window"
```

### Task 10: Rewrite `VoiceSessionEngine` around one cluster element

**Files:**
- Rewrite: `$WS/src/main/java/com/bytechef/platform/webhook/voice/VoiceSessionEngine.java`
- Create: `$WS/src/main/java/com/bytechef/platform/webhook/voice/SessionTranscript.java`
- Modify: `$WS/src/main/java/com/bytechef/platform/webhook/voice/WebSocketEmitter.java` (delete `cancelTurn`, `addTurnCancelListener`, `addOutboundTurnCancelListener`, `outboundTurnCancelListeners`, and the turn-cancel listener list; also remove `cancelTurn` from the SDK `ActionDefinition.WebSocketHandler.WebSocketEmitter` interface if it declares it — `grep -n cancelTurn sdks/backend/java/component-api/src/main/java/com/bytechef/component/definition/ActionDefinition.java`)
- Modify: `$WS/src/main/java/com/bytechef/platform/webhook/voice/WebSocketEmitterRegistry.java` — rename parameters `jobId`→`sessionId`, `taskName`→`elementName`, update the Javadoc (no behaviour change)
- Delete: `$WS/src/main/java/com/bytechef/platform/webhook/voice/WebSocketTaskChain.java`, `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WebsocketTasks.java`, `$WS/src/test/java/com/bytechef/platform/webhook/web/websocket/WebsocketTasksTest.java`
- Rewrite: `$WS/src/test/java/com/bytechef/platform/webhook/voice/VoiceAgentIntTest.java`
- Modify: `$WS/build.gradle.kts` — add `implementation(project(":server:libs:platform:platform-component:platform-component-context:platform-component-context-api"))`, `implementation(project(":server:libs:platform:platform-connection:platform-connection-api"))`, `implementation(project(":server:libs:platform:platform-workflow:platform-workflow-coordinator:platform-workflow-coordinator-api"))`, `implementation(project(":server:libs:platform:platform-workflow:platform-workflow-execution:platform-workflow-execution-api"))`; remove `platform-job-sync` if nothing else in the module imports from it (`grep -rn 'job.sync' $WS/src/main`)

**Interfaces:**
```java
public class VoiceSessionEngine {                                        // @Component
    public VoiceSessionEngine(ClusterElementDefinitionService, ContextFactory, ConnectionService,
        Evaluator, WebSocketEmitterRegistry, ObjectProvider<VoiceAgentToolsetFactory>);

    public VoiceSession start(ClusterElementMap triggerClusterElements, Map<String, Long> connectionIds,
        Map<String, ?> sessionInputs, @Nullable Long environmentId, @Nullable PlatformType type,
        boolean editorEnvironment, Consumer<WebSocketEmitter> bridge);
    public void stop(long sessionId);
    public Optional<WebSocketEmitter> emitter(long sessionId);

    public record VoiceSession(long sessionId, WebSocketEmitter emitter, int outputSampleRate, SessionTranscript transcript) {}
}
public final class SessionTranscript {        // thread-safe, capped at 2000 entries
    public void user(String text); public void assistant(String text);
    public void toolCall(String name, Map<String, ?> arguments, String result);
    public List<Map<String, Object>> entries();   // [{role,text,at}]
    public List<Map<String, Object>> toolCalls(); // [{name,arguments,result,at}]
}
```
`connectionIds` is keyed by the element's `workflowNodeName` (e.g. `voiceAgent_1`) and nested tool names; the engine turns each into a `ComponentConnection` through `ConnectionService`. `outputSampleRate` is read from the element's `outputSampleRate` parameter (default 24000 when absent) so the handler can put it on the `connected` event.

- [ ] **Step 1: Write the failing test** (full replacement of `VoiceAgentIntTest`)

```java
package com.bytechef.platform.webhook.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition.WebSocketHandler;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.definition.voice.VoiceAgentContext;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolset;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolsetFactory;
import com.bytechef.platform.component.definition.voice.VoiceToolDefinition;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElementMap;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine.VoiceSession;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Drives a voice session through the real {@link VoiceSessionEngine} and {@link WebSocketEmitterRegistry}; only the
 * provider element is faked, at the {@link VoiceAgentFunction} seam.
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class VoiceAgentIntTest {

    private static final long TIMEOUT_SECONDS = 5;

    private final ConcurrentLinkedQueue<byte[]> outboundAudio = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Object> outboundEvents = new ConcurrentLinkedQueue<>();
    private final CountDownLatch audioReceived = new CountDownLatch(1);
    private final AtomicReference<Map<String, ?>> agentInputParameters = new AtomicReference<>();
    private final AtomicReference<VoiceAgentContext> agentContext = new AtomicReference<>();

    private VoiceSessionEngine voiceSessionEngine;
    private WebSocketEmitterRegistry webSocketEmitterRegistry;
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void beforeEach() {
        clusterElementDefinitionService = mock(ClusterElementDefinitionService.class);
        webSocketEmitterRegistry = new WebSocketEmitterRegistry();

        ContextFactory contextFactory = mock(ContextFactory.class);

        when(contextFactory.createActionContext(
            anyString(), anyInt(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(Boolean.class)))
                .thenReturn(mock(ActionContext.class));

        ObjectProvider<VoiceAgentToolsetFactory> toolsetFactoryProvider = mock(ObjectProvider.class);

        when(toolsetFactoryProvider.getIfAvailable()).thenReturn((extensions, connections, context) -> new Toolset());

        voiceSessionEngine = new VoiceSessionEngine(
            clusterElementDefinitionService, contextFactory, mock(ConnectionService.class), SpelEvaluator.create(),
            webSocketEmitterRegistry, toolsetFactoryProvider);

        VoiceAgentFunction fakeVoiceAgent = (inputParameters, connectionParameters, context) -> {
            agentInputParameters.set(inputParameters.toMap());
            agentContext.set(context);

            return createVoiceAgentStage(context);
        };

        when(clusterElementDefinitionService.getClusterElement(eq("fake"), eq(1), eq("voiceAgent")))
            .thenReturn(fakeVoiceAgent);
    }

    @Test
    void testSingleElementCarriesAudioAllTheWayThrough() throws Exception {
        VoiceSession voiceSession = start("{\"greeting\":\"hi\"}");

        voiceSession.emitter()
            .dispatchBinaryMessage("hello there".getBytes(StandardCharsets.UTF_8));

        assertThat(audioReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(new String(outboundAudio.peek(), StandardCharsets.UTF_8)).isEqualTo("you said: hello there");
    }

    @Test
    void testElementParametersAreEvaluatedAgainstSessionInputs() {
        start("{\"greeting\":\"session ${sessionId}\"}");

        assertThat(agentInputParameters.get())
            .extracting(parameters -> parameters.get("greeting"))
            .isEqualTo("session S-1");
    }

    @Test
    void testTranscriptCollectsNormalisedEventsAndToolCalls() {
        VoiceSession voiceSession = start("{}");

        voiceSession.emitter()
            .dispatchBinaryMessage("__tool__".getBytes(StandardCharsets.UTF_8));

        assertThat(voiceSession.transcript()
            .entries())
                .extracting(entry -> entry.get("role"), entry -> entry.get("text"))
                .contains(org.assertj.core.groups.Tuple.tuple("assistant", "ordered"));
        assertThat(voiceSession.transcript()
            .toolCalls())
                .singleElement()
                .satisfies(toolCall -> {
                    assertThat(toolCall.get("name")).isEqualTo("lookupOrder");
                    assertThat(toolCall.get("result")).isEqualTo("order 4411 shipped");
                });
    }

    @Test
    void testOutputSampleRateIsReadFromTheElement() {
        assertThat(start("{\"outputSampleRate\":16000}").outputSampleRate()).isEqualTo(16000);
        assertThat(start("{}").outputSampleRate()).isEqualTo(24000);
    }

    @Test
    void testMissingVoiceAgentElementFailsFast() {
        assertThatThrownBy(() -> voiceSessionEngine.start(
            ClusterElementMap.of(Map.of()), Map.of(), Map.of("sessionId", "S-1"), null, null, false, emitter -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Voice Agent");
    }

    @Test
    void testStoppingTheSessionReleasesTheEmitter() {
        VoiceSession voiceSession = start("{}");

        voiceSessionEngine.stop(voiceSession.sessionId());

        assertThat(voiceSessionEngine.emitter(voiceSession.sessionId())).isEmpty();
    }

    private VoiceSession start(String parametersJson) {
        Map<?, ?> parameters = JsonUtils.read(parametersJson, Map.class);

        ClusterElementMap clusterElementMap = ClusterElementMap.of(
            Map.of(
                "clusterElements", Map.of(
                    "voiceAgent", Map.of(
                        "name", "voiceAgent_1", "type", "fake/v1/voiceAgent", "parameters", parameters))));

        return voiceSessionEngine.start(
            clusterElementMap, Map.of(), Map.of("sessionId", "S-1"), null, null, false, this::attachBridge);
    }

    private void attachBridge(WebSocketEmitter emitter) {
        emitter.addOutboundBinaryListener(bytes -> {
            outboundAudio.add(bytes);
            audioReceived.countDown();
        });
        emitter.addOutboundListener(outboundEvents::add);
    }

    /**
     * Fake all-in-one agent: echoes audio, and on the {@code __tool__} marker calls a tool and speaks the result.
     */
    private static WebSocketHandler createVoiceAgentStage(VoiceAgentContext context) {
        return emitter -> emitter.addBinaryMessageListener(audio -> {
            String transcript = new String(audio, StandardCharsets.UTF_8);

            if ("__tool__".equals(transcript)) {
                String result = context.toolset()
                    .call("lookupOrder", "{\"id\":\"4411\"}");

                emitter.send(Map.of("type", "tool_call", "name", "lookupOrder", "arguments", Map.of("id", "4411")));
                emitter.send(Map.of("type", "tool_result", "name", "lookupOrder", "ok", true, "result", result));
                emitter.send(Map.of("type", "assistant_text", "text", "ordered"));

                return;
            }

            emitter.sendBinary(("you said: " + transcript).getBytes(StandardCharsets.UTF_8));
        });
    }

    private static final class Toolset implements VoiceAgentToolset {

        @Override
        public List<VoiceToolDefinition> definitions() {
            return List.of(new VoiceToolDefinition("lookupOrder", "Looks up an order", "{\"type\":\"object\"}"));
        }

        @Override
        public String call(String name, String argumentsJson) {
            return "order 4411 shipped";
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails** — `./gradlew :server:libs:platform:platform-webhook:platform-websocket-webhook-rest:test --tests '*VoiceAgentIntTest' > $SCRATCH/t10.log 2>&1; echo "exit=$?"` → `exit=1`.

- [ ] **Step 3: Implement `SessionTranscript`**

```java
package com.bytechef.platform.webhook.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a session said and did, in order. Bounded because a session is already bounded in time; the cap only guards
 * against a provider that floods interim events.
 *
 * @author Ivica Cardic
 */
public final class SessionTranscript {

    static final int MAX_ENTRIES = 2000;

    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final List<Map<String, Object>> toolCalls = new ArrayList<>();

    public synchronized void user(String text) {
        add("user", text);
    }

    public synchronized void assistant(String text) {
        add("assistant", text);
    }

    public synchronized void toolCall(String name, Map<String, ?> arguments, String result) {
        if (toolCalls.size() >= MAX_ENTRIES) {
            return;
        }

        Map<String, Object> toolCall = new LinkedHashMap<>();

        toolCall.put("name", name);
        toolCall.put("arguments", arguments);
        toolCall.put("result", result);
        toolCall.put("at", Instant.now()
            .toString());

        toolCalls.add(toolCall);
    }

    public synchronized List<Map<String, Object>> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<Map<String, Object>> toolCalls() {
        return List.copyOf(toolCalls);
    }

    private void add(String role, String text) {
        if (entries.size() >= MAX_ENTRIES) {
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>();

        entry.put("role", role);
        entry.put("text", text);
        entry.put("at", Instant.now()
            .toString());

        entries.add(entry);
    }
}
```

- [ ] **Step 4: Rewrite `VoiceSessionEngine`**

```java
package com.bytechef.platform.webhook.voice;

import com.bytechef.commons.util.MapUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition.WebSocketHandler;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.context.ContextFactory;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.voice.VoiceAgentContext;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolset;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolsetFactory;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.configuration.domain.ClusterElementMap;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hosts a voice session: resolves the trigger's single Voice Agent cluster element, gives it an emitter, and keeps
 * the pair alive until {@link #stop}. A voice session is not a workflow run — Atlas dispatches tasks sequentially and
 * a voice element never completes until the caller hangs up — so nothing about a live session flows through the
 * task engine; Atlas re-enters only through the continuation job the WS handler creates on close.
 *
 * <p>
 * <b>Known gap:</b> LLM and tool usage during a session is not cost-attributed: usage events need a job id and a
 * session has none. Post-session work in the continuation job is tracked normally.
 *
 * @author Ivica Cardic
 */
@Component
public class VoiceSessionEngine {

    private static final Logger log = LoggerFactory.getLogger(VoiceSessionEngine.class);

    private static final String ELEMENT_NAME = "voiceAgent";
    private static final String OUTPUT_SAMPLE_RATE = "outputSampleRate";
    private static final int DEFAULT_OUTPUT_SAMPLE_RATE = 24000;

    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ConnectionService connectionService;
    private final ContextFactory contextFactory;
    private final Evaluator evaluator;
    private final ObjectProvider<VoiceAgentToolsetFactory> voiceAgentToolsetFactoryProvider;
    private final WebSocketEmitterRegistry webSocketEmitterRegistry;

    private final AtomicLong sessionIds = new AtomicLong();
    private final Cache<Long, VoiceSession> sessions;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public VoiceSessionEngine(
        ClusterElementDefinitionService clusterElementDefinitionService, ContextFactory contextFactory,
        ConnectionService connectionService, Evaluator evaluator, WebSocketEmitterRegistry webSocketEmitterRegistry,
        ObjectProvider<VoiceAgentToolsetFactory> voiceAgentToolsetFactoryProvider) {

        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.connectionService = connectionService;
        this.contextFactory = contextFactory;
        this.evaluator = evaluator;
        this.voiceAgentToolsetFactoryProvider = voiceAgentToolsetFactoryProvider;
        this.webSocketEmitterRegistry = webSocketEmitterRegistry;
        this.sessions = Caffeine.newBuilder()
            .expireAfterWrite(4, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();
    }

    /**
     * Starts the trigger's Voice Agent and wires it into the caller's session.
     *
     * @param triggerClusterElements the trigger's cluster elements; must hold exactly one {@code voiceAgent}
     * @param connectionIds          connection ids keyed by element workflow node name (the agent and its tools)
     * @param sessionInputs          values the element's parameters may reference: {@code sessionId},
     *                               {@code startedAt}, {@code testMode}
     * @param environmentId          the environment connections resolve in
     * @param type                   AUTOMATION or EMBEDDED
     * @param editorEnvironment      whether this is an in-editor test session
     * @param bridge                 receives the element's emitter so the caller can attach its WebSocket
     */
    public VoiceSession start(
        ClusterElementMap triggerClusterElements, Map<String, Long> connectionIds, Map<String, ?> sessionInputs,
        @Nullable Long environmentId, @Nullable PlatformType type, boolean editorEnvironment,
        Consumer<WebSocketEmitter> bridge) {

        ClusterElement clusterElement = triggerClusterElements.fetchClusterElement(VoiceAgentFunction.VOICE_AGENT)
            .orElseThrow(() -> new IllegalStateException(
                "The voice session trigger has no Voice Agent. Add a Voice Agent cluster element to the trigger."));

        VoiceAgentFunction voiceAgentFunction = clusterElementDefinitionService.getClusterElement(
            clusterElement.getComponentName(), clusterElement.getComponentVersion(),
            clusterElement.getClusterElementName());

        // No job runs during a session, so nothing else would resolve ${sessionId} and friends for the element.
        Map<String, ?> inputParameters = evaluator.evaluate(clusterElement.getParameters(), sessionInputs);

        Map<String, ComponentConnection> connections = resolveConnections(connectionIds);
        ComponentConnection componentConnection = connections.get(clusterElement.getWorkflowNodeName());

        ActionContext actionContext = contextFactory.createActionContext(
            clusterElement.getComponentName(), clusterElement.getComponentVersion(), ELEMENT_NAME, null, null, null,
            null, null, componentConnection, environmentId, type, editorEnvironment);

        VoiceAgentToolset toolset = Optional.ofNullable(voiceAgentToolsetFactoryProvider.getIfAvailable())
            .map(factory -> factory.create(clusterElement.getExtensions(), connections, actionContext))
            .orElse(VoiceAgentToolset.EMPTY);

        long sessionId = sessionIds.incrementAndGet();
        WebSocketEmitter emitter = new WebSocketEmitter();
        SessionTranscript transcript = new SessionTranscript();

        emitter.addOutboundListener(payload -> record(transcript, payload));

        // Register and bridge BEFORE the element runs: a provider that greets the caller the moment it starts must
        // not emit into an unwired session.
        webSocketEmitterRegistry.register(sessionId, ELEMENT_NAME, emitter);
        bridge.accept(emitter);

        VoiceSession voiceSession = new VoiceSession(
            sessionId, emitter, MapUtils.getInteger(inputParameters, OUTPUT_SAMPLE_RATE, DEFAULT_OUTPUT_SAMPLE_RATE),
            transcript);

        sessions.put(sessionId, voiceSession);

        try {
            WebSocketHandler webSocketHandler = voiceAgentFunction.apply(
                ParametersFactory.create(inputParameters), ParametersFactory.create(componentConnection),
                new VoiceAgentContext(actionContext, toolset));

            webSocketHandler.handle(emitter);
        } catch (Exception exception) {
            stop(sessionId);

            throw new IllegalStateException("Failed to start the Voice Agent: " + exception.getMessage(), exception);
        }

        log.info(
            "Voice session started: sessionId={}, element={}", sessionId, clusterElement.getType());

        return voiceSession;
    }

    public Optional<WebSocketEmitter> emitter(long sessionId) {
        return webSocketEmitterRegistry.get(sessionId, ELEMENT_NAME);
    }

    /**
     * Ends a session: completing the emitter is what tells the provider element to close its connection. Safe to call
     * for an unknown or already-stopped session.
     */
    public void stop(long sessionId) {
        webSocketEmitterRegistry.get(sessionId, ELEMENT_NAME)
            .ifPresent(emitter -> {
                try {
                    emitter.complete();
                } catch (Exception exception) {
                    log.warn("Failed to complete voice session emitter: sessionId={}", sessionId, exception);
                }
            });

        webSocketEmitterRegistry.unregisterAll(sessionId);
        sessions.invalidate(sessionId);

        log.info("Voice session stopped: sessionId={}", sessionId);
    }

    private Map<String, ComponentConnection> resolveConnections(Map<String, Long> connectionIds) {
        Map<String, ComponentConnection> connections = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : connectionIds.entrySet()) {
            Connection connection = connectionService.getConnection(entry.getValue());

            connections.put(
                entry.getKey(),
                new ComponentConnection(
                    connection.getComponentName(), connection.getConnectionVersion(), entry.getValue(),
                    connection.getParameters(), connection.getAuthorizationType()));
        }

        return connections;
    }

    @SuppressWarnings("unchecked")
    private static void record(SessionTranscript transcript, Object payload) {
        if (!(payload instanceof Map<?, ?> event)) {
            return;
        }

        Map<String, ?> map = (Map<String, ?>) event;
        String type = String.valueOf(map.get("type"));
        Object text = map.get("text");

        switch (type) {
            case "transcript_final" -> transcript.user(String.valueOf(text));
            case "assistant_text" -> transcript.assistant(String.valueOf(text));
            case "tool_result" -> transcript.toolCall(
                String.valueOf(map.get("name")), MapUtils.getMap(map, "arguments", Map.of()),
                String.valueOf(map.get("result")));
            default -> {
            }
        }
    }

    public record VoiceSession(
        long sessionId, WebSocketEmitter emitter, int outputSampleRate, SessionTranscript transcript) {
    }
}
```
Notes for the implementer: `MapUtils.getMap(map, key, defaultValue)` — check the exact overload in `commons-util`; the `default -> {}` branch needs a statement to satisfy `EmptyBlock` — use `default -> log.trace("Unrecorded voice event: {}", type);`. Providers emit `tool_result` with `arguments` and `result` (Task 16) so the transcript can record the call — add `arguments`/`result` to the stage contract in `.agents/voice.md` (Task 28).

Delete `WebSocketTaskChain`, `WebsocketTasks`, `WebsocketTasksTest`; strip `cancelTurn` from `WebSocketEmitter` (and the SDK interface if declared there — then also drop any `cancelTurn` call in `DeepgramVoiceAgentAction`, none expected).

- [ ] **Step 5: Run the test** → `exit=0`. Also `./gradlew :server:libs:platform:platform-webhook:platform-websocket-webhook-rest:compileJava` will now FAIL in both handlers (they still call the old `start` signature and `WebsocketTasks`) — that is expected until Tasks 12–13; run only the `--tests '*VoiceAgentIntTest'` filter... which needs the module to compile. So do Tasks 10–13 as one unit of compilation: implement 10, then 11, 12, 13, then run all module tests; commit per task by path once green (commits may be built up with `git add` per task and committed in sequence at the end of Task 13).

- [ ] **Step 6: Stage (commit at the end of Task 13)**

```bash
git add $WS/src/main/java/com/bytechef/platform/webhook/voice $WS/src/test/java/com/bytechef/platform/webhook/voice $WS/build.gradle.kts
git rm -q --cached $WS/src/main/java/com/bytechef/platform/webhook/voice/WebSocketTaskChain.java $WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WebsocketTasks.java $WS/src/test/java/com/bytechef/platform/webhook/web/websocket/WebsocketTasksTest.java 2>/dev/null || true
```

### Task 11: `VoiceSessionConnectionResolver` — connection ids for a trigger's elements

**Files:**
- Create: `$WS/src/main/java/com/bytechef/platform/webhook/voice/VoiceSessionConnectionResolver.java`
- Create: `$WS/src/test/java/com/bytechef/platform/webhook/voice/VoiceSessionConnectionResolverTest.java`

**Interfaces:**
```java
@Component
public class VoiceSessionConnectionResolver {
    public VoiceSessionConnectionResolver(List<TriggerDispatcherPreSendProcessor>, WorkflowTestConfigurationService);
    /** Deployed path: runs the trigger through the platform's pre-send processors, which stamp CONNECTION_IDS. */
    public Map<String, Long> resolveDeployed(WorkflowExecutionId workflowExecutionId, WorkflowTrigger workflowTrigger);
    /** Editor path: the workflow test configuration's connections for the trigger node. */
    public Map<String, Long> resolveTest(String workflowId, String triggerName, long environmentId);
}
```

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.webhook.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.configuration.domain.WorkflowTestConfigurationConnection;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.trigger.dispatcher.TriggerDispatcherPreSendProcessor;
import com.bytechef.platform.workflow.execution.domain.TriggerExecution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoiceSessionConnectionResolverTest {

    @Test
    void testDeployedPathReadsConnectionIdsStampedByThePreSendProcessor() {
        TriggerDispatcherPreSendProcessor processor = new TriggerDispatcherPreSendProcessor() {

            @Override
            public TriggerExecution process(TriggerExecution triggerExecution) {
                triggerExecution.putMetadata(MetadataConstants.CONNECTION_IDS, Map.of("voiceAgent_1", 12L));

                return triggerExecution;
            }

            @Override
            public boolean canProcess(TriggerExecution triggerExecution) {
                return true;
            }
        };

        VoiceSessionConnectionResolver resolver = new VoiceSessionConnectionResolver(
            List.of(processor), mock(WorkflowTestConfigurationService.class));

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 7L, "wf-uuid", "trigger_1");
        WorkflowTrigger workflowTrigger = mock(WorkflowTrigger.class);

        when(workflowTrigger.getName()).thenReturn("trigger_1");
        when(workflowTrigger.getType()).thenReturn("browser/v1/voiceSession");
        when(workflowTrigger.getParameters()).thenReturn(Map.of());

        assertThat(resolver.resolveDeployed(workflowExecutionId, workflowTrigger))
            .containsExactlyEntriesOf(Map.of("voiceAgent_1", 12L));
    }

    @Test
    void testTestPathReadsTheWorkflowTestConfiguration() {
        WorkflowTestConfigurationService service = mock(WorkflowTestConfigurationService.class);
        WorkflowTestConfigurationConnection connection = mock(WorkflowTestConfigurationConnection.class);

        when(connection.getWorkflowConnectionKey()).thenReturn("voiceAgent_1");
        when(connection.getConnectionId()).thenReturn(12L);
        when(service.getWorkflowTestConfigurationConnections("wf-1", "trigger_1", 1L)).thenReturn(List.of(connection));

        VoiceSessionConnectionResolver resolver = new VoiceSessionConnectionResolver(List.of(), service);

        assertThat(resolver.resolveTest("wf-1", "trigger_1", 1L)).containsExactlyEntriesOf(Map.of("voiceAgent_1", 12L));
    }
}
```
Check `WorkflowExecutionId`'s factory name (`of(...)`) and argument order in `platform-api`; and whether `WorkflowTrigger` is mockable (final?) — if final, construct it from a map as in Task 7.

- [ ] **Step 2: Implement**

```java
package com.bytechef.platform.webhook.voice;

import com.bytechef.commons.util.MapUtils;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.configuration.domain.WorkflowTestConfigurationConnection;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.coordinator.trigger.dispatcher.TriggerDispatcherPreSendProcessor;
import com.bytechef.platform.workflow.execution.domain.TriggerExecution;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Finds the connection ids a voice trigger's cluster elements run with. Deployed sessions reuse the platform's
 * trigger pre-send processors — the same code that stamps {@code CONNECTION_IDS} on a webhook trigger execution —
 * so a deployment's connection bindings for the trigger node (keyed by element workflow node name) come back without
 * this module knowing about project deployments or integration instances. Editor sessions read the workflow test
 * configuration instead.
 *
 * @author Ivica Cardic
 */
@Component
public class VoiceSessionConnectionResolver {

    private final List<TriggerDispatcherPreSendProcessor> triggerDispatcherPreSendProcessors;
    private final WorkflowTestConfigurationService workflowTestConfigurationService;

    @SuppressFBWarnings("EI")
    public VoiceSessionConnectionResolver(
        List<TriggerDispatcherPreSendProcessor> triggerDispatcherPreSendProcessors,
        WorkflowTestConfigurationService workflowTestConfigurationService) {

        this.triggerDispatcherPreSendProcessors = triggerDispatcherPreSendProcessors;
        this.workflowTestConfigurationService = workflowTestConfigurationService;
    }

    public Map<String, Long> resolveDeployed(WorkflowExecutionId workflowExecutionId, WorkflowTrigger workflowTrigger) {
        TriggerExecution triggerExecution = TriggerExecution.builder()
            .workflowExecutionId(workflowExecutionId)
            .workflowTrigger(workflowTrigger)
            .build();

        for (TriggerDispatcherPreSendProcessor processor : triggerDispatcherPreSendProcessors) {
            if (processor.canProcess(triggerExecution)) {
                triggerExecution = processor.process(triggerExecution);

                break;
            }
        }

        return MapUtils.getMap(triggerExecution.getMetadata(), MetadataConstants.CONNECTION_IDS, Long.class, Map.of());
    }

    public Map<String, Long> resolveTest(String workflowId, String triggerName, long environmentId) {
        Map<String, Long> connectionIds = new LinkedHashMap<>();

        for (WorkflowTestConfigurationConnection connection : workflowTestConfigurationService
            .getWorkflowTestConfigurationConnections(workflowId, triggerName, environmentId)) {

            connectionIds.put(connection.getWorkflowConnectionKey(), connection.getConnectionId());
        }

        return connectionIds;
    }
}
```
`$WS/build.gradle.kts` needs `platform-configuration-api` (already there) and the two coordinator/execution api projects added in Task 10.

- [ ] **Step 3: Stage** — `git add $WS/src/main/java/com/bytechef/platform/webhook/voice/VoiceSessionConnectionResolver.java $WS/src/test/java/com/bytechef/platform/webhook/voice/VoiceSessionConnectionResolverTest.java`

### Task 12: `WebhookWebSocketHandler` on the new model

**Files:**
- Rewrite the voice parts of: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WebhookWebSocketHandler.java` (keep `executeWorkflow`/`WebSocketStreamBridge` — the webhook-over-WS `execute` action is unrelated to voice)
- Create: `$WS/src/test/java/com/bytechef/platform/webhook/web/websocket/WebhookWebSocketHandlerVoiceTest.java`

**Behaviour to implement (each bullet is a test below):**
1. Upgrade with `sessionToken`: validate through `BrowserVoiceSessionTokenService.consume`; create `sessionId = UUID`; `voiceSessionRegistry.register(sessionId, ws, webhookId)`; send `{"event":"connected","id":webhookId,"sessionId":…,"outputSampleRate":N,"silenceTimeoutSeconds":M}` **after** the engine session started (the sample rate comes from it).
2. Upgrade with `sessionToken` + `resumeSessionId`: after token validation, `voiceSessionRegistry.resume(resumeSessionId, ws)`; on success re-attach the bridge to `voiceSessionEngine.emitter(engineSessionId)` and send `connected` with `"resumed":true`; on failure fall through to a fresh session.
3. Start: resolve the trigger (`resolveWorkflowTrigger`), `connectionIds = voiceSessionConnectionResolver.resolveDeployed(workflowExecutionId, trigger)`, `voiceSessionEngine.start(ClusterElementMap.of(trigger.getExtensions()), connectionIds, inputs{sessionId, startedAt, mainWorkflowExecutionId}, environmentId, type, false, bridge)`; on `IllegalStateException` send `{"type":"error","message":…}` and close `POLICY_VIOLATION`.
4. Inbound binary → `session.touchInboundAudio()` then `emitter.dispatchBinaryMessage`.
5. Silence timeout: a scheduled check every 5 s (same `sessionTimeoutScheduler`) closes with `CloseStatus.NORMAL.withReason("silence_timeout")` when `now - lastInboundAudioAt > silenceTimeoutSeconds` (trigger parameter `silenceTimeoutSeconds`, default 120, 0 = off).
6. Close: if the close was **not** initiated by the server (`endReason == null`) and the session is not finished → `voiceSessionRegistry.detach(sessionId)` and schedule a finalize after `RESUME_WINDOW`; a server-initiated close (session_limit, silence_timeout, provider_error) or the resume window elapsing → finalize: `voiceSessionEngine.stop`, continuation job with the §2 payload, `voiceSessionRegistry.remove`.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.webhook.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.webhook.voice.SessionTranscript;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine.VoiceSession;
import com.bytechef.platform.webhook.voice.WebSocketEmitter;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WebhookWebSocketHandlerVoiceTest {

    private final BrowserVoiceSessionTokenService tokenService = mock(BrowserVoiceSessionTokenService.class);
    private final VoiceSessionEngine voiceSessionEngine = mock(VoiceSessionEngine.class);
    private final VoiceSessionRegistry voiceSessionRegistry = new VoiceSessionRegistry();
    private final WorkflowContinuationHelper workflowContinuationHelper = mock(WorkflowContinuationHelper.class);
    private final WebhookWebSocketHandler handler = new WebhookWebSocketHandler(
        tokenService, voiceSessionRegistry, voiceSessionEngine, workflowContinuationHelper,
        new HandlerTestSupport.FixedTriggerResolver(), new HandlerTestSupport.NoConnections(),
        new VoiceMetricsRecorder(mock(org.springframework.beans.factory.ObjectProvider.class)),
        new com.fasterxml.jackson.databind.ObjectMapper(), 150L);

    @Test
    void testFreshSessionStartsTheEngineAndAnnouncesTheOutputSampleRate() throws Exception {
        WebSocketSession webSocketSession = openSocket("tok-1", null);
        WebSocketEmitter emitter = new WebSocketEmitter();

        when(tokenService.consume("tok-1", "hook-1")).thenReturn(true);
        when(voiceSessionEngine.start(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
            .thenReturn(new VoiceSession(1L, emitter, 24000, new SessionTranscript()));

        handler.afterConnectionEstablished(webSocketSession);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);

        verify(webSocketSession, timeout(2000)).sendMessage(sent.capture());

        assertThat(sent.getValue()
            .getPayload())
                .contains("\"event\":\"connected\"")
                .contains("\"outputSampleRate\":24000")
                .contains("\"sessionId\"");
    }

    @Test
    void testInvalidTokenIsRejected() throws Exception {
        WebSocketSession webSocketSession = openSocket("bad", null);

        when(tokenService.consume("bad", "hook-1")).thenReturn(false);

        handler.afterConnectionEstablished(webSocketSession);

        verify(webSocketSession).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void testServerInitiatedCloseFinalizesWithTranscriptAndReason() throws Exception {
        WebSocketSession webSocketSession = openSocket("tok-1", null);
        SessionTranscript transcript = new SessionTranscript();

        transcript.user("hello");

        when(tokenService.consume("tok-1", "hook-1")).thenReturn(true);
        when(voiceSessionEngine.start(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
            .thenReturn(new VoiceSession(1L, new WebSocketEmitter(), 24000, transcript));

        handler.afterConnectionEstablished(webSocketSession);
        verify(webSocketSession, timeout(2000)).sendMessage(any(TextMessage.class));

        handler.afterConnectionClosed(webSocketSession, CloseStatus.NORMAL.withReason("silence_timeout"));

        ArgumentCaptor<Map<String, Object>> output = ArgumentCaptor.captor();

        verify(workflowContinuationHelper).createContinuationJob(eq("hook-1"), output.capture());
        verify(voiceSessionEngine).stop(1L);

        assertThat(output.getValue())
            .containsEntry("endReason", "silence_timeout")
            .containsKeys("sessionId", "startedAt", "durationSeconds", "transcript", "toolCalls");
        assertThat((java.util.List<?>) output.getValue()
            .get("transcript")).hasSize(1);
    }

    @Test
    void testClientDropIsDetachedNotFinalized() throws Exception {
        WebSocketSession webSocketSession = openSocket("tok-1", null);

        when(tokenService.consume("tok-1", "hook-1")).thenReturn(true);
        when(voiceSessionEngine.start(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
            .thenReturn(new VoiceSession(1L, new WebSocketEmitter(), 24000, new SessionTranscript()));

        handler.afterConnectionEstablished(webSocketSession);
        verify(webSocketSession, timeout(2000)).sendMessage(any(TextMessage.class));

        handler.afterConnectionClosed(webSocketSession, CloseStatus.NO_CLOSE_FRAME);

        verify(voiceSessionEngine, org.mockito.Mockito.never()).stop(1L);
        verify(workflowContinuationHelper, org.mockito.Mockito.never()).createContinuationJob(any(), any());
    }

    private static WebSocketSession openSocket(String token, String resumeSessionId) {
        WebSocketSession webSocketSession = mock(WebSocketSession.class);
        String query = "sessionToken=" + token + (resumeSessionId == null ? "" : "&resumeSessionId=" + resumeSessionId);

        when(webSocketSession.getId()).thenReturn("ws-" + token);
        when(webSocketSession.getUri()).thenReturn(URI.create("ws://localhost/webhooks/hook-1/wss?" + query));
        when(webSocketSession.isOpen()).thenReturn(true);

        return webSocketSession;
    }
}
```
`HandlerTestSupport` is a small test-only class in the same package holding a `FixedTriggerResolver` (returns a `WorkflowTrigger` of type `browser/v1/voiceSession` with an empty `clusterElements` map and `silenceTimeoutSeconds: 0`) and `NoConnections` (a `VoiceSessionConnectionResolver` subclass returning `Map.of()` from both methods — make the resolver's methods non-final and the class non-final so it can be extended, or make it an interface with a default `@Component` impl). To make the handler testable, extract the trigger lookup (`resolveWorkflowTrigger` + `WorkflowExecutionId.parse`) into a package-private interface `TriggerResolver { WorkflowTrigger resolve(WorkflowExecutionId); }` with the production impl using `JobPrincipalAccessorRegistry` + `WorkflowService`, injected through the constructor. The handler's constructor becomes:

```java
public WebhookWebSocketHandler(
    BrowserVoiceSessionTokenService browserVoiceSessionTokenService, VoiceSessionRegistry voiceSessionRegistry,
    VoiceSessionEngine voiceSessionEngine, WorkflowContinuationHelper workflowContinuationHelper,
    TriggerResolver triggerResolver, VoiceSessionConnectionResolver voiceSessionConnectionResolver,
    VoiceMetricsRecorder voiceMetricsRecorder, ObjectMapper objectMapper,
    @Value("${bytechef.voice.max-session-duration-seconds:1800}") long maxSessionDurationSeconds)
```
(keep the existing `webhookWorkflowExecutor`/`jobPrincipalAccessorRegistry` parameters if `executeWorkflow` still needs them — read the current constructor and preserve what `WebSocketStreamBridge` uses).

- [ ] **Step 2: Implement** — rewrite the voice paths. Reference implementation for the pieces the tests pin down:

```java
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String webhookId = extractId(uri);
        String sessionToken = extractQueryParam(uri, "sessionToken");
        String resumeSessionId = extractQueryParam(uri, "resumeSessionId");

        if (sessionToken == null) {
            // Non-voice WS clients (the "execute" action) keep working exactly as before.
            return;
        }

        if (webhookId == null || !browserVoiceSessionTokenService.consume(sessionToken, webhookId)) {
            log.warn("Browser-voice WS upgrade rejected: invalid token for webhook={}", webhookId);

            voiceMetricsRecorder.recordTokenRejected();
            sendMessage(session, Map.of("type", "error", "message", "Invalid or expired session token"));
            session.close(CloseStatus.POLICY_VIOLATION);

            return;
        }

        voiceMetricsRecorder.recordTokenConsumed();

        if (resumeSessionId != null) {
            Optional<VoiceSessionRegistry.Session> resumed = voiceSessionRegistry.resume(resumeSessionId, session);

            if (resumed.isPresent() && resumed.get()
                .engineSessionId() != null) {

                resumeSession(session, resumed.get());

                return;
            }
        }

        String sessionId = UUID.randomUUID()
            .toString();

        VoiceSessionRegistry.Session voiceSession = voiceSessionRegistry.register(sessionId, session, webhookId);

        sessionIdByWebSocketSessionId.put(session.getId(), sessionId);
        voiceMetricsRecorder.recordSessionOpened();

        Thread.startVirtualThread(() -> startSession(session, voiceSession, webhookId));
    }

    private void startSession(WebSocketSession session, VoiceSessionRegistry.Session voiceSession, String webhookId) {
        try {
            WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.parse(webhookId);
            WorkflowTrigger workflowTrigger = triggerResolver.resolve(workflowExecutionId);

            Map<String, Object> inputs = new LinkedHashMap<>();

            inputs.put("sessionId", voiceSession.sessionId());
            inputs.put("startedAt", voiceSession.startedAt()
                .toString());
            inputs.put("mainWorkflowExecutionId", webhookId);

            VoiceSession engineSession = voiceSessionEngine.start(
                ClusterElementMap.of(workflowTrigger.getExtensions()),
                voiceSessionConnectionResolver.resolveDeployed(workflowExecutionId, workflowTrigger), inputs, null,
                workflowExecutionId.getType(), false, emitter -> attachOutboundBridge(session, emitter));

            voiceSession.engineSessionId(engineSession.sessionId());
            engineSessionsById.put(engineSession.sessionId(), engineSession);

            int silenceTimeoutSeconds = MapUtils.getInteger(
                workflowTrigger.getParameters(), SILENCE_TIMEOUT_SECONDS, DEFAULT_SILENCE_TIMEOUT_SECONDS);

            scheduleMaxDurationClose(session, session.getId(),
                sessionLimitSeconds(workflowTrigger, maxSessionDurationSeconds));
            scheduleSilenceCheck(session, voiceSession, silenceTimeoutSeconds);

            Map<String, Object> connected = new LinkedHashMap<>();

            connected.put("event", "connected");
            connected.put("id", webhookId);
            connected.put("sessionId", voiceSession.sessionId());
            connected.put("outputSampleRate", engineSession.outputSampleRate());
            connected.put("silenceTimeoutSeconds", silenceTimeoutSeconds);

            sendMessage(session, connected);
        } catch (Exception exception) {
            log.error("Failed to start voice session: sessionId={}", voiceSession.sessionId(), exception);

            sendMessage(session, Map.of("type", "error", "message", exception.getMessage()));
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("provider_error"));
        }
    }
```
`scheduleSilenceCheck` — every 5 s while the socket is open; when `silenceTimeoutSeconds > 0` and `Duration.between(voiceSession.lastInboundAudioAt(), Instant.now()).toSeconds() >= silenceTimeoutSeconds`, `session.close(CloseStatus.NORMAL.withReason("silence_timeout"))`. Store the `ScheduledFuture` beside the max-duration one and cancel both in `afterConnectionClosed`.

`afterConnectionClosed(session, status)`:
```java
        String sessionId = sessionIdByWebSocketSessionId.getIfPresent(session.getId());

        if (sessionId == null) {
            // execute-action sockets and rejected upgrades
            ... existing streamHandles/pendingEvents cleanup ...
            return;
        }

        cancelTimers(session.getId());
        sessionIdByWebSocketSessionId.invalidate(session.getId());

        String endReason = serverEndReason(status);   // "session_limit" | "silence_timeout" | "provider_error" | null

        if (endReason == null) {
            voiceSessionRegistry.detach(sessionId);
            sessionTimeoutScheduler.schedule(
                () -> finalizeIfStillDetached(sessionId), VoiceSessionRegistry.RESUME_WINDOW.toSeconds(), TimeUnit.SECONDS);

            return;
        }

        finalizeSession(sessionId, endReason);
```
`serverEndReason`: `status.getReason()` when it is one of the five reasons; the max-duration close must now use `CloseStatus.NORMAL.withReason("session_limit")`. `finalizeIfStillDetached`: `voiceSessionRegistry.get(sessionId)` present and `detachedAt() != null` → `finalizeSession(sessionId, "client_closed")`. `finalizeSession`:
```java
    private void finalizeSession(String sessionId, String endReason) {
        voiceSessionRegistry.get(sessionId)
            .ifPresent(voiceSession -> {
                Long engineSessionId = voiceSession.engineSessionId();
                VoiceSession engineSession = engineSessionId == null ? null : engineSessionsById.getIfPresent(engineSessionId);

                if (engineSessionId != null) {
                    voiceSessionEngine.stop(engineSessionId);
                    engineSessionsById.invalidate(engineSessionId);
                }

                if (voiceSession.workflowExecutionId() != null) {
                    workflowContinuationHelper.createContinuationJob(
                        voiceSession.workflowExecutionId(), sessionOutput(voiceSession, engineSession, endReason));
                }

                voiceMetricsRecorder.recordSessionClosed(Duration.between(voiceSession.startedAt(), Instant.now()));
            });

        voiceSessionRegistry.remove(sessionId);
    }

    static Map<String, Object> sessionOutput(
        VoiceSessionRegistry.Session voiceSession, @Nullable VoiceSession engineSession, String endReason) {

        Map<String, Object> output = new LinkedHashMap<>();

        output.put("sessionId", voiceSession.sessionId());
        output.put("startedAt", voiceSession.startedAt()
            .toString());
        output.put("durationSeconds", Duration.between(voiceSession.startedAt(), Instant.now())
            .toSeconds());
        output.put("endReason", endReason);
        output.put("transcript", engineSession == null ? List.of() : engineSession.transcript()
            .entries());
        output.put("toolCalls", engineSession == null ? List.of() : engineSession.transcript()
            .toolCalls());

        return output;
    }
```
Inbound binary: `voiceSessionRegistry.get(sessionId).ifPresent(s -> { s.touchInboundAudio(); engineSessionsById.getIfPresent(s.engineSessionId()).emitter().dispatchBinaryMessage(bytes); })`. `resumeSession`: re-attach `attachOutboundBridge(session, engineSession.emitter())`, re-schedule timers, send `connected` with `"resumed": true`. Delete `forwardInboundAudioToFirstTask`, `firstTaskNameByCallSid`, `sessionIdToCallSid`, `applyTriggerSessionLimit`, `getSessionLimitSeconds` (fold into `sessionLimitSeconds(trigger, default)`), `getWebsocketSubflowDefinition`, and every `CallSessionRegistry` reference. Keep the `execute` action path intact.

- [ ] **Step 3: Stage** — `git add $WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WebhookWebSocketHandler.java $WS/src/main/java/com/bytechef/platform/webhook/web/websocket/TriggerResolver.java $WS/src/test/java/com/bytechef/platform/webhook/web/websocket/WebhookWebSocketHandlerVoiceTest.java $WS/src/test/java/com/bytechef/platform/webhook/web/websocket/HandlerTestSupport.java`

### Task 13: `WorkflowTestWebSocketHandler` on the new model, delete `CallSessionRegistry`

**Files:**
- Rewrite: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WorkflowTestWebSocketHandler.java`
- Delete: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/CallSessionRegistry.java`
- Modify: `$WS/src/main/java/com/bytechef/platform/webhook/web/websocket/WebhookWebSocketConfiguration.java` (Javadoc only: no more `websocketTasks`, `CallSessionRegistry` → `VoiceSessionRegistry`)
- Create: `$WS/src/test/java/com/bytechef/platform/webhook/web/websocket/WorkflowTestWebSocketHandlerTest.java`

**Behaviour:** same as Task 12 minus continuation and resume; the upgrade URL gains `environmentId` (`/internal/workflow-tests/{workflowId}/wss?sessionToken=…&sampleRate=…&environmentId=…`); `connectionIds = voiceSessionConnectionResolver.resolveTest(workflowId, trigger.getName(), environmentId)`; inputs `{sessionId, startedAt, testMode: true}`; `editorEnvironment = true`; the trigger is the first `browser/v1/voiceSession` trigger of `workflowService.getWorkflow(workflowId)`; a missing Voice Agent element sends `{"type":"error","message":"Add a Voice Agent to the trigger to test with voice"}` and closes `BAD_DATA`; close always finalizes immediately (no resume in the editor), with `voiceSessionEngine.stop`.

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.platform.webhook.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.configuration.domain.Workflow;
import com.bytechef.platform.configuration.service.WorkflowService;
import com.bytechef.platform.webhook.voice.SessionTranscript;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine.VoiceSession;
import com.bytechef.platform.webhook.voice.WebSocketEmitter;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WorkflowTestWebSocketHandlerTest {

    private final WorkflowTestVoiceSessionTokenService tokenService = mock(WorkflowTestVoiceSessionTokenService.class);
    private final VoiceSessionEngine voiceSessionEngine = mock(VoiceSessionEngine.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowTestWebSocketHandler handler = new WorkflowTestWebSocketHandler(
        new VoiceSessionRegistry(), voiceSessionEngine, workflowService, tokenService,
        new HandlerTestSupport.NoConnections());

    @Test
    void testStartsTheDraftTriggerWithTestModeInputs() throws Exception {
        WebSocketSession webSocketSession = socket("tok-1");

        when(tokenService.consume("tok-1", "wf-1")).thenReturn(true);
        when(workflowService.getWorkflow("wf-1")).thenReturn(HandlerTestSupport.voiceWorkflow());
        when(voiceSessionEngine.start(any(), anyMap(), anyMap(), any(), any(), eq(true), any()))
            .thenReturn(new VoiceSession(5L, new WebSocketEmitter(), 24000, new SessionTranscript()));

        handler.afterConnectionEstablished(webSocketSession);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);

        verify(webSocketSession, timeout(2000)).sendMessage(sent.capture());
        assertThat(sent.getValue()
            .getPayload()).contains("\"event\":\"connected\"");

        handler.afterConnectionClosed(webSocketSession, CloseStatus.NORMAL);

        verify(voiceSessionEngine).stop(5L);
    }

    @Test
    void testWorkflowWithoutVoiceAgentIsRejectedWithAHint() throws Exception {
        WebSocketSession webSocketSession = socket("tok-1");

        when(tokenService.consume("tok-1", "wf-1")).thenReturn(true);
        when(workflowService.getWorkflow("wf-1")).thenReturn(HandlerTestSupport.voiceWorkflow());
        when(voiceSessionEngine.start(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
            .thenThrow(new IllegalStateException("The voice session trigger has no Voice Agent."));

        handler.afterConnectionEstablished(webSocketSession);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);

        verify(webSocketSession, timeout(2000)).sendMessage(sent.capture());
        assertThat(sent.getValue()
            .getPayload()).contains("Add a Voice Agent to the trigger");
        verify(webSocketSession, timeout(2000)).close(CloseStatus.BAD_DATA);
    }

    private static WebSocketSession socket(String token) {
        WebSocketSession webSocketSession = mock(WebSocketSession.class);

        when(webSocketSession.getId()).thenReturn("ws-" + token);
        when(webSocketSession.getUri()).thenReturn(
            URI.create("ws://localhost/internal/workflow-tests/wf-1/wss?sessionToken=" + token +
                "&sampleRate=16000&environmentId=1"));
        when(webSocketSession.isOpen()).thenReturn(true);

        return webSocketSession;
    }
}
```
`HandlerTestSupport.voiceWorkflow()` returns a `Workflow` built from the definition JSON `{"triggers":[{"name":"trigger_1","type":"browser/v1/voiceSession","parameters":{}}],"tasks":[]}` (see how `WorkflowFacadeTest` mocks `Workflow`; `new Workflow(id, definition, Format.JSON)` exists in `platform-configuration-api` — use the real constructor).

- [ ] **Step 2: Implement** (mirror Task 12's `startSession`, with `resolveTest` and `editorEnvironment = true`; `extractQueryParam(uri, "environmentId")` parsed as `long`, defaulting to `1` when absent). Delete `CallSessionRegistry.java`.

- [ ] **Step 3: Run the whole module and commit Tasks 10–13**

```bash
./gradlew :server:libs:platform:platform-webhook:platform-websocket-webhook-rest:test --continue > $SCRATCH/t13.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' $SCRATCH/t13.log
grep -rn 'CallSession\|callSid\|websocketTasks\|WebsocketTasks\|WebSocketTaskChain\|cancelTurn' $WS/src --include='*.java'
```
Expected: `exit=0`, no FAILED, no grep hits. Then four commits by path:
```bash
./gradlew spotlessApply -q
git add -A $WS/src/main/java/com/bytechef/platform/webhook/voice $WS/src/test/java/com/bytechef/platform/webhook/voice $WS/build.gradle.kts
git commit -m "Voice - Run one Voice Agent cluster element per session in VoiceSessionEngine"
git add -A $WS/src/main/java/com/bytechef/platform/webhook/web/websocket $WS/src/test/java/com/bytechef/platform/webhook/web/websocket
git commit -m "Voice - Start voice sessions from the trigger's cluster elements with resume and silence timeout"
```
(the resolver from Task 11 lands in the first commit since it lives under `voice/`.)

### Task 14: Delete `websocketTasks` from configuration and the worker guard

**Files:**
- Modify: `server/libs/platform/platform-configuration/platform-configuration-api/src/main/java/com/bytechef/platform/configuration/constant/WorkflowExtConstants.java` (remove `WEBSOCKET_TASKS` and its entry in `RESERVED_WORDS`)
- Modify: `server/libs/platform/platform-configuration/platform-configuration-api/src/main/java/com/bytechef/platform/configuration/domain/WorkflowTrigger.java` (remove the `websocketTasks` field/getter added in the voice commit — `git show cbf534a8275 -- <file>`)
- Delete: `server/libs/platform/platform-configuration/platform-configuration-service/src/test/java/com/bytechef/platform/configuration/workflow/contributor/WebsocketTasksReservedWordTest.java`
- Modify: `.../contributor/AiHubIdentityStampReservedWordTest.java` (drop the `WEBSOCKET_TASKS` expectation)
- Modify: `server/ee/apps/runtime-job-app/src/test/java/com/bytechef/runtime/job/configuration/workflow/contributor/WorkflowReservedWordContributorParityTest.java` (only if it lists the word explicitly)
- Delete: `server/libs/platform/platform-worker/src/main/java/com/bytechef/platform/worker/task/RealtimeActionTaskExecutionPostOutputProcessor.java` + its test; remove the `@Bean` in `PlatformWorkerConfiguration.java:50-54` and its import
- Modify: `openapi.yaml` (`WorkflowTriggerModel` no longer documents `websocketTasks` — done in Task 7's description edit; verify) and the generated `WorkflowTriggerModel` (regenerate)
- Delete: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/MultipleConnectionsWebSocketPerformFunction.java` is **not** deleted yet (Task 21)

- [ ] **Step 1: Make the edits, then**

```bash
./gradlew :server:libs:platform:platform-configuration:platform-configuration-service:test :server:libs:platform:platform-worker:test :server:ee:apps:runtime-job-app:test --continue > $SCRATCH/t14.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' $SCRATCH/t14.log
grep -rn 'WEBSOCKET_TASKS\|websocketTasks' server --include='*.java' --include='*.yaml' | grep -v '/build/'
```
Expected: `exit=0`, no grep hits.

- [ ] **Step 2: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/platform/platform-configuration server/libs/platform/platform-worker server/ee/apps/runtime-job-app
git commit -m "Voice - Drop the websocketTasks trigger extension and the realtime action guard"
```

---

## Phase 3 — Tool bridge

### Task 15: `VoiceAgentToolsetFactoryImpl` in `components/ai/llm`

**Files:**
- Create: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice/VoiceAgentToolsetFactoryImpl.java`
- Create: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice/ToolCallbackVoiceAgentToolset.java`
- Create: `server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/voice/ToolCallbackVoiceAgentToolsetTest.java`
- Read: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/ClusterElementToolCallbacks.java` (`build(clusterElement, connections, actionContext, approvalChannels)`), `DelegatingToolCallback.unwrap` (package-private — make it `public static` or put the toolset class in `com.bytechef.component.ai.llm.tool`)

**Interfaces:**
- Produces: Spring bean `VoiceAgentToolsetFactoryImpl implements VoiceAgentToolsetFactory`; `ToolCallbackVoiceAgentToolset(List<ToolCallback>)` — `definitions()` maps each callback's `ToolDefinition` (`name()`, `description()`, `inputSchema()`); `call(name, json)` finds by name, catches every `Throwable`, returns `"Tool failed: <message>"`, and returns the approval message for a callback whose unwrapped delegate is an `ApprovalGateToolCallback` (class name check by simple name — the gate lives in `ai/agent/utils`, which ai/llm must not depend on).

- [ ] **Step 1: Write the failing test**

```java
package com.bytechef.component.ai.llm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.platform.component.definition.voice.VoiceAgentToolset;
import com.bytechef.platform.component.definition.voice.VoiceToolDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolCallbackVoiceAgentToolsetTest {

    @Test
    void testDefinitionsMirrorTheCallbacks() {
        VoiceAgentToolset toolset = new ToolCallbackVoiceAgentToolset(List.of(callback("lookupOrder", "Finds an order")));

        assertThat(toolset.definitions())
            .containsExactly(new VoiceToolDefinition("lookupOrder", "Finds an order", "{\"type\":\"object\"}"));
    }

    @Test
    void testCallRoutesByNameAndReturnsTheResult() {
        VoiceAgentToolset toolset = new ToolCallbackVoiceAgentToolset(List.of(callback("lookupOrder", "Finds an order")));

        assertThat(toolset.call("lookupOrder", "{\"id\":\"4411\"}")).isEqualTo("result for {\"id\":\"4411\"}");
    }

    @Test
    void testUnknownToolAndFailingToolNeverThrow() {
        ToolCallback failing = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                    .name("boom")
                    .description("fails")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("provider down");
            }
        };

        VoiceAgentToolset toolset = new ToolCallbackVoiceAgentToolset(List.of(failing));

        assertThat(toolset.call("nope", "{}")).isEqualTo(VoiceAgentToolset.unknownTool("nope"));
        assertThat(toolset.call("boom", "{}")).isEqualTo("Tool failed: provider down");
    }

    private static ToolCallback callback(String name, String description) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                return "result for " + toolInput;
            }
        };
    }
}
```

- [ ] **Step 2: Run it to verify it fails** — `./gradlew :server:libs:modules:components:ai:llm:test --tests '*ToolCallbackVoiceAgentToolsetTest' > $SCRATCH/t15.log 2>&1; echo "exit=$?"` → `exit=1`.

- [ ] **Step 3: Implement**

`ToolCallbackVoiceAgentToolset.java`:
```java
package com.bytechef.component.ai.llm.voice;

import com.bytechef.component.ai.llm.tool.DelegatingToolCallback;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolset;
import com.bytechef.platform.component.definition.voice.VoiceToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A voice agent's tools, backed by the same policy-wrapped {@link ToolCallback}s the AI Agent runs. Approval-gated
 * tools cannot suspend a voice session (there is no job to suspend), so they answer with a spoken-friendly refusal
 * instead of reaching the gate.
 *
 * @author Ivica Cardic
 */
public class ToolCallbackVoiceAgentToolset implements VoiceAgentToolset {

    static final String APPROVAL_UNAVAILABLE =
        "This action requires approval and is not available during a voice call.";

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackVoiceAgentToolset.class);
    private static final String APPROVAL_GATE_CLASS_NAME = "ApprovalGateToolCallback";

    private final Map<String, ToolCallback> toolCallbacks = new LinkedHashMap<>();

    public ToolCallbackVoiceAgentToolset(List<ToolCallback> toolCallbacks) {
        for (ToolCallback toolCallback : toolCallbacks) {
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();

            this.toolCallbacks.put(toolDefinition.name(), toolCallback);
        }
    }

    @Override
    public List<VoiceToolDefinition> definitions() {
        return toolCallbacks.values()
            .stream()
            .map(ToolCallback::getToolDefinition)
            .map(toolDefinition -> new VoiceToolDefinition(
                toolDefinition.name(), toolDefinition.description(), toolDefinition.inputSchema()))
            .toList();
    }

    @Override
    public String call(String name, String argumentsJson) {
        ToolCallback toolCallback = toolCallbacks.get(name);

        if (toolCallback == null) {
            return unknownTool(name);
        }

        Class<?> delegateClass = DelegatingToolCallback.unwrap(toolCallback)
            .getClass();

        if (APPROVAL_GATE_CLASS_NAME.equals(delegateClass.getSimpleName())) {
            return APPROVAL_UNAVAILABLE;
        }

        try {
            return toolCallback.call(argumentsJson);
        } catch (Throwable throwable) {
            log.warn("Voice tool call failed: tool={}", name, throwable);

            return "Tool failed: " + throwable.getMessage();
        }
    }
}
```
(`DelegatingToolCallback.unwrap` is package-private today — make it `public static`.)

`VoiceAgentToolsetFactoryImpl.java`:
```java
package com.bytechef.component.ai.llm.voice;

import com.bytechef.component.ai.llm.facade.AiAgentToolFacade;
import com.bytechef.component.ai.llm.tool.ClusterElementToolCallbacks;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ai.agent.BaseToolFunction;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolset;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolsetFactory;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.configuration.domain.ClusterElementMap;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds a voice agent's toolset from its nested {@code tools} cluster elements through the same
 * {@link ClusterElementToolCallbacks} the AI Agent uses, so Component Rules, the tool execution recorder and
 * guardrail redaction apply to voice tool calls without the engine or any provider knowing about them.
 *
 * @author Ivica Cardic
 */
@Component
public class VoiceAgentToolsetFactoryImpl implements VoiceAgentToolsetFactory {

    private final ClusterElementToolCallbacks clusterElementToolCallbacks;

    public VoiceAgentToolsetFactoryImpl(
        AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
        List<ComponentRuleEnforcer> componentRuleEnforcers,
        ObjectProvider<ToolExecutionRecorder> toolExecutionRecorderObjectProvider) {

        this.clusterElementToolCallbacks = new ClusterElementToolCallbacks(
            aiAgentToolFacade, clusterElementDefinitionService, componentRuleEnforcers,
            toolExecutionRecorderObjectProvider.getIfAvailable());
    }

    @Override
    public VoiceAgentToolset create(
        Map<String, ?> extensions, Map<String, ComponentConnection> connections, ActionContext context) {

        ClusterElementMap clusterElementMap = ClusterElementMap.of(extensions);
        List<ToolCallback> toolCallbacks = new ArrayList<>();

        for (ClusterElement toolClusterElement : clusterElementMap.getClusterElements(BaseToolFunction.TOOLS)) {
            toolCallbacks.addAll(
                clusterElementToolCallbacks.build(toolClusterElement, connections, context, List.of()));
        }

        return toolCallbacks.isEmpty() ? VoiceAgentToolset.EMPTY : new ToolCallbackVoiceAgentToolset(toolCallbacks);
    }
}
```
`components/ai/llm/build.gradle.kts` already depends on `platform-component-api`, `platform-configuration-api`, `platform-tool-execution-api` — nothing to add. The bean is discovered by whatever component scan already picks up `AiAgentToolFacade` (`@Component` in the same tree).

- [ ] **Step 4: Run the test** → `exit=0`. Then `./gradlew :server:apps:server-app:compileJava > $SCRATCH/t15b.log 2>&1; echo "exit=$?"` → `exit=0`.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/tool/DelegatingToolCallback.java server/libs/modules/components/ai/llm/src/test/java/com/bytechef/component/ai/llm/voice
git commit -m "Voice - Build voice agent toolsets through ClusterElementToolCallbacks"
```

---

## Phase 4 — Providers

### Task 16: `ProviderWebSocketConnector` seam and the `voice-test-support` module

Providers open a `java.net.http.WebSocket` to their vendor. A one-method seam lets the contract test drive a fake socket instead of the network.

**Files:**
- Create: `server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice/ProviderWebSocketConnector.java`
- Create: `server/libs/test/voice-test-support/build.gradle.kts`
- Create under `server/libs/test/voice-test-support/src/main/java/com/bytechef/test/voice/`: `FakeProviderWebSocket.java`, `RecordingWebSocketEmitter.java`, `StubVoiceAgentToolset.java`, `AbstractVoiceAgentContractTest.java`
- Modify: `settings.gradle.kts` (add `include("server:libs:test:voice-test-support")` next to the other `server:libs:test` includes)

**Interfaces:**
```java
// ai/llm
@FunctionalInterface
public interface ProviderWebSocketConnector {
    WebSocket connect(URI uri, Map<String, String> headers, WebSocket.Listener listener);   // blocking
    static ProviderWebSocketConnector jdk() { ... HttpClient.newHttpClient().newWebSocketBuilder() ... .buildAsync(uri, listener).join(); }
}
// voice-test-support
public final class FakeProviderWebSocket implements WebSocket {           // records sendText/sendBinary/sendClose; exposes listener() so the test can push frames
    public List<String> sentTexts(); public List<byte[]> sentBinaries(); public boolean closed();
    public void receiveText(String text); public void receiveBinary(byte[] bytes); public void receiveClose(int code, String reason); public void receiveError(Throwable);
}
public final class RecordingWebSocketEmitter implements WebSocketHandler.WebSocketEmitter { ... records send()/sendBinary(); exposes dispatchMessage/dispatchBinaryMessage/complete to drive the provider; List<Map<String,Object>> events(); List<byte[]> audio(); boolean completed(); Throwable error(); }
public final class StubVoiceAgentToolset implements VoiceAgentToolset { public StubVoiceAgentToolset(String name, String description, String schema, String result); public List<String> calls(); }
public abstract class AbstractVoiceAgentContractTest {                    // JUnit 5, one test per contract bullet
    protected abstract VoiceAgentFunction voiceAgent(ProviderWebSocketConnector connector);
    protected abstract Map<String, Object> inputParameters();               // provider-specific minimal config
    protected abstract Map<String, Object> connectionParameters();
    protected abstract void providerGreets(FakeProviderWebSocket socket, String text);      // push the vendor frame(s) meaning "assistant said text"
    protected abstract void providerTranscribes(FakeProviderWebSocket socket, String text);
    protected abstract void providerInterrupts(FakeProviderWebSocket socket);
    protected abstract void providerSpeaks(FakeProviderWebSocket socket, byte[] pcm);
    protected abstract void providerCallsTool(FakeProviderWebSocket socket, String callId, String name, String argumentsJson);
    protected abstract String expectedToolReplyFragment(String callId, String result);       // substring the provider must send back
    protected abstract void providerFails(FakeProviderWebSocket socket);
    protected abstract String expectedToolRegistrationFragment(String toolName);            // substring in the FIRST text the provider sends (session config)
}
```

- [ ] **Step 1: `ProviderWebSocketConnector`**

```java
package com.bytechef.component.ai.llm.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;

/**
 * Opens the provider-side WebSocket of a voice agent. A seam, not an abstraction: production uses the JDK client,
 * the contract test hands the provider a fake socket it can drive frame by frame.
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface ProviderWebSocketConnector {

    WebSocket connect(URI uri, Map<String, String> headers, WebSocket.Listener listener);

    static ProviderWebSocketConnector jdk() {
        return (uri, headers, listener) -> {
            WebSocket.Builder builder = HttpClient.newHttpClient()
                .newWebSocketBuilder();

            headers.forEach(builder::header);

            return builder.buildAsync(uri, listener)
                .join();
        };
    }
}
```

- [ ] **Step 2: The module**

`server/libs/test/voice-test-support/build.gradle.kts`:
```kotlin
dependencies {
    implementation("org.assertj:assertj-core")
    implementation("org.junit.jupiter:junit-jupiter")
    implementation(project(":sdks:backend:java:component-api"))
    implementation(project(":server:libs:core:commons:commons-util"))
    implementation(project(":server:libs:modules:components:ai:llm"))
    implementation(project(":server:libs:platform:platform-component:platform-component-api"))
    implementation(project(":server:libs:test:test-support"))
}
```

`FakeProviderWebSocket.java` — implement every `WebSocket` method: `sendText` appends to `sentTexts` and returns `CompletableFuture.completedFuture(this)`; `sendBinary` copies the buffer into `sentBinaries`; `sendPing`/`sendPong` return completed; `sendClose` sets `closed = true`; `request(n)` no-op; `getSubprotocol()` → `""`; `isOutputClosed()`/`isInputClosed()` → `closed`; `abort()` sets closed. `receiveText(text)` calls `listener.onText(this, text, true)`; `receiveBinary(bytes)` → `listener.onBinary(this, ByteBuffer.wrap(bytes), true)`; `receiveClose(code, reason)` → `listener.onClose(this, code, reason)`; `receiveError(t)` → `listener.onError(this, t)`. The listener is captured by a `FakeProviderWebSocketConnector` (also in this class as a static nested `Connector implements ProviderWebSocketConnector` that records `lastUri`, `lastHeaders`, creates the fake, calls `listener.onOpen(fake)` and returns it).

`RecordingWebSocketEmitter.java` — implements the SDK `ActionDefinition.WebSocketHandler.WebSocketEmitter` interface: stores the four listeners the provider registers (`addMessageListener`, `addBinaryMessageListener`, `addCloseListener`, `addTimeoutListener`); `send(Object)` appends to `events` (`JsonUtils.read` when it is a String, else the map); `sendBinary(byte[])` appends to `audio`; adds `dispatchMessage(Object)`, `dispatchBinaryMessage(byte[])`, `complete()` (runs close listeners) for the test to drive the provider; also `error(Throwable)` if the SDK interface declares it (check the interface — mirror every abstract method).

`StubVoiceAgentToolset.java` — one tool; `call` records the `(name, argumentsJson)` pair and returns the configured result.

`AbstractVoiceAgentContractTest.java`:
```java
package com.bytechef.test.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.component.ai.llm.voice.ProviderWebSocketConnector;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition.WebSocketHandler;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.voice.VoiceAgentContext;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

/**
 * The stage contract every voice agent element honours. Providers extend it and describe their own wire frames; the
 * assertions here are what the browser, the engine and the transcript rely on.
 */
@ExtendWith(ObjectMapperSetupExtension.class)
public abstract class AbstractVoiceAgentContractTest {

    protected FakeProviderWebSocket.Connector connector;
    protected RecordingWebSocketEmitter emitter;
    protected StubVoiceAgentToolset toolset;
    protected FakeProviderWebSocket socket;

    @BeforeEach
    void connect() throws Exception {
        connector = new FakeProviderWebSocket.Connector();
        emitter = new RecordingWebSocketEmitter();
        toolset = new StubVoiceAgentToolset(
            "lookupOrder", "Finds an order", "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
            "order 4411 shipped");

        ActionContext actionContext = Mockito.mock(ActionContext.class, Mockito.RETURNS_DEEP_STUBS);

        // json(...) must really serialise: providers build their session config through it.
        Mockito.when(actionContext.json(Mockito.any()))
            .thenAnswer(invocation -> invocation.<com.bytechef.component.definition.Context.ContextFunction<
                com.bytechef.component.definition.Context.Json, Object>>getArgument(0)
                .apply(new JsonSupport()));

        WebSocketHandler handler = voiceAgent(connector).apply(
            ParametersFactory.create(inputParameters()), ParametersFactory.create(connectionParameters()),
            new VoiceAgentContext(actionContext, toolset));

        handler.handle(emitter);

        socket = connector.lastSocket();
    }

    @Test
    void testSessionConfigRegistersTheTools() {
        assertThat(socket.sentTexts()).isNotEmpty();
        assertThat(socket.sentTexts()
            .getFirst()).contains(expectedToolRegistrationFragment("lookupOrder"));
    }

    @Test
    void testGreetingBecomesAssistantText() {
        providerGreets(socket, "Hi there");

        assertThat(emitter.events()).anySatisfy(event -> {
            assertThat(event).containsEntry("type", "assistant_text");
            assertThat(event).containsEntry("text", "Hi there");
        });
    }

    @Test
    void testUserSpeechBecomesTranscriptFinal() {
        providerTranscribes(socket, "where is my order");

        assertThat(emitter.events()).anySatisfy(event -> {
            assertThat(event).containsEntry("type", "transcript_final");
            assertThat(event).containsEntry("text", "where is my order");
        });
    }

    @Test
    void testInterruptionBecomesSpeechStart() {
        providerInterrupts(socket);

        assertThat(emitter.events()).anySatisfy(event -> assertThat(event).containsEntry("type", "speech_start"));
    }

    @Test
    void testProviderAudioReachesTheEmitterAsBinary() {
        byte[] pcm = {1, 0, 2, 0, 3, 0};

        providerSpeaks(socket, pcm);

        assertThat(emitter.audio()).anySatisfy(bytes -> assertThat(bytes).containsExactly(pcm));
    }

    @Test
    void testInboundAudioIsForwardedToTheProvider() {
        emitter.dispatchBinaryMessage("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(socket.sentBinaries().size() + socket.sentTexts()
            .size()).isGreaterThan(1);
    }

    @Test
    void testToolCallRunsTheToolAndRepliesToTheProvider() throws Exception {
        providerCallsTool(socket, "call-1", "lookupOrder", "{\"id\":\"4411\"}");

        // tools run on a virtual thread; give the reply a moment
        for (int attempt = 0; attempt < 50 && toolset.calls()
            .isEmpty(); attempt++) {
            Thread.sleep(20);
        }

        assertThat(toolset.calls()).containsExactly("lookupOrder:{\"id\":\"4411\"}");
        assertThat(socket.sentTexts()).anySatisfy(
            text -> assertThat(text).contains(expectedToolReplyFragment("call-1", "order 4411 shipped")));
        assertThat(emitter.events()).anySatisfy(event -> assertThat(event).containsEntry("type", "tool_call"));
        assertThat(emitter.events()).anySatisfy(event -> {
            assertThat(event).containsEntry("type", "tool_result");
            assertThat(event).containsEntry("name", "lookupOrder");
            assertThat(event).containsEntry("result", "order 4411 shipped");
        });
    }

    @Test
    void testProviderErrorSurfacesOnceThenCompletes() {
        providerFails(socket);

        assertThat(emitter.error()).isNotNull();
        assertThat(socket.closed()).isTrue();
    }

    @Test
    void testCompletingTheEmitterClosesTheProviderSocket() {
        emitter.complete();

        assertThat(socket.closed()).isTrue();
    }

    protected abstract VoiceAgentFunction voiceAgent(ProviderWebSocketConnector connector);

    protected abstract Map<String, Object> inputParameters();

    protected abstract Map<String, Object> connectionParameters();

    protected abstract String expectedToolRegistrationFragment(String toolName);

    protected abstract void providerGreets(FakeProviderWebSocket socket, String text);

    protected abstract void providerTranscribes(FakeProviderWebSocket socket, String text);

    protected abstract void providerInterrupts(FakeProviderWebSocket socket);

    protected abstract void providerSpeaks(FakeProviderWebSocket socket, byte[] pcm);

    protected abstract void providerCallsTool(FakeProviderWebSocket socket, String callId, String name, String argumentsJson);

    protected abstract String expectedToolReplyFragment(String callId, String result);

    protected abstract void providerFails(FakeProviderWebSocket socket);
}
```
`JsonSupport` is a minimal `Context.Json` implementation over `JsonUtils` (`read`, `write`) placed in the same package — mirror whichever `Json` methods the SDK interface declares (open `sdks/backend/java/component-api/.../Context.java`, `interface Json`) and throw `UnsupportedOperationException` for the ones providers do not use. If `test-support` already has such a helper (grep `implements Context.Json` / `MockContext`), reuse it.

Note on the error test: the contract expects a provider to surface an error **once** through `emitter.error(...)` and close its socket; `RecordingWebSocketEmitter.error()` returns the first throwable. Spec §3 says "one `error` event then close with `provider_error`" — the engine's bridge turns `emitter.error` into the `error` event and the handler closes with `provider_error`; providers only call `emitter.error`.

- [ ] **Step 3: Compile the module** — `./gradlew :server:libs:test:voice-test-support:compileJava > $SCRATCH/t16.log 2>&1; echo "exit=$?"` → `exit=0`.

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply -q
git add settings.gradle.kts server/libs/test/voice-test-support server/libs/modules/components/ai/llm/src/main/java/com/bytechef/component/ai/llm/voice/ProviderWebSocketConnector.java
git commit -m "Voice - Add the provider WebSocket seam and the voice agent contract test support"
```

### Task 17: Re-home `deepgram/v1/voiceAgent` as a cluster element with tools

**Files:**
- Create: `server/libs/modules/components/deepgram/src/main/java/com/bytechef/component/deepgram/cluster/DeepgramVoiceAgent.java` (moved and reshaped from `action/DeepgramVoiceAgentAction.java`)
- Delete: `server/libs/modules/components/deepgram/src/main/java/com/bytechef/component/deepgram/action/DeepgramVoiceAgentAction.java`, `.../src/test/java/com/bytechef/component/deepgram/action/DeepgramVoiceAgentActionTest.java`
- Create: `server/libs/modules/components/deepgram/src/test/java/com/bytechef/component/deepgram/cluster/DeepgramVoiceAgentContractTest.java`, `.../cluster/DeepgramVoiceAgentTest.java` (the event-mapping unit tests moved from the deleted test + the new `FunctionCallRequest` mapping)
- Modify: `DeepgramComponentHandler.java`, `server/libs/modules/components/deepgram/build.gradle.kts` (add `implementation(project(":server:libs:modules:components:ai:llm"))`, `testImplementation(project(":server:libs:test:voice-test-support"))`)
- Regenerate: `deepgram_v1.json`

**Interfaces:**
- Produces: `DeepgramVoiceAgent.of(ProviderWebSocketConnector)` → `ClusterElementDefinition<VoiceAgentFunction>` named `voiceAgent`, `.type(VoiceAgentFunction.VOICE_AGENT)`; `DeepgramVoiceAgent.CLUSTER_ELEMENT_DEFINITION = of(ProviderWebSocketConnector.jdk())`. Properties unchanged from the action (`language`, `prompt`, `greeting`, `llmProvider`, `llmModel`, `ttsProvider`, `ttsModel`, `audioInputEncoding`, `audioInputSampleRate`, `audioOutputEncoding`, `audioOutputSampleRate`) plus `outputSampleRate` is **the existing `audioOutputSampleRate` renamed** so the engine's generic `outputSampleRate` read works (default 24000). The Deepgram component definition also becomes a nested cluster root: wrap it (`DeepgramComponentDefinitionImpl extends AbstractComponentDefinitionWrapper implements ClusterRootComponentDefinition`) with `getClusterElementTypes() == [TOOLS]` and `getClusterElementClusterElementTypes() == {"VOICE_AGENT" → ["TOOLS"]}` (exactly how the AI agent declares what its `aiAgent` tool element nests — read `AiAgentComponentDefinition` in `platform-component-api` to match the map's key convention: element **type name** or element **name**; use whatever `clusterElementsUtils.ts:297-303` reads by `currentClusterElementsType`).

- [ ] **Step 1: Write the failing contract test**

```java
package com.bytechef.component.deepgram.cluster;

import com.bytechef.component.ai.llm.voice.ProviderWebSocketConnector;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.test.voice.AbstractVoiceAgentContractTest;
import com.bytechef.test.voice.FakeProviderWebSocket;
import java.util.Map;

class DeepgramVoiceAgentContractTest extends AbstractVoiceAgentContractTest {

    @Override
    protected VoiceAgentFunction voiceAgent(ProviderWebSocketConnector connector) {
        return DeepgramVoiceAgent.of(connector)
            .getElement();
    }

    @Override
    protected Map<String, Object> inputParameters() {
        return Map.of("prompt", "Be brief", "greeting", "Hi there");
    }

    @Override
    protected Map<String, Object> connectionParameters() {
        return Map.of("token", "dg-key");
    }

    @Override
    protected String expectedToolRegistrationFragment(String toolName) {
        return "\"functions\":[{\"name\":\"" + toolName + "\"";
    }

    @Override
    protected void providerGreets(FakeProviderWebSocket socket, String text) {
        socket.receiveText("{\"type\":\"ConversationText\",\"role\":\"assistant\",\"content\":\"" + text + "\"}");
    }

    @Override
    protected void providerTranscribes(FakeProviderWebSocket socket, String text) {
        socket.receiveText("{\"type\":\"ConversationText\",\"role\":\"user\",\"content\":\"" + text + "\"}");
    }

    @Override
    protected void providerInterrupts(FakeProviderWebSocket socket) {
        socket.receiveText("{\"type\":\"UserStartedSpeaking\"}");
    }

    @Override
    protected void providerSpeaks(FakeProviderWebSocket socket, byte[] pcm) {
        socket.receiveBinary(pcm);
    }

    @Override
    protected void providerCallsTool(FakeProviderWebSocket socket, String callId, String name, String argumentsJson) {
        socket.receiveText(
            "{\"type\":\"FunctionCallRequest\",\"functions\":[{\"id\":\"" + callId + "\",\"name\":\"" + name +
                "\",\"arguments\":\"" + argumentsJson.replace("\"", "\\\"") + "\",\"client_side\":true}]}");
    }

    @Override
    protected String expectedToolReplyFragment(String callId, String result) {
        return "\"type\":\"FunctionCallResponse\",\"id\":\"" + callId + "\"";
    }

    @Override
    protected void providerFails(FakeProviderWebSocket socket) {
        socket.receiveError(new IllegalStateException("deepgram down"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails** — `./gradlew :server:libs:modules:components:deepgram:test --tests '*DeepgramVoiceAgentContractTest' > $SCRATCH/t17.log 2>&1; echo "exit=$?"` → `exit=1`.

- [ ] **Step 3: Implement `DeepgramVoiceAgent`**

Port `DeepgramVoiceAgentAction` with these changes:
- `public static ClusterElementDefinition<VoiceAgentFunction> of(ProviderWebSocketConnector connector)` returns `ComponentDsl.<VoiceAgentFunction>clusterElement("voiceAgent").title("Deepgram Voice Agent").description(...).type(VoiceAgentFunction.VOICE_AGENT).properties(<same list, AUDIO_OUTPUT_SAMPLE_RATE renamed to "outputSampleRate", type integer default 24000, and AUDIO_INPUT_SAMPLE_RATE as integer default 16000>).object(() -> (inputParameters, connectionParameters, context) -> perform(inputParameters, connectionParameters, context, connector))`.
- `perform` builds the settings message with `buildSettingsMessage(inputParameters, context.actionContext(), context.toolset().definitions())`; when the list is non-empty add `think.functions = [{name, description, parameters: <inputSchema parsed to a map via context.actionContext().json(json -> json.read(schema, Map.class))>}]`.
- Open the socket with `connector.connect(URI.create("wss://agent.deepgram.com/v1/agent/converse"), Map.of("Authorization", "Token " + apiKey), listener)`.
- `DeepgramAgentListener.onText`: after `toBrowserVoiceEvent`, additionally when the parsed message `type` is `FunctionCallRequest`, for each entry of `functions` with `client_side == true`, run on `Thread.startVirtualThread`:
```java
String result = toolset.call(name, arguments);              // arguments is a JSON string per Deepgram
webSocketEmitter.send(Map.of("type", "tool_call", "name", name, "arguments", parsedArguments));
webSocketEmitter.send(Map.of("type", "tool_result", "name", name, "ok", true, "arguments", parsedArguments, "result", result));
deepgramWebSocket.sendText(json.write(Map.of("type", "FunctionCallResponse", "id", id, "name", name, "content", result)), true);
```
- `onError`: `webSocketEmitter.error(error)` once, then `closeDeepgramConnection`.
- Remove the legacy Twilio `media`/`stop` text-message handling in `handleIncomingMessage` (the browser never sends text audio frames); keep `addMessageListener` only for a `{"type":"control","action":"end"}` message → close.

`DeepgramComponentHandler`: `.clusterElements(DeepgramVoiceAgent.CLUSTER_ELEMENT_DEFINITION)`, no `.actions(...)`, wrapped as a cluster root per the Interfaces note.

- [ ] **Step 4: Unit tests** — move the three `toVoiceEvent` cases from the deleted `DeepgramVoiceAgentActionTest` into `DeepgramVoiceAgentTest` and add `testFunctionCallRequestIsMappedToToolCall` asserting `DeepgramVoiceAgent.toVoiceEvent(Map.of("type","FunctionCallRequest",...))` returns `null` (it is handled by the tool path, not the browser event path).

- [ ] **Step 5: Run the module tests + snapshot** — two-run dance for `:server:libs:modules:components:deepgram:test`; expected green; `grep -c '"voiceAgent"' deepgram_v1.json` ≥ 1 and `grep -c '"actions"' ` shows an empty list.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply -q
git add -A server/libs/modules/components/deepgram
git commit -m "Voice - Re-home the Deepgram voice agent as a cluster element with tool calling"
```

### Task 18: `openai/v1/voiceAgent` (Realtime API)

**Files:**
- Create: `server/libs/modules/components/ai/llm/open-ai/src/main/java/com/bytechef/component/ai/llm/openai/cluster/OpenAiVoiceAgent.java`
- Create: `.../open-ai/src/test/java/com/bytechef/component/ai/llm/openai/cluster/OpenAiVoiceAgentContractTest.java`, `OpenAiVoiceAgentTest.java`
- Modify: `OpenAiComponentHandler.java` (add `OpenAiVoiceAgent.CLUSTER_ELEMENT_DEFINITION` to `.clusterElements(...)`, wrap as a nested cluster root for TOOLS like Task 17), `open-ai/build.gradle.kts` (`testImplementation(project(":server:libs:test:voice-test-support"))`)
- Regenerate: `open-ai_v1.json`

- [ ] **Step 0: Verify the protocol against the live docs before writing a line** — open https://platform.openai.com/docs/guides/realtime and the Realtime API reference; confirm: the WS URL and `model` query parameter; the `session.update` shape for the GA API (`session.type`, `instructions`, `audio.input.format`, `audio.output.format`/`voice`, `audio.input.transcription`, `audio.input.turn_detection`, `tools[]` with `type: "function"`); the event names `input_audio_buffer.speech_started`, `conversation.item.input_audio_transcription.completed`, `response.output_audio.delta`, `response.output_audio_transcript.done`, `response.function_call_arguments.done`, `response.done`, `error`; the client events `input_audio_buffer.append` (base64), `conversation.item.create` with `function_call_output`, `response.create`. Record any difference from the spec's §3 table in this task's commit message and in the spec (one line under §3 "Provider protocol verification").

- [ ] **Step 1: Write the failing contract test** (same shape as Task 17; the frames):
  - registration fragment: `"tools":[{"type":"function","name":"lookupOrder"`
  - greets: `{"type":"response.output_audio_transcript.done","transcript":"Hi there"}`
  - transcribes: `{"type":"conversation.item.input_audio_transcription.completed","transcript":"where is my order"}`
  - interrupts: `{"type":"input_audio_buffer.speech_started"}`
  - speaks: `{"type":"response.output_audio.delta","delta":"<base64 of pcm>"}`
  - calls tool: `{"type":"response.function_call_arguments.done","call_id":"call-1","name":"lookupOrder","arguments":"{\"id\":\"4411\"}"}`
  - reply fragment: `"type":"function_call_output","call_id":"call-1"`
  - fails: `socket.receiveText("{\"type\":\"error\",\"error\":{\"message\":\"boom\"}}")`
  - input parameters: `Map.of("instructions", "Be brief", "greeting", "Hi there")`; connection: `Map.of("token", "sk-test")`.

- [ ] **Step 2: Implement `OpenAiVoiceAgent`** — properties: `model` (string, default `gpt-realtime`, options from the docs), `instructions` (text area), `voice` (default `alloy`), `greeting`, `inputAudioTranscription` (bool, default true), `inputSampleRate` (integer, default 16000 — the trigger's rate), `outputSampleRate` (integer, fixed default 24000, description says OpenAI emits 24 kHz). Flow: connect to `wss://api.openai.com/v1/realtime?model=<model>` with `Authorization: Bearer <token>`; on open send `session.update` (tools from `toolset.definitions()`, schema strings parsed to maps); if `greeting` set send `response.create` with `instructions: "Greet the user by saying exactly: <greeting>"`; inbound binary → `Pcm16Resampler.resample(bytes, inputSampleRate, 24000)` → base64 → `input_audio_buffer.append`; map server events as listed; `response.function_call_arguments.done` → virtual thread → `toolset.call` → emit `tool_call`/`tool_result` → send `conversation.item.create{item:{type:function_call_output, call_id, output}}` then `response.create`; `error` event → `emitter.error(new IllegalStateException(message))` + close.

- [ ] **Step 3: Run tests, snapshot, commit**

```bash
./gradlew :server:libs:modules:components:ai:llm:open-ai:test > $SCRATCH/t18.log 2>&1; echo "exit=$?"   # two-run dance for the snapshot
./gradlew spotlessApply -q
git add -A server/libs/modules/components/ai/llm/open-ai
git commit -m "Voice - Add the OpenAI Realtime voice agent cluster element"
```

### Task 19: `elevenLabs/v1/voiceAgent` (Conversational AI)

**Files:**
- Create: `server/libs/modules/components/elevenlabs/src/main/java/com/bytechef/component/elevenlabs/cluster/ElevenLabsVoiceAgent.java`
- Create: `.../elevenlabs/src/test/java/com/bytechef/component/elevenlabs/cluster/ElevenLabsVoiceAgentContractTest.java`, `ElevenLabsVoiceAgentTest.java`
- Modify: `ElevenLabsComponentHandler.java` (add to `.clusterElements(...)`, wrap as nested cluster root for TOOLS), `elevenlabs/build.gradle.kts` (add `implementation(project(":server:libs:modules:components:ai:llm"))`, `testImplementation(project(":server:libs:test:voice-test-support"))`)
- Regenerate: `elevenlabs_v1.json`

- [ ] **Step 0: Verify the protocol against https://elevenlabs.io/docs/conversational-ai/libraries/web-sockets (or its current location)** — signed-URL endpoint (`GET /v1/convai/conversation/get-signed-url?agent_id=…` with `xi-api-key`), `conversation_initiation_client_data` override keys, client events (`user_audio_chunk`, `pong`, `client_tool_result`), server events (`conversation_initiation_metadata` with the output format, `user_transcript`, `agent_response`, `audio`, `interruption`, `ping`, `client_tool_call`), the agents list endpoint for dynamic options. Record differences as in Task 18.

- [ ] **Step 1: Write the failing contract test** — frames:
  - registration fragment: ElevenLabs cannot receive tool schemas; assert instead that the first text sent is the `conversation_initiation_client_data` frame (`"type":"conversation_initiation_client_data"`) — override `testSessionConfigRegistersTheTools` in this subclass to assert exactly that, with a comment explaining the name-matching model.
  - greets: `{"type":"agent_response","agent_response_event":{"agent_response":"Hi there"}}`
  - transcribes: `{"type":"user_transcript","user_transcription_event":{"user_transcript":"where is my order"}}`
  - interrupts: `{"type":"interruption","interruption_event":{"event_id":1}}`
  - speaks: `{"type":"audio","audio_event":{"audio_base_64":"<base64>","event_id":1}}`
  - calls tool: `{"type":"client_tool_call","client_tool_call":{"tool_name":"lookupOrder","tool_call_id":"call-1","parameters":{"id":"4411"}}}`
  - reply fragment: `"type":"client_tool_result","tool_call_id":"call-1"`
  - fails: `socket.receiveError(...)`
  - input parameters: `Map.of("agentId", "agent-1")`; connection: `Map.of("key", "xi-api-key", "value", "el-key")`.
  - The signed-URL fetch happens before the WS connect: the element takes an `ElevenLabsSignedUrlResolver` seam alongside the connector (`of(connector, (agentId, apiKey) -> "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=" + agentId)` in the test; production uses `context.actionContext().http(...)` to GET the signed URL).

- [ ] **Step 2: Implement** — properties: `agentId` (required, dynamic options via `GET /v1/convai/agents` listing `agents[].name`/`agent_id`), `prompt`, `firstMessage`, `language`, `outputSampleRate` (default 16000). On open send `conversation_initiation_client_data` with the overrides present; `ping` → `pong` with the same `event_id`; inbound binary → base64 → `{"user_audio_chunk": "..."}`; `client_tool_call` → virtual thread → `toolset.call(tool_name, json(parameters))` → emit `tool_call`/`tool_result` → `{"type":"client_tool_result","tool_call_id":…,"result":…,"is_error":false}`; unknown tool → `is_error: true` with the `unknownTool` message. On start, after connecting, `GET /v1/convai/agents/{agentId}` and log a WARN for every `toolset.definitions()` name not present in the agent's client tools (best-effort; any failure of that GET is logged at DEBUG and ignored).

- [ ] **Step 3: Run tests, snapshot, commit**

```bash
./gradlew :server:libs:modules:components:elevenlabs:test > $SCRATCH/t19.log 2>&1; echo "exit=$?"   # two-run dance for the snapshot
./gradlew spotlessApply -q
git add -A server/libs/modules/components/elevenlabs
git commit -m "Voice - Add the ElevenLabs Conversational AI voice agent cluster element"
```

### Task 20: `openai`/`elevenLabs`/`deepgram` cluster elements are discoverable in the editor picker

**Files:**
- Read: `client/src/pages/platform/workflow-editor/components/WorkflowNodesPopoverMenuComponentList.tsx` and the query it uses for cluster element components (`useGetComponentDefinitionsQuery` with `clusterElementType`), and the server side `ComponentDefinitionServiceImpl`/`ComponentDefinitionFacade` filter by cluster element type
- Create test: `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ComponentDefinitionServiceVoiceAgentFilterTest.java`

- [ ] **Step 1: Write a test** that registers the three provider handlers' definitions in a `ComponentDefinitionRegistry` (see how `ComponentDefinitionServiceTest` builds one) and asserts `componentDefinitionService.getComponentDefinitions(null, null, null, null, /*clusterElementType*/ "VOICE_AGENT")` (use the real signature) returns exactly `deepgram`, `elevenLabs`, `openAi`.
- [ ] **Step 2: Run** — expected to pass with no production change (the filter is by declared element type). If it fails because the filter only looks at `clusterElementTypes` of cluster **roots**, fix the filter to match components that **provide** an element of the type.
- [ ] **Step 3: Commit** `git commit -m "Voice - Prove VOICE_AGENT providers are listed for the trigger's slot"`.

### Task 21: Delete the action-perform WebSocket path

Now no action returns a `WebSocketHandler`.

**Files:**
- Delete: `server/libs/platform/platform-component/platform-component-api/src/main/java/com/bytechef/platform/component/definition/MultipleConnectionsWebSocketPerformFunction.java`, `server/libs/platform/platform-component/platform-component-service/src/test/java/com/bytechef/platform/component/service/ActionDefinitionServiceWebSocketPerformTest.java`
- Modify: `ActionDefinitionServiceImpl.java` (remove the two `instanceof ...WebSocketPerformFunction` branches at ~300 and ~321, `executeWebSocketPerform`, `executeMultipleConnectionsWebSocketPerform`, imports), `sdks/backend/java/component-api/src/main/java/com/bytechef/component/definition/ActionDefinition.java` (remove `interface WebSocketPerformFunction`; keep `WebSocketHandler` + `WebSocketEmitter`), `ComponentDsl.java:726` (remove `perform(WebSocketPerformFunction)`), any `ActionDefinition` wrapper in `platform-component-api` that switches on it (`grep -rn WebSocketPerformFunction server sdks --include='*.java'`)

- [ ] **Step 1: Delete/edit, then** `./gradlew compileJava compileTestJava --continue > $SCRATCH/t21.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' $SCRATCH/t21.log; grep -rn 'WebSocketPerformFunction' server sdks --include='*.java' | grep -v build` → `exit=0`, no hits.
- [ ] **Step 2: Commit** `git commit -m "Voice - Remove the action WebSocket perform path; only Voice Agent elements stream"`.

---

## Phase 5 — Client

### Task 22: `BrowserVoiceSession` protocol: `sessionId`, `outputSampleRate`, `session_end`, reconnect

**Files:**
- Modify: `client/src/shared/lib/browser-voice/BrowserVoiceSession.ts`
- Modify: `client/src/shared/lib/browser-voice/BrowserVoiceSession.test.ts`

**Interfaces (produced):**
```ts
export type VoiceEventType =
    | 'connected' | 'transcript_interim' | 'transcript_final' | 'assistant_text' | 'speech_start'
    | 'tool_call' | 'tool_result' | 'session_end' | 'error';
export type VoiceSessionStatusType = 'idle' | 'connecting' | 'active' | 'reconnecting' | 'ending' | 'closed' | 'error';
export interface BrowserVoiceSessionOptionsI {
    url: string;                       // WS URL WITHOUT sessionToken; the session appends it
    mintToken: () => Promise<string>;  // called on start and on every reconnect attempt
    sampleRate?: number; workletPath?: string;
    maxReconnectAttempts?: number;     // default 3; 0 disables
    onEvent?; onStatusChange?; onSpeakingChange?; onVolume?;
}
```
Behaviour: `start()` mints a token, opens `${url}?sessionToken=…&sampleRate=…`; the `connected` frame (`{event:'connected', sessionId, outputSampleRate, resumed?}`) stores `sessionId` and `outputSampleRate` (playback buffers use it); an `onclose` while status is `active`/`reconnecting` that is not `ending` and whose reason is not a terminal `session_end` reason → status `reconnecting`, then attempts with delays 1000/2000/4000 ms, each minting a fresh token and adding `&resumeSessionId=<sessionId>`; after the last failure → `error` with `message: 'Connection lost'`. A `session_end` event or a close reason in `['session_limit','silence_timeout','provider_error','server_shutdown','client_closed']` → emit `session_end {reason}` (synthesised from the close reason when the server only closed) and status `closed`. Audio buffered during a gap is dropped.

- [ ] **Step 1: Write the failing tests** (append to the existing describe; the existing `FakeWebSocket` gets a `onclose` event shape `{code?: number; reason?: string}` and a `fail(reason?)` helper that sets `readyState = CLOSED` and calls `onclose`):

```ts
    it('adopts sessionId and outputSampleRate from the connected frame', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        expect(ws.url).toBe('ws://host/webhooks/1/wss?sessionToken=tok-1&sampleRate=16000');

        ws.open();
        ws.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});

        expect(session.getSessionId()).toBe('s-1');
        expect(session.getOutputSampleRate()).toBe(24000);
    });

    it('reconnects with a fresh token and the previous sessionId after an unexpected close', async () => {
        vi.useFakeTimers();

        const tokens = ['tok-1', 'tok-2'];
        const statuses: VoiceSessionStatusType[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => tokens.shift()!,
            onStatusChange: (status) => statuses.push(status),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const first = FakeWebSocket.lastInstance!;

        first.open();
        first.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});
        first.fail();

        expect(statuses).toContain('reconnecting');

        await vi.advanceTimersByTimeAsync(1000);

        const second = FakeWebSocket.lastInstance!;

        expect(second).not.toBe(first);
        expect(second.url).toBe('ws://host/webhooks/1/wss?sessionToken=tok-2&sampleRate=16000&resumeSessionId=s-1');

        second.open();

        expect(session.getStatus()).toBe('active');

        vi.useRealTimers();
    });

    it('treats a server end reason as a terminal session_end instead of reconnecting', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.fail('silence_timeout');

        expect(events).toContainEqual({reason: 'silence_timeout', type: 'session_end'});
        expect(session.getStatus()).toBe('closed');
    });

    it('gives up after the configured attempts', async () => {
        vi.useFakeTimers();

        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            maxReconnectAttempts: 1,
            mintToken: async () => 'tok',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        FakeWebSocket.lastInstance!.open();
        FakeWebSocket.lastInstance!.fail();

        await vi.advanceTimersByTimeAsync(1000);

        FakeWebSocket.lastInstance!.fail();

        expect(session.getStatus()).toBe('error');
        expect(events.at(-1)).toEqual({message: 'Connection lost', type: 'error'});

        vi.useRealTimers();
    });
```
`startWithoutAudio(session)` stubs `navigator.mediaDevices.getUserMedia`, `AudioContext` (with `audioWorklet.addModule`, `createMediaStreamSource`, `createBuffer`, `createBufferSource`, `destination`, `currentTime`, `close`) and `AudioWorkletNode` on `globalThis` with `vi.fn()` shapes, then `await session.start()`. Put it in the test file above the describe.

- [ ] **Step 2: Run** — `cd client && npx vitest run src/shared/lib/browser-voice/BrowserVoiceSession.test.ts > $SCRATCH/t22.log 2>&1; echo "exit=$?"` → `exit=1`.

- [ ] **Step 3: Implement** — in `BrowserVoiceSession`:
  - new fields `sessionId: string | null`, `outputSampleRate: number`, `reconnectAttempt = 0`, `readonly maxReconnectAttempts`, `readonly mintToken`; getters `getSessionId()`, `getOutputSampleRate()`.
  - split `start()` into `start()` (mic + audio context + first `openSocket()`) and `private async openSocket(resume: boolean)` that mints a token, builds the URL (`&resumeSessionId=` when `resume && this.sessionId`), constructs the `WebSocket` and wires handlers; `onopen` → `reconnectAttempt = 0`, status `active`.
  - `dispatchTextEvent`: handle `parsed.event === 'connected'` (store `sessionId`, `outputSampleRate`, emit as `{type:'connected', ...}`), `parsed.type === 'session_end'` → `finish(parsed.reason)`.
  - `onclose(event)`: if `status === 'ending'` → cleanup as today. Else if `TERMINAL_REASONS.has(event.reason)` → `this.onEvent?.({reason: event.reason, type: 'session_end'}); this.setStatus('closed'); this.cleanup()`. Else if `this.lastServerError` → error path as today. Else if `reconnectAttempt < maxReconnectAttempts && this.sessionId` → `setStatus('reconnecting')`, `setTimeout(() => void this.openSocket(true), 1000 * 2 ** reconnectAttempt++)` (keep mic/audio context alive — only `this.ws = null`). Else → `onEvent({message:'Connection lost', type:'error'})`, status `error`, cleanup.
  - `enqueuePlayback` uses `this.outputSampleRate` (initialised to `sampleRate` until `connected` arrives).
  - `TERMINAL_REASONS = new Set(['client_closed','session_limit','silence_timeout','provider_error','server_shutdown'])`.
  - Update the class Javadoc's "Used by" list (drop AI Hub, which no longer uses it).

- [ ] **Step 4: Run** → `exit=0`; then `cd client && npm run lint -- src/shared/lib/browser-voice` (sort-keys, naming).

- [ ] **Step 5: Commit**

```bash
cd client && npm run format
git add client/src/shared/lib/browser-voice
git commit -m "Voice client - Adopt sessionId and outputSampleRate, add session_end and reconnect to BrowserVoiceSession"
```

### Task 23: Callers of `BrowserVoiceSession` in the client

**Files:**
- Modify: `client/src/shared/lib/voice/ByteChefRealtimeVoiceAdapter.ts` (mint through `mintToken` instead of pre-fetching; pass `url` without the token; map `reconnecting` → `helpers.setStatus({type:'running'})` stays running with `helpers.emitMode('listening')`; map `session_end` → `helpers.end('finished')`; `tool_call` → `helpers.emitTranscript({isFinal: true, role: 'assistant', text: `Looking up ${event.name}…`})` — only if assistant-ui has no dedicated status channel; otherwise a no-op here and the layout shows it (Task 24))
- Modify: `client/src/shared/hooks/useWorkflowTestVoiceSession.ts` (same `mintToken` change; add `environmentId` from `useEnvironmentStore` — find the store used by `WorkflowTestChatPanel`'s queries for `currentEnvironmentId` — to the WS URL as `&environmentId=`)
- Modify tests: `ByteChefRealtimeVoiceAdapter.test.ts` (token minted lazily; `resumeSessionId` not present on first connect), `useWorkflowTestVoiceSession` has no test today — add `useWorkflowTestVoiceSession.test.ts` asserting the built URL contains `environmentId=` and no token (use `renderHook`, mock `WorkflowTestApi.issueWorkflowTestVoiceSessionToken`)

- [ ] **Step 1: Write the failing tests** — for the adapter, replace the existing URL assertion with:
```ts
    it('opens the socket without the token in the base url and mints lazily', async () => {
        ...
        expect(BrowserVoiceSessionMock).toHaveBeenCalledWith(
            expect.objectContaining({mintToken: expect.any(Function), url: 'ws://host/webhooks/abc/wss'})
        );
    });
```
(mock `BrowserVoiceSession` with `vi.mock('@/shared/lib/browser-voice/BrowserVoiceSession', ...)` using `vi.hoisted` for the mock reference per CLAUDE.md).

- [ ] **Step 2: Implement, run `npx vitest run src/shared/lib/voice src/shared/hooks/useWorkflowTestVoiceSession.test.ts`, commit**

```bash
cd client && npm run format
git add client/src/shared/lib/voice client/src/shared/hooks/useWorkflowTestVoiceSession.ts client/src/shared/hooks/useWorkflowTestVoiceSession.test.ts
git commit -m "Voice client - Mint voice tokens lazily and pass the environment to the test session"
```

### Task 24: `VoiceModeLayout` shows tool activity, reconnecting and the end reason

**Files:**
- Modify: `client/src/shared/lib/voice/VoiceModeLayout.tsx`, `client/src/shared/lib/voice/VoiceModeLayout.test.tsx`
- Modify: `client/src/shared/lib/voice/ByteChefRealtimeVoiceAdapter.ts` — expose the last non-transcript event through a tiny store `client/src/shared/lib/voice/useVoiceActivityStore.ts` (Zustand: `{activity: 'idle' | 'reconnecting' | {tool: string}, endReason: string | null, setActivity, setEndReason, reset}`) that the adapter writes and the layout reads

**Interfaces:** `VoiceModeLayoutPropsI` unchanged; the layout renders a status line under the orb: `Looking that up… (lookupOrder)` while `activity.tool` is set, `Reconnecting…` while `'reconnecting'`, and after the session ends `Ended: silence timeout` (reason with `_` → space) for 5 s.

- [ ] **Step 1: Write the failing tests** (`VoiceModeLayout.test.tsx`, mock `@assistant-ui/react` hooks as the existing test does):
```ts
    it('shows the tool being looked up', () => {
        useVoiceActivityStore.setState({activity: {tool: 'lookupOrder'}, endReason: null});

        render(<VoiceModeLayout sessionLimitSeconds={0} />);

        expect(screen.getByText(/Looking that up/)).toBeInTheDocument();
        expect(screen.getByText(/lookupOrder/)).toBeInTheDocument();
    });

    it('shows the end reason when the session ended', () => {
        useVoiceActivityStore.setState({activity: 'idle', endReason: 'silence_timeout'});

        render(<VoiceModeLayout sessionLimitSeconds={0} />);

        expect(screen.getByText('Ended: silence timeout')).toBeInTheDocument();
    });
```
- [ ] **Step 2: Implement** the store (export `voiceActivityStore` for tests, reset in `beforeEach`), write it from the adapter's `onEvent` (`tool_call` → `{tool: name}`, `tool_result` → `'idle'`, `session_end` → `setEndReason(reason)`) and `onStatusChange` (`reconnecting` → `'reconnecting'`, `active` → `'idle'`), read it in the layout with a selector (never a bare store call — CLAUDE.md Zustand rule).
- [ ] **Step 3: Run the tests, commit** `git commit -m "Voice client - Surface tool activity, reconnecting and the end reason in VoiceModeLayout"`.

### Task 25: Workflow editor — the browser trigger as a cluster root

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/utils/saveWorkflowDefinition.ts:94-124` (trigger branch carries `clusterElements`)
- Create: `client/src/pages/platform/workflow-editor/utils/upsertTrigger.test.ts` if absent, or extend `saveWorkflowDefinition.test.ts` (check which exists: `ls client/src/pages/platform/workflow-editor/utils/*.test.ts | grep -i 'trigger\|saveWorkflow'`)
- Modify: `client/src/pages/platform/workflow-editor/components/hooks/useWorkflowNodeDetailsPanel.ts` — the cluster-element effect at :1441-1480 reads `JSON.parse(workflow.definition).tasks` only; when `currentNode.trigger` (or the cluster root id names a trigger) read `.triggers` instead: `const definition = JSON.parse(workflow.definition); const rootTask = getTask({tasks: definition.tasks, workflowNodeName: clusterRootId}) ?? (definition.triggers ?? []).find((trigger) => trigger.name === clusterRootId);`
- Modify: `client/src/pages/platform/workflow-editor/components/hooks/useWorkflowNodeDetailsPanel.ts:565-580` and `:655-665` — the connection derivations already fall back to `currentWorkflowTrigger?.connections`; verify `mainClusterRootTask` is looked up among triggers too (grep `mainClusterRootTask =` and extend the lookup with `workflow.triggers?.find(...)`)
- Modify: `client/src/pages/platform/workflow-editor/utils/saveClusterElementFieldChange.ts`, `findAndRemoveClusterElement.ts`, `saveClusterElementNodesPosition.ts`, `clusterElementsFieldChangeUtils.ts`, `resolveClusterRootId.ts` — each locates the root through `tasks`; add the same `?? triggers.find(...)` fallback. Grep first: `grep -ln 'workflowDefinitionTasks\|definition.tasks\|\.tasks' client/src/pages/platform/workflow-editor/utils/*Cluster*.ts client/src/pages/platform/workflow-editor/utils/clusterFrame/*.ts`
- Modify: `client/src/pages/platform/workflow-editor/nodes/ClusterRootNode.tsx` / `WorkflowNode.tsx` — if the cluster root node renders task-only affordances (delete task, "add task below" handle) guard them with `!data.trigger`; the trigger's own affordances (change trigger) stay

**Interfaces:** workflow JSON `triggers[i].clusterElements` round-trips through save; the details panel opens the Voice Agent slot for a `browser/v1/voiceSession` trigger; picking `deepgram/v1/voiceAgent` writes `triggers[0].clusterElements.voiceAgent`.

- [ ] **Step 1: Write the failing save test**

```ts
it('keeps clusterElements on a trigger through save', async () => {
    const updateWorkflowMutation = {mutateAsync: vi.fn().mockResolvedValue({})};
    // …minimal workflow with definition '{"triggers":[{"name":"trigger_1","type":"browser/v1/voiceSession","parameters":{}}],"tasks":[]}'
    await saveWorkflowDefinition({
        nodeData: {
            clusterElements: {voiceAgent: {name: 'voiceAgent_1', parameters: {}, type: 'deepgram/v1/voiceAgent'}},
            componentName: 'browser',
            name: 'trigger_1',
            operationName: 'voiceSession',
            parameters: {},
            trigger: true,
            version: 1,
            workflowNodeName: 'trigger_1',
        },
        updateWorkflowMutation,
    });

    const saved = JSON.parse(updateWorkflowMutation.mutateAsync.mock.calls[0][0].workflow.definition);

    expect(saved.triggers[0].clusterElements.voiceAgent.type).toBe('deepgram/v1/voiceAgent');
});
```
Follow the existing `saveWorkflowDefinition.test.ts` setup for the stores it needs (`useWorkflowDataStore.setState({workflow: …})`).

- [ ] **Step 2: Implement** — in the trigger branch of `saveWorkflowDefinition`:
```ts
        const newTrigger: WorkflowTrigger = {
            ...(clusterElements ? {clusterElements} : {}),
            description,
            label,
            metadata,
            name: name!,
            parameters,
            type,
        };
```
and make `upsertTrigger` merge: when the existing trigger has `clusterElements` and the new one does not, keep the existing (`{...existing, ...newTrigger, clusterElements: newTrigger.clusterElements ?? existing.clusterElements}`). Then apply the `triggers` fallbacks listed under Files, driving each by a failing test where a test file exists for the util (extend `resolveClusterRootId.test.ts`-style tests with a trigger fixture).

- [ ] **Step 3: Manual check in the running app** (after Task 27's server is up): create a workflow, add trigger Browser → Browser Voice Session, confirm the cluster frame with a **Voice Agent** slot appears, pick Deepgram Voice Agent, fill prompt + connection, add a Tool inside it, save, reload — everything persists. Record what you saw in the commit message body? No — subject-only; put it in the PR description later.

- [ ] **Step 4: `cd client && npm run check` → exit 0; commit** `git commit -m "Voice client - Render the browser voice trigger as a cluster root with a Voice Agent slot"`.

### Task 26: Test panel voice gating

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/components/workflow-test-chat/WorkflowTestChatPanel.tsx:66-101`
- Modify: `client/src/pages/platform/workflow-editor/components/workflow-test-chat/WorkflowTestChatPanel.test.tsx`

- [ ] **Step 1: Failing tests** — `it('offers voice when the browser trigger has a Voice Agent')` (store a workflow whose `triggers[0]` is `browser/v1/voiceSession` with `clusterElements.voiceAgent`; expect the voice layout / mic affordance to render) and `it('asks for a Voice Agent when the slot is empty')` (same trigger without `clusterElements`; expect the text `Add a Voice Agent to the trigger to test with voice`).
- [ ] **Step 2: Implement** — replace `workflowSupportsVoice` with:
```ts
    const voiceTrigger = useMemo(
        () => (workflow?.triggers ?? []).find((trigger) => trigger?.type === 'browser/v1/voiceSession'),
        [workflow?.triggers]
    );
    const hasVoiceAgent = !!(voiceTrigger?.clusterElements as {voiceAgent?: unknown} | undefined)?.voiceAgent;
    const isVoiceOnlyWorkflow = !!voiceTrigger;
```
render the hint when `isVoiceOnlyWorkflow && !hasVoiceAgent`; only build `voiceAdapter` when `hasVoiceAgent`. Pass `sampleRate` from `voiceTrigger.parameters.sampleRate` to `createWebhookVoiceAdapter`.
- [ ] **Step 3: Run the panel tests, commit** `git commit -m "Voice client - Gate the test panel's voice mode on a configured Voice Agent"`.

### Task 27: SDK widget copy

**Files:**
- Modify: `sdks/frontend/automation/chat/library/src/lib/BrowserVoiceSession.ts` (apply Task 22's changes byte-for-byte except the inlined worklet block and the header comment), `.../lib/ByteChefRealtimeVoiceAdapter.ts`, `.../lib/VoiceModeLayout.tsx` (Task 24's status line, with a local `useState`-based activity instead of the platform store), `.../hooks/useAutomationChatVoiceSession.ts` (`mintToken`), `.../README.md` (voice section: the example workflow is now the cluster-element JSON; mention reconnect and `session_end`)
- Modify tests: `.../lib/BrowserVoiceSession.test.ts` (add the same four tests as Task 22 — the file currently only checks the worklet identifiers; add a second `describe` with the `FakeWebSocket`), `.../hooks/useAutomationChatVoiceSession.test.ts` (URL without token)

- [ ] **Step 1: Port, run `cd sdks/frontend/automation/chat/library && npm run lint && npx vitest run && npm run build`** → all exit 0.
- [ ] **Step 2: Commit** `git commit -m "Voice client - Port the session protocol, reconnect and status line to the chat widget"`.

---

## Phase 6 — Docs and wrap-up

### Task 28: Docs and `.agents/voice.md`

**Files:**
- Rewrite: `docs/voice/quickstart.md`, `docs/voice/editor-testing.md`
- Rewrite: `docs/content/docs/platform/automation/build/voice/quickstart.mdx`, `.../editor-testing.mdx` (keep frontmatter `ee: true`, `comingSoon: true`)
- Rewrite: `docs/examples/voice/deepgram-voiceagent.json`; create `docs/examples/voice/openai-voiceagent.json`
- Create: `.agents/voice.md`
- Modify: `CLAUDE.md` deep-dive table — add the row `| `.agents/voice.md` | Browser voice: engine outside Atlas, Voice Agent cluster element, stage contract, tool policy path, no HITL in a call, `sessionId` protocol, node-affinity gap |` after the `component-rules.md` row
- Modify: `.agents/coming-soon-inventory.md` "Voice (2026-08-21)" paragraph — replace the list of hidden surfaces with the new ones (`browser/v1/voiceSession` trigger + its Voice Agent slot, `deepgram|openAi|elevenLabs voiceAgent` elements, `BrowserVoiceSession`/`voiceMode`); drop `realtimeChat`; then `cd docs && npm run coming-soon` must pass
- Modify: `docs/content/docs/platform/automation/build/workflows/ai/agent/index.mdx:387-396` — delete the commented-out "Realtime Chat" block entirely (the action no longer exists, so it is not coming soon)
- Modify: `sdks/frontend/automation/chat/library/README.md` (done in Task 27)

- [ ] **Step 1: `quickstart.md`** — sections: What you'll build (diagram: mic → ByteChef WS → Voice Agent element → provider → speaker; post-call workflow); Prerequisites; Step 1 connect a provider (table: Deepgram / OpenAI / ElevenLabs — where the agent is configured, tool model, output rate, phone support: none here, vendor-side for ElevenLabs/OpenAI); Step 2 add the trigger and pick a Voice Agent (screenshot placeholders are NOT allowed — describe the UI in words: "the trigger renders as a box with a *Voice Agent* slot; click it and pick…"); Step 3 add Tools (optional; note approval-gated tools answer with a refusal in a call); Step 4 test from the editor; Step 5 deploy and use the trigger output (`transcript`, `toolCalls`, `endReason`) in the post-session tasks with a two-task example (Summarize → Slack); Step 6 widget; Limits (session limit, silence timeout, reconnect, no phone calls — link to the spec's Non-goals). `editor-testing.md`: update the "When the mic button appears" rule (trigger + configured Voice Agent), the WS path with `environmentId`, remove "Multi-provider chains" links, fix the sample-rate troubleshooting (now automatic via `outputSampleRate`).
- [ ] **Step 2: Example JSONs** — the Section 1 JSON of the spec, with `_comment` rewritten (no `websocketTasks` wording).
- [ ] **Step 3: `.agents/voice.md`** — ~80 lines: the engine-outside-Atlas invariant (why, and that `createContinuationJob` is the only Atlas entry), the element contract (`VoiceAgentFunction`, `VoiceAgentContext`, stage events incl. `tool_result` carrying `arguments`/`result` for the transcript), the tool policy path (`VoiceAgentToolsetFactoryImpl` → `ClusterElementToolCallbacks`; no HITL in a call and why), connection resolution (pre-send processors for deployed, test configuration for editor; the `environmentId` query param), the `sessionId` protocol + resume window, silence timeout, the node-affinity gap for `webhook-app`, and the `voice-test-support` contract test as the way to add a provider.
- [ ] **Step 4: Commit** `git commit -m "Voice docs - Rewrite the voice quickstart and editor guide for the Voice Agent cluster element"`.

### Task 29: Regenerate reference docs and remaining snapshots

- [ ] **Step 1:** `./gradlew generateDocumentation > $SCRATCH/t29.log 2>&1; echo "exit=$?"` → 0; check `git status --short docs/content/docs/reference/components | grep -E 'twilio|infobip|deepgram|elevenlabs|open-ai|ai-agent|browser'` shows the expected regenerated pages and `grep -c 'inboundCall\|realtimeChat\|createRealtimeSpeech' docs/content/docs/reference/components/{twilio,ai-agent,elevenlabs}_v1.mdx` → all 0.
- [ ] **Step 2:** Confirm every component snapshot is current: `./gradlew :server:libs:modules:components:twilio:test :server:libs:modules:components:infobip:test :server:libs:modules:components:deepgram:test :server:libs:modules:components:elevenlabs:test :server:libs:modules:components:ai:llm:open-ai:test :server:libs:modules:components:ai:agent:test :server:libs:modules:components:browser:test --continue > $SCRATCH/t29b.log 2>&1; echo "exit=$?"` → 0.
- [ ] **Step 3:** Commit `git commit -m "Voice docs - Regenerate component reference pages"`.

### Task 30: Final verification

- [ ] **Step 1: Server**
```bash
./gradlew spotlessCheck check --continue > $SCRATCH/final-server.log 2>&1; echo "exit=$?"; grep '^> Task .* FAILED' $SCRATCH/final-server.log
```
Expected `exit=0`. `check` includes checkstyle/PMD/SpotBugs — fix what they flag (SpotBugs report: `build/reports/spotbugs/*.html`).
- [ ] **Step 2: Integration tests for the touched modules**
```bash
./gradlew :server:libs:platform:platform-webhook:platform-websocket-webhook-rest:testIntegration :server:libs:platform:platform-configuration:platform-configuration-service:testIntegration :server:libs:platform:platform-component:platform-component-service:testIntegration --continue > $SCRATCH/final-int.log 2>&1; echo "exit=$?"
```
- [ ] **Step 3: Client + SDK** — `cd client && npm run check` → 0; `cd sdks/frontend/automation/chat/library && npm run lint && npx vitest run && npm run build` → 0.
- [ ] **Step 4: Boot** — `cd server && docker compose -f docker-compose.dev.infra.yml up -d && cd .. && ./gradlew -p server/apps/server-app bootRun` in the background; wait for `Started ServerApplication`; log in (admin@localhost.com / admin); run the Task 25 manual flow end-to-end with a real Deepgram key if one is available (otherwise stop at "slot renders and saves"); note in the PR description what was and was not exercised live.
- [ ] **Step 5: Grep for leftovers**
```bash
grep -rn 'callSid\|websocketTasks\|WebsocketTasks\|realtimeChat\|TwilioMediaStream\|Real-Time Workflow\|subWorkflow' server/libs server/ee client/src sdks docs/voice docs/content/docs/platform .agents CLAUDE.md --include='*.java' --include='*.ts' --include='*.tsx' --include='*.md' --include='*.mdx' --include='*.json' --include='*.yaml' | grep -v '/build/\|node_modules\|docs/superpowers'
```
Expected: no output (the superseded May specs are excluded on purpose).
- [ ] **Step 6:** Push the branch and open the PR with the spec + plan links, the live-verification notes, and the list of master-follow-up deletions (spec §5) as a checklist for the second PR.


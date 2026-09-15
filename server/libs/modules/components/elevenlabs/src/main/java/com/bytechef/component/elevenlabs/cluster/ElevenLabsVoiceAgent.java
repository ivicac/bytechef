/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.component.elevenlabs.cluster;

import static com.bytechef.component.definition.Authorization.KEY;
import static com.bytechef.component.definition.Authorization.VALUE;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.ai.llm.voice.Pcm16Resampler;
import com.bytechef.component.ai.llm.voice.ProviderWebSocketConnector;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ActionDefinition.OptionsFunction;
import com.bytechef.component.definition.ActionDefinition.WebSocketHandler;
import com.bytechef.component.definition.ActionDefinition.WebSocketHandler.WebSocketEmitter;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http;
import com.bytechef.component.definition.Option;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import com.bytechef.platform.component.definition.voice.VoiceAgentContext;
import com.bytechef.platform.component.definition.voice.VoiceAgentFunction;
import com.bytechef.platform.component.definition.voice.VoiceAgentToolset;
import com.bytechef.platform.component.definition.voice.VoiceToolDefinition;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice agent cluster element using ElevenLabs' Conversational AI WebSocket API.
 *
 * <p>
 * Bridges audio between a connected browser WebSocket and ElevenLabs' conversational agent API, which provides
 * end-to-end conversational AI with integrated STT, LLM, and TTS capabilities behind a single pre-configured agent.
 * Unlike Deepgram and OpenAI, ElevenLabs cannot receive a per-session tool schema -- an agent's client tools are
 * configured ahead of time (by name) in ElevenLabs itself, and a {@code client_tool_call} is matched against the tools
 * attached to the {@code browser/v1/voiceSession} trigger by NAME alone, through {@link VoiceAgentToolset}.
 * </p>
 *
 * @author Ivica Cardic
 */
public class ElevenLabsVoiceAgent {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsVoiceAgent.class);

    private static final String AGENT_ID = "agentId";
    private static final String FIRST_MESSAGE = "firstMessage";
    private static final String INPUT_SAMPLE_RATE = "inputSampleRate";
    private static final String LANGUAGE = "language";
    private static final String OUTPUT_SAMPLE_RATE = "outputSampleRate";
    private static final String PROMPT = "prompt";

    private static final String PCM_FORMAT_PREFIX = "pcm_";
    private static final int DEFAULT_PROVIDER_INPUT_SAMPLE_RATE = 16000;

    public static final ClusterElementDefinition<VoiceAgentFunction> CLUSTER_ELEMENT_DEFINITION =
        of(ProviderWebSocketConnector.jdk());

    private ElevenLabsVoiceAgent() {
    }

    public static ClusterElementDefinition<VoiceAgentFunction> of(ProviderWebSocketConnector connector) {
        return of(connector, null);
    }

    public static ClusterElementDefinition<VoiceAgentFunction> of(
        ProviderWebSocketConnector connector, @Nullable ElevenLabsSignedUrlResolver signedUrlResolver) {

        return ComponentDsl.<VoiceAgentFunction>clusterElement("voiceAgent")
            .title("ElevenLabs Voice Agent")
            .description(
                "Start a real-time voice agent conversation using ElevenLabs' Conversational AI API. Handles " +
                    "end-to-end voice interaction with a pre-configured agent's integrated speech-to-text, LLM " +
                    "reasoning, and text-to-speech in a single WebSocket connection.")
            .type(VoiceAgentFunction.VOICE_AGENT)
            .properties(
                string(AGENT_ID)
                    .label("Agent")
                    .description("The ElevenLabs Conversational AI agent to use for this conversation.")
                    .options((OptionsFunction<String>) ElevenLabsVoiceAgent::getAgentOptions)
                    .required(true),
                string(PROMPT)
                    .label("System Prompt")
                    .description("Overrides the agent's configured system prompt for this conversation.")
                    .required(false),
                string(FIRST_MESSAGE)
                    .label("First Message")
                    .description(
                        "Overrides the agent's configured first message spoken when the conversation starts.")
                    .required(false),
                string(LANGUAGE)
                    .label("Language")
                    .description("Overrides the agent's configured language for this conversation.")
                    .required(false),
                integer(INPUT_SAMPLE_RATE)
                    .label("Input Sample Rate")
                    .description(
                        "The sample rate of the inbound browser audio in Hz. Must match the Browser Voice " +
                            "Session trigger's sample rate.")
                    .defaultValue(16000)
                    .required(false),
                integer(OUTPUT_SAMPLE_RATE)
                    .label("Output Sample Rate")
                    .description(
                        "The sample rate of the agent's audio in Hz, for the browser's reference. ElevenLabs " +
                            "decides the actual output format server-side; it is reported back on " +
                            "conversation_initiation_metadata and is not something this element can override.")
                    .defaultValue(16000)
                    .required(false))
            .object(() -> (inputParameters, connectionParameters, context) -> perform(
                inputParameters, connectionParameters, context, connector, signedUrlResolver));
    }

    protected static WebSocketHandler perform(
        Parameters inputParameters, Parameters connectionParameters, VoiceAgentContext context,
        ProviderWebSocketConnector connector, @Nullable ElevenLabsSignedUrlResolver signedUrlResolver) {

        String agentId = inputParameters.getRequiredString(AGENT_ID);
        String apiKeyHeaderName = connectionParameters.getRequiredString(KEY);
        String apiKey = connectionParameters.getRequiredString(VALUE);
        int inputSampleRate = inputParameters.getInteger(INPUT_SAMPLE_RATE, DEFAULT_PROVIDER_INPUT_SAMPLE_RATE);
        int outputSampleRate = inputParameters.getInteger(OUTPUT_SAMPLE_RATE, DEFAULT_PROVIDER_INPUT_SAMPLE_RATE);
        ActionContext actionContext = context.actionContext();
        VoiceAgentToolset toolset = context.toolset();
        String initiationMessage = buildInitiationMessage(inputParameters, actionContext);

        // Production has no ActionContext until perform() runs, so the default resolver is created here, closing
        // over this call's actionContext, rather than being built once up front the way the connector is.
        ElevenLabsSignedUrlResolver resolver = signedUrlResolver != null
            ? signedUrlResolver
            : (resolvedAgentId, ignoredApiKey) -> resolveSignedUrl(actionContext, resolvedAgentId);

        return webSocketEmitter -> {
            AtomicBoolean closed = new AtomicBoolean(false);

            // The provider's actual input rate is not known until ElevenLabs reports it on
            // conversation_initiation_metadata (a string like "pcm_16000"); until then, and if it never arrives or
            // isn't a pcm_<rate> value, assume the common default rather than skip resampling.
            AtomicInteger providerInputSampleRateHz = new AtomicInteger(DEFAULT_PROVIDER_INPUT_SAMPLE_RATE);

            try {
                String signedUrl = resolver.resolve(agentId, apiKey);

                // connector.connect(...) (ProviderWebSocketConnector.jdk()) guarantees this is the same serialized
                // socket the listener's own callback parameters receive, so the listener can send through its
                // callback parameter directly with no binding of its own.
                WebSocket elevenLabsWebSocket = connector.connect(
                    URI.create(signedUrl), Map.of(apiKeyHeaderName, apiKey),
                    new ElevenLabsAgentListener(
                        webSocketEmitter, actionContext, toolset, closed, providerInputSampleRateHz, outputSampleRate));

                elevenLabsWebSocket.sendText(initiationMessage, true)
                    .join();

                checkAgentToolsBestEffort(actionContext, agentId, toolset.definitions());

                webSocketEmitter.addBinaryMessageListener(
                    audioData -> sendAudio(
                        elevenLabsWebSocket, audioData, closed, actionContext, inputSampleRate,
                        providerInputSampleRateHz));

                webSocketEmitter.addCloseListener(
                    () -> closeElevenLabsConnection(elevenLabsWebSocket, closed));

                webSocketEmitter.addTimeoutListener(
                    () -> closeElevenLabsConnection(elevenLabsWebSocket, closed));
            } catch (Exception exception) {
                webSocketEmitter.error(exception);
            }
        };
    }

    private static String buildInitiationMessage(Parameters inputParameters, ActionContext actionContext) {
        String prompt = inputParameters.getString(PROMPT);
        String firstMessage = inputParameters.getString(FIRST_MESSAGE);
        String language = inputParameters.getString(LANGUAGE);

        // Build the initiation message as a structured map and serialize it with a JSON writer rather than
        // concatenating raw user input into a JSON string, so every value is correctly escaped and cannot tamper
        // with the message.
        Map<String, Object> agent = new LinkedHashMap<>();

        if (prompt != null && !prompt.isBlank()) {
            agent.put("prompt", Map.of("prompt", prompt));
        }

        if (firstMessage != null && !firstMessage.isBlank()) {
            agent.put("first_message", firstMessage);
        }

        if (language != null && !language.isBlank()) {
            agent.put("language", language);
        }

        Map<String, Object> initiationMessage = new LinkedHashMap<>();

        initiationMessage.put("type", "conversation_initiation_client_data");

        if (!agent.isEmpty()) {
            initiationMessage.put("conversation_config_override", Map.of("agent", agent));
        }

        return actionContext.json(json -> json.write(initiationMessage));
    }

    private static String resolveSignedUrl(ActionContext actionContext, String agentId) {
        Map<String, Object> body = actionContext.http(http -> http.get("/convai/conversation/get-signed-url"))
            .queryParameters("agent_id", agentId)
            .configuration(responseType(Http.ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});

        if (!(body.get("signed_url") instanceof String signedUrl) || signedUrl.isBlank()) {
            throw new IllegalStateException("ElevenLabs did not return a signed_url for agent " + agentId);
        }

        return signedUrl;
    }

    /**
     * Warns, once, about every workflow tool the ElevenLabs agent has no matching client tool for -- a misconfiguration
     * that would otherwise surface only as a silently-ignored {@code client_tool_call} mid-call. This is best-effort by
     * design: it must never block session start or the audio path, so it runs on its own virtual thread and swallows
     * (at DEBUG) anything that goes wrong, including a deep-stub test double whose {@code http(...)} and
     * {@code getBody(...)} calls return further mocks rather than real data.
     */
    private static void checkAgentToolsBestEffort(
        ActionContext actionContext, String agentId, List<VoiceToolDefinition> toolDefinitions) {

        if (toolDefinitions.isEmpty()) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                Map<String, Object> agent = actionContext.http(http -> http.get("/convai/agents/" + agentId))
                    .configuration(responseType(Http.ResponseType.JSON))
                    .execute()
                    .getBody(new TypeReference<>() {});

                Set<String> agentToolNames = resolveAgentToolNames(agent, toolId -> fetchTool(actionContext, toolId));

                for (VoiceToolDefinition toolDefinition : toolDefinitions) {
                    if (!agentToolNames.contains(toolDefinition.name())) {
                        log.warn(
                            "ElevenLabs agent '{}' has no client tool named '{}' configured; the workflow tool " +
                                "will never be called.",
                            agentId, toolDefinition.name());
                    }
                }
            } catch (Exception exception) {
                log.debug("Could not verify the client tools configured on ElevenLabs agent '{}'", agentId,
                    exception);
            }
        });
    }

    private static Map<String, Object> fetchTool(ActionContext actionContext, String toolId) {
        return actionContext.http(http -> http.get("/convai/tools/" + toolId))
            .configuration(responseType(Http.ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }

    /**
     * Collects the client-tool names an ElevenLabs agent declares. Current agents reference their tools by id in
     * {@code conversation_config.agent.prompt.tool_ids}; each id is resolved through
     * {@code GET /v1/convai/tools/{tool_id}}, keeping {@code tool_config.name} only where {@code tool_config.type} is
     * {@code client}, since only a client tool can receive a {@code client_tool_call}. Older agents may still carry the
     * deprecated inline {@code prompt.tools[].name}, which is read too. An id whose lookup fails is skipped.
     */
    static Set<String> resolveAgentToolNames(
        Map<String, Object> agent, Function<String, Map<String, Object>> toolFetcher) {

        Set<String> names = new HashSet<>();

        if (!(agent.get("conversation_config") instanceof Map<?, ?> conversationConfig)
            || !(conversationConfig.get("agent") instanceof Map<?, ?> agentConfig)
            || !(agentConfig.get("prompt") instanceof Map<?, ?> prompt)) {

            return names;
        }

        if (prompt.get("tools") instanceof List<?> tools) {
            for (Object tool : tools) {
                if (tool instanceof Map<?, ?> toolMap && toolMap.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }

        if (prompt.get("tool_ids") instanceof List<?> toolIds) {
            for (Object toolId : toolIds) {
                if (toolId instanceof String toolIdValue && !toolIdValue.isBlank()) {
                    String name = clientToolName(toolFetcher, toolIdValue);

                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }

        return names;
    }

    private static @Nullable String clientToolName(
        Function<String, Map<String, Object>> toolFetcher, String toolId) {

        try {
            Map<String, Object> tool = toolFetcher.apply(toolId);

            if (tool != null && tool.get("tool_config") instanceof Map<?, ?> toolConfig
                && "client".equals(toolConfig.get("type")) && toolConfig.get("name") instanceof String name) {

                return name;
            }
        } catch (RuntimeException exception) {
            log.debug("Could not look up ElevenLabs tool '{}'", toolId, exception);
        }

        return null;
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static List<Option<String>> getAgentOptions(
        Parameters inputParameters, Parameters connectionParameters, Map<String, String> lookupDependsOnPaths,
        String searchText, Context context) {

        String cursor = null;
        boolean hasMore;
        List<Option<String>> agentOptions = new ArrayList<>();

        do {
            Map<String, Object> body = context.http(http -> http.get("/convai/agents"))
                .queryParameters("cursor", cursor, "page_size", 100, "search", searchText)
                .configuration(responseType(Http.ResponseType.JSON))
                .execute()
                .getBody(new TypeReference<>() {});

            if (body.get("agents") instanceof List<?> agents) {
                for (Object agent : agents) {
                    if (agent instanceof Map<?, ?> agentMap) {
                        agentOptions.add(option((String) agentMap.get("name"), (String) agentMap.get("agent_id")));
                    }
                }
            }

            hasMore = Boolean.TRUE.equals(body.get("has_more"));
            cursor = (String) body.get("next_cursor");
        } while (hasMore);

        return agentOptions;
    }

    private static void sendAudio(
        WebSocket elevenLabsWebSocket, byte[] audioData, AtomicBoolean closed, ActionContext actionContext,
        int inputSampleRate, AtomicInteger providerInputSampleRateHz) {

        if (closed.get() || elevenLabsWebSocket.isOutputClosed()) {
            return;
        }

        byte[] resampled = Pcm16Resampler.resample(audioData, inputSampleRate, providerInputSampleRateHz.get());
        String base64Audio = Base64.getEncoder()
            .encodeToString(resampled);

        Map<String, Object> userAudioChunk = Map.of("user_audio_chunk", base64Audio);

        elevenLabsWebSocket.sendText(actionContext.json(json -> json.write(userAudioChunk)), true);
    }

    /**
     * Updates {@code providerInputSampleRateHz} from a {@code conversation_initiation_metadata} frame's
     * {@code user_input_audio_format} (e.g. {@code "pcm_16000"}), if present and well-formed. Left unchanged (default
     * {@value #DEFAULT_PROVIDER_INPUT_SAMPLE_RATE}) when the frame hasn't arrived yet, or the field is absent or not a
     * {@code pcm_<rate>} value.
     */
    private static void updateProviderInputSampleRate(
        Map<String, Object> elevenLabsMessage, AtomicInteger providerInputSampleRateHz) {

        if (!(elevenLabsMessage.get("conversation_initiation_metadata_event") instanceof Map<?, ?> metadata)) {
            return;
        }

        if (!(metadata.get("user_input_audio_format") instanceof String format)) {
            return;
        }

        Integer rate = parsePcmRate(format);

        if (rate != null) {
            providerInputSampleRateHz.set(rate);
        }
    }

    /**
     * The warning to log when ElevenLabs reports an agent output format whose rate differs from the element's
     * configured {@code outputSampleRate}, or null when they agree or the frame says nothing usable.
     */
    static @Nullable String outputFormatMismatchWarning(
        Map<String, Object> elevenLabsMessage, int configuredOutputSampleRate) {

        if (!(elevenLabsMessage.get("conversation_initiation_metadata_event") instanceof Map<?, ?> metadata)) {
            return null;
        }

        if (!(metadata.get("agent_output_audio_format") instanceof String format)) {
            return null;
        }

        Integer rate = parsePcmRate(format);

        if (rate == null || rate == configuredOutputSampleRate) {
            return null;
        }

        return "ElevenLabs sends agent audio as " + format + " but the Voice Agent's outputSampleRate is " +
            configuredOutputSampleRate + "; the browser will play it back at the wrong speed. Set outputSampleRate " +
            "to " + rate + ".";
    }

    private static @Nullable Integer parsePcmRate(String format) {
        if (!format.startsWith(PCM_FORMAT_PREFIX)) {
            return null;
        }

        try {
            return Integer.parseInt(format.substring(PCM_FORMAT_PREFIX.length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void closeElevenLabsConnection(WebSocket elevenLabsWebSocket, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true) && !elevenLabsWebSocket.isOutputClosed()) {
            elevenLabsWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    /**
     * Maps a parsed ElevenLabs Conversational AI server event to a browser voice event, or {@code null} when the event
     * has no browser-UI representation (e.g. {@code conversation_initiation_metadata}, {@code audio} which is handled
     * by the binary-audio path, {@code ping} which is answered directly, or {@code client_tool_call} which is handled
     * by the tool-call path, not the browser event path).
     *
     * <ul>
     * <li>{@code agent_response} &rarr; {@code assistant_text}</li>
     * <li>{@code user_transcript} &rarr; {@code transcript_final}</li>
     * <li>{@code interruption} &rarr; {@code speech_start} (barge-in signal)</li>
     * </ul>
     *
     * @param elevenLabsMessage the parsed ElevenLabs event
     * @return the browser voice event, or {@code null} if the event is not surfaced to the browser
     */
    static @Nullable Map<String, Object> toVoiceEvent(Map<String, Object> elevenLabsMessage) {
        if (!(elevenLabsMessage.get("type") instanceof String type)) {
            return null;
        }

        return switch (type) {
            case "agent_response" -> Map.of(
                "type", "assistant_text", "text",
                nestedText(elevenLabsMessage, "agent_response_event", "agent_response"));
            case "user_transcript" -> Map.of(
                "type", "transcript_final", "text",
                nestedText(elevenLabsMessage, "user_transcription_event", "user_transcript"));
            case "interruption" -> Map.of("type", "speech_start");
            default -> null;
        };
    }

    private static String nestedText(Map<String, Object> message, String eventKey, String textKey) {
        if (message.get(eventKey) instanceof Map<?, ?> event && event.get(textKey) instanceof String text) {
            return text;
        }

        return "";
    }

    private static class ElevenLabsAgentListener implements WebSocket.Listener {

        private final ActionContext actionContext;
        private final AtomicBoolean closed;
        private final AtomicBoolean errored = new AtomicBoolean(false);
        private final int outputSampleRate;
        private final AtomicInteger providerInputSampleRateHz;
        private final StringBuilder textBuffer = new StringBuilder();
        private final VoiceAgentToolset toolset;
        private final WebSocketEmitter webSocketEmitter;

        ElevenLabsAgentListener(
            WebSocketEmitter webSocketEmitter, ActionContext actionContext, VoiceAgentToolset toolset,
            AtomicBoolean closed, AtomicInteger providerInputSampleRateHz, int outputSampleRate) {

            this.actionContext = actionContext;
            this.closed = closed;
            this.outputSampleRate = outputSampleRate;
            this.providerInputSampleRateHz = providerInputSampleRateHz;
            this.toolset = toolset;
            this.webSocketEmitter = webSocketEmitter;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);

            if (last) {
                String message = textBuffer.toString();

                textBuffer.setLength(0);

                handleMessage(webSocket, message);
            }

            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closed.compareAndSet(false, true)) {
                webSocketEmitter.complete();
            }

            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (errored.compareAndSet(false, true)) {
                webSocketEmitter.error(error);
            }

            closeElevenLabsConnection(webSocket, closed);
        }

        @SuppressWarnings("unchecked")
        private void handleMessage(WebSocket webSocket, String message) {
            Map<String, Object> elevenLabsMessage;

            try {
                elevenLabsMessage = actionContext.json(json -> json.read(message, Map.class));
            } catch (Exception exception) {
                log.debug("Ignoring an unparseable ElevenLabs voice-agent frame", exception);

                webSocketEmitter.send(Map.of("source", "eleven_labs_agent", "data", message));

                return;
            }

            String type = elevenLabsMessage.get("type") instanceof String value ? value : null;

            if ("audio".equals(type)) {
                handleAudioEvent(elevenLabsMessage);
            } else if ("ping".equals(type)) {
                handlePing(webSocket, elevenLabsMessage);
            } else if ("client_tool_call".equals(type)) {
                handleClientToolCall(webSocket, elevenLabsMessage);
            } else {
                if ("conversation_initiation_metadata".equals(type)) {
                    updateProviderInputSampleRate(elevenLabsMessage, providerInputSampleRateHz);

                    String outputFormatWarning = outputFormatMismatchWarning(elevenLabsMessage, outputSampleRate);

                    if (outputFormatWarning != null) {
                        log.warn(outputFormatWarning);
                    }
                }

                Map<String, Object> voiceEvent = toVoiceEvent(elevenLabsMessage);

                webSocketEmitter.send(voiceEvent != null ? voiceEvent : Map.of(
                    "source", "eleven_labs_agent", "data", message));
            }
        }

        private void handleAudioEvent(Map<String, Object> elevenLabsMessage) {
            if (!(elevenLabsMessage.get("audio_event") instanceof Map<?, ?> audioEvent)) {
                return;
            }

            if (!(audioEvent.get("audio_base_64") instanceof String audioBase64) || audioBase64.isEmpty()) {
                return;
            }

            webSocketEmitter.sendBinary(Base64.getDecoder()
                .decode(audioBase64));
        }

        private void handlePing(WebSocket webSocket, Map<String, Object> elevenLabsMessage) {
            Object eventId = null;

            if (elevenLabsMessage.get("ping_event") instanceof Map<?, ?> pingEvent) {
                eventId = pingEvent.get("event_id");
            }

            Map<String, Object> pong = new LinkedHashMap<>();

            pong.put("type", "pong");
            pong.put("event_id", eventId);

            try {
                if (!closed.get() && !webSocket.isOutputClosed()) {
                    webSocket.sendText(actionContext.json(json -> json.write(pong)), true);
                }
            } catch (Exception exception) {
                log.warn("Failed to send the ElevenLabs pong reply", exception);
            }
        }

        @SuppressWarnings("unchecked")
        private void handleClientToolCall(WebSocket webSocket, Map<String, Object> elevenLabsMessage) {
            if (!(elevenLabsMessage.get("client_tool_call") instanceof Map<?, ?> clientToolCall)) {
                return;
            }

            String toolCallId = clientToolCall.get("tool_call_id") instanceof String value ? value : null;
            String toolName = clientToolCall.get("tool_name") instanceof String value ? value : null;
            Map<String, Object> parameters = clientToolCall.get("parameters") instanceof Map<?, ?> value
                ? (Map<String, Object>) value
                : Map.of();

            if (toolCallId == null || toolName == null) {
                return;
            }

            Thread.startVirtualThread(() -> callTool(webSocket, toolCallId, toolName, parameters));
        }

        /**
         * Calls the tool and always answers ElevenLabs, even when something goes wrong: a tool that throws despite
         * {@link VoiceAgentToolset#call}'s never-throws contract, or the reply send itself failing, is caught and
         * logged here rather than left to kill this virtual thread silently -- which would otherwise leave ElevenLabs
         * waiting on a {@code client_tool_result} that never arrives and hang the turn. A tool name the workflow never
         * attached is answered the same way, with {@code is_error:true}, without ever calling
         * {@link VoiceAgentToolset#call}.
         */
        private void callTool(
            WebSocket webSocket, String toolCallId, String toolName, Map<String, Object> parameters) {

            boolean knownTool = toolset.definitions()
                .stream()
                .anyMatch(definition -> definition.name()
                    .equals(toolName));
            String argumentsJson = actionContext.json(json -> json.write(parameters));
            String result;
            boolean isError;

            if (!knownTool) {
                result = VoiceAgentToolset.unknownTool(toolName);
                isError = true;
            } else {
                try {
                    result = toolset.call(toolName, argumentsJson);
                    isError = false;
                } catch (Exception exception) {
                    log.warn("Voice tool '{}' failed", toolName, exception);

                    result = errorMessage(exception);
                    isError = true;
                }
            }

            webSocketEmitter.send(Map.of("type", "tool_call", "name", toolName, "arguments", parameters));
            webSocketEmitter.send(
                Map.of(
                    "type", "tool_result", "name", toolName, "ok", !isError, "arguments", parameters, "result",
                    result));

            Map<String, Object> response = new LinkedHashMap<>();

            response.put("type", "client_tool_result");
            response.put("tool_call_id", toolCallId);
            response.put("result", result);
            response.put("is_error", isError);

            try {
                if (!closed.get() && !webSocket.isOutputClosed()) {
                    webSocket.sendText(actionContext.json(json -> json.write(response)), true);
                }
            } catch (Exception exception) {
                log.warn("Failed to send the voice tool reply for '{}'", toolName, exception);
            }
        }

        private static String errorMessage(Exception exception) {
            String message = exception.getMessage();

            return message != null && !message.isBlank() ? message : exception.getClass()
                .getSimpleName();
        }
    }
}

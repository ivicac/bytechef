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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hosts a voice session: resolves the trigger's single Voice Agent cluster element, gives it an emitter, and keeps the
 * pair alive until {@link #stop}. A voice session is not a workflow run — Atlas dispatches tasks sequentially and a
 * voice element never completes until the caller hangs up — so nothing about a live session flows through the task
 * engine; Atlas re-enters only through the continuation job the WS handler creates on close.
 *
 * <p>
 * The {@link ContextFactory} is resolved lazily: the distributed EE {@code webhook-app} carries this module but no
 * context factory, and it must still boot. A voice session started on such a node fails with a clear error instead.
 *
 * <p>
 * <b>Known gap:</b> LLM and tool usage during a session is not cost-attributed: usage events need a job id and a
 * session has none. Post-session work in the continuation job is tracked normally.
 *
 * @author Ivica Cardic
 */
@Component
public class VoiceSessionEngine {

    /** The start of the {@link NoVoiceAgentException} message a trigger without a Voice Agent element fails with. */
    public static final String NO_VOICE_AGENT_MESSAGE = "The voice session trigger has no Voice Agent.";

    private static final Logger log = LoggerFactory.getLogger(VoiceSessionEngine.class);

    private static final int DEFAULT_OUTPUT_SAMPLE_RATE = 24000;
    private static final String ELEMENT_NAME = "voiceAgent";
    private static final String OUTPUT_SAMPLE_RATE = "outputSampleRate";

    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ConnectionService connectionService;
    private final ObjectProvider<ContextFactory> contextFactoryProvider;
    private final Evaluator evaluator;
    private final ObjectProvider<VoiceAgentToolsetFactory> voiceAgentToolsetFactoryProvider;
    private final WebSocketEmitterRegistry webSocketEmitterRegistry;

    private final AtomicLong sessionIds = new AtomicLong();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public VoiceSessionEngine(
        ClusterElementDefinitionService clusterElementDefinitionService,
        ObjectProvider<ContextFactory> contextFactoryProvider, ConnectionService connectionService, Evaluator evaluator,
        WebSocketEmitterRegistry webSocketEmitterRegistry,
        ObjectProvider<VoiceAgentToolsetFactory> voiceAgentToolsetFactoryProvider) {

        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.connectionService = connectionService;
        this.contextFactoryProvider = contextFactoryProvider;
        this.evaluator = evaluator;
        this.voiceAgentToolsetFactoryProvider = voiceAgentToolsetFactoryProvider;
        this.webSocketEmitterRegistry = webSocketEmitterRegistry;
    }

    /**
     * Starts the trigger's Voice Agent and wires it into the caller's session.
     *
     * @param triggerExtensions      the trigger's raw {@code extensions} map (its {@code clusterElements}), must hold a
     *                               {@code voiceAgent} and may hold a {@code tools} slot beside it. Handed to
     *                               {@link VoiceAgentToolsetFactory} as-is, since {@link ClusterElementMap} cannot be
     *                               re-parsed from an already-parsed {@link ClusterElement}'s own extensions.
     * @param connectionIds          connection ids keyed by element workflow node name (the agent and its tools)
     * @param sessionInputs          values the element's parameters may reference: {@code sessionId},
     *                               {@code startedAt}, {@code testMode}
     * @param jobPrincipalId         the deployment the session runs for (a project deployment or integration instance);
     *                               null in the editor
     * @param jobPrincipalWorkflowId the deployment's workflow row, when the caller knows it
     * @param environmentId          the environment connections resolve in
     * @param type                   AUTOMATION or EMBEDDED
     * @param editorEnvironment      whether this is an in-editor test session
     * @param bridge                 receives the element's emitter so the caller can attach its WebSocket
     */
    public VoiceSession start(
        Map<String, ?> triggerExtensions, Map<String, Long> connectionIds, Map<String, ?> sessionInputs,
        @Nullable Long jobPrincipalId, @Nullable Long jobPrincipalWorkflowId, @Nullable Long environmentId,
        @Nullable PlatformType type, boolean editorEnvironment, Consumer<WebSocketEmitter> bridge) {

        ContextFactory contextFactory = contextFactoryProvider.getIfAvailable();

        if (contextFactory == null) {
            throw new IllegalStateException("Voice sessions are not available on this node: no ContextFactory");
        }

        ClusterElementMap triggerClusterElements = ClusterElementMap.of(triggerExtensions);

        ClusterElement clusterElement = triggerClusterElements.fetchClusterElement(VoiceAgentFunction.VOICE_AGENT)
            .orElseThrow(NoVoiceAgentException::new);

        VoiceAgentFunction voiceAgentFunction = clusterElementDefinitionService.getClusterElement(
            clusterElement.getComponentName(), clusterElement.getComponentVersion(),
            clusterElement.getClusterElementName());

        // No job runs during a session, so nothing else would resolve ${sessionId} and friends for the element.
        Map<String, ?> inputParameters = evaluator.evaluate(clusterElement.getParameters(), sessionInputs);

        Map<String, ComponentConnection> connections = resolveConnections(connectionIds);

        ComponentConnection componentConnection = connections.get(clusterElement.getWorkflowNodeName());

        ActionContext actionContext = contextFactory.createActionContext(
            clusterElement.getComponentName(), clusterElement.getComponentVersion(), ELEMENT_NAME, jobPrincipalId,
            jobPrincipalWorkflowId, null, null, null, componentConnection, environmentId, type, editorEnvironment);

        VoiceAgentToolset toolset = Optional.ofNullable(voiceAgentToolsetFactoryProvider.getIfAvailable())
            .map(factory -> factory.create(triggerExtensions, connections, actionContext))
            .orElse(VoiceAgentToolset.EMPTY);

        long sessionId = sessionIds.incrementAndGet();
        WebSocketEmitter emitter = new WebSocketEmitter();
        SessionTranscript transcript = new SessionTranscript();

        emitter.addOutboundListener(payload -> record(transcript, payload));

        // A provider that fails mid-session tells the caller once; WebSocketEmitter.error completes the emitter, so a
        // second error never reaches this listener. The handler's bridge closes the socket after it.
        emitter.addErrorListener(throwable -> emitter.send(errorEvent(throwable)));

        // Register and bridge BEFORE the element runs: a provider that greets the caller the moment it starts must
        // not emit into an unwired session.
        webSocketEmitterRegistry.register(sessionId, ELEMENT_NAME, emitter);

        try {
            bridge.accept(emitter);
        } catch (RuntimeException runtimeException) {
            // The caller never received a session id to stop, so nothing else would ever release this emitter.
            webSocketEmitterRegistry.unregisterAll(sessionId);

            throw runtimeException;
        }

        VoiceSession voiceSession = new VoiceSession(
            sessionId, emitter, MapUtils.getInteger(inputParameters, OUTPUT_SAMPLE_RATE, DEFAULT_OUTPUT_SAMPLE_RATE),
            transcript);

        try {
            WebSocketHandler webSocketHandler = voiceAgentFunction.apply(
                ParametersFactory.create(inputParameters), ParametersFactory.create(componentConnection),
                new VoiceAgentContext(actionContext, toolset));

            webSocketHandler.handle(emitter);
        } catch (Exception exception) {
            stop(sessionId);

            throw new IllegalStateException("Failed to start the Voice Agent: " + exception.getMessage(), exception);
        }

        log.info("Voice session started: sessionId={}, element={}", sessionId, clusterElement.getType());

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

    private static Map<String, Object> errorEvent(Throwable throwable) {
        Map<String, Object> errorEvent = new LinkedHashMap<>();

        errorEvent.put("type", "error");
        errorEvent.put("message", Objects.toString(throwable.getMessage(), "The Voice Agent failed"));

        return errorEvent;
    }

    @SuppressWarnings("unchecked")
    private static void record(SessionTranscript transcript, Object payload) {
        if (!(payload instanceof Map<?, ?> event)) {
            return;
        }

        Map<String, ?> eventMap = (Map<String, ?>) event;

        String type = String.valueOf(eventMap.get("type"));

        switch (type) {
            case "transcript_final" -> recordText(eventMap, transcript::user);
            case "assistant_text" -> recordText(eventMap, transcript::assistant);
            case "tool_result" -> transcript.toolCall(
                String.valueOf(eventMap.get("name")), MapUtils.getMap(eventMap, "arguments", Map.of()),
                String.valueOf(eventMap.get("result")));
            default -> log.trace("Unrecorded voice event: {}", type);
        }
    }

    /**
     * Records an event's {@code text}, when it carries one; an event without text would otherwise enter the transcript
     * as the literal string "null".
     */
    private static void recordText(Map<String, ?> eventMap, Consumer<String> recorder) {
        if (eventMap.get("text") instanceof String text) {
            recorder.accept(text);
        }
    }

    /**
     * A started voice session: the engine's id for it, the element's emitter, the sample rate of the audio it sends
     * back, and the transcript it accumulates.
     */
    @SuppressFBWarnings("EI")
    public record VoiceSession(
        long sessionId, WebSocketEmitter emitter, int outputSampleRate, SessionTranscript transcript) {
    }

    /**
     * Thrown by {@link #start} when the trigger has no Voice Agent element. Handlers catch it by type to refuse the
     * session with a hint; matching on the message would also catch any other failure that happened to quote it.
     */
    public static class NoVoiceAgentException extends IllegalStateException {

        public NoVoiceAgentException() {
            super(NO_VOICE_AGENT_MESSAGE + " Add a Voice Agent cluster element to the trigger.");
        }
    }
}

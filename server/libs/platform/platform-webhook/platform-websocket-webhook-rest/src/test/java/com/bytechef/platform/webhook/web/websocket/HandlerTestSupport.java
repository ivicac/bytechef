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

package com.bytechef.platform.webhook.web.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.platform.configuration.constant.WorkflowExtConstants;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.webhook.voice.SessionTranscript;
import com.bytechef.platform.webhook.voice.VoiceSessionConnectionResolver;
import com.bytechef.platform.webhook.voice.VoiceSessionEngine.VoiceSession;
import com.bytechef.platform.webhook.voice.WebSocketEmitter;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Test doubles shared by the voice WebSocket handler tests.
 *
 * @author Ivica Cardic
 */
final class HandlerTestSupport {

    /** A real, parseable webhook id: the handler decodes it into a {@link WorkflowExecutionId}. */
    static final String WEBHOOK_ID = WorkflowExecutionId.of(PlatformType.AUTOMATION, 7L, "wf-uuid", "trigger_1")
        .toString();

    /** The environment {@link FixedTriggerResolver} reports every deployment running in. */
    static final long ENVIRONMENT_ID = 5L;

    /** A second deployed webhook, for checks that one webhook's token never reaches another webhook's session. */
    static final String OTHER_WEBHOOK_ID = WorkflowExecutionId.of(PlatformType.AUTOMATION, 8L, "wf-uuid-b", "trigger_1")
        .toString();

    private HandlerTestSupport() {
    }

    /**
     * A draft workflow whose only trigger is a {@code browser/v1/voiceSession} with no cluster elements. Mocked rather
     * than parsed: {@code triggers} is a reserved word contributed by platform-configuration-service, which this
     * module's tests do not carry, so a real definition would be rejected as an unknown property. Build it before
     * stubbing anything with it.
     */
    static Workflow voiceWorkflow() {
        Workflow workflow = mock(Workflow.class);

        WorkflowTrigger workflowTrigger = new WorkflowTrigger(
            Map.of("name", "trigger_1", "type", "browser/v1/voiceSession", "parameters", Map.of()));

        when(workflow.getExtensions(eq(WorkflowExtConstants.TRIGGERS), eq(WorkflowTrigger.class), anyList()))
            .thenReturn(List.of(workflowTrigger));

        return workflow;
    }

    /**
     * Stands in for {@code VoiceSessionEngine.start}, in the engine's order: registers the engine's one-shot error
     * event on the emitter, then hands the emitter to the handler's bridge.
     */
    static VoiceSession startLikeTheEngine(
        InvocationOnMock invocation, WebSocketEmitter emitter, long engineSessionId) {

        Consumer<WebSocketEmitter> bridge = invocation.getArgument(8);

        emitter.addErrorListener(
            throwable -> emitter.send(Map.of("type", "error", "message", String.valueOf(throwable.getMessage()))));

        bridge.accept(emitter);

        return new VoiceSession(engineSessionId, emitter, 24000, new SessionTranscript());
    }

    static void assertDecorates(WebSocketSession storedSocket, WebSocketSession rawSocket) {
        assertThat(storedSocket).isInstanceOfSatisfying(
            ConcurrentWebSocketSessionDecorator.class, decorator -> {
                assertThat(decorator.getDelegate()).isSameAs(rawSocket);
                assertThat(decorator.getSendTimeLimit()).isEqualTo(VoiceSessionRegistry.SEND_TIME_LIMIT_MILLIS);
                assertThat(decorator.getBufferSizeLimit()).isEqualTo(VoiceSessionRegistry.BUFFER_SIZE_LIMIT_BYTES);
            });
    }

    /**
     * An engine start that blocks until released, so a test can close the socket while the element is still starting.
     */
    static final class BlockingStart implements Answer<VoiceSession> {

        private final long engineSessionId;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicReference<String> sessionId = new AtomicReference<>();

        BlockingStart(long engineSessionId) {
            this.engineSessionId = engineSessionId;
        }

        @Override
        public VoiceSession answer(InvocationOnMock invocation) throws InterruptedException {
            Map<String, ?> inputs = invocation.getArgument(2);

            sessionId.set(String.valueOf(inputs.get("sessionId")));
            entered.countDown();

            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();

            return new VoiceSession(engineSessionId, new WebSocketEmitter(), 24000, new SessionTranscript());
        }

        String awaitEntered() throws InterruptedException {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            return sessionId.get();
        }

        void release() {
            released.countDown();
        }
    }

    /**
     * Resolves every webhook id to one {@code browser/v1/voiceSession} trigger with no cluster elements.
     */
    static final class FixedTriggerResolver implements TriggerResolver {

        /** The tenant current on the thread each {@code resolve} ran on, in call order. */
        final List<String> resolvedTenantIds = new CopyOnWriteArrayList<>();

        private final boolean enabled;
        private final int silenceTimeoutSeconds;

        FixedTriggerResolver() {
            this(0);
        }

        FixedTriggerResolver(int silenceTimeoutSeconds) {
            this(silenceTimeoutSeconds, true);
        }

        FixedTriggerResolver(int silenceTimeoutSeconds, boolean enabled) {
            this.enabled = enabled;
            this.silenceTimeoutSeconds = silenceTimeoutSeconds;
        }

        @Override
        public boolean isWorkflowEnabled(WorkflowExecutionId workflowExecutionId) {
            return enabled;
        }

        @Override
        public long getEnvironmentId(WorkflowExecutionId workflowExecutionId) {
            return ENVIRONMENT_ID;
        }

        @Override
        public WorkflowTrigger resolve(WorkflowExecutionId workflowExecutionId) {
            resolvedTenantIds.add(TenantContext.getCurrentTenantId());

            return new WorkflowTrigger(
                Map.of(
                    "name", workflowExecutionId.getTriggerName(), "type", "browser/v1/voiceSession", "parameters",
                    Map.of("silenceTimeoutSeconds", silenceTimeoutSeconds), "clusterElements", Map.of()));
        }
    }

    /**
     * Resolves no connections, and records the calls it received as {@code workflowId:triggerName:environmentId}.
     */
    static final class NoConnections extends VoiceSessionConnectionResolver {

        final List<String> deployedCalls = new CopyOnWriteArrayList<>();
        final List<String> testCalls = new CopyOnWriteArrayList<>();

        NoConnections() {
            super(List.of(), mock(WorkflowTestConfigurationService.class));
        }

        @Override
        public Map<String, Long> resolveDeployed(
            WorkflowExecutionId workflowExecutionId, WorkflowTrigger workflowTrigger) {

            deployedCalls.add(workflowExecutionId.getWorkflowUuid() + ":" + workflowTrigger.getName());

            return Map.of();
        }

        @Override
        public Map<String, Long> resolveTest(String workflowId, String triggerName, long environmentId) {
            testCalls.add(workflowId + ":" + triggerName + ":" + environmentId);

            return Map.of();
        }
    }
}

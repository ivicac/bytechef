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

package com.bytechef.component.ai.agent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.ai.agent.tool.AgentToolCallingManagers;
import com.bytechef.component.ai.llm.facade.AiAgentToolFacade;
import com.bytechef.component.ai.llm.util.ModelUtils;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider;
import com.bytechef.platform.ai.guardrails.RestorationDestination;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ai.agent.ModelFunction;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Coverage for the {@link AiGuardrailsAdvisorProvider} wiring in
 * {@link AbstractAiAgentChatAction#getChatClientRequestSpec}: the optional CE SPI is consulted through an
 * {@link ObjectProvider}, the returned advisor (when present) is registered ahead of the rest of the advisor chain,
 * absence of a bean leaves the chain exactly as before, and a blocking violation raised by the advisor propagates out
 * of the request-spec build (and, transitively, {@code perform()}) rather than being swallowed here.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AbstractAiAgentChatActionGuardrailsTest {

    @Mock
    private AiAgentToolFacade aiAgentToolFacade;

    @Mock
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Mock
    private ToolCallingManager toolCallingManager;

    @Test
    void testGuardrailsAdvisorRegisteredWhenProviderPresent() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());
        Parameters extensions = buildExtensions();

        stubModelLookup();

        Map<String, ComponentConnection> connectionParameters = buildConnectionParameters();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
        when(actionContext.getJobPrincipalId()).thenReturn(42L);

        FakeGuardrailsAdvisor guardrailsAdvisor = new FakeGuardrailsAdvisor();

        AiGuardrailsAdvisorProvider provider = mock(AiGuardrailsAdvisorProvider.class);

        when(provider.getAdvisor(
            PlatformType.AUTOMATION, 42L, "ai_agent", RestorationDestination.WORKFLOW_OUTPUT))
                .thenReturn(Optional.of(guardrailsAdvisor));

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager, null,
            presentProvider(provider));

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            ChatClient.ChatClientRequestSpec spec = action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext);

            List<Advisor> advisors = ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getAdvisors();

            // The guardrails advisor self-orders at HIGHEST_PRECEDENCE and is also registered first in the chain —
            // both facts place it ahead of every other advisor Spring AI ends up executing.
            assertThat(advisors).contains(guardrailsAdvisor);
            assertThat(advisors.indexOf(guardrailsAdvisor)).isZero();
        }
    }

    /**
     * Regression coverage for the defect where the canvas AI Agent surface's tool-boundary metrics
     * ({@code tool_args_restored}/{@code token_unresolved}/{@code tool_result_tokenized}) never carried the correct
     * {@code surface} tag: {@code AgentToolCallingManagers} used to resolve its {@code SensitiveDataMetrics} from an
     * injected {@code ObjectProvider<SensitiveDataMetrics>} instead of the workspace-and-surface-scoped instance
     * {@link AiGuardrailsAdvisorProvider#getMetrics} builds for this exact call. This test asserts that
     * {@code getChatClientRequestSpec} resolves the tool-boundary metrics through
     * {@code AiGuardrailsAdvisorProvider#getMetrics} with the identical {@code (platformType, jobPrincipalId,
     * "ai_agent")} arguments used for {@link AiGuardrailsAdvisorProvider#getAdvisor} -- see
     * {@code AgentToolCallingManagersTest} (and {@code AbstractAiAgentChatActionTest}'s own
     * {@code testGetAdvisorsThreadsToolBoundaryMetricsIntoTheToolCallingManager}) for the companion proof that whatever
     * instance is resolved here actually reaches {@code PiiTokenBoundaryToolCallingManager} and gets used to record
     * events.
     */
    @Test
    void testToolBoundaryMetricsResolvedWithTheAiAgentSurfaceTag() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());
        Parameters extensions = buildExtensions();

        stubModelLookup();

        Map<String, ComponentConnection> connectionParameters = buildConnectionParameters();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
        when(actionContext.getJobPrincipalId()).thenReturn(42L);

        AiGuardrailsAdvisorProvider provider = mock(AiGuardrailsAdvisorProvider.class);

        when(provider.getAdvisor(
            PlatformType.AUTOMATION, 42L, "ai_agent", RestorationDestination.WORKFLOW_OUTPUT))
                .thenReturn(Optional.empty());

        SensitiveDataMetrics sensitiveDataMetrics = mock(SensitiveDataMetrics.class);

        when(provider.getMetrics(PlatformType.AUTOMATION, 42L, "ai_agent"))
            .thenReturn(sensitiveDataMetrics);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager, null,
            presentProvider(provider));

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            action.getChatClientRequestSpec(inputParameters, connectionParameters, extensions, null, actionContext);
        }

        verify(provider).getMetrics(PlatformType.AUTOMATION, 42L, "ai_agent");
    }

    /**
     * I2(b): pins the ternary at {@code AbstractAiAgentChatAction#getChatClientRequestSpec} (around line 325) that
     * resolves {@link RestorationDestination#CONVERSATION} for a streaming action and
     * {@link RestorationDestination#WORKFLOW_OUTPUT} for a non-streaming one. Every other test in this class drives
     * {@link TestAiAgentChatAction}, which does not override {@code isStreaming()} and so always stubs
     * {@code WORKFLOW_OUTPUT} -- none of them would fail if that ternary's branches were swapped. This test drives
     * {@link TestStreamingAiAgentChatAction} instead, whose {@code isStreaming()} returns {@code true}, and asserts the
     * provider is asked for the {@code CONVERSATION} destination specifically.
     */
    @Test
    void testStreamingActionResolvesConversationDestination() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());
        Parameters extensions = buildExtensions();

        stubModelLookup();

        Map<String, ComponentConnection> connectionParameters = buildConnectionParameters();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
        when(actionContext.getJobPrincipalId()).thenReturn(42L);

        AiGuardrailsAdvisorProvider provider = mock(AiGuardrailsAdvisorProvider.class);

        when(provider.getAdvisor(
            PlatformType.AUTOMATION, 42L, "ai_agent", RestorationDestination.CONVERSATION))
                .thenReturn(Optional.empty());

        TestStreamingAiAgentChatAction action = new TestStreamingAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager, null,
            presentProvider(provider));

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            action.getChatClientRequestSpec(inputParameters, connectionParameters, extensions, null, actionContext);
        }

        verify(provider).getAdvisor(PlatformType.AUTOMATION, 42L, "ai_agent", RestorationDestination.CONVERSATION);
        verify(provider, never()).getAdvisor(
            PlatformType.AUTOMATION, 42L, "ai_agent", RestorationDestination.WORKFLOW_OUTPUT);
    }

    @Test
    void testNoProviderMeansNoAdvisor() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());
        Parameters extensions = buildExtensions();

        stubModelLookup();

        Map<String, ComponentConnection> connectionParameters = buildConnectionParameters();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        // No ObjectProvider at all (the pre-Task-6 constructor) and an empty ObjectProvider both mean the request
        // spec is built exactly as it was before this wiring existed — no guardrails advisor, no interaction with
        // the platform-type/job-principal accessors that only exist to feed the provider call.
        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager, null,
            emptyProvider());

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            ChatClient.ChatClientRequestSpec spec = action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext);

            List<Advisor> advisors = ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getAdvisors();

            assertThat(advisors).noneMatch(FakeGuardrailsAdvisor.class::isInstance);
        }

        verify(actionContext, never()).getPlatformType();
        verify(actionContext, never()).getJobPrincipalId();
    }

    @Test
    void testBlockingViolationFailsTheStep() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());
        Parameters extensions = buildExtensions();

        ChatModel chatModel = stubModelLookup();

        // The advisor chain executes for real in this test (spec.call().content() below), so
        // DefaultChatClientUtils.toChatClientRequest needs a real ChatOptions to .mutate() while assembling the
        // request — that happens before any advisor (including the blocking one) runs.
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder()
            .build());

        Map<String, ComponentConnection> connectionParameters = buildConnectionParameters();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        when(actionContext.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
        when(actionContext.getJobPrincipalId()).thenReturn(42L);

        // Stands in for the EE AiGuardrailViolationException without the CE component depending on the EE
        // guardrails module: a BLOCK-mode advisor throws before delegating to the rest of the chain, and its
        // message names only the violation category — never the offending content.
        BlockingGuardrailAdvisor blockingAdvisor = new BlockingGuardrailAdvisor("blocked-term");

        AiGuardrailsAdvisorProvider provider = mock(AiGuardrailsAdvisorProvider.class);

        when(provider.getAdvisor(
            PlatformType.AUTOMATION, 42L, "ai_agent", RestorationDestination.WORKFLOW_OUTPUT))
                .thenReturn(Optional.of(blockingAdvisor));

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager, null,
            presentProvider(provider));

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            ChatClient.ChatClientRequestSpec spec = action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext);

            // getChatClientRequestSpec itself never invokes the advisor chain — the violation only surfaces once a
            // terminal call (call().content()/.chatResponse()) actually walks it. Nothing in this component catches
            // it along the way, so it propagates straight out to the caller (perform()), failing the step.
            assertThatThrownBy(() -> spec.call()
                .content())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Blocked by AI guardrail: blocked-term");
        }
    }

    private Parameters buildExtensions() {
        return MockParametersFactory.create(
            Map.of("clusterElements", Map.of("model", buildModelElement())));
    }

    private Map<String, ComponentConnection> buildConnectionParameters() {
        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);

        return Map.of("model_1", componentConnection);
    }

    private static Map<String, Object> buildModelElement() {
        HashMap<String, Object> modelParams = new HashMap<>();
        modelParams.put("model", "gpt-4o");

        Map<String, Object> modelElement = new HashMap<>();
        modelElement.put("name", "model_1");
        modelElement.put("type", "testComponent/v1/testModel");
        modelElement.put("parameters", modelParams);

        return modelElement;
    }

    private ChatModel stubModelLookup() throws Exception {
        ModelFunction modelFunction = mock(ModelFunction.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);
        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);

        return chatModel;
    }

    /**
     * Stubs both {@code ifAvailable(Consumer)} and {@code getIfAvailable()} so this one helper serves every
     * {@code ObjectProvider} consumption style used across this file's constructor arguments -- the guardrails provider
     * resolves itself via {@code getIfAvailable()} (see {@code getChatClientRequestSpec}'s toolBoundaryMetrics wiring,
     * which also needs {@link AiGuardrailsAdvisorProvider#getMetrics}), while
     * {@code WorkspaceSystemPromptAdvisorProvider} still resolves via {@code ifAvailable(Consumer)}. Both stubs are
     * {@code lenient()} because any one caller only ever exercises one of the two styles, and the other would otherwise
     * be flagged as an unnecessary stubbing.
     */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> presentProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);

        lenient().doAnswer(invocation -> {
            Consumer<T> consumer = invocation.getArgument(0);

            consumer.accept(value);

            return null;
        })
            .when(provider)
            .ifAvailable(any());

        lenient().when(provider.getIfAvailable())
            .thenReturn(value);

        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    private static class TestAiAgentChatAction extends AbstractAiAgentChatAction {

        TestAiAgentChatAction(
            AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ToolExecutionRecorder> toolExecutionRecorderObjectProvider,
            ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderObjectProvider) {

            super(
                aiAgentToolFacade, clusterElementDefinitionService, new AgentToolCallingManagers(toolCallingManager),
                toolExecutionRecorderObjectProvider, aiGuardrailsAdvisorProviderObjectProvider);
        }
    }

    /**
     * As {@link TestAiAgentChatAction}, but overriding {@code isStreaming()} to {@code true} -- models
     * {@link com.bytechef.component.ai.agent.action.AiAgentStreamChatAction}, the real streaming action, without
     * pulling that class's own dependencies into this test.
     */
    private static class TestStreamingAiAgentChatAction extends AbstractAiAgentChatAction {

        TestStreamingAiAgentChatAction(
            AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ToolExecutionRecorder> toolExecutionRecorderObjectProvider,
            ObjectProvider<AiGuardrailsAdvisorProvider> aiGuardrailsAdvisorProviderObjectProvider) {

            super(
                aiAgentToolFacade, clusterElementDefinitionService, new AgentToolCallingManagers(toolCallingManager),
                toolExecutionRecorderObjectProvider, aiGuardrailsAdvisorProviderObjectProvider);
        }

        @Override
        protected boolean isStreaming() {
            return true;
        }
    }

    /** Minimal no-op advisor used to assert presence/position in the built chain. */
    private static class FakeGuardrailsAdvisor implements CallAdvisor {

        @Override
        public String getName() {
            return "FakeGuardrailsAdvisor";
        }

        @Override
        public int getOrder() {
            return HIGHEST_PRECEDENCE;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
            return callAdvisorChain.nextCall(chatClientRequest);
        }
    }

    /** Simulates a BLOCK-mode guardrail violation: throws before delegating to the rest of the chain. */
    private static class BlockingGuardrailAdvisor implements CallAdvisor {

        private final String category;

        BlockingGuardrailAdvisor(String category) {
            this.category = category;
        }

        @Override
        public String getName() {
            return "BlockingGuardrailAdvisor";
        }

        @Override
        public int getOrder() {
            return HIGHEST_PRECEDENCE;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
            throw new RuntimeException("Blocked by AI guardrail: " + category);
        }
    }
}

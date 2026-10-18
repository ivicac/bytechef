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

import static com.bytechef.component.ai.llm.constant.LLMConstants.SUBAGENT_CONVERSATION_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bytechef.component.ai.agent.action.event.ToolExecutionEvent;
import com.bytechef.component.ai.agent.tool.AgentToolCallingManagers;
import com.bytechef.component.ai.agent.utils.cluster.AiAgentUtilsApprovalGateTool;
import com.bytechef.component.ai.llm.advisor.CodeFenceStrippingAdvisor;
import com.bytechef.component.ai.llm.advisor.JsonSchemaValidationAdvisor;
import com.bytechef.component.ai.llm.facade.AiAgentToolFacade;
import com.bytechef.component.ai.llm.tool.ClusterElementToolCallbacks;
import com.bytechef.component.ai.llm.util.ModelUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.component.definition.ClusterElementDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.platform.ai.constant.AiAgentToolContextKey;
import com.bytechef.platform.ai.constant.ToolSuspendConstants;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataMetrics;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSessionToolContext;
import com.bytechef.platform.ai.tool.ToolSuspensionException;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ParametersFactory;
import com.bytechef.platform.component.definition.ai.agent.ChatMemoryFunction;
import com.bytechef.platform.component.definition.ai.agent.ModelFunction;
import com.bytechef.platform.component.definition.ai.agent.MultipleConnectionsToolCallbackProviderFunction;
import com.bytechef.platform.component.definition.ai.agent.MultipleConnectionsToolFunction;
import com.bytechef.platform.component.definition.ai.agent.SessionConversationHistoryReader;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.platform.configuration.domain.ClusterElementMap;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import com.bytechef.platform.tool.execution.ToolInvocationWorkspaceResolver;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class AbstractAiAgentChatActionTest {

    private static final String TOOL_LOOP_CONVERSATION_ID = "tool-loop-conversation";

    @Mock
    private AiAgentToolFacade aiAgentToolFacade;

    @Mock
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Mock
    private ToolCallingManager toolCallingManager;

    @Test
    void testChatMemoryToolExecutionNotifiesToolExecutionListener() {
        ToolCallback conversationSearchToolCallback = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                    .name("conversation_search")
                    .description("Search the conversation")
                    .inputSchema("{}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                return "[{\"text\":\"My dog is called Pixel.\"}]";
            }
        };

        ChatMemoryFunction.Result chatMemoryResult = new ChatMemoryFunction.Result(
            mock(BaseAdvisor.class), conversationId -> List.of(), List.of(conversationSearchToolCallback), false);
        List<ToolExecutionEvent> toolExecutionEvents = new ArrayList<>();

        List<ToolCallback> toolCallbacks = AbstractAiAgentChatAction.concatToolCallbacks(
            List.of(), Optional.of(chatMemoryResult), toolExecutionEvents::add, mock(ActionContext.class));

        ToolCallback toolCallback = toolCallbacks.getFirst();

        toolCallback.call("{\"query\":\"Pixel\"}");

        assertThat(toolExecutionEvents)
            .singleElement()
            .satisfies(toolExecutionEvent -> {
                assertThat(toolExecutionEvent.toolName()).isEqualTo("conversation_search");
                assertThat(toolExecutionEvent.inputs()).containsEntry("query", "Pixel");
            });
    }

    @Test
    void testGetChatClientRequestSpecWithNullParameterValues() throws Exception {
        HashMap<String, Object> inputParamsMap = new HashMap<>();

        inputParamsMap.put("key1", "value1");
        inputParamsMap.put("nullableKey", null);

        Parameters inputParameters = MockParametersFactory.create(inputParamsMap);

        HashMap<String, Object> clusterElementParams = new HashMap<>();

        clusterElementParams.put("model", "gpt-4o");
        clusterElementParams.put("conversationId", "sampleConversationId");
        clusterElementParams.put("nullableParam", null);

        Map<String, Object> modelElement = new HashMap<>();

        modelElement.put("name", "model_1");
        modelElement.put("type", "testComponent/v1/testModel");
        modelElement.put("parameters", clusterElementParams);

        Map<String, Object> chatMemoryElement = new HashMap<>();

        chatMemoryElement.put("name", "chatMemory_1");
        chatMemoryElement.put("type", "testComponent/v1/testChatMemory");
        chatMemoryElement.put("parameters", clusterElementParams);

        Parameters extensions = MockParametersFactory.create(
            Map.of("clusterElements", Map.of("model", modelElement, "chatMemory", chatMemoryElement)));

        ModelFunction modelFunction = mock(ModelFunction.class);
        ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

        ChatModel chatModel = mock(ChatModel.class);
        BaseChatMemoryAdvisor chatMemoryAdvisor = mock(BaseChatMemoryAdvisor.class);
        ChatMemoryFunction.Result chatMemoryResult = ChatMemoryFunction.Result.of(chatMemoryAdvisor, null);

        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);
        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);
        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);
        when(chatMemoryFunction.apply(any(), any(), any(), any(), any())).thenReturn(chatMemoryResult);

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);

        Map<String, ComponentConnection> connectionParameters = Map.of("model_1", componentConnection);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertDoesNotThrow(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext));
        }
    }

    @Test
    void testMultipleCheckForViolationsRejectedAtAdvisorBuild() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();
        Map<String, Object> checkForViolationsA = buildGuardrailElement(
            "checkForViolationsA", "checkForViolations/v1/checkForViolations");
        Map<String, Object> checkForViolationsB = buildGuardrailElement(
            "checkForViolationsB", "checkForViolations/v1/checkForViolations");

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", modelElement,
                    "guardrails", List.of(checkForViolationsA, checkForViolationsB))));

        stubModelLookup();

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = Map.of("model_1", componentConnection);
        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertThatThrownBy(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Multiple CheckForViolations");
        }
    }

    @Test
    void testMultipleSanitizeTextRejectedAtAdvisorBuild() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();
        Map<String, Object> sanitizeTextA = buildGuardrailElement(
            "sanitizeTextA", "sanitizeText/v1/sanitizeText");
        Map<String, Object> sanitizeTextB = buildGuardrailElement(
            "sanitizeTextB", "sanitizeText/v1/sanitizeText");

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", modelElement,
                    "guardrails", List.of(sanitizeTextA, sanitizeTextB))));

        stubModelLookup();

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = Map.of("model_1", componentConnection);
        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertThatThrownBy(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Multiple SanitizeText");
        }
    }

    @Test
    void testSingleCheckForViolationsAndSingleSanitizeTextAccepted() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();
        Map<String, Object> checkForViolations = buildGuardrailElement(
            "checkForViolations_1", "checkForViolations/v1/checkForViolations");
        Map<String, Object> sanitizeText = buildGuardrailElement(
            "sanitizeText_1", "sanitizeText/v1/sanitizeText");

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", modelElement,
                    "guardrails", List.of(checkForViolations, sanitizeText))));

        ModelFunction modelFunction = mock(ModelFunction.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);
        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);

        com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction guardrailsFunction = mock(
            com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction.class);
        Advisor advisor = mock(Advisor.class);

        when(
            clusterElementDefinitionService.<com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction>getClusterElement(
                eq("checkForViolations"), eq(1), eq("checkForViolations")))
                    .thenReturn(guardrailsFunction);
        when(
            clusterElementDefinitionService.<com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction>getClusterElement(
                eq("sanitizeText"), eq(1), eq("sanitizeText")))
                    .thenReturn(guardrailsFunction);
        when(guardrailsFunction.apply(any(), any(), any(), any(), any(), any()))
            .thenReturn(advisor);

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = new HashMap<>();

        connectionParameters.put("model_1", componentConnection);
        connectionParameters.put("checkForViolations_1", componentConnection);
        connectionParameters.put("sanitizeText_1", componentConnection);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertDoesNotThrow(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext));
        }

        assertThat(advisor).isNotNull();
    }

    @Test
    void testGuardrailAdvisorBuildFailureInvokesContextLog() throws Exception {
        // Pins the Context-threading promise from commit ee185584cf5: when a cluster element fails to initialize,
        // the error path MUST go through ActionContext.log(...) rather than raw SLF4J, so tenant-aware structured
        // logging captures the failure. A refactor dropping context.log for raw SLF4J would otherwise pass CI.
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();
        Map<String, Object> guardrailElement = buildGuardrailElement(
            "checkForViolations_1", "checkForViolations/v1/checkForViolations");

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", modelElement,
                    "guardrails", List.of(guardrailElement))));

        stubModelLookup();

        com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction guardrailsFunction = mock(
            com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction.class);

        when(
            clusterElementDefinitionService.<com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction>getClusterElement(
                eq("checkForViolations"), eq(1), eq("checkForViolations")))
                    .thenReturn(guardrailsFunction);
        when(guardrailsFunction.apply(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated guardrail init failure"));

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = new HashMap<>();

        connectionParameters.put("model_1", componentConnection);
        connectionParameters.put("checkForViolations_1", componentConnection);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertThatThrownBy(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("guardrails");
        }

        verify(actionContext, atLeastOnce()).log(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGuardrailHistoryLoadFailureFailsTheRequest() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();

        Map<String, Object> chatMemoryParameters = new HashMap<>();

        chatMemoryParameters.put("conversationId", "test-conversation");

        Map<String, Object> chatMemoryElement = new HashMap<>();

        chatMemoryElement.put("name", "chatMemory_1");
        chatMemoryElement.put("type", "testComponent/v1/testChatMemory");
        chatMemoryElement.put("parameters", chatMemoryParameters);

        Map<String, Object> guardrailElement = buildGuardrailElement(
            "checkForViolations_1", "checkForViolations/v1/checkForViolations");

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", modelElement,
                    "chatMemory", chatMemoryElement,
                    "guardrails", List.of(guardrailElement))));

        stubModelLookup();

        ChatMemory failingChatMemory = mock(ChatMemory.class);

        when(failingChatMemory.get("test-conversation")).thenThrow(new IllegalStateException("memory backend down"));

        ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);
        when(chatMemoryFunction.apply(any(), any(), any(), any(), any()))
            .thenReturn(
                ChatMemoryFunction.Result.of(
                    MessageChatMemoryAdvisor.builder(failingChatMemory)
                        .build(),
                    failingChatMemory));

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = Map.of(
            "model_1", componentConnection,
            "chatMemory_1", componentConnection,
            "checkForViolations_1", componentConnection);

        ActionContext actionContext = mock(ActionContext.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertThatThrownBy(
                () -> action.getChatClientRequestSpec(
                    inputParameters, connectionParameters, extensions, null, actionContext))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(
                            "Failed to load conversation history for guardrails, conversationId=test-conversation")
                        .hasRootCauseMessage("memory backend down");
        }

        verify(clusterElementDefinitionService, never()).getClusterElement(
            eq("checkForViolations"), anyInt(), anyString());

        ArgumentCaptor<Context.ContextConsumer<Context.Log>> logConsumerCaptor = ArgumentCaptor.forClass(
            Context.ContextConsumer.class);

        verify(actionContext, atLeastOnce()).log(logConsumerCaptor.capture());

        Context.Log log = mock(Context.Log.class);

        for (Context.ContextConsumer<Context.Log> logConsumer : logConsumerCaptor.getAllValues()) {
            logConsumer.accept(log);
        }

        verify(log).error(
            eq("Failed to load conversation history for guardrails, conversationId=test-conversation"),
            any(IllegalStateException.class));
    }

    @Test
    void testNoGuardrailsConfiguredRunsModelChainWithoutAddingAdvisor() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of("model", modelElement)));

        stubModelLookup();

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = new HashMap<>();

        connectionParameters.put("model_1", componentConnection);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            assertDoesNotThrow(() -> action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext));
        }

        verify(clusterElementDefinitionService,
            never()).<com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction>getClusterElement(
                eq("checkForViolations"), anyInt(), anyString());
        verify(clusterElementDefinitionService,
            never()).<com.bytechef.platform.component.definition.ai.agent.GuardrailsFunction>getClusterElement(
                eq("sanitizeText"), anyInt(), anyString());
    }

    @Test
    void testChatMemoryAdvisorOrderedUpstreamOfToolCallingAdvisor() throws Exception {
        // Spring AI 2.0.0-RC1 default ordering: ChatMemoryAdvisor (DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER,
        // MIN+200) runs upstream of ToolCallingAdvisor (DEFAULT_ORDER, MIN+300), so memory wraps the whole
        // tool-call loop — persisted history is applied once before the loop and only the final assistant
        // message is written back. Validity inside the loop comes from ToolCallingAdvisor retaining the
        // (assistant-with-tool_calls, toolResultMsg) pairs in its own in-loop history
        // (conversationHistoryEnabled defaults to true), so OpenAI never sees an orphaned tool result. This
        // test pins the default composition so a future bump that changes it surfaces at the dependency PR.
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();

        Map<String, Object> chatMemoryParameters = new HashMap<>();
        chatMemoryParameters.put("conversationId", "test-conversation");

        Map<String, Object> chatMemoryElement = new HashMap<>();
        chatMemoryElement.put("name", "chatMemory_1");
        chatMemoryElement.put("type", "testComponent/v1/testChatMemory");
        chatMemoryElement.put("parameters", chatMemoryParameters);

        Parameters extensions = MockParametersFactory.create(
            Map.of("clusterElements", Map.of("model", modelElement, "chatMemory", chatMemoryElement)));

        // stubModelLookup() is invoked for its side effect of wiring the cluster-element model resolution;
        // the returned ChatModel handle is no longer needed now that getOptions() is not stubbed.
        stubModelLookup();

        ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);

        // Build the chat-memory advisor exactly as the production chat-memory components do
        // (default order via the builder), so the assertion catches regressions in any of them.
        ChatMemory chatMemory = mock(ChatMemory.class);

        MessageChatMemoryAdvisor productionStyleChatMemoryAdvisor = MessageChatMemoryAdvisor
            .builder(chatMemory)
            .build();

        when(chatMemoryFunction.apply(any(), any(), any(), any(), any()))
            .thenReturn(ChatMemoryFunction.Result.of(productionStyleChatMemoryAdvisor, null));

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = Map.of(
            "model_1", componentConnection,
            "chatMemory_1", componentConnection);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            ChatClient.ChatClientRequestSpec spec = action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext);

            List<Advisor> advisors = ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getAdvisors();

            Advisor toolCallAdvisor = advisors.stream()
                .filter(ToolCallingAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ToolCallingAdvisor missing from advisor chain"));

            Advisor chatMemoryAdvisor = advisors.stream()
                .filter(BlankReplySkippingChatMemoryAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ChatMemoryAdvisor missing from advisor chain"));

            assertThat(chatMemoryAdvisor.getOrder())
                .as(
                    "Spring AI RC1 default ordering places ChatMemoryAdvisor upstream of ToolCallingAdvisor; " +
                        "ToolCallingAdvisor's own in-loop history (conversationHistoryEnabled=true) is what " +
                        "keeps each tool-call iteration valid, not advisor ordering.")
                .isLessThan(toolCallAdvisor.getOrder());
        }
    }

    @Test
    void testSupportingChatMemoryAdvisorOrderedInsideToolCallingAdvisor() throws Exception {
        Parameters inputParameters = MockParametersFactory.create(Map.of());

        Map<String, Object> modelElement = buildModelElement();

        Map<String, Object> chatMemoryParameters = new HashMap<>();
        chatMemoryParameters.put("conversationId", "test-conversation");

        Map<String, Object> chatMemoryElement = new HashMap<>();
        chatMemoryElement.put("name", "chatMemory_1");
        chatMemoryElement.put("type", "testComponent/v1/testChatMemory");
        chatMemoryElement.put("parameters", chatMemoryParameters);

        Parameters extensions = MockParametersFactory.create(
            Map.of("clusterElements", Map.of("model", modelElement, "chatMemory", chatMemoryElement)));

        stubModelLookup();

        ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);

        MessageChatMemoryAdvisor insideLoopChatMemoryAdvisor = MessageChatMemoryAdvisor
            .builder(mock(ChatMemory.class))
            .order(ChatMemoryFunction.TOOL_MESSAGE_PERSISTENCE_ADVISOR_ORDER)
            .build();

        when(chatMemoryFunction.apply(any(), any(), any(), any(), any()))
            .thenReturn(
                ChatMemoryFunction.Result.persistingToolMessages(
                    insideLoopChatMemoryAdvisor, conversationId -> List.of(), List.of()));

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);
        Map<String, ComponentConnection> connectionParameters = Map.of(
            "model_1", componentConnection,
            "chatMemory_1", componentConnection);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            ChatClient.ChatClientRequestSpec spec = action.getChatClientRequestSpec(
                inputParameters, connectionParameters, extensions, null, actionContext);

            List<Advisor> advisors = ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getAdvisors();

            ToolCallingAdvisor toolCallAdvisor = findToolCallAdvisor(advisors);

            Advisor chatMemoryAdvisor = advisors.stream()
                .filter(advisor -> advisor instanceof BaseChatMemoryAdvisor)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ChatMemoryAdvisor missing from advisor chain"));

            assertThat(chatMemoryAdvisor.getOrder())
                .as(
                    "A memory type declaring supportsToolMessagePersistence must run inside the tool loop " +
                        "(order > ToolCallingAdvisor.DEFAULT_ORDER) so it captures the full tool transcript.")
                .isGreaterThan(toolCallAdvisor.getOrder());

            assertThat(readConversationHistoryEnabled(toolCallAdvisor))
                .as(
                    "When memory persists tool messages inside the loop, ToolCallingAdvisor.disableInternal" +
                        "ConversationHistory() must be applied so the transcript is not written twice.")
                .isFalse();
        }
    }

    @Test
    void testSupportingChatMemoryPersistsOneToolTranscriptDuringAToolLoop() throws Exception {
        SessionService sessionService = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder()
                .build())
            .build();

        SessionMemoryAdvisor sessionMemoryAdvisor = SessionMemoryAdvisor.builder(sessionService)
            .defaultUserId("user-1")
            .order(ChatMemoryFunction.TOOL_MESSAGE_PERSISTENCE_ADVISOR_ORDER)
            .build();

        ToolLoopChatModel toolLoopChatModel = new ToolLoopChatModel();

        ChatResponse chatResponse = runToolLoop(
            toolLoopChatModel,
            ChatMemoryFunction.Result.persistingToolMessages(
                sessionMemoryAdvisor, new SessionConversationHistoryReader(sessionService),
                List.of(echoToolCallback())));

        assertThat(Objects.requireNonNull(chatResponse.getResult())
            .getOutput()
            .getText()).isEqualTo("done");

        List<Message> persistedMessages = sessionService.getMessages(TOOL_LOOP_CONVERSATION_ID);

        assertThat(persistedMessages)
            .as("a supporting memory must persist the tool response exactly once")
            .filteredOn(ToolResponseMessage.class::isInstance)
            .hasSize(1);
        assertThat(persistedMessages)
            .as("a supporting memory must persist the tool call exactly once")
            .filteredOn(AbstractAiAgentChatActionTest::isToolCallMessage)
            .hasSize(1);

        assertSingleToolTranscriptSentToModel(toolLoopChatModel.getPrompts());
    }

    @Test
    void testResumedTurnWithSupportingChatMemoryKeepsTheToolTranscriptComplete() throws Exception {
        SessionService sessionService = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder()
                .build())
            .build();

        UserMessage userMessage = new UserMessage("ask me");
        AssistantMessage questionToolCallMessage = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "askUserQuestion", "{}")))
            .build();

        sessionService.create(CreateSessionRequest.builder()
            .id(TOOL_LOOP_CONVERSATION_ID)
            .userId("user-1")
            .build());
        sessionService.appendEvent(SessionEvent.builder()
            .sessionId(TOOL_LOOP_CONVERSATION_ID)
            .message(userMessage)
            .build());
        sessionService.appendEvent(SessionEvent.builder()
            .sessionId(TOOL_LOOP_CONVERSATION_ID)
            .message(questionToolCallMessage)
            .build());

        SessionMemoryAdvisor sessionMemoryAdvisor = SessionMemoryAdvisor.builder(sessionService)
            .defaultUserId("user-1")
            .order(ChatMemoryFunction.TOOL_MESSAGE_PERSISTENCE_ADVISOR_ORDER)
            .build();

        ResumedToolLoopChatModel resumedToolLoopChatModel = new ResumedToolLoopChatModel();

        List<Message> resumedConversation = List.of(
            userMessage, questionToolCallMessage,
            ToolResponseMessage.builder()
                .responses(
                    List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "askUserQuestion", "{\"answer\":\"blue\"}")))
                .build());

        ChatResponse chatResponse = runToolLoop(
            resumedToolLoopChatModel,
            ChatMemoryFunction.Result.persistingToolMessages(
                sessionMemoryAdvisor, new SessionConversationHistoryReader(sessionService),
                List.of(echoToolCallback())),
            resumedConversation, true);

        assertThat(Objects.requireNonNull(chatResponse.getResult())
            .getOutput()
            .getText()).isEqualTo("done");

        List<Prompt> prompts = resumedToolLoopChatModel.getPrompts();

        assertThat(prompts)
            .as("the resumed turn calls one more tool, then answers")
            .hasSize(2);

        for (Prompt prompt : prompts) {
            List<Message> instructions = prompt.getInstructions();

            assertThat(instructions)
                .as("every model call of the resumed turn carries the user turn exactly once")
                .filteredOn(UserMessage.class::isInstance)
                .hasSize(1);
            assertThat(instructions)
                .as("every tool call sent to the model has its tool response")
                .filteredOn(AbstractAiAgentChatActionTest::isToolCallMessage)
                .hasSameSizeAs(
                    instructions.stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .toList());
        }

        assertThat(prompts.get(1)
            .getInstructions())
                .as("the second model call carries both tool exchanges")
                .filteredOn(ToolResponseMessage.class::isInstance)
                .hasSize(2);

        List<Message> persistedMessages = sessionService.getMessages(TOOL_LOOP_CONVERSATION_ID);

        assertThat(persistedMessages)
            .as("the session keeps the user turn once")
            .filteredOn(UserMessage.class::isInstance)
            .hasSize(1);
        assertThat(persistedMessages)
            .as("the session keeps both tool calls once")
            .filteredOn(AbstractAiAgentChatActionTest::isToolCallMessage)
            .hasSize(2);
        assertThat(persistedMessages)
            .as("the session keeps the answer to the question and the second tool response")
            .filteredOn(ToolResponseMessage.class::isInstance)
            .hasSize(2);
        assertThat(persistedMessages.getLast()
            .getText()).isEqualTo("done");
    }

    @Test
    void testNonSupportingChatMemoryKeepsTheToolTranscriptOutsideMemory() throws Exception {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .build();

        ToolLoopChatModel toolLoopChatModel = new ToolLoopChatModel();

        runToolLoop(
            toolLoopChatModel,
            new ChatMemoryFunction.Result(
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .build(),
                chatMemory::get, List.of(echoToolCallback()), false));

        assertThat(chatMemory.get(TOOL_LOOP_CONVERSATION_ID))
            .as("an outside-the-loop memory records only the user/assistant exchange")
            .noneMatch(ToolResponseMessage.class::isInstance);

        assertSingleToolTranscriptSentToModel(toolLoopChatModel.getPrompts());
    }

    @Test
    void testToolMessagePersistenceAdvisorOrderIsInsideToolLoop() {
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .build();

        assertThat(ChatMemoryFunction.TOOL_MESSAGE_PERSISTENCE_ADVISOR_ORDER)
            .as("TOOL_MESSAGE_PERSISTENCE_ADVISOR_ORDER must be greater than ToolCallingAdvisor.DEFAULT_ORDER")
            .isGreaterThan(toolCallingAdvisor.getOrder());
    }

    @Test
    void testSpringAiDefaultOrdersComposeCorrectly() {
        // Safety net for Spring AI version bumps. Our chat-memory components and AbstractAiAgentChatAction
        // both rely on Spring AI's builder defaults (no explicit .order(...) / .advisorOrder(...) calls).
        // As of 2.0.0-RC1 those defaults place ChatMemoryAdvisor (MIN+200) upstream of ToolCallingAdvisor
        // (MIN+300). If a future bump changes this relationship, the regression surfaces at the
        // dependency-bump PR rather than in production.
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .build();

        MessageChatMemoryAdvisor chatMemoryAdvisor = MessageChatMemoryAdvisor
            .builder(mock(ChatMemory.class))
            .build();

        assertThat(chatMemoryAdvisor.getOrder())
            .as("Spring AI default ordering must keep ChatMemoryAdvisor upstream of ToolCallingAdvisor")
            .isLessThan(toolCallingAdvisor.getOrder());
    }

    @Test
    void testStructuredOutputAdvisorsOrderedInsideToolCallingAdvisor() {
        // JsonSchemaValidationAdvisor must sit inside ToolCallingAdvisor so a validation retry repeats only the final
        // answer, never the tool calls, and CodeFenceStrippingAdvisor must sit inside the validation so every attempt
        // is unwrapped before it is validated.
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .build();

        JsonSchemaValidationAdvisor jsonSchemaValidationAdvisor = new JsonSchemaValidationAdvisor(
            "{\"type\":\"object\"}");

        assertThat(toolCallingAdvisor.getOrder()).isLessThan(jsonSchemaValidationAdvisor.getOrder());
        assertThat(jsonSchemaValidationAdvisor.getOrder()).isLessThan(new CodeFenceStrippingAdvisor().getOrder());
    }

    @Test
    void testApplyStructuredOutputValidationAddsAdvisorForJsonResponseFormat() {
        Parameters inputParameters = MockParametersFactory.create(
            Map.of(
                "response",
                Map.of(
                    "responseFormat", "JSON",
                    "responseSchema", "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}")));

        ActionContext context = mock(ActionContext.class);
        Context.Json json = mock(Context.Json.class);

        Map<String, Object> schemaMap = new LinkedHashMap<>();

        schemaMap.put("type", "object");
        schemaMap.put("properties", Map.of("answer", Map.of("type", "string")));

        when(json.readMap(anyString(), eq(Object.class))).thenReturn(schemaMap);
        when(json.write(any())).thenReturn(
            "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}," +
                "\"required\":[\"answer\"],\"additionalProperties\":false}");
        when(context.json(any())).thenAnswer(invocation -> {
            Context.ContextFunction<Context.Json, Object> contextFunction = invocation.getArgument(0);

            return contextFunction.apply(json);
        });

        ChatClient.ChatClientRequestSpec chatClientRequestSpec = ChatClient.builder(mock(ChatModel.class))
            .build()
            .prompt();

        AbstractAiAgentChatAction.applyStructuredOutputValidation(chatClientRequestSpec, inputParameters, context);

        List<Advisor> advisors =
            ((DefaultChatClient.DefaultChatClientRequestSpec) chatClientRequestSpec).getAdvisors();

        assertThat(advisors).anyMatch(JsonSchemaValidationAdvisor.class::isInstance);
        assertThat(advisors).anyMatch(CodeFenceStrippingAdvisor.class::isInstance);
    }

    @Test
    void testApplyStructuredOutputValidationSkipsAdvisorForTextResponseFormat() {
        Parameters inputParameters = MockParametersFactory.create(
            Map.of("response", Map.of("responseFormat", "TEXT")));

        ActionContext context = mock(ActionContext.class);

        ChatClient.ChatClientRequestSpec chatClientRequestSpec = ChatClient.builder(mock(ChatModel.class))
            .build()
            .prompt();

        AbstractAiAgentChatAction.applyStructuredOutputValidation(chatClientRequestSpec, inputParameters, context);

        List<Advisor> advisors =
            ((DefaultChatClient.DefaultChatClientRequestSpec) chatClientRequestSpec).getAdvisors();

        assertThat(advisors).noneMatch(JsonSchemaValidationAdvisor.class::isInstance);
        assertThat(advisors).noneMatch(CodeFenceStrippingAdvisor.class::isInstance);

        // No JSON schema is read for a TEXT response, so the converter (and therefore context.json) is never touched.
        verifyNoInteractions(context);
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

    @Test
    void testGetAdvisorsIncludesToolCallAdvisorWithDefaultConversationHistoryWhenNoChatMemory() {
        ClusterElementMap clusterElementMap = ClusterElementMap.of(
            Map.of("clusterElements", Map.of("model", buildModelClusterElement())));

        ChatModel chatModel = mock(ChatModel.class);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        List<Advisor> advisors = action.getAdvisors(
            clusterElementMap, Map.of(), chatModel, actionContext, Optional.empty(), null);

        ToolCallingAdvisor toolCallAdvisor = findToolCallAdvisor(advisors);

        assertThat(toolCallAdvisor).isNotNull();
        assertThat(advisors).noneMatch(BaseChatMemoryAdvisor.class::isInstance);
        assertThat(advisors).noneMatch(BlankReplySkippingChatMemoryAdvisor.class::isInstance);
        assertThat(readConversationHistoryEnabled(toolCallAdvisor)).isTrue();
    }

    /**
     * Regression coverage for the defect where the canvas AI Agent surface's tool-boundary metrics
     * ({@code tool_args_restored}/{@code token_unresolved}/{@code tool_result_tokenized}) never fired: previously
     * {@code AgentToolCallingManagers} resolved its {@code SensitiveDataMetrics} from an injected
     * {@code ObjectProvider<SensitiveDataMetrics>}, which either found no bean (the production implementation is gated
     * behind the AI gateway) or found the gateway's own instance mislabelled {@code surface=gateway}. This test
     * exercises the real production chain -- {@code getAdvisors} builds the exact {@code ToolCallingAdvisor} used at
     * runtime, wrapping {@code SuspendableToolCallingManager} around whatever {@code AgentToolCallingManagers} hands
     * back -- and proves that whatever {@link SensitiveDataMetrics} instance is passed into {@code getAdvisors}
     * actually reaches {@code PiiTokenBoundaryToolCallingManager} at the bottom of that chain and gets its
     * {@code tool_args_restored} event recorded, not silently dropped somewhere in between.
     */
    @Test
    void testGetAdvisorsThreadsToolBoundaryMetricsIntoTheToolCallingManager() throws Exception {
        ClusterElementMap clusterElementMap = ClusterElementMap.of(
            Map.of("clusterElements", Map.of("model", buildModelClusterElement())));

        ChatModel chatModel = mock(ChatModel.class);

        ActionContextAware actionContext = mock(ActionContextAware.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        when(toolCallingManager.executeToolCalls(any(), any()))
            .thenReturn(ToolExecutionResult.builder()
                .conversationHistory(List.of())
                .build());

        SensitiveDataMetrics sensitiveDataMetrics = mock(SensitiveDataMetrics.class);

        List<Advisor> advisors = action.getAdvisors(
            clusterElementMap, Map.of(), chatModel, actionContext, Optional.empty(), null, false, null, null,
            sensitiveDataMetrics);

        ToolCallingAdvisor toolCallAdvisor = findToolCallAdvisor(advisors);
        ToolCallingManager wiredToolCallingManager = readToolCallingManager(toolCallAdvisor);

        PiiTokenSession session = PiiTokenSession.create();
        String token = session.tokenFor("EMAIL_ADDRESS", "bob@acme.io");

        wiredToolCallingManager.executeToolCalls(
            promptWithSession(session), chatResponseWithToolCall("sendEmail", "{\"to\":\"" + token + "\"}"));

        verify(sensitiveDataMetrics).recordToolArgsRestored();
    }

    @Test
    void testGetAdvisorsAddsChatMemoryBeforeToolCallAdvisor() throws Exception {
        Map<String, Object> chatMemoryElement = new HashMap<>();

        chatMemoryElement.put("name", "memory_1");
        chatMemoryElement.put("type", "memoryComponent/v1/memoryElement");
        chatMemoryElement.put("parameters", Map.of());

        ClusterElementMap clusterElementMap = ClusterElementMap.of(
            Map.of(
                "clusterElements",
                Map.of("model", buildModelClusterElement(), "chatMemory", chatMemoryElement)));

        BaseChatMemoryAdvisor chatMemoryAdvisor = mock(BaseChatMemoryAdvisor.class);

        Optional<ChatMemoryFunction.Result> chatMemoryResult =
            Optional.of(ChatMemoryFunction.Result.of(chatMemoryAdvisor, null));

        ComponentConnection memoryConnection = new ComponentConnection(
            "memoryComponent", 1, 2L, Map.of(), null);

        Map<String, ComponentConnection> connectionParameters = Map.of("memory_1", memoryConnection);
        ActionContextAware actionContext = mock(ActionContextAware.class);

        ChatModel chatModel = mock(ChatModel.class);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        List<Advisor> advisors = action.getAdvisors(
            clusterElementMap, connectionParameters, chatModel, actionContext, chatMemoryResult, null);

        Advisor wrappedChatMemoryAdvisor = advisors.stream()
            .filter(BlankReplySkippingChatMemoryAdvisor.class::isInstance)
            .findFirst()
            .orElseThrow(() -> new AssertionError("ChatMemoryAdvisor missing from advisor chain"));

        int chatMemoryIndex = advisors.indexOf(wrappedChatMemoryAdvisor);
        ToolCallingAdvisor toolCallAdvisor = findToolCallAdvisor(advisors);
        int toolCallIndex = advisors.indexOf(toolCallAdvisor);

        assertThat(chatMemoryIndex).isGreaterThanOrEqualTo(0);
        assertThat(toolCallIndex).isGreaterThan(chatMemoryIndex);

        // With ChatMemoryAdvisor now upstream of the tool loop (RC1 defaults), ToolCallingAdvisor keeps its
        // internal in-loop history enabled — the former disableInternalConversationHistory() workaround is gone.
        assertThat(readConversationHistoryEnabled(toolCallAdvisor)).isTrue();
    }

    @Test
    void testAiAgentToolGetsConversationScopedUnderTheParentConversation() throws Exception {
        buildChatClientRequestSpec(
            Map.of(), "parent-conversation", buildToolClusterElement("aiAgent_2", "aiAgent/v1/aiAgent"));

        assertThat(captureSubagentConversationId()).isEqualTo("parent-conversation:aiAgent_2");

        ClusterElement toolClusterElement = captureToolClusterElement();

        Map<String, Object> toolParameters = new HashMap<>(toolClusterElement.getParameters());

        assertThat(toolParameters)
            .doesNotContainKey(SUBAGENT_CONVERSATION_ID)
            .containsEntry("prompt", "Research the topic");
    }

    @Test
    void testParentConversationIdContainingFromAiReachesTheSubagentOutsideTheToolParameters() throws Exception {
        String parentConversationId = "fromAi('x') != null ? 'a' : 'b'";

        buildChatClientRequestSpec(
            Map.of(), parentConversationId, buildToolClusterElement("aiAgent_2", "aiAgent/v1/aiAgent"));

        assertThat(captureSubagentConversationId()).isEqualTo(parentConversationId + ":aiAgent_2");

        ClusterElement toolClusterElement = captureToolClusterElement();

        assertThat(toolClusterElement.getParameters()).doesNotContainKey(SUBAGENT_CONVERSATION_ID);
    }

    @Test
    void testOtherMultipleConnectionsToolsDoNotGetASubagentConversation() throws Exception {
        buildChatClientRequestSpec(
            Map.of(), "parent-conversation", buildToolClusterElement("otherTool_1", "otherComponent/v1/otherTool"));

        assertThat(captureSubagentConversationId()).isNull();

        ClusterElement toolClusterElement = captureToolClusterElement();

        assertThat(toolClusterElement.getParameters()).doesNotContainKey(SUBAGENT_CONVERSATION_ID);
    }

    @Test
    void testAiAgentToolKeepsItsOwnConversationWhenTheParentHasNoChatMemory() throws Exception {
        buildChatClientRequestSpec(Map.of(), null, buildToolClusterElement("aiAgent_2", "aiAgent/v1/aiAgent"));

        assertThat(captureSubagentConversationId()).isNull();

        ClusterElement toolClusterElement = captureToolClusterElement();

        assertThat(toolClusterElement.getParameters()).doesNotContainKey(SUBAGENT_CONVERSATION_ID);
    }

    @Test
    void testSubagentConversationReplacesTheChatMemoryConversation() throws Exception {
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = buildChatClientRequestSpec(
            Map.of(SUBAGENT_CONVERSATION_ID, "parent-conversation:aiAgent_2"), "own-conversation", null);

        DefaultChatClient.DefaultChatClientRequestSpec defaultChatClientRequestSpec =
            (DefaultChatClient.DefaultChatClientRequestSpec) chatClientRequestSpec;

        assertThat(defaultChatClientRequestSpec.getAdvisorParams())
            .containsEntry(ChatMemory.CONVERSATION_ID, "parent-conversation:aiAgent_2");
    }

    @Test
    void testChatMemoryWithoutToolCallbacksKeepsTheConversationOutOfTheToolContext() throws Exception {
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = buildChatClientRequestSpec(
            Map.of(), "own-conversation", buildToolClusterElement("otherTool_1", "otherComponent/v1/otherTool"));

        DefaultChatClient.DefaultChatClientRequestSpec defaultChatClientRequestSpec =
            (DefaultChatClient.DefaultChatClientRequestSpec) chatClientRequestSpec;

        assertThat(defaultChatClientRequestSpec.getAdvisorParams())
            .containsEntry(ChatMemory.CONVERSATION_ID, "own-conversation");
        assertThat(defaultChatClientRequestSpec.getToolContext()).isEmpty();
    }

    @Test
    void testChatMemoryToolCallbacksGetTheConversationInTheToolContext() throws Exception {
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = buildChatClientRequestSpec(
            Map.of(SUBAGENT_CONVERSATION_ID, "parent-conversation:aiAgent_2"), "own-conversation", null,
            List.of(echoToolCallback()));

        DefaultChatClient.DefaultChatClientRequestSpec defaultChatClientRequestSpec =
            (DefaultChatClient.DefaultChatClientRequestSpec) chatClientRequestSpec;

        assertThat(defaultChatClientRequestSpec.getToolContext())
            .containsExactly(Map.entry(ChatMemory.CONVERSATION_ID, "parent-conversation:aiAgent_2"));
    }

    private ChatClient.ChatClientRequestSpec buildChatClientRequestSpec(
        Map<String, Object> inputParameterValues, String chatMemoryConversationId, Map<String, Object> toolElement)
        throws Exception {

        return buildChatClientRequestSpec(inputParameterValues, chatMemoryConversationId, toolElement, List.of());
    }

    private ChatClient.ChatClientRequestSpec buildChatClientRequestSpec(
        Map<String, Object> inputParameterValues, String chatMemoryConversationId, Map<String, Object> toolElement,
        List<ToolCallback> chatMemoryToolCallbacks) throws Exception {

        Map<String, Object> clusterElements = new HashMap<>();

        clusterElements.put("model", buildModelClusterElement());

        stubModelLookup();

        if (chatMemoryConversationId != null) {
            Map<String, Object> chatMemoryElement = new HashMap<>();

            chatMemoryElement.put("name", "chatMemory_1");
            chatMemoryElement.put("type", "testComponent/v1/testChatMemory");
            chatMemoryElement.put("parameters", Map.of("conversationId", chatMemoryConversationId));

            clusterElements.put("chatMemory", chatMemoryElement);

            ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

            when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
                eq("testComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);

            ChatMemoryFunction.Result chatMemoryResult;

            if (chatMemoryToolCallbacks.isEmpty()) {
                chatMemoryResult = ChatMemoryFunction.Result.of(mock(BaseChatMemoryAdvisor.class), null);
            } else {
                BaseChatMemoryAdvisor chatMemoryAdvisor = mock(BaseChatMemoryAdvisor.class);

                when(chatMemoryAdvisor.getOrder())
                    .thenReturn(ChatMemoryFunction.TOOL_MESSAGE_PERSISTENCE_ADVISOR_ORDER);

                chatMemoryResult = ChatMemoryFunction.Result.persistingToolMessages(
                    chatMemoryAdvisor, conversationId -> List.of(), chatMemoryToolCallbacks);
            }

            when(chatMemoryFunction.apply(any(), any(), any(), any(), any())).thenReturn(chatMemoryResult);
        }

        if (toolElement != null) {
            clusterElements.put("tools", List.of(toolElement));

            String[] typeSegments = ((String) toolElement.get("type")).split("/");

            when(clusterElementDefinitionService.<MultipleConnectionsToolFunction>getClusterElement(
                eq(typeSegments[0]), eq(1), eq(typeSegments[2])))
                    .thenReturn(mock(MultipleConnectionsToolFunction.class));
            when(aiAgentToolFacade.getFunctionToolCallback(any(ClusterElement.class), anyMap(), any(), any()))
                .thenReturn(echoToolCallback());
        }

        Map<String, ComponentConnection> connectionParameters = Map.of(
            "model_1", new ComponentConnection("testComponent", 1, 1L, Map.of(), null));

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            return action.getChatClientRequestSpec(
                MockParametersFactory.create(inputParameterValues), connectionParameters,
                MockParametersFactory.create(Map.of("clusterElements", clusterElements)), null,
                mock(ActionContext.class));
        }
    }

    private ClusterElement captureToolClusterElement() {
        ArgumentCaptor<ClusterElement> clusterElementArgumentCaptor = ArgumentCaptor.forClass(ClusterElement.class);

        verify(aiAgentToolFacade).getFunctionToolCallback(
            clusterElementArgumentCaptor.capture(), anyMap(), any(), any());

        return clusterElementArgumentCaptor.getValue();
    }

    private String captureSubagentConversationId() {
        ArgumentCaptor<String> subagentConversationIdArgumentCaptor = ArgumentCaptor.forClass(String.class);

        verify(aiAgentToolFacade).getFunctionToolCallback(
            any(ClusterElement.class), anyMap(), subagentConversationIdArgumentCaptor.capture(), any());

        return subagentConversationIdArgumentCaptor.getValue();
    }

    private static Map<String, Object> buildToolClusterElement(String workflowNodeName, String type) {
        Map<String, Object> toolElement = new HashMap<>();

        toolElement.put("name", workflowNodeName);
        toolElement.put("type", type);
        toolElement.put("parameters", Map.of("prompt", "Research the topic"));

        return toolElement;
    }

    private static Map<String, Object> buildModelClusterElement() {
        Map<String, Object> modelElement = new HashMap<>();

        modelElement.put("name", "model_1");
        modelElement.put("type", "testComponent/v1/testModel");
        modelElement.put("parameters", Map.of());

        return modelElement;
    }

    private static Map<String, Object> buildGuardrailElement(String workflowNodeName, String type) {
        Map<String, Object> element = new HashMap<>();
        element.put("name", workflowNodeName);
        element.put("type", type);
        element.put("parameters", new HashMap<>());

        return element;
    }

    private ChatModel stubModelLookup() throws Exception {
        ModelFunction modelFunction = mock(ModelFunction.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);
        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);

        return chatModel;
    }

    private ChatResponse runToolLoop(ChatModel chatModel, ChatMemoryFunction.Result chatMemoryResult)
        throws Exception {

        return runToolLoop(chatModel, chatMemoryResult, List.of(new UserMessage("run the tool")), false);
    }

    private ChatResponse runToolLoop(
        ChatModel chatModel, ChatMemoryFunction.Result chatMemoryResult, List<Message> messages,
        boolean resumedTurn) throws Exception {

        ModelFunction modelFunction = mock(ModelFunction.class);
        ChatMemoryFunction chatMemoryFunction = mock(ChatMemoryFunction.class);

        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);
        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);
        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);
        when(chatMemoryFunction.apply(any(), any(), any(), any(), any())).thenReturn(chatMemoryResult);

        Parameters extensions = MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", buildModelElement(),
                    "chatMemory",
                    Map.of(
                        "name", "chatMemory_1",
                        "type", "testComponent/v1/testChatMemory",
                        "parameters", Map.of("conversationId", TOOL_LOOP_CONVERSATION_ID)))));

        ComponentConnection componentConnection = new ComponentConnection(
            "testComponent", 1, 1L, Map.of(), null);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, ToolCallingManager.builder()
                .build());

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(messages);

            ChatClient.ChatClientRequestSpec chatClientRequestSpec = action.getChatClientRequestSpec(
                MockParametersFactory.create(Map.of()),
                Map.of("model_1", componentConnection, "chatMemory_1", componentConnection), extensions, null,
                mock(ActionContextAware.class), messages, resumedTurn);

            return chatClientRequestSpec.call()
                .chatResponse();
        }
    }

    private static void assertSingleToolTranscriptSentToModel(List<Prompt> prompts) {
        assertThat(prompts)
            .as("one tool call then one final answer")
            .hasSize(2);

        Prompt toolLoopPrompt = prompts.get(1);

        List<Message> instructions = toolLoopPrompt.getInstructions();

        assertThat(instructions)
            .as("the second model call must carry the user turn exactly once")
            .filteredOn(UserMessage.class::isInstance)
            .hasSize(1);
        assertThat(instructions)
            .as("the second model call must carry the tool call exactly once")
            .filteredOn(AbstractAiAgentChatActionTest::isToolCallMessage)
            .hasSize(1);
        assertThat(instructions)
            .as("the second model call must carry the tool response exactly once")
            .filteredOn(ToolResponseMessage.class::isInstance)
            .hasSize(1);
    }

    private static boolean isToolCallMessage(Message message) {
        return message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls();
    }

    private static ToolCallback echoToolCallback() {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name("echo")
                    .description("Echoes a fixed value")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                return "echoed";
            }
        };
    }

    private static ToolCallingAdvisor findToolCallAdvisor(List<Advisor> advisors) {
        return advisors.stream()
            .filter(ToolCallingAdvisor.class::isInstance)
            .map(ToolCallingAdvisor.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected ToolCallingAdvisor in advisor list"));
    }

    private static boolean readConversationHistoryEnabled(ToolCallingAdvisor toolCallAdvisor) {
        try {
            Field field = ToolCallingAdvisor.class.getDeclaredField("conversationHistoryEnabled");

            field.setAccessible(true);

            return field.getBoolean(toolCallAdvisor);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                "Unable to read conversationHistoryEnabled from ToolCallingAdvisor — "
                    + "field name changed in Spring AI?",
                exception);
        }
    }

    private static final class ResumedToolLoopChatModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);

            boolean secondToolAnswered = prompt.getInstructions()
                .stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(toolResponseMessage -> toolResponseMessage.getResponses()
                    .stream())
                .anyMatch(toolResponse -> Objects.equals(toolResponse.id(), "call-2"));

            AssistantMessage assistantMessage = secondToolAnswered || prompts.size() >= 3
                ? new AssistantMessage("done")
                : AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-2", "function", "echo", "{}")))
                    .build();

            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder()
                .build();
        }

        List<Prompt> getPrompts() {
            return prompts;
        }
    }

    private static final class ToolLoopChatModel implements ChatModel {

        private static final int MAX_MODEL_CALLS = 5;

        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);

            boolean toolAnswered = prompt.getInstructions()
                .stream()
                .anyMatch(ToolResponseMessage.class::isInstance);

            AssistantMessage assistantMessage = toolAnswered || prompts.size() >= MAX_MODEL_CALLS
                ? new AssistantMessage("done")
                : AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "echo", "{}")))
                    .build();

            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder()
                .build();
        }

        List<Prompt> getPrompts() {
            return prompts;
        }
    }

    @Nested
    @ExtendWith(ObjectMapperSetupExtension.class)
    class ObservableToolCallbackTests {

        @Test
        void testAFailedToolCallIsLoggedAndRethrown() {
            IllegalStateException toolFailure = new IllegalStateException("lookup service unavailable");
            ActionContext context = mock(ActionContext.class);
            Context.Log log = mockLog(context);

            ToolCallback observableToolCallback = AbstractAiAgentChatAction.createObservableToolCallback(
                createFailingToolCallback(toolFailure), new AtomicReference<>(),
                toolExecutionEvent -> {
                    throw new AssertionError("A failed tool call must not be reported as executed");
                },
                context);

            assertThatThrownBy(() -> observableToolCallback.call("{\"customerId\":\"42\"}"))
                .isSameAs(toolFailure);

            ArgumentCaptor<String> messageArgumentCaptor = ArgumentCaptor.forClass(String.class);

            verify(log).error(messageArgumentCaptor.capture(), eq(toolFailure));

            assertThat(messageArgumentCaptor.getValue()).contains(
                "\"error\":\"lookup service unavailable\"", "\"inputs\":{\"customerId\":\"42\"}",
                "\"toolName\":\"lookUp\"");
        }

        @Test
        void testASuspendingToolCallIsNotLoggedAsAFailure() {
            ToolSuspensionException toolSuspensionException = new ToolSuspensionException(
                "The approval request could not be sent", new IllegalStateException("mail server down"));
            ActionContext context = mock(ActionContext.class);

            ToolCallback observableToolCallback = AbstractAiAgentChatAction.createObservableToolCallback(
                createFailingToolCallback(new RuntimeException("wrapped", toolSuspensionException)),
                new AtomicReference<>(), toolExecutionEvent -> {}, context);

            assertThatThrownBy(() -> observableToolCallback.call("{}")).hasCause(toolSuspensionException);

            verify(context, never()).log(any());
        }

        private static ToolCallback createFailingToolCallback(RuntimeException toolFailure) {
            ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                .name("lookUp")
                .description("Looks up a customer")
                .inputSchema("{}")
                .build();

            return new ToolCallback() {

                @Override
                public ToolDefinition getToolDefinition() {
                    return toolDefinition;
                }

                @Override
                public String call(String toolInput) {
                    throw toolFailure;
                }
            };
        }

        private static Context.Log mockLog(ActionContext context) {
            Context.Log log = mock(Context.Log.class);

            doAnswer(invocation -> {
                Context.ContextConsumer<Context.Log> logConsumer = invocation.getArgument(0);

                logConsumer.accept(log);

                return null;
            }).when(context)
                .log(any());

            return log;
        }
    }

    /**
     * Reflects out the {@code protected final ToolCallingManager toolCallingManager} field {@code getAdvisors} wires
     * onto its {@link ToolCallingAdvisor} -- mirrors {@link #readConversationHistoryEnabled}'s technique. This is
     * always a {@code SuspendableToolCallingManager} wrapping whatever {@code AgentToolCallingManagers} handed back
     * (see {@code AbstractAiAgentChatAction#getAdvisors}), so calling {@code executeToolCalls} on the returned instance
     * exercises the exact chain a real agent run does.
     */
    private static ToolCallingManager readToolCallingManager(ToolCallingAdvisor toolCallAdvisor) {
        try {
            Field field = ToolCallingAdvisor.class.getDeclaredField("toolCallingManager");

            field.setAccessible(true);

            return (ToolCallingManager) field.get(toolCallAdvisor);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                "Unable to read toolCallingManager from ToolCallingAdvisor — field name changed in Spring AI?",
                exception);
        }
    }

    private static Prompt promptWithSession(PiiTokenSession session) {
        return new Prompt(
            List.of(),
            ToolCallingChatOptions.builder()
                .toolContext(PiiTokenSessionToolContext.into(Map.of(), session))
                .build());
    }

    private static ChatResponse chatResponseWithToolCall(String toolName, String arguments) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", toolName, arguments);
        AssistantMessage assistantMessage = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall))
            .build();

        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    @Test
    void testCreatePromptForcesTextResponseFormatWhenStreaming() {
        Parameters inputParameters = MockParametersFactory.create(
            Map.of("response", Map.of("responseFormat", "JSON", "responseSchema", "{\"type\":\"object\"}")));

        ActionContext context = mock(ActionContext.class);

        AbstractAiAgentChatAction action = new StreamingTestAiAgentChatAction(
            mock(AiAgentToolFacade.class), mock(ClusterElementDefinitionService.class),
            mock(ToolCallingManager.class));

        action.createPrompt(
            ChatClient.builder(mock(ChatModel.class))
                .build(),
            inputParameters, context);

        // A streaming action forces TEXT, so the JSON schema converter is never built and context.json is untouched —
        // the model is not asked for structured output on a path that cannot validate or self-correct it.
        verifyNoInteractions(context);
    }

    @Test
    void testCreatePromptHonorsJsonResponseFormatWhenNotStreaming() {
        Parameters inputParameters = MockParametersFactory.create(
            Map.of("response", Map.of("responseFormat", "JSON", "responseSchema", "{\"type\":\"object\"}")));

        ActionContext context = mock(ActionContext.class);
        Context.Json json = mock(Context.Json.class);

        when(json.readMap(anyString(), eq(Object.class))).thenReturn(new LinkedHashMap<>(Map.of("type", "object")));
        when(json.write(any())).thenReturn("{\"type\":\"object\"}");
        when(context.json(any())).thenAnswer(invocation -> {
            Context.ContextFunction<Context.Json, Object> contextFunction = invocation.getArgument(0);

            return contextFunction.apply(json);
        });

        AbstractAiAgentChatAction action = new TestAiAgentChatAction(
            mock(AiAgentToolFacade.class), mock(ClusterElementDefinitionService.class),
            mock(ToolCallingManager.class));

        action.createPrompt(
            ChatClient.builder(mock(ChatModel.class))
                .build(),
            inputParameters, context);

        // The non-streaming path builds the JSON schema converter, so the structured-output format instruction is sent.
        verify(context, atLeastOnce()).json(any());
    }

    private static class TestAiAgentChatAction extends AbstractAiAgentChatAction {

        TestAiAgentChatAction(
            AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
            ToolCallingManager toolCallingManager) {

            super(aiAgentToolFacade, clusterElementDefinitionService, new AgentToolCallingManagers(toolCallingManager));
        }
    }

    private static class StreamingTestAiAgentChatAction extends TestAiAgentChatAction {

        StreamingTestAiAgentChatAction(
            AiAgentToolFacade aiAgentToolFacade, ClusterElementDefinitionService clusterElementDefinitionService,
            ToolCallingManager toolCallingManager) {

            super(aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager);
        }

        @Override
        protected boolean isStreaming() {
            return true;
        }
    }

    /**
     * Dedicated coverage for the approval-gate resume branch ({@code resolveGatedToolResumeData}): approval executes
     * the gated tool with the originally captured arguments through the RAW callback, rejection feeds a denial back
     * into the loop without executing anything, a vanished tool fails loudly, and a tool failure after approval is
     * reported as an error result instead of propagating.
     */
    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class ResumeGate {

        private static final String GATED_TOOL_INPUT = "{\"channel\": \"#general\", \"text\": \"hi\"}";
        private static final String GATED_TOOL_NAME = "SLACK_SEND_MESSAGE";

        private static final Parameters NO_SIMULATION_INPUT = ParametersFactory.create(Map.of());

        private static final Parameters EXTENSIONS = ParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "tools",
                    List.of(
                        Map.of(
                            "name", "slack_1",
                            "type", "slack/v1/sendMessage",
                            "parameters", Map.of())))));

        /**
         * The same tool, but nested beneath an approval gate — the shape that composes the gate wrapper with the rule
         * wrapper.
         */
        private static final Parameters GATED_EXTENSIONS = ParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "tools",
                    List.of(
                        Map.of(
                            "name", "approvalGateTool_1",
                            "type", "aiAgentUtils/v1/approvalGateTool",
                            "parameters", Map.of("name", "Destructive"),
                            "clusterElements",
                            Map.of(
                                "tools",
                                List.of(
                                    Map.of(
                                        "name", "slack_1",
                                        "type", "slack/v1/sendMessage",
                                        "parameters", Map.of()))))))));

        private final AiAgentToolFacade aiAgentToolFacade = mock(AiAgentToolFacade.class);
        private final ClusterElementDefinitionService clusterElementDefinitionService = mock(
            ClusterElementDefinitionService.class);
        private final ToolCallback toolCallback = mock(ToolCallback.class);

        private final AbstractAiAgentChatAction action = new AbstractAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService,
            new AgentToolCallingManagers(mock(ToolCallingManager.class))) {};

        private final ActionContextAware context = mock(
            ActionContextAware.class, withSettings().extraInterfaces(ActionContext.class));

        @BeforeEach
        void setUp() {
            when(context.isEditorEnvironment()).thenReturn(false);
            when(clusterElementDefinitionService.getClusterElement("slack", 1, "sendMessage"))
                .thenReturn(mock(MultipleConnectionsToolFunction.class));
            when(toolCallback.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder()
                    .name(GATED_TOOL_NAME)
                    .description("Send a Slack message")
                    .inputSchema("{}")
                    .build());
            when(aiAgentToolFacade.getFunctionToolCallback(any(), anyMap(), any(), any(ActionContext.class)))
                .thenReturn(toolCallback);
        }

        @Test
        void testApprovedExecutesToolWithOriginalArgumentsAndReportsResult() throws Exception {
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            String resumeData = action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(true, "ship it"), Map.of(), EXTENSIONS, context);

            // The RAW callback must execute the originally captured arguments — not whatever the human typed.
            verify(toolCallback).call(eq(GATED_TOOL_INPUT), any(ToolContext.class));

            assertThat(resumeData)
                .contains("approvedByReviewer")
                .contains("message sent: ts=1721")
                .contains("ship it");
        }

        @Test
        void testRejectedFeedsDenialWithCommentWithoutExecutingTheTool() throws Exception {
            String resumeData = action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(false, "not now"), Map.of(), EXTENSIONS, context);

            assertThat(resumeData)
                .contains("denied")
                .contains("Denied by reviewer: not now");

            // Rejection must short-circuit before any tool machinery is touched.
            verifyNoInteractions(aiAgentToolFacade);
            verifyNoInteractions(toolCallback);
        }

        @Test
        void testApprovedCarriesVerifiedReviewerIdentity() throws Exception {
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            String resumeData = action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                dataWithApprovedBy(true, "@jane"), Map.of(), EXTENSIONS, context);

            assertThat(resumeData)
                .contains("approvedByReviewer")
                .contains("\"reviewer\"")
                .contains("@jane");
        }

        @Test
        void testApprovedReExecutionCarriesTheReviewerIntoTheToolContext() throws Exception {
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                dataWithApprovedBy(true, "@jane"), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);

            verify(toolCallback).call(eq(GATED_TOOL_INPUT), toolContextCaptor.capture());

            // The rule layer survives the gate's unwrap, so it re-checks this call; the reviewer is how it knows the
            // approval it required has already happened and must not be raised again.
            Map<String, Object> toolContextMap = toolContextCaptor.getValue()
                .getContext();

            assertThat(toolContextMap).containsEntry(AiAgentToolContextKey.APPROVED_BY, "@jane");
        }

        @Test
        void testApprovedWithNoVerifiedReviewerStillCarriesAnonymousIntoTheToolContext() throws Exception {
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            // The hosted approval form (no verified reviewer) is the common path, reached by the link in every emailed
            // or messaged approval request — not an edge case. Without a non-null marker here, the rule layer's only
            // "already approved" signal is missing, so a REQUIRE_APPROVAL rule would re-raise the same approval on
            // every
            // resume and suspend forever.
            action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(true, null), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);

            verify(toolCallback).call(eq(GATED_TOOL_INPUT), toolContextCaptor.capture());

            Map<String, Object> toolContextMap = toolContextCaptor.getValue()
                .getContext();

            assertThat(toolContextMap).containsEntry(AiAgentToolContextKey.APPROVED_BY, "anonymous");
        }

        @Test
        void testRejectedCarriesVerifiedReviewerIdentity() throws Exception {
            String resumeData = action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                dataWithApprovedBy(false, "@jane"), Map.of(), EXTENSIONS, context);

            assertThat(resumeData)
                .contains("denied")
                .contains("\"deniedBy\"")
                .contains("@jane");

            verifyNoInteractions(toolCallback);
        }

        @Test
        void testApprovedButToolNoLongerConfiguredFailsLoudly() {
            assertThatThrownBy(
                () -> action.resolveGatedToolResumeData(
                    NO_SIMULATION_INPUT, continueParameters("REMOVED_TOOL"),
                    data(true, null), Map.of(), EXTENSIONS, context))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("REMOVED_TOOL");
        }

        @Test
        void testApprovedToolFailureIsReportedAsErrorInsteadOfPropagating() throws Exception {
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class)))
                .thenThrow(new RuntimeException("connection refused"));

            String resumeData = action.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(true, null), Map.of(), EXTENSIONS, context);

            assertThat(resumeData)
                .contains("approvedByReviewer")
                .contains("connection refused")
                .doesNotContain("\"result\"");
        }

        @Test
        @SuppressWarnings("unchecked")
        void testRejectedRecordsApprovalDeniedAuditEvent() throws Exception {
            ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

            AbstractAiAgentChatAction auditedAction = createAuditedAction(toolExecutionRecorder);

            auditedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(false, "not now"), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<com.bytechef.platform.tool.execution.ToolExecutionEvent> eventArgumentCaptor =
                ArgumentCaptor.forClass(com.bytechef.platform.tool.execution.ToolExecutionEvent.class);

            verify(toolExecutionRecorder).record(eventArgumentCaptor.capture());

            com.bytechef.platform.tool.execution.ToolExecutionEvent toolExecutionEvent = eventArgumentCaptor.getValue();

            assertThat(toolExecutionEvent.surface()).isEqualTo(ToolExecutionSurface.AI_AGENT);
            assertThat(toolExecutionEvent.toolName()).isEqualTo(GATED_TOOL_NAME);
            assertThat(toolExecutionEvent.outcome()).isEqualTo(ToolExecutionOutcome.APPROVAL_DENIED);
        }

        @Test
        @SuppressWarnings("unchecked")
        void testApprovedExecutionRunsThroughTheAuditRecorder() throws Exception {
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

            // The recorder wraps the execution — make the mock actually run the supplier so the tool executes.
            when(toolExecutionRecorder.record(
                any(com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder.class), any(Supplier.class)))
                    .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

            AbstractAiAgentChatAction auditedAction = createAuditedAction(toolExecutionRecorder);

            String resumeData = auditedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(true, null), Map.of(), EXTENSIONS, context);

            verify(toolExecutionRecorder).record(
                any(com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder.class), any(Supplier.class));
            verify(toolCallback).call(eq(GATED_TOOL_INPUT), any(ToolContext.class));

            assertThat(resumeData).contains("message sent: ts=1721");
        }

        @Test
        void testRejectedScopesTheApprovalDeniedEventToTheRunEnvironmentAndWorkspace() throws Exception {
            when(context.getEnvironmentId()).thenReturn(2L);
            when(context.getJobPrincipalId()).thenReturn(5L);
            when(context.getPlatformType()).thenReturn(PlatformType.AUTOMATION);

            ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);
            ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver =
                mock(ToolInvocationWorkspaceResolver.class);

            when(toolInvocationWorkspaceResolver.resolve(PlatformType.AUTOMATION, 5L)).thenReturn(9L);

            AbstractAiAgentChatAction auditedAction = createAuditedAction(
                toolExecutionRecorder, toolInvocationWorkspaceResolver);

            auditedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(false, "not now"), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<com.bytechef.platform.tool.execution.ToolExecutionEvent> eventArgumentCaptor =
                ArgumentCaptor.forClass(com.bytechef.platform.tool.execution.ToolExecutionEvent.class);

            verify(toolExecutionRecorder).record(eventArgumentCaptor.capture());

            com.bytechef.platform.tool.execution.ToolExecutionEvent toolExecutionEvent = eventArgumentCaptor.getValue();

            assertThat(toolExecutionEvent.environment()).isEqualTo(2);
            assertThat(toolExecutionEvent.workspaceId()).isEqualTo(9L);
        }

        @Test
        void testRejectedRecordsTheEnvironmentWithoutAWorkspaceWhenNoResolverIsPresent() throws Exception {
            when(context.getEnvironmentId()).thenReturn(1L);
            when(context.getJobPrincipalId()).thenReturn(5L);
            when(context.getPlatformType()).thenReturn(PlatformType.AUTOMATION);

            ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

            AbstractAiAgentChatAction auditedAction = createAuditedAction(toolExecutionRecorder, null);

            auditedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(false, "not now"), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<com.bytechef.platform.tool.execution.ToolExecutionEvent> eventArgumentCaptor =
                ArgumentCaptor.forClass(com.bytechef.platform.tool.execution.ToolExecutionEvent.class);

            verify(toolExecutionRecorder).record(eventArgumentCaptor.capture());

            com.bytechef.platform.tool.execution.ToolExecutionEvent toolExecutionEvent = eventArgumentCaptor.getValue();

            assertThat(toolExecutionEvent.environment()).isEqualTo(1);
            assertThat(toolExecutionEvent.workspaceId()).isNull();
        }

        @Test
        @SuppressWarnings("unchecked")
        void testApprovedExecutionScopesTheAuditEventToTheRunEnvironmentAndWorkspace() throws Exception {
            when(context.getEnvironmentId()).thenReturn(2L);
            when(context.getJobPrincipalId()).thenReturn(5L);
            when(context.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);
            ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver =
                mock(ToolInvocationWorkspaceResolver.class);

            when(toolInvocationWorkspaceResolver.resolve(PlatformType.AUTOMATION, 5L)).thenReturn(9L);
            when(toolExecutionRecorder.record(
                any(com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder.class), any(Supplier.class)))
                    .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

            AbstractAiAgentChatAction auditedAction = createAuditedAction(
                toolExecutionRecorder, toolInvocationWorkspaceResolver);

            auditedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(true, null), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder> builderArgumentCaptor =
                ArgumentCaptor.forClass(
                    com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder.class);

            verify(toolExecutionRecorder).record(builderArgumentCaptor.capture(), any(Supplier.class));

            com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder builder = builderArgumentCaptor.getValue();

            com.bytechef.platform.tool.execution.ToolExecutionEvent toolExecutionEvent = builder.build();

            assertThat(toolExecutionEvent.environment()).isEqualTo(2);
            assertThat(toolExecutionEvent.workspaceId()).isEqualTo(9L);
        }

        @Test
        @SuppressWarnings("unchecked")
        void testApprovedToolStillRunsWhenTheWorkspaceResolverThrows() throws Exception {
            when(context.getEnvironmentId()).thenReturn(2L);
            when(context.getJobPrincipalId()).thenReturn(5L);
            when(context.getPlatformType()).thenReturn(PlatformType.AUTOMATION);
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);
            ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver =
                mock(ToolInvocationWorkspaceResolver.class);

            when(toolInvocationWorkspaceResolver.resolve(PlatformType.AUTOMATION, 5L))
                .thenThrow(new IllegalStateException("workspace lookup failed"));
            when(toolExecutionRecorder.record(
                any(com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder.class), any(Supplier.class)))
                    .thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());

            AbstractAiAgentChatAction auditedAction = createAuditedAction(
                toolExecutionRecorder, toolInvocationWorkspaceResolver);

            String resumeData = auditedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME),
                data(true, null), Map.of(), EXTENSIONS, context);

            ArgumentCaptor<com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder> builderArgumentCaptor =
                ArgumentCaptor.forClass(
                    com.bytechef.platform.tool.execution.ToolExecutionEvent.Builder.class);

            verify(toolExecutionRecorder).record(builderArgumentCaptor.capture(), any(Supplier.class));

            com.bytechef.platform.tool.execution.ToolExecutionEvent toolExecutionEvent =
                builderArgumentCaptor.getValue()
                    .build();

            assertThat(resumeData).contains("message sent: ts=1721");
            assertThat(toolExecutionEvent.environment()).isEqualTo(2);
            assertThat(toolExecutionEvent.workspaceId()).isNull();
        }

        /**
         * The composition the spec asked for and nobody had pinned: a gate over one tool, with rules switched on. The
         * gate is itself a TOOLS element, so its element passes through {@code ClusterElementToolCallbacks.build} a
         * second time carrying the already-governed callbacks it produced. Wrapping those again would leave a rule
         * layer OUTSIDE the gate, where the resume branch's {@code DelegatingToolCallback::unwrap} cannot reach it —
         * the gate would survive, the approved re-execution would raise a second approval, and every approval would
         * repeat forever. So: the delegate runs exactly once, the enforcer sees exactly one check, and nothing suspends
         * again.
         */
        @Test
        void testApprovedResumeOfAGatedToolUnderRulesExecutesOnceWithoutSuspendingAgain() throws Exception {
            when(context.getResumeUrl()).thenReturn("https://example.com/job/resume/abc123");
            when(toolCallback.call(eq(GATED_TOOL_INPUT), any(ToolContext.class))).thenReturn("message sent: ts=1721");

            ComponentRuleEnforcer componentRuleEnforcer = mock(ComponentRuleEnforcer.class);

            when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new ComponentRuleEnforcer.Decision.Allow());

            AiAgentUtilsApprovalGateTool approvalGateTool = new AiAgentUtilsApprovalGateTool(
                new ClusterElementToolCallbacks(
                    aiAgentToolFacade, clusterElementDefinitionService, List.of(componentRuleEnforcer)),
                clusterElementDefinitionService, null, null);

            ClusterElementDefinition<MultipleConnectionsToolCallbackProviderFunction> gateClusterElementDefinition =
                approvalGateTool.clusterElementDefinition;

            when(clusterElementDefinitionService.<MultipleConnectionsToolCallbackProviderFunction>getClusterElement(
                eq("aiAgentUtils"), eq(1), eq("approvalGateTool")))
                    .thenReturn(gateClusterElementDefinition.getElement());

            AbstractAiAgentChatAction governedAction = new AbstractAiAgentChatAction(
                aiAgentToolFacade, clusterElementDefinitionService,
                new AgentToolCallingManagers(mock(ToolCallingManager.class)), null, null, null, null,
                List.of(componentRuleEnforcer)) {};

            String resumeData = governedAction.resolveGatedToolResumeData(
                NO_SIMULATION_INPUT, continueParameters(GATED_TOOL_NAME), data(true, null), Map.of(),
                GATED_EXTENSIONS, context);

            verify(toolCallback).call(eq(GATED_TOOL_INPUT), any(ToolContext.class));
            verify(componentRuleEnforcer).checkBeforeCall(any());
            verify(context, never()).suspend(any());

            assertThat(resumeData)
                .contains("approvedByReviewer")
                .contains("message sent: ts=1721");
        }

        @SuppressWarnings("unchecked")
        private AbstractAiAgentChatAction createAuditedAction(ToolExecutionRecorder toolExecutionRecorder) {
            ObjectProvider<ToolExecutionRecorder> toolExecutionRecorderObjectProvider = mock(ObjectProvider.class);

            when(toolExecutionRecorderObjectProvider.getIfAvailable()).thenReturn(toolExecutionRecorder);

            return new AbstractAiAgentChatAction(
                aiAgentToolFacade, clusterElementDefinitionService,
                new AgentToolCallingManagers(mock(ToolCallingManager.class)), toolExecutionRecorderObjectProvider) {};
        }

        @SuppressWarnings("unchecked")
        private AbstractAiAgentChatAction createAuditedAction(
            ToolExecutionRecorder toolExecutionRecorder,
            @Nullable ToolInvocationWorkspaceResolver toolInvocationWorkspaceResolver) {

            ObjectProvider<ToolExecutionRecorder> toolExecutionRecorderObjectProvider = mock(ObjectProvider.class);
            ObjectProvider<ToolInvocationWorkspaceResolver> toolInvocationWorkspaceResolverObjectProvider = mock(
                ObjectProvider.class);

            when(toolExecutionRecorderObjectProvider.getIfAvailable()).thenReturn(toolExecutionRecorder);
            when(toolInvocationWorkspaceResolverObjectProvider.getIfAvailable()).thenReturn(
                toolInvocationWorkspaceResolver);

            return new AbstractAiAgentChatAction(
                aiAgentToolFacade, clusterElementDefinitionService,
                new AgentToolCallingManagers(mock(ToolCallingManager.class)), toolExecutionRecorderObjectProvider, null,
                null, null, toolInvocationWorkspaceResolverObjectProvider, List.of()) {};
        }

        private static Parameters continueParameters(String gatedToolName) {
            return ParametersFactory.create(
                Map.of(
                    ToolSuspendConstants.GATED_TOOL_NAME, gatedToolName,
                    ToolSuspendConstants.GATED_TOOL_INPUT, GATED_TOOL_INPUT));
        }

        private static Parameters data(boolean approved, String comment) {
            return ParametersFactory.create(
                comment == null ? Map.of("approved", approved) : Map.of("approved", approved, "comment", comment));
        }

        private static Parameters dataWithApprovedBy(boolean approved, String approvedBy) {
            return ParametersFactory.create(Map.of("approved", approved, "approvedBy", approvedBy));
        }
    }
}

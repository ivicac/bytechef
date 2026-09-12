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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bytechef.component.ai.agent.tool.AgentToolCallingManagers;
import com.bytechef.component.ai.llm.facade.AiAgentToolFacade;
import com.bytechef.component.ai.llm.util.ModelUtils;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.test.definition.MockParametersFactory;
import com.bytechef.platform.ai.guardrails.AiGuardrailsAdvisorProvider;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ai.agent.ChatMemoryFunction;
import com.bytechef.platform.component.definition.ai.agent.ModelFunction;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Coverage for the context handed to a {@code CHAT_MEMORY} cluster element by
 * {@link AbstractAiAgentChatAction#getChatClientRequestSpec}.
 *
 * <p>
 * {@link ChatMemoryFunction} is widened by a context-carrying <em>default</em> method rather than by changing its SAM,
 * because ten components implement it and only the vector-store-backed one addresses a store that belongs to an
 * account. A default overload redirects nothing on its own, though: Java resolves overloads by arity, so a
 * four-argument call binds to the abstract method and an override of the five-argument form is never reached. These
 * tests therefore assert which form actually runs — "the overload exists" and "the overload runs" are different claims,
 * and only the second one carries an owner down to the vector store.
 * </p>
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class AbstractAiAgentChatActionChatMemoryContextTest {

    @Mock
    private AiAgentToolFacade aiAgentToolFacade;

    @Mock
    private ClusterElementDefinitionService clusterElementDefinitionService;

    @Mock
    private ToolCallingManager toolCallingManager;

    @Test
    void testChatMemoryIsInvokedThroughTheContextCarryingForm() throws Exception {
        RecordingChatMemoryFunction chatMemoryFunction = new RecordingChatMemoryFunction();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        buildRequestSpec(chatMemoryFunction, actionContext);

        // The whole point of the task: the five-argument form is the one that ran. With the call site left at four
        // arguments this reads [fourArgument] and the assertion fails, which is what makes it a control rather than
        // a restatement of the interface.
        assertThat(chatMemoryFunction.invokedForms).containsExactly("fiveArgument");
    }

    @Test
    void testChatMemoryReceivesTheRunsOwnContext() throws Exception {
        RecordingChatMemoryFunction chatMemoryFunction = new RecordingChatMemoryFunction();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        buildRequestSpec(chatMemoryFunction, actionContext);

        // Not merely non-null: it is the very context the action was invoked with, which is the one an owner-aware
        // implementation can resolve an owner from.
        assertThat(chatMemoryFunction.receivedContext).isSameAs(actionContext);
    }

    @Test
    void testAContextFreeChatMemoryStillRunsThroughTheDefault() throws Exception {
        ContextFreeChatMemoryFunction chatMemoryFunction = new ContextFreeChatMemoryFunction();

        ActionContextAware actionContext = mock(ActionContextAware.class);

        buildRequestSpec(chatMemoryFunction, actionContext);

        // The nine implementations that address a store the vendor configured override only the context-free form.
        // The five-argument call still reaches them, through the interface default, with no change on their side.
        assertThat(chatMemoryFunction.invocationCount).isEqualTo(1);
    }

    private void buildRequestSpec(ChatMemoryFunction chatMemoryFunction, ActionContextAware actionContext)
        throws Exception {

        Parameters inputParameters = MockParametersFactory.create(Map.of());
        Parameters extensions = buildExtensions();

        stubModelLookup();

        when(clusterElementDefinitionService.<ChatMemoryFunction>getClusterElement(
            eq("testMemoryComponent"), eq(1), eq("testChatMemory"))).thenReturn(chatMemoryFunction);

        TestAiAgentChatAction action = new TestAiAgentChatAction(
            aiAgentToolFacade, clusterElementDefinitionService, toolCallingManager, null, emptyProvider());

        try (MockedStatic<ModelUtils> modelUtilsMockedStatic = mockStatic(ModelUtils.class)) {
            modelUtilsMockedStatic.when(() -> ModelUtils.getMessages(any(), any()))
                .thenReturn(List.of());

            ChatClient.ChatClientRequestSpec chatClientRequestSpec = action.getChatClientRequestSpec(
                inputParameters, buildConnectionParameters(), extensions, null, actionContext);

            assertThat(chatClientRequestSpec).isNotNull();
        }
    }

    private static Parameters buildExtensions() {
        return MockParametersFactory.create(
            Map.of(
                "clusterElements",
                Map.of(
                    "model", buildModelElement(),
                    "chatMemory", buildChatMemoryElement())));
    }

    private static Map<String, Object> buildModelElement() {
        Map<String, Object> modelParameters = new HashMap<>();

        modelParameters.put("model", "gpt-4o");

        Map<String, Object> modelElement = new HashMap<>();

        modelElement.put("name", "model_1");
        modelElement.put("type", "testComponent/v1/testModel");
        modelElement.put("parameters", modelParameters);

        return modelElement;
    }

    private static Map<String, Object> buildChatMemoryElement() {
        Map<String, Object> chatMemoryParameters = new HashMap<>();

        chatMemoryParameters.put("conversationId", "conversation-1");

        Map<String, Object> chatMemoryElement = new HashMap<>();

        chatMemoryElement.put("name", "chatMemory_1");
        chatMemoryElement.put("type", "testMemoryComponent/v1/testChatMemory");
        chatMemoryElement.put("parameters", chatMemoryParameters);

        return chatMemoryElement;
    }

    private static Map<String, ComponentConnection> buildConnectionParameters() {
        return Map.of(
            "model_1", new ComponentConnection("testComponent", 1, 1L, Map.of(), null),
            "chatMemory_1", new ComponentConnection("testMemoryComponent", 1, 2L, Map.of(), null));
    }

    private void stubModelLookup() throws Exception {
        ModelFunction modelFunction = mock(ModelFunction.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(clusterElementDefinitionService.<ModelFunction>getClusterElement(
            eq("testComponent"), eq(1), eq("testModel"))).thenReturn(modelFunction);
        when(modelFunction.apply(any(), any(), anyBoolean())).thenAnswer(invocation -> chatModel);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    /**
     * Overrides BOTH forms and records which one ran. A fake that overrode only the five-argument form could not tell
     * an unedited four-argument call site apart from a correct one — it would simply inherit the default and go green.
     */
    private static final class RecordingChatMemoryFunction implements ChatMemoryFunction {

        private final List<String> invokedForms = new ArrayList<>();

        private @Nullable Context receivedContext;

        @Override
        public Result apply(
            Parameters inputParameters, Parameters connectionParameters, Parameters extensions,
            Map<String, ComponentConnection> componentConnections) {

            invokedForms.add("fourArgument");

            return new Result(new NoOpAdvisor(), null);
        }

        @Override
        public Result apply(
            Parameters inputParameters, Parameters connectionParameters, Parameters extensions,
            Map<String, ComponentConnection> componentConnections, Context context) {

            invokedForms.add("fiveArgument");

            receivedContext = context;

            return new Result(new NoOpAdvisor(), null);
        }
    }

    /** Stands in for the nine implementations that never needed a context and were left untouched. */
    private static final class ContextFreeChatMemoryFunction implements ChatMemoryFunction {

        private int invocationCount;

        @Override
        public Result apply(
            Parameters inputParameters, Parameters connectionParameters, Parameters extensions,
            Map<String, ComponentConnection> componentConnections) {

            invocationCount++;

            return new Result(new NoOpAdvisor(), null);
        }
    }

    /** Minimal advisor so the Result is well-formed; the chain is never walked in these tests. */
    private static final class NoOpAdvisor implements BaseAdvisor {

        @Override
        public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
            return chatClientRequest;
        }

        @Override
        public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
            return chatClientResponse;
        }

        @Override
        public int getOrder() {
            return 0;
        }
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
}

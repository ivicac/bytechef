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

package com.bytechef.ee.ai.copilot.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.advisor.CopilotGuardrailsAdvisorFactory;
import com.bytechef.ee.platform.ai.agent.catalog.CatalogChatClientResolver;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.EvaluatorFunctionDefinitionFactory;
import com.bytechef.platform.ai.sensitivedata.SensitiveDataRedactor;
import com.bytechef.platform.configuration.facade.WorkflowNodeOutputFacade;
import com.bytechef.platform.security.web.authentication.AbstractApiKeyAuthenticationToken;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

/**
 * PropertyCopilotFacadeImpl's guard, hasWorkspaceScopeForProject(projectId, 'WORKFLOW_VIEW'), is environment-agnostic
 * (the two-argument overload), so the client-supplied request.environmentId() is never checked. This test pins the
 * execution side via the TEXT mode path: the environment reaching getPreviousWorkflowNodeSampleOutputs must be the
 * confined principal's own, not the request argument.
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class PropertyCopilotGeneratorEnvironmentTest {

    private static final long DEVELOPMENT_ORDINAL = 0L;
    private static final long PRODUCTION_ORDINAL = 2L;

    @Mock
    private ChatModel chatModel;

    @Mock
    private Evaluator evaluator;

    @Mock
    private WorkflowNodeOutputFacade workflowNodeOutputFacade;

    private PropertyCopilotGeneratorImpl propertyCopilotGenerator;

    @BeforeEach
    void setUp() {
        // ChatClient.builder(chatModel) reads the model's default options to seed every request, so a bare mock
        // (getOptions() -> null) NPEs before the prompt is ever built. Real ChatModel implementations return
        // ToolCallingChatOptions here; lenient because a test that never reaches the model never reads it.
        lenient().when(chatModel.getOptions())
            .thenReturn(ToolCallingChatOptions.builder()
                .build());

        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
        ObjectProvider<CatalogChatClientResolver> catalogChatClientResolverProvider = mock(ObjectProvider.class);

        propertyCopilotGenerator = new PropertyCopilotGeneratorImpl(
            chatModel, guardrailsAdvisorFactory(), evaluator, new PropertyCopilotPromptBuilder(),
            List.<EvaluatorFunctionDefinitionFactory>of(), workflowNodeOutputFacade, meterRegistryProvider, "",
            catalogChatClientResolverProvider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testGenerateUsesConfinedPrincipalEnvironmentAtExecution() {
        authenticate(new TestApiKeyAuthenticationToken(PRODUCTION_ORDINAL, user()));

        stubChatModelResponse("a constant value");

        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(Map.of());

        PropertyCopilotRequest request = new PropertyCopilotRequest(
            "prompt", PropertyCopilotMode.TEXT, "workflow-1", "node-1", "path", "STRING", false,
            DEVELOPMENT_ORDINAL);

        propertyCopilotGenerator.generate(request);

        ArgumentCaptor<Long> environmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(workflowNodeOutputFacade).getPreviousWorkflowNodeSampleOutputs(
            eq("workflow-1"), eq("node-1"), environmentIdCaptor.capture());

        assertThat(environmentIdCaptor.getValue()).isEqualTo(PRODUCTION_ORDINAL);
    }

    @Test
    void testGenerateHonoursSessionPrincipalRequestedEnvironment() {
        authenticate(new UsernamePasswordAuthenticationToken("admin@localhost.com", "n/a", List.of()));

        stubChatModelResponse("a constant value");

        when(workflowNodeOutputFacade.getPreviousWorkflowNodeOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(List.of());
        when(workflowNodeOutputFacade.getPreviousWorkflowNodeSampleOutputs(anyString(), anyString(), anyLong()))
            .thenReturn(Map.of());

        PropertyCopilotRequest request = new PropertyCopilotRequest(
            "prompt", PropertyCopilotMode.TEXT, "workflow-1", "node-1", "path", "STRING", false,
            DEVELOPMENT_ORDINAL);

        propertyCopilotGenerator.generate(request);

        ArgumentCaptor<Long> environmentIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(workflowNodeOutputFacade).getPreviousWorkflowNodeSampleOutputs(
            eq("workflow-1"), eq("node-1"), environmentIdCaptor.capture());

        assertThat(environmentIdCaptor.getValue()).isEqualTo(DEVELOPMENT_ORDINAL);
    }

    /**
     * Builds a real {@link ChatResponse} rather than mocking one. Now that the generator calls the model through a
     * guarded {@code ChatClient}, the advisor chain reads response metadata to accumulate token usage across tool-call
     * rounds, and a mocked {@code ChatResponse} returns null for it - real responses never do.
     */
    private void stubChatModelResponse(String text) {
        ChatResponse chatResponse = ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .build();

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext()
            .setAuthentication(authentication);
    }

    private static User user() {
        return new User("connected-user-1", "", List.of());
    }

    private static final class TestApiKeyAuthenticationToken extends AbstractApiKeyAuthenticationToken {

        private TestApiKeyAuthenticationToken(long environmentId, User user) {
            super(environmentId, user);
        }
    }

    /**
     * A real {@link CopilotGuardrailsAdvisorFactory} with no {@code AiGuardrailsAdvisorProvider} available - the CE
     * shape. It attaches only the inert tool-boundary advisor, so these tests drive the real guarded-{@code ChatClient}
     * path the generator now takes while the model underneath is still reached through {@code chatModel.call(Prompt)} -
     * which is what they stub - and no EE guardrails module is needed on this module's test classpath.
     */
    @SuppressWarnings("unchecked")
    private static CopilotGuardrailsAdvisorFactory guardrailsAdvisorFactory() {
        return new CopilotGuardrailsAdvisorFactory(
            mock(ObjectProvider.class), new SensitiveDataRedactor(List.of()));
    }

}

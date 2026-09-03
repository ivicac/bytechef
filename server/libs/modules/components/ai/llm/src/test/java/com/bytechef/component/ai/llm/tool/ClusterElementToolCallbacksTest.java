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

package com.bytechef.component.ai.llm.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.bytechef.component.ai.llm.facade.AiAgentToolFacade;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.platform.component.ComponentConnection;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.component.definition.ai.agent.MultipleConnectionsToolCallbackProviderFunction;
import com.bytechef.platform.component.definition.ai.agent.ToolCallbackProviderFunction;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.Decision;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer.ToolCall;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.configuration.domain.ClusterElement;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class ClusterElementToolCallbacksTest {

    private final AiAgentToolFacade aiAgentToolFacade = mock(AiAgentToolFacade.class);
    private final ClusterElementDefinitionService clusterElementDefinitionService =
        mock(ClusterElementDefinitionService.class);
    private final ClusterElementToolCallbacks clusterElementToolCallbacks =
        new ClusterElementToolCallbacks(aiAgentToolFacade, clusterElementDefinitionService, List.of());

    /**
     * The branch AiAgentUtilsTaskTool omitted. Ten of the thirteen aiAgentUtils tool elements are
     * ToolCallbackProviderFunction, so without this branch a subagent received a facade-built FunctionToolCallback --
     * whose input schema comes from the element's parameters rather than the provider -- instead of the real tools.
     */
    @Test
    void testProviderFunctionElementReturnsTheProvidersCallbacks() {
        ToolCallback providerToolCallback = mock(ToolCallback.class);

        ToolCallbackProviderFunction toolCallbackProviderFunction =
            (inputParameters, connectionParameters, context) -> ToolCallbackProvider.from(providerToolCallback);

        when(clusterElementDefinitionService.getClusterElement(anyString(), anyInt(), anyString()))
            .thenReturn(toolCallbackProviderFunction);

        List<ToolCallback> toolCallbacks = clusterElementToolCallbacks.build(
            clusterElement("aiAgentUtils/v1/grepTool", "grepTool_1"), Map.of(), false, mock(ActionContext.class),
            List.of());

        assertThat(toolCallbacks).containsExactly(providerToolCallback);

        verifyNoInteractions(aiAgentToolFacade);
    }

    @Test
    void testMultipleConnectionsProviderFunctionElementReturnsTheProvidersCallbacks() {
        ToolCallback providerToolCallback = mock(ToolCallback.class);

        MultipleConnectionsToolCallbackProviderFunction providerFunction =
            (
                inputParameters, connectionParameters, extensions, componentConnections,
                context) -> ToolCallbackProvider.from(providerToolCallback);

        when(clusterElementDefinitionService.getClusterElement(anyString(), anyInt(), anyString()))
            .thenReturn(providerFunction);

        List<ToolCallback> toolCallbacks = clusterElementToolCallbacks.build(
            clusterElement("aiAgentUtils/v1/taskTool", "taskTool_1"), Map.of(), false, mock(ActionContext.class),
            List.of());

        assertThat(toolCallbacks).containsExactly(providerToolCallback);

        verifyNoInteractions(aiAgentToolFacade);
    }

    @Test
    void testPlainFunctionElementDelegatesToTheFacade() {
        ToolCallback facadeToolCallback = mock(ToolCallback.class);

        when(clusterElementDefinitionService.getClusterElement(anyString(), anyInt(), anyString()))
            .thenReturn(new Object());
        when(
            aiAgentToolFacade.getFunctionToolCallback(
                any(ClusterElement.class), any(ComponentConnection.class), anyBoolean()))
                    .thenReturn(facadeToolCallback);

        List<ToolCallback> toolCallbacks = clusterElementToolCallbacks.build(
            clusterElement("aiAgentUtils/v1/createAiSkill", "createAiSkill_1"),
            Map.of("createAiSkill_1", mock(ComponentConnection.class)), false, mock(ActionContext.class), List.of());

        assertThat(toolCallbacks).containsExactly(facadeToolCallback);
    }

    /**
     * Pins the branch Task 3's fix round 1 found untested: a non-empty enforcer list must wrap every returned callback,
     * and the connection id it resolves from {@code componentConnections} must be the one a rule condition sees on the
     * {@link ToolCall}.
     */
    @Test
    void testEnforcedElementWrapsEachCallbackAndCarriesTheResolvedConnectionId() {
        ComponentRuleEnforcer componentRuleEnforcer = mock(ComponentRuleEnforcer.class);
        ClusterElementToolCallbacks enforcedClusterElementToolCallbacks = new ClusterElementToolCallbacks(
            aiAgentToolFacade, clusterElementDefinitionService, List.of(componentRuleEnforcer));

        ToolCallback facadeToolCallback = mock(ToolCallback.class);

        when(facadeToolCallback.getToolDefinition()).thenReturn(
            DefaultToolDefinition.builder()
                .name("CREATE_AI_SKILL")
                .description("Create an AI skill")
                .inputSchema("{}")
                .build());

        when(clusterElementDefinitionService.getClusterElement(anyString(), anyInt(), anyString()))
            .thenReturn(new Object());

        ComponentConnection componentConnection = mock(ComponentConnection.class);

        when(componentConnection.connectionId()).thenReturn(42L);
        when(
            aiAgentToolFacade.getFunctionToolCallback(
                any(ClusterElement.class), any(ComponentConnection.class), anyBoolean()))
                    .thenReturn(facadeToolCallback);
        when(componentRuleEnforcer.checkBeforeCall(any())).thenReturn(new Decision.Allow());

        ActionContextAware actionContext = mock(ActionContextAware.class);

        List<ToolCallback> toolCallbacks = enforcedClusterElementToolCallbacks.build(
            clusterElement("aiAgentUtils/v1/createAiSkill", "createAiSkill_1"),
            Map.of("createAiSkill_1", componentConnection), false, actionContext, List.of());

        assertThat(toolCallbacks).hasSize(1);
        assertThat(toolCallbacks.getFirst()).isInstanceOf(RuleEnforcingToolCallback.class);

        toolCallbacks.getFirst()
            .call("{}", null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);

        verify(componentRuleEnforcer).checkBeforeCall(toolCallCaptor.capture());

        assertThat(toolCallCaptor.getValue()
            .connectionId()).isEqualTo(42L);
    }

    /**
     * A provider that throws must fail with a message naming the element, not with the bare IllegalStateException the
     * task tool's copy produced.
     */
    @Test
    void testInitializationFailureNamesTheClusterElement() {
        ToolCallbackProviderFunction toolCallbackProviderFunction = (
            inputParameters, connectionParameters,
            context) -> {
            throw new IllegalArgumentException("boom");
        };

        when(clusterElementDefinitionService.getClusterElement(anyString(), anyInt(), anyString()))
            .thenReturn(toolCallbackProviderFunction);

        ActionContext actionContext = mock(ActionContext.class);

        try {
            clusterElementToolCallbacks.build(
                clusterElement("aiAgentUtils/v1/grepTool", "grepTool_1"), Map.of(), false, actionContext, List.of());

            assertThat(false)
                .as("expected an IllegalStateException")
                .isTrue();
        } catch (IllegalStateException illegalStateException) {
            assertThat(illegalStateException.getMessage()).contains("grepTool", "aiAgentUtils", "boom");
        }
    }

    /**
     * The approval gate declares itself a TOOLS cluster element, so its own element passes through {@code build} a
     * second time carrying callbacks an inner {@code build} call already governed. Wrapping those again would put a
     * rule layer OUTSIDE the gate, which {@code DelegatingToolCallback.unwrap} cannot strip — the gate would survive
     * the resume branch's unwrap and re-raise its approval on every resume, forever.
     */
    @Test
    void testAnAlreadyGovernedCallbackIsNotWrappedASecondTime() {
        ComponentRuleEnforcer componentRuleEnforcer = mock(ComponentRuleEnforcer.class);
        ClusterElementToolCallbacks enforcedClusterElementToolCallbacks = new ClusterElementToolCallbacks(
            aiAgentToolFacade, clusterElementDefinitionService, List.of(componentRuleEnforcer));

        ToolCallback innerToolCallback = mock(ToolCallback.class);

        RuleEnforcingToolCallback ruleEnforcingToolCallback = new RuleEnforcingToolCallback(
            innerToolCallback, clusterElement("slack/v1/sendMessage", "slack_1"), List.of(componentRuleEnforcer), null,
            mock(ActionContextAware.class, withSettings().extraInterfaces(ActionContext.class)),
            List.of(), Map.of(), clusterElementDefinitionService, null);

        // Stands in for the gate: a delegating wrapper sitting over an already-governed callback.
        DelegatingToolCallback delegatingToolCallback = new DelegatingToolCallback() {

            @Override
            public ToolCallback getDelegate() {
                return ruleEnforcingToolCallback;
            }

            @Override
            public ToolDefinition getToolDefinition() {
                return ruleEnforcingToolCallback.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                return ruleEnforcingToolCallback.call(toolInput);
            }
        };

        ToolCallbackProviderFunction toolCallbackProviderFunction =
            (inputParameters, connectionParameters, context) -> (ToolCallbackProvider) () -> new ToolCallback[] {
                delegatingToolCallback
            };

        when(clusterElementDefinitionService.getClusterElement(anyString(), anyInt(), anyString()))
            .thenReturn(toolCallbackProviderFunction);

        List<ToolCallback> toolCallbacks = enforcedClusterElementToolCallbacks.build(
            clusterElement("aiAgentUtils/v1/approvalGateTool", "approvalGateTool_1"), Map.of(), false,
            mock(ActionContext.class), List.of());

        assertThat(toolCallbacks).containsExactly(delegatingToolCallback);
    }

    private static ClusterElement clusterElement(String type, String workflowNodeName) {
        return new ClusterElement(null, null, Map.of(), null, type, Map.of(), workflowNodeName);
    }
}

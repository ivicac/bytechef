/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.componentrule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ai.copilot.tool.context.AgentToolInvocationContext;
import com.bytechef.component.definition.Property.Type;
import com.bytechef.component.definition.RiskLevel;
import com.bytechef.component.definition.ai.agent.BaseToolFunction;
import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.domain.Property;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleToolCallbacksFactoryTest {

    private final ClusterElementDefinitionService clusterElementDefinitionService =
        mock(ClusterElementDefinitionService.class);
    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final Evaluator evaluator = SpelEvaluator.builder()
        .build();
    private final ComponentRuleToolCallbacksFactory factory = new ComponentRuleToolCallbacksFactory(
        componentRuleService, componentDefinitionService, clusterElementDefinitionService, evaluator);

    @Test
    void testReadToolCallbacksAreGroundingOnly() {
        assertThat(toolNames(factory.readToolCallbacks()))
            .containsExactlyInAnyOrder(
                "listComponentRules", "describeComponentToolParameters", "proposeComponentRuleCondition");
    }

    @Test
    void testWriteToolCallbacksAddExactlyOneMutation() {
        assertThat(toolNames(factory.writeToolCallbacks()))
            .containsExactlyInAnyOrder(
                "listComponentRules", "describeComponentToolParameters", "proposeComponentRuleCondition",
                "createComponentRule");
    }

    @Test
    void testProposeReturnsTheConditionWhenItParses() {
        String result = callTool(
            factory.readToolCallbacks(), "proposeComponentRuleCondition",
            """
                {"condition": "contains(inputParameters['channel'], 'C05')", \
                "explanation": "flags the incident channel"}""");

        assertThat(result).contains("\"valid\":true");
        assertThat(result).contains("C05");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testProposeReportsAnInvalidConditionWithoutThrowing() {
        String result = callTool(
            factory.readToolCallbacks(), "proposeComponentRuleCondition",
            """
                {"condition": "inputParameters['channel'].startsWith('C05')", "explanation": "bad"}""");

        assertThat(result).contains("\"valid\":false");
    }

    @Test
    void testProposeReportsAnUnknownFunctionAsInvalid() {
        // Against an empty context, `inputParameters` is an unresolved reference and SpelEvaluator returns the
        // original string rather than throwing, so the misspelled function name is never even reached — the tool
        // must evaluate against a stub context that supplies `inputParameters` (and friends) so resolution proceeds
        // far enough to hit the unknown-function rejection.
        String result = callTool(
            factory.readToolCallbacks(), "proposeComponentRuleCondition",
            """
                {"condition": "frobnicate(inputParameters['channel'], 'C05')", "explanation": "bad"}""");

        assertThat(result).contains("\"valid\":false");
    }

    @Test
    void testCreateComponentRulePersists() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> {
            ComponentRule componentRule = invocation.getArgument(0);

            componentRule.setId(11L);

            return componentRule;
        });

        CreateComponentRuleToolCallback toolCallback = new CreateComponentRuleToolCallback(componentRuleService);

        String result = toolCallback.call(
            """
                {"componentName": "slack", "toolName": "sendMessage", "phase": "BEFORE", \
                "ruleAction": "TAG", "condition": "true", "description": "always"}""",
            toolContext(3L));

        assertThat(result).contains("11");

        verify(componentRuleService).saveComponentRule(any());
    }

    @Test
    void testCreateComponentRuleRejectsBlockInAfterPhaseBeforeReachingTheService() {
        String result = callTool(
            factory.writeToolCallbacks(), "createComponentRule",
            """
                {"componentName": "slack", "phase": "AFTER", "ruleAction": "BLOCK", "condition": "true"}""");

        assertThat(result).containsIgnoringCase("after");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testCreateComponentRuleAcceptsRequireApprovalAndStrict() {
        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> {
            ComponentRule componentRule = invocation.getArgument(0);

            componentRule.setId(5L);

            return componentRule;
        });

        CreateComponentRuleToolCallback toolCallback = new CreateComponentRuleToolCallback(componentRuleService);

        String result = toolCallback.call(
            """
                {"componentName": "slack", "toolName": "sendMessage", "phase": "BEFORE",
                 "ruleAction": "REQUIRE_APPROVAL", "condition": "true", "strict": true}""",
            toolContext(3L));

        assertThat(result).contains("5");

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getToolName()).isEqualTo("sendMessage");
        assertThat(componentRule.getRuleAction()).isEqualTo(RuleAction.REQUIRE_APPROVAL);
        assertThat(componentRule.isStrict()).isTrue();
        assertThat(componentRule.getWorkspaceId()).isEqualTo(3L);
    }

    @Test
    void testCreateComponentRuleRejectsRequireApprovalInTheAfterPhase() {
        CreateComponentRuleToolCallback toolCallback = new CreateComponentRuleToolCallback(componentRuleService);

        String result = toolCallback.call(
            """
                {"componentName": "slack", "phase": "AFTER", "ruleAction": "REQUIRE_APPROVAL",
                 "condition": "true"}""");

        // The model gets a corrigible message in the same turn instead of a stack trace from the service guard.
        assertThat(result).contains("AFTER");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testCreateComponentRuleRejectsAnUnrecognizedPhaseBeforeReachingTheService() {
        String result = callTool(
            factory.writeToolCallbacks(), "createComponentRule",
            """
                {"componentName": "slack", "phase": "FOO", "ruleAction": "TAG", "condition": "true"}""");

        assertThat(result).contains("FOO");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testDescribeComponentToolParametersResolvesTheHighestComponentVersion() {
        ComponentDefinition oldComponentDefinition = componentDefinition("slack", 1);
        ComponentDefinition newComponentDefinition = componentDefinition("slack", 2);

        when(componentDefinitionService.getComponentDefinitions())
            .thenReturn(List.of(oldComponentDefinition, newComponentDefinition));

        ClusterElementDefinition clusterElementDefinition = mock(ClusterElementDefinition.class);

        doReturn(List.of()).when(clusterElementDefinition)
            .getProperties();
        when(clusterElementDefinition.getRiskLevel()).thenReturn(RiskLevel.LOW);
        when(
            clusterElementDefinitionService.getClusterElementDefinition(
                eq("slack"), eq(2), eq("sendMessage"), eq(BaseToolFunction.TOOLS.name())))
                    .thenReturn(clusterElementDefinition);

        String result = callTool(
            factory.readToolCallbacks(), "describeComponentToolParameters",
            """
                {"componentName": "slack", "toolName": "sendMessage"}""");

        assertThat(result).contains("\"riskLevel\":\"LOW\"");
        assertThat(result).contains("\"parameters\":[]");

        verify(clusterElementDefinitionService).getClusterElementDefinition(
            eq("slack"), eq(2), eq("sendMessage"), eq(BaseToolFunction.TOOLS.name()));
    }

    @Test
    void testDescribeComponentToolParametersMapsEachPropertyIncludingANullType() {
        ComponentDefinition componentDefinition = componentDefinition("slack", 1);

        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(componentDefinition));

        Property channelProperty = mock(Property.class);

        when(channelProperty.getName()).thenReturn("channel");
        when(channelProperty.getType()).thenReturn(Type.STRING);
        when(channelProperty.getRequired()).thenReturn(true);
        when(channelProperty.getDescription()).thenReturn("Channel to post to");

        Property mysteryProperty = mock(Property.class);

        when(mysteryProperty.getName()).thenReturn("mystery");
        when(mysteryProperty.getType()).thenReturn(null);
        when(mysteryProperty.getRequired()).thenReturn(false);
        when(mysteryProperty.getDescription()).thenReturn(null);

        ClusterElementDefinition clusterElementDefinition = mock(ClusterElementDefinition.class);

        doReturn(List.of(channelProperty, mysteryProperty)).when(clusterElementDefinition)
            .getProperties();
        when(clusterElementDefinition.getRiskLevel()).thenReturn(RiskLevel.HIGH);
        when(
            clusterElementDefinitionService.getClusterElementDefinition(
                eq("slack"), eq(1), eq("sendMessage"), eq(BaseToolFunction.TOOLS.name())))
                    .thenReturn(clusterElementDefinition);

        String result = callTool(
            factory.readToolCallbacks(), "describeComponentToolParameters",
            """
                {"componentName": "slack", "toolName": "sendMessage"}""");

        assertThat(result).contains("\"riskLevel\":\"HIGH\"");
        assertThat(result).contains("\"name\":\"channel\"");
        assertThat(result).contains("\"type\":\"STRING\"");
        assertThat(result).contains("\"description\":\"Channel to post to\"");

        // The null-typed property must still be PRESENT, not dropped — the exact behaviour the
        // DescribeComponentToolParametersToolCallback#toSummary null guard preserves.
        assertThat(result).contains("\"name\":\"mystery\"");
        assertThat(result).contains("\"type\":null");

        assertThat(countOccurrences(result, "\"name\":")).isEqualTo(2);
    }

    @Test
    void testDescribeComponentToolParametersRejectsAClusterElementThatIsNotATool() {
        ComponentDefinition componentDefinition = componentDefinition("slack", 1);

        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(componentDefinition));

        // The production ClusterElementDefinitionServiceImpl filters by both name AND type: a name that exists
        // only as a non-TOOLS cluster element (a memory, a model, a subagent, ...) throws here rather than
        // returning it. This tool is model-facing with free-text input, unlike the GraphQL path (which only ever
        // sees a tool name authored through a picker filtered to tools), so it must not hand back a confident,
        // wrong description — with a risk level attached — for something that isn't a tool.
        when(
            clusterElementDefinitionService.getClusterElementDefinition(
                eq("slack"), eq(1), eq("chatMemory"), eq(BaseToolFunction.TOOLS.name())))
                    .thenThrow(
                        new IllegalArgumentException(
                            "Cluster element definition chatMemory with type TOOLS not found in component slack"));

        String result = callTool(
            factory.readToolCallbacks(), "describeComponentToolParameters",
            """
                {"componentName": "slack", "toolName": "chatMemory"}""");

        assertThat(result).contains("No tool named 'chatMemory'");

        // The type-unfiltered overload must never be reached — calling it instead of the four-argument one is
        // exactly the regression this test guards against.
        verify(clusterElementDefinitionService, never())
            .getClusterElementDefinition(anyString(), anyInt(), anyString());
    }

    @Test
    void testListComponentRulesRendersTheStoredRules() {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(1L);
        componentRule.setComponentName("slack");
        componentRule.setToolName("sendMessage");
        componentRule.setPhase(RulePhase.BEFORE);
        componentRule.setRuleAction(RuleAction.BLOCK);
        componentRule.setCondition("true");
        componentRule.setEnabled(true);

        when(componentRuleService.getComponentRules()).thenReturn(List.of(componentRule));

        String result = callTool(factory.readToolCallbacks(), "listComponentRules", "{}");

        assertThat(result).contains("slack");
        assertThat(result).contains("sendMessage");
        assertThat(result).contains("BLOCK");
    }

    private static String callTool(List<ToolCallback> toolCallbacks, String toolName, String toolInput) {
        ToolCallback toolCallback = toolCallbacks.stream()
            .filter(candidate -> {
                ToolDefinition toolDefinition = candidate.getToolDefinition();

                return toolName.equals(toolDefinition.name());
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tool named " + toolName));

        return toolCallback.call(toolInput);
    }

    private static ToolContext toolContext(long workspaceId) {
        return new ToolContext(
            AgentToolInvocationContext.builder()
                .workspaceId(workspaceId)
                .build()
                .toToolContext());
    }

    private static List<String> toolNames(List<ToolCallback> toolCallbacks) {
        return toolCallbacks.stream()
            .map(toolCallback -> {
                ToolDefinition toolDefinition = toolCallback.getToolDefinition();

                return toolDefinition.name();
            })
            .toList();
    }

    private static ComponentDefinition componentDefinition(String name, int version) {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn(name);
        when(componentDefinition.getVersion()).thenReturn(version);

        return componentDefinition;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);

        while (index != -1) {
            count++;

            index = haystack.indexOf(needle, index + needle.length());
        }

        return count;
    }
}

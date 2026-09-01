/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool.componentrule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.component.definition.Property.Type;
import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.evaluator.SpelEvaluator;
import com.bytechef.platform.component.domain.ActionDefinition;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.domain.Property;
import com.bytechef.platform.component.service.ActionDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleToolCallbacksFactoryTest {

    private final ActionDefinitionService actionDefinitionService = mock(ActionDefinitionService.class);
    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final Evaluator evaluator = SpelEvaluator.builder()
        .build();
    private final ComponentRuleToolCallbacksFactory factory = new ComponentRuleToolCallbacksFactory(
        componentRuleService, componentDefinitionService, actionDefinitionService, evaluator);

    @Test
    void testReadToolCallbacksAreGroundingOnly() {
        assertThat(toolNames(factory.readToolCallbacks()))
            .containsExactlyInAnyOrder(
                "listComponentRules", "describeComponentActionParameters", "proposeComponentRuleCondition");
    }

    @Test
    void testWriteToolCallbacksAddExactlyOneMutation() {
        assertThat(toolNames(factory.writeToolCallbacks()))
            .containsExactlyInAnyOrder(
                "listComponentRules", "describeComponentActionParameters", "proposeComponentRuleCondition",
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

        String result = callTool(
            factory.writeToolCallbacks(), "createComponentRule",
            """
                {"componentName": "slack", "actionName": "sendMessage", "phase": "BEFORE", \
                "ruleAction": "TAG", "condition": "true", "description": "always"}""");

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
    void testCreateComponentRuleRejectsAnUnrecognizedPhaseBeforeReachingTheService() {
        String result = callTool(
            factory.writeToolCallbacks(), "createComponentRule",
            """
                {"componentName": "slack", "phase": "FOO", "ruleAction": "TAG", "condition": "true"}""");

        assertThat(result).contains("FOO");

        verify(componentRuleService, never()).saveComponentRule(any());
    }

    @Test
    void testDescribeComponentActionParametersResolvesTheHighestComponentVersion() {
        ComponentDefinition oldComponentDefinition = componentDefinition("slack", 1);
        ComponentDefinition newComponentDefinition = componentDefinition("slack", 2);

        when(componentDefinitionService.getComponentDefinitions())
            .thenReturn(List.of(oldComponentDefinition, newComponentDefinition));

        ActionDefinition actionDefinition = mock(ActionDefinition.class);

        doReturn(List.of()).when(actionDefinition)
            .getProperties();
        when(actionDefinitionService.getActionDefinition(eq("slack"), eq(2), eq("sendMessage")))
            .thenReturn(actionDefinition);

        String result = callTool(
            factory.readToolCallbacks(), "describeComponentActionParameters",
            """
                {"componentName": "slack", "actionName": "sendMessage"}""");

        assertThat(result).isEqualTo("[]");

        verify(actionDefinitionService).getActionDefinition(eq("slack"), eq(2), eq("sendMessage"));
    }

    @Test
    void testDescribeComponentActionParametersMapsEachPropertyIncludingANullType() {
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

        ActionDefinition actionDefinition = mock(ActionDefinition.class);

        doReturn(List.of(channelProperty, mysteryProperty)).when(actionDefinition)
            .getProperties();
        when(actionDefinitionService.getActionDefinition(eq("slack"), eq(1), eq("sendMessage")))
            .thenReturn(actionDefinition);

        String result = callTool(
            factory.readToolCallbacks(), "describeComponentActionParameters",
            """
                {"componentName": "slack", "actionName": "sendMessage"}""");

        assertThat(result).contains("\"name\":\"channel\"");
        assertThat(result).contains("\"type\":\"STRING\"");
        assertThat(result).contains("\"description\":\"Channel to post to\"");

        // The null-typed property must still be PRESENT, not dropped — the exact behaviour the
        // DescribeComponentActionParametersToolCallback#toSummary null guard preserves.
        assertThat(result).contains("\"name\":\"mystery\"");
        assertThat(result).contains("\"type\":null");

        assertThat(countOccurrences(result, "\"name\":")).isEqualTo(2);
    }

    @Test
    void testListComponentRulesRendersTheStoredRules() {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(1L);
        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
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

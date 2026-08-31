/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.ee.platform.component.rule.web.graphql.ComponentRuleGraphQlController.ComponentRuleItem;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class ComponentRuleGraphQlControllerTest {

    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final ComponentRuleService componentRuleService = mock(ComponentRuleService.class);
    private final ComponentRuleGraphQlController controller =
        new ComponentRuleGraphQlController(componentDefinitionService, componentRuleService);

    @Test
    void testComponentRulesWithoutAComponentNameListsEveryRule() {
        ComponentDefinition componentDefinition = slackComponentDefinition();

        when(componentRuleService.getComponentRules()).thenReturn(List.of(newComponentRule(1L)));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(componentDefinition));

        List<ComponentRuleItem> result = controller.componentRules(null);

        assertThat(result).hasSize(1);

        ComponentRuleItem componentRuleItem = result.getFirst();

        assertThat(componentRuleItem.id()).isEqualTo("1");
        assertThat(componentRuleItem.componentName()).isEqualTo("slack");
        assertThat(componentRuleItem.componentTitle()).isEqualTo("Slack");
        assertThat(componentRuleItem.actionName()).isEqualTo("sendMessage");
        assertThat(componentRuleItem.phase()).isEqualTo(RulePhase.BEFORE);
        assertThat(componentRuleItem.ruleAction()).isEqualTo(RuleAction.TAG);
        assertThat(componentRuleItem.enabled()).isTrue();
    }

    @Test
    void testComponentRulesWithAComponentNameNarrows() {
        ComponentDefinition componentDefinition = slackComponentDefinition();

        when(componentRuleService.getComponentRules("slack")).thenReturn(List.of(newComponentRule(1L)));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(componentDefinition));

        assertThat(controller.componentRules("slack")).hasSize(1);

        verify(componentRuleService).getComponentRules("slack");
    }

    @Test
    void testSaveComponentRuleWithoutAnIdCreates() {
        ComponentDefinition componentDefinition = slackComponentDefinition();

        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(componentDefinition));

        controller.saveComponentRule(
            null, "slack", "sendMessage", RulePhase.BEFORE, RuleAction.BLOCK, "inputParameters['x'] == 1",
            "block when x is one", true);

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getId()).isNull();
        assertThat(componentRule.getComponentName()).isEqualTo("slack");
        assertThat(componentRule.getCondition()).isEqualTo("inputParameters['x'] == 1");
        assertThat(componentRule.getDescription()).isEqualTo("block when x is one");
    }

    @Test
    void testSaveComponentRuleWithAnIdUpdatesInPlace() {
        ComponentDefinition componentDefinition = slackComponentDefinition();

        when(componentRuleService.saveComponentRule(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(componentDefinitionService.getComponentDefinitions()).thenReturn(List.of(componentDefinition));

        controller.saveComponentRule(
            "5", "slack", null, RulePhase.AFTER, RuleAction.TAG, "output['ok'] == false", null, false);

        ArgumentCaptor<ComponentRule> componentRuleCaptor = ArgumentCaptor.forClass(ComponentRule.class);

        verify(componentRuleService).saveComponentRule(componentRuleCaptor.capture());

        ComponentRule componentRule = componentRuleCaptor.getValue();

        assertThat(componentRule.getId()).isEqualTo(5L);
        assertThat(componentRule.getActionName()).isNull();
        assertThat(componentRule.getPhase()).isEqualTo(RulePhase.AFTER);
        assertThat(componentRule.isEnabled()).isFalse();
    }

    @Test
    void testDeleteComponentRuleDelegates() {
        assertThat(controller.deleteComponentRule("5")).isTrue();

        verify(componentRuleService).deleteComponentRule(5L);
    }

    private static ComponentDefinition slackComponentDefinition() {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn("slack");
        when(componentDefinition.getTitle()).thenReturn("Slack");
        when(componentDefinition.getIcon()).thenReturn("slack.svg");
        when(componentDefinition.getVersion()).thenReturn(1);

        return componentDefinition;
    }

    private static ComponentRule newComponentRule(long id) {
        ComponentRule componentRule = new ComponentRule();

        componentRule.setId(id);
        componentRule.setComponentName("slack");
        componentRule.setActionName("sendMessage");
        componentRule.setPhase(RulePhase.BEFORE);
        componentRule.setRuleAction(RuleAction.TAG);
        componentRule.setCondition("true");
        componentRule.setEnabled(true);

        return componentRule;
    }
}

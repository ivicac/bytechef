/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubToolApprovalRuleFacadeTest {

    @Test
    void testCreateRuleRejectsBlankToolName() {
        AiHubToolApprovalRuleService ruleService = mock(AiHubToolApprovalRuleService.class);

        AiHubToolApprovalRuleFacadeImpl facade = new AiHubToolApprovalRuleFacadeImpl(ruleService, null);

        assertThatThrownBy(
            () -> facade.createRule(1L, AiHubToolApproval.ToolKind.CATALOG, null, "   ",
                AiHubToolApprovalRule.Mode.REQUIRE))
                    .isInstanceOf(IllegalArgumentException.class);

        verify(ruleService, never()).save(any());
    }

    @Test
    void testCreateRuleRejectsComponentRuleWithoutComponentName() {
        AiHubToolApprovalRuleService ruleService = mock(AiHubToolApprovalRuleService.class);

        AiHubToolApprovalRuleFacadeImpl facade = new AiHubToolApprovalRuleFacadeImpl(ruleService, null);

        assertThatThrownBy(
            () -> facade.createRule(
                1L, AiHubToolApproval.ToolKind.COMPONENT, null, "sendMessage", AiHubToolApprovalRule.Mode.EXEMPT))
                    .isInstanceOf(IllegalArgumentException.class);

        verify(ruleService, never()).save(any());
    }

    @Test
    void testCreateRuleSavesWithWorkspaceIdFromArgumentAndPublishesAudit() {
        AiHubToolApprovalRuleService ruleService = mock(AiHubToolApprovalRuleService.class);
        AiHubAuditPublisher auditPublisher = mock(AiHubAuditPublisher.class);

        when(ruleService.save(any(AiHubToolApprovalRule.class))).thenAnswer(invocation -> {
            AiHubToolApprovalRule savedRule = invocation.getArgument(0);

            savedRule.setId(42L);

            return savedRule;
        });

        AiHubToolApprovalRuleFacadeImpl facade = new AiHubToolApprovalRuleFacadeImpl(ruleService, auditPublisher);

        AiHubToolApprovalRule savedRule = facade.createRule(
            7L, AiHubToolApproval.ToolKind.CATALOG, null, "deleteProject", AiHubToolApprovalRule.Mode.REQUIRE);

        assertThat(savedRule.getWorkspaceId()).isEqualTo(7L);
        assertThat(savedRule.getToolName()).isEqualTo("deleteProject");

        ArgumentCaptor<AiHubToolApprovalRule> ruleCaptor = ArgumentCaptor.forClass(AiHubToolApprovalRule.class);

        verify(ruleService).save(ruleCaptor.capture());

        AiHubToolApprovalRule capturedRule = ruleCaptor.getValue();

        assertThat(capturedRule.getWorkspaceId()).isEqualTo(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditPublisher).publish(eq(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_RULE_CHANGED), dataCaptor.capture());

        Map<String, Object> data = dataCaptor.getValue();

        assertThat(data.get("workspaceId")).isEqualTo(7L);
        assertThat(data.get("ruleId")).isEqualTo(42L);
        assertThat(data.get("toolName")).isEqualTo("deleteProject");
        assertThat(data.get("mode")).isEqualTo("REQUIRE");
        assertThat(data.get("action")).isEqualTo("created");
    }

    @Test
    void testDeleteRuleDelegatesToRuleServiceAndPublishesAudit() {
        AiHubToolApprovalRuleService ruleService = mock(AiHubToolApprovalRuleService.class);
        AiHubAuditPublisher auditPublisher = mock(AiHubAuditPublisher.class);

        AiHubToolApprovalRule existingRule = new AiHubToolApprovalRule();

        existingRule.setId(42L);
        existingRule.setWorkspaceId(7L);
        existingRule.setToolKind(AiHubToolApproval.ToolKind.CATALOG);
        existingRule.setToolName("deleteProject");
        existingRule.setMode(AiHubToolApprovalRule.Mode.REQUIRE);

        when(ruleService.getRules(7L)).thenReturn(List.of(existingRule));

        AiHubToolApprovalRuleFacadeImpl facade = new AiHubToolApprovalRuleFacadeImpl(ruleService, auditPublisher);

        facade.deleteRule(7L, 42L);

        verify(ruleService).delete(7L, 42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        verify(auditPublisher).publish(eq(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_RULE_CHANGED), dataCaptor.capture());

        Map<String, Object> data = dataCaptor.getValue();

        assertThat(data.get("workspaceId")).isEqualTo(7L);
        assertThat(data.get("ruleId")).isEqualTo(42L);
        assertThat(data.get("toolName")).isEqualTo("deleteProject");
        assertThat(data.get("mode")).isEqualTo("REQUIRE");
        assertThat(data.get("action")).isEqualTo("deleted");
    }

    @Test
    void testGetDefaultToolNamesReturnsSortedDefaults() {
        AiHubToolApprovalRuleService ruleService = mock(AiHubToolApprovalRuleService.class);

        AiHubToolApprovalRuleFacadeImpl facade = new AiHubToolApprovalRuleFacadeImpl(ruleService, null);

        List<String> defaultToolNames = facade.getDefaultToolNames();

        assertThat(defaultToolNames).isEqualTo(
            AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES.stream()
                .sorted()
                .toList());
    }
}

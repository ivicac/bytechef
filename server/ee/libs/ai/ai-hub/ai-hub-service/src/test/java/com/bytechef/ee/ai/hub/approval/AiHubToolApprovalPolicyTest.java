/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ee.ai.hub.approval.repository.AiHubToolApprovalRuleRepository;
import com.bytechef.ee.ai.hub.chat.AiHubChatComponent;
import com.bytechef.ee.ai.hub.chat.AiHubChatTool;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatComponentRepository;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatToolRepository;
import com.bytechef.ee.ai.hub.util.ToolNameNormalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiHubToolApprovalPolicyTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long USER_ID = 3L;
    private static final long CHAT_ID = 11L;

    private final AiHubToolApprovalRuleRepository ruleRepository = mock(AiHubToolApprovalRuleRepository.class);
    private final AiHubChatComponentRepository componentRepository = mock(AiHubChatComponentRepository.class);
    private final AiHubChatToolRepository toolRepository = mock(AiHubChatToolRepository.class);

    private final AiHubToolApprovalPolicy policy = new AiHubToolApprovalPolicyImpl(
        ruleRepository, componentRepository, toolRepository);

    @Test
    void testBuiltInDefaultsAreGatedWithNoRules() {
        AiHubToolApprovalPolicy.Decision decision = policy.decide(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(decision.isGated("deleteProject")).isTrue();
        assertThat(decision.isGated("dropDataTable")).isTrue();
        assertThat(decision.isGated("deleteKnowledgeBase")).isTrue();
        assertThat(decision.isGated("listProjects")).isFalse();
    }

    @Test
    void testPrefixMatchGatesAnUnlistedDestructiveName() {
        assertThat(AiHubToolApprovalDefaults.isGatedByDefault("rollbackProjectDeployment")).isTrue();
        assertThat(AiHubToolApprovalDefaults.isGatedByDefault("promoteWorkflow")).isTrue();
        assertThat(AiHubToolApprovalDefaults.isGatedByDefault("getProject")).isFalse();
    }

    @Test
    void testWorkspaceExemptRemovesADefault() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.CATALOG, null, "deleteProject", AiHubToolApprovalRule.Mode.EXEMPT)));

        AiHubToolApprovalPolicy.Decision decision = policy.decide(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(decision.isGated("deleteProject")).isFalse();
    }

    @Test
    void testWorkspaceRequireAddsAComponentOperation() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.COMPONENT, "gmail", "sendEmail", AiHubToolApprovalRule.Mode.REQUIRE)));

        AiHubToolApprovalPolicy.Decision decision = policy.decide(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(decision.isGated(ToolNameNormalizer.toToolName("gmail", "sendEmail"))).isTrue();
    }

    @Test
    void testWorkspaceWildcardGatesEveryOperationOfAComponent() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.COMPONENT, "gmail", "*", AiHubToolApprovalRule.Mode.REQUIRE)));
        AiHubChatComponent component = component(21L, "gmail");
        when(componentRepository.findAllByChatId(CHAT_ID)).thenReturn(List.of(component));
        when(toolRepository.findAllByChatComponentId(21L)).thenReturn(List.of(tool(21L, "sendEmail", false)));

        AiHubToolApprovalPolicy.Decision decision = policy.decide(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(decision.isGated(ToolNameNormalizer.toToolName("gmail", "sendEmail"))).isTrue();
    }

    @Test
    void testChatOwnerFlagAddsButCannotRemove() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(
            rule(AiHubToolApproval.ToolKind.COMPONENT, "slack", "postMessage", AiHubToolApprovalRule.Mode.REQUIRE)));
        AiHubChatComponent gmail = component(21L, "gmail");
        AiHubChatComponent slack = component(22L, "slack");
        when(componentRepository.findAllByChatId(CHAT_ID)).thenReturn(List.of(gmail));
        when(componentRepository.findAllByUserIdAndWorkspaceId(USER_ID, WORKSPACE_ID)).thenReturn(List.of(slack));
        when(toolRepository.findAllByChatComponentId(21L)).thenReturn(List.of(tool(21L, "sendEmail", true)));
        when(toolRepository.findAllByChatComponentId(22L)).thenReturn(List.of(tool(22L, "postMessage", false)));

        AiHubToolApprovalPolicy.Decision decision = policy.decide(WORKSPACE_ID, USER_ID, CHAT_ID);

        assertThat(decision.isGated(ToolNameNormalizer.toToolName("gmail", "sendEmail"))).isTrue();
        assertThat(decision.isGated(ToolNameNormalizer.toToolName("slack", "postMessage"))).isTrue();
    }

    @Test
    void testRepositoryFailureFallsBackToDefaultsOnly() {
        when(ruleRepository.findAllByWorkspaceId(WORKSPACE_ID)).thenThrow(new IllegalStateException("db down"));

        AiHubToolApprovalPolicy.Decision decision = policy.decide(WORKSPACE_ID, USER_ID, CHAT_ID);

        for (String toolName : AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES) {
            assertThat(decision.isGated(toolName)).isTrue();
        }

        assertThat(decision.exempt()).isEmpty();
    }

    private static AiHubToolApprovalRule rule(
        AiHubToolApproval.ToolKind toolKind, String componentName, String toolName, AiHubToolApprovalRule.Mode mode) {

        AiHubToolApprovalRule rule = new AiHubToolApprovalRule();

        rule.setWorkspaceId(WORKSPACE_ID);
        rule.setToolKind(toolKind);
        rule.setComponentName(componentName);
        rule.setToolName(toolName);
        rule.setMode(mode);

        return rule;
    }

    private static AiHubChatComponent component(long id, String componentName) {
        AiHubChatComponent component = new AiHubChatComponent();

        component.setId(id);
        component.setComponentName(componentName);
        component.setComponentVersion(1);

        return component;
    }

    private static AiHubChatTool tool(long componentId, String name, boolean requiresApproval) {
        AiHubChatTool tool = new AiHubChatTool(componentId, name, Map.of());

        tool.setRequiresApproval(requiresApproval);

        return tool;
    }
}

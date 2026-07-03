/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import com.bytechef.ee.ai.hub.audit.AiHubAuditEvent;
import com.bytechef.ee.ai.hub.audit.AiHubAuditPublisher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link AiHubToolApprovalRuleFacade}. Carries the workspace-role guards and delegates to
 * {@link AiHubToolApprovalRuleService}, exactly like {@code AiHubWorkspaceSettingsFacadeImpl}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
@Transactional
public class AiHubToolApprovalRuleFacadeImpl implements AiHubToolApprovalRuleFacade {

    private final AiHubToolApprovalRuleService ruleService;
    private final @Nullable AiHubAuditPublisher auditPublisher;

    @SuppressFBWarnings("EI")
    public AiHubToolApprovalRuleFacadeImpl(
        AiHubToolApprovalRuleService ruleService, @Nullable AiHubAuditPublisher auditPublisher) {

        this.ruleService = ruleService;
        this.auditPublisher = auditPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_VIEW')")
    public List<AiHubToolApprovalRule> getRules(long workspaceId) {
        return ruleService.getRules(workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<String> getDefaultToolNames() {
        return AiHubToolApprovalDefaults.DEFAULT_TOOL_NAMES.stream()
            .sorted()
            .toList();
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_MANAGE')")
    public AiHubToolApprovalRule createRule(
        long workspaceId, AiHubToolApproval.ToolKind toolKind, @Nullable String componentName, String toolName,
        AiHubToolApprovalRule.Mode mode) {

        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }

        if (toolKind == AiHubToolApproval.ToolKind.COMPONENT && (componentName == null || componentName.isBlank())) {
            throw new IllegalArgumentException("componentName is required for a component rule");
        }

        AiHubToolApprovalRule rule = new AiHubToolApprovalRule();

        rule.setWorkspaceId(workspaceId);
        rule.setToolKind(toolKind);
        rule.setComponentName(toolKind == AiHubToolApproval.ToolKind.COMPONENT ? componentName : null);
        rule.setToolName(toolName.trim());
        rule.setMode(mode);

        AiHubToolApprovalRule savedRule = ruleService.save(rule);

        publish(workspaceId, savedRule, "created");

        return savedRule;
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'WORKSPACE_MANAGE')")
    public void deleteRule(long workspaceId, long ruleId) {
        AiHubToolApprovalRule rule = ruleService.getRules(workspaceId)
            .stream()
            .filter(candidateRule -> Objects.equals(candidateRule.getId(), ruleId))
            .findFirst()
            .orElse(null);

        ruleService.delete(workspaceId, ruleId);

        publish(workspaceId, rule, "deleted");
    }

    private void publish(long workspaceId, @Nullable AiHubToolApprovalRule rule, String action) {
        if (auditPublisher == null) {
            return;
        }

        Map<String, Object> data = new HashMap<>();

        data.put("workspaceId", workspaceId);
        data.put("action", action);

        if (rule != null) {
            data.put("ruleId", rule.getId());
            data.put("toolName", rule.getToolName());
            data.put("mode", rule.getMode()
                .name());
        }

        auditPublisher.publish(AiHubAuditEvent.AI_HUB_TOOL_APPROVAL_RULE_CHANGED, data);
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.approval;

import com.bytechef.ee.ai.hub.approval.repository.AiHubToolApprovalRuleRepository;
import com.bytechef.ee.ai.hub.chat.AiHubChatComponent;
import com.bytechef.ee.ai.hub.chat.AiHubChatTool;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatComponentRepository;
import com.bytechef.ee.ai.hub.chat.repository.AiHubChatToolRepository;
import com.bytechef.ee.ai.hub.util.ToolNameNormalizer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Combines the built-in defaults, the workspace's {@link AiHubToolApprovalRule} rows and the per-tool owner flag into
 * one {@link Decision}. Order matters and is the "most restrictive wins" rule from the spec: workspace rules are
 * applied first (an {@code EXEMPT} rule can lift a default or a {@code REQUIRE} rule), then the chat owner's per-tool
 * {@code requiresApproval} flags are applied LAST and only ever add to {@code required} — an owner can never un-gate a
 * workspace {@code REQUIRE}.
 *
 * <p>
 * A repository failure during lookup never throws and never leaves a workspace ungated: the catch block falls back to
 * whatever was accumulated in {@code required} before the failure, with an empty {@code exempt} set, so the built-in
 * defaults ({@link AiHubToolApprovalDefaults}) still gate through {@link Decision#isGated}.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class AiHubToolApprovalPolicyImpl implements AiHubToolApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(AiHubToolApprovalPolicyImpl.class);

    private static final String WILDCARD = "*";

    private final AiHubToolApprovalRuleRepository ruleRepository;
    private final AiHubChatComponentRepository componentRepository;
    private final AiHubChatToolRepository toolRepository;

    @SuppressFBWarnings("EI")
    public AiHubToolApprovalPolicyImpl(
        AiHubToolApprovalRuleRepository ruleRepository, AiHubChatComponentRepository componentRepository,
        AiHubChatToolRepository toolRepository) {

        this.ruleRepository = ruleRepository;
        this.componentRepository = componentRepository;
        this.toolRepository = toolRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Decision decide(long workspaceId, long userId, @Nullable Long chatId) {
        Set<String> required = new HashSet<>();
        Set<String> exempt = new HashSet<>();

        try {
            List<AiHubToolApprovalRule> rules = ruleRepository.findAllByWorkspaceId(workspaceId);
            List<AiHubChatComponent> components = loadComponents(userId, workspaceId, chatId);

            applyCatalogRules(rules, required, exempt);
            applyComponentRules(rules, components, required, exempt);
            applyOwnerFlags(components, required);
        } catch (RuntimeException exception) {
            log.warn(
                "Tool approval policy lookup failed for workspace={} chat={}; gating built-in defaults only",
                workspaceId, chatId, exception);

            return new Decision(Set.copyOf(required), Set.of());
        }

        return new Decision(required, exempt);
    }

    private List<AiHubChatComponent> loadComponents(long userId, long workspaceId, @Nullable Long chatId) {
        List<AiHubChatComponent> components = new ArrayList<>(
            componentRepository.findAllByUserIdAndWorkspaceId(userId, workspaceId));

        if (chatId != null) {
            components.addAll(componentRepository.findAllByChatId(chatId));
        }

        return components;
    }

    private static void applyCatalogRules(List<AiHubToolApprovalRule> rules, Set<String> required, Set<String> exempt) {
        for (AiHubToolApprovalRule rule : rules) {
            if (rule.getToolKind() != AiHubToolApproval.ToolKind.CATALOG) {
                continue;
            }

            apply(rule.getMode(), rule.getToolName(), required, exempt);
        }
    }

    private void applyComponentRules(
        List<AiHubToolApprovalRule> rules, List<AiHubChatComponent> components, Set<String> required,
        Set<String> exempt) {

        for (AiHubToolApprovalRule rule : rules) {
            if (rule.getToolKind() != AiHubToolApproval.ToolKind.COMPONENT) {
                continue;
            }

            if (!WILDCARD.equals(rule.getToolName())) {
                apply(
                    rule.getMode(), ToolNameNormalizer.toToolName(rule.getComponentName(), rule.getToolName()),
                    required, exempt);

                continue;
            }

            for (AiHubChatComponent component : components) {
                if (!Objects.equals(component.getComponentName(), rule.getComponentName())) {
                    continue;
                }

                for (AiHubChatTool tool : toolRepository.findAllByChatComponentId(component.getId())) {
                    apply(
                        rule.getMode(), ToolNameNormalizer.toToolName(component.getComponentName(), tool.getName()),
                        required, exempt);
                }
            }
        }
    }

    private void applyOwnerFlags(List<AiHubChatComponent> components, Set<String> required) {
        for (AiHubChatComponent component : components) {
            for (AiHubChatTool tool : toolRepository.findAllByChatComponentId(component.getId())) {
                if (tool.isRequiresApproval()) {
                    required.add(ToolNameNormalizer.toToolName(component.getComponentName(), tool.getName()));
                }
            }
        }
    }

    private static void apply(
        AiHubToolApprovalRule.Mode mode, String toolName, Set<String> required, Set<String> exempt) {

        if (mode == AiHubToolApprovalRule.Mode.REQUIRE) {
            required.add(toolName);
        } else {
            exempt.add(toolName);
        }
    }
}

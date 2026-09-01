/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.web.graphql;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.security.constant.AuthorityConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL surface for tenant-wide component rules. Admin-only.
 *
 * <p>
 * Each row is decorated with the component's title and icon so the flat Rules list can render a recognisable row
 * without a second round trip per component.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
public class ComponentRuleGraphQlController {

    private final ComponentDefinitionService componentDefinitionService;
    private final ComponentRuleService componentRuleService;

    @SuppressFBWarnings("EI2")
    public ComponentRuleGraphQlController(
        ComponentDefinitionService componentDefinitionService, ComponentRuleService componentRuleService) {

        this.componentDefinitionService = componentDefinitionService;
        this.componentRuleService = componentRuleService;
    }

    @QueryMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public List<ComponentRuleItem> componentRules(@Argument @Nullable String componentName) {
        List<ComponentRule> componentRules = componentName == null
            ? componentRuleService.getComponentRules()
            : componentRuleService.getComponentRules(componentName);

        if (componentRules.isEmpty()) {
            return List.of();
        }

        Map<String, ComponentDefinition> componentDefinitionsByName = getComponentDefinitionsByName();

        return componentRules.stream()
            .map(componentRule -> toItem(componentRule, componentDefinitionsByName))
            .sorted(
                Comparator.comparing(ComponentRuleItem::sortKey, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ComponentRuleItem::id))
            .toList();
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRuleItem saveComponentRule(
        @Argument @Nullable String id, @Argument String componentName, @Argument @Nullable String actionName,
        @Argument RulePhase phase, @Argument RuleAction ruleAction, @Argument String condition,
        @Argument @Nullable String description, @Argument boolean enabled) {

        ComponentRule componentRule = new ComponentRule();

        if (id != null && !id.isBlank()) {
            componentRule.setId(Long.parseLong(id));
        }

        componentRule.setComponentName(componentName);
        componentRule.setActionName(actionName);
        componentRule.setPhase(phase);
        componentRule.setRuleAction(ruleAction);
        componentRule.setCondition(condition);
        componentRule.setDescription(description);
        componentRule.setEnabled(enabled);

        ComponentRule savedComponentRule = componentRuleService.saveComponentRule(componentRule);

        return toItem(savedComponentRule, getComponentDefinitionsByName());
    }

    @MutationMapping
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public boolean deleteComponentRule(@Argument String id) {
        componentRuleService.deleteComponentRule(Long.parseLong(id));

        return true;
    }

    private Map<String, ComponentDefinition> getComponentDefinitionsByName() {
        // The registry can hold several definitions per name (multiple versions; a component and a same-named cluster
        // element), so collapse to one per name the way ComponentPolicyGraphQlController does.
        return componentDefinitionService.getComponentDefinitions()
            .stream()
            .collect(
                Collectors.toMap(
                    ComponentDefinition::getName, Function.identity(),
                    ComponentRuleGraphQlController::preferHighestVersion));
    }

    private static ComponentDefinition preferHighestVersion(
        ComponentDefinition firstComponentDefinition, ComponentDefinition secondComponentDefinition) {

        return firstComponentDefinition.getVersion() >= secondComponentDefinition.getVersion()
            ? firstComponentDefinition
            : secondComponentDefinition;
    }

    private static ComponentRuleItem toItem(
        ComponentRule componentRule, Map<String, ComponentDefinition> componentDefinitionsByName) {

        ComponentDefinition componentDefinition = componentDefinitionsByName.get(componentRule.getComponentName());

        return new ComponentRuleItem(
            String.valueOf(componentRule.getId()), componentRule.getComponentName(),
            componentDefinition == null ? null : componentDefinition.getTitle(),
            componentDefinition == null ? null : componentDefinition.getIcon(), componentRule.getActionName(),
            componentRule.getPhase(), componentRule.getRuleAction(), componentRule.getCondition(),
            componentRule.getDescription(), componentRule.isEnabled());
    }

    public record ComponentRuleItem(
        String id, String componentName, @Nullable String componentTitle, @Nullable String componentIcon,
        @Nullable String actionName, RulePhase phase, RuleAction ruleAction, String condition,
        @Nullable String description, boolean enabled) {

        String sortKey() {
            return componentTitle == null ? componentName : componentTitle;
        }
    }
}

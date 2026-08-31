/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditEvent;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditPublisher;
import com.bytechef.ee.platform.component.rule.audit.ComponentRuleAuditPublisher.ComponentRuleAuditPayload;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.rule.ComponentRuleEnforcer;
import com.bytechef.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EE implementation of {@link ComponentRuleEnforcer} backed by the {@code component_rule} table.
 *
 * <p>
 * The rule list is cached per tenant AND per component for a short window, so an action execution on a component with
 * no rules costs one cache hit rather than a query. The tenant is part of the key because component names are global:
 * keying on the component name alone would serve one tenant's rules to another.
 * </p>
 *
 * <p>
 * A condition is a ByteChef formula body, so it is evaluated as {@code "=" + condition} through the platform
 * {@link Evaluator} in lenient mode. Lenient matters: an unresolved reference makes the evaluator return the original
 * string rather than throw, and only {@code Boolean.TRUE} counts as a match — so a rule whose condition cannot be
 * resolved against this particular call simply does not fire. A {@code BLOCK} rule therefore fails open. That is the
 * deliberate trade: a mis-authored rule must not be able to take a tenant's whole workflow estate offline, and the
 * save-time parse check in {@code ComponentRuleServiceImpl} already rejects conditions that are outright invalid.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ComponentRuleEnforcerImpl implements ComponentRuleEnforcer {

    private static final String CONDITION_KEY = "condition";
    private static final String FORMULA_PREFIX = "=";

    private static final Logger log = LoggerFactory.getLogger(ComponentRuleEnforcerImpl.class);

    private record TenantComponentKey(String tenantId, String componentName) {
    }

    private final Cache<TenantComponentKey, List<ComponentRule>> componentRulesCache;
    private final ComponentRuleAuditPublisher componentRuleAuditPublisher;
    private final ComponentRuleService componentRuleService;
    private final Evaluator evaluator;

    public ComponentRuleEnforcerImpl(
        ComponentRuleService componentRuleService, Evaluator evaluator,
        ComponentRuleAuditPublisher componentRuleAuditPublisher) {

        this(componentRuleService, evaluator, componentRuleAuditPublisher, Ticker.systemTicker());
    }

    /**
     * Package-private, for the TTL test: Caffeine reads elapsed time through a {@link Ticker}, so injecting a fake one
     * is the only way to assert the expiry without a sleep.
     */
    ComponentRuleEnforcerImpl(
        ComponentRuleService componentRuleService, Evaluator evaluator,
        ComponentRuleAuditPublisher componentRuleAuditPublisher, Ticker ticker) {

        this.componentRuleService = componentRuleService;
        this.evaluator = evaluator;
        this.componentRuleAuditPublisher = componentRuleAuditPublisher;
        this.componentRulesCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10))
            .ticker(ticker)
            .build();
    }

    @Override
    public @Nullable String checkBeforePerform(ActionCall actionCall) {
        List<ComponentRule> matchingComponentRules = getMatchingComponentRules(
            actionCall, RulePhase.BEFORE, buildEvaluationContext(actionCall, null));

        if (matchingComponentRules.isEmpty()) {
            return null;
        }

        for (ComponentRule componentRule : matchingComponentRules) {
            if (componentRule.getRuleAction() == RuleAction.BLOCK) {
                publish(ComponentRuleAuditEvent.RULE_BLOCKED, componentRule, actionCall, RulePhase.BEFORE);

                return "Action '%s' of component '%s' was blocked by an administrator rule."
                    .formatted(actionCall.actionName(), actionCall.componentName());
            }
        }

        // Only reached when nothing blocked — block wins over tag, and a refused call has nothing to review.
        for (ComponentRule componentRule : matchingComponentRules) {
            publish(ComponentRuleAuditEvent.RULE_TAGGED, componentRule, actionCall, RulePhase.BEFORE);
        }

        return null;
    }

    @Override
    public void recordAfterPerform(ActionCall actionCall, @Nullable Object output) {
        // The action has already run; nothing here may fail the execution.
        try {
            List<ComponentRule> matchingComponentRules = getMatchingComponentRules(
                actionCall, RulePhase.AFTER, buildEvaluationContext(actionCall, output));

            for (ComponentRule componentRule : matchingComponentRules) {
                publish(ComponentRuleAuditEvent.RULE_TAGGED, componentRule, actionCall, RulePhase.AFTER);
            }
        } catch (RuntimeException exception) {
            log.warn(
                "Could not evaluate AFTER-phase rules for {}#{}", actionCall.componentName(),
                actionCall.actionName(), exception);
        }
    }

    private List<ComponentRule> getMatchingComponentRules(
        ActionCall actionCall, RulePhase rulePhase, Map<String, Object> evaluationContext) {

        List<ComponentRule> componentRules = getCachedComponentRules(actionCall.componentName());

        if (componentRules.isEmpty()) {
            return List.of();
        }

        List<ComponentRule> matchingComponentRules = new ArrayList<>();

        for (ComponentRule componentRule : componentRules) {
            if (componentRule.getPhase() != rulePhase || !componentRule.appliesToAction(actionCall.actionName())) {
                continue;
            }

            if (matches(componentRule, evaluationContext)) {
                matchingComponentRules.add(componentRule);
            }
        }

        return matchingComponentRules;
    }

    private List<ComponentRule> getCachedComponentRules(String componentName) {
        TenantComponentKey tenantComponentKey = new TenantComponentKey(
            TenantContext.getCurrentTenantId(), componentName);

        return componentRulesCache.get(
            tenantComponentKey, key -> componentRuleService.getEnabledComponentRules(key.componentName()));
    }

    private boolean matches(ComponentRule componentRule, Map<String, Object> evaluationContext) {
        try {
            Map<String, ?> evaluated = evaluator.evaluate(
                Map.of(CONDITION_KEY, FORMULA_PREFIX + componentRule.getCondition()), evaluationContext, true);

            return Boolean.TRUE.equals(evaluated.get(CONDITION_KEY));
        } catch (RuntimeException exception) {
            log.warn(
                "Could not evaluate the condition of component rule id={}; treating it as not matching",
                componentRule.getId(), exception);

            return false;
        }
    }

    private static Map<String, Object> buildEvaluationContext(ActionCall actionCall, @Nullable Object output) {
        Map<String, Object> evaluationContext = new HashMap<>();

        evaluationContext.put("inputParameters", actionCall.inputParameters());
        evaluationContext.put("componentName", actionCall.componentName());
        evaluationContext.put("actionName", actionCall.actionName());
        evaluationContext.put("connectionId", actionCall.connectionId());

        if (output != null) {
            evaluationContext.put("output", output);
        }

        return evaluationContext;
    }

    private void publish(
        ComponentRuleAuditEvent componentRuleAuditEvent, ComponentRule componentRule, ActionCall actionCall,
        RulePhase rulePhase) {

        componentRuleAuditPublisher.publish(
            componentRuleAuditEvent,
            new ComponentRuleAuditPayload(
                componentRule.getId(), actionCall.componentName(), actionCall.actionName(), rulePhase.name(),
                actionCall.jobId(), actionCall.taskExecutionId()));
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule.service;

import com.bytechef.ee.platform.component.rule.ComponentRule;
import com.bytechef.ee.platform.component.rule.ComponentRule.RuleAction;
import com.bytechef.ee.platform.component.rule.ComponentRule.RulePhase;
import com.bytechef.ee.platform.component.rule.ComponentRuleConditionStubContext;
import com.bytechef.ee.platform.component.rule.ComponentRuleErrorType;
import com.bytechef.ee.platform.component.rule.ComponentRuleService;
import com.bytechef.ee.platform.component.rule.repository.ComponentRuleRepository;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.constant.AuthorityConstants;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
public class ComponentRuleServiceImpl implements ComponentRuleService {

    private static final String CONDITION_KEY = "condition";
    private static final String FORMULA_PREFIX = "=";

    private final ComponentRuleRepository componentRuleRepository;
    private final Evaluator evaluator;

    public ComponentRuleServiceImpl(ComponentRuleRepository componentRuleRepository, Evaluator evaluator) {
        this.componentRuleRepository = componentRuleRepository;
        this.evaluator = evaluator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getComponentRules() {
        return StreamSupport.stream(
            componentRuleRepository.findAll()
                .spliterator(),
            false)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getComponentRules(String componentName) {
        return componentRuleRepository.findAllByComponentName(componentName);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getEnabledComponentRules(String componentName) {
        return componentRuleRepository.findAllByComponentNameAndEnabled(componentName, true);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public ComponentRule saveComponentRule(ComponentRule componentRule) {
        // A block only means anything before the action runs. After `perform` has already produced output, whatever
        // side effect the block was meant to prevent has happened, so the combination is refused at save rather than
        // silently degraded to a tag at execution.
        if (componentRule.getPhase() == RulePhase.AFTER && componentRule.getRuleAction() == RuleAction.BLOCK) {
            throw new ConfigurationException(
                "A rule evaluated in the AFTER phase cannot BLOCK — the action has already run. Use TAG instead.",
                ComponentRuleErrorType.BLOCK_AFTER_UNSUPPORTED);
        }

        validateCondition(componentRule.getCondition());

        Long id = componentRule.getId();

        if (id == null) {
            return componentRuleRepository.save(componentRule);
        }

        // ComponentRule carries a primitive @Version field, which Spring Data JDBC's new-vs-existing check prefers
        // over the id: a fresh instance with the id copied onto it still reads version 0, so a save() would be treated
        // as an INSERT with a caller-supplied id rather than an UPDATE. Loading the persisted row and mutating it keeps
        // its real version (and its createdBy/createdDate, which are NOT NULL) intact, so save() takes the UPDATE
        // branch instead.
        ComponentRule persistedComponentRule = componentRuleRepository.findById(id)
            .orElseThrow(
                () -> new ConfigurationException(
                    "Component rule with id " + id + " does not exist.",
                    ComponentRuleErrorType.COMPONENT_RULE_NOT_FOUND));

        persistedComponentRule.setComponentName(componentRule.getComponentName());
        persistedComponentRule.setActionName(componentRule.getActionName());
        persistedComponentRule.setPhase(componentRule.getPhase());
        persistedComponentRule.setRuleAction(componentRule.getRuleAction());
        persistedComponentRule.setCondition(componentRule.getCondition());
        persistedComponentRule.setDescription(componentRule.getDescription());
        persistedComponentRule.setEnabled(componentRule.isEnabled());

        return componentRuleRepository.save(persistedComponentRule);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void deleteComponentRule(long id) {
        componentRuleRepository.deleteById(id);
    }

    /**
     * Parses the condition the same way the enforcer will evaluate it — as a formula expression, with the {@code =}
     * prefix prepended and evaluated against {@link ComponentRuleConditionStubContext}. A syntax error, a banned
     * construct ({@code T(}, a {@code .method(} call, {@code new}) or an unknown function surfaces here, at save time,
     * instead of at the next execution. The stub context matters: an empty context lets an unresolved root reference
     * (say, {@code inputParameters}) short-circuit the evaluation before an unknown function name is ever resolved, so
     * a misspelled function would validate clean against one.
     */
    private void validateCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            throw new ConfigurationException(
                "A rule condition must not be empty.", ComponentRuleErrorType.INVALID_CONDITION);
        }

        try {
            evaluator.evaluate(
                Map.of(CONDITION_KEY, FORMULA_PREFIX + condition), ComponentRuleConditionStubContext.get(), false);
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                "The rule condition is not a valid expression: " + exception.getMessage(), exception,
                ComponentRuleErrorType.INVALID_CONDITION);
        }
    }
}

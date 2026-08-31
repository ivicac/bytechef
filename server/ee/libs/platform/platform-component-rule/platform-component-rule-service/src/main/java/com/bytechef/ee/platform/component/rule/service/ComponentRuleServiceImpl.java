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

        return componentRuleRepository.save(componentRule);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void deleteComponentRule(long id) {
        componentRuleRepository.deleteById(id);
    }

    /**
     * Parses the condition the same way the enforcer will evaluate it — as a formula expression, with the {@code =}
     * prefix prepended and an empty context. A syntax error, a banned construct ({@code T(}, a {@code .method(} call,
     * {@code new}) or an unknown function surfaces here, at save time, instead of at the next execution. An empty
     * context is enough: unresolved references are not errors in the evaluator, only bad syntax is.
     */
    private void validateCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            throw new ConfigurationException(
                "A rule condition must not be empty.", ComponentRuleErrorType.INVALID_CONDITION);
        }

        try {
            evaluator.evaluate(Map.of(CONDITION_KEY, FORMULA_PREFIX + condition), Map.of(), false);
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                "The rule condition is not a valid expression: " + exception.getMessage(), exception,
                ComponentRuleErrorType.INVALID_CONDITION);
        }
    }
}

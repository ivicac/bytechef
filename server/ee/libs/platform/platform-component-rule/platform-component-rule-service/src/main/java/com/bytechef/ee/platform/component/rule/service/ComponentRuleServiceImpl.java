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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final Logger log = LoggerFactory.getLogger(ComponentRuleServiceImpl.class);
    private static final AtomicBoolean NO_REPOSITORY_WARNED = new AtomicBoolean();

    // Nullable rather than a hard constructor dependency: on a distributed worker there is no local database, so no
    // ComponentRuleJdbcRepositoryConfiguration (@ConditionalOnBean(AbstractJdbcConfiguration.class)) ever fires and
    // no ComponentRuleRepository bean exists. Resolving it through an ObjectProvider lets this service — and, through
    // it, ComponentRuleEnforcerImpl — still construct there, so the enforcer bean is genuinely on the worker's
    // classpath and reachable, rather than the whole context failing to boot. getEnabledComponentRules(), the one
    // method the enforcer's hot path actually calls, degrades to an empty list rather than throwing when the
    // repository is absent; every other method (the authoring surface, reachable only from server-app's GraphQL API)
    // throws, since it should never be called where there is nothing to persist to.
    private final @Nullable ComponentRuleRepository componentRuleRepository;
    private final Evaluator evaluator;

    /**
     * {@code CT_CONSTRUCTOR_THROW} is suppressed rather than fixed, because neither remedy the pattern offers is
     * available here and the throw is correct.
     *
     * <p>
     * The only call that can throw is Spring's own {@code ObjectProvider.getIfAvailable()} — the worker-wiring seam
     * this constructor exists for. A container failure resolving the repository (two candidate beans, say) must surface
     * at bean creation; swallowing it would hand every caller a silently repository-less service, which is precisely
     * the fail-open state {@code getEnabledComponentRules} reserves for an app that genuinely has no database. Making
     * the class {@code final}, the pattern's own remedy, is not open either: this repository runs
     * {@code proxyTargetClass = true} (see {@code SecurityConfiguration}), so CGLIB subclasses every
     * {@code @Transactional}/{@code @PreAuthorize} bean and a final one cannot be proxied at all. Nothing in the
     * application subclasses this class, so there is no finalizer-attack surface to defend.
     * </p>
     */
    @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
    public ComponentRuleServiceImpl(
        ObjectProvider<ComponentRuleRepository> componentRuleRepositoryProvider, Evaluator evaluator) {

        this.componentRuleRepository = componentRuleRepositoryProvider.getIfAvailable();
        this.evaluator = evaluator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getComponentRules() {
        return StreamSupport.stream(
            requireRepository().findAll()
                .spliterator(),
            false)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getComponentRules(String componentName) {
        return requireRepository().findAllByComponentName(componentName);
    }

    @Override
    @Transactional(readOnly = true)
    public ComponentRule getComponentRule(long id) {
        return requireRepository().findById(id)
            .orElseThrow(
                () -> new ConfigurationException(
                    "Component rule with id " + id + " does not exist.",
                    ComponentRuleErrorType.COMPONENT_RULE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentRule> getEnabledComponentRules(String componentName, @Nullable Long workspaceId) {
        if (componentRuleRepository == null) {
            if (NO_REPOSITORY_WARNED.compareAndSet(false, true)) {
                log.warn(
                    "No ComponentRuleRepository is available on this app (no local database) — component rules " +
                        "will not be enforced here until this app can reach the rule data remotely. Failing open.");
            }

            return List.of();
        }

        return componentRuleRepository.findAllEnabledForWorkspace(componentName, workspaceId);
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

        if (componentRule.getPhase() == RulePhase.AFTER &&
            componentRule.getRuleAction() == RuleAction.REQUIRE_APPROVAL) {

            throw new ConfigurationException(
                "A rule evaluated in the AFTER phase cannot require approval — the tool has already run. Use TAG " +
                    "instead.",
                ComponentRuleErrorType.APPROVAL_AFTER_UNSUPPORTED);
        }

        validateCondition(componentRule.getCondition());

        Long id = componentRule.getId();

        ComponentRuleRepository repository = requireRepository();

        if (id == null) {
            return repository.save(componentRule);
        }

        // ComponentRule carries a primitive @Version field, which Spring Data JDBC's new-vs-existing check prefers
        // over the id: a fresh instance with the id copied onto it still reads version 0, so a save() would be treated
        // as an INSERT with a caller-supplied id rather than an UPDATE. Loading the persisted row and mutating it keeps
        // its real version (and its createdBy/createdDate, which are NOT NULL) intact, so save() takes the UPDATE
        // branch instead.
        ComponentRule persistedComponentRule = repository.findById(id)
            .orElseThrow(
                () -> new ConfigurationException(
                    "Component rule with id " + id + " does not exist.",
                    ComponentRuleErrorType.COMPONENT_RULE_NOT_FOUND));

        persistedComponentRule.setComponentName(componentRule.getComponentName());
        persistedComponentRule.setToolName(componentRule.getToolName());
        persistedComponentRule.setWorkspaceId(componentRule.getWorkspaceId());
        persistedComponentRule.setPhase(componentRule.getPhase());
        persistedComponentRule.setRuleAction(componentRule.getRuleAction());
        persistedComponentRule.setCondition(componentRule.getCondition());
        persistedComponentRule.setDescription(componentRule.getDescription());
        persistedComponentRule.setEnabled(componentRule.isEnabled());
        persistedComponentRule.setStrict(componentRule.isStrict());

        return repository.save(persistedComponentRule);
    }

    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void deleteComponentRule(long id) {
        requireRepository().deleteById(id);
    }

    /**
     * The authoring surface is reachable only from server-app's GraphQL API, which never runs without a database — so a
     * null repository here means something is wired wrong, and that should be loud rather than a silent no-op.
     */
    private ComponentRuleRepository requireRepository() {
        if (componentRuleRepository == null) {
            throw new IllegalStateException(
                "No ComponentRuleRepository is available on this app (no local database); component rule " +
                    "authoring is only supported where the rule data is persisted.");
        }

        return componentRuleRepository;
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

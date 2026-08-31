/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.component.rule;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A conditional, tenant-wide governance rule over a single component action. Unlike {@code ComponentOperationPolicy},
 * which is a pure deny-list whose row presence is the whole signal, a rule carries a payload: the phase it runs in,
 * what it does when it fires, and the SpEL condition that decides whether it fires at all. That payload has to survive
 * a temporary disable, which is why {@code enabled} is a column rather than row presence.
 *
 * <p>
 * {@code actionName} is nullable: null means the rule applies to every action of the component. There is deliberately
 * no composite unique key — one component/action pair may carry several unrelated rules.
 * </p>
 *
 * <p>
 * {@code condition} stores a ByteChef <b>formula body</b> — the text that follows the {@code =} prefix
 * {@code SpelEvaluator} requires before it will parse full SpEL. Callers prepend the {@code =} at evaluation and
 * parse-check time; it is never stored. Because {@code SpelEvaluator.validateFormulaExpression} rejects {@code T(},
 * {@code .method(} calls and {@code new}, a condition composes the evaluator's own whitelisted functions
 * ({@code contains}, {@code equalsIgnoreCase}, {@code size}, …) rather than Java methods.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("component_rule")
public class ComponentRule {

    /**
     * When the rule is evaluated relative to the action's {@code perform}. INT ordinal persisted — append new values at
     * the end, never reorder.
     */
    public enum RulePhase {
        BEFORE, AFTER
    }

    /**
     * What a firing rule does. INT ordinal persisted — append new values at the end, never reorder.
     */
    public enum RuleAction {
        BLOCK, TAG
    }

    @Id
    private Long id;

    @Column("component_name")
    private String componentName;

    @Column("action_name")
    private @Nullable String actionName;

    @Column("phase")
    private int phase;

    @Column("rule_action")
    private int ruleAction;

    @Column("condition")
    private String condition;

    @Column("description")
    private @Nullable String description;

    @Column("enabled")
    private boolean enabled = true;

    @CreatedBy
    @Column("created_by")
    private String createdBy;

    @CreatedDate
    @Column("created_date")
    private LocalDateTime createdDate;

    @LastModifiedBy
    @Column("last_modified_by")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column("last_modified_date")
    private LocalDateTime lastModifiedDate;

    @Version
    private int version;

    public ComponentRule() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getComponentName() {
        return componentName;
    }

    public void setComponentName(String componentName) {
        this.componentName = componentName;
    }

    public @Nullable String getActionName() {
        return actionName;
    }

    public void setActionName(@Nullable String actionName) {
        this.actionName = actionName;
    }

    public RulePhase getPhase() {
        return RulePhase.values()[phase];
    }

    public void setPhase(RulePhase phase) {
        this.phase = phase.ordinal();
    }

    public RuleAction getRuleAction() {
        return RuleAction.values()[ruleAction];
    }

    public void setRuleAction(RuleAction ruleAction) {
        this.ruleAction = ruleAction.ordinal();
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVersion() {
        return version;
    }

    /**
     * Whether this rule governs the given action: either it names that action, or it names none and therefore governs
     * every action of its component.
     */
    public boolean appliesToAction(String actionName) {
        return this.actionName == null || this.actionName.equals(actionName);
    }
}

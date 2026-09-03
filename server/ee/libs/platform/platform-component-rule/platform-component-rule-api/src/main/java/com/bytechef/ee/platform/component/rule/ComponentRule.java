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
 * A conditional, tenant-wide governance rule over a single component tool. Unlike {@code ComponentOperationPolicy},
 * which is a pure deny-list whose row presence is the whole signal, a rule carries a payload: the phase it runs in,
 * what it does when it fires, and the SpEL condition that decides whether it fires at all. That payload has to survive
 * a temporary disable, which is why {@code enabled} is a column rather than row presence.
 *
 * <p>
 * {@code toolName} is nullable: null means the rule applies to every tool of the component. There is deliberately no
 * composite unique key — one component/tool pair may carry several unrelated rules.
 * </p>
 *
 * <p>
 * {@code workspaceId} scopes the rule to one workspace, or, when null, to every workspace in the tenant.
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
        BLOCK, TAG, REQUIRE_APPROVAL
    }

    @Id
    private Long id;

    @Column("component_name")
    private String componentName;

    @Column("tool_name")
    private @Nullable String toolName;

    @Column("workspace_id")
    private @Nullable Long workspaceId;

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

    @Column("strict")
    private boolean strict;

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

    public @Nullable String getToolName() {
        return toolName;
    }

    public void setToolName(@Nullable String toolName) {
        this.toolName = toolName;
    }

    /**
     * The workspace this rule governs, or {@code null} for every workspace in the tenant. A boxed {@link Long} because
     * null is a real state here, not a missing value.
     */
    public @Nullable Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(@Nullable Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    /**
     * Whether this rule governs {@code workspaceId}: either it names that workspace, or it names none and therefore
     * governs every workspace in the tenant.
     *
     * <p>
     * This is the Java twin of the enforcement query's {@code workspace_id IS NULL OR workspace_id = :workspaceId}. The
     * two must always agree — the query decides what actually fires, this decides what a listing claims fires, and a
     * listing that disagrees with enforcement tells an administrator a rule governs their workspace when it does not.
     * Change them together.
     * </p>
     *
     * <p>
     * A {@code null} {@code workspaceId} argument means the caller could not determine a workspace, and matches only
     * tenant-wide rules — narrowing, never widening to another workspace's.
     * </p>
     */
    public boolean appliesToWorkspace(@Nullable Long workspaceId) {
        return this.workspaceId == null || this.workspaceId.equals(workspaceId);
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

    /**
     * Whether an unevaluable condition counts as a match. Default false: the lenient evaluator returns the source text
     * for an unresolved reference, so a mis-authored rule does not fire and a BLOCK rule fails open. A strict rule
     * inverts that for the calls where fail-closed is worth the risk.
     */
    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public int getVersion() {
        return version;
    }

    /**
     * Whether this rule governs the given tool: either it names that tool, or it names none and therefore governs every
     * tool of its component.
     */
    public boolean appliesToTool(String toolName) {
        return this.toolName == null || this.toolName.equals(toolName);
    }
}

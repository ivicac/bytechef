/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.workflow.alert.domain;

import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Workspace membership row for a {@link WorkflowAlertRule}. Workspace is an automation-configuration-owned concept, so
 * alert rules attach to it through this join table instead of carrying a {@code workspace_id} column — the repo-wide
 * {@code workspace_*} membership pattern.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("workspace_workflow_alert_rule")
public class WorkspaceWorkflowAlertRule {

    @Id
    private Long id;

    @Version
    private int version;

    @Column("workspace_id")
    private Long workspaceId;

    @Column("workflow_alert_rule_id")
    private Long workflowAlertRuleId;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    private WorkspaceWorkflowAlertRule() {
    }

    public WorkspaceWorkflowAlertRule(Long workflowAlertRuleId, Long workspaceId) {
        this.workflowAlertRuleId = workflowAlertRuleId;
        this.workspaceId = workspaceId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getWorkflowAlertRuleId() {
        return workflowAlertRuleId;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof WorkspaceWorkflowAlertRule that)) {
            return false;
        }

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

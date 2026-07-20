/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.workflow.execution.cost.domain;

import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Workspace membership row for a {@link WorkflowExecutionCost}. Workspace is an automation-configuration-owned concept,
 * so cost rows attach to it through this join table instead of carrying a {@code workspace_id} column — the repo-wide
 * {@code workspace_*} membership pattern.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("workspace_workflow_execution_cost")
public class WorkspaceWorkflowExecutionCost {

    @Id
    private Long id;

    @Version
    private int version;

    @Column("workspace_id")
    private Long workspaceId;

    @Column("workflow_execution_cost_id")
    private Long workflowExecutionCostId;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    private WorkspaceWorkflowExecutionCost() {
    }

    public WorkspaceWorkflowExecutionCost(Long workflowExecutionCostId, Long workspaceId) {
        this.workflowExecutionCostId = workflowExecutionCostId;
        this.workspaceId = workspaceId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getWorkflowExecutionCostId() {
        return workflowExecutionCostId;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof WorkspaceWorkflowExecutionCost that)) {
            return false;
        }

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

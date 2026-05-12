/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.task;

import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Workspace ↔ {@link com.bytechef.ee.platform.aihub.task.AiHubTask} membership row. Mirrors the workspace_mcp_server /
 * workspace_connection / workspace_asset_file pattern. Unique on (workspace_id, ai_hub_task_id); ON DELETE CASCADE on
 * the task FK clears the row when the task is deleted.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("workspace_ai_hub_task")
public class WorkspaceAiHubTask {

    @Id
    private Long id;

    @Column("workspace_id")
    private Long workspaceId;

    @Column("ai_hub_task_id")
    private Long aiHubTaskId;

    @CreatedBy
    @Column("created_by")
    private String createdBy;

    @CreatedDate
    @Column("created_date")
    private Instant createdDate;

    @LastModifiedBy
    @Column("last_modified_by")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column("last_modified_date")
    private Instant lastModifiedDate;

    @Version
    private int version;

    public WorkspaceAiHubTask() {
    }

    public WorkspaceAiHubTask(Long workspaceId, Long aiHubTaskId) {
        this.workspaceId = workspaceId;
        this.aiHubTaskId = aiHubTaskId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getAiHubTaskId() {
        return aiHubTaskId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        WorkspaceAiHubTask that = (WorkspaceAiHubTask) other;

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkspaceAiHubTask{" +
            "id=" + id +
            ", workspaceId=" + workspaceId +
            ", aiHubTaskId=" + aiHubTaskId +
            '}';
    }
}

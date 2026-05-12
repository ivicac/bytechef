/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.personalagent;

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
 * Workspace ↔ {@link com.bytechef.ee.platform.aihub.personalagent.AiHubPersonalAgent} membership row. Mirrors the
 * pattern used by {@code WorkspaceMcpServer}, {@code WorkspaceConnection}, and {@code WorkspaceAssetFile}: the agent
 * itself is workspace-agnostic; this row is the only place a workspace claims an agent. The unique constraint
 * {@code uk_workspace_ai_hub_personal_agent} on (workspace_id, ai_hub_personal_agent_id) prevents duplicate
 * memberships; {@code ON DELETE CASCADE} on the agent FK clears the row when the agent is deleted.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("workspace_ai_hub_personal_agent")
public class WorkspaceAiHubPersonalAgent {

    @Id
    private Long id;

    @Column("workspace_id")
    private Long workspaceId;

    @Column("ai_hub_personal_agent_id")
    private Long aiHubPersonalAgentId;

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

    public WorkspaceAiHubPersonalAgent() {
    }

    public WorkspaceAiHubPersonalAgent(Long workspaceId, Long aiHubPersonalAgentId) {
        this.workspaceId = workspaceId;
        this.aiHubPersonalAgentId = aiHubPersonalAgentId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getAiHubPersonalAgentId() {
        return aiHubPersonalAgentId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public int getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        WorkspaceAiHubPersonalAgent that = (WorkspaceAiHubPersonalAgent) other;

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkspaceAiHubPersonalAgent{" +
            "id=" + id +
            ", workspaceId=" + workspaceId +
            ", aiHubPersonalAgentId=" + aiHubPersonalAgentId +
            '}';
    }
}

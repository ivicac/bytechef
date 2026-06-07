/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.ai.auto.memory;

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
 * Workspace ↔ {@link AiAutoMemory} membership row. Mirrors the workspace_mcp_server / workspace_ai_hub_* pattern.
 * Unique on {@code (workspace_id, ai_auto_memory_id)}; {@code ON DELETE CASCADE} on the memory FK clears the row when
 * the memory is deleted.
 *
 * @author Ivica Cardic
 */
@Table("workspace_ai_auto_memory")
public class WorkspaceAiAutoMemory {

    @Id
    private Long id;

    @Column("workspace_id")
    private Long workspaceId;

    @Column("ai_auto_memory_id")
    private Long aiAutoMemoryId;

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

    public WorkspaceAiAutoMemory() {
    }

    public WorkspaceAiAutoMemory(Long workspaceId, Long aiAutoMemoryId) {
        this.workspaceId = workspaceId;
        this.aiAutoMemoryId = aiAutoMemoryId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getAiAutoMemoryId() {
        return aiAutoMemoryId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        WorkspaceAiAutoMemory that = (WorkspaceAiAutoMemory) other;

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkspaceAiAutoMemory{" +
            "id=" + id +
            ", workspaceId=" + workspaceId +
            ", aiAutoMemoryId=" + aiAutoMemoryId +
            '}';
    }
}

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

package com.bytechef.automation.knowledgebase.domain;

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
 * Workspace ↔ KnowledgeBaseSource relation entity. Mirrors {@code WorkspaceKnowledgeBase}: each row maps a single
 * platform-side {@code KnowledgeBaseSource} to a workspace, with cascade-delete handled by the
 * {@code fk_workspace_kb_source_source} foreign key.
 *
 * @author Ivica Cardic
 */
@Table("workspace_knowledge_base_source")
public class WorkspaceKnowledgeBaseSource {

    @Column("knowledge_base_source_id")
    private Long knowledgeBaseSourceId;

    @CreatedBy
    @Column("created_by")
    private String createdBy;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    @Id
    private Long id;

    @Column("last_modified_by")
    @LastModifiedBy
    private String lastModifiedBy;

    @Column("last_modified_date")
    @LastModifiedDate
    private Instant lastModifiedDate;

    @Column("workspace_id")
    private Long workspaceId;

    @Version
    private int version;

    private WorkspaceKnowledgeBaseSource() {
    }

    public WorkspaceKnowledgeBaseSource(Long knowledgeBaseSourceId, Long workspaceId) {
        this.knowledgeBaseSourceId = knowledgeBaseSourceId;
        this.workspaceId = workspaceId;
    }

    public Long getKnowledgeBaseSourceId() {
        return knowledgeBaseSourceId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public Long getId() {
        return id;
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

    public Long getWorkspaceId() {
        return workspaceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        WorkspaceKnowledgeBaseSource workspaceKnowledgeBaseSource = (WorkspaceKnowledgeBaseSource) o;

        return Objects.equals(id, workspaceKnowledgeBaseSource.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkspaceKnowledgeBaseSource{" +
            "id=" + id +
            ", knowledgeBaseSourceId=" + knowledgeBaseSourceId +
            ", workspaceId=" + workspaceId +
            ", createdBy='" + createdBy + '\'' +
            ", createdDate=" + createdDate +
            ", lastModifiedBy='" + lastModifiedBy + '\'' +
            ", lastModifiedDate=" + lastModifiedDate +
            ", version=" + version +
            '}';
    }
}

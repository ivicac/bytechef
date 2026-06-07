/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.contextstore.domain;

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
 * Workspace ↔ ContextStore (parent) relation entity. Parallels {@code WorkspaceKnowledgeBase}; coexists with
 * {@link WorkspaceContextStoreSource} so direct workspace→source lookups stay cheap. See spec
 * {@code docs/superpowers/specs/2026-05-13-context-store-multi-env-design.md}.
 *
 * @author Ivica Cardic
 * @version ee
 */
@Table("workspace_context_store")
public class WorkspaceContextStore {

    @Column("context_store_id")
    private Long contextStoreId;

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

    private WorkspaceContextStore() {
    }

    public WorkspaceContextStore(Long contextStoreId, Long workspaceId) {
        this.contextStoreId = contextStoreId;
        this.workspaceId = workspaceId;
    }

    public Long getContextStoreId() {
        return contextStoreId;
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

        WorkspaceContextStore that = (WorkspaceContextStore) o;

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkspaceContextStore{" +
            "id=" + id +
            ", contextStoreId=" + contextStoreId +
            ", workspaceId=" + workspaceId +
            ", createdBy='" + createdBy + '\'' +
            ", createdDate=" + createdDate +
            ", lastModifiedBy='" + lastModifiedBy + '\'' +
            ", lastModifiedDate=" + lastModifiedDate +
            ", version=" + version +
            '}';
    }
}

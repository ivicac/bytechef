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

package com.bytechef.automation.datasync.domain;

import com.bytechef.commons.data.jdbc.wrapper.MapWrapper;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.platform.tag.domain.Tag;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A Data Sync: one source, one destination, an optional field mapping, and a trigger. The rows are the source of truth;
 * the hidden project referenced by {@code projectId} holds the workflow {@code DataSyncWorkflowGenerator} renders from
 * them.
 *
 * @author Ivica Cardic
 */
@Table("data_sync")
public final class DataSync {

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_-]{1,64}$");
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** Persisted as its ordinal; append only. */
    public enum TriggerType {
        MANUAL, SCHEDULE
    }

    @Id
    private Long id;

    @Column("name")
    private String name;

    @Column("title")
    private String title;

    @Column("description")
    private @Nullable String description;

    @Column("workspace_id")
    private @Nullable Long workspaceId;

    @Column("project_id")
    private long projectId;

    @Column("uuid")
    private UUID uuid;

    @Column("trigger_type")
    private int triggerType;

    @Column("trigger_parameters")
    private MapWrapper triggerParameters = new MapWrapper();

    @MappedCollection(idColumn = "data_sync_id")
    private Set<DataSyncTag> dataSyncTags = new HashSet<>();

    @CreatedBy
    @Column("created_by")
    private String createdBy;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    @Column("last_modified_by")
    @LastModifiedBy
    private String lastModifiedBy;

    @Column("last_modified_date")
    @LastModifiedDate
    private Instant lastModifiedDate;

    @Version
    private int version;

    public DataSync() {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DataSync that = (DataSync) o;

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (!NAME_PATTERN.matcher(name)
            .matches()) {

            throw new IllegalArgumentException("name '" + name + "' must match " + NAME_PATTERN.pattern());
        }

        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public @Nullable Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(@Nullable Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public long getProjectId() {
        return projectId;
    }

    public void setProjectId(long projectId) {
        this.projectId = projectId;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public TriggerType getTriggerType() {
        return TriggerType.values()[triggerType];
    }

    public void setTriggerType(TriggerType triggerType) {
        this.triggerType = triggerType.ordinal();
    }

    public Map<String, ?> getTriggerParameters() {
        return triggerParameters.getMap();
    }

    public void setTriggerParameters(@Nullable Map<String, ?> triggerParameters) {
        this.triggerParameters = triggerParameters == null ? new MapWrapper() : new MapWrapper(triggerParameters);
    }

    public List<Long> getTagIds() {
        return dataSyncTags.stream()
            .map(DataSyncTag::getTagId)
            .toList();
    }

    public void setTagIds(List<Long> tagIds) {
        this.dataSyncTags = new HashSet<>();

        if (!CollectionUtils.isEmpty(tagIds)) {
            for (Long tagId : tagIds) {
                dataSyncTags.add(new DataSyncTag(tagId));
            }
        }
    }

    public void setTags(List<Tag> tags) {
        if (tags == null) {
            setTagIds(List.of());
        } else {
            setTagIds(CollectionUtils.map(tags, Tag::getId));
        }
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

    public void setVersion(int version) {
        this.version = version;
    }

    @Override
    public String toString() {
        return "DataSync{" +
            "id=" + id +
            ", name='" + name + '\'' +
            ", title='" + title + '\'' +
            ", workspaceId=" + workspaceId +
            ", projectId=" + projectId +
            ", triggerType=" + getTriggerType() +
            '}';
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.workspace.domain;

import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Workspace membership row for a CE {@code Notification}. A notification with NO membership row is global
 * (deployment-wide, today's pre-scoping behavior); a notification with a membership row is visible and selectable only
 * within that workspace. Workspace is an automation-configuration-owned concept, so the CE {@code notification} table
 * stays untouched and the scoping lives in this EE {@code workspace_*} membership table — the repo-wide pattern.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Table("workspace_notification")
public class WorkspaceNotification {

    @Id
    private Long id;

    @Version
    private int version;

    @Column("workspace_id")
    private Long workspaceId;

    @Column("notification_id")
    private Long notificationId;

    @Column("created_date")
    @CreatedDate
    private Instant createdDate;

    private WorkspaceNotification() {
    }

    public WorkspaceNotification(Long notificationId, Long workspaceId) {
        this.notificationId = notificationId;
        this.workspaceId = workspaceId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof WorkspaceNotification that)) {
            return false;
        }

        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

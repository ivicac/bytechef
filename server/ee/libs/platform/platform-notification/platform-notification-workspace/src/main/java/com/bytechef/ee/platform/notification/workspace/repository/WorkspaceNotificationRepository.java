/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.notification.workspace.repository;

import com.bytechef.ee.platform.notification.workspace.domain.WorkspaceNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Repository
public interface WorkspaceNotificationRepository extends ListCrudRepository<WorkspaceNotification, Long> {

    List<WorkspaceNotification> findAllByWorkspaceId(long workspaceId);

    Optional<WorkspaceNotification> findByNotificationId(long notificationId);

    void deleteByNotificationId(long notificationId);
}

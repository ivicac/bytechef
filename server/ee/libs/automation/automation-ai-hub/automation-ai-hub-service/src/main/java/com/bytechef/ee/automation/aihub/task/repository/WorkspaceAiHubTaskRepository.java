/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.task.repository;

import com.bytechef.ee.automation.aihub.task.WorkspaceAiHubTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Workspace ↔ task membership repository. The task service uses this on every save (to insert the membership row) and
 * on every load (to populate {@code task.workspaceId} after JOIN).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface WorkspaceAiHubTaskRepository extends ListCrudRepository<WorkspaceAiHubTask, Long> {

    List<WorkspaceAiHubTask> findAllByWorkspaceId(long workspaceId);

    Optional<WorkspaceAiHubTask> findByAiHubTaskId(long aiHubTaskId);

    Optional<WorkspaceAiHubTask> findByWorkspaceIdAndAiHubTaskId(
        long workspaceId, long aiHubTaskId);
}

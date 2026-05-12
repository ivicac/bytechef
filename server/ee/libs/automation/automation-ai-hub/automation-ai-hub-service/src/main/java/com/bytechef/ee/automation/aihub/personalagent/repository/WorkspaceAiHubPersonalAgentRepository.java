/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.personalagent.repository;

import com.bytechef.ee.automation.aihub.personalagent.WorkspaceAiHubPersonalAgent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Workspace ↔ personal agent membership repository. The personal-agent service uses this as the index for "list agents
 * in this workspace" queries (load workspace memberships, then fetch the agent rows by id).
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public interface WorkspaceAiHubPersonalAgentRepository
    extends ListCrudRepository<WorkspaceAiHubPersonalAgent, Long> {

    List<WorkspaceAiHubPersonalAgent> findAllByWorkspaceId(long workspaceId);

    Optional<WorkspaceAiHubPersonalAgent> findByAiHubPersonalAgentId(
        long aiHubPersonalAgentId);

    Optional<WorkspaceAiHubPersonalAgent> findByWorkspaceIdAndAiHubPersonalAgentId(
        long workspaceId, long aiHubPersonalAgentId);

    void deleteByAiHubPersonalAgentId(long aiHubPersonalAgentId);
}

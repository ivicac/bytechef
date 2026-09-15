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

package com.bytechef.automation.ai.mcp.event;

import com.bytechef.automation.ai.mcp.domain.McpProject;
import com.bytechef.automation.ai.mcp.service.McpProjectService;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.platform.mcp.repository.McpServerRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.relational.core.mapping.event.AbstractRelationalEventListener;
import org.springframework.data.relational.core.mapping.event.AfterSaveEvent;
import org.springframework.data.relational.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

/**
 * Event listener that triggers actions after an MCP server is saved. Specifically, it is responsible for managing
 * project deployment triggers based on the state of the saved MCP server entity.
 *
 * This class extends the generic {@link AbstractRelationalEventListener}, allowing it to listen to and process
 * {@link AfterSaveEvent} events for entities of type {@link McpServer}.
 *
 * The primary function of this listener is to enable or disable associated project deployments based on the saved MCP
 * server's properties.
 *
 * @author Ivica Cardic
 */
@Component
public class McpServerAfterSaveEventListener extends AbstractRelationalEventListener<McpServer> {

    private static final ThreadLocal<Map<Long, Boolean>> STORED_ENABLED_FLAGS = ThreadLocal.withInitial(HashMap::new);

    private final McpProjectService mcpProjectService;
    private final McpServerRepository mcpServerRepository;
    private final ProjectDeploymentFacade projectDeploymentFacade;

    @SuppressFBWarnings("EI")
    public McpServerAfterSaveEventListener(
        McpProjectService mcpProjectService, McpServerRepository mcpServerRepository,
        ProjectDeploymentFacade projectDeploymentFacade) {

        this.mcpProjectService = mcpProjectService;
        this.mcpServerRepository = mcpServerRepository;
        this.projectDeploymentFacade = projectDeploymentFacade;
    }

    /**
     * Records the stored {@code enabled} flag of a server about to be updated, so {@link #onAfterSave} can tell a
     * toggle from a rename or a tag change. Every save converts before it saves, so an entry left behind by a save that
     * failed in between is overwritten by the next save of the same server on this thread.
     */
    @Override
    protected void onBeforeConvert(BeforeConvertEvent<McpServer> event) {
        McpServer mcpServer = event.getEntity();

        Long mcpServerId = mcpServer.getId();

        if (mcpServerId == null) {
            return;
        }

        Map<Long, Boolean> storedEnabledFlags = STORED_ENABLED_FLAGS.get();

        mcpServerRepository.findById(mcpServerId)
            .ifPresentOrElse(
                storedMcpServer -> storedEnabledFlags.put(mcpServerId, storedMcpServer.isEnabled()),
                () -> storedEnabledFlags.remove(mcpServerId));
    }

    /**
     * Toggles the server's project deployments only when the save changed its {@code enabled} flag, or when the stored
     * flag is unknown (a newly created server). Re-arming on every save re-registered each trigger with its provider on
     * a rename, and required {@code DEPLOYMENT_EDIT} on every deployment from anyone renaming the server.
     */
    @Override
    protected void onAfterSave(AfterSaveEvent<McpServer> event) {
        McpServer mcpServer = event.getEntity();

        Map<Long, Boolean> storedEnabledFlags = STORED_ENABLED_FLAGS.get();

        Boolean storedEnabled = storedEnabledFlags.remove(mcpServer.getId());

        if (storedEnabledFlags.isEmpty()) {
            STORED_ENABLED_FLAGS.remove();
        }

        if (storedEnabled != null && storedEnabled == mcpServer.isEnabled()) {
            return;
        }

        checkProjectDeploymentTriggers(mcpServer.getId(), mcpServer.isEnabled());
    }

    /**
     * Asks every deployment's authorization questions before toggling any. The listener runs inside the server save's
     * transaction, so a denial on a later deployment rolls back the earlier ones' rows -- but not the webhook and
     * listener subscriptions their triggers already registered with external providers.
     */
    private void checkProjectDeploymentTriggers(long mcpServerId, boolean enabled) {
        List<McpProject> mcpProjects = mcpProjectService.getMcpServerMcpProjects(mcpServerId);

        for (McpProject mcpProject : mcpProjects) {
            projectDeploymentFacade.checkEnableProjectDeployment(mcpProject.getProjectDeploymentId(), enabled);
        }

        for (McpProject mcpProject : mcpProjects) {
            projectDeploymentFacade.enableProjectDeployment(mcpProject.getProjectDeploymentId(), enabled);
        }
    }
}

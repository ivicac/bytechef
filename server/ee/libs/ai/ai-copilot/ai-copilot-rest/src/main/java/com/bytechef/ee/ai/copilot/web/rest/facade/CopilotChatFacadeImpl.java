/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.rest.facade;

import com.agui.core.state.State;
import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.bytechef.ai.copilot.constant.CopilotConstants;
import com.bytechef.ai.copilot.util.Mode;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.configuration.facade.IntegrationWorkflowFacade;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import com.bytechef.tenant.TenantContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @version ee
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@ConditionalOnCoordinator
@ConditionalOnProperty(prefix = "bytechef.ai.copilot", name = "enabled", havingValue = "true")
class CopilotChatFacadeImpl implements CopilotChatFacade {

    private static final String BUILD_AGENT_ID_SUFFIX = "_build";
    private static final String WORKFLOW_EDITOR_EMBEDDED_AGENT_ID = "workflow_editor_embedded";

    private final Map<String, LocalAgent> localAgentMap;
    private final AgUiService agUiService;
    private final IntegrationWorkflowFacade integrationWorkflowFacade;
    private final PermissionService permissionService;
    private final ProjectWorkflowService projectWorkflowService;
    private final UserService userService;

    @SuppressFBWarnings("EI")
    CopilotChatFacadeImpl(
        AgUiService agUiService, List<LocalAgent> localAgents, Optional<PermissionService> permissionService,
        Optional<ProjectWorkflowService> projectWorkflowService, Optional<UserService> userService,
        Optional<IntegrationWorkflowFacade> integrationWorkflowFacade) {

        this.agUiService = agUiService;
        this.integrationWorkflowFacade = integrationWorkflowFacade.orElse(null);
        this.localAgentMap = localAgents.stream()
            .collect(Collectors.toMap(LocalAgent::getAgentId, localAgent -> localAgent));
        this.permissionService = permissionService.orElse(null);
        this.projectWorkflowService = projectWorkflowService.orElse(null);
        this.userService = userService.orElse(null);
    }

    @Override
    public SseEmitter chat(String agentId, AgUiParameters agUiParameters) {
        State state = agUiParameters.getState();
        Map<String, Object> stateMap = state.getState();
        String resolvedAgentId = resolveAgentId(agentId, parseMode(stateMap.get("mode")));

        LocalAgent localAgent = localAgentMap.get(resolvedAgentId);

        if (localAgent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agentId: " + resolvedAgentId);
        }

        boolean build = resolvedAgentId.endsWith(BUILD_AGENT_ID_SUFFIX);

        if (agentId.equals(WORKFLOW_EDITOR_EMBEDDED_AGENT_ID)) {
            authorizeIntegrationWorkflowAccess(stateMap);
        } else {
            authorizeWorkflowAccess(stateMap, build);
        }

        authorizeWorkspaceAccess(stateMap, build);

        stateMap.remove(CopilotConstants.STATE_AUTHENTICATED_USER_ID);

        injectAuthenticatedUserId(stateMap);
        stateMap.put(CopilotConstants.STATE_TENANT_ID, TenantContext.getCurrentTenantId());

        return agUiService.runAgent(localAgent, agUiParameters);
    }

    private static String resolveAgentId(String agentId, @Nullable Mode mode) {
        if (agentId.equals("converter")) {
            return "converter" + BUILD_AGENT_ID_SUFFIX;
        }

        if (agentId.equals("mcp_server")) {
            return "mcp_server" + BUILD_AGENT_ID_SUFFIX;
        }

        if (mode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The run state has no mode");
        }

        return agentId + "_" + mode.name()
            .toLowerCase();
    }

    private void injectAuthenticatedUserId(Map<String, Object> stateMap) {
        if (userService == null) {
            return;
        }

        SecurityUtils.fetchCurrentUserLogin()
            .flatMap(userService::fetchUserByLogin)
            .map(User::getId)
            .ifPresent(userId -> stateMap.put(CopilotConstants.STATE_AUTHENTICATED_USER_ID, userId));
    }

    private void authorizeIntegrationWorkflowAccess(Map<String, Object> stateMap) {
        if (!(stateMap.get("workflowId") instanceof String workflowId) || workflowId.isBlank()) {
            return;
        }

        if (integrationWorkflowFacade == null) {
            throw new AccessDeniedException("Workflow authorization is not available");
        }

        try {
            integrationWorkflowFacade.getIntegrationWorkflow(workflowId);
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Access denied to workflow " + workflowId, exception);
        }
    }

    private void authorizeWorkflowAccess(Map<String, Object> stateMap, boolean build) {
        if (!(stateMap.get("workflowId") instanceof String workflowId) || workflowId.isBlank()) {
            return;
        }

        if (permissionService == null || projectWorkflowService == null) {
            throw new AccessDeniedException("Workflow authorization is not available");
        }

        long projectId = projectWorkflowService.getWorkflowProjectWorkflow(workflowId)
            .getProjectId();

        if (!permissionService.hasWorkspaceScopeForProject(projectId, build ? "WORKFLOW_EDIT" : "WORKFLOW_VIEW")) {
            throw new AccessDeniedException("Access denied to workflow " + workflowId);
        }
    }

    private void authorizeWorkspaceAccess(Map<String, Object> stateMap, boolean build) {
        Object workspaceIdValue = stateMap.get(CopilotConstants.STATE_WORKSPACE_ID);

        if (workspaceIdValue == null) {
            return;
        }

        long workspaceId = parseWorkspaceId(workspaceIdValue);

        if (permissionService == null) {
            throw new AccessDeniedException("Workspace authorization is not available");
        }

        if (!permissionService.hasWorkspaceRole(workspaceId, build ? "EDITOR" : "VIEWER")) {
            throw new AccessDeniedException("Access denied to workspace " + workspaceId);
        }
    }

    private static long parseWorkspaceId(Object workspaceIdValue) {
        if (workspaceIdValue instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(workspaceIdValue));
        } catch (NumberFormatException exception) {
            throw new AccessDeniedException("Invalid workspace id");
        }
    }

    private static @Nullable Mode parseMode(@Nullable Object mode) {
        if (mode == null) {
            return null;
        }

        try {
            return Mode.valueOf(String.valueOf(mode));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown mode: " + mode);
        }
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.rest.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agui.core.state.State;
import com.agui.server.LocalAgent;
import com.agui.server.spring.AgUiParameters;
import com.agui.server.spring.AgUiService;
import com.bytechef.ai.copilot.constant.CopilotConstants;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.platform.user.domain.User;
import com.bytechef.platform.user.service.UserService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pins the mode-dependent workflow-scope guard that moved off {@code CopilotApiController.chat}'s method body onto this
 * facade. Three of these tests go red if the guard is deleted: {@code testChatDeniedWhenUserLacksWorkflowScope} (an ASK
 * turn needs {@code WORKFLOW_VIEW}), {@code testChatBuildRequiresWorkflowEditScope} (a BUILD turn needs the stronger
 * {@code WORKFLOW_EDIT}, so collapsing the two scopes to make one annotation fit is caught here as well as outright
 * deletion), and {@code testChatFailsClosedWhenAuthorizationServicesAbsent} (an app variant without the authorization
 * services must refuse the run rather than skip the check).
 *
 * <p>
 * Pins the workspace guard beside it, which closes a hole the workflow guard never covered: a run carrying no
 * {@code workflowId} skips the workflow guard entirely, so before the workspace guard existed such a run was authorized
 * by nothing at all while still handing every workspace-scoped tool a client-supplied workspace id.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class CopilotChatFacadeImplTest {

    private static final String LOGIN = "user@localhost.com";
    private static final long USER_ID = 7L;
    private static final long WORKSPACE_ID = 1L;

    private final AgUiService agUiService = mock(AgUiService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);
    private final UserService userService = mock(UserService.class);
    private final WorkspaceFacade workspaceFacade = mock(WorkspaceFacade.class);

    private final CopilotChatFacadeImpl facade = new CopilotChatFacadeImpl(
        agUiService, List.of(localAgent("converter_build"), localAgent("workflow_editor_ask")),
        Optional.of(permissionService), Optional.of(projectWorkflowService), Optional.empty(), Optional.empty());

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testChatDeniedWhenUserLacksWorkflowScope() {
        AgUiParameters parameters = parameters(Map.<String, Object>of("workflowId", "wf1", "mode", "ASK"));

        givenWorkflowProject("wf1", 42L);

        when(permissionService.hasWorkspaceScopeForProject(42L, "WORKFLOW_VIEW")).thenReturn(false);

        assertThatThrownBy(() -> facade.chat("workflow_editor", parameters))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Access denied to workflow wf1");

        verify(agUiService, never()).runAgent(any(), any());
    }

    @Test
    void testChatBuildRequiresWorkflowEditScope() {
        AgUiParameters parameters = parameters(Map.<String, Object>of("workflowId", "wf1", "mode", "BUILD"));

        givenWorkflowProject("wf1", 42L);

        when(permissionService.hasWorkspaceScopeForProject(42L, "WORKFLOW_EDIT")).thenReturn(false);

        assertThatThrownBy(() -> facade.chat("workflow_editor", parameters))
            .isInstanceOf(AccessDeniedException.class);

        verify(permissionService).hasWorkspaceScopeForProject(42L, "WORKFLOW_EDIT");
    }

    @Test
    void testChatAllowedWhenUserHasScope() {
        AgUiParameters parameters = parameters(Map.<String, Object>of("workflowId", "wf1", "mode", "ASK"));

        givenWorkflowProject("wf1", 42L);

        when(permissionService.hasWorkspaceScopeForProject(42L, "WORKFLOW_VIEW")).thenReturn(true);
        when(agUiService.runAgent(any(), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_editor", parameters);

        verify(agUiService).runAgent(any(), any());
    }

    @Test
    void testChatWithoutWorkflowIdSkipsAuthorization() {
        AgUiParameters parameters = parameters(Map.<String, Object>of("mode", "BUILD"));

        when(agUiService.runAgent(any(), any())).thenReturn(new SseEmitter());

        facade.chat("converter", parameters);

        verify(agUiService).runAgent(any(), any());
        verify(permissionService, never()).hasWorkspaceScopeForProject(anyLong(), any());
    }

    @Test
    void testChatFailsClosedWhenAuthorizationServicesAbsent() {
        CopilotChatFacadeImpl facadeWithoutServices = new CopilotChatFacadeImpl(
            agUiService, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        AgUiParameters parameters = parameters(Map.<String, Object>of("workflowId", "wf1", "mode", "ASK"));

        assertThatThrownBy(() -> facadeWithoutServices.chat("workflow_editor", parameters))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Workflow authorization is not available");

        verify(agUiService, never()).runAgent(any(), any());
    }

    @Test
    void testChatRejectsAWorkspaceTheUserIsNotAMemberOf() {
        Map<String, Object> stateMap = new HashMap<>(
            Map.of("mode", "BUILD", CopilotConstants.STATE_WORKSPACE_ID, 99L));

        givenAuthenticatedUser();
        givenUserWorkspaces(WORKSPACE_ID);

        assertThatThrownBy(() -> workspaceAwareFacade().chat("project", mutableParameters(stateMap)))
            .as("a client naming a workspace it cannot access must be refused, not silently scoped to it")
            .isInstanceOf(AccessDeniedException.class);

        verify(agUiService, never()).runAgent(any(), any());
    }

    @Test
    void testChatInjectsTheVerifiedWorkspaceAndOverwritesTheClientSuppliedOne() {
        Map<String, Object> stateMap = new HashMap<>(Map.of("mode", "BUILD", CopilotConstants.STATE_WORKSPACE_ID, 1));

        givenAuthenticatedUser();
        givenUserWorkspaces(WORKSPACE_ID);

        when(agUiService.runAgent(any(), any())).thenReturn(new SseEmitter());

        workspaceAwareFacade().chat("project", mutableParameters(stateMap));

        assertThat(stateMap).containsEntry(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, WORKSPACE_ID);
        assertThat(stateMap)
            .as("the unverified key is overwritten too, so a later regression reading it still gets "
                + "server-controlled data")
            .containsEntry(CopilotConstants.STATE_WORKSPACE_ID, WORKSPACE_ID);
    }

    @Test
    void testChatFailsClosedWhenWorkspaceAuthorizationIsNotWired() {
        Map<String, Object> stateMap = new HashMap<>(Map.of("mode", "BUILD", CopilotConstants.STATE_WORKSPACE_ID, 1L));

        givenAuthenticatedUser();

        assertThatThrownBy(() -> facadeWithoutWorkspaceFacade().chat("project", mutableParameters(stateMap)))
            .as("an app variant without the workspace facade must refuse, not skip the check")
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Workspace authorization is not available");

        verify(agUiService, never()).runAgent(any(), any());
    }

    @Test
    void testChatWithoutWorkspaceIdSkipsWorkspaceAuthorization() {
        Map<String, Object> stateMap = new HashMap<>(Map.of("mode", "BUILD"));

        givenAuthenticatedUser();

        when(agUiService.runAgent(any(), any())).thenReturn(new SseEmitter());

        workspaceAwareFacade().chat("project", mutableParameters(stateMap));

        verify(workspaceFacade, never()).getUserWorkspaces(anyLong());

        assertThat(stateMap)
            .as("a surface that carries no workspace id must run, not be refused")
            .doesNotContainKey(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID);
    }

    @Test
    void testChatVerifiesAWorkspaceIdSuppliedAsAString() {
        Map<String, Object> stateMap = new HashMap<>(Map.of("mode", "BUILD", CopilotConstants.STATE_WORKSPACE_ID, "1"));

        givenAuthenticatedUser();
        givenUserWorkspaces(WORKSPACE_ID);

        when(agUiService.runAgent(any(), any())).thenReturn(new SseEmitter());

        workspaceAwareFacade().chat("project", mutableParameters(stateMap));

        verify(workspaceFacade).getUserWorkspaces(USER_ID);

        assertThat(stateMap)
            .as("a string-form id is a real client shape and must be checked, not waved through")
            .containsEntry(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, WORKSPACE_ID);
    }

    @Test
    void testChatRefusesAWorkspaceIdThatDoesNotParse() {
        // The verified key is seeded with a forged value on purpose: asserting its absence below is vacuous unless
        // something had to remove it, and a client can put it there.
        Map<String, Object> stateMap = new HashMap<>(
            Map.of(
                "mode", "BUILD", CopilotConstants.STATE_WORKSPACE_ID, "99.0",
                CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, 99L));

        givenAuthenticatedUser();
        givenUserWorkspaces(WORKSPACE_ID);

        assertThatThrownBy(() -> workspaceAwareFacade().chat("project", mutableParameters(stateMap)))
            .as("skipping the check on a value this parser rejects would hand NumberUtils.asLong's BigDecimal "
                + "fallback an unauthorized 99 downstream")
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Malformed workspace id in request state");

        assertThat(stateMap)
            .as("the forged verified id seeded above must be gone, so a refused request cannot leave a "
                + "client-chosen workspace behind for anything downstream to read")
            .doesNotContainKey(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID);

        verify(agUiService, never()).runAgent(any(), any());
    }

    @Test
    void testChatStripsAClientSuppliedVerifiedWorkspaceId() {
        Map<String, Object> stateMap = new HashMap<>(
            Map.of("mode", "BUILD", CopilotConstants.STATE_VERIFIED_WORKSPACE_ID, 99L));

        givenAuthenticatedUser();

        when(agUiService.runAgent(any(), any())).thenReturn(new SseEmitter());

        workspaceAwareFacade().chat("project", mutableParameters(stateMap));

        assertThat(stateMap)
            .as("the run state is the raw request body, so a forged verified key must not survive the early "
                + "return taken when no workspace id is named")
            .doesNotContainKey(CopilotConstants.STATE_VERIFIED_WORKSPACE_ID);
    }

    @Test
    void testChatRoutesWorkflowCodeEditorAsk() {
        LocalAgent agent = localAgent("workflow_code_editor_ask");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_code_editor", agUiParameters("ASK"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesWorkflowCodeEditorBuild() {
        LocalAgent agent = localAgent("workflow_code_editor_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_code_editor", agUiParameters("BUILD"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesWorkflowEditorEmbeddedAsk() {
        LocalAgent agent = localAgent("workflow_editor_embedded_ask");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_editor_embedded", agUiParameters("ASK"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesWorkflowEditorEmbeddedBuild() {
        LocalAgent agent = localAgent("workflow_editor_embedded_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_editor_embedded", agUiParameters("BUILD"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesWorkflowExecutionEmbeddedAsk() {
        LocalAgent agent = localAgent("workflow_execution_embedded_ask");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_execution_embedded", agUiParameters("ASK"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesWorkflowExecutionEmbeddedBuild() {
        LocalAgent agent = localAgent("workflow_execution_embedded_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("workflow_execution_embedded", agUiParameters("BUILD"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesCodeWorkflowAsk() {
        LocalAgent agent = localAgent("code_workflow_ask");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("code_workflow", agUiParameters("ASK"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesCodeWorkflowBuild() {
        LocalAgent agent = localAgent("code_workflow_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("code_workflow", agUiParameters("BUILD"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesCodeWorkflowEmbeddedAsk() {
        LocalAgent agent = localAgent("code_workflow_embedded_ask");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("code_workflow_embedded", agUiParameters("ASK"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesCodeWorkflowEmbeddedBuild() {
        LocalAgent agent = localAgent("code_workflow_embedded_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("code_workflow_embedded", agUiParameters("BUILD"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesProjectToBuildByDefaultRule() {
        LocalAgent agent = localAgent("project_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("project", agUiParameters("BUILD"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRoutesConverterToBuildRegardlessOfMode() {
        LocalAgent agent = localAgent("converter_build");
        CopilotChatFacadeImpl facade = facadeWith(agent);

        when(agUiService.runAgent(eq(agent), any())).thenReturn(new SseEmitter());

        facade.chat("converter", agUiParameters("ASK"));

        verify(agUiService).runAgent(eq(agent), any());
    }

    @Test
    void testChatRejectsUnregisteredAgentWithClientError() {
        CopilotChatFacadeImpl facadeWithoutAgents = new CopilotChatFacadeImpl(
            agUiService, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> facadeWithoutAgents.chat("workflow_editor", agUiParameters("ASK")))
            .isInstanceOf(ResponseStatusException.class)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

        verify(agUiService, never()).runAgent(any(), any());
    }

    private AgUiParameters agUiParameters(String mode) {
        return parameters(Map.<String, Object>of("mode", mode));
    }

    private LocalAgent localAgent(String agentId) {
        LocalAgent localAgent = mock(LocalAgent.class);

        when(localAgent.getAgentId()).thenReturn(agentId);

        return localAgent;
    }

    private CopilotChatFacadeImpl facadeWith(LocalAgent localAgent) {
        return new CopilotChatFacadeImpl(
            agUiService, List.of(localAgent), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private AgUiParameters parameters(Map<String, Object> stateMap) {
        return mutableParameters(new HashMap<>(stateMap));
    }

    /**
     * Backs the returned parameters with {@code stateMap} itself rather than a copy, so a test can assert on what the
     * facade wrote into the run state.
     */
    private AgUiParameters mutableParameters(Map<String, Object> stateMap) {
        State state = mock(State.class);

        when(state.getState()).thenReturn(stateMap);

        AgUiParameters parameters = mock(AgUiParameters.class);

        when(parameters.getState()).thenReturn(state);

        return parameters;
    }

    private void givenWorkflowProject(String workflowId, long projectId) {
        ProjectWorkflow projectWorkflow = mock(ProjectWorkflow.class);

        when(projectWorkflow.getProjectId()).thenReturn(projectId);
        when(projectWorkflowService.getWorkflowProjectWorkflow(workflowId)).thenReturn(projectWorkflow);
    }

    private void givenAuthenticatedUser() {
        SecurityContext securityContext = SecurityContextHolder.getContext();

        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(LOGIN, null, List.of()));

        User user = mock(User.class);

        when(user.getId()).thenReturn(USER_ID);
        when(userService.fetchUserByLogin(LOGIN)).thenReturn(Optional.of(user));
    }

    private void givenUserWorkspaces(long... workspaceIds) {
        List<Workspace> workspaces = new ArrayList<>(workspaceIds.length);

        for (long workspaceId : workspaceIds) {
            Workspace workspace = mock(Workspace.class);

            when(workspace.getId()).thenReturn(workspaceId);

            workspaces.add(workspace);
        }

        when(workspaceFacade.getUserWorkspaces(USER_ID)).thenReturn(workspaces);
    }

    private CopilotChatFacadeImpl workspaceAwareFacade() {
        return new CopilotChatFacadeImpl(
            agUiService, List.of(localAgent("project_build")), Optional.of(permissionService),
            Optional.of(projectWorkflowService), Optional.of(userService), Optional.of(workspaceFacade));
    }

    private CopilotChatFacadeImpl facadeWithoutWorkspaceFacade() {
        return new CopilotChatFacadeImpl(
            agUiService, List.of(localAgent("project_build")), Optional.of(permissionService),
            Optional.of(projectWorkflowService), Optional.of(userService), Optional.empty());
    }
}

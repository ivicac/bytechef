/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.dto.AutomationWorkflowProjectDTO;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserWorkflowTemplateDTO;
import com.bytechef.ee.embedded.configuration.exception.CodeWorkflowNotCopyableException;
import com.bytechef.ee.embedded.configuration.service.ConnectedUserProjectWorkflowService;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Provisioning-time authorization for {@link ConnectedUserProjectFacadeImpl#copyWorkflowTemplate}: the requested uuid
 * must belong to a template the PERMISSION-FILTERED automation workflow would show the requesting connected user, not
 * merely to some published automation workflow template somewhere in the tenant.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
class ConnectedUserProjectFacadeCopyTemplateAuthorizationTest {

    private static final String EXTERNAL_USER_ID = "ext-user-1";
    private static final String HIDDEN_WORKFLOW_UUID = "hidden-uuid";
    private static final String UNKNOWN_WORKFLOW_UUID = "no-such-uuid";
    private static final String VISIBLE_WORKFLOW_UUID = "visible-uuid";

    private final AutomationWorkflowProjectFacade automationWorkflowProjectFacade =
        mock(AutomationWorkflowProjectFacade.class);
    private final ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager =
        mock(ConnectedUserProjectWorkflowManager.class);
    private final ConnectedUserProjectWorkflowService connectedUserProjectWorkflowService =
        mock(ConnectedUserProjectWorkflowService.class);
    private final ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);

    private final ConnectedUserProjectFacadeImpl facade = new ConnectedUserProjectFacadeImpl(
        automationWorkflowProjectFacade, null, null, null, connectedUserProjectWorkflowManager, null,
        connectedUserProjectWorkflowService, null, null, mock(ConnectedUserWorkflowWebhookUrlResolver.class), null,
        null, null, null, null, null, null, null, null, null, null, null, null, projectWorkflowService, null, null,
        workflowService, null, null);

    @Test
    void testCopyWorkflowTemplateCopiesATemplateTheConnectedUserIsPermittedToSee() {
        givenTenantAutomationWorkflowsContains(VISIBLE_WORKFLOW_UUID, HIDDEN_WORKFLOW_UUID);
        givenVisibleAutomationWorkflowsContains(VISIBLE_WORKFLOW_UUID);

        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(1L);

        when(connectedUserProjectWorkflowManager.getOrCreateConnectedUserProject(
            EXTERNAL_USER_ID, Environment.PRODUCTION)).thenReturn(connectedUserProject);
        when(projectWorkflowService.getLastPublishedWorkflowId(VISIBLE_WORKFLOW_UUID)).thenReturn("workflow-1");
        when(workflowService.getWorkflow("workflow-1"))
            .thenReturn(new Workflow("{\"tasks\":[]}", Workflow.Format.JSON));
        when(connectedUserProjectWorkflowManager.createProjectWorkflow(
            eq(EXTERNAL_USER_ID), any(), eq(Environment.PRODUCTION), eq(VISIBLE_WORKFLOW_UUID)))
                .thenReturn("copy-uuid");

        String copyWorkflowUuid = facade.copyWorkflowTemplate(
            EXTERNAL_USER_ID, VISIBLE_WORKFLOW_UUID, Environment.PRODUCTION);

        assertThat(copyWorkflowUuid).isEqualTo("copy-uuid");
    }

    @Test
    void testCopyWorkflowTemplateRejectsATemplateHiddenByThePermissionExpression() {
        givenTenantAutomationWorkflowsContains(VISIBLE_WORKFLOW_UUID, HIDDEN_WORKFLOW_UUID);
        givenVisibleAutomationWorkflowsContains(VISIBLE_WORKFLOW_UUID);

        assertThatThrownBy(
            () -> facade.copyWorkflowTemplate(EXTERNAL_USER_ID, HIDDEN_WORKFLOW_UUID, Environment.PRODUCTION))
                .isInstanceOf(IllegalArgumentException.class);

        verify(connectedUserProjectWorkflowManager, never())
            .createProjectWorkflow(any(), any(), any(), any());
    }

    /**
     * The point of the rejection: a caller must not be able to tell a template that exists but is hidden from them
     * apart from a uuid that does not exist at all. Same exception type, and a message that differs only by the uuid
     * the caller itself supplied.
     */
    @Test
    void testHiddenTemplateRejectionIsIndistinguishableFromAnUnknownUuid() {
        givenTenantAutomationWorkflowsContains(VISIBLE_WORKFLOW_UUID, HIDDEN_WORKFLOW_UUID);
        givenVisibleAutomationWorkflowsContains(VISIBLE_WORKFLOW_UUID);

        Throwable hiddenThrowable = catchThrowable(
            () -> facade.copyWorkflowTemplate(EXTERNAL_USER_ID, HIDDEN_WORKFLOW_UUID, Environment.PRODUCTION));
        Throwable unknownThrowable = catchThrowable(
            () -> facade.copyWorkflowTemplate(EXTERNAL_USER_ID, UNKNOWN_WORKFLOW_UUID, Environment.PRODUCTION));

        assertThat(hiddenThrowable).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(unknownThrowable).isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(hiddenThrowable).hasMessage(rejectionMessage(HIDDEN_WORKFLOW_UUID));
        assertThat(unknownThrowable).hasMessage(rejectionMessage(UNKNOWN_WORKFLOW_UUID));
    }

    /**
     * A code workflow template can only be referenced: copying one is refused before anything is copied.
     */
    @Test
    void testCopyWorkflowTemplateRejectsCodeWorkflowTemplate() {
        stubVisibleAutomationWorkflowTemplate(VISIBLE_WORKFLOW_UUID, true);

        assertThatThrownBy(
            () -> facade.copyWorkflowTemplate(EXTERNAL_USER_ID, VISIBLE_WORKFLOW_UUID, Environment.PRODUCTION))
                .isInstanceOf(CodeWorkflowNotCopyableException.class);

        verify(connectedUserProjectWorkflowManager, never())
            .createProjectWorkflow(any(), any(), any(), any());
    }

    private void stubVisibleAutomationWorkflowTemplate(String workflowUuid, boolean codeWorkflowProject) {
        when(automationWorkflowProjectFacade.getPublishedProjects(anyString(), any()))
            .thenReturn(List.of(automationWorkflowProject(codeWorkflowProject, workflowUuid)));
    }

    private void givenTenantAutomationWorkflowsContains(String... workflowUuids) {
        when(automationWorkflowProjectFacade.getPublishedProjects())
            .thenReturn(List.of(automationWorkflowProject(workflowUuids)));
    }

    private void givenVisibleAutomationWorkflowsContains(String... workflowUuids) {
        when(automationWorkflowProjectFacade.getPublishedProjects(EXTERNAL_USER_ID, Environment.PRODUCTION))
            .thenReturn(List.of(automationWorkflowProject(workflowUuids)));
    }

    private static AutomationWorkflowProjectDTO automationWorkflowProject(String... workflowUuids) {
        return automationWorkflowProject(false, workflowUuids);
    }

    private static AutomationWorkflowProjectDTO
        automationWorkflowProject(boolean codeWorkflowProject, String... workflowUuids) {
        List<ConnectedUserWorkflowTemplateDTO> workflowTemplates = Stream.of(workflowUuids)
            .map(workflowUuid -> new ConnectedUserWorkflowTemplateDTO(
                workflowUuid, "Label", "Description", null, List.of(), List.of(), List.of(), null))
            .toList();

        return new AutomationWorkflowProjectDTO(
            1L, "Automation workflow", "", null, List.of(), true, 1, 1, workflowTemplates, null, codeWorkflowProject,
            true);
    }

    private static String rejectionMessage(String workflowUuid) {
        return "Not a published automation workflow template: " + workflowUuid;
    }
}

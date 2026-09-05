/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies {@link ConnectedUserProjectFacadeImpl#updateProjectWorkflowInputs}: the values a connected user supplied on
 * the hub's configure step land on the project deployment that runs their workflow, and a workflowUuid that is not in
 * that user's own project is refused.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ConnectedUserProjectFacadeWorkflowInputsTest {

    private static final String EXTERNAL_USER_ID = "ext-1";
    private static final String WORKFLOW_ID = "wf-id-1";
    private static final String WORKFLOW_UUID = "wf-uuid-1";

    @Mock
    private ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private ProjectDeploymentService projectDeploymentService;

    @Mock
    private ProjectDeploymentWorkflowService projectDeploymentWorkflowService;

    @Mock
    private ProjectWorkflowService projectWorkflowService;

    private ConnectedUserProjectFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new ConnectedUserProjectFacadeImpl(
            null, null, null, null, connectedUserProjectWorkflowManager, null, null, null, null, null,
            environmentService, null, null, null, null, null, projectDeploymentService,
            projectDeploymentWorkflowService, null, null, null, projectWorkflowService, null, null, null, null, null);

        ConnectedUserProject connectedUserProject = new ConnectedUserProject();

        connectedUserProject.setId(10L);
        connectedUserProject.setConnectedUserId(7L);
        connectedUserProject.setProjectId(20L);

        when(environmentService.getEnvironment(0L)).thenReturn(Environment.PRODUCTION);

        when(connectedUserProjectWorkflowManager
            .getOrCreateConnectedUserProject(EXTERNAL_USER_ID, Environment.PRODUCTION))
                .thenReturn(connectedUserProject);
    }

    @Test
    void testUpdateProjectWorkflowInputsWritesValuesToTheDeploymentWorkflow() {
        when(projectWorkflowService.fetchLastProjectWorkflowId(20L, WORKFLOW_UUID))
            .thenReturn(Optional.of(WORKFLOW_ID));
        when(projectDeploymentService.getProjectDeploymentId(20L, Environment.PRODUCTION)).thenReturn(30L);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        when(projectDeploymentWorkflowService.getProjectDeploymentWorkflow(30L, WORKFLOW_ID))
            .thenReturn(projectDeploymentWorkflow);

        facade.updateProjectWorkflowInputs(EXTERNAL_USER_ID, WORKFLOW_UUID, Map.of("sheetName", "Leads"), 0L);

        ArgumentCaptor<ProjectDeploymentWorkflow> captor = ArgumentCaptor.forClass(ProjectDeploymentWorkflow.class);

        verify(projectDeploymentWorkflowService).update(captor.capture());

        ProjectDeploymentWorkflow updated = captor.getValue();

        Map<String, ?> inputs = updated.getInputs();

        assertThat(inputs).hasSize(1);
        assertThat((Object) inputs.get("sheetName")).isEqualTo("Leads");
    }

    /**
     * The lookup runs against the connected user's OWN project, so another user's workflowUuid is simply absent from
     * it. That surfaces as not-found rather than as a permission error, which is what stops a connected user probing
     * uuids to learn which ones exist.
     */
    @Test
    void testUpdateProjectWorkflowInputsRefusesAWorkflowOutsideTheConnectedUsersProject() {
        when(projectWorkflowService.fetchLastProjectWorkflowId(20L, WORKFLOW_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(
            () -> facade.updateProjectWorkflowInputs(
                EXTERNAL_USER_ID, WORKFLOW_UUID, Map.of("sheetName", "Leads"), 0L))
                    .isInstanceOf(ConfigurationException.class);

        verify(projectDeploymentWorkflowService, never()).update(any(ProjectDeploymentWorkflow.class));
    }
}

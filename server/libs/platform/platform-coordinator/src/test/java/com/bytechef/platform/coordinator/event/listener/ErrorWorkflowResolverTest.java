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

package com.bytechef.platform.coordinator.event.listener;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.ErrorWorkflowDispatch;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.platform.configuration.domain.Environment;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Ivica Cardic
 */
@ExtendWith(MockitoExtension.class)
class ErrorWorkflowResolverTest {

    @Mock
    private ProjectDeploymentService projectDeploymentService;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectWorkflowService projectWorkflowService;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private ErrorWorkflowResolver errorWorkflowResolver;

    @BeforeEach
    void setUp() {
        // resolve() takes the job principal id, which for automation is the project deployment id.
        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setProjectId(1L);
        projectDeployment.setEnvironment(Environment.STAGING);

        Mockito.lenient()
            .when(projectDeploymentService.getProjectDeployment(1L))
            .thenReturn(projectDeployment);

        // Workflow is a final, mostly-constructor-populated domain object with no label setter, so it is mocked
        // rather than instantiated directly.
        Workflow failedWorkflow = Mockito.mock(Workflow.class);

        Mockito.lenient()
            .when(failedWorkflow.getLabel())
            .thenReturn("Failing Workflow");

        Mockito.lenient()
            .when(workflowService.getWorkflow("wf-1"))
            .thenReturn(failedWorkflow);
    }

    @Test
    void testWorkflowOverrideWins() {
        ProjectWorkflow failing = projectWorkflow(10L, 99L, false);

        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("wf-1"))
            .thenReturn(failing);
        Mockito.when(projectWorkflowService.getProjectWorkflow(99L))
            .thenReturn(projectWorkflow(99L, null, false));

        Optional<ErrorWorkflowDispatch> result = errorWorkflowResolver.resolve(1L, "wf-1");

        Assertions.assertEquals("wf-99", result.orElseThrow()
            .handlerWorkflowId());
        Mockito.verifyNoInteractions(projectService);
    }

    @Test
    void testFallsBackToProjectDefault() {
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("wf-1"))
            .thenReturn(projectWorkflow(10L, null, false));
        Mockito.when(projectService.getProject(1L))
            .thenReturn(project(7L));
        Mockito.when(projectWorkflowService.getProjectWorkflow(7L))
            .thenReturn(projectWorkflow(7L, null, false));

        Optional<ErrorWorkflowDispatch> result = errorWorkflowResolver.resolve(1L, "wf-1");

        Assertions.assertEquals("wf-7", result.orElseThrow()
            .handlerWorkflowId());
    }

    @Test
    void testDisabledBeatsInheritedDefault() {
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("wf-1"))
            .thenReturn(projectWorkflow(10L, null, true));

        Assertions.assertTrue(errorWorkflowResolver.resolve(1L, "wf-1")
            .isEmpty());
        Mockito.verifyNoInteractions(projectService);
    }

    @Test
    void testNoConfigurationAnywhere() {
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("wf-1"))
            .thenReturn(projectWorkflow(10L, null, false));
        Mockito.when(projectService.getProject(1L))
            .thenReturn(project(null));

        Assertions.assertTrue(errorWorkflowResolver.resolve(1L, "wf-1")
            .isEmpty());
    }

    @Test
    void testSelfReferenceIsRejected() {
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("wf-1"))
            .thenReturn(projectWorkflow(10L, 10L, false));

        Assertions.assertTrue(errorWorkflowResolver.resolve(1L, "wf-1")
            .isEmpty());
    }

    @Test
    void testDispatchCarriesRealEnvironmentFromProjectDeployment() {
        Mockito.when(projectWorkflowService.getWorkflowProjectWorkflow("wf-1"))
            .thenReturn(projectWorkflow(10L, 99L, false));
        Mockito.when(projectWorkflowService.getProjectWorkflow(99L))
            .thenReturn(projectWorkflow(99L, null, false));

        Optional<ErrorWorkflowDispatch> result = errorWorkflowResolver.resolve(1L, "wf-1");

        // The environment must be the deployment's real value ("STAGING", per setUp), never the literal string
        // "null" that String.valueOf(job.getMetadata("environment")) would produce.
        Assertions.assertEquals("STAGING", result.orElseThrow()
            .environment());
    }

    private static Project project(Long errorProjectWorkflowId) {
        Project project = new Project();

        project.setId(1L);
        project.setErrorProjectWorkflowId(errorProjectWorkflowId);

        return project;
    }

    private static ProjectWorkflow projectWorkflow(long id, Long errorId, boolean disabled) {
        // ProjectWorkflow has no setId(); the (long id) constructor is the existing way to stamp an id in tests.
        ProjectWorkflow projectWorkflow = new ProjectWorkflow(id);

        projectWorkflow.setWorkflowId("wf-" + id);
        projectWorkflow.setErrorProjectWorkflowId(errorId);
        projectWorkflow.setErrorWorkflowDisabled(disabled);

        return projectWorkflow;
    }
}

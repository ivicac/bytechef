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

package com.bytechef.automation.workflow.execution.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.domain.TriggerExecution;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A trigger execution reaches its workspace through the deployment its {@code WorkflowExecutionId} names, never through
 * a job: the rows this resolver exists to gate are precisely the trigger executions that never produced one.
 *
 * @author Ivica Cardic
 */
class TriggerExecutionOwnershipResolverTest {

    private static final long DEPLOYMENT_ID = 7L;
    private static final long PROJECT_ID = 11L;
    private static final long TRIGGER_EXECUTION_ID = 3L;
    private static final long WORKSPACE_ID = 42L;

    private final ProjectDeploymentService projectDeploymentService = mock(ProjectDeploymentService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final TriggerExecutionService triggerExecutionService = mock(TriggerExecutionService.class);

    private final TriggerExecutionOwnershipResolver resolver = new TriggerExecutionOwnershipResolver(
        projectDeploymentService, projectService, triggerExecutionService);

    @Test
    void testResourceType() {
        assertThat(resolver.resourceType()).isEqualTo("TriggerExecution");
    }

    @Test
    void testResolvesWorkspaceViaTheDeploymentTheTriggerNames() {
        givenTriggerExecution();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setId(DEPLOYMENT_ID);
        projectDeployment.setProjectId(PROJECT_ID);

        when(projectDeploymentService.fetchProjectDeployment(DEPLOYMENT_ID)).thenReturn(
            Optional.of(projectDeployment));

        Project project = new Project();

        project.setId(PROJECT_ID);
        project.setName("Customer Sync");
        project.setWorkspaceId(WORKSPACE_ID);

        when(projectService.fetchProject(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThat(resolver.resolveOwner(TRIGGER_EXECUTION_ID)
            .workspaceId()).hasValue(WORKSPACE_ID);
    }

    /**
     * The lookup must not throw on an id that does not exist — a resolver that threw would surface as a 500 on a
     * guessed id rather than the denial the gate is there to produce.
     */
    @Test
    void testUnknownTriggerExecutionIsUnknown() {
        when(triggerExecutionService.getTriggerExecutions(List.of(99L))).thenReturn(List.of());

        assertThat(resolver.resolveOwner(99L)
            .workspaceId()).isEmpty();
    }

    @Test
    void testUnknownDeploymentFailsClosed() {
        givenTriggerExecution();

        when(projectDeploymentService.fetchProjectDeployment(DEPLOYMENT_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolveOwner(TRIGGER_EXECUTION_ID)
            .workspaceId()).isEmpty();
    }

    @Test
    void testUnknownProjectFailsClosed() {
        givenTriggerExecution();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setId(DEPLOYMENT_ID);
        projectDeployment.setProjectId(PROJECT_ID);

        when(projectDeploymentService.fetchProjectDeployment(DEPLOYMENT_ID)).thenReturn(
            Optional.of(projectDeployment));
        when(projectService.fetchProject(PROJECT_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolveOwner(TRIGGER_EXECUTION_ID)
            .workspaceId()).isEmpty();
    }

    private void givenTriggerExecution() {
        TriggerExecution triggerExecution = TriggerExecution.builder()
            .id(TRIGGER_EXECUTION_ID)
            .workflowExecutionId(
                WorkflowExecutionId.of(
                    PlatformType.AUTOMATION, DEPLOYMENT_ID, UUID.randomUUID()
                        .toString(),
                    "trigger_1"))
            .build();

        when(triggerExecutionService.getTriggerExecutions(List.of(TRIGGER_EXECUTION_ID))).thenReturn(
            List.of(triggerExecution));
    }
}

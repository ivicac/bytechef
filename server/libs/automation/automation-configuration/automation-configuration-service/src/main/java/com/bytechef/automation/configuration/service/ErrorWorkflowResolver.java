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

package com.bytechef.automation.configuration.service;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.ErrorWorkflowDispatch;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves which workflow handles a failed run: the failing workflow's own override, else the project default, else
 * none. An explicit disable on the workflow beats an inherited project default, which is why the disable flag is
 * separate from the nullable reference.
 *
 * @author Ivica Cardic
 */
@Service
public class ErrorWorkflowResolver {

    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectService projectService;
    private final ProjectWorkflowService projectWorkflowService;
    private final WorkflowService workflowService;

    public ErrorWorkflowResolver(
        ProjectDeploymentService projectDeploymentService, ProjectService projectService,
        ProjectWorkflowService projectWorkflowService, WorkflowService workflowService) {

        this.projectDeploymentService = projectDeploymentService;
        this.projectService = projectService;
        this.projectWorkflowService = projectWorkflowService;
        this.workflowService = workflowService;
    }

    /**
     * @param jobPrincipalId the failed job's principal id, which for automation is the PROJECT DEPLOYMENT id, not the
     *                       project id — mapped here so the coordinator needs no project lookups of its own
     */
    public Optional<ErrorWorkflowDispatch> resolve(long jobPrincipalId, String failedWorkflowId) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(jobPrincipalId);

        long projectId = projectDeployment.getProjectId();

        ProjectWorkflow failingProjectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(failedWorkflowId);

        if (failingProjectWorkflow.isErrorWorkflowDisabled()) {
            return Optional.empty();
        }

        Long targetId = failingProjectWorkflow.getErrorProjectWorkflowId();

        if (targetId == null) {
            Project project = projectService.getProject(projectId);

            targetId = project.getErrorProjectWorkflowId();
        }

        if (targetId == null) {
            return Optional.empty();
        }

        // Defensive: configuration-time validation rejects self-reference, but a workflow can be re-pointed
        // afterwards, and a self-referencing handler would fail forever.
        if (targetId.equals(failingProjectWorkflow.getId())) {
            return Optional.empty();
        }

        ProjectWorkflow target = projectWorkflowService.getProjectWorkflow(targetId);

        Workflow failedWorkflow = workflowService.getWorkflow(failedWorkflowId);

        return Optional.of(
            new ErrorWorkflowDispatch(
                target.getWorkflowId(), projectId, failingProjectWorkflow.getId(), failedWorkflowId,
                failedWorkflow.getLabel()));
    }
}

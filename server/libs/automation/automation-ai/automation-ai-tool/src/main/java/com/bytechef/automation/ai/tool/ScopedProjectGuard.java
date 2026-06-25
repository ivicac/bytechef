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

package com.bytechef.automation.ai.tool;

import com.bytechef.ai.copilot.tool.ScopedConnectionGuard;
import com.bytechef.ai.copilot.tool.context.ScopedProjectToolContext;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.exception.ErrorType;
import com.bytechef.exception.ExecutionException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Confines an automation tool call of a run that carries a scoped project to that project and its caller's connections.
 * Any other run is left to the authorization rules of the facades the tools call.
 *
 * @author Ivica Cardic
 */
@Component
public class ScopedProjectGuard {

    static final String OUT_OF_SCOPE_MESSAGE = "This chat can only work with the projects and workflows of its own " +
        "project.";
    static final String PROJECT_CHANGE_MESSAGE = "This chat cannot create, change, delete or publish projects.";

    private static final Logger log = LoggerFactory.getLogger(ScopedProjectGuard.class);

    private final ProjectWorkflowService projectWorkflowService;
    private final ScopedConnectionGuard scopedConnectionGuard;

    @SuppressFBWarnings("EI")
    public ScopedProjectGuard(
        ProjectWorkflowService projectWorkflowService, ScopedConnectionGuard scopedConnectionGuard) {

        this.projectWorkflowService = projectWorkflowService;
        this.scopedConnectionGuard = scopedConnectionGuard;
    }

    public void checkWorkflow(String workflowId, @Nullable ToolContext toolContext, ErrorType errorType) {
        Long scopedProjectId = fetchScopedProjectId(toolContext);

        if (scopedProjectId == null) {
            return;
        }

        ProjectWorkflow projectWorkflow;

        try {
            projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(workflowId);
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new ExecutionException(getWorkflowNotFoundMessage(workflowId), errorType);
        } catch (RuntimeException exception) {
            log.warn("Rejected workflow {}: looking up its project failed", workflowId, exception);

            throw new ExecutionException(getWorkflowNotFoundMessage(workflowId), exception, errorType);
        }

        if (projectWorkflow.getProjectId() != scopedProjectId.longValue()) {
            log.warn("Rejected workflow {}: it is outside the scoped project {}", workflowId, scopedProjectId);

            throw new ExecutionException(getWorkflowNotFoundMessage(workflowId), errorType);
        }
    }

    public void checkProject(long projectId, @Nullable ToolContext toolContext, ErrorType errorType) {
        Long scopedProjectId = fetchScopedProjectId(toolContext);

        if (scopedProjectId != null && projectId != scopedProjectId) {
            log.warn("Rejected project {}: it is not the scoped project {}", projectId, scopedProjectId);

            throw new ExecutionException(OUT_OF_SCOPE_MESSAGE, errorType);
        }
    }

    public @Nullable Long fetchScopedProjectId(@Nullable ToolContext toolContext) {
        return ScopedProjectToolContext.fetchScopedProjectId(toolContext);
    }

    public void checkProjectChangeAllowed(@Nullable ToolContext toolContext, ErrorType errorType) {
        if (ScopedProjectToolContext.isConfined(toolContext)) {
            log.warn("Rejected a project change from a confined run");

            throw new ExecutionException(PROJECT_CHANGE_MESSAGE, errorType);
        }
    }

    public void checkConnection(long connectionId, @Nullable ToolContext toolContext, ErrorType errorType) {
        String denialMessage = scopedConnectionGuard.fetchDenialMessage(null, connectionId, toolContext);

        if (denialMessage != null) {
            throw new ExecutionException(denialMessage, errorType);
        }
    }

    private static String getWorkflowNotFoundMessage(String workflowId) {
        return "Workflow " + workflowId + " was not found in this chat's project. Check the workflow id.";
    }
}

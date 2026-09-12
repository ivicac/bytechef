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

package com.bytechef.platform.workflow.validator.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.workflow.validator.WorkflowValidatorFacade;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * @author Marko Kriskovic
 */
@Controller
@ConditionalOnCoordinator
public class WorkflowValidatorGraphQlController {

    private final WorkflowValidatorFacade workflowValidatorFacade;

    @SuppressFBWarnings("EI")
    public WorkflowValidatorGraphQlController(WorkflowValidatorFacade workflowValidatorFacade) {
        this.workflowValidatorFacade = workflowValidatorFacade;
    }

    /**
     * Every route out of here is authorized, and that is what the two id arguments are for: a definition that belongs
     * to a stored workflow is gated on that workflow, one that does not is gated on the workspace it is being authored
     * in. Naming neither is refused rather than answered, because validating a definition resolves its data table and
     * knowledge base references in an environment - an answer this endpoint cannot authorize without one of the two
     * ids. The facade's own unauthorized overload is left to the agent tool that needs it.
     */
    @QueryMapping
    public WorkflowValidatorFacade.WorkflowValidationResult validateWorkflow(
        @Argument String workflow, @Argument @Nullable String workflowId, @Argument @Nullable Long workspaceId,
        @Argument @Nullable Long environmentId) {

        long resolvedEnvironmentId = environmentId == null ? Environment.DEVELOPMENT.ordinal() : environmentId;

        if (workflowId != null) {
            return workflowValidatorFacade.validateWorkflow(workflow, workflowId, resolvedEnvironmentId);
        }

        if (workspaceId == null) {
            throw new IllegalArgumentException(
                "validateWorkflow requires either workflowId or workspaceId: validating a definition resolves its " +
                    "data table and knowledge base references in an environment, which cannot be authorized without " +
                    "naming the workflow or the workspace it belongs to.");
        }

        return workflowValidatorFacade.validateWorkflow(workflow, workspaceId, resolvedEnvironmentId);
    }

    @QueryMapping
    public WorkflowValidatorFacade.WorkflowValidationResult validateWorkflowById(
        @Argument String workflowId, @Argument @Nullable Long environmentId) {

        if (environmentId == null) {
            return workflowValidatorFacade.validateWorkflowById(workflowId);
        }

        return workflowValidatorFacade.validateWorkflowById(workflowId, environmentId);
    }
}

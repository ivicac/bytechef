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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bytechef.platform.workflow.validator.WorkflowValidatorFacade;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class WorkflowValidatorGraphQlControllerTest {

    private final WorkflowValidatorFacade workflowValidatorFacade = mock(WorkflowValidatorFacade.class);
    private final WorkflowValidatorGraphQlController controller =
        new WorkflowValidatorGraphQlController(workflowValidatorFacade);

    @Test
    void passesTheWorkflowIdThroughWhenGiven() {
        controller.validateWorkflow("{}", "wf-1", null, 2L);

        verify(workflowValidatorFacade).validateWorkflow("{}", "wf-1", 2L);
    }

    @Test
    void validatesInDevelopmentWhenOnlyTheWorkflowIdIsGiven() {
        controller.validateWorkflow("{}", "wf-1", null, null);

        verify(workflowValidatorFacade).validateWorkflow("{}", "wf-1", 0L);
    }

    @Test
    void routesThroughTheWorkflowWhenBothIdsAreGiven() {
        controller.validateWorkflow("{}", "wf-1", 7L, 2L);

        verify(workflowValidatorFacade).validateWorkflow("{}", "wf-1", 2L);
    }

    @Test
    void passesTheWorkspaceThroughWhenNoWorkflowIdIsGiven() {
        controller.validateWorkflow("{}", null, 7L, 2L);

        verify(workflowValidatorFacade).validateWorkflow("{}", 7L, 2L);
    }

    @Test
    void validatesInDevelopmentWhenOnlyTheWorkspaceIsGiven() {
        controller.validateWorkflow("{}", null, 7L, null);

        verify(workflowValidatorFacade).validateWorkflow("{}", 7L, 0L);
    }

    /**
     * The facade's unauthorized single-argument overload stays reachable for the agent tool, so the guard that keeps it
     * off this endpoint is the controller's own: naming neither id must be refused rather than quietly answered there.
     */
    @Test
    void refusesWhenNeitherTheWorkflowNorTheWorkspaceIsGiven() {
        assertThrows(IllegalArgumentException.class, () -> controller.validateWorkflow("{}", null, null, 2L));

        verifyNoInteractions(workflowValidatorFacade);
    }

    @Test
    void validateByIdPassesEnvironmentThrough() {
        controller.validateWorkflowById("wf-1", 1L);

        verify(workflowValidatorFacade).validateWorkflowById("wf-1", 1L);
    }
}

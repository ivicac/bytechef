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

package com.bytechef.automation.workflow.execution.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.workflow.execution.dto.WorkflowExecutionDTO;
import com.bytechef.automation.workflow.execution.facade.ProjectWorkflowExecutionFacade;
import com.bytechef.automation.workflow.execution.web.rest.config.WorkflowExecutionRestTestConfiguration;
import com.bytechef.automation.workflow.execution.web.rest.model.WorkflowExecutionModel;
import com.bytechef.platform.workflow.execution.dto.TaskExecutionDTO;
import com.bytechef.platform.workflow.execution.web.rest.model.TaskExecutionModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Pins the routing of the automation workflow-execution reads: which facade method each path reaches, and with which
 * id. Written against the paths the generated {@link WorkflowExecutionApi} declares, so a path or parameter renamed in
 * {@code openapi.yaml} shows up here rather than only in the client.
 *
 * <p>
 * The trigger-execution read is covered specifically because it is keyed on a {@code triggerExecutionId} rather than a
 * job id, and its facade method is gated on that argument name — a controller that passed the wrong path variable would
 * gate the wrong row.
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = {
    WorkflowExecutionRestTestConfiguration.class, WorkflowExecutionApiController.class
})
@WebMvcTest(value = WorkflowExecutionApiController.class)
public class WorkflowExecutionApiControllerIntTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversionService conversionService;

    @MockitoBean
    private ProjectWorkflowExecutionFacade projectWorkflowExecutionFacade;

    @Test
    public void testGetTriggerExecutionWorkflowExecutionPassesTheTriggerExecutionIdToTheFacade() throws Exception {
        WorkflowExecutionDTO workflowExecutionDTO = org.mockito.Mockito.mock(WorkflowExecutionDTO.class);

        when(projectWorkflowExecutionFacade.getTriggerExecutionWorkflowExecution(4200L))
            .thenReturn(workflowExecutionDTO);
        when(conversionService.convert(workflowExecutionDTO, WorkflowExecutionModel.class))
            .thenReturn(new WorkflowExecutionModel());

        mockMvc
            .perform(
                MockMvcRequestBuilders.get("/internal/workflow-executions/trigger-executions/{triggerExecutionId}",
                    4200L))
            .andExpect(MockMvcResultMatchers.status()
                .isOk());

        ArgumentCaptor<Long> triggerExecutionIdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(projectWorkflowExecutionFacade).getTriggerExecutionWorkflowExecution(
            triggerExecutionIdCaptor.capture());

        // The id from the path, not the one the job-keyed read would have used: these are different id spaces, and a
        // trigger execution that never produced a job has no job id at all.
        assertThat(triggerExecutionIdCaptor.getValue()).isEqualTo(4200L);
    }

    @Test
    public void testGetWorkflowExecutionPassesTheJobIdToTheFacade() throws Exception {
        WorkflowExecutionDTO workflowExecutionDTO = org.mockito.Mockito.mock(WorkflowExecutionDTO.class);

        when(projectWorkflowExecutionFacade.getWorkflowExecution(11L)).thenReturn(workflowExecutionDTO);
        when(conversionService.convert(workflowExecutionDTO, WorkflowExecutionModel.class))
            .thenReturn(new WorkflowExecutionModel());

        mockMvc
            .perform(MockMvcRequestBuilders.get("/internal/workflow-executions/{id}", 11L))
            .andExpect(MockMvcResultMatchers.status()
                .isOk());

        verify(projectWorkflowExecutionFacade).getWorkflowExecution(eq(11L));
    }

    @Test
    public void testGetWorkflowExecutionTaskExecutionPassesBothIdsToTheFacade() throws Exception {
        TaskExecutionDTO taskExecutionDTO = org.mockito.Mockito.mock(TaskExecutionDTO.class);

        when(projectWorkflowExecutionFacade.getWorkflowExecutionTaskExecution(11L, 22L)).thenReturn(taskExecutionDTO);
        when(conversionService.convert(taskExecutionDTO, TaskExecutionModel.class))
            .thenReturn(new TaskExecutionModel());

        mockMvc
            .perform(
                MockMvcRequestBuilders.get(
                    "/internal/workflow-executions/{id}/task-executions/{taskExecutionId}", 11L, 22L))
            .andExpect(MockMvcResultMatchers.status()
                .isOk());

        verify(projectWorkflowExecutionFacade).getWorkflowExecutionTaskExecution(eq(11L), eq(22L));
    }
}

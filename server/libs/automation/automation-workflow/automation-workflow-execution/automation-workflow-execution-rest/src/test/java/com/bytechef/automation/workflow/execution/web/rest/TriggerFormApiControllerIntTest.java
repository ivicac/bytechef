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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.workflow.execution.web.rest.config.WorkflowExecutionRestTestConfiguration;
import com.bytechef.automation.workflow.execution.web.rest.model.TriggerFormModel;
import com.bytechef.commons.util.EncodingUtils;
import com.bytechef.tenant.TenantContext;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = {
    WorkflowExecutionRestTestConfiguration.class, TriggerFormApiController.class
})
@ExtendWith(ObjectMapperSetupExtension.class)
@WebMvcTest(value = TriggerFormApiController.class)
public class TriggerFormApiControllerIntTest {

    private static final String DEFINITION = """
        {
            "triggers": [
                {
                    "name": "trigger_1",
                    "type": "form/v1/newFormRequest",
                    "parameters": {
                        "formTitle": "Contact us"
                    }
                }
            ]
        }
        """;

    @Autowired
    private ConversionService conversionService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectWorkflowService projectWorkflowService;

    @MockitoBean
    private WorkflowService workflowService;

    @Test
    public void testGetTriggerFormRunsTheLookupInTheTenantOfTheId() throws Exception {
        AtomicReference<String> lookupTenantId = new AtomicReference<>();

        when(projectWorkflowService.getProjectWorkflowWorkflowId(1050L, "workflow-uuid")).thenAnswer(invocation -> {
            lookupTenantId.set(TenantContext.getCurrentTenantId());

            return "workflow-1";
        });
        when(workflowService.getWorkflow("workflow-1"))
            .thenReturn(new Workflow("workflow-1", DEFINITION, Workflow.Format.JSON));
        when(conversionService.convert(any(Map.class), eq(TriggerFormModel.class)))
            .thenReturn(new TriggerFormModel().formTitle("Contact us"));

        mockMvc
            .perform(MockMvcRequestBuilders.get("/internal/trigger-form/{id}", encodeId("000003")))
            .andExpect(MockMvcResultMatchers.status()
                .isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.formTitle")
                .value("Contact us"));

        assertThat(lookupTenantId.get()).isEqualTo("000003");
        assertThat(TenantContext.getCurrentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    }

    @Test
    public void testGetTriggerFormRejectsAnIdWithAnInvalidTenantId() throws Exception {
        mockMvc
            .perform(MockMvcRequestBuilders.get("/internal/trigger-form/{id}", encodeId("bad.id")))
            .andExpect(MockMvcResultMatchers.status()
                .isBadRequest());

        verifyNoInteractions(projectWorkflowService, workflowService);
    }

    private static String encodeId(String tenantId) {
        return EncodingUtils.base64EncodeToString(tenantId + ":0:1050:workflow-uuid:trigger_1");
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.configuration.public_.web.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.automation.workflow.execution.facade.ProjectWorkflowExecutionFacade;
import com.bytechef.ee.automation.configuration.public_.web.rest.config.AutomationConfigurationPublicRestTestConfiguration;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = AutomationConfigurationPublicRestTestConfiguration.class)
@TestPropertySource(properties = "bytechef.edition=ee")
@WebMvcTest(WorkflowExecutionApiController.class)
class WorkflowExecutionApiControllerIntTest {

    private static final long WORKSPACE_ID = 100L;

    @MockitoBean
    private EnvironmentService environmentService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectWorkflowExecutionFacade projectWorkflowExecutionFacade;

    @ParameterizedTest(name = "X-Environment [{0}]")
    @ValueSource(strings = {
        "development", "Development", " development "
    })
    void testGetWorkflowExecutionsPageAcceptsAnyCaseOfTheEnvironmentHeader(String requestEnvironment)
        throws Exception {

        stubWorkflowExecutionsInEnvironment("DEVELOPMENT", Environment.DEVELOPMENT);

        mockMvc
            .perform(
                get("/v1/workflow-executions")
                    .header("X-Environment", requestEnvironment)
                    .param("workspaceId", String.valueOf(WORKSPACE_ID)))
            .andExpect(status().isOk());

        verifyWorkflowExecutionsRequestedInEnvironment(Environment.DEVELOPMENT);
    }

    @ParameterizedTest(name = "X-Environment [{0}]")
    @ValueSource(strings = {
        "", "   "
    })
    void testGetWorkflowExecutionsPageTreatsABlankEnvironmentHeaderAsAbsent(String requestEnvironment)
        throws Exception {

        stubWorkflowExecutionsInEnvironment(null, Environment.PRODUCTION);

        mockMvc
            .perform(
                get("/v1/workflow-executions")
                    .header("X-Environment", requestEnvironment)
                    .param("workspaceId", String.valueOf(WORKSPACE_ID)))
            .andExpect(status().isOk());

        verifyWorkflowExecutionsRequestedInEnvironment(Environment.PRODUCTION);
    }

    @Test
    void testGetWorkflowExecutionsPageRejectsAnUnknownEnvironmentHeader() throws Exception {
        mockMvc
            .perform(
                get("/v1/workflow-executions")
                    .header("X-Environment", "staging-x")
                    .param("workspaceId", String.valueOf(WORKSPACE_ID)))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(environmentService, projectWorkflowExecutionFacade);
    }

    private void stubWorkflowExecutionsInEnvironment(@Nullable String environmentName, Environment environment) {
        when(environmentService.getEnvironment(environmentName)).thenReturn(environment);
        when(
            projectWorkflowExecutionFacade.getWorkflowExecutions(
                isNull(), eq((long) environment.ordinal()), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(WORKSPACE_ID), eq(0)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
    }

    private void verifyWorkflowExecutionsRequestedInEnvironment(Environment environment) {
        verify(projectWorkflowExecutionFacade).getWorkflowExecutions(
            isNull(), eq((long) environment.ordinal()), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
            isNull(), eq(WORKSPACE_ID), eq(0));
    }
}

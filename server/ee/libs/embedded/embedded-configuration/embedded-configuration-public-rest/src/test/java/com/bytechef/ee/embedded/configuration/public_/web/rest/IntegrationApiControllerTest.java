/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserIntegrationDTO;
import com.bytechef.ee.embedded.configuration.dto.IntegrationDTO;
import com.bytechef.ee.embedded.configuration.dto.IntegrationInstanceConfigurationDTO;
import com.bytechef.ee.embedded.configuration.exception.EmbeddedIntegrationNotVisibleException;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserIntegrationFacade;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.EnvironmentModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationModel;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationWorkflowService;
import com.bytechef.ee.embedded.mcp.service.McpIntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.mcp.service.McpIntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.mcp.service.McpIntegrationInstanceToolService;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.mcp.service.McpComponentService;
import com.bytechef.platform.mcp.service.McpServerService;
import com.bytechef.platform.mcp.service.McpToolService;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class IntegrationApiControllerTest {

    private final ConnectedUserIntegrationFacade connectedUserIntegrationFacade =
        mock(ConnectedUserIntegrationFacade.class);
    private final ConversionService conversionService = mock(ConversionService.class);
    private final EnvironmentService environmentService = mock(EnvironmentService.class);

    private final IntegrationApiController integrationApiController = new IntegrationApiController(
        mock(ClusterElementDefinitionService.class), mock(ComponentDefinitionService.class), conversionService,
        connectedUserIntegrationFacade, environmentService,
        mock(IntegrationInstanceConfigurationWorkflowService.class), mock(IntegrationInstanceWorkflowService.class),
        mock(IntegrationWorkflowService.class), mock(McpComponentService.class),
        mock(McpIntegrationInstanceToolService.class), mock(McpIntegrationInstanceConfigurationService.class),
        mock(McpIntegrationInstanceConfigurationWorkflowService.class), mock(McpServerService.class),
        mock(McpToolService.class), mock(WorkflowService.class));

    @Test
    void testGetIntegrationReturnsNotFoundWhenIntegrationNotVisible() {
        when(environmentService.getEnvironment(any()))
            .thenReturn(Environment.PRODUCTION);
        when(connectedUserIntegrationFacade.getConnectedUserIntegration(
            anyString(), anyLong(), anyBoolean(), any()))
                .thenThrow(new EmbeddedIntegrationNotVisibleException(1L));

        ResponseEntity<IntegrationModel> responseEntity = integrationApiController.getIntegration(
            "external-user-id", 1L, EnvironmentModel.PRODUCTION);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testGetIntegrationReturnsOkWhenIntegrationVisible() {
        ConnectedUserIntegrationDTO connectedUserIntegrationDTO = mock(ConnectedUserIntegrationDTO.class);
        IntegrationInstanceConfigurationDTO integrationInstanceConfigurationDTO =
            mock(IntegrationInstanceConfigurationDTO.class);
        IntegrationModel integrationModel = new IntegrationModel();

        // MCP/workflow population walks the DTO chain; stub it to resolve to empty results so the happy path
        // exercises the visible-integration branch without NPEs. integrationInstanceConfigurationWorkflows() is left
        // null so filterDisabledWorkflows returns early.
        when(connectedUserIntegrationDTO.integrationInstanceConfiguration())
            .thenReturn(integrationInstanceConfigurationDTO);
        when(integrationInstanceConfigurationDTO.integration())
            .thenReturn(mock(IntegrationDTO.class));

        when(environmentService.getEnvironment(any()))
            .thenReturn(Environment.PRODUCTION);
        when(connectedUserIntegrationFacade.getConnectedUserIntegration(
            anyString(), anyLong(), anyBoolean(), any()))
                .thenReturn(connectedUserIntegrationDTO);
        when(conversionService.convert(eq(connectedUserIntegrationDTO), eq(IntegrationModel.class)))
            .thenReturn(integrationModel);

        ResponseEntity<IntegrationModel> responseEntity = integrationApiController.getIntegration(
            "external-user-id", 1L, EnvironmentModel.PRODUCTION);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getBody()).isSameAs(integrationModel);
    }
}

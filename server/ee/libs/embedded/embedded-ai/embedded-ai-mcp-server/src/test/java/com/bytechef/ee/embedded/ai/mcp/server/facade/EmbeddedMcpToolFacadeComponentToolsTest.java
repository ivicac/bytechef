/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.mcp.server.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.ee.embedded.ai.mcp.domain.McpIntegrationInstanceTool;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.ai.mcp.service.McpIntegrationInstanceToolService;
import com.bytechef.ee.embedded.configuration.domain.Integration;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.embedded.security.service.JwtTokenService;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.domain.ConnectionDefinition;
import com.bytechef.platform.component.facade.ClusterElementDefinitionFacade;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.job.sync.executor.JobSyncExecutor;
import com.bytechef.platform.mcp.domain.McpComponent;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.platform.mcp.domain.McpTool;
import com.bytechef.platform.mcp.service.McpComponentService;
import com.bytechef.platform.mcp.service.McpServerService;
import com.bytechef.platform.tool.execution.ToolExecutionEvent;
import com.bytechef.platform.tool.execution.ToolExecutionOutcome;
import com.bytechef.platform.tool.execution.ToolExecutionRecorder;
import com.bytechef.platform.tool.execution.ToolExecutionSurface;
import com.bytechef.platform.workflow.execution.JobCompletionAwaiter;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the tool invocation row a call of an Embedded MCP component tool describes.
 *
 * @version ee
 */
@ExtendWith(ObjectMapperSetupExtension.class)
@SuppressWarnings("unchecked")
class EmbeddedMcpToolFacadeComponentToolsTest {

    private final ClusterElementDefinitionService clusterElementDefinitionService =
        mock(ClusterElementDefinitionService.class);
    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final ConnectedUserService connectedUserService = mock(ConnectedUserService.class);
    private final IntegrationInstanceService integrationInstanceService = mock(IntegrationInstanceService.class);
    private final IntegrationService integrationService = mock(IntegrationService.class);
    private final McpComponentService mcpComponentService = mock(McpComponentService.class);
    private final McpIntegrationInstanceToolService mcpIntegrationInstanceToolService =
        mock(McpIntegrationInstanceToolService.class);
    private final McpServerService mcpServerService = mock(McpServerService.class);
    private final ToolExecutionRecorder toolExecutionRecorder = mock(ToolExecutionRecorder.class);

    private final EmbeddedMcpToolFacade embeddedMcpToolFacade = new EmbeddedMcpToolFacade(
        mock(ObjectProvider.class), mock(ClusterElementDefinitionFacade.class), clusterElementDefinitionService,
        componentDefinitionService, connectedUserService, mock(Evaluator.class),
        mock(IntegrationInstanceConfigurationService.class),
        mock(IntegrationInstanceConfigurationWorkflowService.class), integrationInstanceService,
        mock(IntegrationInstanceWorkflowService.class), integrationService,
        mock(JobCompletionAwaiter.class), mock(JobResumeFacade.class), mock(JobService.class),
        mock(JobSyncExecutor.class), mock(JwtTokenService.class), mcpComponentService,
        mock(McpIntegrationInstanceConfigurationWorkflowService.class), mcpIntegrationInstanceToolService,
        mcpServerService, mock(PrincipalJobFacade.class), "https://example.com", mock(TaskExecutionService.class),
        mock(TaskFileStorage.class), toolExecutionRecorder, mock(WorkflowService.class));

    @Test
    void testCallOfComponentToolRecordsTheConnectedUser() {
        McpTool mcpTool = new McpTool();

        mcpTool.setEnabled(true);
        mcpTool.setId(1L);
        mcpTool.setMcpComponentId(10L);
        mcpTool.setName("post");
        mcpTool.setParameters(Map.of());

        McpComponent mcpComponent = new McpComponent();

        mcpComponent.setComponentName("httpClient");
        mcpComponent.setComponentVersion(1);
        mcpComponent.setMcpServerId(30L);

        when(mcpComponentService.getMcpComponent(10L)).thenReturn(mcpComponent);

        ConnectedUser connectedUser = new ConnectedUser(
            Map.of(), null, true, "external-user", 80L, "external-user", 0);

        when(connectedUserService.fetchConnectedUser("external-user", Environment.STAGING))
            .thenReturn(Optional.of(connectedUser));

        IntegrationInstance integrationInstance = new IntegrationInstance();

        integrationInstance.setId(90L);
        integrationInstance.setConnectedUserId(80L);
        integrationInstance.setConnectionId(100L);

        when(integrationInstanceService.fetchIntegrationInstance(80L, "httpClient", Environment.STAGING))
            .thenReturn(Optional.of(integrationInstance));
        when(mcpIntegrationInstanceToolService.fetchMcpIntegrationInstanceTool(90L, 1L))
            .thenReturn(Optional.of(new McpIntegrationInstanceTool(90L, 1L, true)));

        ClusterElementDefinition clusterElementDefinition = mock(ClusterElementDefinition.class);

        when(clusterElementDefinition.getComponentName()).thenReturn("httpClient");
        when(clusterElementDefinition.getComponentVersion()).thenReturn(1);
        when(clusterElementDefinition.getName()).thenReturn("post");
        when(clusterElementDefinitionService.getClusterElementDefinition("httpClient", 1, "post"))
            .thenReturn(clusterElementDefinition);

        when(mcpServerService.getMcpServer(30L)).thenReturn(
            new McpServer("test-server", PlatformType.EMBEDDED, Environment.STAGING, true));
        when(toolExecutionRecorder.record(any(ToolExecutionEvent.Builder.class), any(Supplier.class)))
            .thenReturn(Map.of("ok", true));

        FunctionToolCallback<Map<String, Object>, Object> functionToolCallback =
            embeddedMcpToolFacade.getFunctionToolCallback(mcpTool, "external-user", Environment.STAGING, "tenant-1");

        assertThat(functionToolCallback).isNotNull();

        functionToolCallback.call("{}");

        ArgumentCaptor<ToolExecutionEvent.Builder> builderArgumentCaptor =
            ArgumentCaptor.forClass(ToolExecutionEvent.Builder.class);

        verify(toolExecutionRecorder).record(builderArgumentCaptor.capture(), any(Supplier.class));

        ToolExecutionEvent.Builder builder = builderArgumentCaptor.getValue();

        ToolExecutionEvent toolExecutionEvent = builder.build();

        assertThat(toolExecutionEvent.surface()).isEqualTo(ToolExecutionSurface.MCP_EMBEDDED);
        assertThat(toolExecutionEvent.connectedUserId()).isEqualTo(80L);
        assertThat(toolExecutionEvent.externalUserId()).isEqualTo("external-user");
        assertThat(toolExecutionEvent.connectionId()).isEqualTo(100L);
        assertThat(toolExecutionEvent.environment()).isEqualTo(Environment.STAGING.ordinal());
    }

    @Test
    void testCallOfComponentToolWithoutConnectedUserRecordsConnectionRequiredWithoutConnectedUser() {
        McpTool mcpTool = new McpTool();

        mcpTool.setEnabled(true);
        mcpTool.setId(1L);
        mcpTool.setMcpComponentId(10L);
        mcpTool.setName("post");
        mcpTool.setParameters(Map.of());

        McpComponent mcpComponent = new McpComponent();

        mcpComponent.setComponentName("httpClient");
        mcpComponent.setComponentVersion(1);
        mcpComponent.setMcpServerId(30L);

        when(mcpComponentService.getMcpComponent(10L)).thenReturn(mcpComponent);
        when(connectedUserService.fetchConnectedUser("external-user", Environment.STAGING))
            .thenReturn(Optional.empty());

        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getConnection()).thenReturn(mock(ConnectionDefinition.class));
        when(componentDefinitionService.getComponentDefinition("httpClient", 1)).thenReturn(componentDefinition);

        Integration integration = new Integration();

        integration.setId(70L);
        integration.setComponentName("httpClient");

        when(integrationService.getIntegrations()).thenReturn(List.of(integration));

        ClusterElementDefinition clusterElementDefinition = mock(ClusterElementDefinition.class);

        when(clusterElementDefinition.getComponentName()).thenReturn("httpClient");
        when(clusterElementDefinition.getComponentVersion()).thenReturn(1);
        when(clusterElementDefinition.getName()).thenReturn("post");
        when(clusterElementDefinitionService.getClusterElementDefinition("httpClient", 1, "post"))
            .thenReturn(clusterElementDefinition);

        when(mcpServerService.getMcpServer(30L)).thenReturn(
            new McpServer("test-server", PlatformType.EMBEDDED, Environment.STAGING, true));

        FunctionToolCallback<Map<String, Object>, Object> functionToolCallback =
            embeddedMcpToolFacade.getFunctionToolCallback(mcpTool, "external-user", Environment.STAGING, "tenant-1");

        assertThat(functionToolCallback).isNotNull();

        functionToolCallback.call("{}");

        ArgumentCaptor<ToolExecutionEvent> toolExecutionEventArgumentCaptor =
            ArgumentCaptor.forClass(ToolExecutionEvent.class);

        verify(toolExecutionRecorder).record(toolExecutionEventArgumentCaptor.capture());

        ToolExecutionEvent toolExecutionEvent = toolExecutionEventArgumentCaptor.getValue();

        assertThat(toolExecutionEvent.surface()).isEqualTo(ToolExecutionSurface.MCP_EMBEDDED);
        assertThat(toolExecutionEvent.outcome()).isEqualTo(ToolExecutionOutcome.CONNECTION_REQUIRED);
        assertThat(toolExecutionEvent.connectedUserId()).isNull();
        assertThat(toolExecutionEvent.connectionId()).isNull();
        assertThat(toolExecutionEvent.externalUserId()).isEqualTo("external-user");
        assertThat(toolExecutionEvent.environment()).isEqualTo(Environment.STAGING.ordinal());
    }
}

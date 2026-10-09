/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.ai.mcp.server.spi.McpServerToolCallbackContributor;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.facade.ProjectWorkflowFacade;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.workflow.CallableWorkflowChecker;
import com.bytechef.ee.automation.app.facade.AppFacade;
import com.bytechef.ee.automation.app.facade.AppWorkflowFacade;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class AppLoopMcpContributorConfigurationTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final AppLoopMcpContributorConfiguration configuration = new AppLoopMcpContributorConfiguration();

    @Test
    void testContributesTheThreeToolsWrappedForWorkspaceScope() {
        McpServerToolCallbackContributor contributor = configuration.appLoopMcpContributor(
            present(mock(AppFacade.class)), present(mock(AppWorkflowFacade.class)),
            present(mock(CallableWorkflowChecker.class)), present(mock(ProjectDeploymentFacade.class)),
            present(mock(ProjectFacade.class)), present(mock(ProjectWorkflowFacade.class)),
            mock(WorkspaceFacade.class));

        assertThat(contributor.getToolCallbacks())
            .extracting(toolCallback -> toolCallback.getToolDefinition()
                .name())
            .containsExactlyInAnyOrder("deployProject", "listConnections", "listAppCallableWorkflows");
        assertThat(contributor.getToolCallbacks())
            .allSatisfy(toolCallback -> assertThat(toolCallback.getToolDefinition()
                .inputSchema()).contains("workspaceId"));
    }

    @Test
    void testDeployProjectSchemaRequiresEveryConnectionChoiceField() {
        McpServerToolCallbackContributor contributor = configuration.appLoopMcpContributor(
            present(mock(AppFacade.class)), present(mock(AppWorkflowFacade.class)),
            present(mock(CallableWorkflowChecker.class)), present(mock(ProjectDeploymentFacade.class)),
            present(mock(ProjectFacade.class)), present(mock(ProjectWorkflowFacade.class)),
            mock(WorkspaceFacade.class));

        ToolCallback deployProjectToolCallback = contributor.getToolCallbacks()
            .stream()
            .filter(toolCallback -> Objects.equals(
                toolCallback.getToolDefinition()
                    .name(),
                "deployProject"))
            .findFirst()
            .orElseThrow();

        JsonNode connectionChoiceSchemaNode = JSON_MAPPER.readTree(
            deployProjectToolCallback.getToolDefinition()
                .inputSchema())
            .at("/properties/connections/items");

        assertThat(connectionChoiceSchemaNode.get("required"))
            .extracting(JsonNode::asString)
            .containsExactlyInAnyOrder("workflowUuid", "workflowNodeName", "key", "connectionId");
    }

    @Test
    void testContributesNothingWithoutTheAppModule() {
        McpServerToolCallbackContributor contributor = configuration.appLoopMcpContributor(
            absent(), absent(), absent(), present(mock(ProjectDeploymentFacade.class)),
            present(mock(ProjectFacade.class)), present(mock(ProjectWorkflowFacade.class)),
            mock(WorkspaceFacade.class));

        assertThat(contributor.getToolCallbacks()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> present(T value) {
        ObjectProvider<T> objectProvider = mock(ObjectProvider.class);

        when(objectProvider.getIfAvailable()).thenReturn(value);

        return objectProvider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        return mock(ObjectProvider.class);
    }
}

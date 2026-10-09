/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.ai.tool;

import com.bytechef.ai.mcp.server.spi.McpServerToolCallbackContributor;
import com.bytechef.automation.ai.tool.WorkspaceScopedFlatToolCallback;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.facade.ProjectFacade;
import com.bytechef.automation.configuration.facade.ProjectWorkflowFacade;
import com.bytechef.automation.configuration.facade.WorkspaceFacade;
import com.bytechef.automation.configuration.workflow.CallableWorkflowChecker;
import com.bytechef.ee.automation.app.facade.AppFacade;
import com.bytechef.ee.automation.app.facade.AppWorkflowFacade;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Apps agent loop's {@code deployProject}, {@code listConnections} and {@code listAppCallableWorkflows}
 * on the management MCP server, each wrapped in {@link WorkspaceScopedFlatToolCallback}. Every collaborator is
 * optional: a deployment without the app module, or CE, contributes nothing.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(name = "bytechef.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class AppLoopMcpContributorConfiguration {

    @Bean
    McpServerToolCallbackContributor appLoopMcpContributor(
        ObjectProvider<AppFacade> appFacadeProvider, ObjectProvider<AppWorkflowFacade> appWorkflowFacadeProvider,
        ObjectProvider<CallableWorkflowChecker> callableWorkflowCheckerProvider,
        ObjectProvider<ProjectDeploymentFacade> projectDeploymentFacadeProvider,
        ObjectProvider<ProjectFacade> projectFacadeProvider,
        ObjectProvider<ProjectWorkflowFacade> projectWorkflowFacadeProvider, WorkspaceFacade workspaceFacade) {

        return () -> {
            AppFacade appFacade = appFacadeProvider.getIfAvailable();
            AppWorkflowFacade appWorkflowFacade = appWorkflowFacadeProvider.getIfAvailable();
            CallableWorkflowChecker callableWorkflowChecker = callableWorkflowCheckerProvider.getIfAvailable();
            ProjectDeploymentFacade projectDeploymentFacade = projectDeploymentFacadeProvider.getIfAvailable();
            ProjectFacade projectFacade = projectFacadeProvider.getIfAvailable();
            ProjectWorkflowFacade projectWorkflowFacade = projectWorkflowFacadeProvider.getIfAvailable();

            if (appFacade == null || appWorkflowFacade == null || callableWorkflowChecker == null ||
                projectDeploymentFacade == null || projectFacade == null || projectWorkflowFacade == null) {

                return List.of();
            }

            List<ToolCallback> toolCallbacks = new ArrayList<>();

            for (ToolCallback toolCallback : ToolCallbacks.from(
                new AppLoopTools(
                    appFacade, appWorkflowFacade, callableWorkflowChecker, projectDeploymentFacade, projectFacade,
                    projectWorkflowFacade))) {

                toolCallbacks.add(new WorkspaceScopedFlatToolCallback(toolCallback, workspaceFacade));
            }

            return toolCallbacks;
        };
    }
}

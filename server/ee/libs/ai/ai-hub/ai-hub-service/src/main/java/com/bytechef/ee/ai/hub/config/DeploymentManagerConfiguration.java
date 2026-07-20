/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.hub.config;

import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.ee.ai.hub.tool.AiHubAgentType;
import com.bytechef.ee.ai.hub.tool.CreateProjectDeploymentToolCallback;
import com.bytechef.ee.ai.hub.tool.DeleteProjectDeploymentToolCallback;
import com.bytechef.ee.ai.hub.tool.ListProjectDeploymentsToolCallback;
import com.bytechef.ee.ai.hub.tool.ManagerSubAgentToolCallback;
import com.bytechef.ee.ai.hub.tool.PromoteWorkflowToolCallback;
import com.bytechef.ee.ai.hub.tool.RollbackProjectDeploymentToolCallback;
import com.bytechef.ee.ai.hub.tool.ToggleProjectDeploymentToolCallback;
import com.bytechef.ee.ai.hub.tool.UpdateProjectDeploymentToolCallback;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Registers the {@code deploymentManagerChatClient} Spring bean used by the ai_hub BUILD agent.
 *
 * <p>
 * The deployment_manager subagent is a dedicated {@link ChatClient} pre-loaded with the project-deployment lifecycle
 * tools ({@code listProjectDeployments}, {@code createProjectDeployment}, {@code updateProjectDeployment},
 * {@code deleteProjectDeployment}, {@code rollbackProjectDeployment}, {@code toggleProjectDeployment},
 * {@code promoteWorkflow}) and the {@code prompt_deployment_manager.txt} system prompt. Its isolated context means the
 * parent ai_hub BUILD agent never sees the lifecycle transcript — it only receives the final status summary.
 * </p>
 *
 * <p>
 * The {@link ManagerSubAgentToolCallback} is intentionally <em>not</em> a Spring bean. It is instantiated inline in the
 * ai_hub BUILD agent bean method (via {@link #createDeploymentManagerToolCallback}) so that it is registered only on
 * that agent.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Configuration
@ConditionalOnProperty(prefix = "bytechef.ai.hub", name = "enabled", havingValue = "true")
public class DeploymentManagerConfiguration {

    static final String TOOL_DESCRIPTION = """
        Delegate project-deployment lifecycle work to a specialised deployment_manager subagent. It lists,
        creates, updates, enables/disables, rolls back, and deletes project deployments, and promotes a
        workflow between environments. Use for requests like "deploy version 3 of my CRM project to
        production", "disable the staging deployment", "roll back the last deploy", "promote this workflow
        to prod". Pass the user's request verbatim in 'request' plus any project/deployment ids and
        decisions already resolved. Deployment mutations are immediately live and affect running
        automations — delete and rollback are destructive, so only delegate them after the user has
        explicitly confirmed. The subagent returns a status summary with the affected deployment ids; if
        it reports missing decisions (which project, which version, which environment), relay them to the
        user with askUserQuestion and re-delegate with the answers.""";

    @Bean
    ChatClient deploymentManagerChatClient(
        ChatModel chatModel, ProjectDeploymentFacade projectDeploymentFacade,
        @Value("classpath:prompt_deployment_manager.txt") Resource promptResource) {

        String systemPrompt = readPrompt(promptResource);

        return ChatClient.builder(chatModel)
            .defaultSystem(systemPrompt)
            .defaultTools(
                new ListProjectDeploymentsToolCallback(projectDeploymentFacade),
                new CreateProjectDeploymentToolCallback(projectDeploymentFacade),
                new UpdateProjectDeploymentToolCallback(projectDeploymentFacade),
                new DeleteProjectDeploymentToolCallback(projectDeploymentFacade),
                new RollbackProjectDeploymentToolCallback(projectDeploymentFacade),
                new ToggleProjectDeploymentToolCallback(projectDeploymentFacade),
                new PromoteWorkflowToolCallback(projectDeploymentFacade))
            .build();
    }

    static ManagerSubAgentToolCallback createDeploymentManagerToolCallback(ChatClient deploymentManagerChatClient) {
        return new ManagerSubAgentToolCallback(
            AiHubAgentType.DEPLOYMENT_MANAGER, deploymentManagerChatClient, TOOL_DESCRIPTION);
    }

    private String readPrompt(Resource resource) {
        try {
            InputStream inputStream = resource.getInputStream();

            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read deployment manager prompt resource: " + resource.getDescription(), exception);
        }
    }
}

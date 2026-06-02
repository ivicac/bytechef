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

package com.bytechef.ai.mcp.tool.integration;

import com.bytechef.ai.mcp.tool.integration.exception.ConnectedUserProjectWorkflowToolErrorType;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserProjectFacade;
import com.bytechef.exception.ExecutionException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for managing the workflows of an embedded connected user's project. Mirrors the project-workflow tools but
 * scopes every operation to a connected user (external user id + environment).
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class ConnectedUserProjectWorkflowTools {

    private static final Logger log = LoggerFactory.getLogger(ConnectedUserProjectWorkflowTools.class);

    private final ConnectedUserProjectFacade connectedUserProjectFacade;

    @SuppressFBWarnings("EI")
    public ConnectedUserProjectWorkflowTools(ConnectedUserProjectFacade connectedUserProjectFacade) {
        this.connectedUserProjectFacade = connectedUserProjectFacade;
    }

    @Tool(
        description = "Generate a new workflow for an embedded connected user from a natural language prompt using AI Copilot. Returns the new workflow uuid.")
    public String createProjectWorkflowFromPrompt(
        @ToolParam(description = "The external user id of the connected user") String externalUserId,
        @ToolParam(description = "Natural language description of the workflow to build") String prompt,
        @ToolParam(
            required = false,
            description = "The environment (DEVELOPMENT, STAGING, PRODUCTION); defaults to PRODUCTION") @Nullable String environment) {

        try {
            String workflowUuid = connectedUserProjectFacade.createProjectWorkflow(
                externalUserId, prompt, resolveEnvironment(environment), true);

            if (log.isDebugEnabled()) {
                log.debug(
                    "createProjectWorkflowFromPrompt({}, {}): Created workflow {}", externalUserId, environment,
                    workflowUuid);
            }

            return workflowUuid;
        } catch (Exception e) {
            log.error("createProjectWorkflowFromPrompt({}): Failed to create workflow", externalUserId, e);

            throw new ExecutionException(
                "Failed to create connected user project workflow: " + e.getMessage(), e,
                ConnectedUserProjectWorkflowToolErrorType.CREATE_WORKFLOW);
        }
    }

    @Tool(
        description = "Update an existing workflow of an embedded connected user from a natural language prompt using AI Copilot. Returns the workflow uuid.")
    public String updateProjectWorkflowFromPrompt(
        @ToolParam(description = "The external user id of the connected user") String externalUserId,
        @ToolParam(description = "The uuid of the workflow to update") String workflowUuid,
        @ToolParam(description = "Natural language description of the changes to apply to the workflow") String prompt,
        @ToolParam(
            required = false,
            description = "The environment (DEVELOPMENT, STAGING, PRODUCTION); defaults to PRODUCTION") @Nullable String environment) {

        try {
            String workflowUuidResult = connectedUserProjectFacade.updateProjectWorkflow(
                externalUserId, workflowUuid, prompt, resolveEnvironment(environment), true);

            if (log.isDebugEnabled()) {
                log.debug(
                    "updateProjectWorkflowFromPrompt({}, {}): Updated workflow {}", externalUserId, environment,
                    workflowUuidResult);
            }

            return workflowUuidResult;
        } catch (Exception e) {
            log.error("updateProjectWorkflowFromPrompt({}, {}): Failed to update workflow {}", externalUserId,
                environment, workflowUuid, e);

            throw new ExecutionException(
                "Failed to update connected user project workflow: " + e.getMessage(), e,
                ConnectedUserProjectWorkflowToolErrorType.UPDATE_WORKFLOW);
        }
    }

    @Tool(
        description = "Update the definition of an embedded connected user's workflow. Returns a confirmation message.")
    public String updateProjectWorkflow(
        @ToolParam(description = "The external user id of the connected user") String externalUserId,
        @ToolParam(description = "The uuid of the workflow to update") String workflowUuid,
        @ToolParam(description = "The new workflow definition in JSON format") String definition,
        @ToolParam(
            required = false,
            description = "The environment (DEVELOPMENT, STAGING, PRODUCTION); defaults to PRODUCTION") @Nullable String environment) {

        try {
            connectedUserProjectFacade.updateProjectWorkflow(
                externalUserId, workflowUuid, definition, resolveEnvironment(environment));

            if (log.isDebugEnabled()) {
                log.debug("updateProjectWorkflow({}, {}): Updated workflow {}", externalUserId, environment,
                    workflowUuid);
            }

            return "Workflow '" + workflowUuid + "' has been successfully updated.";
        } catch (Exception e) {
            log.error("updateProjectWorkflow({}, {}): Failed to update workflow {}", externalUserId, environment,
                workflowUuid, e);

            throw new ExecutionException(
                "Failed to update connected user project workflow: " + e.getMessage(), e,
                ConnectedUserProjectWorkflowToolErrorType.UPDATE_WORKFLOW);
        }
    }

    @Tool(
        description = "Delete an embedded connected user's workflow. Returns a confirmation message.")
    public String deleteProjectWorkflow(
        @ToolParam(description = "The external user id of the connected user") String externalUserId,
        @ToolParam(description = "The uuid of the workflow to delete") String workflowUuid,
        @ToolParam(
            required = false,
            description = "The environment (DEVELOPMENT, STAGING, PRODUCTION); defaults to PRODUCTION") @Nullable String environment) {

        try {
            connectedUserProjectFacade.deleteProjectWorkflow(
                externalUserId, workflowUuid, resolveEnvironment(environment));

            if (log.isDebugEnabled()) {
                log.debug("deleteProjectWorkflow({}, {}): Deleted workflow {}", externalUserId, environment,
                    workflowUuid);
            }

            return "Workflow '" + workflowUuid + "' has been successfully deleted.";
        } catch (Exception e) {
            log.error("deleteProjectWorkflow({}, {}): Failed to delete workflow {}", externalUserId, environment,
                workflowUuid, e);

            throw new ExecutionException(
                "Failed to delete connected user project workflow: " + e.getMessage(), e,
                ConnectedUserProjectWorkflowToolErrorType.DELETE_WORKFLOW);
        }
    }

    private static Environment resolveEnvironment(@Nullable String environment) {
        if (StringUtils.isBlank(environment)) {
            return Environment.PRODUCTION;
        }

        return Environment.valueOf(StringUtils.upperCase(environment));
    }
}

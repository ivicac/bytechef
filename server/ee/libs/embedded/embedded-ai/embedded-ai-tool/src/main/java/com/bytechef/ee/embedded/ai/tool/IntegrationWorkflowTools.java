/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.ai.tool;

import com.bytechef.ee.embedded.ai.tool.model.CreatedIntegrationWorkflowInfo;
import com.bytechef.ee.embedded.ai.tool.model.IntegrationWorkflowInfo;
import com.bytechef.ee.embedded.configuration.dto.IntegrationWorkflowDTO;
import com.bytechef.ee.embedded.configuration.exception.IntegrationWorkflowErrorType;
import com.bytechef.ee.embedded.configuration.facade.IntegrationWorkflowFacade;
import com.bytechef.exception.ExecutionException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class IntegrationWorkflowTools {

    private static final Logger log = LoggerFactory.getLogger(IntegrationWorkflowTools.class);

    private static final String DEFAULT_DEFINITION = """
        {
            "label": "workflowName",
            "description": "workflowDescription",
            "inputs": [],
            "triggers": [
                {
                    "label": "Manual",
                    "name": "trigger_1",
                    "type": "manual/v1/manual"
                }
            ],
            "tasks": []
        }
        """;

    private final IntegrationWorkflowFacade integrationWorkflowFacade;

    @SuppressFBWarnings("EI")
    public IntegrationWorkflowTools(IntegrationWorkflowFacade integrationWorkflowFacade) {
        this.integrationWorkflowFacade = integrationWorkflowFacade;
    }

    @Tool(
        description = "Get comprehensive information about a specific workflow. Returns detailed information including id, name, description, version, definition, integration workflow id, created date, last modified date.")
    public IntegrationWorkflowInfo getWorkflow(
        @ToolParam(description = "The ID of the workflow to retrieve") String workflowId) {

        try {
            IntegrationWorkflowDTO integrationWorkflowDTO =
                integrationWorkflowFacade.getIntegrationWorkflow(workflowId);

            if (log.isDebugEnabled()) {
                log.debug(
                    "getWorkflow({}): Retrieved workflow {}", workflowId,
                    integrationWorkflowDTO.getIntegrationWorkflowId());
            }

            return toIntegrationWorkflowInfo(integrationWorkflowDTO);
        } catch (Exception exception) {
            log.error("getWorkflow({}): Failed to get workflow {}", workflowId, workflowId, exception);

            throw new ExecutionException(
                "Failed to get workflow: " + exception.getMessage(), exception,
                IntegrationWorkflowErrorType.GET_WORKFLOW);
        }
    }

    @Tool(
        description = "List all workflows in an integration. Returns a list of workflows with their basic information including id, name and description")
    public List<IntegrationWorkflowInfo> listWorkflows(
        @ToolParam(description = "The ID of the integration") long integrationId) {

        try {
            List<IntegrationWorkflowDTO> workflows = integrationWorkflowFacade.getIntegrationWorkflows(integrationId);

            List<IntegrationWorkflowInfo> workflowInfos = workflows.stream()
                .map(IntegrationWorkflowTools::toIntegrationWorkflowInfo)
                .toList();

            if (log.isDebugEnabled()) {
                log.debug("listWorkflows({}): Found {} workflows", integrationId, workflowInfos.size());
            }

            return workflowInfos;
        } catch (Exception exception) {
            log.error("listWorkflows({}): Failed to list integration workflows", integrationId, exception);

            throw new ExecutionException(
                "Failed to list integration workflows: " + exception.getMessage(), exception,
                IntegrationWorkflowErrorType.LIST_WORKFLOWS);
        }
    }

    @Tool(
        description = "Full-text search across integration workflows. Returns a list of workflows matching the search query in name or description.")
    public List<IntegrationWorkflowInfo> searchWorkflows(
        @ToolParam(description = "The search query to match against workflow names and descriptions") String query,
        @ToolParam(required = false, description = "The ID of the integration") Long integrationId) {

        try {
            List<IntegrationWorkflowDTO> allWorkflows = integrationId != null
                ? integrationWorkflowFacade.getIntegrationWorkflows(integrationId)
                : integrationWorkflowFacade.getIntegrationWorkflows();

            String lowerQuery = StringUtils.trim(query.toLowerCase());

            List<IntegrationWorkflowInfo> matchingWorkflows = allWorkflows.stream()
                .filter(workflow -> {
                    String name = workflow.getLabel();

                    name = name != null ? name.toLowerCase() : "";

                    String description = workflow.getDescription();

                    description = description != null ? description.toLowerCase() : "";

                    return name.contains(lowerQuery) || description.contains(lowerQuery);
                })
                .map(IntegrationWorkflowTools::toIntegrationWorkflowInfo)
                .toList();

            if (log.isDebugEnabled()) {
                log.debug(
                    "searchWorkflows({}, {}): Found {} workflows matching query '{}'", query, integrationId,
                    matchingWorkflows.size(), query);
            }

            return matchingWorkflows;
        } catch (Exception exception) {
            log.error(
                "searchWorkflows({}, {}): Failed to search workflows with query '{}'", query, integrationId, query,
                exception);

            throw new ExecutionException(
                "Failed to search workflows: " + exception.getMessage(), exception,
                IntegrationWorkflowErrorType.SEARCH_WORKFLOWS);
        }
    }

    @Tool(
        description = "Create a new workflow in a ByteChef integration. Returns the created workflow information including id, integration id, workflow id, and reference code.")
    public CreatedIntegrationWorkflowInfo createIntegrationWorkflow(
        @ToolParam(description = "The ID of the integration to add the workflow to") long integrationId,
        @ToolParam(
            description = "The definition for the workflow. Needs to be in JSON format similar to " +
                DEFAULT_DEFINITION) String definition) {

        try {
            long integrationWorkflowId = integrationWorkflowFacade.addWorkflow(integrationId, definition);

            IntegrationWorkflowDTO integrationWorkflowDTO =
                integrationWorkflowFacade.getIntegrationWorkflow(integrationWorkflowId);

            if (log.isDebugEnabled()) {
                log.debug(
                    "createIntegrationWorkflow({}): Created workflow {} for integration {}", integrationId,
                    integrationWorkflowId, integrationId);
            }

            return new CreatedIntegrationWorkflowInfo(
                integrationWorkflowDTO.getIntegrationWorkflowId(), integrationId,
                integrationWorkflowDTO.getIntegrationVersion(), integrationWorkflowDTO.getId(),
                integrationWorkflowDTO.getWorkflowUuid(), integrationWorkflowDTO.getCreatedDate(),
                integrationWorkflowDTO.getLastModifiedDate());
        } catch (Exception exception) {
            log.error(
                "createIntegrationWorkflow({}): Failed to create workflow for integration {}", integrationId,
                integrationId, exception);

            throw new ExecutionException(
                "Failed to create integration workflow: " + exception.getMessage(), exception,
                IntegrationWorkflowErrorType.CREATE_WORKFLOW);
        }
    }

    @Tool(description = "Delete a workflow. Returns a confirmation message.")
    public String deleteWorkflow(
        @ToolParam(description = "The ID of the workflow to delete") String workflowId) {

        try {
            IntegrationWorkflowDTO integrationWorkflowDTO =
                integrationWorkflowFacade.getIntegrationWorkflow(workflowId);

            String workflowName = integrationWorkflowDTO.getLabel();

            integrationWorkflowFacade.deleteWorkflow(workflowId);

            if (log.isDebugEnabled()) {
                log.debug(
                    "deleteWorkflow({}): Deleted workflow {} with name '{}'", workflowId, workflowId, workflowName);
            }

            return "Workflow '" + workflowName + "' (ID: " + workflowId + ") has been successfully deleted.";
        } catch (Exception exception) {
            log.error("deleteWorkflow({}): Failed to delete workflow {}", workflowId, workflowId, exception);

            throw new ExecutionException(
                "Failed to delete workflow: " + exception.getMessage(), exception,
                IntegrationWorkflowErrorType.DELETE_WORKFLOW);
        }
    }

    @Tool(description = "Update the workflow definition. Returns the updated workflow id, name and definition.")
    public IntegrationWorkflowInfo updateWorkflow(
        @ToolParam(description = "The ID of the workflow to update") String workflowId,
        @ToolParam(
            description = "The new definition of the workflow. Needs to be in JSON format similar to " +
                DEFAULT_DEFINITION) String definition) {

        try {
            IntegrationWorkflowDTO integrationWorkflowDTO =
                integrationWorkflowFacade.getIntegrationWorkflow(workflowId);

            IntegrationWorkflowDTO updatedIntegrationWorkflowDTO = integrationWorkflowFacade.updateWorkflow(
                workflowId, definition, integrationWorkflowDTO.getVersion());

            if (log.isDebugEnabled()) {
                log.debug(
                    "updateWorkflow({}): Updated workflow {} with name '{}'", workflowId, workflowId,
                    updatedIntegrationWorkflowDTO.getLabel());
            }

            return toIntegrationWorkflowInfo(updatedIntegrationWorkflowDTO);
        } catch (Exception exception) {
            log.error("updateWorkflow({}): Failed to update workflow {}", workflowId, workflowId, exception);

            throw new ExecutionException(
                "Failed to update workflow: " + exception.getMessage(), exception,
                IntegrationWorkflowErrorType.UPDATE_WORKFLOW);
        }
    }

    private static IntegrationWorkflowInfo toIntegrationWorkflowInfo(IntegrationWorkflowDTO integrationWorkflowDTO) {
        return new IntegrationWorkflowInfo(
            integrationWorkflowDTO.getId(), integrationWorkflowDTO.getIntegrationWorkflowId(),
            integrationWorkflowDTO.getWorkflowUuid(), integrationWorkflowDTO.getLabel(),
            integrationWorkflowDTO.getDescription(), integrationWorkflowDTO.getDefinition(),
            integrationWorkflowDTO.getVersion(), integrationWorkflowDTO.getCreatedDate(),
            integrationWorkflowDTO.getLastModifiedDate());
    }
}

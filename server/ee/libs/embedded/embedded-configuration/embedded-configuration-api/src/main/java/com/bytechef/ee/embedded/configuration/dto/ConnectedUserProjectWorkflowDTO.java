/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.dto;

import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.platform.configuration.dto.WorkflowDTO;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@SuppressFBWarnings("EI")
public record ConnectedUserProjectWorkflowDTO(
    long id, long connectedUserId, boolean enabled, Instant lastExecutionDate, long projectId, WorkflowDTO workflow,
    String workflowUuid, Integer workflowVersion, Kind kind, String catalogWorkflowUuid,
    String copiedFromWorkflowUuid, boolean dangling, List<ConnectedUserWorkflowTemplateDTO.Component> components,
    List<ConnectedUserWorkflowTemplateDTO.Input> inputs, Map<String, ?> inputValues) {

    /**
     * {@code COPY} when the workflow is the connected user's own editable copy; {@code REFERENCE} when it points at a
     * shared catalog workflow instead (see the embedded automation code workflow bridge in the repository docs).
     */
    public enum Kind {
        COPY, REFERENCE
    }

    /**
     * Copy-mode row (the user's own project workflow).
     */
    public ConnectedUserProjectWorkflowDTO(
        long connectedUserId, ConnectedUserProjectWorkflow connectedUserProjectWorkflow, boolean enabled,
        Instant lastExecutionDate, ProjectWorkflow projectWorkflow, WorkflowDTO workflow,
        List<ConnectedUserWorkflowTemplateDTO.Component> components) {

        this(
            connectedUserId, connectedUserProjectWorkflow, enabled, lastExecutionDate, projectWorkflow, workflow,
            components, List.of(), Map.of());
    }

    public ConnectedUserProjectWorkflowDTO(
        long connectedUserId, ConnectedUserProjectWorkflow connectedUserProjectWorkflow, boolean enabled,
        Instant lastExecutionDate, ProjectWorkflow projectWorkflow, WorkflowDTO workflow,
        List<ConnectedUserWorkflowTemplateDTO.Component> components,
        List<ConnectedUserWorkflowTemplateDTO.Input> inputs, Map<String, ?> inputValues) {

        this(
            connectedUserProjectWorkflow.getId(), connectedUserId, enabled, lastExecutionDate,
            projectWorkflow.getProjectId(), workflow, projectWorkflow.getUuidAsString(),
            connectedUserProjectWorkflow.getWorkflowVersion(), Kind.COPY, null,
            connectedUserProjectWorkflow.getCopiedFromWorkflowUuid(), false, components, inputs, inputValues);
    }

    /**
     * Reference-mode row (points at a shared catalog workflow).
     */
    public static ConnectedUserProjectWorkflowDTO ofReference(
        long connectedUserId, ConnectedUserProjectWorkflow reference, WorkflowDTO catalogWorkflow,
        List<ConnectedUserWorkflowTemplateDTO.Component> components) {

        return ofReference(connectedUserId, reference, catalogWorkflow, components, List.of(), Map.of());
    }

    public static ConnectedUserProjectWorkflowDTO ofReference(
        long connectedUserId, ConnectedUserProjectWorkflow reference, WorkflowDTO catalogWorkflow,
        List<ConnectedUserWorkflowTemplateDTO.Component> components,
        List<ConnectedUserWorkflowTemplateDTO.Input> inputs, Map<String, ?> inputValues) {

        return new ConnectedUserProjectWorkflowDTO(
            reference.getId(), connectedUserId, reference.isEnabled(), null, 0L, catalogWorkflow,
            reference.getCatalogWorkflowUuid(), reference.getWorkflowVersion(), Kind.REFERENCE,
            reference.getCatalogWorkflowUuid(), null, reference.isDangling(), components, inputs, inputValues);
    }
}

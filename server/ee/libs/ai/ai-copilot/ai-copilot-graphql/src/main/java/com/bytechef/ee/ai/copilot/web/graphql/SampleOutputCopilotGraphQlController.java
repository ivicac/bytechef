/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.graphql;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.ai.copilot.sampleoutput.SampleOutputCopilotGenerator;
import com.bytechef.ee.ai.copilot.sampleoutput.SampleOutputCopilotRequest;
import com.bytechef.ee.ai.copilot.sampleoutput.SampleOutputCopilotResult;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

/**
 * GraphQL controller for the Sample Output Copilot feature.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Controller
@ConditionalOnEEVersion
@ConditionalOnCoordinator
@SuppressFBWarnings("EI")
public class SampleOutputCopilotGraphQlController {

    private static final String WORKFLOW_VIEW_SCOPE = "WORKFLOW_VIEW";

    private final PermissionService permissionService;
    private final ProjectWorkflowService projectWorkflowService;
    private final SampleOutputCopilotGenerator sampleOutputCopilotGenerator;

    public SampleOutputCopilotGraphQlController(
        PermissionService permissionService, ProjectWorkflowService projectWorkflowService,
        Optional<SampleOutputCopilotGenerator> sampleOutputCopilotGenerator) {

        this.permissionService = permissionService;
        this.projectWorkflowService = projectWorkflowService;
        this.sampleOutputCopilotGenerator = sampleOutputCopilotGenerator.orElse(null);
    }

    @MutationMapping
    public GenerateSampleOutputPayload generateSampleOutput(@Argument GenerateSampleOutputInput input) {
        if (sampleOutputCopilotGenerator == null) {
            throw new IllegalStateException("Sample Output Copilot is not enabled");
        }

        // Authorize: the workflowId is client-supplied, so verify the current user may view the owning
        // project before generating (IDOR / cross-tenant guard).
        long projectId = projectWorkflowService.getWorkflowProjectWorkflow(input.workflowId())
            .getProjectId();

        if (!permissionService.hasProjectScope(projectId, WORKFLOW_VIEW_SCOPE)) {
            throw new AccessDeniedException("Access denied to workflow " + input.workflowId());
        }

        SampleOutputCopilotResult result = sampleOutputCopilotGenerator.generate(
            new SampleOutputCopilotRequest(input.workflowId(), input.prompt(), input.environmentId()));

        return new GenerateSampleOutputPayload(result.value(), result.valid(), result.message());
    }

    @SuppressFBWarnings("EI")
    public record GenerateSampleOutputInput(String workflowId, String prompt, long environmentId) {
    }

    @SuppressFBWarnings("EI")
    public record GenerateSampleOutputPayload(String value, boolean valid, String message) {
    }
}

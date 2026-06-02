/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.ai.copilot.sampleoutput.SampleOutputCopilotGenerator;
import com.bytechef.ee.ai.copilot.sampleoutput.SampleOutputCopilotResult;
import com.bytechef.ee.ai.copilot.web.graphql.SampleOutputCopilotGraphQlController.GenerateSampleOutputInput;
import com.bytechef.ee.ai.copilot.web.graphql.SampleOutputCopilotGraphQlController.GenerateSampleOutputPayload;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class SampleOutputCopilotGraphQlControllerTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    private final ProjectWorkflowService projectWorkflowService = mock(ProjectWorkflowService.class);
    private final SampleOutputCopilotGenerator generator = mock(SampleOutputCopilotGenerator.class);

    private final SampleOutputCopilotGraphQlController controller = new SampleOutputCopilotGraphQlController(
        permissionService, projectWorkflowService, Optional.of(generator));

    @Test
    void testGenerateDeniedWhenUserLacksWorkflowViewScope() {
        givenWorkflowProject(42L);

        when(permissionService.hasProjectScope(42L, "WORKFLOW_VIEW")).thenReturn(false);

        assertThatThrownBy(() -> controller.generateSampleOutput(input()))
            .isInstanceOf(AccessDeniedException.class);

        verify(generator, never()).generate(any());
    }

    @Test
    void testGenerateAllowedWhenUserHasWorkflowViewScope() {
        givenWorkflowProject(42L);

        when(permissionService.hasProjectScope(42L, "WORKFLOW_VIEW")).thenReturn(true);
        when(generator.generate(any())).thenReturn(new SampleOutputCopilotResult("{\"id\":1}", true, null));

        GenerateSampleOutputPayload payload = controller.generateSampleOutput(input());

        assertThat(payload.value()).isEqualTo("{\"id\":1}");
        assertThat(payload.valid()).isTrue();
    }

    @Test
    void testGenerateThrowsWhenCopilotDisabled() {
        SampleOutputCopilotGraphQlController disabledController = new SampleOutputCopilotGraphQlController(
            permissionService, projectWorkflowService, Optional.empty());

        assertThatThrownBy(() -> disabledController.generateSampleOutput(input()))
            .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(projectWorkflowService, permissionService);
    }

    private void givenWorkflowProject(long projectId) {
        ProjectWorkflow projectWorkflow = mock(ProjectWorkflow.class);

        when(projectWorkflow.getProjectId()).thenReturn(projectId);
        when(projectWorkflowService.getWorkflowProjectWorkflow("wf1")).thenReturn(projectWorkflow);
    }

    private static GenerateSampleOutputInput input() {
        return new GenerateSampleOutputInput("wf1", "order", 0);
    }
}

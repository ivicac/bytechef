/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserWorkflowTemplateDTO;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.workflow.task.dispatcher.domain.TaskDispatcherDefinition;
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith(ObjectMapperSetupExtension.class)
class WorkflowComponentResolverTest {

    private final ComponentDefinitionService componentDefinitionService = mock(ComponentDefinitionService.class);
    private final TaskDispatcherDefinitionService taskDispatcherDefinitionService =
        mock(TaskDispatcherDefinitionService.class);
    private final WorkflowComponentResolver workflowComponentResolver =
        new WorkflowComponentResolver(componentDefinitionService, taskDispatcherDefinitionService);

    @Test
    void testComponentTaskResolvesToItsComponentDefinition() {
        stubNoComponents();
        stubNoTaskDispatchers();

        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn("gmail");
        when(componentDefinition.getTitle()).thenReturn("Gmail");
        when(componentDefinition.getIcon()).thenReturn("path:assets/gmail.svg");

        when(componentDefinitionService.fetchComponentDefinition(eq("gmail"), anyInt()))
            .thenReturn(Optional.of(componentDefinition));

        assertEquals(
            List.of(new ConnectedUserWorkflowTemplateDTO.Component("gmail", "Gmail", "path:assets/gmail.svg")),
            workflowComponentResolver.getTaskComponents(workflow("gmail/v1/sendEmail")));
    }

    /**
     * A task dispatcher is not a component, so the component lookup misses it. Without the second tier a workflow
     * containing one -- a condition, a loop, Call AI Agent -- listed a row titled with its bare type name instead of
     * the dispatcher's own title and icon.
     */
    @Test
    void testTaskDispatcherTaskResolvesToItsTaskDispatcherDefinition() {
        stubNoComponents();
        stubNoTaskDispatchers();

        TaskDispatcherDefinition taskDispatcherDefinition = mock(TaskDispatcherDefinition.class);

        when(taskDispatcherDefinition.getName()).thenReturn("condition");
        when(taskDispatcherDefinition.getTitle()).thenReturn("Condition");
        when(taskDispatcherDefinition.getIcon()).thenReturn("path:assets/condition.svg");

        when(taskDispatcherDefinitionService.fetchTaskDispatcherDefinition(eq("condition"), anyInt()))
            .thenReturn(Optional.of(taskDispatcherDefinition));

        assertEquals(
            List.of(
                new ConnectedUserWorkflowTemplateDTO.Component(
                    "condition", "Condition", "path:assets/condition.svg")),
            workflowComponentResolver.getTaskComponents(workflow("condition/v1/condition")));
    }

    /**
     * Neither tier knows the type -- an unregistered component on this deployment -- so the row falls back to the bare
     * name rather than dropping out of the listing.
     */
    @Test
    void testUnknownTaskFallsBackToItsBareName() {
        stubNoComponents();
        stubNoTaskDispatchers();

        assertEquals(
            List.of(new ConnectedUserWorkflowTemplateDTO.Component("mystery", "mystery", null)),
            workflowComponentResolver.getTaskComponents(workflow("mystery/v1/doSomething")));
    }

    private void stubNoComponents() {
        when(componentDefinitionService.fetchComponentDefinition(anyString(), anyInt()))
            .thenReturn(Optional.empty());
    }

    private void stubNoTaskDispatchers() {
        when(taskDispatcherDefinitionService.fetchTaskDispatcherDefinition(anyString(), anyInt()))
            .thenReturn(Optional.empty());
    }

    private static Workflow workflow(String taskType) {
        return new Workflow(
            "{\"tasks\":[{\"name\":\"task_1\",\"type\":\"" + taskType + "\"}]}", Workflow.Format.JSON);
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserWorkflowTemplateDTO;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.workflow.task.dispatcher.domain.TaskDispatcherDefinition;
import com.bytechef.platform.workflow.task.dispatcher.service.TaskDispatcherDefinitionService;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

    private WorkflowComponentResolver workflowComponentResolver;

    @BeforeEach
    void beforeEach() {
        when(componentDefinitionService.fetchComponentDefinition(anyString(), anyInt()))
            .thenReturn(Optional.empty());
        when(taskDispatcherDefinitionService.fetchTaskDispatcherDefinition(anyString(), anyInt()))
            .thenReturn(Optional.empty());

        workflowComponentResolver = new WorkflowComponentResolver(
            componentDefinitionService, taskDispatcherDefinitionService);
    }

    @Test
    void testComponentTaskResolvesToItsComponentDefinition() {
        ComponentDefinition componentDefinition = mock(ComponentDefinition.class);

        when(componentDefinition.getName()).thenReturn("gmail");
        when(componentDefinition.getTitle()).thenReturn("Gmail");
        when(componentDefinition.getIcon()).thenReturn("path:assets/gmail.svg");

        when(componentDefinitionService.fetchComponentDefinition(eq("gmail"), anyInt()))
            .thenReturn(Optional.of(componentDefinition));

        assertThat(workflowComponentResolver.getTaskComponents(workflowWithTask("gmail/v1/sendEmail")))
            .containsExactly(new ConnectedUserWorkflowTemplateDTO.Component("gmail", "Gmail", "path:assets/gmail.svg"));
    }

    @Test
    void testConnectionRequiredIsTrueForAComponentWithAConnection() {
        when(componentDefinitionService.fetchComponentDefinition("slack", 1))
            .thenReturn(Optional.of(new ComponentDefinition(
                ComponentDsl.component("slack")
                    .title("Slack")
                    .connection(ComponentDsl.connection()))));

        List<ConnectedUserWorkflowTemplateDTO.Component> components = workflowComponentResolver.getTaskComponents(
            workflowWithTask("slack/v1/sendMessage"));

        assertThat(components)
            .singleElement()
            .satisfies(component -> {
                assertThat(component.name()).isEqualTo("slack");
                assertThat(component.connectionRequired()).isTrue();
                assertThat(component.integrationId()).isNull();
            });
    }

    @Test
    void testConnectionRequiredIsFalseForAComponentWithoutAConnection() {
        when(componentDefinitionService.fetchComponentDefinition("logger", 1))
            .thenReturn(Optional.of(new ComponentDefinition(
                ComponentDsl.component("logger")
                    .title("Logger"))));

        List<ConnectedUserWorkflowTemplateDTO.Component> components = workflowComponentResolver.getTaskComponents(
            workflowWithTask("logger/v1/info"));

        assertThat(components)
            .singleElement()
            .satisfies(component -> assertThat(component.connectionRequired()).isFalse());
    }

    @Test
    void testConnectionRequiredIsFalseForATaskDispatcher() {
        TaskDispatcherDefinition taskDispatcherDefinition = mock(TaskDispatcherDefinition.class);

        when(taskDispatcherDefinition.getName()).thenReturn("condition");
        when(taskDispatcherDefinition.getTitle()).thenReturn("Condition");
        when(taskDispatcherDefinitionService.fetchTaskDispatcherDefinition("condition", 1))
            .thenReturn(Optional.of(taskDispatcherDefinition));

        List<ConnectedUserWorkflowTemplateDTO.Component> components = workflowComponentResolver.getTaskComponents(
            workflowWithTask("condition/v1"));

        assertThat(components)
            .singleElement()
            .satisfies(component -> {
                assertThat(component.name()).isEqualTo("condition");
                assertThat(component.connectionRequired()).isFalse();
            });
    }

    /**
     * A task dispatcher is not a component, so the component lookup misses it. Without the second tier a workflow
     * containing one -- a condition, a loop, Call AI Agent -- listed a row titled with its bare type name instead of
     * the dispatcher's own title and icon.
     */
    @Test
    void testTaskDispatcherTaskResolvesToItsTaskDispatcherDefinition() {
        TaskDispatcherDefinition taskDispatcherDefinition = mock(TaskDispatcherDefinition.class);

        when(taskDispatcherDefinition.getName()).thenReturn("condition");
        when(taskDispatcherDefinition.getTitle()).thenReturn("Condition");
        when(taskDispatcherDefinition.getIcon()).thenReturn("path:assets/condition.svg");

        when(taskDispatcherDefinitionService.fetchTaskDispatcherDefinition(eq("condition"), anyInt()))
            .thenReturn(Optional.of(taskDispatcherDefinition));

        assertThat(workflowComponentResolver.getTaskComponents(workflowWithTask("condition/v1/condition")))
            .containsExactly(
                new ConnectedUserWorkflowTemplateDTO.Component("condition", "Condition", "path:assets/condition.svg"));
    }

    /**
     * Neither tier knows the type -- an unregistered component on this deployment -- so the row falls back to the bare
     * name rather than dropping out of the listing.
     */
    @Test
    void testUnknownTaskFallsBackToItsBareName() {
        assertThat(workflowComponentResolver.getTaskComponents(workflowWithTask("mystery/v1/doSomething")))
            .containsExactly(new ConnectedUserWorkflowTemplateDTO.Component("mystery", "mystery", null));
    }

    private static Workflow workflowWithTask(String type) {
        return new Workflow(
            "{\"tasks\":[{\"name\":\"task_1\",\"type\":\"" + type + "\",\"parameters\":{}}]}", Workflow.Format.JSON);
    }
}

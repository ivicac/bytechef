/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.domain.WorkflowTask;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class WorkflowConnectionSlotsTest {

    private static final String DEFINITION = """
        {
          "label": "Nested",
          "triggers": [
            {"name": "newMessage1", "type": "slack/v1/newMessage"}
          ],
          "tasks": [
            {
              "name": "condition1",
              "type": "condition/v1",
              "parameters": {
                "rawExpression": true,
                "caseTrue": [
                  {"name": "createIssue1", "type": "jira/v1/createIssue", "parameters": {"summary": "s"}}
                ],
                "caseFalse": []
              }
            }
          ]
        }
        """;

    @Mock
    private ComponentConnectionFacade componentConnectionFacade;

    @Mock
    private WorkflowService workflowService;

    private WorkflowConnectionSlots workflowConnectionSlots;

    @BeforeEach
    void setUp() {
        workflowConnectionSlots = new WorkflowConnectionSlots(componentConnectionFacade, workflowService);
    }

    @Test
    void testGetSlotsCoversTriggersAndTasksNestedInsideDispatchers() {
        when(workflowService.getWorkflow("wf-1")).thenReturn(new Workflow("wf-1", DEFINITION, Workflow.Format.JSON));
        when(componentConnectionFacade.getComponentConnections(any(WorkflowTrigger.class)))
            .thenAnswer(invocation -> {
                WorkflowTrigger workflowTrigger = invocation.getArgument(0);

                return List.of(
                    new ComponentConnection("slack", 1, workflowTrigger.getName(), "slack", true));
            });
        when(componentConnectionFacade.getComponentConnections(any(WorkflowTask.class)))
            .thenAnswer(invocation -> {
                WorkflowTask workflowTask = invocation.getArgument(0);

                return workflowTask.getName()
                    .equals("createIssue1")
                        ? List.of(new ComponentConnection("jira", 1, "createIssue1", "jira", true))
                        : List.of();
            });

        List<ComponentConnection> slots = workflowConnectionSlots.getSlots("wf-1");

        assertThat(slots)
            .extracting(ComponentConnection::componentName, ComponentConnection::workflowNodeName)
            .containsExactly(
                tuple("slack", "newMessage1"),
                tuple("jira", "createIssue1"));
    }
}

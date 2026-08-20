/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.ai.copilot.web.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.ai.copilot.property.PropertyCopilotMode;
import com.bytechef.ee.ai.copilot.property.PropertyCopilotRequest;
import com.bytechef.ee.ai.copilot.property.PropertyCopilotResult;
import com.bytechef.ee.ai.copilot.web.graphql.facade.PropertyCopilotFacade;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins that the mutation reaches the generator through the facade layer, which is where this codebase puts
 * authorization. The {@code WORKFLOW_VIEW} guard itself is pinned by
 * {@code PropertyCopilotFacadeImplTest#testGenerateDeniedWhenUserLacksWorkflowViewScope}.
 *
 * <p>
 * The controller carries no gate of its own and is not meant to. Asserting the delegation is not enough on its own:
 * {@code testControllerHoldsNoAuthorizationCollaborators} is what makes a revert to a locally written
 * {@code permissionService} check fail here rather than pass, since such a check has to reintroduce one of those
 * collaborators as a field.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@ContextConfiguration(classes = PropertyCopilotGraphQlController.class)
@GraphQlTest(
    controllers = PropertyCopilotGraphQlController.class,
    properties = {
        "bytechef.coordinator.enabled=true",
        "bytechef.edition=ee",
        "spring.graphql.schema.locations=classpath*:/graphql/"
    })
class PropertyCopilotGraphQlControllerIntTest {

    @MockitoBean
    private PropertyCopilotFacade propertyCopilotFacade;

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    void testGeneratePropertyValueDelegatesToTheGuardedFacade() {
        when(propertyCopilotFacade.generatePropertyValue(any()))
            .thenReturn(new PropertyCopilotResult("Hello", true, null));

        graphQlTester
            .document(
                """
                    mutation {
                        generatePropertyValue(input: {
                            prompt: "greet"
                            mode: TEXT
                            workflowId: "wf1"
                            workflowNodeName: "n1"
                            propertyPath: "p"
                            propertyType: "STRING"
                            dynamic: true
                            environmentId: 0
                        }) {
                            value
                            valid
                            message
                        }
                    }
                    """)
            .execute()
            .path("generatePropertyValue.value")
            .entity(String.class)
            .isEqualTo("Hello")
            .path("generatePropertyValue.valid")
            .entity(Boolean.class)
            .isEqualTo(true);

        ArgumentCaptor<PropertyCopilotRequest> requestArgumentCaptor =
            ArgumentCaptor.forClass(PropertyCopilotRequest.class);

        verify(propertyCopilotFacade).generatePropertyValue(requestArgumentCaptor.capture());

        assertThat(requestArgumentCaptor.getValue()).isEqualTo(
            new PropertyCopilotRequest(
                "greet", PropertyCopilotMode.TEXT, "wf1", "n1", "p", "STRING", true, 0));
    }

    @Test
    void testControllerHoldsNoAuthorizationCollaborators() {
        assertThat(Arrays.stream(PropertyCopilotGraphQlController.class.getDeclaredFields())
            .map(Field::getType))
                .as("authorization belongs on the facade; the controller must not hold the services a gate needs")
                .doesNotContain(PermissionService.class, ProjectWorkflowService.class);
    }
}

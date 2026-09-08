/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceWorkflow;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.List;
import java.util.Map;
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
class FieldMappingDescriptorResolverTest {

    private static final long ENVIRONMENT_ID = 1L;
    private static final long INSTANCE_ID = 42L;
    private static final String WORKFLOW_ID = "wf-1";

    private static final String DEFINITION = """
        {
          "label": "Sync contacts",
          "inputs": [
            {"name": "contactMapping", "label": "Contact Mapping", "type": "field_mapping", "objectName": "Contacts"},
            {"name": "accountMapping", "label": "Account Mapping", "type": "field_mapping", "objectName": "Accounts"},
            {"name": "apiKey", "label": "API Key", "type": "string"}
          ],
          "tasks": []
        }
        """;

    private static final Map<String, Object> SAVED_MAPPING = Map.of(
        "objectType", "contacts",
        "mappings", List.of(
            Map.of(
                "applicationField", Map.of("label", "Title", "value", "title", "custom", false),
                "integrationField", "first_name")));

    private static final FieldMappingDescriptor EXPECTED = new FieldMappingDescriptor(
        "contacts", List.of(new FieldMappingDescriptor.Mapping("title", "first_name")));

    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService =
        mock(IntegrationInstanceWorkflowService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowTestConfigurationService workflowTestConfigurationService =
        mock(WorkflowTestConfigurationService.class);

    private FieldMappingDescriptorResolver resolver;

    @BeforeEach
    void setUp() {
        when(workflowService.getWorkflow(WORKFLOW_ID))
            .thenReturn(new Workflow(WORKFLOW_ID, DEFINITION, Workflow.Format.JSON));

        resolver = new FieldMappingDescriptorResolver(
            integrationInstanceWorkflowService, workflowService, workflowTestConfigurationService);
    }

    @Test
    void testRuntimeResolvesTheConnectedUsersSavedMapping() {
        IntegrationInstanceWorkflow integrationInstanceWorkflow = new IntegrationInstanceWorkflow();

        integrationInstanceWorkflow.setInputs(Map.of("contactMapping", SAVED_MAPPING, "apiKey", "k"));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(integrationInstanceWorkflow));

        FieldMappingDescriptor descriptor = resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED));

        assertEquals(EXPECTED, descriptor);
    }

    @Test
    void testRuntimeFailsWhenPlatformTypeIsNotEmbedded() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.AUTOMATION)));

        assertTrue(exception.getMessage()
            .contains("embedded"), exception.getMessage());
    }

    @Test
    void testEditorFailsWhenPlatformTypeIsNotEmbedded() {
        ActionContextAware context = editorContext();

        when(context.getPlatformType()).thenReturn(PlatformType.AUTOMATION);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class, () -> resolver.resolve("Contacts", context));

        assertTrue(exception.getMessage()
            .contains("embedded"), exception.getMessage());
    }

    @Test
    void testRuntimeFailsWhenIntegrationInstanceIsMissing() {
        ActionContextAware context = runtimeContext(PlatformType.EMBEDDED);

        when(context.getJobPrincipalId()).thenReturn(null);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class, () -> resolver.resolve("Contacts", context));

        assertTrue(exception.getMessage()
            .contains("integration instance"), exception.getMessage());
    }

    @Test
    void testRuntimeFailsWhenConnectedUserHasNoRow() {
        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED)));

        assertTrue(exception.getMessage()
            .contains("Contact Mapping"), exception.getMessage());
    }

    @Test
    void testRuntimeFailsWhenSavedMappingIsEmpty() {
        IntegrationInstanceWorkflow integrationInstanceWorkflow = new IntegrationInstanceWorkflow();

        integrationInstanceWorkflow.setInputs(
            Map.of("contactMapping", Map.of("objectType", "contacts", "mappings", List.of())));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(integrationInstanceWorkflow));

        assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED)));
    }

    @Test
    void testRuntimeNeverReadsAnotherConnectedUsersMapping() {
        long otherInstanceId = 43L;

        IntegrationInstanceWorkflow integrationInstanceWorkflow = new IntegrationInstanceWorkflow();

        integrationInstanceWorkflow.setInputs(Map.of("contactMapping", SAVED_MAPPING));

        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(INSTANCE_ID, WORKFLOW_ID))
            .thenReturn(Optional.of(integrationInstanceWorkflow));
        when(integrationInstanceWorkflowService.fetchIntegrationInstanceWorkflow(otherInstanceId, WORKFLOW_ID))
            .thenReturn(Optional.empty());

        ActionContextAware otherUsersContext = runtimeContext(PlatformType.EMBEDDED);

        when(otherUsersContext.getJobPrincipalId()).thenReturn(otherInstanceId);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("Contacts", otherUsersContext));
    }

    @Test
    void testUnknownObjectNameListsTheDeclaredOnes() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Deals", runtimeContext(PlatformType.EMBEDDED)));

        assertTrue(exception.getMessage()
            .contains("Deals"), exception.getMessage());
        assertTrue(exception.getMessage()
            .contains("Contacts"), exception.getMessage());
    }

    @Test
    void testFindInputIgnoresANonFieldMappingInputWithAMatchingObjectNameExtension() {
        String definition = """
            {
              "label": "Sync contacts",
              "inputs": [
                {"name": "apiKey", "label": "API Key", "type": "string", "objectName": "Contacts"}
              ],
              "tasks": []
            }
            """;

        when(workflowService.getWorkflow(WORKFLOW_ID))
            .thenReturn(new Workflow(WORKFLOW_ID, definition, Workflow.Format.JSON));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED)));

        assertTrue(exception.getMessage()
            .contains("No field mapping input declares object name 'Contacts'"), exception.getMessage());
    }

    @Test
    void testFindInputFailsWhenTwoFieldMappingInputsShareAnObjectName() {
        String definition =
            """
                {
                  "label": "Sync contacts",
                  "inputs": [
                    {"name": "contactMapping", "label": "Contact Mapping", "type": "field_mapping", "objectName": "Contacts"},
                    {"name": "contactMapping2", "label": "Contact Mapping 2", "type": "field_mapping", "objectName": "Contacts"}
                  ],
                  "tasks": []
                }
                """;

        when(workflowService.getWorkflow(WORKFLOW_ID))
            .thenReturn(new Workflow(WORKFLOW_ID, definition, Workflow.Format.JSON));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> resolver.resolve("Contacts", runtimeContext(PlatformType.EMBEDDED)));

        assertTrue(exception.getMessage()
            .contains("contactMapping"), exception.getMessage());
        assertTrue(exception.getMessage()
            .contains("contactMapping2"), exception.getMessage());
    }

    @Test
    void testMissingWorkflowIdFails() {
        ActionContextAware context = mock(ActionContextAware.class);

        when(context.getWorkflowId()).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> resolver.resolve("Contacts", context));
    }

    @Test
    void testEditorResolvesSampleMappingFromTheInputTestValue() {
        String testValue = """
            {"Contacts": {
               "objectTypes": [{"label": "Contacts", "value": "contacts"}],
               "integrationFields": [{"label": "First Name", "value": "first_name"}],
               "applicationFields": {"fields": [{"label": "Title", "value": "title"}]},
               "sampleMapping": {
                 "objectType": "contacts",
                 "mappings": [
                   {"applicationField": {"label": "Title", "value": "title", "custom": false},
                    "integrationField": "first_name"}
                 ]
               }
            }}
            """;

        doReturn(Map.of("contactMapping", testValue))
            .when(workflowTestConfigurationService)
            .getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID);

        FieldMappingDescriptor descriptor = resolver.resolve("Contacts", editorContext());

        assertEquals(EXPECTED, descriptor);
    }

    @Test
    void testEditorAcceptsTheMapObjectFieldsEnvelope() {
        String testValue = """
            {"mapObjectFields": {"Contacts": {
               "sampleMapping": {"objectType": "contacts", "mappings": [
                 {"applicationField": {"value": "title"}, "integrationField": "first_name"}]}
            }}}
            """;

        doReturn(Map.of("contactMapping", testValue))
            .when(workflowTestConfigurationService)
            .getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID);

        assertEquals(EXPECTED, resolver.resolve("Contacts", editorContext()));
    }

    @Test
    void testEditorFailsWhenTestValueIsKeyedUnderADifferentObjectName() {
        String testValue = """
            {"Contacts": {
               "sampleMapping": {"objectType": "contacts", "mappings": []}
            }}
            """;

        doReturn(Map.of("accountMapping", testValue))
            .when(workflowTestConfigurationService)
            .getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Accounts", editorContext()));

        assertTrue(exception.getMessage()
            .contains("Accounts"), exception.getMessage());
        assertTrue(exception.getMessage()
            .contains("Contacts"), exception.getMessage());
    }

    @Test
    void testEditorFailsWithGuidanceWhenSampleMappingIsAbsent() {
        doReturn(Map.of("contactMapping", "{\"Contacts\": {\"applicationFields\": {\"fields\": []}}}"))
            .when(workflowTestConfigurationService)
            .getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Contacts", editorContext()));

        assertTrue(exception.getMessage()
            .contains("sampleMapping"), exception.getMessage());
    }

    @Test
    void testEditorFailsWithGuidanceWhenThereIsNoTestValue() {
        when(workflowTestConfigurationService.getWorkflowTestConfigurationInputs(WORKFLOW_ID, ENVIRONMENT_ID))
            .thenReturn(Map.of());

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> resolver.resolve("Contacts", editorContext()));

        assertTrue(exception.getMessage()
            .contains("sampleMapping"), exception.getMessage());
    }

    private static ActionContextAware runtimeContext(PlatformType platformType) {
        ActionContextAware context = mock(ActionContextAware.class);

        when(context.getWorkflowId()).thenReturn(WORKFLOW_ID);
        when(context.isEditorEnvironment()).thenReturn(false);
        when(context.getPlatformType()).thenReturn(platformType);
        when(context.getJobPrincipalId()).thenReturn(INSTANCE_ID);
        when(context.getEnvironmentId()).thenReturn(ENVIRONMENT_ID);

        return context;
    }

    private static ActionContextAware editorContext() {
        ActionContextAware context = mock(ActionContextAware.class);

        when(context.getWorkflowId()).thenReturn(WORKFLOW_ID);
        when(context.isEditorEnvironment()).thenReturn(true);
        when(context.getPlatformType()).thenReturn(PlatformType.EMBEDDED);
        when(context.getJobPrincipalId()).thenReturn(null);
        when(context.getEnvironmentId()).thenReturn(ENVIRONMENT_ID);

        return context;
    }
}

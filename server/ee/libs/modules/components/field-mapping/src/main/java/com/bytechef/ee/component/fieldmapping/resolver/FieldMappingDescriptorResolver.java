/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.component.fieldmapping.resolver;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.component.definition.ActionContext;
import com.bytechef.ee.component.fieldmapping.mapper.FieldMappingDescriptor;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.platform.component.definition.ActionContextAware;
import com.bytechef.platform.configuration.domain.WorkflowInput;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.constant.PlatformType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds the {@code field_mapping} workflow input declared with a given object name and loads its descriptor. At runtime
 * that is the connected user's saved value on {@code IntegrationInstanceWorkflow.inputs}; under the editor's Test
 * button there is no connected user, so the input's design-time test value must carry a {@code sampleMapping}.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public class FieldMappingDescriptorResolver {

    private static final int DEVELOPMENT_ENVIRONMENT_ID = 0;
    private static final String MAP_OBJECT_FIELDS = "mapObjectFields";
    private static final String SAMPLE_MAPPING = "sampleMapping";

    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService;
    private final WorkflowService workflowService;
    private final WorkflowTestConfigurationService workflowTestConfigurationService;

    @SuppressFBWarnings("EI2")
    public FieldMappingDescriptorResolver(
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService, WorkflowService workflowService,
        WorkflowTestConfigurationService workflowTestConfigurationService) {

        this.integrationInstanceWorkflowService = integrationInstanceWorkflowService;
        this.workflowService = workflowService;
        this.workflowTestConfigurationService = workflowTestConfigurationService;
    }

    public FieldMappingDescriptor resolve(String objectName, ActionContext context) {
        ActionContextAware contextAware = (ActionContextAware) context;

        String workflowId = contextAware.getWorkflowId();

        if (workflowId == null) {
            throw new IllegalStateException("Field mapping actions require a workflow execution context");
        }

        Workflow workflow = workflowService.getWorkflow(workflowId);

        WorkflowInput workflowInput = findInput(objectName, WorkflowInput.of(workflow));

        Object rawDescriptor = contextAware.isEditorEnvironment()
            ? readSampleMapping(objectName, workflowInput, workflowId, contextAware)
            : readSavedMapping(workflowInput, workflowId, contextAware);

        return FieldMappingDescriptor.of(toMap(rawDescriptor));
    }

    private static WorkflowInput findInput(String objectName, List<WorkflowInput> workflowInputs) {
        return workflowInputs.stream()
            .filter(workflowInput -> Objects.equals(objectName, workflowInput.getObjectName()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No field mapping input declares object name '%s'; declared object names: %s".formatted(
                    objectName,
                    workflowInputs.stream()
                        .map(WorkflowInput::getObjectName)
                        .filter(Objects::nonNull)
                        .toList())));
    }

    private Object readSavedMapping(WorkflowInput workflowInput, String workflowId, ActionContextAware contextAware) {
        if (contextAware.getPlatformType() != PlatformType.EMBEDDED) {
            throw new IllegalStateException(
                "The field mapping component is available in embedded workflows only");
        }

        Long integrationInstanceId = contextAware.getJobPrincipalId();

        if (integrationInstanceId == null) {
            throw new IllegalStateException("Field mapping actions require an integration instance");
        }

        Object saved = integrationInstanceWorkflowService
            .fetchIntegrationInstanceWorkflow(integrationInstanceId, workflowId)
            .map(integrationInstanceWorkflow -> integrationInstanceWorkflow.getInputs()
                .get(workflowInput.getName()))
            .orElse(null);

        if (saved == null) {
            throw new IllegalArgumentException(
                "The connected user has not completed the '%s' field mapping".formatted(inputLabel(workflowInput)));
        }

        return saved;
    }

    private Object readSampleMapping(
        String objectName, WorkflowInput workflowInput, String workflowId, ActionContextAware contextAware) {

        Long environmentId = contextAware.getEnvironmentId();

        Map<String, ?> testInputs = workflowTestConfigurationService.getWorkflowTestConfigurationInputs(
            workflowId, environmentId == null ? DEVELOPMENT_ENVIRONMENT_ID : environmentId);

        Object testValue = testInputs.get(workflowInput.getName());

        if (testValue == null) {
            throw new IllegalArgumentException(
                "Add a sampleMapping to the '%s' input's test value to run this action from the editor".formatted(
                    inputLabel(workflowInput)));
        }

        Map<String, ?> root = toMap(testValue);

        if (root.get(MAP_OBJECT_FIELDS) instanceof Map<?, ?> envelope) {
            root = castMap(envelope);
        }

        if (!root.containsKey(objectName)) {
            throw new IllegalArgumentException(
                "The test value for the '%s' input has no entry for object name '%s'; it contains: %s".formatted(
                    inputLabel(workflowInput), objectName, root.keySet()));
        }

        Object entry = root.get(objectName);

        Object sampleMapping = entry instanceof Map<?, ?> entryMap ? entryMap.get(SAMPLE_MAPPING) : null;

        if (sampleMapping == null) {
            throw new IllegalArgumentException(
                "Add a sampleMapping to the '%s' input's test value to run this action from the editor".formatted(
                    inputLabel(workflowInput)));
        }

        return sampleMapping;
    }

    private static String inputLabel(WorkflowInput workflowInput) {
        return workflowInput.getLabel() == null ? workflowInput.getName() : workflowInput.getLabel();
    }

    private static Map<String, ?> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castMap(map);
        }

        if (value instanceof String string) {
            return JsonUtils.readMap(string);
        }

        throw new IllegalArgumentException(
            "Expected a field mapping object, got: " + value.getClass()
                .getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castMap(Map<?, ?> map) {
        return (Map<String, ?>) map;
    }
}

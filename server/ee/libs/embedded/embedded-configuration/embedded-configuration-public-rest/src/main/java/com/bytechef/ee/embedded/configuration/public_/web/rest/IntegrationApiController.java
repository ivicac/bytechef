/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.public_.web.rest;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfigurationWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationWorkflow;
import com.bytechef.ee.embedded.configuration.dto.ConnectedUserIntegrationDTO;
import com.bytechef.ee.embedded.configuration.dto.IntegrationInstanceConfigurationWorkflowDTO;
import com.bytechef.ee.embedded.configuration.exception.EmbeddedIntegrationNotVisibleException;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserIntegrationFacade;
import com.bytechef.ee.embedded.configuration.public_.web.rest.converter.CaseInsensitiveEnumPropertyEditorSupport;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.ComponentInputReferenceModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.ComponentPropertyGroupModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.ComponentPropertyModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.EnvironmentModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.InputModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.InputTypeModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationBasicModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationInstanceModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationInstanceWorkflowModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.IntegrationWorkflowModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.McpIntegrationInstanceToolModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.McpToolModel;
import com.bytechef.ee.embedded.configuration.public_.web.rest.model.OptionModel;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationWorkflowService;
import com.bytechef.ee.embedded.mcp.domain.McpIntegrationInstanceConfiguration;
import com.bytechef.ee.embedded.mcp.domain.McpIntegrationInstanceConfigurationWorkflow;
import com.bytechef.ee.embedded.mcp.service.McpIntegrationInstanceConfigurationService;
import com.bytechef.ee.embedded.mcp.service.McpIntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.mcp.service.McpIntegrationInstanceToolService;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.component.domain.ClusterElementDefinition;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.domain.DateProperty;
import com.bytechef.platform.component.domain.DateTimeProperty;
import com.bytechef.platform.component.domain.IntegerProperty;
import com.bytechef.platform.component.domain.NumberProperty;
import com.bytechef.platform.component.domain.ObjectProperty;
import com.bytechef.platform.component.domain.Option;
import com.bytechef.platform.component.domain.OptionsDataSourceAware;
import com.bytechef.platform.component.domain.Property;
import com.bytechef.platform.component.domain.PropertyGroup;
import com.bytechef.platform.component.domain.StringProperty;
import com.bytechef.platform.component.domain.ValueProperty;
import com.bytechef.platform.component.service.ClusterElementDefinitionService;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.mcp.domain.McpComponent;
import com.bytechef.platform.mcp.domain.McpServer;
import com.bytechef.platform.mcp.domain.McpTool;
import com.bytechef.platform.mcp.service.McpComponentService;
import com.bytechef.platform.mcp.service.McpServerService;
import com.bytechef.platform.mcp.service.McpToolService;
import com.bytechef.platform.security.util.SecurityUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@RestController("com.bytechef.ee.embedded.configuration.public_.web.rest.IntegrationApiController")
@RequestMapping("${openapi.openAPIDefinition.base-path.embedded:}/v1")
@ConditionalOnCoordinator
@ConditionalOnEEVersion
public class IntegrationApiController implements IntegrationApi {

    private static final Logger log = LoggerFactory.getLogger(IntegrationApiController.class);

    private final ClusterElementDefinitionService clusterElementDefinitionService;
    private final ComponentDefinitionService componentDefinitionService;
    private final ConversionService conversionService;
    private final ConnectedUserIntegrationFacade connectedUserIntegrationFacade;
    private final EnvironmentService environmentService;
    private final IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService;
    private final IntegrationWorkflowService integrationWorkflowService;
    private final McpComponentService mcpComponentService;
    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService;
    private final McpIntegrationInstanceToolService mcpIntegrationInstanceToolService;
    private final McpIntegrationInstanceConfigurationService mcpIntegrationInstanceConfigurationService;
    private final McpIntegrationInstanceConfigurationWorkflowService mcpIntegrationInstanceConfigurationWorkflowService;
    private final McpServerService mcpServerService;
    private final McpToolService mcpToolService;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public IntegrationApiController(
        ClusterElementDefinitionService clusterElementDefinitionService,
        ComponentDefinitionService componentDefinitionService, ConversionService conversionService,
        ConnectedUserIntegrationFacade connectedUserIntegrationFacade, EnvironmentService environmentService,
        IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService,
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService,
        IntegrationWorkflowService integrationWorkflowService, McpComponentService mcpComponentService,
        McpIntegrationInstanceToolService mcpIntegrationInstanceToolService,
        McpIntegrationInstanceConfigurationService mcpIntegrationInstanceConfigurationService,
        McpIntegrationInstanceConfigurationWorkflowService mcpIntegrationInstanceConfigurationWorkflowService,
        McpServerService mcpServerService, McpToolService mcpToolService, WorkflowService workflowService) {

        this.clusterElementDefinitionService = clusterElementDefinitionService;
        this.componentDefinitionService = componentDefinitionService;
        this.conversionService = conversionService;
        this.connectedUserIntegrationFacade = connectedUserIntegrationFacade;
        this.environmentService = environmentService;
        this.integrationInstanceConfigurationWorkflowService = integrationInstanceConfigurationWorkflowService;
        this.integrationInstanceWorkflowService = integrationInstanceWorkflowService;
        this.integrationWorkflowService = integrationWorkflowService;
        this.mcpComponentService = mcpComponentService;
        this.mcpIntegrationInstanceToolService = mcpIntegrationInstanceToolService;
        this.mcpIntegrationInstanceConfigurationService = mcpIntegrationInstanceConfigurationService;
        this.mcpIntegrationInstanceConfigurationWorkflowService = mcpIntegrationInstanceConfigurationWorkflowService;
        this.mcpServerService = mcpServerService;
        this.mcpToolService = mcpToolService;
        this.workflowService = workflowService;
    }

    @CrossOrigin
    @Override
    public ResponseEntity<IntegrationModel> getFrontendIntegration(Long id, EnvironmentModel xEnvironment) {
        String externalId = SecurityUtils.fetchCurrentUserLogin()
            .orElseThrow(() -> new RuntimeException("User not authenticated"));

        ConnectedUserIntegrationDTO connectedUserIntegrationDTO;

        try {
            connectedUserIntegrationDTO = connectedUserIntegrationFacade.getConnectedUserIntegration(
                externalId, id, true, getEnvironment(xEnvironment));
        } catch (EmbeddedIntegrationNotVisibleException exception) {
            return ResponseEntity.notFound()
                .build();
        }

        IntegrationModel integrationModel = conversionService.convert(
            connectedUserIntegrationDTO, IntegrationModel.class);

        populateMcpData(connectedUserIntegrationDTO, integrationModel);
        filterDisabledWorkflows(connectedUserIntegrationDTO, integrationModel);

        return ResponseEntity.ok(integrationModel);
    }

    @CrossOrigin
    @Override
    public ResponseEntity<List<IntegrationBasicModel>> getFrontendIntegrations(EnvironmentModel xEnvironment) {
        String externalId = SecurityUtils.fetchCurrentUserLogin()
            .orElseThrow(() -> new RuntimeException("User not authenticated"));

        return ResponseEntity.ok(
            connectedUserIntegrationFacade
                .getConnectedUserIntegrations(externalId, true, getEnvironment(xEnvironment))
                .stream()
                .map(integrationDTO -> conversionService.convert(integrationDTO, IntegrationBasicModel.class))
                .toList());
    }

    @Override
    public ResponseEntity<IntegrationModel> getIntegration(
        String externalUserId, Long id, EnvironmentModel xEnvironment) {

        ConnectedUserIntegrationDTO connectedUserIntegrationDTO;

        try {
            connectedUserIntegrationDTO = connectedUserIntegrationFacade.getConnectedUserIntegration(
                externalUserId, id, true, getEnvironment(xEnvironment));
        } catch (EmbeddedIntegrationNotVisibleException exception) {
            return ResponseEntity.notFound()
                .build();
        }

        IntegrationModel integrationModel = conversionService.convert(
            connectedUserIntegrationDTO, IntegrationModel.class);

        populateMcpData(connectedUserIntegrationDTO, integrationModel);
        filterDisabledWorkflows(connectedUserIntegrationDTO, integrationModel);

        return ResponseEntity.ok(integrationModel);
    }

    @Override
    public ResponseEntity<List<IntegrationBasicModel>> getIntegrations(
        String externalUserId, EnvironmentModel xEnvironment) {

        return ResponseEntity.ok(
            connectedUserIntegrationFacade
                .getConnectedUserIntegrations(externalUserId, true, getEnvironment(xEnvironment))
                .stream()
                .map(integrationInstanceConfigurationDTO -> conversionService.convert(
                    integrationInstanceConfigurationDTO, IntegrationBasicModel.class))
                .toList());
    }

    @InitBinder
    public void initBinder(WebDataBinder dataBinder) {
        dataBinder.registerCustomEditor(EnvironmentModel.class, new CaseInsensitiveEnumPropertyEditorSupport());
    }

    private void filterDisabledWorkflows(
        ConnectedUserIntegrationDTO connectedUserIntegrationDTO, IntegrationModel integrationModel) {

        if (integrationModel == null || integrationModel.getWorkflows() == null) {
            return;
        }

        if (connectedUserIntegrationDTO.integrationInstanceConfiguration() == null ||
            connectedUserIntegrationDTO.integrationInstanceConfiguration()
                .integrationInstanceConfigurationWorkflows() == null) {

            return;
        }

        Set<String> mcpWorkflowUuids = integrationModel.getMcpWorkflows() == null
            ? Set.of()
            : integrationModel.getMcpWorkflows()
                .stream()
                .map(IntegrationWorkflowModel::getWorkflowUuid)
                .collect(Collectors.toSet());

        Set<String> enabledWorkflowUuids = connectedUserIntegrationDTO.integrationInstanceConfiguration()
            .integrationInstanceConfigurationWorkflows()
            .stream()
            .filter(IntegrationInstanceConfigurationWorkflowDTO::enabled)
            .map(IntegrationInstanceConfigurationWorkflowDTO::workflowUuid)
            .filter(workflowUuid -> !mcpWorkflowUuids.contains(workflowUuid))
            .collect(Collectors.toSet());

        integrationModel.setWorkflows(
            integrationModel.getWorkflows()
                .stream()
                .filter(workflowModel -> enabledWorkflowUuids.contains(workflowModel.getWorkflowUuid()))
                .toList());
    }

    private Environment getEnvironment(EnvironmentModel xEnvironment) {
        return environmentService.getEnvironment(xEnvironment == null ? null : xEnvironment.name());
    }

    private void populateMcpData(
        ConnectedUserIntegrationDTO connectedUserIntegrationDTO, IntegrationModel integrationModel) {

        if (integrationModel == null) {
            return;
        }

        String componentName =
            connectedUserIntegrationDTO.integrationInstanceConfiguration()
                .integration()
                .componentName();

        List<McpToolModel> mcpToolModels = mcpComponentService.getMcpComponentsByComponentName(componentName)
            .stream()
            .filter(mcpComponent -> isEmbeddedMcpServerEnabled(mcpComponent.getMcpServerId()))
            .flatMap(mcpComponent -> mcpToolService.getMcpComponentMcpTools(mcpComponent.getId())
                .stream()
                .map(mcpTool -> {
                    McpToolModel mcpToolModel = conversionService.convert(mcpTool, McpToolModel.class);

                    mcpToolModel.setName(mcpTool.getName());

                    ClusterElementDefinition clusterElementDefinition =
                        clusterElementDefinitionService.getClusterElementDefinition(
                            mcpComponent.getComponentName(), mcpComponent.getComponentVersion(), mcpTool.getName());

                    mcpToolModel.setDescription(clusterElementDefinition.getDescription());

                    return mcpToolModel;
                }))
            .toList();

        integrationModel.setMcpTools(mcpToolModels);

        long integrationId = connectedUserIntegrationDTO.integrationInstanceConfiguration()
            .integrationId();

        List<IntegrationWorkflowModel> mcpWorkflowModels =
            mcpIntegrationInstanceConfigurationService
                .getMcpIntegrationInstanceConfigurationsByIntegrationId(integrationId)
                .stream()
                .filter(mcpIntegrationInstanceConfiguration -> isEmbeddedMcpServerEnabled(
                    mcpIntegrationInstanceConfiguration.getMcpServerId()))
                .map(McpIntegrationInstanceConfiguration::getId)
                .flatMap(mcpIntegrationInstanceConfigurationId -> mcpIntegrationInstanceConfigurationWorkflowService
                    .getMcpIntegrationInstanceConfigurationMcpIntegrationInstanceConfigurationWorkflows(
                        mcpIntegrationInstanceConfigurationId)
                    .stream())
                .map(mcpIntegrationInstanceConfigurationWorkflow -> {
                    IntegrationInstanceConfigurationWorkflow integrationInstanceConfigurationWorkflow =
                        integrationInstanceConfigurationWorkflowService.getIntegrationInstanceConfigurationWorkflow(
                            mcpIntegrationInstanceConfigurationWorkflow
                                .getIntegrationInstanceConfigurationWorkflowId());

                    String workflowId = integrationInstanceConfigurationWorkflow.getWorkflowId();

                    Workflow workflow = workflowService.getWorkflow(workflowId);

                    IntegrationWorkflow integrationWorkflow =
                        integrationWorkflowService.getWorkflowIntegrationWorkflow(workflowId);

                    List<InputModel> inputModels = workflow.getInputs()
                        .stream()
                        .map(this::toInputModel)
                        .toList();

                    return new IntegrationWorkflowModel()
                        .description(workflow.getDescription())
                        .inputs(inputModels)
                        .label(workflow.getLabel())
                        .workflowUuid(integrationWorkflow.getUuidAsString());
                })
                .filter(model -> model.getWorkflowUuid() != null)
                .toList();

        integrationModel.setMcpWorkflows(mcpWorkflowModels);

        populateMcpInstanceData(integrationModel);
    }

    private void populateMcpInstanceData(IntegrationModel integrationModel) {
        if (integrationModel.getIntegrationInstances() == null) {
            return;
        }

        for (IntegrationInstanceModel integrationInstanceModel : integrationModel.getIntegrationInstances()) {
            Long integrationInstanceId = integrationInstanceModel.getId();

            if (integrationInstanceId == null) {
                continue;
            }

            List<McpIntegrationInstanceToolModel> mcpInstanceToolModels =
                mcpIntegrationInstanceToolService.getMcpIntegrationInstanceTools(integrationInstanceId)
                    .stream()
                    .filter(mcpInstanceTool -> {
                        McpTool mcpTool = mcpToolService.fetchMcpTool(mcpInstanceTool.getMcpToolId())
                            .orElse(null);

                        if (mcpTool == null) {
                            return false;
                        }

                        McpComponent mcpComponent =
                            mcpComponentService.getMcpComponent(mcpTool.getMcpComponentId());

                        return isEmbeddedMcpServerEnabled(mcpComponent.getMcpServerId());
                    })
                    .map(mcpTool -> conversionService.convert(mcpTool, McpIntegrationInstanceToolModel.class))
                    .toList();

            integrationInstanceModel.setMcpTools(mcpInstanceToolModels);

            List<IntegrationInstanceWorkflowModel> mcpInstanceWorkflowModels =
                integrationInstanceWorkflowService.getIntegrationInstanceWorkflows(integrationInstanceId)
                    .stream()
                    .map(integrationInstanceWorkflow -> {
                        McpIntegrationInstanceConfigurationWorkflow mcpIntegrationInstanceConfigurationWorkflow =
                            mcpIntegrationInstanceConfigurationWorkflowService
                                .fetchMcpIntegrationInstanceConfigurationWorkflowByIntegrationInstanceConfigurationWorkflowId(
                                    integrationInstanceWorkflow.getIntegrationInstanceConfigurationWorkflowId())
                                .orElse(null);

                        if (mcpIntegrationInstanceConfigurationWorkflow == null) {
                            return null;
                        }

                        McpIntegrationInstanceConfiguration mcpIntegrationInstanceConfiguration =
                            mcpIntegrationInstanceConfigurationService
                                .fetchMcpIntegrationInstanceConfiguration(mcpIntegrationInstanceConfigurationWorkflow
                                    .getMcpIntegrationInstanceConfigurationId())
                                .orElse(null);

                        if (mcpIntegrationInstanceConfiguration == null
                            || !isEmbeddedMcpServerEnabled(mcpIntegrationInstanceConfiguration.getMcpServerId())) {
                            return null;
                        }

                        IntegrationInstanceWorkflowModel model = conversionService.convert(
                            integrationInstanceWorkflow, IntegrationInstanceWorkflowModel.class);

                        IntegrationInstanceConfigurationWorkflow configurationWorkflow =
                            integrationInstanceConfigurationWorkflowService
                                .getIntegrationInstanceConfigurationWorkflow(
                                    integrationInstanceWorkflow.getIntegrationInstanceConfigurationWorkflowId());

                        IntegrationWorkflow integrationWorkflow =
                            integrationWorkflowService.getWorkflowIntegrationWorkflow(
                                configurationWorkflow.getWorkflowId());

                        model.setWorkflowUuid(integrationWorkflow.getUuidAsString());

                        return model;
                    })
                    .filter(model -> model != null)
                    .toList();

            integrationInstanceModel.setMcpWorkflows(mcpInstanceWorkflowModels);
        }
    }

    private boolean isEmbeddedMcpServerEnabled(long mcpServerId) {
        McpServer mcpServer = mcpServerService.getMcpServer(mcpServerId);

        return mcpServer.getType() == PlatformType.EMBEDDED && mcpServer.isEnabled();
    }

    private InputModel toInputModel(Workflow.Input input) {
        InputModel inputModel = new InputModel()
            .label(input.label())
            .name(input.name())
            .required(input.required())
            .type(toInputTypeModel(input.type()));

        Workflow.ComponentInputReference componentReference = input.componentReference();

        if (componentReference != null) {
            ComponentDefinition componentDefinition = componentDefinitionService.getComponentDefinition(
                componentReference.componentName(), componentReference.componentVersion());

            inputModel.componentReference(
                new ComponentInputReferenceModel()
                    .componentName(componentReference.componentName())
                    .componentVersion(componentReference.componentVersion())
                    .groupName(componentReference.groupName())
                    .group(toComponentPropertyGroupModel(componentDefinition, componentReference.groupName())));
        }

        return inputModel;
    }

    private static ComponentPropertyGroupModel toComponentPropertyGroupModel(
        ComponentDefinition componentDefinition, String groupName) {

        for (PropertyGroup propertyGroup : componentDefinition.getInputs()) {
            if (groupName.equals(propertyGroup.getName())) {
                List<ComponentPropertyModel> componentPropertyModels = propertyGroup.getProperties()
                    .stream()
                    .map(IntegrationApiController::toComponentPropertyModel)
                    .toList();

                return new ComponentPropertyGroupModel()
                    .name(propertyGroup.getName())
                    .label(propertyGroup.getLabel())
                    .properties(componentPropertyModels);
            }
        }

        return null;
    }

    private static ComponentPropertyModel toComponentPropertyModel(Property property) {
        var propertyType = property.getType();

        ComponentPropertyModel componentPropertyModel = new ComponentPropertyModel()
            .name(property.getName())
            .required(property.getRequired())
            .type(toInputTypeModel(propertyType == null ? null : propertyType.name()));

        if (property instanceof ValueProperty<?> valueProperty) {
            componentPropertyModel.label(valueProperty.getLabel());

            if (valueProperty.getControlType() != null) {
                componentPropertyModel.controlType(valueProperty.getControlType()
                    .name());
            }
        }

        List<Option> options = getStaticOptions(property);

        if (options != null) {
            componentPropertyModel.options(options.stream()
                .map(option -> new OptionModel()
                    .label(option.getLabel())
                    .value(String.valueOf(option.getValue())))
                .toList());
        }

        if (property instanceof OptionsDataSourceAware optionsDataSourceAware &&
            optionsDataSourceAware.getOptionsDataSource() != null) {

            componentPropertyModel.dynamicOptions(true);
            componentPropertyModel.optionsLookupDependsOn(
                optionsDataSourceAware.getOptionsDataSource()
                    .getOptionsLookupDependsOn());
        }

        return componentPropertyModel;
    }

    private static List<Option> getStaticOptions(Property property) {
        if (property instanceof StringProperty stringProperty) {
            return stringProperty.getOptions();
        }

        if (property instanceof IntegerProperty integerProperty) {
            return integerProperty.getOptions();
        }

        if (property instanceof NumberProperty numberProperty) {
            return numberProperty.getOptions();
        }

        if (property instanceof ObjectProperty objectProperty) {
            return objectProperty.getOptions();
        }

        if (property instanceof DateProperty dateProperty) {
            return dateProperty.getOptions();
        }

        if (property instanceof DateTimeProperty dateTimeProperty) {
            return dateTimeProperty.getOptions();
        }

        return null;
    }

    private static InputTypeModel toInputTypeModel(String type) {
        if (type == null) {
            return null;
        }

        try {
            return InputTypeModel.fromValue(type);
        } catch (IllegalArgumentException e) {
            if (log.isWarnEnabled()) {
                log.warn(
                    "Unmapped input/property type '{}'; emitting null type. The field will fall back to a plain text " +
                        "input in the SDK.",
                    type);
            }

            return null;
        }
    }
}

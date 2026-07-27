/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.webhook.public_.web.rest;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.facade.AutomationWorkflowProjectFacade;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationWorkflowService;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.ee.embedded.webhook.public_.web.rest.converter.CaseInsensitiveEnumPropertyEditorSupport;
import com.bytechef.ee.embedded.webhook.public_.web.rest.model.EnvironmentModel;
import com.bytechef.file.storage.token.FileEntryTokens;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.file.storage.TempFileStorage;
import com.bytechef.platform.security.util.SecurityUtils;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutor;
import com.bytechef.platform.webhook.rest.AbstractWebhookTriggerController;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
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
@RestController
@RequestMapping("${openapi.openAPIDefinition.base-path.embedded:}/v1")
@ConditionalOnCoordinator
@ConditionalOnEEVersion
public class RequestTriggerApiController extends AbstractWebhookTriggerController implements RequestTriggerApi {

    private final AutomationWorkflowProjectFacade automationWorkflowProjectFacade;
    private final ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;
    private final ConnectedUserService connectedUserService;
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;
    private final IntegrationInstanceService integrationInstanceService;
    private final IntegrationWorkflowService integrationWorkflowService;
    private final ProjectWorkflowService projectWorkflowService;
    private final WebhookWorkflowExecutor webhookWorkflowExecutor;
    private final WorkflowService workflowService;
    private final EnvironmentService environmentService;

    @SuppressFBWarnings("EI")
    public RequestTriggerApiController(
        ApplicationProperties applicationProperties, AutomationWorkflowProjectFacade automationWorkflowProjectFacade,
        ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade,
        ConnectedUserService connectedUserService, EnvironmentService environmentService,
        FileEntryTokens fileEntryTokens, HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse, TempFileStorage tempFileStorage,
        WebhookWorkflowExecutor webhookWorkflowExecutor, IntegrationInstanceService integrationInstanceService,
        IntegrationWorkflowService integrationWorkflowService, ProjectWorkflowService projectWorkflowService,
        WorkflowService workflowService) {

        super(fileEntryTokens, applicationProperties.getPublicUrl(), tempFileStorage, webhookWorkflowExecutor);

        this.automationWorkflowProjectFacade = automationWorkflowProjectFacade;
        this.connectedUserCodeWorkflowReferenceFacade = connectedUserCodeWorkflowReferenceFacade;
        this.connectedUserService = connectedUserService;
        this.httpServletRequest = httpServletRequest;
        this.httpServletResponse = httpServletResponse;
        this.integrationInstanceService = integrationInstanceService;
        this.integrationWorkflowService = integrationWorkflowService;
        this.projectWorkflowService = projectWorkflowService;
        this.webhookWorkflowExecutor = webhookWorkflowExecutor;
        this.workflowService = workflowService;
        this.environmentService = environmentService;
    }

    @CrossOrigin
    @Override
    public ResponseEntity<Object> executeWorkflow(String workflowUuid, EnvironmentModel xEnvironment) {
        Environment environment = environmentService.getEnvironment(xEnvironment == null ? null : xEnvironment.name());

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(
            OptionalUtils.get(SecurityUtils.fetchCurrentUserLogin(), "User not found"), environment);

        Optional<String> integrationWorkflowId = integrationWorkflowService.fetchLastWorkflowId(
            workflowUuid, environment);

        if (integrationWorkflowId.isPresent()) {
            return executeIntegrationWorkflow(connectedUser, workflowUuid, integrationWorkflowId.get(), environment);
        }

        return executeAutomationBridgeWorkflow(connectedUser, workflowUuid, environment);
    }

    private ResponseEntity<Object> executeIntegrationWorkflow(
        ConnectedUser connectedUser, String workflowUuid, String workflowId, Environment environment) {

        IntegrationInstance integrationInstance = integrationInstanceService.getIntegrationInstance(
            connectedUser.getId(), workflowId, environment);

        Workflow workflow = workflowService.getWorkflow(workflowId);

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.EMBEDDED, integrationInstance.getId(), workflowUuid, findRequestTriggerName(workflow));

        return dispatch(workflowExecutionId);
    }

    /**
     * The automation-bridge branch: workflowUuid is not an integration workflow, so try it as a published catalog
     * ProjectWorkflow uuid. No published catalog workflow with this uuid, and no enabled reference to it, both resolve
     * to the SAME 404 an unknown workflowUuid always returned -- an existence leak would tell a caller something about
     * the catalog they otherwise couldn't see.
     */
    private ResponseEntity<Object> executeAutomationBridgeWorkflow(
        ConnectedUser connectedUser, String workflowUuid, Environment environment) {

        boolean isPublishedCatalogWorkflow = automationWorkflowProjectFacade.getPublishedProjects()
            .stream()
            .flatMap(project -> CollectionUtils.stream(project.workflowTemplates()))
            .anyMatch(workflowTemplate -> Objects.equals(workflowTemplate.workflowUuid(), workflowUuid));

        if (!isPublishedCatalogWorkflow) {
            return ResponseEntity.notFound()
                .build();
        }

        ConnectedUserProjectWorkflow reference;

        try {
            reference = connectedUserCodeWorkflowReferenceFacade.getOrCreateReference(
                connectedUser.getExternalId(), workflowUuid, environment);
        } catch (MissingConnectionException missingConnectionException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("missingConnectionComponentName", missingConnectionException.getComponentName()));
        }

        if (!reference.isEnabled() || reference.isDangling()) {
            return ResponseEntity.notFound()
                .build();
        }

        Long projectDeploymentId = reference.getProjectDeploymentId();

        if (projectDeploymentId == null) {
            return ResponseEntity.notFound()
                .build();
        }

        String catalogWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(workflowUuid);
        Workflow workflow = workflowService.getWorkflow(catalogWorkflowId);

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, projectDeploymentId, workflowUuid,
            findRequestTriggerName(workflow));

        return dispatch(workflowExecutionId);
    }

    private ResponseEntity<Object> dispatch(WorkflowExecutionId workflowExecutionId) {
        if (webhookWorkflowExecutor.isWorkflowDisabled(workflowExecutionId)) {
            return ResponseEntity.ok()
                .build();
        }

        try {
            return doProcessTrigger(workflowExecutionId, null, httpServletRequest, httpServletResponse)
                .join();
        } catch (IOException | ServletException e) {
            throw new RuntimeException(e);
        }
    }

    @InitBinder
    public void initBinder(WebDataBinder dataBinder) {
        dataBinder.registerCustomEditor(EnvironmentModel.class, new CaseInsensitiveEnumPropertyEditorSupport());
    }

    private static String findRequestTriggerName(Workflow workflow) {
        return WorkflowTrigger.of(workflow)
            .stream()
            .map(workflowTrigger -> {
                WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

                if (Objects.equals(workflowNodeType.name(), "request")) {
                    return workflowTrigger.getName();
                }

                return null;
            })
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.webhook.public_.web.rest;

import static com.bytechef.platform.component.definition.AppEventComponentDefinition.APP_EVENT;
import static com.bytechef.platform.component.definition.AppEventComponentDefinition.NEW_EVENT;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.commons.util.OptionalUtils;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstance;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceConfigurationWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationInstanceWorkflow;
import com.bytechef.ee.embedded.configuration.domain.IntegrationWorkflow;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserCodeWorkflowReferenceFacade;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceConfigurationWorkflowService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceService;
import com.bytechef.ee.embedded.configuration.service.IntegrationInstanceWorkflowService;
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
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
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
public class AppEventTriggerApiController extends AbstractWebhookTriggerController implements AppEventTriggerApi {

    private static final Logger log = LoggerFactory.getLogger(AppEventTriggerApiController.class);

    private final ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade;
    private final ConnectedUserService connectedUserService;
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;
    private final IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService;
    private final IntegrationInstanceService integrationInstanceService;
    private final IntegrationInstanceWorkflowService integrationInstanceWorkflowService;
    private final IntegrationWorkflowService integrationWorkflowService;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectWorkflowService projectWorkflowService;
    private final WebhookWorkflowExecutor webhookWorkflowExecutor;
    private final WorkflowService workflowService;
    private final EnvironmentService environmentService;

    @SuppressFBWarnings("EI")
    public AppEventTriggerApiController(
        ApplicationProperties applicationProperties,
        ConnectedUserCodeWorkflowReferenceFacade connectedUserCodeWorkflowReferenceFacade,
        ConnectedUserService connectedUserService, EnvironmentService environmentService,
        FileEntryTokens fileEntryTokens, HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse,
        IntegrationInstanceConfigurationWorkflowService integrationInstanceConfigurationWorkflowService,
        IntegrationInstanceService integrationInstanceService,
        IntegrationInstanceWorkflowService integrationInstanceWorkflowService,
        IntegrationWorkflowService integrationWorkflowService, ProjectDeploymentService projectDeploymentService,
        ProjectWorkflowService projectWorkflowService, TempFileStorage tempFileStorage,
        WebhookWorkflowExecutor webhookWorkflowExecutor, WorkflowService workflowService) {

        super(fileEntryTokens, applicationProperties.getPublicUrl(), tempFileStorage, webhookWorkflowExecutor);

        this.connectedUserCodeWorkflowReferenceFacade = connectedUserCodeWorkflowReferenceFacade;
        this.connectedUserService = connectedUserService;
        this.environmentService = environmentService;
        this.httpServletRequest = httpServletRequest;
        this.httpServletResponse = httpServletResponse;
        this.integrationInstanceConfigurationWorkflowService = integrationInstanceConfigurationWorkflowService;
        this.integrationInstanceService = integrationInstanceService;
        this.integrationInstanceWorkflowService = integrationInstanceWorkflowService;
        this.integrationWorkflowService = integrationWorkflowService;
        this.projectDeploymentService = projectDeploymentService;
        this.projectWorkflowService = projectWorkflowService;
        this.webhookWorkflowExecutor = webhookWorkflowExecutor;
        this.workflowService = workflowService;
    }

    public ResponseEntity<Void> executeWorkflows(EnvironmentModel xEnvironment) {
        Environment environment = environmentService.getEnvironment(xEnvironment == null ? null : xEnvironment.name());

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(
            OptionalUtils.get(SecurityUtils.fetchCurrentUserLogin(), "User not found"), environment);

        List<IntegrationInstance> integrationInstances =
            integrationInstanceService.getConnectedUserIntegrationInstances(connectedUser.getId(), true);

        for (IntegrationInstance integrationInstance : integrationInstances) {
            List<IntegrationInstanceWorkflow> integrationInstanceWorkflows =
                integrationInstanceWorkflowService.getIntegrationInstanceWorkflows(integrationInstance.getId());

            List<String> workflowIds = integrationInstanceWorkflows.stream()
                .filter(IntegrationInstanceWorkflow::isEnabled)
                .map(integrationInstanceWorkflow -> integrationInstanceConfigurationWorkflowService
                    .getIntegrationInstanceConfigurationWorkflow(
                        integrationInstanceWorkflow.getIntegrationInstanceConfigurationWorkflowId()))
                .map(IntegrationInstanceConfigurationWorkflow::getWorkflowId)
                .toList();

            for (String workflowId : workflowIds) {
                Workflow workflow = workflowService.getWorkflow(workflowId);

                String appEventTriggerName = findAppEventTriggerName(workflow);

                if (appEventTriggerName == null) {
                    continue;
                }

                IntegrationWorkflow integrationWorkflow = integrationWorkflowService.getWorkflowIntegrationWorkflow(
                    workflowId);

                WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
                    PlatformType.EMBEDDED, integrationInstance.getId(), integrationWorkflow.getUuidAsString(),
                    appEventTriggerName);

                try {
                    doProcessTrigger(workflowExecutionId, null, httpServletRequest, httpServletResponse)
                        .join();
                } catch (IOException | ServletException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        List<ConnectedUserProjectWorkflow> connectedUserProjectWorkflows = connectedUserCodeWorkflowReferenceFacade
            .getConnectedUserWorkflows(connectedUser.getId());

        for (ConnectedUserProjectWorkflow connectedUserProjectWorkflow : connectedUserProjectWorkflows) {
            if (!connectedUserProjectWorkflow.isEnabled() || connectedUserProjectWorkflow.isDangling()) {
                continue;
            }

            // Isolated per row: one row's lookup or dispatch failure must not stop the fan-out to the remaining
            // rows, so every step for this row lives inside the try.
            try {
                dispatchAutomationBridgeWorkflow(connectedUserProjectWorkflow, environment);
            } catch (IOException | ServletException | RuntimeException e) {
                log.warn(
                    "Failed to dispatch app event to automation-bridge workflow {}",
                    connectedUserProjectWorkflow.getId(), e);
            }
        }

        return ResponseEntity.ok()
            .build();
    }

    private void dispatchAutomationBridgeWorkflow(
        ConnectedUserProjectWorkflow connectedUserProjectWorkflow, Environment environment)
        throws IOException, ServletException {

        // Copy-mode rows (the connected user's own copy of a visual template) have no catalogWorkflowUuid and must be
        // resolved through their own per-user ProjectWorkflow/deployment instead of the shared catalog workflow.
        if (connectedUserProjectWorkflow.getCatalogWorkflowUuid() == null) {
            dispatchCopyModeWorkflow(connectedUserProjectWorkflow, environment);

            return;
        }

        dispatchReferenceModeWorkflow(connectedUserProjectWorkflow);
    }

    private void dispatchReferenceModeWorkflow(ConnectedUserProjectWorkflow reference)
        throws IOException, ServletException {

        String catalogWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(
            reference.getCatalogWorkflowUuid());

        Workflow workflow = workflowService.getWorkflow(catalogWorkflowId);

        String appEventTriggerName = findAppEventTriggerName(workflow);

        if (appEventTriggerName == null) {
            return;
        }

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, reference.getProjectDeploymentId(), reference.getCatalogWorkflowUuid(),
            appEventTriggerName);

        if (webhookWorkflowExecutor.isWorkflowDisabled(workflowExecutionId)) {
            return;
        }

        doProcessTrigger(workflowExecutionId, null, httpServletRequest, httpServletResponse)
            .join();
    }

    /**
     * Mirrors {@code ConnectedUserProjectFacadeImpl.enableProjectWorkflow(long, boolean)}'s copy-mode resolution: a
     * copy's {@code projectWorkflowId} points at a {@link ProjectWorkflow} owned by the connected user's own project
     * (one project per connected user per environment, provisioned by {@code ConnectedUserProjectWorkflowManager}), so
     * that project's currently active deployment -- not the row's (always-null, for copy mode) projectDeploymentId --
     * is what must be resolved and used as the job principal.
     */
    private void dispatchCopyModeWorkflow(
        ConnectedUserProjectWorkflow connectedUserProjectWorkflow, Environment environment)
        throws IOException, ServletException {

        ProjectWorkflow projectWorkflow = projectWorkflowService.getProjectWorkflow(
            connectedUserProjectWorkflow.getProjectWorkflowId());

        long projectDeploymentId = projectDeploymentService.getProjectDeploymentId(
            projectWorkflow.getProjectId(), environment);

        String workflowUuid = projectWorkflow.getUuidAsString();

        String workflowId = projectWorkflowService
            .fetchProjectWorkflowWorkflowId(projectDeploymentId, workflowUuid)
            .orElse(null);

        if (workflowId == null) {
            return;
        }

        Workflow workflow = workflowService.getWorkflow(workflowId);

        String appEventTriggerName = findAppEventTriggerName(workflow);

        if (appEventTriggerName == null) {
            return;
        }

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, projectDeploymentId, workflowUuid, appEventTriggerName);

        if (webhookWorkflowExecutor.isWorkflowDisabled(workflowExecutionId)) {
            return;
        }

        doProcessTrigger(workflowExecutionId, null, httpServletRequest, httpServletResponse)
            .join();
    }

    @InitBinder
    public void initBinder(WebDataBinder dataBinder) {
        dataBinder.registerCustomEditor(EnvironmentModel.class, new CaseInsensitiveEnumPropertyEditorSupport());
    }

    private static String findAppEventTriggerName(Workflow workflow) {
        return WorkflowTrigger.of(workflow)
            .stream()
            .map(workflowTrigger -> {
                WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

                if (Objects.equals(workflowNodeType.name(), APP_EVENT) &&
                    Objects.equals(workflowNodeType.operation(), NEW_EVENT)) {

                    return workflowTrigger.getName();
                }

                return null;
            })
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}

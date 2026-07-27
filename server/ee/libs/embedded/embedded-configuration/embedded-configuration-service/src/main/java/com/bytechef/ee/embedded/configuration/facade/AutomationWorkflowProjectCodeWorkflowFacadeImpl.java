/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.project.ProjectHandler;
import com.bytechef.automation.project.definition.ProjectDefinition;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.config.ApplicationProperties.Workflow.CodeWorkflow;
import com.bytechef.ee.automation.configuration.service.ProjectCodeWorkflowService;
import com.bytechef.ee.embedded.configuration.exception.CodeWorkflowErrorType;
import com.bytechef.ee.platform.codeworkflow.configuration.domain.CodeWorkflowContainer;
import com.bytechef.ee.platform.codeworkflow.configuration.domain.CodeWorkflowContainer.Language;
import com.bytechef.ee.platform.codeworkflow.configuration.facade.CodeWorkflowContainerFacade;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.codeworkflow.loader.automation.ProjectHandlerLoader;
import com.bytechef.platform.component.definition.AppEventComponentDefinition;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.security.constant.AuthorityConstants;
import com.bytechef.workflow.definition.TriggerDefinition;
import com.bytechef.workflow.definition.WorkflowDefinition;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deploys a plain automation code-workflow artifact into the embedded catalog. Structured identically to
 * {@code ProjectCodeWorkflowFacadeImpl} (same java-enabled/loader gate, same {@link ProjectHandlerLoader} call), with
 * one structural difference: the target catalog project is resolved/created through
 * {@link AutomationWorkflowProjectFacade}'s marker convention instead of a bare {@code ProjectService#fetchProject}, so
 * it stays hidden behind the embedded automation-workflow-project entity. Publish goes straight through
 * {@link ProjectService#publishProject}, mirroring how {@code ProjectCodeWorkflowFacadeImpl} itself publishes -- NOT
 * through {@link AutomationWorkflowProjectFacade#publishProject}, which additionally duplicates workflow rows for the
 * visual-editor versioning story that code workflows don't need.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
public class AutomationWorkflowProjectCodeWorkflowFacadeImpl implements AutomationWorkflowProjectCodeWorkflowFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutomationWorkflowProjectCodeWorkflowFacadeImpl.class);

    private final CacheManager cacheManager;
    private final AutomationWorkflowProjectFacade automationWorkflowProjectFacade;
    private final CodeWorkflowContainerFacade codeWorkflowContainerFacade;
    private final ProjectCodeWorkflowService projectCodeWorkflowService;
    private final ProjectService projectService;
    private final ProjectWorkflowService projectWorkflowService;
    private final boolean javaEnabled;
    private final ProjectHandlerLoader.JavaLoader javaLoader;

    @SuppressFBWarnings("EI")
    public AutomationWorkflowProjectCodeWorkflowFacadeImpl(
        ApplicationProperties applicationProperties, CacheManager cacheManager,
        AutomationWorkflowProjectFacade automationWorkflowProjectFacade,
        CodeWorkflowContainerFacade codeWorkflowContainerFacade,
        ProjectCodeWorkflowService projectCodeWorkflowService, ProjectService projectService,
        ProjectWorkflowService projectWorkflowService) {

        this.cacheManager = cacheManager;
        this.automationWorkflowProjectFacade = automationWorkflowProjectFacade;
        this.codeWorkflowContainerFacade = codeWorkflowContainerFacade;
        this.projectCodeWorkflowService = projectCodeWorkflowService;
        this.projectService = projectService;
        this.projectWorkflowService = projectWorkflowService;
        this.javaEnabled = applicationProperties.getWorkflow()
            .getCodeWorkflow()
            .isJavaEnabled();
        this.javaLoader = applicationProperties.getWorkflow()
            .getCodeWorkflow()
            .getJavaLoader() == CodeWorkflow.JavaLoader.ESPRESSO
                ? ProjectHandlerLoader.JavaLoader.ESPRESSO
                : ProjectHandlerLoader.JavaLoader.CLASS_LOADER;
    }

    /**
     * Deploying a code workflow loads and executes the uploaded artifact on the server, so it is restricted to
     * administrators. The guard lives here, mirroring {@code IntegrationCodeWorkflowFacadeImpl#save} and
     * {@code ProjectCodeWorkflowFacadeImpl#save}, so it protects every caller, not only the REST entry point.
     */
    @Override
    @PreAuthorize("hasAuthority(\"" + AuthorityConstants.ADMIN + "\")")
    public void save(byte[] bytes, Language language) {
        if (!javaEnabled && language == Language.JAVA) {
            throw new ConfigurationException(
                "Uploading of Java code workflows is disabled",
                CodeWorkflowErrorType.JAVA_CODE_WORKFLOW_UPLOAD_DISABLED);
        }

        ProjectDefinition projectDefinition = loadProjectDefinition(language, bytes);

        long projectId = automationWorkflowProjectFacade.fetchProjectIdByName(projectDefinition.getName())
            .orElseGet(() -> automationWorkflowProjectFacade.createProject(
                projectDefinition.getName(),
                projectDefinition.getDescription()
                    .orElse(null),
                null, List.of(), null));

        Project project = projectService.getProject(projectId);

        CodeWorkflowContainer codeWorkflowContainer = codeWorkflowContainerFacade.create(
            projectDefinition.getName(), projectDefinition.getVersion(), projectDefinition.getWorkflows(), language,
            bytes, PlatformType.AUTOMATION);

        projectCodeWorkflowService.create(codeWorkflowContainer, project);

        for (Map.Entry<String, String> entry : codeWorkflowContainer.getWorkflowNameIds()
            .entrySet()) {

            projectWorkflowService.addWorkflow(project.getId(), project.getLastProjectVersion(), entry.getValue());
        }

        projectService.publishProject(project.getId(), null, false);

        for (WorkflowDefinition workflowDefinition : projectDefinition.getWorkflows()) {
            warnIfNotPubliclyInvocable(projectDefinition.getName(), workflowDefinition);
        }
    }

    /**
     * Deploy-time trigger validation is advisory, not a rejection (consistent with {@code WorkflowValidator} being
     * advisory elsewhere): a deployed workflow with neither a {@code request} trigger nor an app-event trigger is still
     * deployed, but it will not be invocable through the embedded public endpoints, so a WARN is logged to surface the
     * gap to operators.
     */
    private void warnIfNotPubliclyInvocable(String projectName, WorkflowDefinition workflowDefinition) {
        List<? extends TriggerDefinition> triggerDefinitions = workflowDefinition.getTriggers()
            .orElseGet(List::of);

        boolean publiclyInvocable = triggerDefinitions.stream()
            .anyMatch(AutomationWorkflowProjectCodeWorkflowFacadeImpl::isPubliclyInvocableTrigger);

        if (!publiclyInvocable) {
            LOGGER.warn(
                "Workflow '{}' in deployed automation code workflow project '{}' declares neither a request "
                    + "trigger nor an app-event trigger; it will not be invocable through the embedded public "
                    + "endpoints",
                workflowDefinition.getName(), projectName);
        }
    }

    private static boolean isPubliclyInvocableTrigger(TriggerDefinition triggerDefinition) {
        WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(triggerDefinition.getType());

        if (Objects.equals(workflowNodeType.name(), "request")) {
            return true;
        }

        return Objects.equals(workflowNodeType.name(), AppEventComponentDefinition.APP_EVENT) &&
            Objects.equals(workflowNodeType.operation(), AppEventComponentDefinition.NEW_EVENT);
    }

    @SuppressFBWarnings("PATH_TRAVERSAL_IN")
    private ProjectDefinition loadProjectDefinition(Language language, byte[] bytes) {
        try {
            Path path = Files.createTempFile("embedded_automation_code_workflow", language.getExtension());

            Files.write(path, bytes);

            URI uri = path.toUri();

            try {
                ProjectHandler projectHandler = ProjectHandlerLoader.loadProjectHandler(
                    uri.toURL(), language, javaLoader, uri + UUID.randomUUID()
                        .toString(),
                    cacheManager);

                return projectHandler.getDefinition();
            } finally {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

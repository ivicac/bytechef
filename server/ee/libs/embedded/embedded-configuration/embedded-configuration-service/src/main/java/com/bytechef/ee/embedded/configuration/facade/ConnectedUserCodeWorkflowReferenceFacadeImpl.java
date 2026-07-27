/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.exception.WorkflowErrorType;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.security.SkipAutomationAuthorization;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflowConnection;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowConnectionRepository;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@Transactional
@ConditionalOnEEVersion
@SkipAutomationAuthorization
public class ConnectedUserCodeWorkflowReferenceFacadeImpl implements ConnectedUserCodeWorkflowReferenceFacade {

    private static final String MARKER = "__EMBEDDED__";

    private final ConnectedUserProjectWorkflowConnectionRepository connectedUserProjectWorkflowConnectionRepository;
    private final ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;
    private final ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager;
    private final ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver;
    private final ProjectDeploymentFacade projectDeploymentFacade;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectWorkflowService projectWorkflowService;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public ConnectedUserCodeWorkflowReferenceFacadeImpl(
        ConnectedUserProjectWorkflowConnectionRepository connectedUserProjectWorkflowConnectionRepository,
        ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository,
        ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager,
        ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver,
        ProjectDeploymentFacade projectDeploymentFacade, ProjectDeploymentService projectDeploymentService,
        ProjectWorkflowService projectWorkflowService, WorkflowService workflowService) {

        this.connectedUserProjectWorkflowConnectionRepository = connectedUserProjectWorkflowConnectionRepository;
        this.connectedUserProjectWorkflowRepository = connectedUserProjectWorkflowRepository;
        this.connectedUserProjectWorkflowManager = connectedUserProjectWorkflowManager;
        this.connectedUserWorkflowConnectionResolver = connectedUserWorkflowConnectionResolver;
        this.projectDeploymentFacade = projectDeploymentFacade;
        this.projectDeploymentService = projectDeploymentService;
        this.projectWorkflowService = projectWorkflowService;
        this.workflowService = workflowService;
    }

    /**
     * Creates the reference on first use, provisioning a dedicated {@link ProjectDeployment} for this (catalog project,
     * connected user) pair and auto-wiring per-node connections through
     * {@link ConnectedUserWorkflowConnectionResolver}.
     *
     * <p>
     * A component with no matching connection for the connected user does not abort provisioning: the reference row is
     * still created (and any successfully resolved connections up to that point are still wired), just left
     * {@code enabled = false}, and {@link MissingConnectionException} is rethrown afterward so the caller can surface
     * which connection is missing.
     */
    @Override
    public ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        ConnectedUserProject connectedUserProject = connectedUserProjectWorkflowManager
            .getOrCreateConnectedUserProject(externalUserId, environment);

        Optional<ConnectedUserProjectWorkflow> existing = connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProject.getId(), catalogWorkflowUuid);

        if (existing.isPresent()) {
            return existing.get();
        }

        String catalogWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(catalogWorkflowUuid);
        ProjectWorkflow catalogProjectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(
            catalogWorkflowId);

        long catalogProjectId = catalogProjectWorkflow.getProjectId();

        Workflow catalogWorkflow = workflowService.getWorkflow(catalogWorkflowId);

        Map<String, Long> resolvedConnections;
        MissingConnectionException missingConnectionException = null;

        try {
            resolvedConnections = connectedUserWorkflowConnectionResolver.resolve(catalogWorkflow.getDefinition());
        } catch (MissingConnectionException e) {
            resolvedConnections = Map.of();
            missingConnectionException = e;
        }

        long projectDeploymentId = getOrCreateProjectDeployment(
            catalogProjectId, externalUserId, environment, catalogWorkflowId, resolvedConnections);

        ConnectedUserProjectWorkflow connectedUserProjectWorkflow = new ConnectedUserProjectWorkflow();

        connectedUserProjectWorkflow.setConnectedUserProjectId(connectedUserProject.getId());
        connectedUserProjectWorkflow.setCatalogWorkflowUuid(catalogWorkflowUuid);
        connectedUserProjectWorkflow.setProjectDeploymentId(projectDeploymentId);
        connectedUserProjectWorkflow.setEnabled(missingConnectionException == null);

        ConnectedUserProjectWorkflow saved = connectedUserProjectWorkflowRepository.save(
            connectedUserProjectWorkflow);

        for (Map.Entry<String, Long> entry : resolvedConnections.entrySet()) {
            ConnectedUserProjectWorkflowConnection connection = new ConnectedUserProjectWorkflowConnection();

            connection.setConnectedUserProjectWorkflowId(saved.getId());
            connection.setWorkflowNodeName(entry.getKey());
            connection.setConnectionId(entry.getValue());

            connectedUserProjectWorkflowConnectionRepository.save(connection);
        }

        if (missingConnectionException != null) {
            throw missingConnectionException;
        }

        return saved;
    }

    private long getOrCreateProjectDeployment(
        long catalogProjectId, String externalUserId, Environment environment, String catalogWorkflowId,
        Map<String, Long> resolvedConnections) {

        String name = MARKER + externalUserId;

        List<ProjectDeploymentWorkflowConnection> connections = resolvedConnections.entrySet()
            .stream()
            .map(entry -> new ProjectDeploymentWorkflowConnection(entry.getValue(), entry.getKey(), entry.getKey()))
            .toList();

        return projectDeploymentService.fetchProjectDeploymentByName(catalogProjectId, name)
            .map(ProjectDeployment::getId)
            .orElseGet(() -> {
                ProjectDeployment projectDeployment = new ProjectDeployment();

                projectDeployment.setEnabled(true);
                projectDeployment.setEnvironment(environment);
                projectDeployment.setName(name);
                projectDeployment.setProjectId(catalogProjectId);
                projectDeployment.setProjectVersion(1);

                return projectDeploymentFacade.createProjectDeployment(
                    projectDeployment, catalogWorkflowId, connections);
            });
    }

    @Override
    public void enableReference(
        String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment) {

        ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

        reference.setEnabled(enable);

        connectedUserProjectWorkflowRepository.save(reference);

        String catalogWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(catalogWorkflowUuid);

        projectDeploymentFacade.enableProjectDeploymentWorkflow(
            reference.getProjectDeploymentId(), catalogWorkflowId, enable);
    }

    @Override
    public void deleteReference(String externalUserId, String catalogWorkflowUuid, Environment environment) {
        ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

        for (ConnectedUserProjectWorkflowConnection connection : connectedUserProjectWorkflowConnectionRepository
            .findAllByConnectedUserProjectWorkflowId(reference.getId())) {

            connectedUserProjectWorkflowConnectionRepository.deleteById(connection.getId());
        }

        connectedUserProjectWorkflowRepository.deleteById(reference.getId());
    }

    @Override
    public void markDanglingReferences(long catalogProjectId, Set<String> currentCatalogWorkflowUuids) {
        for (ConnectedUserProjectWorkflow reference : connectedUserProjectWorkflowRepository.findAll()) {
            String catalogWorkflowUuid = reference.getCatalogWorkflowUuid();

            if (catalogWorkflowUuid == null || currentCatalogWorkflowUuids.contains(catalogWorkflowUuid)) {
                continue;
            }

            reference.setDangling(true);
            reference.setDanglingReason("Removed from the catalog project on redeploy");

            connectedUserProjectWorkflowRepository.save(reference);
        }
    }

    private ConnectedUserProjectWorkflow requireReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        ConnectedUserProject connectedUserProject = connectedUserProjectWorkflowManager
            .getOrCreateConnectedUserProject(externalUserId, environment);

        return connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProject.getId(), catalogWorkflowUuid)
            .orElseThrow(() -> new ConfigurationException(
                "No reference to catalog workflow: %s".formatted(catalogWorkflowUuid),
                WorkflowErrorType.WORKFLOW_NOT_FOUND));
    }
}

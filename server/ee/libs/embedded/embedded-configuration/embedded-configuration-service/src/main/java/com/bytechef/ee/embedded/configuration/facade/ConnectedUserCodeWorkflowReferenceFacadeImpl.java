/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.exception.WorkflowErrorType;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.security.SkipAutomationAuthorization;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflowConnection;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowConnectionRepository;
import com.bytechef.ee.embedded.configuration.repository.ConnectedUserProjectWorkflowRepository;
import com.bytechef.ee.embedded.connected.user.domain.ConnectedUser;
import com.bytechef.ee.embedded.connected.user.service.ConnectedUserService;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final AutomationWorkflowProjectFacade automationWorkflowProjectFacade;
    private final ConnectedUserProjectWorkflowConnectionRepository connectedUserProjectWorkflowConnectionRepository;
    private final ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;
    private final ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager;
    private final ConnectedUserService connectedUserService;
    private final ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver;
    private final ProjectDeploymentFacade projectDeploymentFacade;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService;
    private final ProjectWorkflowService projectWorkflowService;

    @SuppressFBWarnings("EI")
    public ConnectedUserCodeWorkflowReferenceFacadeImpl(
        AutomationWorkflowProjectFacade automationWorkflowProjectFacade,
        ConnectedUserProjectWorkflowConnectionRepository connectedUserProjectWorkflowConnectionRepository,
        ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository,
        ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager,
        ConnectedUserService connectedUserService,
        ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver,
        ProjectDeploymentFacade projectDeploymentFacade, ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService,
        ProjectWorkflowService projectWorkflowService) {

        this.automationWorkflowProjectFacade = automationWorkflowProjectFacade;
        this.connectedUserProjectWorkflowConnectionRepository = connectedUserProjectWorkflowConnectionRepository;
        this.connectedUserProjectWorkflowRepository = connectedUserProjectWorkflowRepository;
        this.connectedUserProjectWorkflowManager = connectedUserProjectWorkflowManager;
        this.connectedUserService = connectedUserService;
        this.connectedUserWorkflowConnectionResolver = connectedUserWorkflowConnectionResolver;
        this.projectDeploymentFacade = projectDeploymentFacade;
        this.projectDeploymentService = projectDeploymentService;
        this.projectDeploymentWorkflowService = projectDeploymentWorkflowService;
        this.projectWorkflowService = projectWorkflowService;
    }

    /**
     * Creates the reference on first use, provisioning a dedicated {@link ProjectDeployment} for this (catalog project,
     * connected user) pair and auto-wiring per-slot connections through
     * {@link ConnectedUserWorkflowConnectionResolver}.
     *
     * <p>
     * A component with no matching connection for the connected user does not abort provisioning: the reference row is
     * still created (and any successfully resolved connections up to that point are still wired), just left
     * {@code enabled = false}, and {@link MissingConnectionException} is rethrown afterward so the caller can surface
     * which connection is missing.
     *
     * <p>
     * {@code noRollbackFor} is required for that "still create the row, just disabled" contract to actually hold:
     * without it, Spring's default rollback rule for a {@code @Transactional} method rolls back everything this method
     * wrote (the {@code ConnectedUserProject}, the disabled reference row, any partially-resolved connection rows) the
     * instant {@link MissingConnectionException} propagates out -- silently contradicting this method's own documented
     * behavior. This only surfaces against a real transactional datasource; mocked unit tests never exercise the real
     * proxy chain, so they never catch it.
     */
    @Override
    @Transactional(noRollbackFor = MissingConnectionException.class)
    public ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        ConnectedUserProject connectedUserProject = connectedUserProjectWorkflowManager
            .getOrCreateConnectedUserProject(externalUserId, environment);

        Optional<ConnectedUserProjectWorkflow> existing = connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProject.getId(), catalogWorkflowUuid);

        if (existing.isPresent()) {
            return existing.get();
        }

        validateCatalogWorkflowTemplateVisible(externalUserId, catalogWorkflowUuid, environment);

        String catalogWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(catalogWorkflowUuid);
        ProjectWorkflow catalogProjectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(
            catalogWorkflowId);

        long catalogProjectId = catalogProjectWorkflow.getProjectId();

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);

        ResolvedWorkflowConnections resolvedWorkflowConnections = connectedUserWorkflowConnectionResolver.resolve(
            catalogWorkflowId, connectedUser.getId(), Map.of(), List.of());

        long projectDeploymentId = getOrCreateProjectDeployment(
            catalogProjectId, externalUserId, environment, catalogWorkflowId,
            resolvedWorkflowConnections.connections());

        ConnectedUserProjectWorkflow connectedUserProjectWorkflow = new ConnectedUserProjectWorkflow();

        connectedUserProjectWorkflow.setConnectedUserProjectId(connectedUserProject.getId());
        connectedUserProjectWorkflow.setCatalogWorkflowUuid(catalogWorkflowUuid);
        connectedUserProjectWorkflow.setProjectDeploymentId(projectDeploymentId);
        connectedUserProjectWorkflow.setEnabled(resolvedWorkflowConnections.isComplete());

        ConnectedUserProjectWorkflow saved = connectedUserProjectWorkflowRepository.save(
            connectedUserProjectWorkflow);

        saveConnectedUserProjectWorkflowConnections(saved.getId(), resolvedWorkflowConnections.connections());

        if (!resolvedWorkflowConnections.isComplete()) {
            throw new MissingConnectionException(resolvedWorkflowConnections.firstMissingComponentName());
        }

        return saved;
    }

    /**
     * Provisioning-time authorization, mirroring {@link ConnectedUserProjectFacadeImpl#copyWorkflowTemplate}: the
     * caller-supplied {@code catalogWorkflowUuid} must belong to a template the PERMISSION-FILTERED catalog would show
     * this connected user, so a template the catalog listing hides can never be provisioned by uuid. An unknown uuid
     * and a uuid the user may not see both miss this same membership test and fail identically, so nothing here reveals
     * whether the template exists.
     *
     * <p>
     * Deliberately called AFTER the existing-reference early return in {@link #getOrCreateReference}: this gates
     * PROVISIONING only. A reference already provisioned keeps running even if the vendor later narrows the permission
     * expression -- revoking access to already running automations is a separate product decision.
     */
    private void validateCatalogWorkflowTemplateVisible(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        boolean visibleCatalogWorkflowTemplate = automationWorkflowProjectFacade
            .getPublishedProjects(externalUserId, environment)
            .stream()
            .flatMap(project -> CollectionUtils.stream(project.workflowTemplates()))
            .anyMatch(workflowTemplate -> Objects.equals(workflowTemplate.workflowUuid(), catalogWorkflowUuid));

        if (!visibleCatalogWorkflowTemplate) {
            throw new IllegalArgumentException(
                "Not a published catalog workflow template: " + catalogWorkflowUuid);
        }
    }

    private long getOrCreateProjectDeployment(
        long catalogProjectId, String externalUserId, Environment environment, String catalogWorkflowId,
        List<ProjectDeploymentWorkflowConnection> connections) {

        // The environment must be part of the name: the same external user can be connected in more than one
        // Environment (e.g. PRODUCTION and STAGING), and the ProjectDeployment lookup below is scoped to
        // (catalogProjectId, name) only -- without the environment suffix, the two environments would collide onto
        // the single deployment created by whichever environment provisioned first.
        String name = MARKER + externalUserId + "__" + environment.name();

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

    /**
     * Enabling a reference re-runs {@link ConnectedUserWorkflowConnectionResolver#resolve} and re-populates the
     * connection wiring before flipping the flag, so that a reference whose provisioning (or a previous enable) failed
     * with {@link MissingConnectionException} does not silently start running once re-enabled: if the connected user
     * has since created the missing connection, the wiring is refreshed and enabling proceeds; if the connection is
     * still missing, the same {@link MissingConnectionException} propagates and the reference is left unchanged --
     * enabling must never succeed while wiring is missing or stale.
     */
    @Override
    public void enableReference(
        String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment) {

        ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

        String catalogWorkflowId = projectWorkflowService.getLastPublishedWorkflowId(catalogWorkflowUuid);

        if (enable) {
            rewireConnections(reference, catalogWorkflowId, externalUserId, environment);
        }

        reference.setEnabled(enable);

        connectedUserProjectWorkflowRepository.save(reference);

        projectDeploymentFacade.enableProjectDeploymentWorkflow(
            reference.getProjectDeploymentId(), catalogWorkflowId, enable);
    }

    /**
     * Replaces the reference's {@link ConnectedUserProjectWorkflowConnection} bookkeeping rows and the underlying
     * {@link ProjectDeploymentWorkflow}'s real execution-time connections with a freshly resolved set, mirroring the
     * wiring performed in {@link #getOrCreateReference}. {@link MissingConnectionException} is thrown before any
     * bookkeeping is touched, so a still-missing connection leaves both the bookkeeping rows and the real
     * {@link ProjectDeploymentWorkflow} connections untouched and aborts {@link #enableReference} before the reference
     * is flipped to enabled.
     */
    private void rewireConnections(
        ConnectedUserProjectWorkflow reference, String catalogWorkflowId, String externalUserId,
        Environment environment) {

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = projectDeploymentWorkflowService
            .getProjectDeploymentWorkflow(reference.getProjectDeploymentId(), catalogWorkflowId);

        ResolvedWorkflowConnections resolvedWorkflowConnections = connectedUserWorkflowConnectionResolver.resolve(
            catalogWorkflowId, connectedUser.getId(), Map.of(), projectDeploymentWorkflow.getConnections());

        if (!resolvedWorkflowConnections.isComplete()) {
            throw new MissingConnectionException(resolvedWorkflowConnections.firstMissingComponentName());
        }

        for (ConnectedUserProjectWorkflowConnection connection : connectedUserProjectWorkflowConnectionRepository
            .findAllByConnectedUserProjectWorkflowId(reference.getId())) {

            connectedUserProjectWorkflowConnectionRepository.deleteById(connection.getId());
        }

        saveConnectedUserProjectWorkflowConnections(reference.getId(), resolvedWorkflowConnections.connections());

        projectDeploymentWorkflow.setConnections(resolvedWorkflowConnections.connections());

        projectDeploymentWorkflowService.update(projectDeploymentWorkflow);
    }

    private void saveConnectedUserProjectWorkflowConnections(
        Long connectedUserProjectWorkflowId, List<ProjectDeploymentWorkflowConnection> connections) {

        for (ProjectDeploymentWorkflowConnection connection : connections) {
            ConnectedUserProjectWorkflowConnection connectedUserProjectWorkflowConnection =
                new ConnectedUserProjectWorkflowConnection();

            connectedUserProjectWorkflowConnection.setConnectedUserProjectWorkflowId(connectedUserProjectWorkflowId);
            connectedUserProjectWorkflowConnection.setWorkflowNodeName(connection.getWorkflowNodeName());
            connectedUserProjectWorkflowConnection.setConnectionId(connection.getConnectionId());

            connectedUserProjectWorkflowConnectionRepository.save(connectedUserProjectWorkflowConnection);
        }
    }

    @Override
    public List<ConnectedUserProjectWorkflow> getConnectedUserWorkflows(long connectedUserId) {
        return connectedUserProjectWorkflowRepository.findAllByConnectedUserId(connectedUserId);
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
    public void markDanglingReferences(
        long catalogProjectId, Set<String> previousCatalogWorkflowUuids, Set<String> currentCatalogWorkflowUuids) {

        for (ConnectedUserProjectWorkflow reference : connectedUserProjectWorkflowRepository.findAll()) {
            String catalogWorkflowUuid = reference.getCatalogWorkflowUuid();

            // A reference dangles only if its uuid was served by THIS catalog project's previous deploy and is not
            // served by the current one -- a uuid never previously served by this project (i.e. one belonging to a
            // different catalog project entirely) is never touched, regardless of what the current set contains.
            if (catalogWorkflowUuid == null || !previousCatalogWorkflowUuids.contains(catalogWorkflowUuid) ||
                currentCatalogWorkflowUuids.contains(catalogWorkflowUuid)) {

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

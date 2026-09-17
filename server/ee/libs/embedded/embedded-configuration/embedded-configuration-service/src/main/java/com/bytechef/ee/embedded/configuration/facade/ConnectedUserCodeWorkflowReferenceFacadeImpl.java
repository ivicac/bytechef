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
import com.bytechef.automation.configuration.security.SkipAutomationAuthorization;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProject;
import com.bytechef.ee.embedded.configuration.domain.ConnectedUserProjectWorkflow;
import com.bytechef.ee.embedded.configuration.dto.AutomationWorkflowProjectDTO;
import com.bytechef.ee.embedded.configuration.exception.MissingConnectionException;
import com.bytechef.ee.embedded.configuration.exception.MissingInputException;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserReferenceDeploymentManager.ReferenceResolution;
import com.bytechef.ee.embedded.configuration.facade.ConnectedUserReferenceDeploymentManager.RowSpec;
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

    private final AutomationWorkflowProjectFacade automationWorkflowProjectFacade;
    private final ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager;
    private final ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository;
    private final ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager;
    private final ConnectedUserService connectedUserService;

    @SuppressFBWarnings("EI")
    public ConnectedUserCodeWorkflowReferenceFacadeImpl(
        AutomationWorkflowProjectFacade automationWorkflowProjectFacade,
        ConnectedUserProjectWorkflowManager connectedUserProjectWorkflowManager,
        ConnectedUserProjectWorkflowRepository connectedUserProjectWorkflowRepository,
        ConnectedUserReferenceDeploymentManager connectedUserReferenceDeploymentManager,
        ConnectedUserService connectedUserService) {

        this.automationWorkflowProjectFacade = automationWorkflowProjectFacade;
        this.connectedUserProjectWorkflowManager = connectedUserProjectWorkflowManager;
        this.connectedUserProjectWorkflowRepository = connectedUserProjectWorkflowRepository;
        this.connectedUserReferenceDeploymentManager = connectedUserReferenceDeploymentManager;
        this.connectedUserService = connectedUserService;
    }

    @Override
    public void deleteReference(String externalUserId, String catalogWorkflowUuid, Environment environment) {
        ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

        if (!reference.isDangling()) {
            connectedUserReferenceDeploymentManager.removeWorkflow(
                reference.getProjectDeploymentId(), catalogWorkflowUuid);
        }

        connectedUserProjectWorkflowRepository.deleteById(reference.getId());
    }

    /**
     * {@code noRollbackFor} keeps the reference saved disabled when enabling is refused with a 409: without it Spring's
     * default rollback rule would undo that write the instant the exception propagates.
     */
    @Override
    @Transactional(noRollbackFor = {
        MissingConnectionException.class, MissingInputException.class
    })
    public void enableReference(
        String externalUserId, String catalogWorkflowUuid, boolean enable, Environment environment) {

        ConnectedUserProjectWorkflow reference = requireReference(externalUserId, catalogWorkflowUuid, environment);

        if (reference.isDangling()) {
            if (enable) {
                throw new ConfigurationException(
                    "Reference to catalog workflow %s is dangling".formatted(catalogWorkflowUuid),
                    WorkflowErrorType.WORKFLOW_NOT_FOUND);
            }

            return;
        }

        applyWorkflow(reference, externalUserId, environment, enable, true, Map.of());
    }

    @Override
    public List<ConnectedUserProjectWorkflow> getConnectedUserWorkflows(long connectedUserId) {
        return connectedUserProjectWorkflowRepository.findAllByConnectedUserId(connectedUserId);
    }

    /**
     * Creates the reference on first use: the template's row is written into the connected user's deployment of the
     * catalog project, created at the project's last published version when it does not exist yet, keeping every other
     * row. Repeating the call with {@code requestedConnectionIds} re-resolves an existing reference's connections.
     *
     * <p>
     * A component with no matching connection does not abort provisioning: the reference and its row are still written,
     * disabled, and {@link MissingConnectionException} is thrown afterward. {@code noRollbackFor} is what keeps those
     * writes; mocked unit tests never exercise the real transactional proxy, so they cannot catch its absence.
     */
    @Override
    @Transactional(noRollbackFor = {
        MissingConnectionException.class, MissingInputException.class
    })
    public ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        return provisionReference(externalUserId, catalogWorkflowUuid, environment, Map.of());
    }

    /**
     * See {@link #getOrCreateReference(String, String, Environment)}; both overloads carry the same transaction
     * attribute and delegate to one private method, so neither reaches the other through {@code this}.
     */
    @Override
    @Transactional(noRollbackFor = {
        MissingConnectionException.class, MissingInputException.class
    })
    public ConnectedUserProjectWorkflow getOrCreateReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment,
        Map<String, Long> requestedConnectionIds) {

        return provisionReference(externalUserId, catalogWorkflowUuid, environment, requestedConnectionIds);
    }

    private ConnectedUserProjectWorkflow provisionReference(
        String externalUserId, String catalogWorkflowUuid, Environment environment,
        Map<String, Long> requestedConnectionIds) {

        ConnectedUserProject connectedUserProject = connectedUserProjectWorkflowManager
            .getOrCreateConnectedUserProject(externalUserId, environment);

        Optional<ConnectedUserProjectWorkflow> existingReference = connectedUserProjectWorkflowRepository
            .findByConnectedUserProjectIdAndCatalogWorkflowUuid(connectedUserProject.getId(), catalogWorkflowUuid);

        if (existingReference.isPresent()) {
            ConnectedUserProjectWorkflow reference = existingReference.get();

            if (!requestedConnectionIds.isEmpty() && !reference.isDangling()) {
                return applyWorkflow(
                    reference, externalUserId, environment, reference.isEnabled(), false, requestedConnectionIds);
            }

            return reference;
        }

        AutomationWorkflowProjectDTO catalogProject = getVisibleCatalogProject(
            externalUserId, catalogWorkflowUuid, environment);

        long projectDeploymentId = connectedUserReferenceDeploymentManager.getOrCreateDeployment(
            catalogProject.id(), externalUserId, environment);

        ConnectedUserProjectWorkflow reference = new ConnectedUserProjectWorkflow();

        reference.setCatalogWorkflowUuid(catalogWorkflowUuid);
        reference.setConnectedUserProjectId(connectedUserProject.getId());
        reference.setEnabled(false);
        reference.setProjectDeploymentId(projectDeploymentId);

        ConnectedUserProjectWorkflow savedReference = connectedUserProjectWorkflowRepository.save(reference);

        return applyWorkflow(savedReference, externalUserId, environment, true, false, requestedConnectionIds);
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
            reference.setEnabled(false);

            connectedUserProjectWorkflowRepository.save(reference);
        }
    }

    /**
     * Resolves the reference at its deployment's current version, writes its row (other rows are kept) and saves the
     * reference. When enabling was asked for, a missing required connection throws {@link MissingConnectionException}
     * and a missing required input throws {@link MissingInputException} -- in both cases AFTER the reference is saved
     * disabled. On provisioning ({@code throwOnMissingInput == false}) a missing input is not an error: inputs are
     * written after provisioning in both the hub and the API flow, so the reference is simply left disabled.
     */
    private ConnectedUserProjectWorkflow applyWorkflow(
        ConnectedUserProjectWorkflow reference, String externalUserId, Environment environment, boolean enable,
        boolean throwOnMissingInput, Map<String, Long> requestedConnectionIds) {

        ConnectedUser connectedUser = connectedUserService.getConnectedUser(externalUserId, environment);
        ProjectDeployment projectDeployment = connectedUserReferenceDeploymentManager.getDeployment(
            reference.getProjectDeploymentId());

        int projectVersion = projectDeployment.getProjectVersion();
        String catalogWorkflowUuid = reference.getCatalogWorkflowUuid();

        String workflowId = connectedUserReferenceDeploymentManager.getWorkflowId(
            projectDeployment.getProjectId(), projectVersion, catalogWorkflowUuid);

        Optional<ProjectDeploymentWorkflow> currentRow = connectedUserReferenceDeploymentManager.fetchWorkflowRow(
            reference.getProjectDeploymentId(), workflowId);

        List<ProjectDeploymentWorkflowConnection> currentConnections = currentRow
            .map(ProjectDeploymentWorkflow::getConnections)
            .orElse(List.of());
        Map<String, ?> currentInputs = currentRow.<Map<String, ?>>map(ProjectDeploymentWorkflow::getInputs)
            .orElse(Map.of());

        ReferenceResolution resolution = connectedUserReferenceDeploymentManager.resolveReference(
            connectedUser.getId(), workflowId, enable, requestedConnectionIds, currentConnections, currentInputs);

        RowSpec rowSpec = resolution.rowSpec();

        connectedUserReferenceDeploymentManager.putWorkflows(
            reference.getProjectDeploymentId(), projectVersion, Map.of(catalogWorkflowUuid, rowSpec));

        reference.setEnabled(rowSpec.enabled());

        ConnectedUserProjectWorkflow savedReference = connectedUserProjectWorkflowRepository.save(reference);

        if (enable && resolution.missingComponentName() != null) {
            throw new MissingConnectionException(resolution.missingComponentName());
        }

        if (enable && throwOnMissingInput && resolution.missingInputName() != null) {
            throw new MissingInputException(resolution.missingInputName());
        }

        return savedReference;
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
    private AutomationWorkflowProjectDTO getVisibleCatalogProject(
        String externalUserId, String catalogWorkflowUuid, Environment environment) {

        return automationWorkflowProjectFacade.getPublishedProjects(externalUserId, environment)
            .stream()
            .filter(project -> CollectionUtils.stream(project.workflowTemplates())
                .anyMatch(workflowTemplate -> Objects.equals(workflowTemplate.workflowUuid(), catalogWorkflowUuid)))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Not a published catalog workflow template: " + catalogWorkflowUuid));
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

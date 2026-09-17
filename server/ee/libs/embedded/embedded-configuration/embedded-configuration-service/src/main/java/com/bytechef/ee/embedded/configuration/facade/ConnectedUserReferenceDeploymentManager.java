/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.embedded.configuration.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectVersion;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.security.SkipAutomationAuthorization;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.ee.embedded.configuration.exception.MissingInputException;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.configuration.domain.Environment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Owns the per-(connected user, catalog project, environment) {@link ProjectDeployment} every reference to that
 * project's templates shares. Within one project version a change to a template writes that template's row alone, so
 * the triggers and running jobs of the user's other templates are never disturbed; only moving the deployment to
 * another version writes the complete list through
 * {@link ProjectDeploymentFacade#updateProjectDeployment(ProjectDeployment, List, List)}. Never resolves a deployment
 * by (project, environment): a catalog project has one deployment per connected user.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
@SkipAutomationAuthorization
public class ConnectedUserReferenceDeploymentManager {

    private static final String MARKER = "__EMBEDDED__";

    private final ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver;
    private final ProjectDeploymentFacade projectDeploymentFacade;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService;
    private final ProjectService projectService;
    private final ProjectWorkflowService projectWorkflowService;
    private final WorkflowService workflowService;

    @SuppressFBWarnings("EI")
    public ConnectedUserReferenceDeploymentManager(
        ConnectedUserWorkflowConnectionResolver connectedUserWorkflowConnectionResolver,
        ProjectDeploymentFacade projectDeploymentFacade, ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
        ProjectWorkflowService projectWorkflowService, WorkflowService workflowService) {

        this.connectedUserWorkflowConnectionResolver = connectedUserWorkflowConnectionResolver;
        this.projectDeploymentFacade = projectDeploymentFacade;
        this.projectDeploymentService = projectDeploymentService;
        this.projectDeploymentWorkflowService = projectDeploymentWorkflowService;
        this.projectService = projectService;
        this.projectWorkflowService = projectWorkflowService;
        this.workflowService = workflowService;
    }

    /**
     * Writes an empty row list first so every enabled row's triggers are disabled through the same path a row removal
     * takes, then deletes the deployment.
     */
    public void deleteDeployment(long projectDeploymentId) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        projectDeploymentFacade.updateProjectDeployment(projectDeployment, List.of(), List.of());

        projectDeploymentFacade.deleteProjectDeployment(projectDeploymentId);
    }

    /**
     * The row of {@code catalogWorkflowUuid} at the deployment's current version, looked up directly rather than by
     * scanning the deployment's rows.
     */
    public Optional<ProjectDeploymentWorkflow> fetchRow(long projectDeploymentId, String catalogWorkflowUuid) {
        return projectDeploymentService.fetchProjectDeployment(projectDeploymentId)
            .flatMap(projectDeployment -> fetchRowAtCurrentVersion(projectDeployment, catalogWorkflowUuid));
    }

    public Optional<ProjectDeployment> fetchDeployment(long projectDeploymentId) {
        return projectDeploymentService.fetchProjectDeployment(projectDeploymentId);
    }

    public Optional<ProjectDeploymentWorkflow> fetchWorkflowRow(long projectDeploymentId, String workflowId) {
        return projectDeploymentWorkflowService.fetchProjectDeploymentWorkflow(projectDeploymentId, workflowId);
    }

    @Nullable
    public String findMissingRequiredInput(String workflowId, Map<String, ?> inputs) {
        Workflow workflow = workflowService.getWorkflow(workflowId);

        for (Workflow.Input input : workflow.getInputs()) {
            Object value = inputs.get(input.name());

            if (input.required() && (value == null || StringUtils.isBlank(String.valueOf(value)))) {
                return input.name();
            }
        }

        return null;
    }

    public ProjectDeployment getDeployment(long projectDeploymentId) {
        return projectDeploymentService.getProjectDeployment(projectDeploymentId);
    }

    public String getDeploymentName(String externalUserId, Environment environment) {
        return MARKER + externalUserId + "__" + environment.name();
    }

    /**
     * The row's stored inputs, or an empty map when the reference has no row yet (e.g. dangling).
     */
    public Map<String, ?> getInputs(long projectDeploymentId, String catalogWorkflowUuid) {
        return fetchRow(projectDeploymentId, catalogWorkflowUuid)
            .<Map<String, ?>>map(ProjectDeploymentWorkflow::getInputs)
            .orElse(Map.of());
    }

    public int getLastPublishedVersion(long catalogProjectId) {
        Project project = projectService.getProject(catalogProjectId);

        ProjectVersion lastPublishedProjectVersion = project.getLastPublishedProjectVersion();

        if (lastPublishedProjectVersion == null) {
            throw new IllegalArgumentException("Catalog project id=%s is not published".formatted(catalogProjectId));
        }

        return lastPublishedProjectVersion.getVersion();
    }

    /**
     * The environment is part of the name because the same external user can be connected in more than one environment,
     * and the lookup is scoped to (catalog project, name) only.
     *
     * <p>
     * {@code ProjectDeploymentService#create} always stores a new deployment disabled, and triggers of enabled rows are
     * registered only while their deployment is enabled, so a created deployment is enabled right away -- before it has
     * any row, which makes enabling it a flag flip. Whether a template runs is carried by its row's enabled flag.
     */
    public long getOrCreateDeployment(long catalogProjectId, String externalUserId, Environment environment) {
        String name = getDeploymentName(externalUserId, environment);

        return projectDeploymentService.fetchProjectDeploymentByName(catalogProjectId, name)
            .map(ProjectDeployment::getId)
            .orElseGet(() -> {
                ProjectDeployment projectDeployment = new ProjectDeployment();

                projectDeployment.setEnabled(true);
                projectDeployment.setEnvironment(environment);
                projectDeployment.setName(name);
                projectDeployment.setProjectId(catalogProjectId);
                projectDeployment.setProjectVersion(getLastPublishedVersion(catalogProjectId));

                long projectDeploymentId = projectDeploymentFacade.createProjectDeployment(
                    projectDeployment, List.of(), List.of());

                projectDeploymentFacade.enableProjectDeployment(projectDeploymentId, true);

                return projectDeploymentId;
            });
    }

    /**
     * Whether the deployment is a connected user's reference deployment, as opposed to any other deployment of the
     * catalog project, which the reference code must never touch.
     */
    public static boolean isReferenceDeployment(ProjectDeployment projectDeployment) {
        String name = projectDeployment.getName();

        return name != null && name.startsWith(MARKER);
    }

    public Optional<String> fetchWorkflowId(long catalogProjectId, int projectVersion, String catalogWorkflowUuid) {
        return projectWorkflowService.fetchProjectWorkflow(catalogProjectId, projectVersion, catalogWorkflowUuid)
            .map(ProjectWorkflow::getWorkflowId);
    }

    public String getWorkflowId(long catalogProjectId, int projectVersion, String catalogWorkflowUuid) {
        return fetchWorkflowId(catalogProjectId, projectVersion, catalogWorkflowUuid)
            .orElseThrow(() -> new IllegalArgumentException(
                "Catalog workflow %s is not in version %s of project id=%s".formatted(
                    catalogWorkflowUuid, projectVersion, catalogProjectId)));
    }

    /**
     * Writes the rows of {@code rowSpecsByCatalogWorkflowUuid} at {@code projectVersion}.
     *
     * <p>
     * At the deployment's current version each passed row is written on its own and no other row is read or re-sent:
     * {@code ProjectDeploymentFacadeImpl#updateProjectDeployment} disables and re-enables every enabled row it is
     * handed, which stops that row's running jobs and re-registers its triggers, so a full-list write would disturb
     * every other template of the connected user whenever one of them changed.
     *
     * <p>
     * At a different version the deployment moves as a whole through {@code updateProjectDeployment}: every workflow id
     * changes, so re-registration is inherent, and the rows not passed are dropped because their workflow ids belong to
     * the version the deployment is leaving.
     */
    public void putWorkflows(
        long projectDeploymentId, int projectVersion, Map<String, RowSpec> rowSpecsByCatalogWorkflowUuid) {

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        if (projectDeployment.getProjectVersion() == projectVersion) {
            for (Map.Entry<String, RowSpec> entry : rowSpecsByCatalogWorkflowUuid.entrySet()) {
                putWorkflow(projectDeployment, entry.getKey(), entry.getValue());
            }

            return;
        }

        Map<String, ProjectDeploymentWorkflow> existingRowsByCatalogWorkflowUuid = getRowsByCatalogWorkflowUuid(
            projectDeploymentId);

        List<ProjectDeploymentWorkflow> rows = new ArrayList<>();

        for (Map.Entry<String, RowSpec> entry : rowSpecsByCatalogWorkflowUuid.entrySet()) {
            RowSpec rowSpec = entry.getValue();

            ProjectDeploymentWorkflow existingRow = existingRowsByCatalogWorkflowUuid.get(entry.getKey());
            ResolvedWorkflowConnections resolved = rowSpec.resolved();

            ProjectDeploymentWorkflow row = new ProjectDeploymentWorkflow();

            row.setConnections(resolved.connections());
            row.setEnabled(rowSpec.enabled());
            row.setInputs(
                rowSpec.inputs() != null || existingRow == null ? rowSpec.inputs() : existingRow.getInputs());
            row.setProjectDeploymentId(projectDeploymentId);
            row.setWorkflowId(getWorkflowId(projectDeployment.getProjectId(), projectVersion, entry.getKey()));

            rows.add(row);
        }

        projectDeployment.setProjectVersion(projectVersion);

        projectDeploymentFacade.updateProjectDeployment(projectDeployment, rows, List.of());
    }

    /**
     * Removes the row of {@code catalogWorkflowUuid} alone -- disabling it first when enabled, which unregisters its
     * triggers and stops its running jobs -- and deletes the deployment when no row remains. A deployment that no
     * longer exists has nothing to remove, so the caller can still delete its reference.
     */
    public void removeWorkflow(long projectDeploymentId, String catalogWorkflowUuid) {
        Optional<ProjectDeployment> fetchedProjectDeployment = projectDeploymentService.fetchProjectDeployment(
            projectDeploymentId);

        if (fetchedProjectDeployment.isEmpty()) {
            return;
        }

        Optional<ProjectDeploymentWorkflow> row = fetchRowAtCurrentVersion(
            fetchedProjectDeployment.get(), catalogWorkflowUuid);

        if (row.isPresent()) {
            ProjectDeploymentWorkflow existingRow = row.get();

            if (existingRow.isEnabled()) {
                projectDeploymentFacade.enableProjectDeploymentWorkflow(
                    projectDeploymentId, existingRow.getWorkflowId(), false);
            }

            projectDeploymentWorkflowService.delete(existingRow.getId());
        }

        List<ProjectDeploymentWorkflow> remainingRows = projectDeploymentWorkflowService.getProjectDeploymentWorkflows(
            projectDeploymentId);

        if (remainingRows.isEmpty()) {
            projectDeploymentFacade.deleteProjectDeployment(projectDeploymentId);
        }
    }

    /**
     * A row is enabled only when enabling was asked for AND every required connection resolved AND every required input
     * has a value: {@code ProjectDeploymentFacadeImpl} refuses to save an enabled row that misses either.
     */
    public ReferenceResolution resolveReference(
        long connectedUserId, String workflowId, boolean enable, Map<String, Long> requestedConnectionIds,
        List<ProjectDeploymentWorkflowConnection> currentConnections, Map<String, ?> inputs) {

        ResolvedWorkflowConnections resolved = connectedUserWorkflowConnectionResolver.resolve(
            workflowId, connectedUserId, requestedConnectionIds, currentConnections);

        String missingInputName = findMissingRequiredInput(workflowId, inputs);

        boolean enabled = enable && resolved.isComplete() && missingInputName == null;

        return new ReferenceResolution(
            new RowSpec(resolved, enabled, null), resolved.firstMissingComponentName(), missingInputName);
    }

    /**
     * Rewrites the row at the deployment's current version so an enabled row's triggers re-register with the new
     * inputs. An empty map cannot clear inputs: {@code ProjectDeploymentWorkflow.setInputs} ignores it, the same limit
     * the copy path has.
     *
     * <p>
     * An ENABLED row is checked against its required inputs before any write: without this, the rewrite would disable
     * the row, save the incomplete inputs, then fail to re-enable it through
     * {@code ProjectDeploymentFacadeImpl#enableProjectDeploymentWorkflow}, whose own validation throws a raw
     * {@code IllegalArgumentException} -- leaving the row disabled and surfacing a low-level exception the rest of the
     * reference API never uses. A disabled row skips this check: a partial input set is allowed there, the same as
     * provisioning.
     */
    public void updateInputs(long projectDeploymentId, String catalogWorkflowUuid, Map<String, ?> inputs) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        ProjectDeploymentWorkflow row = fetchRowAtCurrentVersion(projectDeployment, catalogWorkflowUuid)
            .orElseThrow(() -> new IllegalArgumentException(
                "Catalog workflow %s is not deployed in deployment id=%s".formatted(
                    catalogWorkflowUuid, projectDeploymentId)));

        if (row.isEnabled()) {
            String missingInputName = findMissingRequiredInput(row.getWorkflowId(), inputs);

            if (missingInputName != null) {
                throw new MissingInputException(missingInputName);
            }
        }

        RowSpec rowSpec = new RowSpec(
            new ResolvedWorkflowConnections(row.getConnections(), List.of()), row.isEnabled(), inputs);

        putWorkflow(projectDeployment, catalogWorkflowUuid, rowSpec);
    }

    private Optional<ProjectDeploymentWorkflow> fetchRowAtCurrentVersion(
        ProjectDeployment projectDeployment, String catalogWorkflowUuid) {

        return projectWorkflowService
            .fetchProjectWorkflow(
                projectDeployment.getProjectId(), projectDeployment.getProjectVersion(), catalogWorkflowUuid)
            .flatMap(projectWorkflow -> projectDeploymentWorkflowService.fetchProjectDeploymentWorkflow(
                projectDeployment.getId(), projectWorkflow.getWorkflowId()));
    }

    private Map<String, ProjectDeploymentWorkflow> getRowsByCatalogWorkflowUuid(long projectDeploymentId) {
        Map<String, ProjectDeploymentWorkflow> rowsByCatalogWorkflowUuid = new LinkedHashMap<>();

        for (ProjectDeploymentWorkflow row : projectDeploymentWorkflowService.getProjectDeploymentWorkflows(
            projectDeploymentId)) {

            ProjectWorkflow projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(row.getWorkflowId());

            rowsByCatalogWorkflowUuid.put(projectWorkflow.getUuidAsString(), row);
        }

        return rowsByCatalogWorkflowUuid;
    }

    /**
     * Writes one row without touching any other. A row whose connections, inputs and enabled flag already match the
     * spec is left alone, so repeating an enable or re-resolving unchanged wiring never stops the row's own running
     * jobs. Otherwise an enabled row is disabled first (unregistering its triggers), then saved disabled with its new
     * wiring, and enabled again through {@code ProjectDeploymentFacade#enableProjectDeploymentWorkflow} when the spec
     * asks for it -- which checks the required connections and inputs and registers the triggers of this workflow
     * alone.
     */
    private void putWorkflow(ProjectDeployment projectDeployment, String catalogWorkflowUuid, RowSpec rowSpec) {
        long projectDeploymentId = projectDeployment.getId();

        String workflowId = getWorkflowId(
            projectDeployment.getProjectId(), projectDeployment.getProjectVersion(), catalogWorkflowUuid);

        Optional<ProjectDeploymentWorkflow> existingRow = projectDeploymentWorkflowService
            .fetchProjectDeploymentWorkflow(projectDeploymentId, workflowId);

        ResolvedWorkflowConnections resolved = rowSpec.resolved();

        if (existingRow.isPresent()) {
            ProjectDeploymentWorkflow row = existingRow.get();

            if (isUnchanged(row, rowSpec)) {
                return;
            }

            if (row.isEnabled()) {
                projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentId, workflowId, false);

                // Disabling saved the row again, so its optimistic-lock version moved on.
                row = projectDeploymentWorkflowService.getProjectDeploymentWorkflow(row.getId());
            }

            row.setConnections(resolved.connections());
            row.setEnabled(false);
            row.setWorkflowId(workflowId);

            if (rowSpec.inputs() != null) {
                row.setInputs(rowSpec.inputs());
            }

            projectDeploymentWorkflowService.update(row);
        } else {
            ProjectDeploymentWorkflow row = new ProjectDeploymentWorkflow();

            row.setConnections(resolved.connections());
            row.setEnabled(false);
            row.setInputs(rowSpec.inputs());
            row.setProjectDeploymentId(projectDeploymentId);
            row.setWorkflowId(workflowId);

            projectDeploymentWorkflowService.create(row);
        }

        if (rowSpec.enabled()) {
            projectDeploymentFacade.enableProjectDeploymentWorkflow(projectDeploymentId, workflowId, true);
        }
    }

    private static boolean isUnchanged(ProjectDeploymentWorkflow row, RowSpec rowSpec) {
        ResolvedWorkflowConnections resolved = rowSpec.resolved();

        return row.isEnabled() == rowSpec.enabled() &&
            Set.copyOf(row.getConnections())
                .equals(Set.copyOf(resolved.connections()))
            &&
            (rowSpec.inputs() == null || Objects.equals(row.getInputs(), rowSpec.inputs()));
    }

    public record ReferenceResolution(
        RowSpec rowSpec, @Nullable String missingComponentName, @Nullable String missingInputName) {
    }

    /**
     * @param inputs the row's inputs, or {@code null} to keep the inputs the row already has
     */
    @SuppressFBWarnings("EI")
    public record RowSpec(ResolvedWorkflowConnections resolved, boolean enabled, @Nullable Map<String, ?> inputs) {
    }
}

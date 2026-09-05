/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.automation.datasync.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectVersion;
import com.bytechef.automation.configuration.domain.SystemProjects;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.domain.DataSyncElement.Kind;
import com.bytechef.automation.datasync.dto.DataSyncDTO;
import com.bytechef.automation.datasync.dto.DataSyncDeploymentDTO;
import com.bytechef.automation.datasync.dto.DataSyncVersionDTO;
import com.bytechef.automation.datasync.exception.DataSyncErrorType;
import com.bytechef.automation.datasync.service.DataSyncElementService;
import com.bytechef.automation.datasync.service.DataSyncService;
import com.bytechef.automation.datasync.util.DataSyncWorkflowGenerator;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.WorkflowNodeTestOutputService;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.configuration.workflow.WorkflowPreDeleteListener;
import com.bytechef.platform.tag.domain.Tag;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Modelled on {@code AiAgentFacadeImpl}: rows are the truth, the hidden project's draft workflow is regenerated on
 * every mutation, and publish snapshots that draft as a project version.
 *
 * @author Ivica Cardic
 */
@Service
public class DataSyncFacadeImpl implements DataSyncFacade {

    private static final int MAX_NAME_LENGTH = 64;

    private final ComponentDefinitionService componentDefinitionService;
    private final DataSyncElementService dataSyncElementService;
    private final DataSyncService dataSyncService;
    private final JobService jobService;
    private final PrincipalJobService principalJobService;
    private final ProjectDeploymentFacade projectDeploymentFacade;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService;
    private final ProjectService projectService;
    private final ProjectVisibilityFilter projectVisibilityFilter;
    private final ProjectWorkflowService projectWorkflowService;
    private final TagService tagService;
    private final WorkflowNodeTestOutputService workflowNodeTestOutputService;
    private final List<WorkflowPreDeleteListener> workflowPreDeleteListeners;
    private final WorkflowService workflowService;
    private final WorkflowTestConfigurationService workflowTestConfigurationService;

    @SuppressFBWarnings("EI2")
    public DataSyncFacadeImpl(
        ComponentDefinitionService componentDefinitionService, DataSyncElementService dataSyncElementService,
        DataSyncService dataSyncService, JobService jobService, PrincipalJobService principalJobService,
        ProjectDeploymentFacade projectDeploymentFacade, ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
        ProjectVisibilityFilter projectVisibilityFilter, ProjectWorkflowService projectWorkflowService,
        TagService tagService, WorkflowNodeTestOutputService workflowNodeTestOutputService,
        List<WorkflowPreDeleteListener> workflowPreDeleteListeners, WorkflowService workflowService,
        WorkflowTestConfigurationService workflowTestConfigurationService) {

        this.componentDefinitionService = componentDefinitionService;
        this.dataSyncElementService = dataSyncElementService;
        this.dataSyncService = dataSyncService;
        this.jobService = jobService;
        this.principalJobService = principalJobService;
        this.projectDeploymentFacade = projectDeploymentFacade;
        this.projectDeploymentService = projectDeploymentService;
        this.projectDeploymentWorkflowService = projectDeploymentWorkflowService;
        this.projectService = projectService;
        this.projectVisibilityFilter = projectVisibilityFilter;
        this.projectWorkflowService = projectWorkflowService;
        this.tagService = tagService;
        this.workflowNodeTestOutputService = workflowNodeTestOutputService;
        this.workflowPreDeleteListeners = workflowPreDeleteListeners;
        this.workflowService = workflowService;
        this.workflowTestConfigurationService = workflowTestConfigurationService;
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_CREATE')")
    @Transactional
    public DataSyncDTO createDataSync(String title, @Nullable String description, long workspaceId) {
        Objects.requireNonNull(title, "title");

        String name = uniqueName(slugify(title), workspaceId);
        UUID uuid = UUID.randomUUID();

        Project project = new Project();

        project.setName(SystemProjects.DATA_SYNC_NAME_PREFIX + uuid);
        project.setDescription("System project backing the '" + title + "' Data Sync. Do not edit.");
        project.setWorkspaceId(workspaceId);

        Project savedProject = projectService.create(project);

        DataSync dataSync = new DataSync();

        dataSync.setName(name);
        dataSync.setTitle(title);
        dataSync.setDescription(description);
        dataSync.setWorkspaceId(workspaceId);
        dataSync.setProjectId(savedProject.getId());
        dataSync.setUuid(uuid);
        dataSync.setTriggerType(TriggerType.MANUAL);

        DataSync savedDataSync = dataSyncService.create(dataSync);

        String definition = DataSyncWorkflowGenerator.generate(savedDataSync, List.of());

        Workflow workflow = workflowService.create(definition, Workflow.Format.JSON, Workflow.SourceType.JDBC);

        projectWorkflowService.addWorkflow(
            savedProject.getId(), savedProject.getLastProjectVersion(), workflow.getId());

        return toDataSyncDTO(savedDataSync);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public DataSyncDTO updateDataSync(long id, @Nullable String title, @Nullable String description) {
        DataSync dataSync = dataSyncService.getDataSync(id);

        if (title != null) {
            dataSync.setTitle(title);
        }

        if (description != null) {
            dataSync.setDescription(description);
        }

        DataSync updatedDataSync = dataSyncService.update(dataSync);

        regenerateAndSaveWorkflow(updatedDataSync);

        return toDataSyncDTO(updatedDataSync);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_DELETE')")
    @Transactional
    public void deleteDataSync(long id) {
        DataSync dataSync = dataSyncService.getDataSync(id);

        if (hasAnyDeployment(dataSync.getProjectId())) {
            throw new ConfigurationException(
                "Data Sync " + id + " cannot be deleted while its project has deployments",
                DataSyncErrorType.DATA_SYNC_HAS_DEPLOYMENTS);
        }

        long projectId = dataSync.getProjectId();

        dataSyncService.delete(id);

        Project project = projectService.getProject(projectId);

        for (ProjectVersion projectVersion : project.getProjectVersions()) {
            int version = projectVersion.getVersion();

            for (String workflowId : projectWorkflowService.getProjectWorkflowIds(projectId, version)) {
                projectWorkflowService.delete(projectId, version, workflowId);

                for (WorkflowPreDeleteListener workflowPreDeleteListener : workflowPreDeleteListeners) {
                    workflowPreDeleteListener.onWorkflowPreDelete(workflowId);
                }

                workflowService.delete(workflowId);
            }
        }

        projectService.delete(projectId);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public DataSyncDTO getDataSync(long id) {
        return toDataSyncDTO(dataSyncService.getDataSync(id));
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<DataSyncDTO> getDataSyncs(long workspaceId) {
        return visibleDataSyncs(workspaceId)
            .stream()
            .map(this::toDataSyncDTO)
            .toList();
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public void updateDataSyncTrigger(long id, TriggerType triggerType, @Nullable Map<String, ?> triggerParameters) {
        DataSync dataSync = dataSyncService.getDataSync(id);

        dataSync.setTriggerType(triggerType);
        dataSync.setTriggerParameters(triggerType == TriggerType.MANUAL ? null : triggerParameters);

        regenerateAndSaveWorkflow(dataSyncService.update(dataSync));
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public DataSyncElement setDataSyncElement(
        long id, Kind kind, String componentName, int componentVersion, String operationName,
        @Nullable Map<String, ?> parameters, @Nullable Long connectionId) {

        DataSync dataSync = dataSyncService.getDataSync(id);

        if (kind == Kind.PROCESSOR && !isFieldMapper(componentName, componentVersion, operationName)) {
            throw new ConfigurationException(
                "The processor of a Data Sync must be dataStreamProcessor/v1/fieldMapper",
                DataSyncErrorType.PROCESSOR_NOT_FIELD_MAPPER);
        }

        List<DataSyncElement> elements = dataSyncElementService.getByDataSyncId(id);

        Optional<DataSyncElement> existing = elements.stream()
            .filter(element -> element.getKind() == kind)
            .findFirst();

        boolean sideComponentChanged = kind != Kind.PROCESSOR && existing
            .map(element -> !Objects.equals(element.getComponentName(), componentName)
                || element.getComponentVersion() != componentVersion
                || !Objects.equals(element.getOperationName(), operationName))
            .orElse(true);

        DataSyncElement element = existing.orElseGet(() -> new DataSyncElement(id, kind));

        element.setComponentName(componentName);
        element.setComponentVersion(componentVersion);
        element.setOperationName(operationName);
        element.setParameters(parameters);
        element.setConnectionId(connectionId);

        DataSyncElement savedElement = existing.isPresent()
            ? dataSyncElementService.update(element)
            : dataSyncElementService.create(element);

        if (sideComponentChanged) {
            elements.stream()
                .filter(candidate -> candidate.getKind() == Kind.PROCESSOR)
                .findFirst()
                .ifPresent(processor -> dataSyncElementService.delete(processor.getId()));
        }

        regenerateAndSaveWorkflow(dataSync);

        return savedElement;
    }

    @Override
    @PreAuthorize("hasPermission(#elementId, 'DataSyncElement', 'DATA_SYNC_EDIT')")
    @Transactional
    public void updateDataSyncElement(
        long elementId, @Nullable Map<String, ?> parameters, @Nullable Long connectionId) {

        DataSyncElement element = dataSyncElementService.getDataSyncElement(elementId);

        element.setParameters(parameters);
        element.setConnectionId(connectionId);

        dataSyncElementService.update(element);

        regenerateAndSaveWorkflow(dataSyncService.getDataSync(element.getDataSyncId()));
    }

    // Task 4 fills these in; the bodies below are the compile-only versions.

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_PUBLISH')")
    @Transactional
    public int publishDataSync(long id, @Nullable String description) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<DataSyncVersionDTO> getDataSyncVersions(long id) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<DataSyncDeploymentDTO> getDataSyncDeployments(long workspaceId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    public long runDataSyncDeployment(long id, long projectDeploymentId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<Tag> getDataSyncTags(long workspaceId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public void updateDataSyncTags(long id, List<Tag> tags) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<Tag> getDataSyncDeploymentTags(long workspaceId) {
        throw new UnsupportedOperationException("Task 4");
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public void updateDataSyncDeploymentTags(long id, long projectDeploymentId, List<Tag> tags) {
        throw new UnsupportedOperationException("Task 4");
    }

    // --- draft regeneration -----------------------------------------------------------------------------------

    private void regenerateAndSaveWorkflow(DataSync dataSync) {
        Project project = projectService.getProject(dataSync.getProjectId());
        String workflowId = getVersionWorkflowId(project.getId(), project.getLastProjectVersion());
        Workflow workflow = workflowService.getWorkflow(workflowId);

        List<DataSyncElement> elements = dataSyncElementService.getByDataSyncId(dataSync.getId());

        String definition = DataSyncWorkflowGenerator.generate(dataSync, elements);

        workflowService.update(workflowId, definition, workflow.getVersion());

        syncTestConnections(workflowId, elements);
    }

    /**
     * Test runs source connections ONLY from the draft's {@code WorkflowTestConfiguration}, so every regeneration
     * re-syncs each row's {@code connectionId} into it, keyed the way the generator emits the element's
     * {@code connections} block: root node name + element node name. Synced into every {@link Environment} because the
     * row's connection is not environment-scoped and the test configuration is.
     */
    private void syncTestConnections(String workflowId, List<DataSyncElement> elements) {
        for (DataSyncElement element : elements) {
            Long connectionId = element.getConnectionId();

            if (connectionId == null) {
                continue;
            }

            for (Environment environment : Environment.values()) {
                workflowTestConfigurationService.saveWorkflowTestConfigurationConnection(
                    workflowId, DataSyncWorkflowGenerator.TASK_NODE_NAME,
                    DataSyncWorkflowGenerator.nodeName(element.getKind()), connectionId, false,
                    environment.ordinal());
            }
        }
    }

    private String getVersionWorkflowId(long projectId, int projectVersion) {
        List<String> workflowIds = projectWorkflowService.getProjectWorkflowIds(projectId, projectVersion);

        if (workflowIds.size() != 1) {
            throw new IllegalStateException(
                "Expected exactly one workflow for project " + projectId + " version " + projectVersion
                    + ", found " + workflowIds.size());
        }

        return workflowIds.get(0);
    }

    private boolean hasAnyDeployment(long projectId) {
        for (Environment environment : Environment.values()) {
            if (projectDeploymentService.fetchProjectDeployment(projectId, environment)
                .isPresent()) {

                return true;
            }
        }

        return false;
    }

    private static boolean isFieldMapper(String componentName, int componentVersion, String operationName) {
        return DataSyncElement.PROCESSOR_COMPONENT_NAME.equals(componentName)
            && DataSyncElement.PROCESSOR_COMPONENT_VERSION == componentVersion
            && DataSyncElement.PROCESSOR_OPERATION_NAME.equals(operationName);
    }

    // --- read model ---------------------------------------------------------------------------------------------

    private DataSyncDTO toDataSyncDTO(DataSync dataSync) {
        List<DataSyncElement> elements = dataSyncElementService.getByDataSyncId(dataSync.getId());

        Project project = projectService.getProject(dataSync.getProjectId());
        ProjectVersion lastPublishedProjectVersion = project.getLastPublishedProjectVersion();

        String draftWorkflowId = getVersionWorkflowId(project.getId(), project.getLastProjectVersion());

        boolean unpublishedChanges;
        int lastPublishedVersion;
        Instant publishedDate;

        if (lastPublishedProjectVersion == null) {
            unpublishedChanges = true;
            lastPublishedVersion = 0;
            publishedDate = null;
        } else {
            lastPublishedVersion = lastPublishedProjectVersion.getVersion();
            publishedDate = lastPublishedProjectVersion.getPublishedDate();

            String publishedWorkflowId = getVersionWorkflowId(project.getId(), lastPublishedVersion);

            unpublishedChanges = !Objects.equals(
                workflowService.getWorkflow(draftWorkflowId)
                    .getDefinition(),
                workflowService.getWorkflow(publishedWorkflowId)
                    .getDefinition());
        }

        List<Tag> tags = tagService.getTags(dataSync.getTagIds());

        return new DataSyncDTO(
            dataSync, elements, unpublishedChanges, lastPublishedVersion, publishedDate, tags,
            project.getVisibility(), draftWorkflowId);
    }

    private List<DataSync> visibleDataSyncs(long workspaceId) {
        List<DataSync> dataSyncs = dataSyncService.getDataSyncs(workspaceId);

        if (dataSyncs.isEmpty()) {
            return dataSyncs;
        }

        Set<Long> visibleProjectIds = projectVisibilityFilter.visibleProjectIds(
            dataSyncs.stream()
                .map(DataSync::getProjectId)
                .map(projectService::getProject)
                .toList());

        return dataSyncs.stream()
            .filter(dataSync -> visibleProjectIds.contains(dataSync.getProjectId()))
            .toList();
    }

    private static String slugify(String title) {
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }

        String lower = title.toLowerCase(Locale.ROOT);
        String collapsed = lower.replaceAll("[^a-z0-9_-]+", "-");
        String trimmed = collapsed.replaceAll("^-+|-+$", "");

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                "title '" + title + "' produces an empty slug — use letters, digits, hyphens, or underscores");
        }

        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    private String uniqueName(String baseName, Long workspaceId) {
        Set<String> existingNames = dataSyncService.getDataSyncs(workspaceId)
            .stream()
            .map(DataSync::getName)
            .collect(Collectors.toSet());

        if (!existingNames.contains(baseName)) {
            return baseName;
        }

        for (int suffix = 2;; suffix++) {
            String suffixText = "-" + suffix;
            String truncatedBase = baseName.length() > MAX_NAME_LENGTH - suffixText.length()
                ? baseName.substring(0, MAX_NAME_LENGTH - suffixText.length())
                : baseName;
            String candidate = truncatedBase + suffixText;

            if (!existingNames.contains(candidate)) {
                return candidate;
            }
        }
    }
}

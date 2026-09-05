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
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectVersion;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
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
import com.bytechef.commons.util.MapUtils;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.service.WorkflowNodeTestOutputService;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.configuration.workflow.WorkflowPreDeleteListener;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.tag.domain.Tag;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_PUBLISH')")
    @Transactional
    public int publishDataSync(long id, @Nullable String description) {
        DataSync dataSync = dataSyncService.getDataSync(id);

        validateForPublish(dataSync);

        // The draft must reflect the Data Sync's current rows before it is duplicated into the new published
        // version, or the published snapshot could lag the rows it was just validated against.
        regenerateAndSaveWorkflow(dataSync);

        return publishProjectVersion(dataSync.getProjectId(), description);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<DataSyncVersionDTO> getDataSyncVersions(long id) {
        DataSync dataSync = dataSyncService.getDataSync(id);
        Project project = projectService.getProject(dataSync.getProjectId());

        return project.getProjectVersions()
            .stream()
            .sorted(Comparator.comparingInt(ProjectVersion::getVersion)
                .reversed())
            .map(projectVersion -> new DataSyncVersionDTO(
                projectVersion.getVersion(), projectVersion.getDescription(), projectVersion.getPublishedDate(),
                String.valueOf(projectVersion.getStatus())))
            .toList();
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<DataSyncDeploymentDTO> getDataSyncDeployments(long workspaceId) {
        List<DataSyncDeploymentDTO> deployments = new ArrayList<>();

        for (DataSync dataSync : visibleDataSyncs(workspaceId)) {
            for (Environment environment : Environment.values()) {
                projectDeploymentService.fetchProjectDeployment(dataSync.getProjectId(), environment)
                    .ifPresent(projectDeployment -> deployments.add(toDeploymentDTO(dataSync, projectDeployment)));
            }
        }

        return deployments;
    }

    /**
     * Not {@code @Transactional}: {@code ProjectDeploymentFacadeImpl.createProjectDeploymentWorkflowJob} is declared
     * {@code Propagation.NEVER}, so an enclosing transaction here would make it throw.
     */
    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    public long runDataSyncDeployment(long id, long projectDeploymentId) {
        DataSync dataSync = dataSyncService.getDataSync(id);
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        if (!Objects.equals(projectDeployment.getProjectId(), dataSync.getProjectId())) {
            throw new ConfigurationException(
                "Deployment " + projectDeploymentId + " does not belong to Data Sync " + id,
                DataSyncErrorType.DEPLOYMENT_NOT_OWNED);
        }

        if (!projectDeployment.isEnabled()) {
            throw new ConfigurationException(
                "Deployment " + projectDeploymentId + " is disabled", DataSyncErrorType.DEPLOYMENT_DISABLED);
        }

        String workflowId = getVersionWorkflowId(dataSync.getProjectId(), projectDeployment.getProjectVersion());

        return projectDeploymentFacade.createProjectDeploymentWorkflowJob(projectDeploymentId, workflowId);
    }

    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<Tag> getDataSyncTags(long workspaceId) {
        List<Long> tagIds = visibleDataSyncs(workspaceId)
            .stream()
            .map(DataSync::getTagIds)
            .flatMap(List::stream)
            .distinct()
            .toList();

        return tagService.getTags(tagIds);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public void updateDataSyncTags(long id, List<Tag> tags) {
        List<Tag> savedTags = tags == null || tags.isEmpty() ? List.of() : tagService.save(tags);

        dataSyncService.update(id, savedTags.stream()
            .map(Tag::getId)
            .toList());
    }

    /**
     * No filter of its own: it aggregates over {@link #getDataSyncDeployments}, which is already filtered through
     * {@link #visibleDataSyncs}.
     */
    @Override
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DATA_SYNC_VIEW')")
    @Transactional(readOnly = true)
    public List<Tag> getDataSyncDeploymentTags(long workspaceId) {
        List<Long> tagIds = getDataSyncDeployments(workspaceId)
            .stream()
            .map(DataSyncDeploymentDTO::tags)
            .flatMap(List::stream)
            .map(Tag::getId)
            .distinct()
            .toList();

        return tagService.getTags(tagIds);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'DataSync', 'DATA_SYNC_EDIT')")
    @Transactional
    public void updateDataSyncDeploymentTags(long id, long projectDeploymentId, List<Tag> tags) {
        DataSync dataSync = dataSyncService.getDataSync(id);
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        if (!Objects.equals(projectDeployment.getProjectId(), dataSync.getProjectId())) {
            throw new ConfigurationException(
                "Deployment " + projectDeploymentId + " does not belong to Data Sync " + id,
                DataSyncErrorType.DEPLOYMENT_NOT_OWNED);
        }

        List<Tag> savedTags = tags == null || tags.isEmpty() ? List.of() : tagService.save(tags);

        projectDeploymentService.update(projectDeploymentId, savedTags.stream()
            .map(Tag::getId)
            .toList());
    }

    // --- publish validation -------------------------------------------------------------------------------------

    private void validateForPublish(DataSync dataSync) {
        List<DataSyncElement> elements = dataSyncElementService.getByDataSyncId(dataSync.getId());

        DataSyncElement source = elements.stream()
            .filter(element -> element.getKind() == Kind.SOURCE)
            .findFirst()
            .orElseThrow(() -> new ConfigurationException(
                "Data Sync " + dataSync.getId() + " has no source", DataSyncErrorType.SOURCE_MISSING));

        DataSyncElement destination = elements.stream()
            .filter(element -> element.getKind() == Kind.DESTINATION)
            .findFirst()
            .orElseThrow(() -> new ConfigurationException(
                "Data Sync " + dataSync.getId() + " has no destination", DataSyncErrorType.DESTINATION_MISSING));

        for (DataSyncElement element : List.of(source, destination)) {
            if (element.getConnectionId() != null) {
                continue;
            }

            ComponentDefinition componentDefinition = componentDefinitionService.getComponentDefinition(
                element.getComponentName(), element.getComponentVersion());

            if (componentDefinition != null && componentDefinition.isConnectionRequired()) {
                throw new ConfigurationException(
                    "Element " + element.getKind() + " of Data Sync " + dataSync.getId() + " needs a connection",
                    DataSyncErrorType.ELEMENT_CONNECTION_MISSING);
            }
        }

        if (dataSync.getTriggerType() == TriggerType.SCHEDULE) {
            String expression = MapUtils.getString(
                dataSync.getTriggerParameters(), DataSyncWorkflowGenerator.TRIGGER_PARAMETER_EXPRESSION, "");

            if (expression.isBlank()) {
                throw new ConfigurationException(
                    "Data Sync " + dataSync.getId() + " is scheduled but has no cron expression",
                    DataSyncErrorType.SCHEDULE_EXPRESSION_MISSING);
            }
        }
    }

    // --- publish --------------------------------------------------------------------------------------------------

    /**
     * Publishes {@code projectId}'s current draft version and duplicates every one of its workflows into the new
     * version, the same replicated {@code ProjectFacadeImpl.publishProject} body {@code AiAgentFacadeImpl} uses, rather
     * than delegating to {@code ProjectFacade.publishProject} — that facade method is gated for user-visible projects
     * and would reject the caller on a Data Sync's hidden backing project.
     */
    private int publishProjectVersion(long projectId, @Nullable String description) {
        int oldProjectVersion = projectService.getProject(projectId)
            .getLastProjectVersion();

        List<ProjectWorkflow> oldProjectWorkflows = projectWorkflowService.getProjectWorkflows(
            projectId, oldProjectVersion);

        int newProjectVersion = projectService.publishProject(projectId, description, false);

        for (ProjectWorkflow oldProjectWorkflow : oldProjectWorkflows) {
            String oldWorkflowId = oldProjectWorkflow.getWorkflowId();

            Workflow duplicatedWorkflow = workflowService.duplicateWorkflow(oldWorkflowId);

            oldProjectWorkflow.setProjectVersion(newProjectVersion);
            oldProjectWorkflow.setWorkflowId(duplicatedWorkflow.getId());

            projectWorkflowService.publishWorkflow(projectId, oldProjectVersion, oldWorkflowId, oldProjectWorkflow);

            workflowTestConfigurationService.updateWorkflowId(oldWorkflowId, duplicatedWorkflow.getId());
            workflowNodeTestOutputService.updateWorkflowId(oldWorkflowId, duplicatedWorkflow.getId());
        }

        return newProjectVersion;
    }

    // --- deployments ----------------------------------------------------------------------------------------------

    private DataSyncDeploymentDTO toDeploymentDTO(DataSync dataSync, ProjectDeployment projectDeployment) {
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows =
            projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeployment.getId());

        String workflowId = projectDeploymentWorkflows.isEmpty()
            ? getVersionWorkflowId(dataSync.getProjectId(), projectDeployment.getProjectVersion())
            : projectDeploymentWorkflows.get(0)
                .getWorkflowId();

        return new DataSyncDeploymentDTO(
            projectDeployment.getId(), projectDeployment.getName(), dataSync.getId(), dataSync.getTitle(),
            dataSync.getProjectId(), (int) projectDeployment.getEnvironmentId(), projectDeployment.isEnabled(),
            projectDeployment.getProjectVersion(), dataSync.getTriggerType(), workflowId,
            tagService.getTags(projectDeployment.getTagIds()),
            getLastExecutionDate(projectDeployment.getId(), projectDeploymentWorkflows));
    }

    /**
     * The deployment's most recent finished run, derived exactly as {@code AiAgentFacadeImpl} derives its own: the last
     * job recorded against the deployment for its workflows. A Data Sync deploys a single generated workflow, so this
     * is that workflow's last run.
     */
    private @Nullable Instant getLastExecutionDate(
        long projectDeploymentId, List<ProjectDeploymentWorkflow> projectDeploymentWorkflows) {

        List<String> workflowIds = projectDeploymentWorkflows.stream()
            .map(ProjectDeploymentWorkflow::getWorkflowId)
            .toList();

        if (workflowIds.isEmpty()) {
            return null;
        }

        return principalJobService.fetchLastWorkflowJobId(projectDeploymentId, workflowIds, PlatformType.AUTOMATION)
            .map(jobId -> {
                Job job = jobService.getJob(jobId);

                return job.getEndDate();
            })
            .orElse(null);
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

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

package com.bytechef.automation.configuration.facade;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.dto.JobParametersDTO;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflowConnection;
import com.bytechef.automation.configuration.domain.ProjectVersion;
import com.bytechef.automation.configuration.domain.ProjectWorkflow;
import com.bytechef.automation.configuration.domain.SystemProjects;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.Candidate;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.DeployedConnection;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.DeployedWorkflow;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.MissingInput;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.RemovedWorkflow;
import com.bytechef.automation.configuration.dto.DeployProjectResultDTO.UnresolvedConnection;
import com.bytechef.automation.configuration.dto.DeploymentConnectionChoiceDTO;
import com.bytechef.automation.configuration.dto.ProjectDeploymentDTO;
import com.bytechef.automation.configuration.dto.ProjectDeploymentWorkflowDTO;
import com.bytechef.automation.configuration.exception.ProjectDeploymentErrorType;
import com.bytechef.automation.configuration.listener.ProjectDeploymentDeleteEventListener;
import com.bytechef.automation.configuration.security.EnvironmentScopeFilter;
import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.service.PermissionService;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectDeploymentWorkflowService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.commons.util.CollectionUtils;
import com.bytechef.component.definition.TriggerDefinition.TriggerType;
import com.bytechef.component.definition.TriggerDefinition.WebhookEnableOutput;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.evaluator.Evaluator;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.domain.ConnectionDefinition;
import com.bytechef.platform.component.domain.TriggerDefinition;
import com.bytechef.platform.component.service.ConnectionDefinitionService;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.configuration.domain.ComponentConnection;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.HostedChatTriggers;
import com.bytechef.platform.configuration.domain.WorkflowTrigger;
import com.bytechef.platform.configuration.facade.ComponentConnectionFacade;
import com.bytechef.platform.configuration.service.EnvironmentService;
import com.bytechef.platform.connection.domain.AiProviderConnectionId;
import com.bytechef.platform.connection.domain.Connection;
import com.bytechef.platform.connection.domain.Connection.CredentialStatus;
import com.bytechef.platform.connection.dto.ConnectionDTO;
import com.bytechef.platform.connection.event.ConnectionWorkflowPausedEvent;
import com.bytechef.platform.connection.exception.ConnectionErrorType;
import com.bytechef.platform.connection.service.ConnectionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.definition.WorkflowNodeType;
import com.bytechef.platform.tag.domain.Tag;
import com.bytechef.platform.tag.service.TagService;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.facade.TriggerLifecycleFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TriggerExecutionService;
import com.bytechef.platform.workflow.execution.service.TriggerStateService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * @author Ivica Cardic
 */
@Service
@Transactional
public class ProjectDeploymentFacadeImpl implements ProjectDeploymentFacade {

    private static final String CONNECTION = "Connection";
    private static final String CONNECTION_USE = "CONNECTION_USE";
    private static final String MANUAL_TRIGGER_NAME = "manual";

    private static final Logger log = LoggerFactory.getLogger(ProjectDeploymentFacadeImpl.class);

    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConnectionService connectionService;
    private final Evaluator evaluator;
    private final EnvironmentService environmentService;
    private final PrincipalJobFacade principalJobFacade;
    private final PrincipalJobService principalJobService;
    private final JobFacade jobFacade;
    private final JobService jobService;
    private final List<ProjectDeploymentDeleteEventListener> projectDeploymentDeleteEventListeners;
    private final ProjectDeploymentService projectDeploymentService;
    private final ProjectDeploymentWorkflowService projectDeploymentWorkflowService;
    private final ProjectService projectService;
    private final EnvironmentScopeFilter environmentScopeFilter;
    private final ProjectVisibilityFilter projectVisibilityFilter;
    private final ProjectWorkflowService projectWorkflowService;
    private final TagService tagService;
    private final TriggerDefinitionService triggerDefinitionService;
    private final TriggerExecutionService triggerExecutionService;
    private final TriggerLifecycleFacade triggerLifecycleFacade;
    private final TriggerStateService triggerStateService;
    private final String webhookUrl;
    private final ComponentConnectionFacade componentConnectionFacade;
    private final WorkflowService workflowService;
    private final ConnectionDefinitionService connectionDefinitionService;
    private final PermissionService permissionService;
    private final WorkspaceConnectionFacade workspaceConnectionFacade;
    private final TransactionTemplate requiresNewTransactionTemplate;

    @SuppressFBWarnings("EI")
    public ProjectDeploymentFacadeImpl(
        ApplicationEventPublisher applicationEventPublisher, ConnectionService connectionService, Evaluator evaluator,
        EnvironmentScopeFilter environmentScopeFilter, EnvironmentService environmentService,
        PrincipalJobFacade principalJobFacade,
        PrincipalJobService principalJobService, JobFacade jobFacade, JobService jobService,
        List<ProjectDeploymentDeleteEventListener> projectDeploymentDeleteEventListeners,
        ProjectDeploymentService projectDeploymentService,
        ProjectDeploymentWorkflowService projectDeploymentWorkflowService, ProjectService projectService,
        ProjectVisibilityFilter projectVisibilityFilter, ProjectWorkflowService projectWorkflowService,
        TagService tagService, TriggerDefinitionService triggerDefinitionService,
        TriggerExecutionService triggerExecutionService,
        TriggerLifecycleFacade triggerLifecycleFacade, ApplicationProperties applicationProperties,
        ComponentConnectionFacade componentConnectionFacade, WorkflowService workflowService,
        @Lazy ConnectionDefinitionService connectionDefinitionService, @Lazy PermissionService permissionService,
        @Lazy WorkspaceConnectionFacade workspaceConnectionFacade, PlatformTransactionManager transactionManager,
        TriggerStateService triggerStateService) {

        this.applicationEventPublisher = applicationEventPublisher;
        this.connectionService = connectionService;
        this.evaluator = evaluator;
        this.environmentScopeFilter = environmentScopeFilter;
        this.environmentService = environmentService;
        this.principalJobFacade = principalJobFacade;
        this.principalJobService = principalJobService;
        this.jobFacade = jobFacade;
        this.jobService = jobService;
        this.projectDeploymentDeleteEventListeners = projectDeploymentDeleteEventListeners;
        this.projectDeploymentService = projectDeploymentService;
        this.projectDeploymentWorkflowService = projectDeploymentWorkflowService;
        this.projectService = projectService;
        this.projectVisibilityFilter = projectVisibilityFilter;
        this.projectWorkflowService = projectWorkflowService;
        this.tagService = tagService;
        this.triggerDefinitionService = triggerDefinitionService;
        this.triggerExecutionService = triggerExecutionService;
        this.triggerLifecycleFacade = triggerLifecycleFacade;
        this.triggerStateService = triggerStateService;
        this.webhookUrl = applicationProperties.getWebhookUrl();
        this.componentConnectionFacade = componentConnectionFacade;
        this.workflowService = workflowService;
        this.connectionDefinitionService = connectionDefinitionService;
        this.permissionService = permissionService;
        this.workspaceConnectionFacade = workspaceConnectionFacade;

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.requiresNewTransactionTemplate = transactionTemplate;
    }

    @Override
    // The whole DTO, not its projectId: the evaluator reads the target environment off it so that the role
    // checked is the one held in the environment being deployed into.
    @PreAuthorize("hasPermission(#projectDeploymentDTO, 'WORKFLOW_EDIT') and " +
        "hasPermission(#projectDeploymentDTO, 'DEPLOYMENT_PUSH')")
    public long createProjectDeployment(ProjectDeploymentDTO projectDeploymentDTO) {
        return createProjectDeployment(
            projectDeploymentDTO.toProjectDeployment(), CollectionUtils.map(
                projectDeploymentDTO.projectDeploymentWorkflows(),
                ProjectDeploymentWorkflowDTO::toProjectDeploymentWorkflow),
            projectDeploymentDTO.tags());
    }

    @Override
    @PreAuthorize("hasPermission(#projectDeployment.projectId, 'Project', 'DEPLOYMENT_PUSH')")
    public long createProjectDeployment(
        ProjectDeployment projectDeployment, String workflowId, List<ProjectDeploymentWorkflowConnection> connections) {

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setConnections(connections);
        projectDeploymentWorkflow.setInputs(Map.of());
        projectDeploymentWorkflow.setProjectDeploymentId(projectDeployment.getId());
        projectDeploymentWorkflow.setWorkflowId(workflowId);

        return createProjectDeployment(projectDeployment, List.of(projectDeploymentWorkflow), List.of());
    }

    @Override
    @PreAuthorize("hasPermission(#projectDeployment.projectId, 'Project', 'DEPLOYMENT_PUSH')")
    public long createProjectDeployment(
        ProjectDeployment projectDeployment, List<ProjectDeploymentWorkflow> projectDeploymentWorkflows,
        List<Tag> tags) {

        long projectId = Validate.notNull(projectDeployment.getProjectId(), "projectId");

        Project project = projectService.getProject(projectId);

        if (!project.isPublished()) {
            throw new ConfigurationException(
                "Project id=%s is not published".formatted(projectId),
                ProjectDeploymentErrorType.PROJECT_NOT_PUBLISHED);
        }

        if (project.getLastProjectVersion() == projectDeployment.getProjectVersion()) {
            throw new ConfigurationException(
                "Project version v=%s cannot be in DRAFT".formatted(projectDeployment.getProjectVersion()),
                ProjectDeploymentErrorType.INVALID_PROJECT_VERSION);
        }

        normalizeProjectDeploymentWorkflowIds(
            projectDeployment, projectDeploymentWorkflows, projectWorkflowService.getProjectWorkflows(projectId));

        if (!tags.isEmpty()) {
            projectDeployment.setTags(checkTags(tags));
        }

        projectDeployment = projectDeploymentService.create(projectDeployment);

        checkProjectDeploymentWorkflows(projectDeployment, -1, projectDeploymentWorkflows, List.of());

        return projectDeployment.getId();
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasPermission(#id, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    public long createProjectDeploymentWorkflowJob(Long id, String workflowId) {
        ProjectDeploymentWorkflow projectDeploymentWorkflow =
            projectDeploymentWorkflowService.getProjectDeploymentWorkflow(
                id, workflowId);

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(id);

        List<Long> connectionIds = projectDeploymentWorkflow.getConnections()
            .stream()
            .map(ProjectDeploymentWorkflowConnection::getConnectionId)
            .toList();

        List<Connection> inactiveConnections = connectionService.getInactiveConnections(connectionIds);

        if (!inactiveConnections.isEmpty()) {
            for (Connection connection : inactiveConnections) {
                applicationEventPublisher.publishEvent(
                    new ConnectionWorkflowPausedEvent(
                        connection.getId(),
                        Map.of(
                            "projectDeploymentId", id,
                            "workflowId", workflowId,
                            "connectionStatus", connection.getStatus()
                                .name())));
            }

            connectionService.validateConnectionsActive(connectionIds);
        }

        return principalJobFacade.createJob(
            new JobParametersDTO(
                workflowId, projectDeploymentWorkflow.getInputs(),
                Map.of("projectVersion", projectDeployment.getProjectVersion())),
            id, PlatformType.AUTOMATION);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    public void deleteProjectDeployment(long id) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(id);

        for (ProjectDeploymentDeleteEventListener deleteEventListener : projectDeploymentDeleteEventListeners) {
            deleteEventListener.onBeforeDeleteProjectDeployment(id);
        }

        changeWorkflowTriggers(workflowTriggerJournal -> {
            if (projectDeployment.isEnabled()) {
                enableProjectDeployment(projectDeployment.getId(), false, workflowTriggerJournal);
            }

            deleteProjectDeploymentRows(id);
        });

// TODO find a way to delete all tags not referenced anymore
//        project.getTagIds()
//            .forEach(tagService::delete);
    }

    @Override
    @PreAuthorize("hasResourceScopeInEnvironment(#projectId, 'Project', 'WORKFLOW_EDIT', " +
        "T(com.bytechef.platform.configuration.domain.Environment).DEVELOPMENT) and " +
        "(#projectDeploymentId == null or hasPermission(#projectDeploymentId, 'ProjectDeployment', " +
        "'DEPLOYMENT_EDIT')) and hasResourceScopeInEnvironment(#projectId, 'Project', 'DEPLOYMENT_PUSH', " +
        "T(com.bytechef.platform.configuration.domain.Environment).DEVELOPMENT)")
    public DeployProjectResultDTO deployProject(
        long projectId, @Nullable Long projectDeploymentId, List<String> enabledWorkflowUuids,
        List<DeploymentConnectionChoiceDTO> connections, Map<String, Map<String, ?>> inputs) {

        Project project = projectService.getProject(projectId);

        if (SystemProjects.isSystemProject(project)) {
            throw new NoSuchElementException();
        }

        ProjectDeployment projectDeployment = fetchDevelopmentProjectDeployment(projectId, projectDeploymentId);

        int projectVersion = getDeployableProjectVersion(project);

        List<ProjectWorkflow> projectWorkflows = projectWorkflowService.getProjectWorkflows(projectId, projectVersion);

        checkWorkflowUuids(projectId, projectVersion, projectWorkflows, enabledWorkflowUuids, connections, inputs);

        long workspaceId = Objects.requireNonNull(project.getWorkspaceId(), "workspaceId");
        Map<String, ProjectDeploymentWorkflow> currentProjectDeploymentWorkflows = projectDeployment == null
            ? Map.of() : getProjectDeploymentWorkflowsByUuid(projectDeployment);
        Map<String, String> draftWorkflowIds = getWorkflowIdsByUuid(projectId, project.getLastProjectVersion());
        Map<String, List<ConnectionDTO>> candidateConnections = new HashMap<>();

        List<DeployedWorkflow> deployedWorkflows = new ArrayList<>();
        List<MissingInput> missingInputs = new ArrayList<>();
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows = new ArrayList<>();
        List<UnresolvedConnection> unresolvedConnections = new ArrayList<>();

        for (ProjectWorkflow projectWorkflow : projectWorkflows) {
            String workflowUuid = projectWorkflow.getUuidAsString();
            Workflow workflow = workflowService.getWorkflow(projectWorkflow.getWorkflowId());
            ProjectDeploymentWorkflow currentProjectDeploymentWorkflow = currentProjectDeploymentWorkflows.get(
                workflowUuid);

            boolean enabled = enabledWorkflowUuids.contains(workflowUuid) ||
                (currentProjectDeploymentWorkflow != null && currentProjectDeploymentWorkflow.isEnabled());

            List<DeployedConnection> deployedConnections = new ArrayList<>();
            List<ProjectDeploymentWorkflowConnection> workflowConnections = new ArrayList<>();

            for (ComponentConnection componentConnection : componentConnectionFacade
                .getWorkflowComponentConnections(workflow.getId())) {

                List<ConnectionDTO> candidates = candidateConnections.computeIfAbsent(
                    componentConnection.componentName() + ":" + componentConnection.componentVersion(),
                    candidateKey -> getCandidateConnections(workspaceId, componentConnection));

                ConnectionDTO connection = chooseConnection(
                    workflowUuid, componentConnection, candidates, connections, currentProjectDeploymentWorkflow);

                if (connection == null) {
                    if (enabled && componentConnection.required()) {
                        unresolvedConnections.add(
                            toUnresolvedConnection(workflowUuid, workflow, componentConnection, candidates));
                    }

                    continue;
                }

                long connectionId = Objects.requireNonNull(connection.id(), "id");

                workflowConnections.add(
                    new ProjectDeploymentWorkflowConnection(
                        connectionId, componentConnection.key(), componentConnection.workflowNodeName()));
                deployedConnections.add(
                    new DeployedConnection(
                        componentConnection.workflowNodeName(), componentConnection.key(), connectionId,
                        connection.name()));
            }

            Map<String, ?> workflowInputs = resolveInputs(workflowUuid, inputs, currentProjectDeploymentWorkflow);

            if (enabled) {
                missingInputs.addAll(getMissingInputs(workflowUuid, workflow, workflowInputs));
            }

            projectDeploymentWorkflows.add(
                toProjectDeploymentWorkflow(workflow.getId(), enabled, workflowInputs, workflowConnections));
            deployedWorkflows.add(
                new DeployedWorkflow(
                    workflowUuid, workflow.getId(), getWorkflowName(workflow), enabled,
                    draftWorkflowIds.get(workflowUuid), deployedConnections));
        }

        checkConnectionChoicesMatchSlots(connections, deployedWorkflows);

        List<RemovedWorkflow> removedWorkflows = projectDeployment == null
            ? List.of() : getRemovedWorkflows(projectDeployment, projectWorkflows);

        if (!unresolvedConnections.isEmpty() || !missingInputs.isEmpty()) {
            return new DeployProjectResultDTO(
                projectDeployment == null ? null : projectDeployment.getId(), projectVersion, deployedWorkflows,
                removedWorkflows, unresolvedConnections, missingInputs);
        }

        long savedProjectDeploymentId = projectDeployment == null
            ? createDevelopmentProjectDeployment(project, projectVersion, projectDeploymentWorkflows)
            : redeployDevelopmentProjectDeployment(projectDeployment, projectVersion, projectDeploymentWorkflows);

        return new DeployProjectResultDTO(
            savedProjectDeploymentId, projectVersion, deployedWorkflows, removedWorkflows, List.of(), List.of());
    }

    @Override
    @PreAuthorize("hasPermission(#projectDeploymentId, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public void checkEnableProjectDeployment(long projectDeploymentId, boolean enable) {
        // The DEPLOYMENT_EDIT gate on this method is the whole check: enableProjectDeployment asks nothing more.
    }

    /**
     * Arms or disarms the triggers of the deployment's enabled workflows and stores the new state. When any of that
     * fails, or the transaction it ran in rolls back, whatever triggers it armed are disarmed and whatever it disarmed
     * are re-armed.
     */
    @Override
    @PreAuthorize("hasPermission(#projectDeploymentId, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    public void enableProjectDeployment(long projectDeploymentId, boolean enable) {
        changeWorkflowTriggers(
            workflowTriggerJournal -> enableProjectDeployment(projectDeploymentId, enable, workflowTriggerJournal));
    }

    /**
     * Enables or disables one workflow of a deployment on its own and stores the new state. When any of that fails,
     * whatever triggers it already armed are disarmed and whatever it already disarmed are re-armed. A disable stops
     * the jobs the workflow had running when its triggers were disarmed, and only once everything else succeeded.
     */
    @Override
    @PreAuthorize("hasPermission(#projectDeploymentId, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    public void enableProjectDeploymentWorkflow(long projectDeploymentId, String workflowId, boolean enable) {
        changeWorkflowTriggers(workflowTriggerJournal -> {
            ProjectDeploymentWorkflow projectDeploymentWorkflow = doEnableProjectDeploymentWorkflow(
                projectDeploymentId, workflowId, enable, workflowTriggerJournal);

            projectDeploymentWorkflowService.updateEnabled(projectDeploymentWorkflow.getId(), enable);
        });
    }

    /**
     * The embedded overload: resolves the deployment from a project id plus an environment, then does what the
     * three-argument one does.
     *
     * <p>
     * Ungated on purpose, and pinned that way by
     * {@code ProjectDeploymentFacadeAuthorizationTest.testEmbeddedEnableWorkflowOverloadIsNotGated}. Its only
     * production caller is the EE embedded {@code ConnectedUserProjectFacade}; embedded authorizes by api key and
     * connected user at its own REST boundary, not by workspace RBAC, and there is no platform workspace behind an
     * embedded caller for a {@code hasPermission} to resolve against. Gating it would fail closed for every embedded
     * deployment.
     *
     * <p>
     * The call below is an in-bean self-invocation, so it does not cross the security proxy and the
     * {@code hasPermission(#projectDeploymentId, 'ProjectDeployment', 'DEPLOYMENT_EDIT')} on the three-argument
     * overload does not fire. That is the behaviour this method wants, but it is worth saying that it holds by accident
     * of how Spring AOP works rather than by anything stated: were this ever changed to go through the proxy —
     * self-injection, {@code AopContext.currentProxy()}, or splitting the two overloads onto separate beans — the
     * embedded path would start being denied, and the test above is what would catch it. The audit in
     * {@code VisibilityBearingSurfaceAuditTest} cannot record this alongside the class's other ungated
     * internal-composition overloads: its {@code EXEMPTIONS} map is keyed by signatures the audit's own scan produces,
     * and this signature names no visibility-bearing type, so an entry for it would fail
     * {@code testEveryExemptionStillMatchesASurface}.
     */
    @Override
    public void enableProjectDeploymentWorkflow(
        long projectId, String workflowId, boolean enable, Environment environment) {

        long projectDeploymentId = projectDeploymentService.getProjectDeploymentId(projectId, environment);

        enableProjectDeploymentWorkflow(projectDeploymentId, workflowId, enable);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'ProjectDeployment', 'DEPLOYMENT_VIEW')")
    public ProjectDeploymentDTO getProjectDeployment(long id) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(id);

        List<ProjectWorkflow> projectWorkflows = projectWorkflowService.getProjectWorkflows(
            projectDeployment.getProjectId(), projectDeployment.getProjectVersion());
        List<String> workflowIds = projectWorkflowService.getProjectWorkflowIds(
            projectDeployment.getProjectId(), projectDeployment.getProjectVersion());

        return new ProjectDeploymentDTO(
            projectDeployment,
            CollectionUtils.map(
                CollectionUtils.filter(
                    projectDeploymentWorkflowService.getProjectDeploymentWorkflows(id),
                    projectDeploymentWorkflow -> workflowIds.contains(projectDeploymentWorkflow.getWorkflowId())),
                projectDeploymentWorkflow -> toProjectDeploymentWorkflowDTO(
                    projectDeploymentWorkflow, projectDeployment, projectWorkflows)),
            projectService.getProject(projectDeployment.getProjectId()),
            getProjectDeploymentLastExecutionDate(Validate.notNull(projectDeployment.getId(), "id")),
            tagService.getTags(projectDeployment.getTagIds()));
    }

    /**
     * The by-id read behind the {@code projectDeploymentWorkflow} GraphQL query, which until this gate was added was a
     * root query anyone authenticated in the tenant could call. What it hands back is not a row's metadata: the
     * {@code ProjectDeploymentWorkflow} GraphQL type exposes the deployment's {@code inputs} and {@code connections}
     * and resolves {@code projectWorkflow.workflow} into the whole workflow definition. The id it is keyed by is the
     * same string that forms the path segment of the workflow's static webhook URL, which is handed to third parties by
     * design &mdash; so an ungated read here turned "holds a webhook URL" into "reads a PRIVATE project's workflow".
     *
     * <p>
     * Two scopes rather than one, because two different things leak and they are separately grantable. A custom role
     * carries an arbitrary set of scope names, so {@code DEPLOYMENT_VIEW} without {@code WORKFLOW_VIEW} — and the
     * reverse — are both constructible: the first would read the workflow definition, the second the deployment's
     * inputs and connection bindings. The sibling by-id reads ask for exactly one each,
     * {@code hasPermission(#id, 'ProjectDeployment', 'DEPLOYMENT_VIEW')} on {@link #getProjectDeployment(long)} and
     * {@code hasPermission(#projectWorkflowId, 'ProjectWorkflow', 'WORKFLOW_VIEW')} on
     * {@code ProjectWorkflowFacadeImpl.getProjectWorkflow(long)}; this surface returns the union of what those two
     * return, so it asks for the union of what they ask for.
     *
     * <p>
     * Both are checked against {@code 'ProjectDeployment'} rather than one against {@code 'ProjectWorkflow'}, and that
     * is the stronger of the two spellings rather than a convenience: a deployment has a
     * {@code ResourceEnvironmentResolver} and a project workflow does not, so a deployment-keyed check is answered by
     * the role the caller holds in THAT environment instead of the union over the environments they can reach. The
     * visibility precondition is identical either way — both providers redirect to the owning project, and a
     * deployment's workflows belong to the project it deploys.
     */
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#workflowExecutionId.jobPrincipalId, 'ProjectDeployment', 'DEPLOYMENT_VIEW') and " +
        "hasPermission(#workflowExecutionId.jobPrincipalId, 'ProjectDeployment', 'WORKFLOW_VIEW')")
    public ProjectDeploymentWorkflow getProjectDeploymentWorkflow(WorkflowExecutionId workflowExecutionId) {
        long projectDeploymentId = workflowExecutionId.getJobPrincipalId();

        String workflowId = projectWorkflowService.getProjectWorkflowWorkflowId(
            projectDeploymentId, workflowExecutionId.getWorkflowUuid());

        return projectDeploymentWorkflowService.getProjectDeploymentWorkflow(projectDeploymentId, workflowId);
    }

    /**
     * Narrowed by {@link #filterOutSystemProjectDeployments}, the same helper
     * {@link #getWorkspaceProjectDeployments(long, long, Long, Long)} uses. This listing feeds the filter dropdown over
     * that one, so a tag it offers that matches no listed deployment is both a name disclosed from a project the caller
     * cannot see and a filter option that selects nothing.
     */
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DEPLOYMENT_VIEW')")
    public List<Tag> getProjectDeploymentTags(long workspaceId) {
        List<ProjectDeployment> projectDeployments = filterOutSystemProjectDeployments(
            projectDeploymentService.getProjectDeployments(null, null, null, null, workspaceId));

        return tagService.getTags(
            projectDeployments.stream()
                .map(ProjectDeployment::getTagIds)
                .flatMap(Collection::stream)
                .toList());
    }

    /**
     * The gate and the filter below are the two halves the by-id reads this listing leads to already compose. Until
     * they were added the query had neither: it named the project and labelled the workflow of every hosted-chat
     * deployment in ANY workspace, {@code PRIVATE} projects included &mdash; a listing looser than the
     * {@code hasPermission(#projectWorkflowId, 'ProjectWorkflow', 'WORKFLOW_VIEW')} that guards opening one of those
     * rows in the right panel. {@code WORKFLOW_VIEW} rather than the {@code DEPLOYMENT_VIEW} its neighbour
     * {@link #getWorkspaceProjectDeployments} uses, because what leaks is project names and workflow labels, and
     * because it is the scope the by-id read this row leads to asks for; both are VIEWER-rank, so no workspace member
     * loses a row they had.
     *
     * <p>
     * The visibility half is one batched {@link ProjectVisibilityFilter#visibleProjectIds(Collection)} call over the
     * projects already loaded for the listing, not a per-row check &mdash; a per-element check inside the loop below
     * would be an N+1 authorization storm on a surface built expressly to avoid one.
     *
     * <p>
     * Every consumer tolerates a shorter list: the launcher's Workflows cascade still renders, with its own reason ("No
     * deployed workflow with a chat trigger."), the {@code /automation/chats} sidebar falls back to its empty state,
     * and {@code useLiveWorkflowLabel} returns null, which suppresses one tooltip line.
     */
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'WORKFLOW_VIEW', #environmentId)")
    public List<ChatWorkflow> getWorkspaceChatWorkflows(long workspaceId, long environmentId) {
        Environment environment = environmentService.getEnvironment(environmentId);

        List<ProjectDeployment> projectDeployments = projectDeploymentService.getProjectDeployments(
            false, environment, null, null, workspaceId);

        if (projectDeployments.isEmpty()) {
            return List.of();
        }

        List<ProjectDeployment> enabledProjectDeployments = projectDeployments.stream()
            .filter(ProjectDeployment::isEnabled)
            .toList();

        if (enabledProjectDeployments.isEmpty()) {
            return List.of();
        }

        List<Long> projectDeploymentIds = enabledProjectDeployments.stream()
            .map(ProjectDeployment::getId)
            .toList();

        List<ProjectDeploymentWorkflow> enabledProjectDeploymentWorkflows = projectDeploymentWorkflowService
            .getProjectDeploymentWorkflows(projectDeploymentIds)
            .stream()
            .filter(ProjectDeploymentWorkflow::isEnabled)
            .toList();

        if (enabledProjectDeploymentWorkflows.isEmpty()) {
            return List.of();
        }

        List<String> workflowIds = enabledProjectDeploymentWorkflows.stream()
            .map(ProjectDeploymentWorkflow::getWorkflowId)
            .distinct()
            .toList();

        Map<String, Workflow> workflowMap = workflowService.getWorkflows(workflowIds)
            .stream()
            .collect(Collectors.toMap(Workflow::getId, Function.identity()));

        Map<String, ProjectWorkflow> projectWorkflowMap = projectWorkflowService
            .getWorkflowProjectWorkflows(workflowIds)
            .stream()
            .collect(Collectors.toMap(ProjectWorkflow::getWorkflowId, Function.identity()));

        // An AI agent's hosted chat is listed separately by AiAgentFacade.getWorkspaceChatAgents, so its generated
        // workflow is dropped here rather than surfaced twice. The same holds for any other generated workflow, such
        // as a Data Sync's.
        List<ProjectDeploymentWorkflow> userProjectDeploymentWorkflows = enabledProjectDeploymentWorkflows.stream()
            .filter(projectDeploymentWorkflow -> {
                ProjectWorkflow projectWorkflow = projectWorkflowMap.get(projectDeploymentWorkflow.getWorkflowId());

                return projectWorkflow == null || !projectWorkflow.getType()
                    .isGenerated();
            })
            .toList();

        List<Project> projects = projectService.getProjects(
            enabledProjectDeployments.stream()
                .map(ProjectDeployment::getProjectId)
                .distinct()
                .toList());

        Map<Long, Project> projectMap = projects.stream()
            .collect(Collectors.toMap(project -> Objects.requireNonNull(project.getId(), "id"), Function.identity()));

        Set<Long> visibleProjectIds = projectVisibilityFilter.visibleProjectIds(projects);

        Map<Long, ProjectDeployment> projectDeploymentMap = enabledProjectDeployments.stream()
            .collect(Collectors.toMap(ProjectDeployment::getId, Function.identity()));

        Map<TriggerDefinitionKey, TriggerDefinition> triggerDefinitionMap = new HashMap<>();

        List<ChatWorkflow> chatWorkflows = new ArrayList<>();

        for (ProjectDeploymentWorkflow projectDeploymentWorkflow : userProjectDeploymentWorkflows) {
            Workflow workflow = workflowMap.get(projectDeploymentWorkflow.getWorkflowId());

            if (workflow == null) {
                continue;
            }

            List<WorkflowTrigger> workflowTriggers = WorkflowTrigger.of(workflow);

            if (!HostedChatTriggers.hasHostedChatTrigger(workflowTriggers)) {
                continue;
            }

            String webhookExecutionId = resolveStaticWebhookExecutionId(
                workflow, workflowTriggers, projectDeploymentWorkflow, projectWorkflowMap, triggerDefinitionMap);

            if (webhookExecutionId == null) {
                continue;
            }

            ProjectDeployment projectDeployment =
                projectDeploymentMap.get(projectDeploymentWorkflow.getProjectDeploymentId());

            if (projectDeployment == null) {
                continue;
            }

            Project project = projectMap.get(projectDeployment.getProjectId());

            // A workflow is only as visible as the project that owns it. A project row that is gone cannot be
            // checked at all, so it is skipped rather than listed under a placeholder name as it used to be —
            // an unnameable project is not one to advertise.
            if (project == null || !visibleProjectIds.contains(project.getId())) {
                continue;
            }

            // projectWorkflowMap is keyed on workflow.id (see workflowIds loader above) and is the same
            // lookup resolveStaticWebhookExecutionId already did successfully — otherwise we would have
            // continued past that step. Reusing it here gives the client the (projectId, projectWorkflowId)
            // pair it needs to open the workflow's definition tab in the AI Hub right panel without an
            // extra round-trip.
            ProjectWorkflow projectWorkflow = projectWorkflowMap.get(workflow.getId());

            chatWorkflows.add(
                new ChatWorkflow(
                    projectDeploymentWorkflow.getProjectDeploymentId(), projectDeployment.getProjectId(),
                    project.getName(), projectWorkflow.getId(), webhookExecutionId, workflow.getId(),
                    workflow.getLabel() == null ? "Untitled Workflow" : workflow.getLabel()));
        }

        return chatWorkflows;
    }

    /**
     * Serves the GraphQL {@code workspaceProjectDeployments} listing. Its gate and its filtering used to live in
     * {@code ProjectDeploymentGraphQlController}, which read the rows straight off {@code ProjectDeploymentService} —
     * past this facade, and so past the gate and the filters its REST twin below had all along.
     */
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#workspaceId, 'DEPLOYMENT_VIEW', #environmentId)")
    public List<ProjectDeployment> getWorkspaceProjectDeployments(
        long workspaceId, long environmentId, Long projectId, Long tagId) {

        Environment environment = environmentService.getEnvironment(environmentId);

        return filterOutSystemProjectDeployments(
            projectDeploymentService.getProjectDeployments(false, environment, projectId, tagId, workspaceId));
    }

    /**
     * {@code environmentId} is nullable here, unlike on the four-argument sibling above, so the gate cannot always
     * answer the environment question and the body finishes it. With an environment named the gate has checked it and
     * the query returns that environment alone; with none named the gate correctly permits the call and the query would
     * otherwise return every environment in the workspace, including ones the caller holds no role in. See
     * {@link EnvironmentScopeFilter}.
     */
    @Override
    @PreAuthorize("hasWorkspaceScopeInEnvironmentId(#id, 'DEPLOYMENT_VIEW', #environmentId)")
    public List<ProjectDeploymentDTO> getWorkspaceProjectDeployments(
        long id, Long environmentId, Long projectId, Long tagId, boolean includeAllFields) {

        Environment environment = environmentId == null ? null : environmentService.getEnvironment(environmentId);

        // A deployment is only as visible as the project it deploys: the auto-provisioned system projects behind
        // Knowledge Base / Context Store sync (and the embedded catalog) each own a deployment the user never made
        // and cannot meaningfully act on. See SystemProjects.
        List<ProjectDeployment> projectDeployments = filterOutSystemProjectDeployments(
            projectDeploymentService.getProjectDeployments(false, environment, projectId, tagId, id));

        if (environment == null) {
            projectDeployments = environmentScopeFilter.filterByEnvironment(
                id, "DEPLOYMENT_VIEW", projectDeployments, ProjectDeployment::getEnvironment);
        }

        if (includeAllFields) {
            List<ProjectDeploymentWorkflow> projectDeploymentWorkflows = projectDeploymentWorkflowService
                .getProjectDeploymentWorkflows(CollectionUtils.map(projectDeployments, ProjectDeployment::getId));
            List<Project> projects = getProjects(projectDeployments);
            List<Tag> tags = getTags(projectDeployments);

            List<Long> projectIds = projectDeployments.stream()
                .map(ProjectDeployment::getProjectId)
                .distinct()
                .toList();

            List<ProjectWorkflow> allProjectWorkflows = projectWorkflowService.getProjectWorkflows(projectIds);

            return CollectionUtils.map(
                projectDeployments,
                projectDeployment -> {
                    Project project = CollectionUtils.getFirst(
                        projects, curProject -> Objects.equals(curProject.getId(), projectDeployment.getProjectId()));

                    List<String> workflowIds = allProjectWorkflows.stream()
                        .filter(projectWorkflow -> Objects.equals(
                            projectWorkflow.getProjectId(), projectDeployment.getProjectId()) &&
                            projectWorkflow.getProjectVersion() == projectDeployment.getProjectVersion())
                        .map(ProjectWorkflow::getWorkflowId)
                        .toList();

                    List<ProjectWorkflow> deploymentProjectWorkflows = allProjectWorkflows.stream()
                        .filter(projectWorkflow -> Objects.equals(
                            projectWorkflow.getProjectId(), projectDeployment.getProjectId()))
                        .toList();

                    return new ProjectDeploymentDTO(
                        projectDeployment,
                        CollectionUtils.map(
                            CollectionUtils.filter(
                                projectDeploymentWorkflows,
                                projectDeploymentWorkflow -> Objects.equals(
                                    projectDeploymentWorkflow.getProjectDeploymentId(), projectDeployment.getId()) &&
                                    workflowIds.contains(projectDeploymentWorkflow.getWorkflowId())),
                            projectDeploymentWorkflow -> toProjectDeploymentWorkflowDTO(
                                projectDeploymentWorkflow, projectDeployment, deploymentProjectWorkflows)),
                        project,
                        getProjectDeploymentLastExecutionDate(Validate.notNull(projectDeployment.getId(), "id")),
                        filterTags(tags, projectDeployment));
                });
        } else {
            return CollectionUtils.map(projectDeployments, ProjectDeploymentDTO::new);
        }
    }

    @Override
    @PreAuthorize("hasPermission(#projectDeploymentDTO.id, 'ProjectDeployment', 'DEPLOYMENT_EDIT') and " +
        "hasPermission(#projectDeploymentDTO, 'DEPLOYMENT_PUSH')")
    public void updateProjectDeployment(ProjectDeploymentDTO projectDeploymentDTO) {
        updateProjectDeployment(
            projectDeploymentDTO.toProjectDeployment(),
            CollectionUtils.map(
                projectDeploymentDTO.projectDeploymentWorkflows(),
                ProjectDeploymentWorkflowDTO::toProjectDeploymentWorkflow),
            projectDeploymentDTO.tags());
    }

    @Override
    @PreAuthorize("hasPermission(#projectId, 'Project', 'DEPLOYMENT_PUSH')")
    public void updateProjectDeployment(
        long projectId, int projectVersion, String workflowUuid,
        List<ProjectDeploymentWorkflowConnection> connections, Long environmentId) {

        Environment environment = environmentId == null ? null : environmentService.getEnvironment(environmentId);

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(
            projectDeploymentService.getProjectDeploymentId(projectId, environment));

        projectDeployment.setProjectVersion(projectVersion);

        List<ProjectDeploymentWorkflow> oldProjectDeploymentWorkflows = projectDeploymentWorkflowService
            .getProjectDeploymentWorkflows(projectDeployment.getId());

        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows = projectWorkflowService
            .getProjectWorkflows(projectDeployment.getProjectId(), projectVersion)
            .stream()
            .map(curProjectWorkflow -> {
                ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

                projectDeploymentWorkflow.setProjectDeploymentId(projectId);
                projectDeploymentWorkflow.setWorkflowId(curProjectWorkflow.getWorkflowId());

                if (Objects.equals(curProjectWorkflow.getUuidAsString(), workflowUuid)) {
                    projectDeploymentWorkflow.setConnections(connections);
                    projectDeploymentWorkflow.setEnabled(
                        getFirst(curProjectWorkflow, oldProjectDeploymentWorkflows, projectDeployment)
                            .map(ProjectDeploymentWorkflow::isEnabled)
                            .orElse(false));
                    projectDeploymentWorkflow.setInputs(Map.of());
                } else {
                    return getFirst(curProjectWorkflow, oldProjectDeploymentWorkflows, projectDeployment)
                        .map(curProjectDeploymentWorkflow -> {
                            projectDeploymentWorkflow.setConnections(curProjectDeploymentWorkflow.getConnections());
                            projectDeploymentWorkflow.setEnabled(curProjectDeploymentWorkflow.isEnabled());
                            projectDeploymentWorkflow.setInputs(curProjectDeploymentWorkflow.getInputs());

                            return projectDeploymentWorkflow;
                        })
                        .orElse(projectDeploymentWorkflow);
                }

                return projectDeploymentWorkflow;
            })
            .toList();

        updateProjectDeployment(projectDeployment, projectDeploymentWorkflows, List.of());
    }

    @Override
    @PreAuthorize("hasPermission(#projectDeployment.projectId, 'Project', 'DEPLOYMENT_PUSH')")
    public void updateProjectDeployment(
        ProjectDeployment projectDeployment, List<ProjectDeploymentWorkflow> projectDeploymentWorkflows,
        List<Tag> tags) {

        if (!tags.isEmpty()) {
            projectDeployment.setTags(tags);
        }

        ProjectDeployment oldProjectDeployment = projectDeploymentService.getProjectDeployment(
            projectDeployment.getId());

        List<ProjectWorkflow> allProjectWorkflows = projectWorkflowService.getProjectWorkflows(
            projectDeployment.getProjectId());

        normalizeProjectDeploymentWorkflowIds(projectDeployment, projectDeploymentWorkflows, allProjectWorkflows);

        projectDeploymentService.update(projectDeployment);

        checkProjectDeploymentWorkflows(
            projectDeployment, oldProjectDeployment.getProjectVersion(), projectDeploymentWorkflows,
            allProjectWorkflows);
    }

    @Override
    @PreAuthorize("hasPermission(#id, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    public void updateProjectDeploymentTags(long id, List<Tag> tags) {
        projectDeploymentService.update(id, CollectionUtils.map(checkTags(tags), Tag::getId));
    }

    /**
     * The gate authorizes {@code projectDeploymentId}, but the write is keyed by {@code id} -- a DIFFERENT field of the
     * same caller-supplied object. {@code ProjectDeploymentWorkflowServiceImpl.update} loads the row by {@code getId()}
     * and overwrites its connections, enabled flag, inputs and workflowId, and that service carries no
     * {@code @PreAuthorize} of its own, so without the binding check below nothing tied the row being written to the
     * deployment being authorized.
     *
     * <p>
     * Directly expressible rather than theoretical:
     * {@code ProjectDeploymentApiController.updateProjectDeploymentWorkflow} binds the first path segment to
     * {@code projectDeploymentId} and the second to {@code id}, and the two are independent. A caller holding
     * {@code DEPLOYMENT_EDIT} on ANY one deployment could therefore name their own deployment in segment one and any
     * {@code project_deployment_workflow} row in the tenant in segment two -- including a tenant admin's production
     * row. {@code validateProjectDeploymentWorkflow} is no obstacle: it short-circuits entirely when {@code enabled} is
     * false, so disabling somebody else's workflow was unvalidated.
     *
     * <p>
     * This is the only one of the nine {@code 'ProjectDeployment'} gates on this class with that shape; the other eight
     * act solely on the deployment they name.
     *
     * <p>
     * The check is here rather than in the {@code @PreAuthorize} expression so it holds for every caller of this facade
     * rather than for one controller, and so the gate and the binding it depends on are read together. An unknown
     * {@code id} is denied with the SAME message as a mismatched one, so this does not become an existence oracle for
     * rows the caller cannot see.
     */
    @Override
    @PreAuthorize("hasPermission(#projectDeploymentWorkflow.projectDeploymentId, 'ProjectDeployment', 'DEPLOYMENT_EDIT')")
    public void updateProjectDeploymentWorkflow(ProjectDeploymentWorkflow projectDeploymentWorkflow) {
        validateProjectDeploymentWorkflowBinding(projectDeploymentWorkflow);

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(
            projectDeploymentWorkflow.getProjectDeploymentId());

        validateDeploymentConnectionEnvironments(
            projectDeploymentWorkflow.getConnections(), projectDeployment.getEnvironment());

        validateProjectDeploymentWorkflow(projectDeploymentWorkflow);

        projectDeploymentWorkflowService.update(projectDeploymentWorkflow);
    }

    /**
     * Points every workflow row at the workflow of the deployed project version: a row may name the same workflow (by
     * uuid) in another version of the project. A workflow id that is not part of the project, or a workflow missing
     * from the deployed version, is rejected.
     */
    private static void normalizeProjectDeploymentWorkflowIds(
        ProjectDeployment projectDeployment, List<ProjectDeploymentWorkflow> projectDeploymentWorkflows,
        List<ProjectWorkflow> allProjectWorkflows) {

        for (ProjectDeploymentWorkflow projectDeploymentWorkflow : projectDeploymentWorkflows) {
            String workflowId = projectDeploymentWorkflow.getWorkflowId();

            ProjectWorkflow projectWorkflow = allProjectWorkflows.stream()
                .filter(curProjectWorkflow -> Objects.equals(curProjectWorkflow.getWorkflowId(), workflowId))
                .findFirst()
                .orElseThrow(() -> new ConfigurationException(
                    "Workflow id=%s does not belong to project id=%s".formatted(
                        workflowId, projectDeployment.getProjectId()),
                    ProjectDeploymentErrorType.INVALID_PROJECT_WORKFLOW));

            String deployedWorkflowId = allProjectWorkflows.stream()
                .filter(curProjectWorkflow -> Objects.equals(
                    curProjectWorkflow.getUuidAsString(), projectWorkflow.getUuidAsString()) &&
                    curProjectWorkflow.getProjectVersion() == projectDeployment.getProjectVersion())
                .map(ProjectWorkflow::getWorkflowId)
                .findFirst()
                .orElseThrow(() -> new ConfigurationException(
                    "Workflow id=%s does not exist in project version v=%s".formatted(
                        workflowId, projectDeployment.getProjectVersion()),
                    ProjectDeploymentErrorType.INVALID_PROJECT_WORKFLOW));

            projectDeploymentWorkflow.setWorkflowId(deployedWorkflowId);
        }
    }

    /**
     * Asserts that the persisted {@code ProjectDeploymentWorkflow} named by {@code id} really does belong to the
     * {@code projectDeploymentId} the {@code @PreAuthorize} on {@link #updateProjectDeploymentWorkflow} authorized. See
     * that method for why the two can otherwise disagree.
     */
    private void validateProjectDeploymentWorkflowBinding(ProjectDeploymentWorkflow projectDeploymentWorkflow) {
        Long projectDeploymentId = projectDeploymentWorkflow.getProjectDeploymentId();
        Long id = projectDeploymentWorkflow.getId();

        if (id == null) {
            throw new AccessDeniedException(toProjectDeploymentWorkflowBindingMessage(null, projectDeploymentId));
        }

        ProjectDeploymentWorkflow curProjectDeploymentWorkflow;

        try {
            curProjectDeploymentWorkflow = projectDeploymentWorkflowService.getProjectDeploymentWorkflow(id);
        } catch (NoSuchElementException exception) {
            throw new AccessDeniedException(toProjectDeploymentWorkflowBindingMessage(id, projectDeploymentId));
        }

        if (!Objects.equals(curProjectDeploymentWorkflow.getProjectDeploymentId(), projectDeploymentId)) {
            throw new AccessDeniedException(toProjectDeploymentWorkflowBindingMessage(id, projectDeploymentId));
        }
    }

    private static String toProjectDeploymentWorkflowBindingMessage(Long id, Long projectDeploymentId) {
        return "Project deployment workflow %s does not belong to project deployment %s".formatted(
            id, projectDeploymentId);
    }

    /**
     * Creates, updates and deletes the deployment's workflow rows and, for an enabled deployment, moves the external
     * trigger registrations along with them. Every registration this call arms or disarms is journaled, and when the
     * call throws or its transaction rolls back, the journal is undone in reverse order -- armed triggers are disarmed,
     * disarmed ones re-armed -- so the registrations match the rolled-back rows again. The jobs every workflow it
     * disables or removes had running when that workflow was disarmed are stopped only after all of that has succeeded,
     * so a failed call stops none.
     */
    private void checkProjectDeploymentWorkflows(
        ProjectDeployment projectDeployment, int oldProjectVersion,
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows, List<ProjectWorkflow> allProjectWorkflows) {

        changeWorkflowTriggers(
            workflowTriggerJournal -> checkProjectDeploymentWorkflows(
                projectDeployment, oldProjectVersion, projectDeploymentWorkflows, allProjectWorkflows,
                workflowTriggerJournal));
    }

    /**
     * Runs {@code triggerChange} against a fresh journal. Inside a transaction the journal is handed to that
     * transaction, whether the change returns or throws: a rollback undoes its trigger changes, latest first, in a new
     * transaction once the rolled-back rows are back, and a commit stops the jobs of the workflows a change that
     * returned disabled. Outside a transaction a change that throws is undone at once, latest first, before the
     * original exception is rethrown, and the jobs of one that returned are stopped at once. A stop that fails is
     * logged and does not fail the call.
     */
    private void changeWorkflowTriggers(Consumer<WorkflowTriggerJournal> triggerChange) {
        WorkflowTriggerJournal workflowTriggerJournal = new WorkflowTriggerJournal();
        boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();

        try {
            triggerChange.accept(workflowTriggerJournal);
        } catch (RuntimeException exception) {
            if (synchronizationActive) {
                workflowTriggerJournal.discardDisabledWorkflows();

                registerJournalSynchronization(workflowTriggerJournal);
            } else {
                workflowTriggerJournal.undo(exception);
            }

            throw exception;
        }

        if (synchronizationActive) {
            registerJournalSynchronization(workflowTriggerJournal);
        } else {
            workflowTriggerJournal.stopRunningJobs();
        }
    }

    private void registerJournalSynchronization(WorkflowTriggerJournal workflowTriggerJournal) {
        if (workflowTriggerJournal.isEmpty()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
            new WorkflowTriggerJournalSynchronization(workflowTriggerJournal, getNextJournalSynchronizationOrder()));
    }

    /**
     * Each journal handed to the same transaction is ordered ahead of the ones handed to it before, so that a rollback
     * undoes the trigger changes of several calls latest first, as one journal undoes its own.
     */
    private static int getNextJournalSynchronizationOrder() {
        List<TransactionSynchronization> transactionSynchronizations =
            TransactionSynchronizationManager.getSynchronizations();

        long journalSynchronizationCount = transactionSynchronizations.stream()
            .filter(WorkflowTriggerJournalSynchronization.class::isInstance)
            .count();

        return Ordered.LOWEST_PRECEDENCE - Math.toIntExact(journalSynchronizationCount);
    }

    private void checkProjectDeploymentWorkflows(
        ProjectDeployment projectDeployment, int oldProjectVersion,
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows, List<ProjectWorkflow> allProjectWorkflows,
        WorkflowTriggerJournal workflowTriggerJournal) {

        List<ProjectDeploymentWorkflow> oldProjectDeploymentWorkflows = List.of();

        if (oldProjectVersion != -1) {
            oldProjectDeploymentWorkflows = projectDeploymentWorkflowService.getProjectDeploymentWorkflows(
                projectDeployment.getId());
        }

        for (ProjectDeploymentWorkflow projectDeploymentWorkflow : projectDeploymentWorkflows) {
            ProjectDeploymentWorkflow oldProjectDeploymentWorkflow = null;

            if (oldProjectVersion != -1) {
                String workflowUuid = allProjectWorkflows.stream()
                    .filter(curProjectWorkflow -> Objects.equals(
                        curProjectWorkflow.getWorkflowId(), projectDeploymentWorkflow.getWorkflowId()))
                    .findFirst()
                    .map(ProjectWorkflow::getUuidAsString)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Project workflow with workflowId=%s not found".formatted(
                            projectDeploymentWorkflow.getWorkflowId())));

                String oldWorkflowId = allProjectWorkflows.stream()
                    .filter(curProjectWorkflow -> Objects.equals(
                        curProjectWorkflow.getUuidAsString(), workflowUuid) &&
                        curProjectWorkflow.getProjectVersion() == oldProjectVersion)
                    .map(ProjectWorkflow::getWorkflowId)
                    .findFirst()
                    .orElse(null);

                if (oldWorkflowId != null) {
                    oldProjectDeploymentWorkflow = oldProjectDeploymentWorkflows.stream()
                        .filter(
                            curProjectDeploymentWorkflow -> Objects.equals(curProjectDeploymentWorkflow.getWorkflowId(),
                                oldWorkflowId))
                        .findFirst()
                        .orElse(null);
                }
            }

            validateProjectDeploymentWorkflow(projectDeploymentWorkflow);
            validateDeploymentConnectionEnvironments(
                projectDeploymentWorkflow.getConnections(), projectDeployment.getEnvironment());

            if (oldProjectDeploymentWorkflow == null) {
                projectDeploymentWorkflow.setProjectDeploymentId(projectDeployment.getId());

                projectDeploymentWorkflowService.create(projectDeploymentWorkflow);

                if (projectDeployment.isEnabled() && projectDeploymentWorkflow.isEnabled()) {
                    ProjectDeploymentWorkflow enabledProjectDeploymentWorkflow = doEnableProjectDeploymentWorkflow(
                        projectDeployment.getId(), projectDeploymentWorkflow.getWorkflowId(), true,
                        workflowTriggerJournal);

                    projectDeploymentWorkflowService.updateEnabled(enabledProjectDeploymentWorkflow.getId(), true);
                }
            } else {
                String oldWorkflowId = oldProjectDeploymentWorkflow.getWorkflowId();
                boolean wasEnabled = oldProjectDeploymentWorkflow.isEnabled();

                oldProjectDeploymentWorkflow.setConnections(projectDeploymentWorkflow.getConnections());
                oldProjectDeploymentWorkflow.setEnabled(projectDeploymentWorkflow.isEnabled());
                oldProjectDeploymentWorkflow.setInputs(projectDeploymentWorkflow.getInputs());
                oldProjectDeploymentWorkflow.setWorkflowId(projectDeploymentWorkflow.getWorkflowId());

                if (projectDeploymentWorkflow.isEnabled()) {
                    if (projectDeployment.isEnabled() && wasEnabled) {
                        doEnableProjectDeploymentWorkflow(
                            projectDeployment.getId(), oldWorkflowId, false, workflowTriggerJournal);
                    }

                    projectDeploymentWorkflowService.update(oldProjectDeploymentWorkflow);

                    if (projectDeployment.isEnabled()) {
                        doEnableProjectDeploymentWorkflow(
                            projectDeployment.getId(), projectDeploymentWorkflow.getWorkflowId(), true,
                            workflowTriggerJournal);
                    }
                } else {
                    if (wasEnabled) {
                        doEnableProjectDeploymentWorkflow(
                            projectDeployment.getId(), oldWorkflowId, false, workflowTriggerJournal);
                    }

                    projectDeploymentWorkflowService.update(oldProjectDeploymentWorkflow);
                }
            }
        }

        for (ProjectDeploymentWorkflow oldProjectDeploymentWorkflow : oldProjectDeploymentWorkflows) {
            String workflowUuid = allProjectWorkflows.stream()
                .filter(curProjectWorkflow -> Objects.equals(
                    curProjectWorkflow.getWorkflowId(), oldProjectDeploymentWorkflow.getWorkflowId()))
                .findFirst()
                .map(ProjectWorkflow::getUuidAsString)
                .orElseThrow(() -> new IllegalArgumentException(
                    "Project workflow with workflowId=%s not found".formatted(
                        oldProjectDeploymentWorkflow.getWorkflowId())));

            String workflowId = allProjectWorkflows.stream()
                .filter(curProjectWorkflow -> Objects.equals(
                    curProjectWorkflow.getUuidAsString(), workflowUuid) &&
                    curProjectWorkflow.getProjectVersion() == projectDeployment.getProjectVersion())
                .findFirst()
                .map(ProjectWorkflow::getWorkflowId)
                .orElse(null);

            if (workflowId == null || CollectionUtils.noneMatch(
                projectDeploymentWorkflows,
                projectDeploymentWorkflow -> Objects.equals(projectDeploymentWorkflow.getWorkflowId(), workflowId))) {

                if (oldProjectDeploymentWorkflow.isEnabled()) {
                    doEnableProjectDeploymentWorkflow(
                        projectDeployment.getId(), oldProjectDeploymentWorkflow.getWorkflowId(), false,
                        workflowTriggerJournal);
                }

                projectDeploymentWorkflowService.delete(oldProjectDeploymentWorkflow.getId());
            }
        }
    }

    private void checkConnectionChoicesMatchSlots(
        List<DeploymentConnectionChoiceDTO> connectionChoices, List<DeployedWorkflow> deployedWorkflows) {

        Set<List<String>> chosenSlots = new HashSet<>();

        for (DeploymentConnectionChoiceDTO connectionChoice : connectionChoices) {
            boolean firstChoiceForSlot = chosenSlots.add(
                Arrays.asList(
                    connectionChoice.workflowUuid(), connectionChoice.workflowNodeName(), connectionChoice.key()));

            if (!firstChoiceForSlot) {
                throw new ConfigurationException(
                    "Workflow %s has several connection choices for slot %s/%s; pass one".formatted(
                        connectionChoice.workflowUuid(), connectionChoice.workflowNodeName(), connectionChoice.key()),
                    ProjectDeploymentErrorType.INVALID_CONNECTION_CHOICE);
            }

            boolean matched = deployedWorkflows.stream()
                .filter(deployedWorkflow -> Objects.equals(
                    deployedWorkflow.workflowUuid(), connectionChoice.workflowUuid()))
                .flatMap(deployedWorkflow -> deployedWorkflow.connections()
                    .stream())
                .anyMatch(deployedConnection -> Objects.equals(
                    deployedConnection.workflowNodeName(), connectionChoice.workflowNodeName()) &&
                    Objects.equals(deployedConnection.key(), connectionChoice.key()));

            if (!matched) {
                throw new ConfigurationException(
                    "Workflow %s has no connection slot %s/%s".formatted(
                        connectionChoice.workflowUuid(), connectionChoice.workflowNodeName(), connectionChoice.key()),
                    ProjectDeploymentErrorType.INVALID_CONNECTION_CHOICE);
            }
        }
    }

    private static void checkWorkflowUuids(
        long projectId, int projectVersion, List<ProjectWorkflow> projectWorkflows, List<String> enabledWorkflowUuids,
        List<DeploymentConnectionChoiceDTO> connectionChoices, Map<String, Map<String, ?>> inputs) {

        Set<String> workflowUuids = projectWorkflows.stream()
            .map(ProjectWorkflow::getUuidAsString)
            .collect(Collectors.toCollection(TreeSet::new));

        List<String> unknownWorkflowUuids = Stream.of(
            enabledWorkflowUuids.stream(),
            connectionChoices.stream()
                .map(DeploymentConnectionChoiceDTO::workflowUuid),
            inputs.keySet()
                .stream())
            .flatMap(Function.identity())
            .filter(workflowUuid -> workflowUuid == null || !workflowUuids.contains(workflowUuid))
            .distinct()
            .toList();

        if (!unknownWorkflowUuids.isEmpty()) {
            throw new ConfigurationException(
                "Workflows %s are not in version %d of project %d; its workflow uuids are %s".formatted(
                    unknownWorkflowUuids, projectVersion, projectId, workflowUuids),
                ProjectDeploymentErrorType.INVALID_DEPLOYMENT);
        }
    }

    private static @Nullable ConnectionDTO chooseConnection(
        String workflowUuid, ComponentConnection componentConnection, List<ConnectionDTO> candidates,
        List<DeploymentConnectionChoiceDTO> connectionChoices,
        @Nullable ProjectDeploymentWorkflow currentProjectDeploymentWorkflow) {

        Optional<DeploymentConnectionChoiceDTO> connectionChoice = connectionChoices.stream()
            .filter(choice -> Objects.equals(choice.workflowUuid(), workflowUuid) &&
                Objects.equals(choice.workflowNodeName(), componentConnection.workflowNodeName()) &&
                Objects.equals(choice.key(), componentConnection.key()))
            .findFirst();

        if (connectionChoice.isPresent()) {
            DeploymentConnectionChoiceDTO deploymentConnectionChoiceDTO = connectionChoice.get();

            long connectionId = deploymentConnectionChoiceDTO.connectionId();

            return findCandidate(candidates, connectionId)
                .orElseThrow(() -> new ConfigurationException(
                    "Connection %d cannot be used for slot %s/%s of workflow %s; usable candidates: %s".formatted(
                        connectionId, componentConnection.workflowNodeName(), componentConnection.key(), workflowUuid,
                        describeCandidates(candidates)),
                    ProjectDeploymentErrorType.INVALID_CONNECTION_CHOICE));
        }

        if (currentProjectDeploymentWorkflow != null) {
            Optional<ConnectionDTO> currentConnection = currentProjectDeploymentWorkflow.getConnections()
                .stream()
                .filter(workflowConnection -> Objects.equals(
                    workflowConnection.getWorkflowNodeName(), componentConnection.workflowNodeName()) &&
                    Objects.equals(workflowConnection.getWorkflowConnectionKey(), componentConnection.key()))
                .map(ProjectDeploymentWorkflowConnection::getConnectionId)
                .filter(Objects::nonNull)
                .flatMap(connectionId -> findCandidate(candidates, connectionId).stream())
                .findFirst();

            if (currentConnection.isPresent()) {
                return currentConnection.get();
            }
        }

        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private long createDevelopmentProjectDeployment(
        Project project, int projectVersion, List<ProjectDeploymentWorkflow> projectDeploymentWorkflows) {

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setEnvironment(Environment.DEVELOPMENT);
        projectDeployment.setName(project.getName());
        projectDeployment.setProjectId(project.getId());
        projectDeployment.setProjectVersion(projectVersion);

        projectDeployment = projectDeploymentService.create(projectDeployment);

        checkProjectDeploymentWorkflows(projectDeployment, -1, projectDeploymentWorkflows, List.of());

        return Objects.requireNonNull(projectDeployment.getId(), "id");
    }

    private static String describeCandidates(List<ConnectionDTO> candidates) {
        if (candidates.isEmpty()) {
            return "none";
        }

        return candidates.stream()
            .map(candidate -> "%d (%s)".formatted(candidate.id(), candidate.name()))
            .collect(Collectors.joining(", "));
    }

    private @Nullable ProjectDeployment fetchDevelopmentProjectDeployment(
        long projectId, @Nullable Long projectDeploymentId) {

        if (projectDeploymentId == null) {
            List<Long> developmentProjectDeploymentIds = projectDeploymentService
                .getProjectDeployments(false, Environment.DEVELOPMENT, projectId, null, null)
                .stream()
                .filter(projectDeployment -> !SystemProjects.isSystemDeploymentName(projectDeployment.getName()))
                .map(ProjectDeployment::getId)
                .toList();

            if (!developmentProjectDeploymentIds.isEmpty()) {
                throw new ConfigurationException(
                    "Project %d already has a DEVELOPMENT deployment %s; pass its projectDeploymentId".formatted(
                        projectId, developmentProjectDeploymentIds),
                    ProjectDeploymentErrorType.INVALID_DEPLOYMENT);
            }

            return null;
        }

        return projectDeploymentService.fetchProjectDeployment(projectDeploymentId)
            .filter(projectDeployment -> Objects.equals(projectDeployment.getProjectId(), projectId))
            .filter(projectDeployment -> projectDeployment.getEnvironment() == Environment.DEVELOPMENT)
            .filter(projectDeployment -> !SystemProjects.isSystemDeploymentName(projectDeployment.getName()))
            .orElseThrow(() -> new ConfigurationException(
                "Project deployment %d is not a DEVELOPMENT deployment of project %d".formatted(
                    projectDeploymentId, projectId),
                ProjectDeploymentErrorType.INVALID_DEPLOYMENT));
    }

    private static Optional<ConnectionDTO> findCandidate(List<ConnectionDTO> candidates, long connectionId) {
        return candidates.stream()
            .filter(candidate -> Objects.equals(candidate.id(), connectionId))
            .findFirst();
    }

    private List<ConnectionDTO> getCandidateConnections(long workspaceId, ComponentConnection componentConnection) {
        ConnectionDefinition connectionDefinition = connectionDefinitionService.getConnectionDefinition(
            componentConnection.componentName(), componentConnection.componentVersion());

        return workspaceConnectionFacade
            .getConnections(
                workspaceId, componentConnection.componentName(), connectionDefinition.getVersion(),
                (long) Environment.DEVELOPMENT.ordinal(), null)
            .stream()
            .filter(this::isUsableCandidate)
            .toList();
    }

    private int getDeployableProjectVersion(Project project) {
        long projectId = Objects.requireNonNull(project.getId(), "id");
        ProjectVersion lastPublishedProjectVersion = project.getLastPublishedProjectVersion();

        if (lastPublishedProjectVersion == null) {
            throw new ConfigurationException(
                "Project %d has never been published; call publishProject first".formatted(projectId),
                ProjectDeploymentErrorType.UNPUBLISHED_CHANGES);
        }

        int publishedProjectVersion = lastPublishedProjectVersion.getVersion();

        Map<String, String> draftWorkflowIds = getWorkflowIdsByUuid(projectId, project.getLastProjectVersion());
        Map<String, String> publishedWorkflowIds = getWorkflowIdsByUuid(projectId, publishedProjectVersion);

        boolean unpublishedChanges = !Objects.equals(draftWorkflowIds.keySet(), publishedWorkflowIds.keySet()) ||
            draftWorkflowIds.entrySet()
                .stream()
                .anyMatch(entry -> !hasSameDefinition(entry.getValue(), publishedWorkflowIds.get(entry.getKey())));

        if (unpublishedChanges) {
            throw new ConfigurationException(
                "Project %d has unpublished changes; call publishProject first".formatted(projectId),
                ProjectDeploymentErrorType.UNPUBLISHED_CHANGES);
        }

        return publishedProjectVersion;
    }

    private static List<MissingInput> getMissingInputs(
        String workflowUuid, Workflow workflow, Map<String, ?> workflowInputs) {

        List<MissingInput> missingInputs = new ArrayList<>();

        for (Workflow.Input input : workflow.getInputs()) {
            Object value = workflowInputs.get(input.name());

            if (input.required() && (value == null || (value instanceof String string && string.isBlank()))) {
                missingInputs.add(new MissingInput(workflowUuid, input.name()));
            }
        }

        return missingInputs;
    }

    private Map<String, ProjectDeploymentWorkflow> getProjectDeploymentWorkflowsByUuid(
        ProjectDeployment projectDeployment) {

        long projectDeploymentId = Objects.requireNonNull(projectDeployment.getId(), "id");

        Map<String, ProjectDeploymentWorkflow> projectDeploymentWorkflows = new HashMap<>();

        for (ProjectDeploymentWorkflow projectDeploymentWorkflow : projectDeploymentWorkflowService
            .getProjectDeploymentWorkflows(projectDeploymentId)) {

            projectDeploymentWorkflows.put(
                projectWorkflowService.getProjectWorkflowUuid(
                    projectDeploymentId, projectDeploymentWorkflow.getWorkflowId()),
                projectDeploymentWorkflow);
        }

        return projectDeploymentWorkflows;
    }

    private List<RemovedWorkflow> getRemovedWorkflows(
        ProjectDeployment projectDeployment, List<ProjectWorkflow> projectWorkflows) {

        Set<String> workflowUuids = projectWorkflows.stream()
            .map(ProjectWorkflow::getUuidAsString)
            .collect(Collectors.toSet());

        return projectWorkflowService
            .getProjectWorkflows(projectDeployment.getProjectId(), projectDeployment.getProjectVersion())
            .stream()
            .filter(projectWorkflow -> !workflowUuids.contains(projectWorkflow.getUuidAsString()))
            .map(projectWorkflow -> new RemovedWorkflow(
                projectWorkflow.getUuidAsString(),
                getWorkflowName(workflowService.getWorkflow(projectWorkflow.getWorkflowId()))))
            .toList();
    }

    private Map<String, String> getWorkflowIdsByUuid(long projectId, int projectVersion) {
        return projectWorkflowService.getProjectWorkflows(projectId, projectVersion)
            .stream()
            .collect(Collectors.toMap(ProjectWorkflow::getUuidAsString, ProjectWorkflow::getWorkflowId));
    }

    private static String getWorkflowName(Workflow workflow) {
        String label = workflow.getLabel();

        return label == null ? workflow.getId() : label;
    }

    private boolean hasSameDefinition(String workflowId, String otherWorkflowId) {
        Workflow workflow = workflowService.getWorkflow(workflowId);
        Workflow otherWorkflow = workflowService.getWorkflow(otherWorkflowId);

        return Objects.equals(workflow.getDefinition(), otherWorkflow.getDefinition());
    }

    private boolean isUsableCandidate(ConnectionDTO connection) {
        Long connectionId = connection.id();

        return connectionId != null && !AiProviderConnectionId.isAiProviderConnectionId(connectionId) &&
            connection.credentialStatus() == CredentialStatus.VALID &&
            permissionService.hasResourceScope(connectionId, CONNECTION, CONNECTION_USE);
    }

    private long redeployDevelopmentProjectDeployment(
        ProjectDeployment projectDeployment, int projectVersion,
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows) {

        long projectDeploymentId = Objects.requireNonNull(projectDeployment.getId(), "id");
        int oldProjectVersion = projectDeployment.getProjectVersion();
        List<WorkflowTriggerRegistration> enabledWorkflowTriggerRegistrations = projectDeployment.isEnabled()
            ? getEnabledWorkflowTriggerRegistrations(projectDeploymentId) : List.of();

        ProjectDeployment disabledProjectDeployment = projectDeploymentService.getProjectDeployment(
            projectDeploymentId);

        disabledProjectDeployment.setEnabled(false);
        disabledProjectDeployment.setProjectVersion(projectVersion);

        disabledProjectDeployment = projectDeploymentService.update(disabledProjectDeployment);

        checkProjectDeploymentWorkflows(
            disabledProjectDeployment, oldProjectVersion, projectDeploymentWorkflows,
            projectWorkflowService.getProjectWorkflows(disabledProjectDeployment.getProjectId()));

        changeWorkflowTriggers(
            workflowTriggerJournal -> disableWorkflowTriggers(
                enabledWorkflowTriggerRegistrations, projectDeployment::getEnvironmentId, workflowTriggerJournal));

        return projectDeploymentId;
    }

    private static Map<String, ?> resolveInputs(
        String workflowUuid, Map<String, Map<String, ?>> inputs,
        @Nullable ProjectDeploymentWorkflow currentProjectDeploymentWorkflow) {

        Map<String, ?> workflowInputs = inputs.get(workflowUuid);

        if (workflowInputs != null) {
            return workflowInputs;
        }

        if (currentProjectDeploymentWorkflow != null) {
            return currentProjectDeploymentWorkflow.getInputs();
        }

        return Map.of();
    }

    private static ProjectDeploymentWorkflow toProjectDeploymentWorkflow(
        String workflowId, boolean enabled, Map<String, ?> workflowInputs,
        List<ProjectDeploymentWorkflowConnection> workflowConnections) {

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setConnections(workflowConnections);
        projectDeploymentWorkflow.setEnabled(enabled);
        projectDeploymentWorkflow.setInputs(workflowInputs);
        projectDeploymentWorkflow.setWorkflowId(workflowId);

        return projectDeploymentWorkflow;
    }

    private static UnresolvedConnection toUnresolvedConnection(
        String workflowUuid, Workflow workflow, ComponentConnection componentConnection,
        List<ConnectionDTO> candidates) {

        return new UnresolvedConnection(
            workflowUuid, getWorkflowName(workflow), componentConnection.workflowNodeName(),
            componentConnection.key(), componentConnection.componentName(), componentConnection.required(),
            candidates.stream()
                .map(candidate -> new Candidate(Objects.requireNonNull(candidate.id(), "id"), candidate.name()))
                .toList());
    }

    private List<Tag> checkTags(List<Tag> tags) {
        return CollectionUtils.isEmpty(tags) ? Collections.emptyList() : tagService.save(tags);
    }

    private static boolean containsTag(ProjectDeployment projectDeployment, Tag tag) {
        List<Long> tagIds = projectDeployment.getTagIds();

        return tagIds.contains(tag.getId());
    }

    private List<WorkflowTriggerRegistration> getEnabledWorkflowTriggerRegistrations(long projectDeploymentId) {
        return projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeploymentId)
            .stream()
            .filter(ProjectDeploymentWorkflow::isEnabled)
            .flatMap(projectDeploymentWorkflow -> getWorkflowTriggerRegistrations(projectDeploymentWorkflow).stream())
            .toList();
    }

    private List<WorkflowTriggerRegistration> getWorkflowTriggerRegistrations(
        ProjectDeploymentWorkflow projectDeploymentWorkflow) {

        Workflow workflow = workflowService.getWorkflow(projectDeploymentWorkflow.getWorkflowId());

        List<WorkflowTrigger> workflowTriggers = WorkflowTrigger.of(workflow);
        ProjectWorkflow projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(workflow.getId());

        List<WorkflowTriggerRegistration> workflowTriggerRegistrations = new ArrayList<>();

        for (WorkflowTrigger workflowTrigger : workflowTriggers) {
            workflowTriggerRegistrations.add(
                toWorkflowTriggerRegistration(
                    projectDeploymentWorkflow, workflow.getId(), projectWorkflow, workflowTrigger,
                    WorkflowNodeType.ofType(workflowTrigger.getType())));
        }

        return workflowTriggerRegistrations;
    }

    private void disableWorkflowTriggers(
        ProjectDeploymentWorkflow projectDeploymentWorkflow, LongSupplier environmentIdSupplier,
        WorkflowTriggerJournal workflowTriggerJournal) {

        disableWorkflowTriggers(
            getWorkflowTriggerRegistrations(projectDeploymentWorkflow), environmentIdSupplier, workflowTriggerJournal);
    }

    private void disableWorkflowTriggers(
        List<WorkflowTriggerRegistration> workflowTriggerRegistrations, LongSupplier environmentIdSupplier,
        WorkflowTriggerJournal workflowTriggerJournal) {

        for (WorkflowTriggerRegistration workflowTriggerRegistration : workflowTriggerRegistrations) {

            disableWorkflowTrigger(workflowTriggerRegistration);

            if (!isManualTrigger(workflowTriggerRegistration.workflowNodeType())) {
                workflowTriggerJournal.recordDisarmed(workflowTriggerRegistration, environmentIdSupplier);
            }
        }
    }

    private void disableWorkflowTrigger(WorkflowTriggerRegistration workflowTriggerRegistration) {
        triggerLifecycleFacade.executeTriggerDisable(
            workflowTriggerRegistration.workflowId(), workflowTriggerRegistration.workflowExecutionId(),
            workflowTriggerRegistration.workflowNodeType(), workflowTriggerRegistration.triggerParameters(),
            workflowTriggerRegistration.connectionId());
    }

    private void enableWorkflowTrigger(WorkflowTriggerRegistration workflowTriggerRegistration, long environmentId) {
        WorkflowExecutionId workflowExecutionId = workflowTriggerRegistration.workflowExecutionId();

        triggerLifecycleFacade.executeTriggerEnable(
            workflowTriggerRegistration.workflowId(), workflowExecutionId,
            workflowTriggerRegistration.workflowNodeType(), workflowTriggerRegistration.triggerParameters(),
            workflowTriggerRegistration.connectionId(), getWebhookUrl(workflowExecutionId), environmentId);
    }

    /**
     * Stores {@code armedTriggerState} again unless the stored trigger state already equals it, which it does where the
     * trigger state is kept outside the transaction that rolled back.
     */
    private void restoreTriggerState(WorkflowExecutionId workflowExecutionId, @Nullable Object armedTriggerState) {
        if (armedTriggerState == null) {
            return;
        }

        Optional<Object> storedTriggerState = triggerStateService.fetchValue(workflowExecutionId);

        if (!Objects.equals(storedTriggerState.orElse(null), armedTriggerState)) {
            triggerStateService.save(workflowExecutionId, armedTriggerState);
        }
    }

    private static boolean isManualTrigger(WorkflowNodeType workflowNodeType) {
        return Objects.equals(workflowNodeType.name(), "manual");
    }

    private WorkflowTriggerRegistration toWorkflowTriggerRegistration(
        ProjectDeploymentWorkflow projectDeploymentWorkflow, String workflowId, ProjectWorkflow projectWorkflow,
        WorkflowTrigger workflowTrigger, WorkflowNodeType workflowNodeType) {

        WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, projectDeploymentWorkflow.getProjectDeploymentId(),
            projectWorkflow.getUuidAsString(), workflowTrigger.getName());
        Map<String, ?> triggerParameters = workflowTrigger.evaluateParameters(
            projectDeploymentWorkflow.getInputs(), evaluator);
        Long connectionId = getConnectionId(
            projectDeploymentWorkflow.getProjectDeploymentId(), workflowId, workflowTrigger);

        return new WorkflowTriggerRegistration(
            workflowId, workflowExecutionId, workflowNodeType, triggerParameters, connectionId);
    }

    private ProjectDeploymentWorkflow doEnableProjectDeploymentWorkflow(
        long projectDeploymentId, String workflowId, boolean enable, WorkflowTriggerJournal workflowTriggerJournal) {

        ProjectDeploymentWorkflow projectDeploymentWorkflow =
            projectDeploymentWorkflowService.getProjectDeploymentWorkflow(
                projectDeploymentId, workflowId);

        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        if (enable) {
            Workflow workflow = workflowService.getWorkflow(workflowId);

            List<ComponentConnection> requiredComponentConnections = CollectionUtils.concat(
                WorkflowTrigger.of(workflow)
                    .stream()
                    .flatMap(workflowTrigger -> CollectionUtils.stream(
                        componentConnectionFacade.getComponentConnections(workflowTrigger)))
                    .filter(ComponentConnection::required)
                    .toList(),
                workflow.getTasks(true)
                    .stream()
                    .flatMap(workflowTask -> CollectionUtils.stream(
                        componentConnectionFacade.getComponentConnections(workflowTask)))
                    .filter(ComponentConnection::required)
                    .toList());

            List<String> workflowNodeNames = requiredComponentConnections.stream()
                .map(ComponentConnection::workflowNodeName)
                .toList();

            List<ProjectDeploymentWorkflowConnection> connections = projectDeploymentWorkflow.getConnections()
                .stream()
                .filter(connection -> workflowNodeNames.contains(connection.getWorkflowNodeName()))
                .toList();

            if (!requiredComponentConnections.isEmpty() && requiredComponentConnections.size() != connections.size()) {
                throw new ConfigurationException(
                    "Not all required connections are set for a workflow with id=%s".formatted(workflow.getId()),
                    ProjectDeploymentErrorType.WORKFLOW_CONNECTIONS_NOT_FOUND);
            }
        }

        if (projectDeployment.isEnabled()) {
            if (enable) {
                enableWorkflowTriggers(projectDeploymentWorkflow, workflowTriggerJournal);
            } else {
                disableWorkflowTriggers(
                    projectDeploymentWorkflow, projectDeployment::getEnvironmentId, workflowTriggerJournal);

                workflowTriggerJournal.recordDisabledWorkflow(projectDeploymentWorkflow);
            }
        }

        return projectDeploymentWorkflow;
    }

    private void enableProjectDeployment(
        long projectDeploymentId, boolean enable, WorkflowTriggerJournal workflowTriggerJournal) {

        List<ProjectDeploymentWorkflow> enabledProjectDeploymentWorkflows = projectDeploymentWorkflowService
            .getProjectDeploymentWorkflows(projectDeploymentId)
            .stream()
            .filter(ProjectDeploymentWorkflow::isEnabled)
            .toList();

        for (ProjectDeploymentWorkflow projectDeploymentWorkflow : enabledProjectDeploymentWorkflows) {
            if (enable) {
                enableWorkflowTriggers(projectDeploymentWorkflow, workflowTriggerJournal);
            } else {
                disableWorkflowTriggers(
                    projectDeploymentWorkflow, () -> getEnvironmentId(projectDeploymentId), workflowTriggerJournal);
            }
        }

        projectDeploymentService.updateEnabled(projectDeploymentId, enable);
    }

    private void deleteProjectDeploymentRows(long projectDeploymentId) {
        List<ProjectDeploymentWorkflow> projectDeploymentWorkflows =
            projectDeploymentWorkflowService.getProjectDeploymentWorkflows(projectDeploymentId);

        List<Long> jobIds = principalJobService.getJobIds(projectDeploymentId, PlatformType.AUTOMATION);

        for (long jobId : jobIds) {
            triggerExecutionService.deleteJobTriggerExecution(jobId);

            principalJobService.deletePrincipalJobs(jobId, PlatformType.AUTOMATION);
        }

        List<Long> orderedJobIds = jobIds.stream()
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();

        for (long jobId : orderedJobIds) {
            jobFacade.deleteJob(jobId);
        }

        for (ProjectDeploymentWorkflow projectDeploymentWorkflow : projectDeploymentWorkflows) {
            projectDeploymentWorkflowService.delete(projectDeploymentWorkflow.getId());
        }

        projectDeploymentService.delete(projectDeploymentId);
    }

    private void enableWorkflowTriggers(
        ProjectDeploymentWorkflow projectDeploymentWorkflow, WorkflowTriggerJournal workflowTriggerJournal) {

        Workflow workflow = workflowService.getWorkflow(projectDeploymentWorkflow.getWorkflowId());

        validateProjectDeploymentWorkflowInputs(projectDeploymentWorkflow.getInputs(), workflow);

        List<WorkflowTrigger> workflowTriggers = WorkflowTrigger.of(workflow);
        ProjectWorkflow projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(workflow.getId());
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(
            projectDeploymentWorkflow.getProjectDeploymentId());

        for (WorkflowTrigger workflowTrigger : workflowTriggers) {
            WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

            if (isManualTrigger(workflowNodeType)) {
                continue;
            }

            WorkflowTriggerRegistration workflowTriggerRegistration = toWorkflowTriggerRegistration(
                projectDeploymentWorkflow, workflow.getId(), projectWorkflow, workflowTrigger, workflowNodeType);

            enableWorkflowTrigger(workflowTriggerRegistration, projectDeployment.getEnvironmentId());

            workflowTriggerJournal.recordArmed(workflowTriggerRegistration);
        }
    }

    private long getEnvironmentId(long projectDeploymentId) {
        ProjectDeployment projectDeployment = projectDeploymentService.getProjectDeployment(projectDeploymentId);

        return projectDeployment.getEnvironmentId();
    }

    private List<Long> getRunningJobIds(long projectDeploymentId, String workflowId) {
        List<Long> principalIds = List.of(projectDeploymentId);
        List<String> workflowIds = List.of(workflowId);

        List<Long> runningJobIds = new ArrayList<>();
        int pageNumber = 0;

        while (true) {
            Page<Long> page = principalJobService.getJobIds(
                Job.Status.STARTED, null, null, principalIds, PlatformType.AUTOMATION, workflowIds, false, pageNumber);

            List<Long> jobIds = page.getContent();

            if (jobIds.isEmpty()) {
                break;
            }

            runningJobIds.addAll(jobIds);

            if (page.hasNext()) {
                pageNumber++;
            } else {
                break;
            }
        }

        return runningJobIds;
    }

    private void stopRunningJobs(DisabledWorkflow disabledWorkflow) {
        for (Long jobId : disabledWorkflow.runningJobIds()) {
            try {
                jobFacade.stopJob(jobId);
            } catch (RuntimeException exception) {
                log.warn(
                    "Failed to stop job {} of workflow {} of project deployment {} after disabling the workflow",
                    jobId, disabledWorkflow.workflowId(), disabledWorkflow.projectDeploymentId(), exception);
            }
        }
    }

    private List<Tag> filterTags(List<Tag> tags, ProjectDeployment projectDeployment) {
        return CollectionUtils.filter(tags, tag -> containsTag(projectDeployment, tag));
    }

    private Long getConnectionId(long projectDeploymentId, String workflowId, WorkflowTrigger workflowTrigger) {
        return componentConnectionFacade.getComponentConnections(workflowTrigger)
            .stream()
            .findFirst()
            .map(workflowConnection -> getConnectionId(
                projectDeploymentId, workflowId, workflowConnection.workflowNodeName(), workflowConnection.key()))
            .orElse(null);
    }

    private Long getConnectionId(
        long projectDeploymentId, String workflowId, String workflowNodeName, String workflowConnectionKey) {

        return projectDeploymentWorkflowService
            .fetchProjectDeploymentWorkflowConnection(
                projectDeploymentId, workflowId, workflowNodeName, workflowConnectionKey)
            .map(ProjectDeploymentWorkflowConnection::getConnectionId)
            .orElse(null);
    }

    private Optional<ProjectDeploymentWorkflow> getFirst(
        ProjectWorkflow curProjectWorkflow, List<ProjectDeploymentWorkflow> oldProjectDeploymentWorkflows,
        ProjectDeployment projectDeployment) {
        return oldProjectDeploymentWorkflows.stream()
            .filter(curProjectDeploymentWorkflow -> {
                String projectDeploymentWorkflowUuid =
                    projectWorkflowService.getProjectWorkflowUuid(
                        projectDeployment.getId(), curProjectDeploymentWorkflow.getWorkflowId());

                return Objects.equals(
                    projectDeploymentWorkflowUuid, curProjectWorkflow.getUuidAsString());
            })
            .findFirst();
    }

    private Instant getJobEndDate(Long jobId) {
        Job job = jobService.getJob(jobId);

        return job.getEndDate();
    }

    private Job.Status getJobStatus(Long jobId) {
        Job job = jobService.getJob(jobId);

        return job.getStatus();
    }

    private Instant getProjectDeploymentLastExecutionDate(long projectDeploymentId) {
        return principalJobService.fetchLastJobId(projectDeploymentId, PlatformType.AUTOMATION)
            .map(this::getJobEndDate)
            .orElse(null);
    }

    /**
     * Drops the deployments whose project is feature-owned rather than user-created (see {@link SystemProjects}) or
     * hidden from the current principal — a deployment is only as visible as the project it deploys.
     */
    private List<ProjectDeployment> filterOutSystemProjectDeployments(List<ProjectDeployment> projectDeployments) {
        if (projectDeployments.isEmpty()) {
            return projectDeployments;
        }

        List<Project> projects = getProjects(projectDeployments);

        Set<Long> systemProjectIds = projects.stream()
            .filter(SystemProjects::isSystemProject)
            .map(Project::getId)
            .collect(Collectors.toSet());

        Set<Long> visibleProjectIds = projectVisibilityFilter.visibleProjectIds(projects);

        return projectDeployments.stream()
            .filter(projectDeployment -> !systemProjectIds.contains(projectDeployment.getProjectId()))
            .filter(projectDeployment -> visibleProjectIds.contains(projectDeployment.getProjectId()))
            .toList();
    }

    /**
     * The distinct projects the given deployments belong to. Several deployments of one project — one per environment —
     * are the norm, so the ids are deduplicated before the lookup: undeduplicated, the IN list of the lookup query
     * grows with the number of environments each project is deployed to rather than with the number of projects being
     * loaded. Matches how {@code getWorkspaceProjectDeployments} already derives its project ids for the workflow
     * lookup.
     */
    private List<Project> getProjects(List<ProjectDeployment> projectDeployments) {
        return projectService.getProjects(
            projectDeployments
                .stream()
                .map(ProjectDeployment::getProjectId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    }

    private String getStaticWebhookUrl(long projectDeploymentId, String workflowId) {
        Workflow workflow = workflowService.getWorkflow(workflowId);

        List<WorkflowTrigger> workflowTriggers = WorkflowTrigger.of(workflow);

        for (WorkflowTrigger workflowTrigger : workflowTriggers) {
            WorkflowNodeType triggerWorkflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

            TriggerDefinition triggerDefinition;

            try {
                triggerDefinition = triggerDefinitionService.getTriggerDefinition(
                    triggerWorkflowNodeType.name(), triggerWorkflowNodeType.version(),
                    Objects.requireNonNull(triggerWorkflowNodeType.operation()));
            } catch (Exception exception) {
                log.error(
                    "Failed to get trigger definition for workflow trigger type={}",
                    triggerWorkflowNodeType.name(),
                    exception);

                continue;
            }

            if (triggerDefinition.getType() == TriggerType.STATIC_WEBHOOK &&
                !Objects.equals(triggerDefinition.getName(), MANUAL_TRIGGER_NAME)) {

                ProjectWorkflow projectWorkflow = projectWorkflowService.getWorkflowProjectWorkflow(workflow.getId());

                return getWebhookUrl(
                    WorkflowExecutionId.of(
                        PlatformType.AUTOMATION, projectDeploymentId, projectWorkflow.getUuidAsString(),
                        workflowTrigger.getName()));
            }
        }

        return null;
    }

    private List<Tag> getTags(List<ProjectDeployment> projectDeployments) {
        return tagService.getTags(
            projectDeployments.stream()
                .flatMap(projectDeployment -> CollectionUtils.stream(projectDeployment.getTagIds()))
                .filter(Objects::nonNull)
                .toList());
    }

    private String getWebhookUrl(WorkflowExecutionId workflowExecutionId) {
        return webhookUrl.replace("{id}", workflowExecutionId.toString());
    }

    private Instant getWorkflowLastExecutionDate(long projectDeploymentId, List<String> workflowIds) {
        return principalJobService.fetchLastWorkflowJobId(projectDeploymentId, workflowIds, PlatformType.AUTOMATION)
            .map(this::getJobEndDate)
            .orElse(null);
    }

    private Job.Status getWorkflowLastExecutionStatus(long projectDeploymentId, List<String> workflowIds) {
        return principalJobService.fetchLastWorkflowJobId(projectDeploymentId, workflowIds, PlatformType.AUTOMATION)
            .map(this::getJobStatus)
            .orElse(null);
    }

    private String getWorkflowUuid(
        String workflowId, int projectVersion, List<ProjectWorkflow> projectWorkflows) {

        return projectWorkflows.stream()
            .filter(projectWorkflow -> Objects.equals(projectWorkflow.getWorkflowId(), workflowId) &&
                projectWorkflow.getProjectVersion() == projectVersion)
            .findFirst()
            .map(ProjectWorkflow::getUuidAsString)
            .orElseThrow();
    }

    private String resolveStaticWebhookExecutionId(
        Workflow workflow, List<WorkflowTrigger> workflowTriggers, ProjectDeploymentWorkflow deploymentWorkflow,
        Map<String, ProjectWorkflow> projectWorkflowsByWorkflowId,
        Map<TriggerDefinitionKey, TriggerDefinition> triggerDefinitionCache) {

        for (WorkflowTrigger workflowTrigger : workflowTriggers) {
            WorkflowNodeType workflowNodeType = WorkflowNodeType.ofType(workflowTrigger.getType());

            TriggerDefinitionKey triggerDefinitionKey = new TriggerDefinitionKey(
                workflowNodeType.name(), workflowNodeType.version(),
                Objects.requireNonNull(workflowNodeType.operation()));

            TriggerDefinition triggerDefinition = triggerDefinitionCache.computeIfAbsent(
                triggerDefinitionKey,
                lookupKey -> triggerDefinitionService.getTriggerDefinition(
                    lookupKey.name(), lookupKey.version(), lookupKey.operation()));

            if (triggerDefinition.getType() == TriggerType.STATIC_WEBHOOK &&
                !Objects.equals(triggerDefinition.getName(), MANUAL_TRIGGER_NAME)) {

                ProjectWorkflow projectWorkflow = projectWorkflowsByWorkflowId.get(workflow.getId());

                if (projectWorkflow == null) {
                    return null;
                }

                WorkflowExecutionId workflowExecutionId = WorkflowExecutionId.of(
                    PlatformType.AUTOMATION, deploymentWorkflow.getProjectDeploymentId(),
                    projectWorkflow.getUuidAsString(), workflowTrigger.getName());

                return workflowExecutionId.toString();
            }
        }

        return null;
    }

    private ProjectDeploymentWorkflowDTO toProjectDeploymentWorkflowDTO(
        ProjectDeploymentWorkflow projectDeploymentWorkflow, ProjectDeployment projectDeployment,
        List<ProjectWorkflow> projectWorkflows) {

        String workflowUuid = getWorkflowUuid(
            projectDeploymentWorkflow.getWorkflowId(), projectDeployment.getProjectVersion(),
            projectWorkflows);

        List<String> workflowUuidWorkflowIds = projectWorkflows.stream()
            .filter(projectWorkflow -> Objects.equals(
                projectWorkflow.getUuidAsString(), workflowUuid))
            .map(ProjectWorkflow::getWorkflowId)
            .toList();

        return new ProjectDeploymentWorkflowDTO(
            projectDeploymentWorkflow,
            getWorkflowLastExecutionDate(projectDeployment.getId(), workflowUuidWorkflowIds),
            getWorkflowLastExecutionStatus(projectDeployment.getId(), workflowUuidWorkflowIds),
            getStaticWebhookUrl(
                projectDeploymentWorkflow.getProjectDeploymentId(),
                projectDeploymentWorkflow.getWorkflowId()),
            workflowUuid);
    }

    private void validateDeploymentConnectionEnvironments(
        List<ProjectDeploymentWorkflowConnection> workflowConnections, Environment targetEnvironment) {

        for (ProjectDeploymentWorkflowConnection workflowConnection : workflowConnections) {
            if (workflowConnection.getConnectionId() == null) {
                continue;
            }

            Connection connection = connectionService.getConnection(workflowConnection.getConnectionId());

            if (connection.getEnvironmentId() != targetEnvironment.ordinal()) {
                throw new ConfigurationException(
                    "Connection '%s' environment does not match deployment environment %s".formatted(
                        connection.getName(), targetEnvironment.name()),
                    ConnectionErrorType.INVALID_CONNECTION);
            }
        }
    }

    private void validateProjectDeploymentWorkflow(ProjectDeploymentWorkflow projectDeploymentWorkflow) {
        if (projectDeploymentWorkflow.isEnabled()) {
            List<ProjectDeploymentWorkflowConnection> projectDeploymentWorkflowConnections =
                projectDeploymentWorkflow.getConnections();
            Workflow workflow = workflowService.getWorkflow(projectDeploymentWorkflow.getWorkflowId());

            validateProjectDeploymentWorkflowConnections(projectDeploymentWorkflowConnections, workflow);
            validateProjectDeploymentWorkflowInputs(projectDeploymentWorkflow.getInputs(), workflow);
        }
    }

    private void validateProjectDeploymentWorkflowConnections(
        List<ProjectDeploymentWorkflowConnection> projectDeploymentWorkflowConnections, Workflow workflow) {

        for (ProjectDeploymentWorkflowConnection projectDeploymentWorkflowConnection : projectDeploymentWorkflowConnections) {
            Connection connection = connectionService.getConnection(
                projectDeploymentWorkflowConnection.getConnectionId());

            ComponentConnection componentConnection = componentConnectionFacade.getComponentConnection(
                workflow.getId(), projectDeploymentWorkflowConnection.getWorkflowNodeName(),
                projectDeploymentWorkflowConnection.getWorkflowConnectionKey());

            if (!Objects.equals(connection.getComponentName(), componentConnection.componentName())) {
                throw new ConfigurationException(
                    "Connection component name does not match workflow connection component name",
                    ConnectionErrorType.INVALID_CONNECTION_COMPONENT_NAME);
            }
        }
    }

    static void validateProjectDeploymentWorkflowInputs(Map<String, ?> inputs, Workflow workflow) {
        for (Workflow.Input input : workflow.getInputs()) {
            if (input.required()) {
                Object value = inputs.get(input.name());

                Assert.notNull(value, "Missing required param: " + input.name());

                if (value instanceof String string) {
                    Assert.hasText(string, "Missing required param: " + input.name());
                }
            }
        }
    }

    /**
     * Memoization key for the trigger-definition lookups {@link #getWorkspaceChatWorkflows} would otherwise repeat once
     * per workflow in the listing.
     */
    private record TriggerDefinitionKey(String name, int version, String operation) {
    }

    private record WorkflowTriggerRegistration(
        String workflowId, WorkflowExecutionId workflowExecutionId, WorkflowNodeType workflowNodeType,
        Map<String, ?> triggerParameters, @Nullable Long connectionId) {
    }

    private record DisabledWorkflow(long projectDeploymentId, String workflowId, List<Long> runningJobIds) {
    }

    private record WorkflowTriggerUndo(
        WorkflowTriggerRegistration workflowTriggerRegistration, String description, Runnable action) {
    }

    /**
     * The external trigger registrations one operation has changed so far, each paired with the step that reverses it,
     * and the workflows it disabled, each with the jobs it had running when it was disarmed, which are stopped once the
     * whole operation has succeeded.
     */
    private final class WorkflowTriggerJournal {

        private final List<DisabledWorkflow> disabledWorkflows = new ArrayList<>();
        private final List<WorkflowTriggerUndo> workflowTriggerUndos = new ArrayList<>();

        /**
         * Records a trigger this change armed together with the trigger state arming it stored, read while that state
         * is still visible: the disarm of a dynamic webhook or hybrid trigger finds the subscription it removes in that
         * state, and a rollback of the transaction the change ran in takes it back. If the state cannot be read, the
         * arm is recorded without it before the failure is rethrown.
         */
        private void recordArmed(WorkflowTriggerRegistration workflowTriggerRegistration) {
            WorkflowExecutionId workflowExecutionId = workflowTriggerRegistration.workflowExecutionId();
            Object armedTriggerState;

            try {
                armedTriggerState = triggerStateService.fetchValue(workflowExecutionId)
                    .filter(WebhookEnableOutput.class::isInstance)
                    .orElse(null);
            } catch (RuntimeException exception) {
                workflowTriggerUndos.add(
                    new WorkflowTriggerUndo(
                        workflowTriggerRegistration, "disable",
                        () -> disableWorkflowTrigger(workflowTriggerRegistration)));

                throw exception;
            }

            workflowTriggerUndos.add(
                new WorkflowTriggerUndo(
                    workflowTriggerRegistration, "disable", () -> {
                        try {
                            restoreTriggerState(workflowExecutionId, armedTriggerState);
                        } catch (RuntimeException exception) {
                            log.warn(
                                "Failed to restore the state of trigger {} of workflow {} before disabling it",
                                workflowExecutionId.getTriggerName(), workflowTriggerRegistration.workflowId(),
                                exception);
                        }

                        disableWorkflowTrigger(workflowTriggerRegistration);
                    }));
        }

        private void discardDisabledWorkflows() {
            disabledWorkflows.clear();
        }

        private boolean hasTriggerChanges() {
            return !workflowTriggerUndos.isEmpty();
        }

        private boolean isEmpty() {
            return !hasTriggerChanges() && disabledWorkflows.stream()
                .allMatch(disabledWorkflow -> disabledWorkflow.runningJobIds()
                    .isEmpty());
        }

        private void recordDisabledWorkflow(ProjectDeploymentWorkflow projectDeploymentWorkflow) {
            long projectDeploymentId = projectDeploymentWorkflow.getProjectDeploymentId();
            String workflowId = projectDeploymentWorkflow.getWorkflowId();

            disabledWorkflows.add(
                new DisabledWorkflow(
                    projectDeploymentId, workflowId, getRunningJobIds(projectDeploymentId, workflowId)));
        }

        private void recordDisarmed(
            WorkflowTriggerRegistration workflowTriggerRegistration, LongSupplier environmentIdSupplier) {

            workflowTriggerUndos.add(
                new WorkflowTriggerUndo(
                    workflowTriggerRegistration, "re-enable",
                    () -> enableWorkflowTrigger(workflowTriggerRegistration, environmentIdSupplier.getAsLong())));
        }

        private void stopRunningJobs() {
            for (DisabledWorkflow disabledWorkflow : disabledWorkflows) {
                ProjectDeploymentFacadeImpl.this.stopRunningJobs(disabledWorkflow);
            }
        }

        /**
         * Runs every recorded undo step, latest first. A step that fails is added to {@code failure} as suppressed and
         * logged, and the remaining steps still run.
         */
        private void undo(RuntimeException failure) {
            undo(failure, "a project deployment change failed");
        }

        /**
         * Runs every recorded undo step, latest first, once the transaction the change ran in has rolled back. A step
         * that fails is logged, and the remaining steps still run.
         */
        private void undoAfterRollback() {
            undo(null, "the transaction of a project deployment change rolled back");
        }

        private void undo(@Nullable RuntimeException failure, String cause) {
            for (WorkflowTriggerUndo workflowTriggerUndo : workflowTriggerUndos.reversed()) {
                try {
                    Runnable action = workflowTriggerUndo.action();

                    action.run();
                } catch (RuntimeException exception) {
                    if (failure != null && exception != failure) {
                        failure.addSuppressed(exception);
                    }

                    WorkflowTriggerRegistration workflowTriggerRegistration =
                        workflowTriggerUndo.workflowTriggerRegistration();
                    WorkflowExecutionId workflowExecutionId = workflowTriggerRegistration.workflowExecutionId();

                    log.warn(
                        "Failed to {} trigger {} of workflow {} while restoring the trigger registrations after {}",
                        workflowTriggerUndo.description(), workflowExecutionId.getTriggerName(),
                        workflowTriggerRegistration.workflowId(), cause, exception);
                }
            }
        }

        private List<String> getWorkflowIds() {
            return workflowTriggerUndos.stream()
                .map(workflowTriggerUndo -> workflowTriggerUndo.workflowTriggerRegistration()
                    .workflowId())
                .distinct()
                .toList();
        }
    }

    /**
     * Ties one journal to the transaction its trigger changes ran in, so that the external trigger registrations follow
     * the outcome of that transaction rather than of the facade call alone. On commit the jobs of the workflows the
     * change disabled are stopped. On rollback every trigger change is undone, latest first, inside a new transaction,
     * since the original one is finished and arming or disarming a trigger may write. With
     * {@code JdbcTransactionManager} a commit that fails is rolled back and reported as rolled back, so its trigger
     * changes are undone as well; the outcome is reported unknown only when that rollback fails too, and then nothing
     * is undone and the mismatch is logged.
     */
    private final class WorkflowTriggerJournalSynchronization implements TransactionSynchronization {

        private final int order;
        private final WorkflowTriggerJournal workflowTriggerJournal;

        private WorkflowTriggerJournalSynchronization(WorkflowTriggerJournal workflowTriggerJournal, int order) {
            this.order = order;
            this.workflowTriggerJournal = workflowTriggerJournal;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void afterCommit() {
            workflowTriggerJournal.stopRunningJobs();
        }

        @Override
        public void afterCompletion(int status) {
            if (!workflowTriggerJournal.hasTriggerChanges()) {
                return;
            }

            if (status == STATUS_ROLLED_BACK) {
                try {
                    requiresNewTransactionTemplate.executeWithoutResult(
                        transactionStatus -> workflowTriggerJournal.undoAfterRollback());
                } catch (RuntimeException exception) {
                    log.warn(
                        "Failed to restore the trigger registrations of workflows {} after the transaction of a " +
                            "project deployment change rolled back",
                        workflowTriggerJournal.getWorkflowIds(), exception);
                }
            } else if (status == STATUS_UNKNOWN) {
                log.warn(
                    "The outcome of the transaction of a project deployment change is unknown; the trigger " +
                        "registrations of workflows {} may not match the stored deployment",
                    workflowTriggerJournal.getWorkflowIds());
            }
        }
    }
}

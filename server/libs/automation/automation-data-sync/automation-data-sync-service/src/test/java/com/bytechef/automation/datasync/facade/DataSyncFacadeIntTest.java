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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.atlas.configuration.domain.Workflow;
import com.bytechef.atlas.configuration.repository.WorkflowCrudRepository;
import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.ProjectDeployment;
import com.bytechef.automation.configuration.domain.ProjectDeploymentWorkflow;
import com.bytechef.automation.configuration.domain.SystemProjects;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.facade.ProjectDeploymentFacade;
import com.bytechef.automation.configuration.repository.ProjectDeploymentRepository;
import com.bytechef.automation.configuration.repository.ProjectDeploymentWorkflowRepository;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.repository.ProjectWorkflowRepository;
import com.bytechef.automation.configuration.repository.WorkspaceRepository;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.automation.datasync.config.AutomationDataSyncIntTestConfiguration;
import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.domain.DataSyncElement.Kind;
import com.bytechef.automation.datasync.dto.DataSyncDTO;
import com.bytechef.automation.datasync.dto.DataSyncDeploymentDTO;
import com.bytechef.automation.datasync.dto.DataSyncVersionDTO;
import com.bytechef.automation.datasync.exception.DataSyncErrorType;
import com.bytechef.automation.datasync.repository.DataSyncElementRepository;
import com.bytechef.automation.datasync.repository.DataSyncRepository;
import com.bytechef.automation.datasync.util.DataSyncWorkflowGenerator;
import com.bytechef.commons.util.JsonUtils;
import com.bytechef.exception.ConfigurationException;
import com.bytechef.platform.component.domain.ComponentDefinition;
import com.bytechef.platform.component.service.ComponentDefinitionService;
import com.bytechef.platform.configuration.domain.Environment;
import com.bytechef.platform.configuration.domain.WorkflowTestConfiguration;
import com.bytechef.platform.configuration.domain.WorkflowTestConfigurationConnection;
import com.bytechef.platform.configuration.service.WorkflowTestConfigurationService;
import com.bytechef.platform.tag.domain.Tag;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.core.type.TypeReference;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = AutomationDataSyncIntTestConfiguration.class,
    properties = "bytechef.workflow.repository.jdbc.enabled=true")
@Import(PostgreSQLContainerConfiguration.class)
class DataSyncFacadeIntTest {

    @Autowired
    private DataSyncFacade dataSyncFacade;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectWorkflowService projectWorkflowService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowTestConfigurationService workflowTestConfigurationService;

    @Autowired
    private DataSyncRepository dataSyncRepository;

    @Autowired
    private DataSyncElementRepository dataSyncElementRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectWorkflowRepository projectWorkflowRepository;

    @Autowired
    private ProjectDeploymentRepository projectDeploymentRepository;

    @Autowired
    private ProjectDeploymentWorkflowRepository projectDeploymentWorkflowRepository;

    @Autowired
    private WorkflowCrudRepository workflowCrudRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ComponentDefinitionService componentDefinitionService;

    @Autowired
    private ProjectDeploymentFacade projectDeploymentFacade;

    private long workspaceId;

    @BeforeEach
    void beforeEach() {
        Workspace workspace = workspaceRepository.save(new Workspace("ws"));

        workspaceId = workspace.getId();
    }

    @AfterEach
    void afterEach() {
        // componentDefinitionService and projectDeploymentFacade are context-scoped Mockito mocks (see
        // AutomationDataSyncIntTestConfiguration), so per-test stubbing would otherwise leak into every later test
        // sharing the same Spring context.
        Mockito.reset(componentDefinitionService, projectDeploymentFacade);

        projectDeploymentWorkflowRepository.deleteAll();
        projectDeploymentRepository.deleteAll();
        dataSyncElementRepository.deleteAll();
        dataSyncRepository.deleteAll();
        projectWorkflowRepository.deleteAll();

        for (Workflow workflow : workflowCrudRepository.findAll()) {
            workflowCrudRepository.deleteById(workflow.getId());
        }

        projectRepository.deleteAll();
        workspaceRepository.deleteAll();
    }

    @Test
    void testCreateMakesHiddenProjectAndDraftWorkflow() {
        DataSyncDTO dataSyncDTO = dataSyncFacade.createDataSync("CRM to DB", "nightly", workspaceId);

        Project project = projectService.getProject(dataSyncDTO.dataSync()
            .getProjectId());

        assertThat(project.getName()).startsWith(SystemProjects.DATA_SYNC_NAME_PREFIX);
        assertThat(dataSyncDTO.dataSync()
            .getName()).isEqualTo("crm-to-db");
        assertThat(dataSyncDTO.dataSync()
            .getTriggerType()).isEqualTo(TriggerType.MANUAL);

        Workflow draft = workflowService.getWorkflow(dataSyncDTO.draftWorkflowId());

        Map<String, Object> definition = JsonUtils.read(draft.getDefinition(), new TypeReference<>() {});

        assertThat(definition).containsKey("triggers");

        Project ordinaryProject = new Project();

        ordinaryProject.setName("an-ordinary-project");
        ordinaryProject.setWorkspaceId(workspaceId);

        Project savedOrdinaryProject = projectService.create(ordinaryProject);

        // getProjects() with no arguments is unfiltered — SystemProjects hiding is applied by the filtered overload
        // listing surfaces actually call, so that is the one this asserts against. Asserting the ordinary project's
        // presence proves the query returns rows for this workspace, so the hidden project's absence is meaningful
        // rather than an artifact of an empty result.
        assertThat(projectService.getProjects(null, null, null, null, null, workspaceId))
            .anyMatch(candidate -> candidate.getId()
                .equals(savedOrdinaryProject.getId()))
            .noneMatch(candidate -> candidate.getId()
                .equals(project.getId()));
    }

    @Test
    void testDuplicateTitleGetsSuffixedSlug() {
        dataSyncFacade.createDataSync("Sync", null, workspaceId);

        DataSyncDTO second = dataSyncFacade.createDataSync("Sync", null, workspaceId);

        assertThat(second.dataSync()
            .getName()).isEqualTo("sync-2");
    }

    @Test
    void testSetElementRegeneratesDraftAndSyncsTestConnection() {
        DataSyncDTO dataSyncDTO = dataSyncFacade.createDataSync("Sync", null, workspaceId);
        long id = dataSyncDTO.dataSync()
            .getId();

        dataSyncFacade.setDataSyncElement(
            id, Kind.SOURCE, "csvFile", 1, "read", Map.of("delimiter", ","), 77L);

        Map<String, Object> definition = readDraft(id);

        @SuppressWarnings("unchecked")
        Map<String, Object> task = ((List<Map<String, Object>>) definition.get("tasks")).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> clusterElements = (Map<String, Object>) task.get("clusterElements");

        assertThat(clusterElements).containsKey("source")
            .doesNotContainKey("destination");

        for (Environment environment : Environment.values()) {
            WorkflowTestConfiguration configuration = workflowTestConfigurationService
                .fetchWorkflowTestConfiguration(dataSyncDTO.draftWorkflowId(), environment.ordinal())
                .orElseThrow();

            assertThat(configuration.getConnections())
                .extracting(
                    WorkflowTestConfigurationConnection::getWorkflowNodeName,
                    WorkflowTestConfigurationConnection::getWorkflowConnectionKey,
                    WorkflowTestConfigurationConnection::getConnectionId)
                .contains(org.assertj.core.groups.Tuple.tuple(
                    DataSyncWorkflowGenerator.TASK_NODE_NAME, DataSyncWorkflowGenerator.nodeName(Kind.SOURCE), 77L));
        }
    }

    @Test
    void testReplacingSourceComponentDropsProcessor() {
        long id = createFullyConfigured();

        dataSyncFacade.setDataSyncElement(id, Kind.SOURCE, "jsonFile", 1, "read", Map.of(), null);

        assertThat(dataSyncElementRepository.findAllByDataSyncIdOrderByKindAsc(id))
            .extracting(DataSyncElement::getKind)
            .containsExactly(Kind.SOURCE, Kind.DESTINATION);
    }

    @Test
    void testUpdateElementParametersReplacesWholeMap() {
        long id = createFullyConfigured();

        DataSyncElement source = elementOf(id, Kind.SOURCE);

        dataSyncFacade.updateDataSyncElement(source.getId(), Map.of("path", "/tmp/a.csv"), 5L);

        DataSyncElement reloaded = elementOf(id, Kind.SOURCE);

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) reloaded.getParameters();

        assertThat(parameters).containsOnlyKeys("path");
        assertThat(reloaded.getConnectionId()).isEqualTo(5L);
        assertThat(dataSyncElementRepository.findAllByDataSyncIdOrderByKindAsc(id)).hasSize(3);
    }

    @Test
    void testProcessorMustBeFieldMapper() {
        long id = createFullyConfigured();

        assertThatThrownBy(
            () -> dataSyncFacade.setDataSyncElement(id, Kind.PROCESSOR, "script", 1, "run", Map.of(), null))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                    .isEqualTo(DataSyncErrorType.PROCESSOR_NOT_FIELD_MAPPER.getErrorKey()));
    }

    @Test
    void testUpdateTriggerRewritesDraftTrigger() {
        long id = createFullyConfigured();

        dataSyncFacade.updateDataSyncTrigger(
            id, TriggerType.SCHEDULE, Map.of("expression", "0 9 * * ?", "timezone", "UTC", "frequencyKind", "DAILY"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trigger = ((List<Map<String, Object>>) readDraft(id).get("triggers")).get(0);

        assertThat(trigger).containsEntry("type", "schedule/v1/cron");
    }

    @Test
    void testDeleteRefusedWhileDeployed() {
        long id = createFullyConfigured();
        long projectId = dataSyncFacade.getDataSync(id)
            .dataSync()
            .getProjectId();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setName("deploy");
        projectDeployment.setProjectId(projectId);
        projectDeployment.setProjectVersion(1);
        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setUuid(UUID.randomUUID());

        projectDeploymentRepository.save(projectDeployment);

        assertThatThrownBy(() -> dataSyncFacade.deleteDataSync(id))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.DATA_SYNC_HAS_DEPLOYMENTS.getErrorKey()));
    }

    @Test
    void testDeleteRemovesProjectAndRows() {
        long id = createFullyConfigured();
        long projectId = dataSyncFacade.getDataSync(id)
            .dataSync()
            .getProjectId();

        dataSyncFacade.deleteDataSync(id);

        assertThat(dataSyncRepository.findById(id)).isEmpty();
        assertThat(dataSyncElementRepository.findAllByDataSyncIdOrderByKindAsc(id)).isEmpty();
        assertThat(projectRepository.findById(projectId)).isEmpty();
    }

    @Test
    void testPublishRefusesWithoutSource() {
        DataSyncDTO dataSyncDTO = dataSyncFacade.createDataSync("Sync", null, workspaceId);
        long id = dataSyncDTO.dataSync()
            .getId();

        dataSyncFacade.setDataSyncElement(id, Kind.DESTINATION, "postgresql", 1, "insert", Map.of(), 2L);

        assertThatThrownBy(() -> dataSyncFacade.publishDataSync(id, null))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.SOURCE_MISSING.getErrorKey()));
    }

    @Test
    void testPublishRefusesWithoutDestination() {
        DataSyncDTO dataSyncDTO = dataSyncFacade.createDataSync("Sync", null, workspaceId);
        long id = dataSyncDTO.dataSync()
            .getId();

        dataSyncFacade.setDataSyncElement(id, Kind.SOURCE, "csvFile", 1, "read", Map.of(), 1L);

        assertThatThrownBy(() -> dataSyncFacade.publishDataSync(id, null))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.DESTINATION_MISSING.getErrorKey()));
    }

    @Test
    void testPublishRefusesMissingRequiredConnection() {
        long id = createFullyConfigured();

        DataSyncElement source = elementOf(id, Kind.SOURCE);

        dataSyncFacade.updateDataSyncElement(source.getId(), Map.of(), null);

        ComponentDefinition csvFile = Mockito.mock(ComponentDefinition.class);

        Mockito.when(csvFile.isConnectionRequired())
            .thenReturn(true);
        Mockito.when(componentDefinitionService.getComponentDefinition("csvFile", 1))
            .thenReturn(csvFile);

        assertThatThrownBy(() -> dataSyncFacade.publishDataSync(id, null))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.ELEMENT_CONNECTION_MISSING.getErrorKey()));
    }

    @Test
    void testPublishRefusesScheduleWithoutExpression() {
        long id = createFullyConfigured();

        dataSyncFacade.updateDataSyncTrigger(id, TriggerType.SCHEDULE, Map.of("timezone", "UTC"));

        assertThatThrownBy(() -> dataSyncFacade.publishDataSync(id, null))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.SCHEDULE_EXPRESSION_MISSING.getErrorKey()));
    }

    @Test
    void testPublishMintsVersionAndClearsUnpublishedChanges() {
        long id = createFullyConfigured();

        int version = dataSyncFacade.publishDataSync(id, "first");

        DataSyncDTO published = dataSyncFacade.getDataSync(id);

        // publishDataSync returns the newly-minted DRAFT version number, exactly as AiAgentFacadeImpl.publishAgent
        // does (see AiAgentFacadeIntTest.testPublishAgentWithGatedToolPublishesApprovalGateDeliveringOverChat's
        // assertThat(newVersion).isEqualTo(2)) — Project.publish() advances the draft and returns that new number,
        // not the version it just marked PUBLISHED.
        assertThat(version).isEqualTo(2);
        assertThat(published.lastPublishedVersion()).isEqualTo(1);
        assertThat(published.unpublishedChanges()).isFalse();
        assertThat(dataSyncFacade.getDataSyncVersions(id)).extracting(DataSyncVersionDTO::version)
            .containsExactly(2, 1);

        dataSyncFacade.updateDataSyncTrigger(id, TriggerType.SCHEDULE, Map.of("expression", "0 9 * * ?"));

        assertThat(dataSyncFacade.getDataSync(id)
            .unpublishedChanges()).isTrue();
    }

    @Test
    void testDeploymentsListAndRunNow() {
        long id = createFullyConfigured();

        dataSyncFacade.publishDataSync(id, null);

        long projectId = dataSyncFacade.getDataSync(id)
            .dataSync()
            .getProjectId();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setName("deploy");
        projectDeployment.setProjectId(projectId);
        projectDeployment.setProjectVersion(1);
        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setEnabled(true);
        projectDeployment.setUuid(UUID.randomUUID());

        ProjectDeployment savedProjectDeployment = projectDeploymentRepository.save(projectDeployment);

        ProjectDeploymentWorkflow projectDeploymentWorkflow = new ProjectDeploymentWorkflow();

        projectDeploymentWorkflow.setProjectDeploymentId(savedProjectDeployment.getId());
        projectDeploymentWorkflow.setWorkflowId(publishedWorkflowId(projectId));
        projectDeploymentWorkflow.setEnabled(true);

        projectDeploymentWorkflowRepository.save(projectDeploymentWorkflow);

        List<DataSyncDeploymentDTO> deployments = dataSyncFacade.getDataSyncDeployments(workspaceId);

        assertThat(deployments).singleElement()
            .satisfies(deployment -> {
                assertThat(deployment.dataSyncId()).isEqualTo(id);
                assertThat(deployment.triggerType()).isEqualTo(TriggerType.MANUAL);
                assertThat(deployment.workflowId()).isNotBlank();
            });

        Mockito.when(projectDeploymentFacade.createProjectDeploymentWorkflowJob(
            Mockito.eq(savedProjectDeployment.getId()), Mockito.eq(publishedWorkflowId(projectId))))
            .thenReturn(99L);

        assertThat(dataSyncFacade.runDataSyncDeployment(id, savedProjectDeployment.getId())).isEqualTo(99L);
    }

    @Test
    void testRunNowRefusesDisabledDeployment() {
        long id = createFullyConfigured();

        dataSyncFacade.publishDataSync(id, null);

        long projectId = dataSyncFacade.getDataSync(id)
            .dataSync()
            .getProjectId();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setName("deploy");
        projectDeployment.setProjectId(projectId);
        projectDeployment.setProjectVersion(1);
        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setEnabled(false);
        projectDeployment.setUuid(UUID.randomUUID());

        ProjectDeployment saved = projectDeploymentRepository.save(projectDeployment);

        assertThatThrownBy(() -> dataSyncFacade.runDataSyncDeployment(id, saved.getId()))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.DEPLOYMENT_DISABLED.getErrorKey()));
    }

    @Test
    void testRunNowRefusesForeignDeployment() {
        long id = createFullyConfigured();
        long otherId = createFullyConfigured();

        dataSyncFacade.publishDataSync(otherId, null);

        long otherProjectId = dataSyncFacade.getDataSync(otherId)
            .dataSync()
            .getProjectId();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setName("deploy");
        projectDeployment.setProjectId(otherProjectId);
        projectDeployment.setProjectVersion(1);
        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setEnabled(true);
        projectDeployment.setUuid(UUID.randomUUID());

        ProjectDeployment saved = projectDeploymentRepository.save(projectDeployment);

        assertThatThrownBy(() -> dataSyncFacade.runDataSyncDeployment(id, saved.getId()))
            .isInstanceOf(ConfigurationException.class)
            .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                .isEqualTo(DataSyncErrorType.DEPLOYMENT_NOT_OWNED.getErrorKey()));
    }

    @Test
    void testUpdateDeploymentTagsRefusesForeignDeployment() {
        long id = createFullyConfigured();
        long otherId = createFullyConfigured();

        dataSyncFacade.publishDataSync(otherId, null);

        long otherProjectId = dataSyncFacade.getDataSync(otherId)
            .dataSync()
            .getProjectId();

        ProjectDeployment projectDeployment = new ProjectDeployment();

        projectDeployment.setName("deploy");
        projectDeployment.setProjectId(otherProjectId);
        projectDeployment.setProjectVersion(1);
        projectDeployment.setEnvironment(Environment.PRODUCTION);
        projectDeployment.setEnabled(true);
        projectDeployment.setUuid(UUID.randomUUID());

        ProjectDeployment saved = projectDeploymentRepository.save(projectDeployment);

        assertThatThrownBy(
            () -> dataSyncFacade.updateDataSyncDeploymentTags(id, saved.getId(), List.of(new Tag("urgent"))))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(exception -> assertThat(((ConfigurationException) exception).getErrorKey())
                    .isEqualTo(DataSyncErrorType.DEPLOYMENT_NOT_OWNED.getErrorKey()));
    }

    private long createFullyConfigured() {
        DataSyncDTO dataSyncDTO = dataSyncFacade.createDataSync("Sync", null, workspaceId);
        long id = dataSyncDTO.dataSync()
            .getId();

        dataSyncFacade.setDataSyncElement(id, Kind.SOURCE, "csvFile", 1, "read", Map.of("delimiter", ","), 1L);
        dataSyncFacade.setDataSyncElement(id, Kind.DESTINATION, "postgresql", 1, "insert", Map.of(), 2L);
        dataSyncFacade.setDataSyncElement(
            id, Kind.PROCESSOR, DataSyncElement.PROCESSOR_COMPONENT_NAME, DataSyncElement.PROCESSOR_COMPONENT_VERSION,
            DataSyncElement.PROCESSOR_OPERATION_NAME, Map.of("mappings", List.of()), null);

        return id;
    }

    private DataSyncElement elementOf(long id, Kind kind) {
        return dataSyncElementRepository.findAllByDataSyncIdOrderByKindAsc(id)
            .stream()
            .filter(element -> element.getKind() == kind)
            .findFirst()
            .orElseThrow();
    }

    private Map<String, Object> readDraft(long id) {
        String draftWorkflowId = dataSyncFacade.getDataSync(id)
            .draftWorkflowId();

        return JsonUtils.read(workflowService.getWorkflow(draftWorkflowId)
            .getDefinition(), new TypeReference<>() {});
    }

    private String publishedWorkflowId(long projectId) {
        return projectWorkflowService.getProjectWorkflowIds(projectId, 1)
            .get(0);
    }
}

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

package com.bytechef.automation.datasync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.domain.Workspace;
import com.bytechef.automation.configuration.repository.ProjectRepository;
import com.bytechef.automation.configuration.repository.WorkspaceRepository;
import com.bytechef.automation.datasync.config.AutomationDataSyncIntTestConfiguration;
import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.domain.DataSync.TriggerType;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.domain.DataSyncElement.Kind;
import com.bytechef.automation.datasync.repository.DataSyncElementRepository;
import com.bytechef.automation.datasync.repository.DataSyncRepository;
import com.bytechef.test.config.testcontainers.PostgreSQLContainerConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * @author Ivica Cardic
 */
@SpringBootTest(
    classes = AutomationDataSyncIntTestConfiguration.class,
    properties = "bytechef.workflow.repository.jdbc.enabled=true")
@Import(PostgreSQLContainerConfiguration.class)
class DataSyncServiceIntTest {

    @Autowired
    private DataSyncService dataSyncService;

    @Autowired
    private DataSyncElementService dataSyncElementService;

    @Autowired
    private DataSyncRepository dataSyncRepository;

    @Autowired
    private DataSyncElementRepository dataSyncElementRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @AfterEach
    void afterEach() {
        dataSyncElementRepository.deleteAll();
        dataSyncRepository.deleteAll();
        projectRepository.deleteAll();
        workspaceRepository.deleteAll();
    }

    @Test
    void testCreateAndListByWorkspace() {
        Workspace workspace = workspaceRepository.save(new Workspace("ws"));
        Project project = projectRepository.save(newProject(workspace.getId()));

        DataSync dataSync = dataSyncService.create(newDataSync("crm-to-db", workspace.getId(), project.getId()));

        assertThat(dataSync.getId()).isNotNull();
        assertThat(dataSync.getTriggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(dataSyncService.getDataSyncs(workspace.getId())).extracting(DataSync::getName)
            .containsExactly("crm-to-db");
    }

    @Test
    void testTriggerRoundTrip() {
        Workspace workspace = workspaceRepository.save(new Workspace("ws"));
        Project project = projectRepository.save(newProject(workspace.getId()));

        DataSync dataSync = newDataSync("scheduled", workspace.getId(), project.getId());

        dataSync.setTriggerType(TriggerType.SCHEDULE);
        dataSync.setTriggerParameters(Map.of("expression", "0 9 * * ?", "timezone", "UTC"));

        DataSync saved = dataSyncService.create(dataSync);

        DataSync reloaded = dataSyncService.getDataSync(saved.getId());

        assertThat(reloaded.getTriggerType()).isEqualTo(TriggerType.SCHEDULE);
        assertThat(reloaded.getTriggerParameters()
            .get("expression")).isEqualTo("0 9 * * ?");
    }

    @Test
    void testOneElementPerKind() {
        Workspace workspace = workspaceRepository.save(new Workspace("ws"));
        Project project = projectRepository.save(newProject(workspace.getId()));
        DataSync dataSync = dataSyncService.create(newDataSync("sync", workspace.getId(), project.getId()));

        dataSyncElementService.create(newElement(dataSync.getId(), Kind.SOURCE));

        assertThatThrownBy(() -> dataSyncElementService.create(newElement(dataSync.getId(), Kind.SOURCE)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testNameSlugIsEnforced() {
        assertThatThrownBy(() -> newDataSync("Not A Slug", 1L, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Project newProject(long workspaceId) {
        Project project = new Project();

        project.setName("__DATA_SYNC__" + UUID.randomUUID());
        project.setWorkspaceId(workspaceId);

        return project;
    }

    private static DataSync newDataSync(String name, long workspaceId, long projectId) {
        DataSync dataSync = new DataSync();

        dataSync.setName(name);
        dataSync.setTitle(name);
        dataSync.setWorkspaceId(workspaceId);
        dataSync.setProjectId(projectId);
        dataSync.setUuid(UUID.randomUUID());
        dataSync.setTriggerType(TriggerType.MANUAL);

        return dataSync;
    }

    private static DataSyncElement newElement(long dataSyncId, Kind kind) {
        DataSyncElement element = new DataSyncElement(dataSyncId, kind);

        element.setComponentName("csvFile");
        element.setComponentVersion(1);
        element.setOperationName("read");

        return element;
    }
}

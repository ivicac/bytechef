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

package com.bytechef.automation.datasync.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.automation.configuration.domain.Project;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.domain.DataSyncElement;
import com.bytechef.automation.datasync.repository.DataSyncElementRepository;
import com.bytechef.automation.datasync.repository.DataSyncRepository;
import com.bytechef.platform.security.domain.ResourceVisibility;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the inheritance invariant for the Data Sync family: a Data Sync and its element rows carry no visibility of
 * their own, so both providers here answer with the hidden backing PROJECT's record under the {@code "Project"} type —
 * the same invariant {@code AiAgentVisibilityProvidersTest} pins for the sibling agent feature.
 *
 * <p>
 * Both halves of each assertion do work the other cannot. The record must be the project's, because a provider that
 * returned the Data Sync's own id would look up grants under {@code ("Project", dataSyncId)} — rows that do not exist —
 * and hide a Data Sync its owner had shared. And {@code visibilityResourceType()} must say {@code "Project"}, because
 * that is the string {@code PermissionServiceImpl.isResourceVisible} hands the resolver; the ids alone would resolve
 * against {@code "DataSync"} grants and deny for the same reason.
 *
 * @author Ivica Cardic
 */
class DataSyncVisibilityProvidersTest {

    private static final long DATA_SYNC_ID = 3L;
    private static final long ELEMENT_ID = 7L;
    private static final long PROJECT_ID = 5L;

    private final DataSyncRepository dataSyncRepository = mock(DataSyncRepository.class);
    private final DataSyncElementRepository dataSyncElementRepository = mock(DataSyncElementRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);

    private DataSyncVisibilityProvider dataSyncVisibilityProvider;

    @BeforeEach
    void setUp() {
        Project project = new Project();

        project.setId(PROJECT_ID);
        project.setVisibility(ResourceVisibility.PRIVATE);

        // created_by is @CreatedBy-managed; the test seeds it the way the persistence layer would
        ReflectionTestUtils.setField(project, "createdBy", "ivica");

        when(projectService.fetchProject(PROJECT_ID)).thenReturn(Optional.of(project));

        DataSync dataSync = new DataSync();

        dataSync.setId(DATA_SYNC_ID);
        dataSync.setProjectId(PROJECT_ID);

        when(dataSyncRepository.findById(DATA_SYNC_ID)).thenReturn(Optional.of(dataSync));

        DataSyncElement dataSyncElement = mock(DataSyncElement.class);

        when(dataSyncElement.getDataSyncId()).thenReturn(DATA_SYNC_ID);
        when(dataSyncElementRepository.findById(ELEMENT_ID)).thenReturn(Optional.of(dataSyncElement));

        dataSyncVisibilityProvider = new DataSyncVisibilityProvider(dataSyncRepository, projectService);
    }

    @Test
    void testDataSyncProviderInheritsProjectRecord() {
        assertThat(dataSyncVisibilityProvider.resourceType()).isEqualTo("DataSync");
        assertThat(dataSyncVisibilityProvider.visibilityResourceType()).isEqualTo("Project");
        assertThat(dataSyncVisibilityProvider.fetchVisibility(DATA_SYNC_ID)).contains(projectRecord());
        assertThat(dataSyncVisibilityProvider.fetchVisibility(999L)).as("an unknown Data Sync must fail closed")
            .isEmpty();
    }

    /**
     * A Data Sync whose row exists but whose backing project has been deleted resolves to nothing rather than to a
     * record, so the by-id gates deny it. The alternative — treating a missing project as unrestricted — would make
     * deleting the project the way to un-hide the Data Sync.
     */
    @Test
    void testDataSyncWithoutProjectFailsClosed() {
        DataSync orphanDataSync = new DataSync();

        orphanDataSync.setId(4L);
        orphanDataSync.setProjectId(404L);

        when(dataSyncRepository.findById(4L)).thenReturn(Optional.of(orphanDataSync));
        when(projectService.fetchProject(404L)).thenReturn(Optional.empty());

        assertThat(dataSyncVisibilityProvider.fetchVisibility(4L)).isEmpty();
    }

    @Test
    void testElementProviderInheritsProjectRecord() {
        DataSyncElementVisibilityProvider provider =
            new DataSyncElementVisibilityProvider(dataSyncElementRepository, dataSyncVisibilityProvider);

        assertThat(provider.resourceType()).isEqualTo("DataSyncElement");
        assertThat(provider.visibilityResourceType()).isEqualTo("Project");
        assertThat(provider.fetchVisibility(ELEMENT_ID)).contains(projectRecord());
        assertThat(provider.fetchVisibility(999L)).as("an unknown element must fail closed")
            .isEmpty();
    }

    private static VisibilityRecord projectRecord() {
        return new VisibilityRecord(PROJECT_ID, ResourceVisibility.PRIVATE, "ivica");
    }
}

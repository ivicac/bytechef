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

import com.bytechef.automation.configuration.security.ProjectVisibilityFilter;
import com.bytechef.automation.configuration.security.ResourceVisibilityProvider;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ResourceVisibilityResolver.VisibilityRecord;
import com.bytechef.automation.datasync.domain.DataSync;
import com.bytechef.automation.datasync.repository.DataSyncRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A Data Sync has no visibility of its own: it is exactly as visible as the hidden project it keeps its generated
 * workflow in, so the record returned here is that PROJECT's and its grants are looked up under {@code "Project"} — the
 * same inheritance the sibling AI Agent feature's visibility provider expresses for its own parent.
 *
 * <p>
 * One question, one record, deliberately. A Data Sync's generated workflow is not reachable as a capability separate
 * from the Data Sync, so "who can see this Data Sync" and "who can see its project" can never need to diverge; a second
 * {@code visibility} column on {@code data_sync} would be a second answer to one question and could only drift from
 * this one. {@code DataSync.getProjectId()} is a non-null {@code long}, so the traversal below has no absent middle
 * term.
 *
 * <p>
 * Reads {@link DataSyncRepository} rather than the {@code @PreAuthorize}-guarded facade to avoid recursion, exactly as
 * {@link DataSyncOwnershipResolver} does. The project side goes through {@link ProjectService} rather than
 * {@code ProjectRepository} because the repository lives in {@code automation-configuration-service}, which this module
 * cannot depend on without inverting the dependency. {@code ProjectService.fetchProject(long)} carries no gate of its
 * own, so the hop adds no recursion.
 *
 * @author Ivica Cardic
 */
@Component
public class DataSyncVisibilityProvider implements ResourceVisibilityProvider {

    private final DataSyncRepository dataSyncRepository;
    private final ProjectService projectService;

    @SuppressFBWarnings("EI")
    public DataSyncVisibilityProvider(DataSyncRepository dataSyncRepository, ProjectService projectService) {
        this.dataSyncRepository = dataSyncRepository;
        this.projectService = projectService;
    }

    @Override
    public String resourceType() {
        return "DataSync";
    }

    @Override
    public String visibilityResourceType() {
        return ProjectVisibilityFilter.PROJECT;
    }

    @Override
    public Optional<VisibilityRecord> fetchVisibility(long id) {
        return dataSyncRepository.findById(id)
            .map(DataSync::getProjectId)
            .flatMap(projectService::fetchProject)
            .map(ProjectVisibilityFilter::toVisibilityRecord);
    }
}

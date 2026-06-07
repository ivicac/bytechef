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

package com.bytechef.automation.knowledgebase.repository;

import com.bytechef.automation.knowledgebase.domain.WorkspaceKnowledgeBaseSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link WorkspaceKnowledgeBaseSource}. Workspace-scoped lookups for KB sources flow
 * through this repository — callers join the returned rows with the platform-side
 * {@link com.bytechef.platform.knowledgebase.service.KnowledgeBaseSourceService} to materialise the actual source
 * domain objects.
 *
 * @author Ivica Cardic
 */
@Repository
public interface WorkspaceKnowledgeBaseSourceRepository extends ListCrudRepository<WorkspaceKnowledgeBaseSource, Long> {

    List<WorkspaceKnowledgeBaseSource> findAllByWorkspaceId(Long workspaceId);

    Optional<WorkspaceKnowledgeBaseSource> findByKnowledgeBaseSourceId(Long knowledgeBaseSourceId);
}

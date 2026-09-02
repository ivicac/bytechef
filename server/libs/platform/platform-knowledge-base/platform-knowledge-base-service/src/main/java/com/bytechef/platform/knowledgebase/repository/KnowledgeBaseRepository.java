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

package com.bytechef.platform.knowledgebase.repository;

import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeBaseRepository
    extends PagingAndSortingRepository<KnowledgeBase, Long>, ListCrudRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findAllByPlatformType(int platformType);

    List<KnowledgeBase> findAllByEnvironmentAndPlatformType(int environment, int platformType);

    /**
     * A knowledge base's copy of a name within an environment and a pool. A name is meant to identify at most one row
     * there, with no further split by owner -- what separates two accounts sharing that knowledge base is the owner on
     * the chunks inside it, applied by {@code KnowledgeBaseVectorStoreWrapper}, not a second row for the same name.
     */
    Optional<KnowledgeBase> findByNameAndEnvironmentAndPlatformType(String name, int environment, int platformType);
}

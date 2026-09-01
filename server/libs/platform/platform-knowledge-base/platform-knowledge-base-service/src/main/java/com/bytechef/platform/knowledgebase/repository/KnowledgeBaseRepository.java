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
     * One account's copy of a name. Optional rather than a list because
     * {@code uk_knowledge_base_name_platform_type_environment_owner} makes at most one row match -- and the owner is
     * the (id, type) PAIR, so the type is part of the lookup exactly as it is part of that key.
     */
    Optional<KnowledgeBase> findByNameAndEnvironmentAndPlatformTypeAndOwnerIdAndOwnerType(
        String name, int environment, int platformType, Long ownerId, Integer ownerType);

    /**
     * The vendor's own copy of a name. At most one row matches, per the partial unique index
     * {@code uk_knowledge_base_name_platform_type_environment_shared}.
     */
    Optional<KnowledgeBase> findByNameAndEnvironmentAndPlatformTypeAndOwnerIdIsNull(
        String name, int environment, int platformType);
}

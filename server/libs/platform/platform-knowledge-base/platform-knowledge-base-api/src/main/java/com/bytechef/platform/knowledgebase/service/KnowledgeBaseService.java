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

package com.bytechef.platform.knowledgebase.service;

import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseService {

    /**
     * Creates a new KnowledgeBase instance and persists it.
     *
     * @param knowledgeBase the KnowledgeBase object to be created
     * @return the newly created KnowledgeBase object with its generated attributes populated
     */
    KnowledgeBase createKnowledgeBase(KnowledgeBase knowledgeBase);

    /**
     * Deletes the knowledge base identified by the specified ID.
     *
     * @param id the unique identifier of the knowledge base to be deleted
     */
    void deleteKnowledgeBase(Long id);

    /**
     * Retrieves the knowledge base associated with the specified unique identifier, with neither a pool nor an owner
     * check.
     *
     * <p>
     * Deliberately kept unscoped, for trusted internal callers only: the console and admin facades, which run their own
     * {@code @PreAuthorize} workspace checks; the background document-processing worker, which has no principal at all;
     * and {@code KnowledgeBaseOptionsUtils.readUnscopedKnowledgeBase}, the fallback a frame reaches for when it cannot
     * learn the run's owner at all and so cannot ask even the pool question -- there is nothing left for it to refuse,
     * since a knowledge base is no longer assigned to one account.
     *
     * <p>
     * {@code KnowledgeBaseVectorStore} was once on that list, justified by its reading chunking configuration only
     * after the enclosing step had resolved the same id. That was true of its {@code load} and false of the
     * {@code VECTOR_STORE} cluster element defined in the same file, which resolved nothing -- and this sentence is
     * what made the exemption look considered. It now resolves through {@link #getKnowledgeBase(Long, List)} like
     * everything else. A workflow step must never reach this overload; it is the id path the pool split exists to
     * close.
     *
     * @param id the unique identifier of the knowledge base to retrieve
     * @return the KnowledgeBase object associated with the given ID
     * @throws RuntimeException if no knowledge base exists with the given ID
     */
    KnowledgeBase getKnowledgeBase(Long id);

    /**
     * Pool-aware form, for every caller acting on behalf of a run.
     *
     * <p>
     * The pool is the whole scope here. A knowledge base in an embedded environment is visible to every account in it;
     * what separates two accounts is the owner on the chunks inside it, applied by
     * {@code KnowledgeBaseVectorStoreWrapper}, not which knowledge bases they can name.
     *
     * @param id            the knowledge base id
     * @param platformTypes the pools this run may read, from {@code poolFor(owner)}
     * @return the knowledge base
     */
    KnowledgeBase getKnowledgeBase(Long id, List<PlatformType> platformTypes);

    /**
     * Retrieves every knowledge base in the given pool, across every environment.
     *
     * @param platformType the pool to list
     * @return every {@code KnowledgeBase} in that pool
     */
    List<KnowledgeBase> getKnowledgeBases(PlatformType platformType);

    /**
     * Retrieves a list of knowledge bases for the specified environment and pool.
     *
     * @param environment  the environment ordinal to filter by
     * @param platformType the pool to list
     * @return a list of {@code KnowledgeBase} objects in the given environment and pool
     */
    List<KnowledgeBase> getKnowledgeBases(int environment, PlatformType platformType);

    /**
     * The knowledge base a run resolves for a name.
     *
     * <p>
     * The twin of {@code DataTableService#fetchDataTable}, and the same rule: resolution is by name within a pool and
     * environment, with no further filtering. What separates two accounts sharing a knowledge base is the owner on the
     * chunks inside it, applied by {@code KnowledgeBaseVectorStoreWrapper} at query time, not which knowledge bases
     * this method can name.
     *
     * @param name         the knowledge base name to resolve
     * @param environment  the environment ordinal to resolve within
     * @param platformType the pool to resolve within
     * @return the knowledge base with that name in that environment and pool, if one exists
     */
    Optional<KnowledgeBase> fetchKnowledgeBase(String name, int environment, PlatformType platformType);

    /**
     * Updates an existing KnowledgeBase identified by the given ID with the provided new values.
     *
     * @param id            the unique identifier of the KnowledgeBase to update
     * @param knowledgeBase the KnowledgeBase object containing the updated values
     * @return the updated KnowledgeBase object after persisting the changes
     */
    KnowledgeBase updateKnowledgeBase(Long id, KnowledgeBase knowledgeBase);
}

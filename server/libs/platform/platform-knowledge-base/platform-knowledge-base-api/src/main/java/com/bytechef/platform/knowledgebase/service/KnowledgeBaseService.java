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
import com.bytechef.platform.owner.Owner;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
     * and {@code KnowledgeBaseOptionsUtils.requireUnassignedKnowledgeBase}, which uses it to INSPECT whether a target
     * belongs to an account rather than to reach one -- it refuses an owned knowledge base outright.
     *
     * <p>
     * {@code KnowledgeBaseVectorStore} was once on that list, justified by its reading chunking configuration only
     * after the enclosing step had resolved the same id. That was true of its {@code load} and false of the
     * {@code VECTOR_STORE} cluster element defined in the same file, which resolved nothing -- and this sentence is
     * what made the exemption look considered. It now resolves through {@link #getKnowledgeBase(Long, List, Optional)}
     * like everything else. A workflow step must never reach this overload; it is the id path the pool split exists to
     * close.
     *
     * @param id the unique identifier of the knowledge base to retrieve
     * @return the KnowledgeBase object associated with the given ID
     * @throws RuntimeException if no knowledge base exists with the given ID
     */
    KnowledgeBase getKnowledgeBase(Long id);

    /**
     * Pool- and owner-aware form, for every caller acting on behalf of a run.
     *
     * <p>
     * The pool is checked first and the owner second, because the owner rule alone cannot separate the pools: an
     * unowned knowledge base is readable by everyone, and every AUTOMATION knowledge base is unowned, so an owned run
     * resolving an id would otherwise read straight out of the AUTOMATION pool. Both refusals are reported exactly as a
     * missing knowledge base, so ids cannot be probed for existence.
     *
     * @param id            the knowledge base id
     * @param platformTypes the pools this run may read, from {@code poolFor(owner)}
     * @param owner         the caller's owner, or empty for an admin or automation caller
     * @return the knowledge base
     */
    KnowledgeBase getKnowledgeBase(Long id, List<PlatformType> platformTypes, Optional<Owner> owner);

    /**
     * Retrieves every knowledge base in the given pool, across every environment.
     *
     * <p>
     * Pool-scoped but not owner-scoped: callers that also need owner filtering use
     * {@link #getKnowledgeBases(PlatformType, Optional)} instead of adding a filter of their own, so the two rules
     * cannot drift apart.
     *
     * @param platformType the pool to list
     * @return every {@code KnowledgeBase} in that pool
     */
    List<KnowledgeBase> getKnowledgeBases(PlatformType platformType);

    /**
     * Owner-aware form of {@link #getKnowledgeBases(PlatformType)}. See
     * {@link #getKnowledgeBase(Long, List, Optional)}.
     *
     * @param platformType the pool to list
     * @param owner        the caller's owner, or empty for an admin or automation caller
     */
    List<KnowledgeBase> getKnowledgeBases(PlatformType platformType, Optional<Owner> owner);

    /**
     * Retrieves a list of knowledge bases for the specified environment and pool.
     *
     * <p>
     * Owner-aware. An unowned knowledge base belongs to the vendor and is listed for everyone, an empty owner lists
     * everything, and an owned one is listed only for that owner. See {@link #getKnowledgeBase(Long, List, Optional)}.
     *
     * @param environment  the environment ordinal to filter by
     * @param platformType the pool to list
     * @param owner        the caller's owner, or empty for an admin or automation caller
     * @return a list of {@code KnowledgeBase} objects in the given environment and pool
     */
    List<KnowledgeBase> getKnowledgeBases(int environment, PlatformType platformType, Optional<Owner> owner);

    /**
     * The knowledge base a run resolves for a name: the caller's own if they have one, the shared one otherwise.
     *
     * <p>
     * The twin of {@code DataTableService#fetchDataTable}, and the same rule. Within a pool two matches are the feature
     * rather than a bug -- the vendor ships one workflow naming {@code docs}, and giving one account its own
     * {@code docs} is a drop-in override needing no workflow edit. Failing on ambiguity here would make per-account
     * knowledge bases unusable.
     *
     * <p>
     * A run with no owner resolves only shared knowledge bases and must never fall through to an account's. That is a
     * property of this method, not of its callers: {@link #getKnowledgeBases(int, PlatformType, Optional)} returns
     * every account's rows once the owner is empty, so a caller filtering a listing by name alone would resolve
     * whichever account's row came back first.
     *
     * @param name         the knowledge base name to resolve
     * @param environment  the environment ordinal to resolve within
     * @param platformType the pool to resolve within
     * @param owner        the caller's owner, or empty for a vendor run with no named account
     * @return the caller's own knowledge base if one exists, else the shared one, else empty
     */
    Optional<KnowledgeBase> fetchKnowledgeBase(
        String name, int environment, PlatformType platformType, Optional<Owner> owner);

    /**
     * Assigns a knowledge base to an account, or returns it to the vendor when {@code owner} is null.
     *
     * <p>
     * Three writes, not one, all in the same transaction: the registry row, every document in the knowledge base, and
     * every one of those documents' chunks in the vector store. Documents used to inherit through
     * {@code knowledge_base_id} and carry no owner of their own, which is what made a single write enough; since the
     * row axis landed they carry their own owner and their chunks carry one again independently, and a document left
     * unowned inside an assigned knowledge base is readable by that account and writable by nobody.
     *
     * <p>
     * <b>Refused</b> when the knowledge base holds documents belonging to a different account, rather than transferring
     * them. Re-stamping only the unowned ones would leave the rest silently unreachable, which is worse to discover
     * late than a refusal. Unassignment is unchecked: making a knowledge base shared says its documents are everyone's,
     * and it is the repair path out of a wrong assignment.
     *
     * <p>
     * EMBEDDED only, for a non-null owner: accounts exist in that pool alone, so an owner on an AUTOMATION knowledge
     * base would resolve for nobody and is refused. Passing null is allowed in every pool -- it is the repair path for
     * a row that acquired an owner before that was refused.
     *
     * @param id    the knowledge base id
     * @param owner the owning account, or null to make the knowledge base shared again
     * @throws IllegalArgumentException if no such knowledge base exists, if a non-null owner is given for one outside
     *                                  the EMBEDDED pool, or if the knowledge base holds documents belonging to some
     *                                  other account
     */
    void assignOwner(long id, @Nullable Owner owner);

    /**
     * Updates an existing KnowledgeBase identified by the given ID with the provided new values.
     *
     * @param id            the unique identifier of the KnowledgeBase to update
     * @param knowledgeBase the KnowledgeBase object containing the updated values
     * @return the updated KnowledgeBase object after persisting the changes
     */
    KnowledgeBase updateKnowledgeBase(Long id, KnowledgeBase knowledgeBase);
}

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

import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeBaseDocumentRepository
    extends PagingAndSortingRepository<KnowledgeBaseDocument, Long>, ListCrudRepository<KnowledgeBaseDocument, Long> {

    List<KnowledgeBaseDocument> findAllByKnowledgeBaseId(Long knowledgeBaseId);

    /**
     * The tombstoned documents of the given source that belong to NOBODY -- the vendor's own. Paired with
     * {@link #findAllBySourceIdAndOwnerIdAndOwnerTypeAndDeletedAtIsNotNull} for the same reason the two tombstone
     * queries are a pair, and named for both columns so neither can be asked for without the other.
     */
    List<KnowledgeBaseDocument> findAllBySourceIdAndOwnerIdIsNullAndOwnerTypeIsNullAndDeletedAtIsNotNull(Long sourceId);

    /**
     * The tombstoned documents of the given source that belong to one account.
     */
    List<KnowledgeBaseDocument> findAllBySourceIdAndOwnerIdAndOwnerTypeAndDeletedAtIsNotNull(
        Long sourceId, Long ownerId, Integer ownerType);

    /**
     * The synced document of the given source record that belongs to NOBODY -- the vendor's own. Paired with
     * {@link #findBySourceIdAndSourceRecordIdAndOwnerIdAndOwnerType}, both columns named in both, so a half-written
     * owner satisfies neither and is nobody's.
     *
     * <p>
     * {@code (source_id, source_record_id)} no longer identifies at most one row: the partial unique indexes behind it
     * are keyed on the owner as well, so one source record can carry one document per account plus the vendor's. That
     * is why this lookup is a pair rather than the single unscoped finder it replaced -- see
     * {@code KnowledgeBaseDocumentService#findSyncedDocument} for why two documents is the right answer.
     */
    Optional<KnowledgeBaseDocument> findBySourceIdAndSourceRecordIdAndOwnerIdIsNullAndOwnerTypeIsNull(
        Long sourceId, String sourceRecordId);

    /**
     * The synced document of the given source record that belongs to one account.
     */
    Optional<KnowledgeBaseDocument> findBySourceIdAndSourceRecordIdAndOwnerIdAndOwnerType(
        Long sourceId, String sourceRecordId, Long ownerId, Integer ownerType);

    /**
     * How many documents in the given knowledge base belong to an account OTHER than the given one. Read before an
     * assignment re-stamps them, so an assignment that would hand one account's documents to another is refused instead
     * -- see {@code KnowledgeBaseServiceImpl#checkNoOtherAccountsDocuments}.
     *
     * <p>
     * A document whose {@code owner_id} sits beside a null {@code owner_type} belongs to nobody, so it must not count
     * as another account's: {@code owner_type = :ownerType} is false rather than unknown for it, but the surrounding
     * {@code NOT (...)} would turn that into a match, so the pair is required explicitly on the outer predicate too.
     */
    @Query("""
        SELECT COUNT(*) FROM knowledge_base_document
        WHERE knowledge_base_id = :knowledgeBaseId
          AND owner_id IS NOT NULL
          AND owner_type IS NOT NULL
          AND NOT (owner_id = :ownerId AND owner_type = :ownerType)
        """)
    long countByKnowledgeBaseIdAndOwnedByAnotherAccount(
        @Param("knowledgeBaseId") Long knowledgeBaseId, @Param("ownerId") Long ownerId,
        @Param("ownerType") Integer ownerType);

    /**
     * Moves every document of a reassigned knowledge base onto its new owner, in the same transaction as the
     * assignment. Both columns are written by the one statement, so an {@code owner_id} can never be left beside a null
     * {@code owner_type}.
     *
     * <p>
     * Tombstoned documents move with the rest: a tombstone is the resurrect anchor for a record that reappears
     * upstream, and one left behind on the old owner would be resurrected by nobody.
     *
     * <p>
     * {@code @Modifying} is required for Spring Data JDBC string {@code @Query} UPDATE/DELETE -- without it, JDBC tries
     * {@code executeQuery()} on the UPDATE and fails with a misleading {@code DataIntegrityViolationException}.
     */
    @Modifying
    @Query("""
        UPDATE knowledge_base_document
        SET owner_id = :ownerId, owner_type = :ownerType
        WHERE knowledge_base_id = :knowledgeBaseId
        """)
    int restampOwnedBy(
        @Param("knowledgeBaseId") Long knowledgeBaseId, @Param("ownerId") Long ownerId,
        @Param("ownerType") Integer ownerType);

    /**
     * The unassignment half of {@link #restampOwnedBy}: every document of a knowledge base handed back to the vendor
     * belongs to nobody again. Two statements rather than one taking a nullable owner, matching the pairs above and
     * keeping every bind parameter typed.
     *
     * <p>
     * Without this half, returning a knowledge base to the vendor would leave its documents on the account that had it,
     * where the vendor can neither read nor write them: a run with no owner reaches the unowned documents alone and
     * never falls through to an account's. The knowledge base would come back empty.
     */
    @Modifying
    @Query("""
        UPDATE knowledge_base_document
        SET owner_id = NULL, owner_type = NULL
        WHERE knowledge_base_id = :knowledgeBaseId
        """)
    int restampUnowned(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * Soft-deletes (tombstones) every UNOWNED synced document tied to the given source whose {@code source_record_id}
     * is not present in the {@code seenIds} collection. Manual uploads (where {@code source_id IS NULL}) are untouched.
     * Returns the count of rows tombstoned.
     *
     * <p>
     * A source no longer identifies one account's documents. Two accounts may sync the same source into one shared
     * knowledge base, so a run that keyed its sweep on {@code source_id} alone reaped rows another account's run had
     * created -- and the chunk sweep that follows then deleted their chunks out of the vector store. The owner is part
     * of the predicate for that reason, and this is the vendor's half of it: a run with no owner tombstones the
     * documents belonging to nobody and never falls through to an account's.
     *
     * <p>
     * Two queries rather than one taking a nullable owner, matching the read/write split the rest of the axis uses and
     * avoiding a null-typed bind parameter in an {@code IS NULL} comparison, which Postgres cannot infer a type for.
     * Both columns are named in both queries: an {@code owner_id} beside a null {@code owner_type} belongs to nobody
     * and must satisfy neither predicate.
     *
     * <p>
     * {@code @Modifying} is required for Spring Data JDBC string {@code @Query} UPDATE/DELETE — without it, JDBC tries
     * {@code executeQuery()} on the UPDATE and fails with a misleading {@code DataIntegrityViolationException}.
     * </p>
     */
    @Modifying
    @Query("""
        UPDATE knowledge_base_document
        SET deleted_at = :deletedAt, last_modified_date = :deletedAt
        WHERE source_id = :sourceId
          AND source_record_id NOT IN (:seenIds)
          AND deleted_at IS NULL
          AND owner_id IS NULL
          AND owner_type IS NULL
        """)
    int tombstoneUnseenUnowned(
        @Param("sourceId") Long sourceId,
        @Param("seenIds") Collection<String> seenIds,
        @Param("deletedAt") Instant deletedAt);

    /**
     * The account half of {@link #tombstoneUnseenUnowned}: a run acting for an owner reaps that owner's documents
     * alone, leaving both the vendor's unowned ones and every other account's untouched. This is the WRITE rule -- an
     * unowned document is every account's to read and nobody's to tombstone.
     */
    @Modifying
    @Query("""
        UPDATE knowledge_base_document
        SET deleted_at = :deletedAt, last_modified_date = :deletedAt
        WHERE source_id = :sourceId
          AND source_record_id NOT IN (:seenIds)
          AND deleted_at IS NULL
          AND owner_id = :ownerId
          AND owner_type = :ownerType
        """)
    int tombstoneUnseenOwnedBy(
        @Param("sourceId") Long sourceId,
        @Param("seenIds") Collection<String> seenIds,
        @Param("ownerId") Long ownerId,
        @Param("ownerType") Integer ownerType,
        @Param("deletedAt") Instant deletedAt);
}

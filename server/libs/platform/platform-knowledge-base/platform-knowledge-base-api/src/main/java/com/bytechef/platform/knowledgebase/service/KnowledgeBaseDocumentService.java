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

import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.dto.DocumentStatusUpdate;
import com.bytechef.platform.owner.Owner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface KnowledgeBaseDocumentService {

    /**
     * Deletes the knowledge base document identified by the specified ID.
     *
     * @param id the unique identifier of the knowledge base document to be deleted
     */
    void delete(long id);

    /**
     * Retrieves the knowledge base document for the specified unique identifier.
     *
     * @param id the unique identifier of the knowledge base document to retrieve
     * @return the {@code KnowledgeBaseDocument} associated with the given ID, or {@code null} if no such document
     *         exists
     */
    KnowledgeBaseDocument getKnowledgeBaseDocument(long id);

    /**
     * Retrieves a list of knowledge base documents associated with the specified knowledge base ID.
     *
     * @param knowledgeBaseId the unique identifier of the knowledge base
     * @return a list of {@code KnowledgeBaseDocument} objects associated with the provided knowledge base ID
     */
    List<KnowledgeBaseDocument> getKnowledgeBaseDocuments(long knowledgeBaseId);

    /**
     * Retrieves the status of the knowledge base document identified by the specified ID.
     *
     * @param id the unique identifier of the knowledge base document whose status is to be retrieved
     * @return a {@code DocumentStatusUpdate} object containing the status information of the specified knowledge base
     *         document
     */
    DocumentStatusUpdate getKnowledgeBaseDocumentStatus(long id);

    /**
     * Saves the provided {@code KnowledgeBaseDocument} to the underlying data store. If the document already exists
     * (based on its unique identifier), it will be updated; otherwise, a new document will be created.
     *
     * @param knowledgeBaseDocument the {@code KnowledgeBaseDocument} to be saved or updated
     * @return the saved or updated {@code KnowledgeBaseDocument} instance, including any modifications made during the
     *         save operation (e.g., generated IDs, timestamps)
     */
    KnowledgeBaseDocument saveKnowledgeBaseDocument(KnowledgeBaseDocument knowledgeBaseDocument);

    /**
     * Creates a new synced document tied to a Knowledge Base source. Persists {@code text} to platform file storage as
     * {@code <sourceRecordId>.md}, populates the five sync columns ({@code source_id}, {@code source_record_id},
     * {@code synced_payload_hash}, {@code last_seen_at}, {@code deleted_at = NULL}), saves the document, and publishes
     * {@code KnowledgeBaseDocumentEvent} to kick the chunker pipeline. Used by the DESTINATION cluster element writer
     * in Phase 13 Task 31.
     *
     * <p>
     * {@code metadataFieldsWhitelist} — when non-null, narrows {@code metadata} to the listed field names before
     * flattening to {@code key=value} tags. Mirrors {@code KnowledgeBaseSource.metadataFields}. Null preserves MVP
     * behavior: every metadata key becomes a tag.
     * </p>
     *
     * <p>
     * {@code owner} — the account the sync run acts for, persisted on the row so the chunker can read it back. Null
     * means the vendor's own sync, whose chunks are shared. Set once, at creation: {@link #replaceSyncedDocument}
     * deliberately takes no owner, so re-syncing a document never moves it between accounts.
     * </p>
     */
    KnowledgeBaseDocument createSyncedDocument(
        long kbId, long sourceId, String sourceRecordId, String name, String text, Map<String, ?> metadata,
        @Nullable Map<String, ?> metadataFieldsWhitelist, String payloadHash, Instant now, @Nullable Owner owner);

    /**
     * Replaces the content of an existing synced document. Idempotent fast path: if the new {@code payloadHash} matches
     * the stored one and the row is not tombstoned, just bumps {@code last_seen_at} (no file rewrite, no chunker
     * re-run, no event publication). Otherwise: writes the new {@code text} to a new {@code FileEntry}, swaps the
     * document's pointer, eagerly deletes the old {@code FileEntry}, clears {@code deleted_at}, sets status to
     * {@code STATUS_UPLOADED} (which re-triggers the chunker pipeline), saves, and publishes
     * {@code KnowledgeBaseDocumentEvent}.
     *
     * <p>
     * {@code metadataFieldsWhitelist} — same semantics as on {@link #createSyncedDocument}.
     * </p>
     */
    KnowledgeBaseDocument replaceSyncedDocument(
        long documentId, String name, String text, Map<String, ?> metadata,
        @Nullable Map<String, ?> metadataFieldsWhitelist, String payloadHash, Instant now);

    /**
     * Soft-deletes the documents the given run may tombstone: those tied to {@code sourceId} whose
     * {@code source_record_id} is not in {@code seenSourceRecordIds}, restricted to the run's OWN documents. Manual
     * uploads ({@code source_id IS NULL}) are unaffected. Returns the number of rows tombstoned. Called by the
     * {@code KnowledgeBaseSourceSyncJobListener} after each FULL_REPLACE sync run completes.
     *
     * <p>
     * {@code owner} — the account the sync run acted for. A source does not identify one account's documents: two
     * accounts may sync the same source into one shared knowledge base, and a sweep keyed on the source alone reaped
     * the other account's rows. The WRITE rule applies, so an empty owner is the vendor and reaps the unowned documents
     * alone rather than everything.
     */
    int tombstoneUnseen(long sourceId, Set<String> seenSourceRecordIds, Instant now, Optional<Owner> owner);

    /**
     * Returns the tombstoned synced documents ({@code deleted_at IS NOT NULL}) tied to the given source that belong to
     * the given owner. Used by the post-tombstone chunk sweep to locate documents whose chunk rows, chunk content
     * files, and vector-store entries must be evicted so semantic search stops serving content that no longer exists
     * upstream.
     *
     * <p>
     * Owner-scoped for the same reason {@link #tombstoneUnseen} is, and by the same WRITE rule: what the sweep returns
     * is deleted, so a listing wider than the caller's own documents is a cross-account delete by another name.
     */
    List<KnowledgeBaseDocument> getTombstonedDocuments(long sourceId, Optional<Owner> owner);

    /**
     * Looks up the run's OWN synced document for the given {@code (source_id, source_record_id)} sync key. Used by the
     * DESTINATION cluster element writer to decide between create / unchanged-fast-path / replace paths per record.
     *
     * <p>
     * Owner-scoped, and by the WRITE rule rather than the read one, because what the caller does with the answer is
     * rewrite it. Unscoped, account 43's run found account 42's document for the same source record and
     * {@code replaceSyncedDocument} rewrote its content: ownership did not move, the content did, which is the same
     * content-crossing-accounts effect the rest of this axis exists to stop. A run with no owner is the vendor and
     * reaches the unowned documents alone, never falling through to an account's.
     *
     * <p>
     * <b>Two accounts syncing one shared source therefore produce two documents for the same source record, one each,
     * and that is the intended answer -- not duplication to be collapsed later.</b> Under per-account ownership each
     * account's copy IS its own record: it carries that account's owner, its chunks carry that account, and only that
     * account's run may rewrite or tombstone it. The single-document alternative is one row whose content is whichever
     * account synced last and whose owner is whichever account synced first, which is a document belonging to one
     * account and describing another's run. The partial unique indexes on {@code (source_id, source_record_id)} are
     * keyed on the owner for the same reason; re-narrowing this lookup to the sync key alone would start failing on
     * them rather than quietly resuming the old behaviour.
     *
     * @param sourceId       the knowledge base source
     * @param sourceRecordId the upstream record id
     * @param owner          the account the sync run acts for, empty for the vendor's own run
     */
    Optional<KnowledgeBaseDocument> findSyncedDocument(long sourceId, String sourceRecordId, Optional<Owner> owner);

    /**
     * Bumps {@code last_seen_at} on the given synced document and saves it. Used by the DESTINATION cluster element
     * writer's unchanged-record fast path (Phase 13 Task 31): when a record's payload hash matches the stored hash and
     * the row is not tombstoned, neither the file nor the chunker pipeline needs to be re-run — only the heartbeat
     * needs to be refreshed so the post-job tombstone sweep doesn't reap the row. Avoids the extra
     * {@code findById}-then-save round-trip that {@link #replaceSyncedDocument} would incur on its own service-side
     * fast path.
     */
    KnowledgeBaseDocument bumpLastSeenAt(KnowledgeBaseDocument document, Instant now);
}

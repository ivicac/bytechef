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

package com.bytechef.component.ai.vectorstore.knowledgebase.destination;

import com.bytechef.commons.util.PayloadHashUtil;
import com.bytechef.component.ai.vectorstore.knowledgebase.util.KnowledgeBaseOptionsUtils;
import com.bytechef.component.definition.ClusterElementContext;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.datastream.ExecutionContext;
import com.bytechef.component.definition.datastream.ItemWriter;
import com.bytechef.platform.component.owner.OwnerResolution;
import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBase;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseDocument;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSource;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseSourceService;
import com.bytechef.platform.owner.Owner;
import com.bytechef.platform.owner.OwnerResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * DESTINATION cluster element implementation for the {@code knowledgeBase} component. Upserts source records into the
 * target Knowledge Base as documents and tracks {@code seenRecordIds} per job for the post-step tombstone sweep
 * performed by the {@code KnowledgeBaseSourceSyncJobListener} (Phase 13 Task 32).
 *
 * <p>
 * Lifecycle: {@link #open} loads the {@link KnowledgeBaseSource} (failing fast if it was deleted before the job
 * started), validates the {@code mode} parameter, and initializes per-job state. {@link #write} hashes each record's
 * payload via the shared {@link PayloadHashUtil}, looks up the existing document by {@code (sourceId, sourceRecordId)},
 * and routes to one of three paths: unchanged-record fast path (bump {@code lastSeenAt} only — no chunker re-run),
 * changed-record replace, or new-record create. {@link #update} flushes {@code seenRecordIds} and {@code mode} to the
 * {@link ExecutionContext} so the listener can decide whether to run the tombstone sweep. {@link #close} is a no-op.
 *
 * <p>
 * Instances are created fresh per batch job by a {@code Supplier} captured in the cluster element definition (see
 * {@link com.bytechef.component.ai.vectorstore.knowledgebase.KnowledgeBaseComponentHandler}). Each instance carries
 * per-job mutable state ({@code sourceId}, {@code mode}, {@code kbId}, {@code seenRecordIds}) and is therefore not safe
 * to share across concurrent jobs.
 *
 * @author Ivica Cardic
 */
public class KnowledgeBaseItemWriter implements ItemWriter {

    public static final String SOURCE_ID = "sourceId";
    public static final String MODE = "mode";

    public static final String MODE_FULL_REPLACE = "FULL_REPLACE";
    public static final String MODE_PARTIAL = "PARTIAL";

    public static final String SEEN_RECORD_IDS_KEY = "knowledgeBaseSource.seenRecordIds";
    public static final String MODE_KEY = "knowledgeBaseSource.mode";

    /**
     * The account this run acted for, flushed alongside {@code seenRecordIds} so the post-job tombstone sweep can be
     * scoped to it.
     *
     * <p>
     * Two keys, written together and read together, because the pair IS the owner: an id without a type belongs to
     * nobody and must not be mistaken for the vendor, whose runs write neither key.
     */
    public static final String OWNER_ID_KEY = "knowledgeBaseSource.ownerId";
    public static final String OWNER_TYPE_KEY = "knowledgeBaseSource.ownerType";

    private final KnowledgeBaseSourceService knowledgeBaseSourceService;
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectProvider<OwnerResolver> ownerResolverProvider;

    private Long sourceId;
    private String mode;
    private Long kbId;
    private Set<String> seenRecordIds;

    /**
     * Resolved once at {@link #open}, where the run's context is still in hand, and stamped onto every document row
     * this run creates. {@link #write} runs on the same thread, but the chunker that turns those rows into chunks does
     * not run at all until a message is picked up later, by which point nothing can be asked who this run was for -- so
     * the answer has to be written down, not re-derived.
     */
    private Optional<Owner> owner = Optional.empty();

    @Nullable
    private Map<String, ?> metadataFieldsWhitelist;

    @SuppressFBWarnings("EI2")
    public KnowledgeBaseItemWriter(
        KnowledgeBaseSourceService knowledgeBaseSourceService,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService, KnowledgeBaseService knowledgeBaseService,
        ObjectProvider<OwnerResolver> ownerResolverProvider) {

        this.knowledgeBaseSourceService = knowledgeBaseSourceService;
        this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.ownerResolverProvider = ownerResolverProvider;
    }

    @Override
    public void open(
        Parameters inputParameters, Parameters connectionParameters, Context context,
        ExecutionContext executionContext) {

        this.sourceId = inputParameters.getRequiredLong(SOURCE_ID);
        this.mode = inputParameters.getString(MODE, MODE_FULL_REPLACE);

        if (!MODE_FULL_REPLACE.equals(mode) && !MODE_PARTIAL.equals(mode)) {
            throw new IllegalArgumentException(
                "Unknown sync mode '" + mode + "': expected '" + MODE_FULL_REPLACE + "' or '" + MODE_PARTIAL + "'");
        }

        KnowledgeBaseSource source = knowledgeBaseSourceService.fetch(sourceId)
            .orElseThrow(() -> new IllegalStateException("KnowledgeBaseSource " + sourceId + " not found"));

        Admission admission = admit(context, source.getKnowledgeBaseId());

        KnowledgeBase knowledgeBase = admission.knowledgeBase();

        this.kbId = knowledgeBase.getId();
        this.owner = admission.owner();
        this.seenRecordIds = new HashSet<>();
        // Captured once at open() rather than per-record so a mid-job edit of the source row does not split the run's
        // tags between two whitelist policies.
        this.metadataFieldsWhitelist = source.getMetadataFields();
    }

    @Override
    @SuppressFBWarnings(
        value = "UNSAFE_HASH_EQUALS",
        justification = "Hash comparison is used for change detection only, not for security-sensitive verification.")
    public void write(List<? extends Map<String, Object>> items) {
        Instant now = Instant.now();

        for (Map<String, Object> sourceRecord : items) {
            Object idValue = sourceRecord.get("id");

            if (idValue == null) {
                throw new IllegalArgumentException(
                    "Record missing required 'id' field: " + sourceRecord);
            }

            String sourceRecordId = String.valueOf(idValue);

            seenRecordIds.add(sourceRecordId);

            String payloadHash = PayloadHashUtil.hash(sourceRecord);

            // Scoped to this run's own account. The sync key alone no longer names one document: two accounts syncing
            // one shared source each keep their own copy of a record, so an unscoped lookup handed this run the OTHER
            // account's row and the replace path below rewrote its content under an owner that never changed.
            Optional<KnowledgeBaseDocument> existingOptional = knowledgeBaseDocumentService.findSyncedDocument(
                sourceId, sourceRecordId, owner);

            String name = extractName(sourceRecord, sourceRecordId);
            String text = extractText(sourceRecord);

            if (existingOptional.isPresent()
                && payloadHash.equals(existingOptional.get()
                    .getSyncedPayloadHash())
                && existingOptional.get()
                    .getDeletedAt() == null) {

                // Unchanged-record fast path: bump heartbeat only -- no file rewrite, no chunker re-run, no
                // KnowledgeBaseDocumentEvent. Upstream of the service-side fast path in replaceSyncedDocument so we
                // also avoid that method's findById round-trip on every unchanged record.
                knowledgeBaseDocumentService.bumpLastSeenAt(existingOptional.get(), now);
            } else if (existingOptional.isPresent()) {
                // Changed-record path (or reappeared after tombstone). replaceSyncedDocument also clears deletedAt
                // and resets status to STATUS_UPLOADED to re-trigger the chunker pipeline.
                knowledgeBaseDocumentService.replaceSyncedDocument(
                    existingOptional.get()
                        .getId(),
                    name, text, sourceRecord, metadataFieldsWhitelist, payloadHash, now);
            } else {
                knowledgeBaseDocumentService.createSyncedDocument(
                    kbId, sourceId, sourceRecordId, name, text, sourceRecord, metadataFieldsWhitelist, payloadHash,
                    now, owner.orElse(null));
            }
        }
    }

    /**
     * The owner travels with {@code seenRecordIds} because the two are consumed together. The tombstone sweep that
     * reads them runs in a job listener, after this instance is gone and with no run context left to resolve an owner
     * from -- the same crossing the document row solves for the chunker. A sweep that could not learn the owner would
     * key on the source alone and reap another account's documents from the same source.
     */
    @Override
    public void update(
        Parameters inputParameters, Parameters connectionParameters, Context context,
        ExecutionContext executionContext) {

        executionContext.put(SEEN_RECORD_IDS_KEY, new ArrayList<>(seenRecordIds));
        executionContext.put(MODE_KEY, mode);

        if (owner.isPresent()) {
            Owner curOwner = owner.get();

            OwnerType ownerType = curOwner.type();

            executionContext.putLong(OWNER_ID_KEY, curOwner.id());
            executionContext.putInt(OWNER_TYPE_KEY, ownerType.ordinal());
        }
    }

    @Override
    public void close() {
        // No-op; tombstone sweep + status flip are handled by the KnowledgeBaseSourceSyncJobListener after the job
        // completes (Task 32), gated by mode.
    }

    /**
     * The admission gate this step runs before it binds a knowledge base, at lifecycle entry rather than per record, so
     * a refusal means {@link #write} is never entered and none of its three mutation paths can run.
     *
     * <p>
     * A {@link KnowledgeBaseSource} row names a knowledge base and constrains nothing else -- no owner, no pool, no
     * environment -- and {@code sourceId} arrives as an ordinary expression-enabled parameter, so the id alone decides
     * which knowledge base this run writes to and, in {@code FULL_REPLACE}, tombstones.
     *
     * <p>
     * Which question can be asked depends on whether the run's owner is knowable. The data stream delegate now carries
     * the job principal into this step's context, so an embedded run for a connected user resolves an owner and gets
     * the full gate: the id must name a knowledge base in a pool that owner may read, which is EMBEDDED alone. That is
     * the pool separation this step used to lack.
     *
     * <p>
     * A run with no owner -- the vendor's own automation sync, and every Community run -- gets no gate at all, and
     * deliberately NOT the full one: an empty owner opens both pools and admits every knowledge base in the tenant, so
     * resolving with it would be the opposite answer rather than a weaker one. Such a run has no pool to check either,
     * so it falls back to the fully unscoped read: a knowledge base is no longer assigned to one account, so there is
     * nothing left for it to refuse. What separates two accounts sharing a knowledge base is the owner on the chunks
     * inside it, applied by {@code KnowledgeBaseVectorStoreWrapper} when the chunks are written, not anything this step
     * can see.
     */
    private Admission admit(Context context, long knowledgeBaseId) {
        if (context instanceof ClusterElementContext clusterElementContext) {
            Optional<Owner> resolvedOwner = OwnerResolution.resolve(clusterElementContext, ownerResolverProvider);

            if (resolvedOwner.isPresent()) {
                return new Admission(
                    KnowledgeBaseOptionsUtils.resolveKnowledgeBase(
                        knowledgeBaseService, knowledgeBaseId, resolvedOwner),
                    resolvedOwner);
            }
        }

        return new Admission(
            KnowledgeBaseOptionsUtils.readUnscopedKnowledgeBase(knowledgeBaseService, knowledgeBaseId),
            Optional.empty());
    }

    /**
     * The knowledge base this run was admitted to, together with the owner it was admitted AS.
     *
     * <p>
     * The two travel as one because discarding the second is precisely how the row axis went inert: the gate resolved
     * an owner, used it to answer whether the run could write here, and then created rows carrying nobody.
     */
    private record Admission(KnowledgeBase knowledgeBase, Optional<Owner> owner) {
    }

    private static String extractName(Map<String, Object> sourceRecord, String sourceRecordId) {
        Object nameValue = sourceRecord.get("name");

        if (nameValue == null) {
            return sourceRecordId;
        }

        return String.valueOf(nameValue);
    }

    private static String extractText(Map<String, Object> sourceRecord) {
        Object textValue = sourceRecord.get("text");

        if (textValue == null) {
            return "";
        }

        return String.valueOf(textValue);
    }
}

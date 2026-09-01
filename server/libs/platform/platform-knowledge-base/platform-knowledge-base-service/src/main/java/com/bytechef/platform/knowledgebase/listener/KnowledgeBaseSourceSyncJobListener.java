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

package com.bytechef.platform.knowledgebase.listener;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSource;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSourceStatus;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseSourceService;
import com.bytechef.platform.owner.Owner;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link JobExecutionListener} that detects DataStream sync jobs whose DESTINATION cluster element is
 * {@code knowledgeBase.writeAsDocument} and applies mode-aware status updates plus an optional tombstone sweep.
 *
 * <p>
 * On COMPLETED: the listener reads the {@code mode} parameter from the destination's {@code inputParameters} (defaults
 * to {@code FULL_REPLACE} when missing — backward compatible with workflows that pre-date the parameter):
 * <ul>
 * <li>{@code FULL_REPLACE}: aggregates {@code seenRecordIds} AND the run's owner from each step's
 * {@code ExecutionContext} (both populated by {@code KnowledgeBaseItemWriter}), calls
 * {@link KnowledgeBaseDocumentService#tombstoneUnseen}, evicts the tombstoned documents' chunks, chunk content files,
 * and vector-store rows via {@link KnowledgeBaseDocumentFacade#sweepTombstonedDocumentChunks} (best-effort — a sweep
 * failure never fails the structured sync; the next FULL_REPLACE run retries because tombstoned rows keep their
 * {@code deleted_at}), then flips status to {@code READY} along with {@code lastSyncRunAt} and
 * {@code lastSyncJobExecutionId}.</li>
 * <li>{@code PARTIAL}: skips the tombstone sweep entirely and updates {@code lastSyncRunAt} +
 * {@code lastSyncJobExecutionId} without touching status — a {@code BUILDING_PREVIEW} source stays
 * {@code BUILDING_PREVIEW} until a {@code FULL_REPLACE} run completes; a {@code READY} source stays {@code READY}.</li>
 * </ul>
 *
 * <p>
 * On FAILED: updates the source status to {@code FAILED} unless the source was previously {@code READY} — preserving
 * the last good state so a transient sync failure doesn't downgrade a working source.
 *
 * <p>
 * Non-Knowledge-Base DataStream jobs are passed through unchanged: the listener inspects the {@code DESTINATION} job
 * parameter and short-circuits when the component/cluster-element name doesn't match. CS sync jobs (destination
 * {@code contextStore.writeToReplica}) flow through their own listener untouched.
 *
 * <p>
 * The tombstone sweep is scoped to the account the run acted for, not to the source. A source used to identify one
 * account's documents because the knowledge base behind it did; a SHARED knowledge base holds the documents of many
 * accounts, and two of them may sync the same source into it, so a sweep keyed on {@code source_id} alone tombstoned
 * the other account's rows and then deleted their chunks out of the vector store by raw id.
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnProperty(prefix = "bytechef.ai.knowledge-base", name = "enabled", havingValue = "true")
public class KnowledgeBaseSourceSyncJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSourceSyncJobListener.class);

    private static final String DESTINATION_KEY = "DESTINATION";
    private static final String COMPONENT_NAME_KEY = "componentName";
    private static final String CLUSTER_ELEMENT_NAME_KEY = "clusterElementName";
    private static final String INPUT_PARAMETERS_KEY = "inputParameters";

    private static final String KNOWLEDGE_BASE_COMPONENT_NAME = "knowledgeBase";
    private static final String WRITE_AS_DOCUMENT_CLUSTER_ELEMENT_NAME = "writeAsDocument";

    private static final String SOURCE_ID_PARAMETER = "sourceId";
    private static final String MODE_PARAMETER = "mode";

    private static final String MODE_FULL_REPLACE = "FULL_REPLACE";
    private static final String MODE_PARTIAL = "PARTIAL";

    /**
     * Must match {@code KnowledgeBaseItemWriter.SEEN_RECORD_IDS_KEY}; duplicated here to avoid a service-module
     * dependency on the component module that owns the writer.
     */
    private static final String SEEN_RECORD_IDS_KEY = "knowledgeBaseSource.seenRecordIds";

    /**
     * Must match {@code KnowledgeBaseItemWriter.OWNER_ID_KEY} / {@code OWNER_TYPE_KEY}, duplicated for the same reason.
     * Read as a pair and never singly: an id without a type belongs to nobody, and taking it for the vendor would point
     * an account's sweep at the unowned documents.
     */
    private static final String OWNER_ID_KEY = "knowledgeBaseSource.ownerId";
    private static final String OWNER_TYPE_KEY = "knowledgeBaseSource.ownerType";

    private final KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private final KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private final KnowledgeBaseSourceService knowledgeBaseSourceService;

    @SuppressFBWarnings("EI2")
    public KnowledgeBaseSourceSyncJobListener(
        KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade,
        KnowledgeBaseDocumentService knowledgeBaseDocumentService,
        KnowledgeBaseSourceService knowledgeBaseSourceService) {

        this.knowledgeBaseDocumentFacade = knowledgeBaseDocumentFacade;
        this.knowledgeBaseDocumentService = knowledgeBaseDocumentService;
        this.knowledgeBaseSourceService = knowledgeBaseSourceService;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        KnowledgeBaseSyncJobParameters parameters = parseJobParameters(jobExecution);

        if (parameters == null) {
            return;
        }

        Long sourceId = parameters.sourceId();
        String mode = parameters.mode();

        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            Instant now = Instant.now();

            if (MODE_PARTIAL.equals(mode)) {
                log.info("Knowledge Base sync completed in PARTIAL mode: source={}", sourceId);

                knowledgeBaseSourceService.updateLastSyncMetadata(sourceId, now, jobExecution.getId());
            } else {
                SyncRunScope scope = aggregateSyncRunScope(jobExecution);

                if (scope == null) {
                    // The steps disagree about the account this run acted for, which one job's partitions cannot do
                    // by construction. Reaping under either answer would tombstone somebody's documents on a guess, so
                    // nothing is reaped -- the rows keep their content and the next FULL_REPLACE run sweeps them,
                    // exactly as the best-effort chunk sweep below already relies on.
                    log.error(
                        "Knowledge Base sync completed in FULL_REPLACE mode but its steps disagree about the owner: " +
                            "source={} — nothing tombstoned",
                        sourceId);
                } else {
                    Set<String> seenRecordIds = scope.seenRecordIds();
                    Optional<Owner> owner = scope.owner();

                    int tombstoned = knowledgeBaseDocumentService.tombstoneUnseen(sourceId, seenRecordIds, now, owner);

                    int sweptChunks = sweepTombstonedDocumentChunks(sourceId, owner);

                    log.info(
                        "Knowledge Base sync completed in FULL_REPLACE mode: source={} owner={} seen={} " +
                            "tombstoned={} sweptChunks={}",
                        sourceId, owner.orElse(null), seenRecordIds.size(), tombstoned, sweptChunks);
                }

                knowledgeBaseSourceService.updateStatus(
                    sourceId, KnowledgeBaseSourceStatus.READY, now, jobExecution.getId());
            }
        } else {
            log.warn(
                "Knowledge Base sync failed: source={} status={} errors={}",
                sourceId, jobExecution.getStatus(), jobExecution.getAllFailureExceptions());

            Optional<KnowledgeBaseSource> sourceOptional = knowledgeBaseSourceService.fetch(sourceId);

            if (sourceOptional.isEmpty()) {
                return;
            }

            KnowledgeBaseSourceStatus current = sourceOptional.get()
                .getStatus();

            if (current != KnowledgeBaseSourceStatus.READY) {
                knowledgeBaseSourceService.updateStatus(
                    sourceId, KnowledgeBaseSourceStatus.FAILED, null, jobExecution.getId());
            } else {
                // Preserve the READY state but record the failure metadata so the dashboard can surface a stale
                // last-sync timestamp without flipping the user-visible status badge.
                knowledgeBaseSourceService.updateLastSyncMetadata(sourceId, null, jobExecution.getId());
            }
        }
    }

    /**
     * Best-effort eviction of tombstoned documents' chunks and vector-store rows. A failure here must not fail the
     * structured sync or block the status flip to {@code READY} — the tombstoned document rows keep their
     * {@code deleted_at}, so the next FULL_REPLACE run retries the sweep.
     */
    private int sweepTombstonedDocumentChunks(Long sourceId, Optional<Owner> owner) {
        try {
            return knowledgeBaseDocumentFacade.sweepTombstonedDocumentChunks(sourceId, owner);
        } catch (RuntimeException exception) {
            log.warn(
                "Knowledge Base tombstone chunk sweep failed: source={} — structured sync remains intact",
                sourceId, exception);

            return 0;
        }
    }

    private static @Nullable KnowledgeBaseSyncJobParameters parseJobParameters(JobExecution jobExecution) {
        JobParameter<?> destinationParameter = jobExecution.getJobParameters()
            .getParameter(DESTINATION_KEY);

        if (destinationParameter == null || !(destinationParameter.value() instanceof Map<?, ?> destination)) {
            return null;
        }

        if (!KNOWLEDGE_BASE_COMPONENT_NAME.equals(destination.get(COMPONENT_NAME_KEY))
            || !WRITE_AS_DOCUMENT_CLUSTER_ELEMENT_NAME.equals(destination.get(CLUSTER_ELEMENT_NAME_KEY))) {

            return null;
        }

        if (!(destination.get(INPUT_PARAMETERS_KEY) instanceof Map<?, ?> inputParameters)) {
            return null;
        }

        Long sourceId = coerceLong(inputParameters.get(SOURCE_ID_PARAMETER));

        if (sourceId == null) {
            return null;
        }

        Object modeValue = inputParameters.get(MODE_PARAMETER);

        String mode = modeValue instanceof String string && !string.isEmpty()
            ? string
            : MODE_FULL_REPLACE;

        return new KnowledgeBaseSyncJobParameters(sourceId, mode);
    }

    private static @Nullable Long coerceLong(@Nullable Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String string && !string.isEmpty()) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    /**
     * What the run saw and who it acted for, read back off the step contexts the writer flushed them to.
     *
     * <p>
     * Both halves come from the same steps -- the ones carrying {@code seenRecordIds} -- because they answer the same
     * question and the sweep needs both: the record ids say which documents survived, the owner says whose documents
     * are in scope at all. A source names neither. The listener runs after the job, with no run context left to resolve
     * an owner from, so the writer writing it down is the only thing that survives the crossing, exactly as the
     * document row is for the chunker.
     *
     * <p>
     * The owner is read as a PAIR: an id flushed without a type belongs to nobody and is treated as no owner at all,
     * rather than as the vendor's, which would point an account's sweep at the unowned documents.
     *
     * @return the aggregated scope, or {@code null} if the steps disagree about the owner -- impossible for the
     *         partitions of one job, and reaped under neither answer rather than under a guess
     */
    private static @Nullable SyncRunScope aggregateSyncRunScope(JobExecution jobExecution) {
        Set<String> seenRecordIds = new HashSet<>();
        Set<Optional<Owner>> owners = new HashSet<>();

        for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
            ExecutionContext executionContext = stepExecution.getExecutionContext();

            Object value = executionContext.get(SEEN_RECORD_IDS_KEY);

            if (!(value instanceof List<?> list)) {
                continue;
            }

            for (Object item : list) {
                if (item instanceof String string) {
                    seenRecordIds.add(string);
                }
            }

            owners.add(readOwner(executionContext));
        }

        if (owners.size() > 1) {
            return null;
        }

        Optional<Owner> owner = owners.isEmpty() ? Optional.empty() : owners.iterator()
            .next();

        return new SyncRunScope(seenRecordIds, owner);
    }

    private static Optional<Owner> readOwner(ExecutionContext executionContext) {
        Long ownerId = coerceLong(executionContext.get(OWNER_ID_KEY));
        Long ownerType = coerceLong(executionContext.get(OWNER_TYPE_KEY));

        if (ownerId == null || ownerType == null) {
            return Optional.empty();
        }

        OwnerType[] ownerTypes = OwnerType.values();

        if (ownerType < 0 || ownerType >= ownerTypes.length) {
            return Optional.empty();
        }

        return Optional.of(new Owner(ownerTypes[ownerType.intValue()], ownerId));
    }

    private record KnowledgeBaseSyncJobParameters(Long sourceId, String mode) {
    }

    private record SyncRunScope(Set<String> seenRecordIds, Optional<Owner> owner) {
    }
}

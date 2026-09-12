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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bytechef.platform.constant.OwnerType;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSource;
import com.bytechef.platform.knowledgebase.domain.KnowledgeBaseSourceStatus;
import com.bytechef.platform.knowledgebase.facade.KnowledgeBaseDocumentFacade;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseDocumentService;
import com.bytechef.platform.knowledgebase.service.KnowledgeBaseSourceService;
import com.bytechef.platform.owner.Owner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Unit tests for {@link KnowledgeBaseSourceSyncJobListener}. Verifies the listener short-circuits non-Knowledge-Base
 * jobs, applies mode-aware behaviour on COMPLETED (FULL_REPLACE tombstones unseen + flips status to READY; PARTIAL only
 * updates last-sync metadata while preserving status), and preserves the last-good-state on FAILED.
 *
 * @author Ivica Cardic
 */
class KnowledgeBaseSourceSyncJobListenerTest {

    private static final Long SOURCE_ID = 200L;
    private static final long JOB_EXECUTION_ID = 999L;
    private static final long JOB_EXECUTION_ID_BOXED = JOB_EXECUTION_ID;
    private static final String SEEN_RECORD_IDS_KEY = "knowledgeBaseSource.seenRecordIds";
    private static final String OWNER_ID_KEY = "knowledgeBaseSource.ownerId";
    private static final String OWNER_TYPE_KEY = "knowledgeBaseSource.ownerType";

    private static final Owner OWNER = Owner.connectedUser(42L);
    private static final Owner OTHER_OWNER = Owner.connectedUser(43L);

    private KnowledgeBaseDocumentFacade knowledgeBaseDocumentFacade;
    private KnowledgeBaseDocumentService knowledgeBaseDocumentService;
    private KnowledgeBaseSourceService knowledgeBaseSourceService;
    private KnowledgeBaseSourceSyncJobListener listener;

    @BeforeEach
    void setUp() {
        knowledgeBaseDocumentFacade = mock(KnowledgeBaseDocumentFacade.class);
        knowledgeBaseDocumentService = mock(KnowledgeBaseDocumentService.class);
        knowledgeBaseSourceService = mock(KnowledgeBaseSourceService.class);

        listener = new KnowledgeBaseSourceSyncJobListener(
            knowledgeBaseDocumentFacade, knowledgeBaseDocumentService, knowledgeBaseSourceService);
    }

    @Test
    void testAfterJobOnCompletedFullReplaceTombstonesUnseenAndFlipsToReady() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        StepExecution stepExecution = newStepExecution(jobExecution, List.of("rec1", "rec2", "rec3"));

        jobExecution.addStepExecution(stepExecution);

        listener.afterJob(jobExecution);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> seenIdsCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Instant> deletedAtCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), seenIdsCaptor.capture(), deletedAtCaptor.capture(), eq(Optional.empty()));

        assertThat(seenIdsCaptor.getValue()).containsExactlyInAnyOrder("rec1", "rec2", "rec3");
        assertThat(deletedAtCaptor.getValue()).isNotNull();

        ArgumentCaptor<Instant> statusInstantCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(knowledgeBaseSourceService, times(1))
            .updateStatus(eq(SOURCE_ID), eq(KnowledgeBaseSourceStatus.READY), statusInstantCaptor.capture(),
                eq(JOB_EXECUTION_ID_BOXED));

        assertThat(statusInstantCaptor.getValue()).isEqualTo(deletedAtCaptor.getValue());

        verify(knowledgeBaseDocumentFacade, times(1)).sweepTombstonedDocumentChunks(SOURCE_ID, Optional.empty());
        verify(knowledgeBaseSourceService, never()).updateLastSyncMetadata(anyLong(), any(), any());
    }

    @Test
    void testAfterJobOnCompletedFullReplaceSweepFailureStillFlipsToReady() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec1")));

        when(knowledgeBaseDocumentFacade.sweepTombstonedDocumentChunks(SOURCE_ID, Optional.empty()))
            .thenThrow(new RuntimeException("vector store unavailable"));

        listener.afterJob(jobExecution);

        // The chunk sweep is best-effort — its failure must not prevent the tombstone bookkeeping or the READY flip.
        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), any(), any(Instant.class), any());
        verify(knowledgeBaseSourceService, times(1))
            .updateStatus(eq(SOURCE_ID), eq(KnowledgeBaseSourceStatus.READY), any(Instant.class),
                eq(JOB_EXECUTION_ID_BOXED));
    }

    @Test
    void testAfterJobOnCompletedPartialSkipsTombstoneSweep() {
        Map<String, Object> destination = newKnowledgeBaseDestination("PARTIAL");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        StepExecution stepExecution = newStepExecution(jobExecution, List.of("rec1", "rec2"));

        jobExecution.addStepExecution(stepExecution);

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, never()).tombstoneUnseen(anyLong(), any(), any(), any());
        verify(knowledgeBaseDocumentFacade, never()).sweepTombstonedDocumentChunks(anyLong(), any());
        verify(knowledgeBaseSourceService, never())
            .updateStatus(anyLong(), any(KnowledgeBaseSourceStatus.class), any(), any());

        verify(knowledgeBaseSourceService, times(1))
            .updateLastSyncMetadata(eq(SOURCE_ID), any(Instant.class), eq(JOB_EXECUTION_ID_BOXED));
    }

    @Test
    void testAfterJobOnCompletedPartialPreservesBuildingPreviewStatus() {
        // The PARTIAL branch never calls updateStatus — so a BUILDING_PREVIEW source stays BUILDING_PREVIEW
        // regardless of the source's current status. We don't even need to seed the source.
        Map<String, Object> destination = newKnowledgeBaseDestination("PARTIAL");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        listener.afterJob(jobExecution);

        // status stays BUILDING_PREVIEW: no flip to READY; no flip to FAILED.
        verify(knowledgeBaseSourceService, never())
            .updateStatus(anyLong(), any(KnowledgeBaseSourceStatus.class), any(), any());
        verify(knowledgeBaseDocumentService, never()).tombstoneUnseen(anyLong(), any(), any(), any());
    }

    @Test
    void testAfterJobModeDefaultsToFullReplaceWhenAbsent() {
        // Destination input parameters omit "mode" — listener should treat as FULL_REPLACE.
        Map<String, Object> destination = newKnowledgeBaseDestination(null);

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        StepExecution stepExecution = newStepExecution(jobExecution, List.of("rec1"));

        jobExecution.addStepExecution(stepExecution);

        listener.afterJob(jobExecution);

        // tombstoneUnseen + updateStatus(READY) fire — proves FULL_REPLACE branch was taken.
        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), any(), any(Instant.class), any());
        verify(knowledgeBaseSourceService, times(1))
            .updateStatus(eq(SOURCE_ID), eq(KnowledgeBaseSourceStatus.READY), any(Instant.class),
                eq(JOB_EXECUTION_ID_BOXED));
    }

    @Test
    void testAfterJobOnFailedPreservesReadyStatus() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.FAILED, jobParametersWithDestination(destination));

        KnowledgeBaseSource source = new KnowledgeBaseSource();

        source.setStatus(KnowledgeBaseSourceStatus.READY);

        when(knowledgeBaseSourceService.fetch(SOURCE_ID)).thenReturn(Optional.of(source));

        listener.afterJob(jobExecution);

        // Status is preserved — not flipped to FAILED.
        verify(knowledgeBaseSourceService, never())
            .updateStatus(anyLong(), any(KnowledgeBaseSourceStatus.class), any(), any());
        // last-sync metadata is recorded for dashboards.
        verify(knowledgeBaseSourceService, times(1))
            .updateLastSyncMetadata(eq(SOURCE_ID), isNull(), eq(JOB_EXECUTION_ID_BOXED));
        verify(knowledgeBaseDocumentService, never()).tombstoneUnseen(anyLong(), any(), any(), any());
        verify(knowledgeBaseDocumentFacade, never()).sweepTombstonedDocumentChunks(anyLong(), any());
    }

    @Test
    void testAfterJobOnFailedDowngradesNonReadyToFailed() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.FAILED, jobParametersWithDestination(destination));

        KnowledgeBaseSource source = new KnowledgeBaseSource();

        source.setStatus(KnowledgeBaseSourceStatus.BUILDING_PREVIEW);

        when(knowledgeBaseSourceService.fetch(SOURCE_ID)).thenReturn(Optional.of(source));

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, never()).tombstoneUnseen(anyLong(), any(), any(), any());
        verify(knowledgeBaseSourceService, times(1))
            .updateStatus(eq(SOURCE_ID), eq(KnowledgeBaseSourceStatus.FAILED), isNull(), eq(JOB_EXECUTION_ID_BOXED));
    }

    @Test
    void testAfterJobShortCircuitsForNonKnowledgeBaseDestination() {
        Map<String, Object> destination = new HashMap<>();

        // Different component — Context Store lane runs through its own listener.
        destination.put("componentName", "contextStore");
        destination.put("clusterElementName", "writeToReplica");
        destination.put("inputParameters", Map.of("sourceId", SOURCE_ID));

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        StepExecution stepExecution = newStepExecution(jobExecution, List.of("rec1"));

        jobExecution.addStepExecution(stepExecution);

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, never()).tombstoneUnseen(anyLong(), any(), any(), any());
        verify(knowledgeBaseSourceService, never())
            .updateStatus(anyLong(), any(KnowledgeBaseSourceStatus.class), any(), any());
        verify(knowledgeBaseSourceService, never()).updateLastSyncMetadata(anyLong(), any(), any());
    }

    @Test
    void testAfterJobAggregatesSeenIdsFromMultipleSteps() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec1", "rec2")));
        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec2", "rec3")));

        listener.afterJob(jobExecution);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> seenIdsCaptor = ArgumentCaptor.forClass(Set.class);

        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), seenIdsCaptor.capture(), any(Instant.class), any());

        assertThat(new HashSet<>(seenIdsCaptor.getValue())).containsExactlyInAnyOrder("rec1", "rec2", "rec3");
    }

    @Test
    void testAfterJobMissingSourceLogsAndShortCircuits() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.FAILED, jobParametersWithDestination(destination));

        when(knowledgeBaseSourceService.fetch(SOURCE_ID)).thenReturn(Optional.empty());

        // Should not throw NPE — listener bails out cleanly when the source row is missing.
        listener.afterJob(jobExecution);

        verify(knowledgeBaseSourceService, never())
            .updateStatus(anyLong(), any(KnowledgeBaseSourceStatus.class), any(), any());
        verify(knowledgeBaseSourceService, never()).updateLastSyncMetadata(anyLong(), any(), any());
    }

    /**
     * The owner the run acted for reaches BOTH destructive calls. A source no longer identifies one account's
     * documents, so a sweep that reached them with no owner reaped every account's rows from that source.
     */
    @Test
    void testAfterJobScopesTheTombstoneSweepToTheRunsOwner() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec1"), OWNER));

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), any(), any(Instant.class), eq(Optional.of(OWNER)));
        verify(knowledgeBaseDocumentFacade, times(1)).sweepTombstonedDocumentChunks(SOURCE_ID, Optional.of(OWNER));
    }

    /**
     * A run whose steps flushed no owner is the vendor's, and reaches the unowned documents rather than everything. The
     * pair with the test above: a guard that always passed {@code Optional.empty()} would satisfy this one alone.
     */
    @Test
    void testAfterJobScopesAVendorRunToTheUnownedDocuments() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec1")));

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), any(), any(Instant.class), eq(Optional.empty()));
        verify(knowledgeBaseDocumentFacade, times(1)).sweepTombstonedDocumentChunks(SOURCE_ID, Optional.empty());
    }

    /**
     * An owner id flushed without its type belongs to nobody. Reading it as the vendor's would point an account's sweep
     * at the unowned documents, so the pair is read as a pair and a half of it is no owner at all.
     */
    @Test
    void testAfterJobTreatsAnOwnerIdWithoutATypeAsNoOwner() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        StepExecution stepExecution = newStepExecution(jobExecution, List.of("rec1"));

        ExecutionContext executionContext = stepExecution.getExecutionContext();

        executionContext.putLong(OWNER_ID_KEY, OWNER.id());

        jobExecution.addStepExecution(stepExecution);

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, times(1))
            .tombstoneUnseen(eq(SOURCE_ID), any(), any(Instant.class), eq(Optional.empty()));
    }

    /**
     * Steps that disagree about the owner cannot happen for the partitions of one job, and if they somehow did there is
     * no answer to reap under. Nothing is tombstoned and nothing is swept; the status still flips, because the sync
     * itself succeeded and the next FULL_REPLACE run reaps what this one did not.
     */
    @Test
    void testAfterJobReapsNothingWhenStepsDisagreeAboutTheOwner() {
        Map<String, Object> destination = newKnowledgeBaseDestination("FULL_REPLACE");

        JobExecution jobExecution = newJobExecution(BatchStatus.COMPLETED, jobParametersWithDestination(destination));

        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec1"), OWNER));
        jobExecution.addStepExecution(newStepExecution(jobExecution, List.of("rec2"), OTHER_OWNER));

        listener.afterJob(jobExecution);

        verify(knowledgeBaseDocumentService, never()).tombstoneUnseen(anyLong(), any(), any(), any());
        verify(knowledgeBaseDocumentFacade, never()).sweepTombstonedDocumentChunks(anyLong(), any());
        verify(knowledgeBaseSourceService, times(1))
            .updateStatus(eq(SOURCE_ID), eq(KnowledgeBaseSourceStatus.READY), any(Instant.class),
                eq(JOB_EXECUTION_ID_BOXED));
    }

    private static Map<String, Object> newKnowledgeBaseDestination(String mode) {
        Map<String, Object> destination = new HashMap<>();

        destination.put("componentName", "knowledgeBase");
        destination.put("clusterElementName", "writeAsDocument");

        Map<String, Object> inputParameters = new HashMap<>();

        inputParameters.put("sourceId", SOURCE_ID);

        if (mode != null) {
            inputParameters.put("mode", mode);
        }

        destination.put("inputParameters", inputParameters);

        return destination;
    }

    private static JobParameters jobParametersWithDestination(Map<String, Object> destination) {
        Set<JobParameter<?>> parameters = new HashSet<>();

        parameters.add(new JobParameter<>("DESTINATION", destination, Map.class));

        return new JobParameters(parameters);
    }

    private static JobExecution newJobExecution(BatchStatus status, JobParameters jobParameters) {
        JobInstance jobInstance = new JobInstance(1L, "dataStreamJob");

        JobExecution jobExecution = new JobExecution(JOB_EXECUTION_ID, jobInstance, jobParameters);

        jobExecution.setStatus(status);

        return jobExecution;
    }

    private static StepExecution newStepExecution(JobExecution jobExecution, List<String> seenRecordIds) {
        return newStepExecution(jobExecution, seenRecordIds, null);
    }

    /**
     * A vendor's step writes neither owner key, exactly as the writer does -- absence is the vendor, not a default.
     */
    private static StepExecution newStepExecution(
        JobExecution jobExecution, List<String> seenRecordIds, @Nullable Owner owner) {

        StepExecution stepExecution = new StepExecution("step1", jobExecution);

        ExecutionContext executionContext = new ExecutionContext();

        executionContext.put(SEEN_RECORD_IDS_KEY, new ArrayList<>(seenRecordIds));

        if (owner != null) {
            OwnerType ownerType = owner.type();

            executionContext.putLong(OWNER_ID_KEY, owner.id());
            executionContext.putInt(OWNER_TYPE_KEY, ownerType.ordinal());
        }

        stepExecution.setExecutionContext(executionContext);

        return stepExecution;
    }
}

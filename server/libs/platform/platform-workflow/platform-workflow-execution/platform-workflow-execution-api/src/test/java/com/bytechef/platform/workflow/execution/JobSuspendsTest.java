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

package com.bytechef.platform.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.error.ExecutionError;
import com.bytechef.platform.component.constant.MetadataConstants;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author Ivica Cardic
 */
class JobSuspendsTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-17T10:05:00Z");

    @Test
    void testATaskExecutionWithAJobResumeIdIsSuspending() {
        TaskExecution suspendingTaskExecution = new TaskExecution();

        suspendingTaskExecution.putMetadata(MetadataConstants.JOB_RESUME_ID, "resume-1");

        assertThat(JobSuspends.isSuspending(suspendingTaskExecution)).isTrue();
        assertThat(JobSuspends.isSuspending(new TaskExecution())).isFalse();
    }

    @Test
    void testAStoppedJobSuspendedForAnApprovalIsAwaitingApproval() {
        Job job = job(
            Job.Status.STOPPED,
            Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1", MetadataConstants.APPROVAL_RESUME, true));

        assertThat(JobSuspends.isAwaitingApproval(job)).isTrue();
        assertThat(JobSuspends.isSuspendedForApproval(job)).isTrue();
    }

    @Test
    void testAStoppedJobSuspendedForInputIsNotAwaitingApproval() {
        Job job = job(Job.Status.STOPPED, Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1"));

        assertThat(JobSuspends.isAwaitingApproval(job)).isFalse();
        assertThat(JobSuspends.isSuspendedForApproval(job)).isFalse();
    }

    @Test
    void testARunningJobIsNotAwaitingApprovalButWasSuspendedForOne() {
        Job job = job(
            Job.Status.STARTED,
            Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1", MetadataConstants.APPROVAL_RESUME, true));

        assertThat(JobSuspends.isAwaitingApproval(job)).isFalse();
        assertThat(JobSuspends.isSuspendedForApproval(job)).isTrue();
    }

    @Test
    void testAStoppedApprovalJobWithoutAResumeIdIsNotAwaitingApproval() {
        Job job = job(Job.Status.STOPPED, Map.of(MetadataConstants.APPROVAL_RESUME, true));

        assertThat(JobSuspends.isAwaitingApproval(job)).isFalse();
    }

    @Test
    void testFailExpiredApprovalFailsTheJobAndSpendsItsResumeId() {
        Job job = job(
            Job.Status.STOPPED,
            Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1", MetadataConstants.APPROVAL_RESUME, true));

        JobSuspends.failExpiredApproval(job, EXPIRES_AT, NOW);

        ExecutionError error = job.getError();

        assertThat(job.getStatus()).isEqualTo(Job.Status.FAILED);
        assertThat(job.getEndDate()).isEqualTo(NOW);
        assertThat(error.getMessage()).isEqualTo(JobSuspends.approvalExpiredReason(EXPIRES_AT));
        assertThat(job.getMetadata(MetadataConstants.CONSUMED_JOB_RESUME_ID)).isEqualTo("resume-1");
        assertThat(job.getMetadata(MetadataConstants.EXPIRED_JOB_RESUME_ID)).isEqualTo("resume-1");
        assertThat(JobSuspends.isApprovalExpired(job)).isTrue();
        assertThat(JobSuspends.isApprovalCancelled(job)).isFalse();
    }

    @Test
    void testCloseCancelledApprovalSpendsTheResumeIdWithoutFailingTheJob() {
        Job job = job(
            Job.Status.STOPPED,
            Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1", MetadataConstants.APPROVAL_RESUME, true));

        JobSuspends.closeCancelledApproval(job);

        assertThat(job.getStatus()).isEqualTo(Job.Status.STOPPED);
        assertThat(job.getMetadata(MetadataConstants.CONSUMED_JOB_RESUME_ID)).isEqualTo("resume-1");
        assertThat(JobSuspends.isApprovalCancelled(job)).isTrue();
        assertThat(JobSuspends.isApprovalExpired(job)).isFalse();
    }

    @Test
    void testAnApprovalExpiredUnderAnEarlierResumeIdIsNotTheCurrentOne() {
        Job job = job(
            Job.Status.STOPPED,
            Map.of(
                MetadataConstants.JOB_RESUME_ID, "resume-2", MetadataConstants.EXPIRED_JOB_RESUME_ID, "resume-1",
                MetadataConstants.CANCELLED_JOB_RESUME_ID, "resume-1"));

        assertThat(JobSuspends.isApprovalExpired(job)).isFalse();
        assertThat(JobSuspends.isApprovalCancelled(job)).isFalse();
    }

    private static Job job(Job.Status status, Map<String, ?> metadata) {
        Job job = new Job();

        job.setMetadata(metadata);
        job.setStatus(status);

        return job;
    }
}

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

package com.bytechef.platform.coordinator.monitor;

import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.domain.TaskExecution;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.commons.util.MapUtils;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.component.definition.SuspendUtils;
import com.bytechef.platform.notification.domain.NotificationEvent;
import com.bytechef.platform.notification.handler.NotificationHandlerContext;
import com.bytechef.platform.notification.handler.NotificationHandlerRegistry;
import com.bytechef.platform.notification.handler.NotificationSenderRegistry;
import com.bytechef.platform.notification.service.NotificationService;
import com.bytechef.platform.workflow.execution.JobSuspends;
import com.bytechef.platform.workflow.execution.token.ApprovalFormUrls;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import com.bytechef.tenant.TenantContext;
import com.bytechef.tenant.service.TenantService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Escalates runs that have been paused on an approval longer than the configured escalation window and are still
 * unresolved. Where {@link ApprovalReminderMonitor} nudges the original approvers shortly before expiry, this sweep
 * fires a {@code JOB_APPROVAL_ESCALATED} notification once the approval has gone unanswered for
 * {@code bytechef.workflow.execution.approval-escalation.after} — an operator subscribes a <b>different</b>
 * {@code Notification} (a manager, an on-call channel) to that event so the request is re-delivered to a second
 * recipient. Delivery goes through the same central notification registry the job-status notifications use.
 *
 * <p>
 * The escalation window is measured from when the approval was raised (the suspend's {@code approvalRaisedAt}, else the
 * suspended task execution's start, which predates the approval for a tool gate raised mid-run). With no {@code after}
 * configured the sweep is a no-op — escalation is opt-in. An approval whose expiry has already passed is left to
 * {@link ApprovalExpiryMonitor}, and suspends that are not approvals are ignored. Escalations are per-suspend
 * idempotent: the sweep claims the escalation under {@code approvalEscalatedAt} in job metadata (cleared on resume)
 * before sending, so two coordinator replicas cannot both send it, and keeps the claim only when at least one
 * notification was delivered. When no notification is configured or every send fails, the claim is released and the
 * next sweep tries again. Disable the sweep with {@code bytechef.workflow.execution.approval-escalation.enabled=false}.
 * </p>
 *
 * <p>
 * In the distributed EE deployment the stale-STOPPED-jobs finder is served over REST by the execution app (see
 * {@code RemoteJobServiceClient#getStaleJobs}), so the sweep runs there too; the {@code UnsupportedOperationException}
 * catch below is a defensive fallback only.
 * </p>
 *
 * @author Ivica Cardic
 */
public class ApprovalEscalationMonitor {

    private static final String APPROVAL_ESCALATED_AT = "approvalEscalatedAt";

    private static final Logger log = LoggerFactory.getLogger(ApprovalEscalationMonitor.class);

    private final @Nullable Duration after;
    private final ApprovalNotificationDispatcher approvalNotificationDispatcher;
    private final @Nullable ApprovalTokens approvalTokens;
    private final JobService jobService;
    private final @Nullable String publicUrl;
    private final TaskExecutionService taskExecutionService;
    private final TenantService tenantService;

    @SuppressFBWarnings("EI")
    public ApprovalEscalationMonitor(
        @Nullable Duration after, @Nullable ApprovalTokens approvalTokens, JobService jobService,
        NotificationHandlerRegistry notificationHandlerRegistry,
        NotificationSenderRegistry notificationSenderRegistry, NotificationService notificationService,
        @Nullable String publicUrl, TaskExecutionService taskExecutionService, TenantService tenantService) {

        this.after = after;
        this.approvalNotificationDispatcher = new ApprovalNotificationDispatcher(
            notificationHandlerRegistry, notificationSenderRegistry, notificationService);
        this.approvalTokens = approvalTokens;
        this.jobService = jobService;
        this.publicUrl = publicUrl;
        this.taskExecutionService = taskExecutionService;
        this.tenantService = tenantService;
    }

    @Scheduled(initialDelayString = "PT8M", fixedDelayString = "PT15M")
    public void escalateStalledApprovals() {
        if (after == null || after.isZero() || after.isNegative()) {
            return;
        }

        for (String tenantId : tenantService.getTenantIds()) {
            try {
                TenantContext.runWithTenantId(tenantId, this::escalateCurrentTenant);
            } catch (UnsupportedOperationException unsupportedOperationException) {
                if (log.isDebugEnabled()) {
                    log.debug(
                        "Stale-job finder unavailable for tenant {}; skipping approval-escalation sweep", tenantId);
                }
            } catch (Exception exception) {
                log.warn("Approval-escalation sweep failed for tenant {}: {}", tenantId, exception.getMessage());
            }
        }
    }

    private void escalateCurrentTenant() {
        Instant now = Instant.now();

        for (Job job : jobService.getStaleJobs(Job.Status.STOPPED, now)) {
            try {
                escalateJob(job, now);
            } catch (RuntimeException runtimeException) {
                log.warn("Could not process the approval escalation for job {}", job.getId(), runtimeException);
            }
        }
    }

    private void escalateJob(Job job, Instant now) {
        Object jobResumeId = job.getMetadata(MetadataConstants.JOB_RESUME_ID);

        if (jobResumeId == null || !JobSuspends.isSuspendedForApproval(job) ||
            job.getMetadata(APPROVAL_ESCALATED_AT) != null) {

            return;
        }

        TaskExecution taskExecution = fetchSuspendedTaskExecution(job);

        if (taskExecution == null) {
            return;
        }

        Instant raisedAt = SuspendUtils.extractApprovalRaisedAt(taskExecution.getMetadata());

        if (raisedAt == null) {
            raisedAt = taskExecution.getStartDate();
        }

        if (raisedAt == null || raisedAt.plus(after)
            .isAfter(now)) {

            return;
        }

        Instant expiresAt = SuspendUtils.extractSuspendExpiresAt(taskExecution.getMetadata());

        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return;
        }

        Job claimedJob;

        try {
            claimedJob = putMarker(job, now.toString());
        } catch (OptimisticLockingFailureException optimisticLockingFailureException) {
            return;
        }

        ApprovalNotificationDispatcher.Result result;

        try {
            result = sendEscalation(job, jobResumeId.toString(), expiresAt);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not send approval escalation for job {}", job.getId(), runtimeException);

            result = ApprovalNotificationDispatcher.Result.FAILED;
        }

        if (result != ApprovalNotificationDispatcher.Result.DELIVERED) {
            releaseClaim(claimedJob);
        }
    }

    private ApprovalNotificationDispatcher.Result sendEscalation(
        Job job, String jobResumeId, @Nullable Instant expiresAt) {

        NotificationEvent.Type eventType = NotificationEvent.Type.JOB_APPROVAL_ESCALATED;

        String formUrl = ApprovalFormUrls.buildFormUrl(publicUrl, jobResumeId, approvalTokens, expiresAt)
            .orElse(null);

        NotificationHandlerContext notificationHandlerContext = new NotificationHandlerContext.Builder()
            .approvalExpiresAt(expiresAt == null ? null : expiresAt.toString())
            .approvalFormUrl(formUrl)
            .eventType(eventType)
            .jobId(job.getId())
            .jobName(job.getLabel())
            .build();

        return approvalNotificationDispatcher.dispatch(eventType, notificationHandlerContext);
    }

    private void releaseClaim(Job claimedJob) {
        try {
            putMarker(claimedJob, null);
        } catch (RuntimeException runtimeException) {
            log.warn(
                "Could not release the approval escalation claim on job {}; it will not be retried",
                claimedJob.getId(), runtimeException);
        }
    }

    private Job putMarker(Job job, @Nullable String escalatedAt) {
        Map<String, Object> metadata = new HashMap<>(job.getMetadata());

        if (escalatedAt == null) {
            metadata.remove(APPROVAL_ESCALATED_AT);
        } else {
            metadata.put(APPROVAL_ESCALATED_AT, escalatedAt);
        }

        job.setMetadata(metadata);

        return jobService.update(job);
    }

    private @Nullable TaskExecution fetchSuspendedTaskExecution(Job job) {
        Long taskExecutionResumeId = MapUtils.getLong(job.getMetadata(), MetadataConstants.TASK_EXECUTION_RESUME_ID);

        if (taskExecutionResumeId == null) {
            return null;
        }

        try {
            return taskExecutionService.getTaskExecution(taskExecutionResumeId);
        } catch (RuntimeException runtimeException) {
            log.warn(
                "Could not read the suspended task execution {} of job {}; skipping its escalation this sweep",
                taskExecutionResumeId, job.getId(), runtimeException);

            return null;
        }
    }
}

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
 * Reminds subscribed notification targets about runs paused on an approval that is about to expire. A suspended run
 * sits STOPPED until someone resolves it; once the suspend expiry passes, {@link ApprovalExpiryMonitor} fails the run.
 * This sweep fires a {@code JOB_APPROVAL_EXPIRING} notification through the central notification registry while there
 * is still time to act — delivery targets are the admin-configured {@code Notification} rows, the same registry the
 * job-status notifications use. Only runs paused on an approval are reminded, not other suspends. Reminders are per-run
 * idempotent: the sweep claims the reminder under {@code approvalReminderSentAt} in the job metadata before sending, so
 * two coordinator replicas cannot both send it, and keeps the claim only when at least one notification was delivered.
 * When no notification is configured or every send fails, the claim is released and the next sweep tries again.
 *
 * <p>
 * The lead time defaults to 24 hours before expiry and is configurable via
 * {@code bytechef.workflow.execution.approval-reminder.lead-time}; disable the sweep entirely with
 * {@code bytechef.workflow.execution.approval-reminder.enabled=false}. Approvals whose total lifetime is shorter than
 * the lead time are reminded on the first sweep after they pause. In the distributed EE deployment the
 * stale-STOPPED-jobs finder is served over REST by the execution app (see {@code RemoteJobServiceClient#getStaleJobs}),
 * so the sweep runs there too; the {@code UnsupportedOperationException} catch below is a defensive fallback only.
 * </p>
 *
 * @author Ivica Cardic
 */
public class ApprovalReminderMonitor {

    private static final String APPROVAL_REMINDER_SENT_AT = "approvalReminderSentAt";

    private static final Logger log = LoggerFactory.getLogger(ApprovalReminderMonitor.class);

    private final ApprovalNotificationDispatcher approvalNotificationDispatcher;
    private final @Nullable ApprovalTokens approvalTokens;
    private final JobService jobService;
    private final Duration leadTime;
    private final @Nullable String publicUrl;
    private final TaskExecutionService taskExecutionService;
    private final TenantService tenantService;

    @SuppressFBWarnings("EI")
    public ApprovalReminderMonitor(
        @Nullable ApprovalTokens approvalTokens, JobService jobService, Duration leadTime,
        NotificationHandlerRegistry notificationHandlerRegistry,
        NotificationSenderRegistry notificationSenderRegistry, NotificationService notificationService,
        @Nullable String publicUrl, TaskExecutionService taskExecutionService, TenantService tenantService) {

        this.approvalNotificationDispatcher = new ApprovalNotificationDispatcher(
            notificationHandlerRegistry, notificationSenderRegistry, notificationService);
        this.approvalTokens = approvalTokens;
        this.jobService = jobService;
        this.leadTime = leadTime;
        this.publicUrl = publicUrl;
        this.taskExecutionService = taskExecutionService;
        this.tenantService = tenantService;
    }

    @Scheduled(initialDelayString = "PT6M", fixedDelayString = "PT15M")
    public void remindExpiringApprovals() {
        for (String tenantId : tenantService.getTenantIds()) {
            try {
                TenantContext.runWithTenantId(tenantId, this::remindCurrentTenant);
            } catch (UnsupportedOperationException unsupportedOperationException) {
                if (log.isDebugEnabled()) {
                    log.debug(
                        "Stale-job finder unavailable for tenant {}; skipping approval-reminder sweep", tenantId);
                }
            } catch (Exception exception) {
                log.warn("Approval-reminder sweep failed for tenant {}: {}", tenantId, exception.getMessage());
            }
        }
    }

    private void remindCurrentTenant() {
        Instant now = Instant.now();

        for (Job job : jobService.getStaleJobs(Job.Status.STOPPED, now)) {
            try {
                remindJob(job, now);
            } catch (RuntimeException runtimeException) {
                log.warn("Could not process the approval reminder for job {}", job.getId(), runtimeException);
            }
        }
    }

    private void remindJob(Job job, Instant now) {
        Object jobResumeId = job.getMetadata(MetadataConstants.JOB_RESUME_ID);

        if (jobResumeId == null || !JobSuspends.isSuspendedForApproval(job) ||
            job.getMetadata(APPROVAL_REMINDER_SENT_AT) != null) {

            return;
        }

        Instant expiresAt = readSuspendExpiry(job);

        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(leadTime))) {
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
            result = sendReminder(job, jobResumeId.toString(), expiresAt);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not send approval reminder for job {}", job.getId(), runtimeException);

            result = ApprovalNotificationDispatcher.Result.FAILED;
        }

        if (result != ApprovalNotificationDispatcher.Result.DELIVERED) {
            releaseClaim(claimedJob);
        }
    }

    private ApprovalNotificationDispatcher.Result sendReminder(Job job, String jobResumeId, Instant expiresAt) {
        NotificationEvent.Type eventType = NotificationEvent.Type.JOB_APPROVAL_EXPIRING;

        String formUrl = ApprovalFormUrls.buildFormUrl(publicUrl, jobResumeId, approvalTokens, expiresAt)
            .orElse(null);

        NotificationHandlerContext notificationHandlerContext = new NotificationHandlerContext.Builder()
            .approvalExpiresAt(expiresAt.toString())
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
                "Could not release the approval reminder claim on job {}; it will not be retried",
                claimedJob.getId(), runtimeException);
        }
    }

    private Job putMarker(Job job, @Nullable String sentAt) {
        Map<String, Object> metadata = new HashMap<>(job.getMetadata());

        if (sentAt == null) {
            metadata.remove(APPROVAL_REMINDER_SENT_AT);
        } else {
            metadata.put(APPROVAL_REMINDER_SENT_AT, sentAt);
        }

        job.setMetadata(metadata);

        return jobService.update(job);
    }

    private @Nullable Instant readSuspendExpiry(Job job) {
        Long taskExecutionResumeId = MapUtils.getLong(job.getMetadata(), MetadataConstants.TASK_EXECUTION_RESUME_ID);

        if (taskExecutionResumeId == null) {
            return null;
        }

        try {
            TaskExecution taskExecution = taskExecutionService.getTaskExecution(taskExecutionResumeId);

            return SuspendUtils.extractSuspendExpiresAt(taskExecution.getMetadata());
        } catch (RuntimeException runtimeException) {
            log.warn(
                "Could not read the suspended task execution {} of job {}; skipping its reminder this sweep",
                taskExecutionResumeId, job.getId(), runtimeException);

            return null;
        }
    }
}

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

package com.bytechef.platform.coordinator.config;

import com.bytechef.atlas.configuration.service.WorkflowService;
import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.atlas.execution.service.TaskExecutionService;
import com.bytechef.atlas.file.storage.TaskFileStorage;
import com.bytechef.automation.configuration.service.ProjectDeploymentService;
import com.bytechef.automation.configuration.service.ProjectService;
import com.bytechef.automation.configuration.service.ProjectWorkflowService;
import com.bytechef.config.ApplicationProperties;
import com.bytechef.message.broker.MessageBroker;
import com.bytechef.platform.coordinator.ErrorWorkflowDispatchCounter;
import com.bytechef.platform.coordinator.event.listener.ErrorWorkflowJobStatusApplicationEventListener;
import com.bytechef.platform.coordinator.event.listener.ErrorWorkflowPayloadFactory;
import com.bytechef.platform.coordinator.event.listener.ErrorWorkflowResolver;
import com.bytechef.platform.coordinator.event.listener.NotificationJobStatusApplicationEventListener;
import com.bytechef.platform.coordinator.event.listener.SseStreamApplicationEventListener;
import com.bytechef.platform.coordinator.event.listener.SuspendedTaskStateJobDeletionListener;
import com.bytechef.platform.coordinator.event.listener.WebhookJobStatusApplicationEventListener;
import com.bytechef.platform.coordinator.event.listener.WebhookTaskStartedApplicationEventListener;
import com.bytechef.platform.coordinator.metrics.JobExecutionCounter;
import com.bytechef.platform.coordinator.monitor.ApprovalEscalationMonitor;
import com.bytechef.platform.coordinator.monitor.ApprovalExpiryMonitor;
import com.bytechef.platform.coordinator.monitor.ApprovalReminderMonitor;
import com.bytechef.platform.coordinator.monitor.JobRetentionMonitor;
import com.bytechef.platform.coordinator.monitor.JobTimeoutMonitor;
import com.bytechef.platform.coordinator.monitor.OrphanedJobRecoveryMonitor;
import com.bytechef.platform.data.storage.DataStorage;
import com.bytechef.platform.notification.delivery.WebhookNotificationClient;
import com.bytechef.platform.notification.handler.NotificationHandlerRegistry;
import com.bytechef.platform.notification.handler.NotificationSenderRegistry;
import com.bytechef.platform.notification.service.NotificationService;
import com.bytechef.platform.workflow.execution.facade.PrincipalJobFacade;
import com.bytechef.platform.workflow.execution.service.PrincipalJobService;
import com.bytechef.platform.workflow.execution.service.TaskStateService;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import com.bytechef.tenant.service.TenantService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * @author Matija Petanjek
 */
@Configuration
@ConditionalOnCoordinator
public class PlatformCoordinatorConfiguration {

    private final ApplicationProperties.Workflow.Execution execution;
    private final JobService jobService;
    private final NotificationHandlerRegistry notificationHandlerRegistry;
    private final NotificationSenderRegistry notificationSenderRegistry;
    private final NotificationService notificationService;
    private final String publicUrl;

    @SuppressFBWarnings("EI")
    public PlatformCoordinatorConfiguration(
        ApplicationProperties applicationProperties, JobService jobService,
        NotificationHandlerRegistry notificationHandlerRegistry, NotificationSenderRegistry notificationSenderRegistry,
        NotificationService notificationService) {

        ApplicationProperties.Workflow workflow = applicationProperties.getWorkflow();

        this.execution = workflow.getExecution();
        this.jobService = jobService;
        this.notificationHandlerRegistry = notificationHandlerRegistry;
        this.notificationSenderRegistry = notificationSenderRegistry;
        this.notificationService = notificationService;
        this.publicUrl = applicationProperties.getPublicUrl();
    }

    @Bean
    @ConditionalOnProperty(name = "bytechef.observability.enabled", havingValue = "true")
    JobExecutionCounter jobExecutionCounter(MeterRegistry meterRegistry) {
        return new JobExecutionCounter(meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(
        name = "bytechef.workflow.execution.recovery.enabled", havingValue = "true", matchIfMissing = true)
    OrphanedJobRecoveryMonitor orphanedJobRecoveryMonitor(
        ApplicationEventPublisher eventPublisher, TaskExecutionService taskExecutionService,
        TenantService tenantService) {

        ApplicationProperties.Workflow.Execution.Recovery recovery = execution.getRecovery();

        return new OrphanedJobRecoveryMonitor(
            recovery.isAutoResume(), eventPublisher, jobService, recovery.getMaxAutoResumeAttempts(),
            recovery.getStalenessThreshold(), taskExecutionService, tenantService);
    }

    @Bean
    @ConditionalOnProperty(
        name = "bytechef.workflow.execution.timeout.enabled", havingValue = "true", matchIfMissing = true)
    JobTimeoutMonitor jobTimeoutMonitor(
        ApplicationEventPublisher eventPublisher, TaskExecutionService taskExecutionService,
        TenantService tenantService) {

        ApplicationProperties.Workflow.Execution.Timeout timeout = execution.getTimeout();

        return new JobTimeoutMonitor(
            timeout.getDefaultTimeout(), eventPublisher, jobService, taskExecutionService, tenantService);
    }

    @Bean
    @ConditionalOnProperty(
        name = "bytechef.workflow.execution.retention.enabled", havingValue = "true", matchIfMissing = true)
    JobRetentionMonitor jobRetentionMonitor(
        ObjectProvider<DataStorage> dataStorageObjectProvider, JobFacade jobFacade, TenantService tenantService) {

        ApplicationProperties.Workflow.Execution.Retention retention = execution.getRetention();

        return new JobRetentionMonitor(
            dataStorageObjectProvider, retention.getDefaultRetentionDays(), jobFacade, jobService, tenantService);
    }

    @Bean
    WebhookJobStatusApplicationEventListener webhookJobStatusApplicationEventListener(
        WebhookNotificationClient webhookNotificationClient) {

        return new WebhookJobStatusApplicationEventListener(jobService, webhookNotificationClient);
    }

    @Bean
    WebhookTaskStartedApplicationEventListener taskStartedWebhookEventListener(
        @Value("${bytechef.security.ssrf.enabled:true}") boolean ssrfEnabled,
        @Value("${bytechef.security.ssrf.allowed-hosts:}") Set<String> ssrfAllowedHosts) {

        return new WebhookTaskStartedApplicationEventListener(jobService, ssrfEnabled, ssrfAllowedHosts);
    }

    @Bean
    NotificationJobStatusApplicationEventListener jobStatusApplicationEventListener(
        Optional<JobExecutionCounter> jobExecutionCounter) {

        return new NotificationJobStatusApplicationEventListener(
            jobExecutionCounter.orElse(null), jobService, notificationHandlerRegistry, notificationSenderRegistry,
            notificationService);
    }

    @Bean
    @ConditionalOnProperty(
        name = "bytechef.workflow.execution.approval-expiry.enabled", havingValue = "true", matchIfMissing = true)
    ApprovalExpiryMonitor approvalExpiryMonitor(
        ApplicationEventPublisher eventPublisher, ObjectProvider<MeterRegistry> meterRegistryObjectProvider,
        TaskExecutionService taskExecutionService,
        ObjectProvider<TaskStateService> taskStateServiceObjectProvider, TenantService tenantService) {

        return new ApprovalExpiryMonitor(
            eventPublisher, jobService, meterRegistryObjectProvider, taskExecutionService,
            taskStateServiceObjectProvider.getIfAvailable(), tenantService);
    }

    @Bean
    SuspendedTaskStateJobDeletionListener suspendedTaskStateJobDeletionListener(
        ObjectProvider<TaskStateService> taskStateServiceObjectProvider) {

        return new SuspendedTaskStateJobDeletionListener(taskStateServiceObjectProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(
        name = "bytechef.workflow.execution.approval-reminder.enabled", havingValue = "true", matchIfMissing = true)
    ApprovalReminderMonitor approvalReminderMonitor(
        ObjectProvider<ApprovalTokens> approvalTokensObjectProvider, TaskExecutionService taskExecutionService,
        TenantService tenantService) {

        ApplicationProperties.Workflow.Execution.ApprovalReminder approvalReminder = execution.getApprovalReminder();

        return new ApprovalReminderMonitor(
            approvalTokensObjectProvider.getIfAvailable(), jobService, approvalReminder.getLeadTime(),
            notificationHandlerRegistry, notificationSenderRegistry, notificationService, publicUrl,
            taskExecutionService, tenantService);
    }

    @Bean
    @ConditionalOnProperty(
        name = "bytechef.workflow.execution.approval-escalation.enabled", havingValue = "true",
        matchIfMissing = true)
    ApprovalEscalationMonitor approvalEscalationMonitor(
        ObjectProvider<ApprovalTokens> approvalTokensObjectProvider, TaskExecutionService taskExecutionService,
        TenantService tenantService) {

        ApplicationProperties.Workflow.Execution.ApprovalEscalation approvalEscalation =
            execution.getApprovalEscalation();

        return new ApprovalEscalationMonitor(
            approvalEscalation.getAfter(), approvalTokensObjectProvider.getIfAvailable(), jobService,
            notificationHandlerRegistry, notificationSenderRegistry, notificationService, publicUrl,
            taskExecutionService, tenantService);
    }

    @Bean
    ErrorWorkflowResolver errorWorkflowResolver(
        ProjectDeploymentService projectDeploymentService, ProjectService projectService,
        ProjectWorkflowService projectWorkflowService, WorkflowService workflowService) {

        return new ErrorWorkflowResolver(
            projectDeploymentService, projectService, projectWorkflowService, workflowService);
    }

    @Bean
    @Order(300)
    ErrorWorkflowJobStatusApplicationEventListener errorWorkflowJobStatusApplicationEventListener(
        ErrorWorkflowResolver errorWorkflowResolver, PrincipalJobFacade principalJobFacade,
        PrincipalJobService principalJobService,
        @Value("${bytechef.public-url:#{null}}") String publicUrl,
        ObjectProvider<MeterRegistry> meterRegistryObjectProvider, TaskExecutionService taskExecutionService) {

        return new ErrorWorkflowJobStatusApplicationEventListener(
            new ErrorWorkflowPayloadFactory(publicUrl), errorWorkflowResolver, jobService, principalJobFacade,
            principalJobService, taskExecutionService,
            new ErrorWorkflowDispatchCounter(meterRegistryObjectProvider.getIfAvailable()));
    }

    @Bean
    SseStreamApplicationEventListener sseStreamApplicationEventListener(
        MessageBroker messageBroker, TaskExecutionService taskExecutionService, TaskFileStorage taskFileStorage) {

        return new SseStreamApplicationEventListener(messageBroker, taskExecutionService, taskFileStorage);
    }
}

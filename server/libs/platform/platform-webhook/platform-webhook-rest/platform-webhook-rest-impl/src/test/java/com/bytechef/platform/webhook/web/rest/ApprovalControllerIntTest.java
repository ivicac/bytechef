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

package com.bytechef.platform.webhook.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.commons.util.EncodingUtils;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.workflow.execution.ApprovalId;
import com.bytechef.platform.workflow.execution.facade.ApprovalInputException;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade.JobResumeOutcome;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import com.bytechef.platform.workflow.execution.token.ApprovalTokensImpl;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionOperations;

/**
 * @author Ivica Cardic
 */
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(ObjectMapperSetupExtension.class)
@ContextConfiguration(classes = {
    ApprovalController.class, ApprovalControllerIntTest.ApprovalControllerIntTestConfiguration.class
})
@WebMvcTest(controllers = ApprovalController.class, properties = "bytechef.coordinator.enabled=true")
class ApprovalControllerIntTest {

    private static final long JOB_ID = 42L;
    private static final String SIGNING_SECRET = EncodingUtils.base64EncodeToString("0123456789abcdef0123456789abcdef");
    private static final long TASK_EXECUTION_ID = 7L;
    private static final String TENANT_ID = "public";

    @Autowired
    private ApprovalTokens approvalTokens;

    @MockitoBean
    private JobResumeFacade jobResumeFacade;

    @MockitoBean
    private JobService jobService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void beforeEach() {
        when(jobResumeFacade.resumeApproval(any(ApprovalId.class))).thenReturn(JobResumeOutcome.OK);
    }

    @Test
    void testApproveResumesJobForSignedToken() throws Exception {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, true);

    @BeforeEach
    void setUp() {
        when(jobService.tryClaimResume(any())).thenReturn(true);

        assertThat(captureResumedApprovalId()).hasToString(approvalId.toString());
    }

    @Test
    void testApproveResumesJobForLegacyUnsignedToken() throws Exception {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, false);

        mockMvc.perform(get("/approvals/{id}", approvalId.toString()))
            .andExpect(status().isNoContent());

        assertThat(captureResumedApprovalId()).hasToString(approvalId.toString());
    }

    @Nested
    class LegacyStoppedJob {

        @BeforeEach
        void setUp() {
            stubJob(Job.Status.STOPPED, Map.of());
        }

        @Test
        void testApproveResumesJobForSignedToken() throws Exception {
            ApprovalId approvalId = ApprovalId.of(TENANT_ID, JOB_ID, true);

            mockMvc.perform(post("/approvals/{id}", sign(approvalId)))
                .andExpect(status().isNoContent());

            verify(jobFacade).resumeApproval(JOB_ID, approvalId.getUuidAsString(), true);
        }

        @Test
        void testApproveRejectsTamperedSignedToken() throws Exception {
            String signedToken = sign(ApprovalId.of(TENANT_ID, JOB_ID, true));

            int signatureStart = signedToken.lastIndexOf('.') + 1;
            char replacement = signedToken.charAt(signatureStart) == 'A' ? 'B' : 'A';

            String tamperedToken =
                signedToken.substring(0, signatureStart) + replacement + signedToken.substring(signatureStart + 1);

            mockMvc.perform(post("/approvals/{id}", tamperedToken))
                .andExpect(status().isBadRequest());

            verify(jobFacade, never()).resumeApproval(anyLong(), anyString(), anyBoolean());
        }

        @Test
        void testApproveRejectsUnsignedToken() throws Exception {
            ApprovalId approvalId = ApprovalId.of(TENANT_ID, JOB_ID, false);

            mockMvc.perform(post("/approvals/{id}", approvalId.toString()))
                .andExpect(status().isBadRequest());

            verify(jobFacade, never()).resumeApproval(anyLong(), anyString(), anyBoolean());
        }

        @Test
        void testApproveRejectsMalformedToken() throws Exception {
            mockMvc.perform(post("/approvals/{id}", "not-a-token"))
                .andExpect(status().isBadRequest());

            verify(jobFacade, never()).resumeApproval(anyLong(), anyString(), anyBoolean());
        }

        @Test
        void testApproveReturnsGoneForAnApprovalAlreadyProcessed() throws Exception {
            ApprovalId approvalId = ApprovalId.of(TENANT_ID, JOB_ID, true);

            doThrow(new IllegalArgumentException("Approval already processed"))
                .when(jobFacade)
                .resumeApproval(JOB_ID, approvalId.getUuidAsString(), true);

            mockMvc.perform(post("/approvals/{id}", sign(approvalId)))
                .andExpect(status().isGone());
        }

        @Test
        void testApproveDoesNotResumeAJobThatIsStillRunning() throws Exception {
            stubJob(Job.Status.STARTED, Map.of());

        verify(jobResumeFacade, never()).resumeApproval(any(ApprovalId.class));
    }

    @Test
    void testApproveRejectsMalformedToken() throws Exception {
        mockMvc.perform(post("/approvals/{id}", "not-a-token"))
            .andExpect(status().isBadRequest());

        verify(jobResumeFacade, never()).resumeApproval(any(ApprovalId.class));
    }

    @Test
    void testApproveIsGoneWhenTheApprovalWasAlreadyResolved() throws Exception {
        when(jobResumeFacade.resumeApproval(any(ApprovalId.class))).thenReturn(JobResumeOutcome.GONE);

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isGone());
    }

    @Test
    void testApproveIsUnprocessableWhenTheJobFailed() throws Exception {
        when(jobResumeFacade.resumeApproval(any(ApprovalId.class))).thenReturn(JobResumeOutcome.JOB_FAILED);

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    void testApproveIsConflictWhileTheJobIsNotYetSuspended() throws Exception {
        when(jobResumeFacade.resumeApproval(any(ApprovalId.class))).thenReturn(JobResumeOutcome.NOT_YET_SUSPENDED);

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isConflict());
    }

    @Test
    void testApproveIsBadRequestWhenTheApprovalFormRequiresInputs() throws Exception {
        when(jobResumeFacade.resumeApproval(any(ApprovalId.class))).thenThrow(
            new ApprovalInputException(List.of("'reason' is required")));

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0]").value("'reason' is required"));
    }

    private ApprovalId captureResumedApprovalId() {
        ArgumentCaptor<ApprovalId> approvalIdCaptor = ArgumentCaptor.forClass(ApprovalId.class);

        verify(jobResumeFacade).resumeApproval(approvalIdCaptor.capture());

        return approvalIdCaptor.getValue();
    }

    private String signedApprovalToken() {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, true);

        return approvalTokens.toSignedToken(approvalId.toString(), Duration.ofHours(1));
    }

    private void stubTaskExecution(Instant expiresAt) {
        TaskExecution taskExecution = new TaskExecution();

        if (expiresAt != null) {
            taskExecution.putMetadata(MetadataConstants.SUSPEND, Map.of("expiresAt", expiresAt.toEpochMilli()));
        }

        when(taskExecutionService.fetchTaskExecution(TASK_EXECUTION_ID)).thenReturn(Optional.of(taskExecution));
    }

    static class ApprovalControllerIntTestConfiguration {

        @Bean
        ApprovalTokens approvalTokens() {
            return new ApprovalTokensImpl(
                Clock.systemUTC(), SIGNING_SECRET, List.of(), Duration.ofHours(1), Duration.ofSeconds(30), false);
        }

        @Bean
        JobResumeFacade jobResumeFacade(
            ApplicationEventPublisher applicationEventPublisher, ObjectProvider<ApprovalTokens> approvalTokensProvider,
            JobFacade jobFacade, JobService jobService, ObjectProvider<MeterRegistry> meterRegistryObjectProvider,
            TaskExecutionService taskExecutionService) {

            return new JobResumeFacadeImpl(
                applicationEventPublisher, approvalTokensProvider, jobFacade, jobService, meterRegistryObjectProvider,
                TransactionOperations.withoutTransaction(), taskExecutionService, mock(TaskStateFacade.class));
        }
    }
}

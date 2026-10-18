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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.commons.util.EncodingUtils;
import com.bytechef.platform.workflow.execution.ApprovalId;
import com.bytechef.platform.workflow.execution.facade.ApprovalInputException;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade.JobResumeOutcome;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import com.bytechef.platform.workflow.execution.token.ApprovalTokensImpl;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @author Ivica Cardic
 */
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
    ApprovalController.class, ApprovalControllerIntTest.ApprovalTokensConfiguration.class
})
@WebMvcTest(controllers = ApprovalController.class, properties = "bytechef.coordinator.enabled=true")
class ApprovalControllerIntTest {

    private static final long JOB_ID = 42L;
    private static final String SIGNING_SECRET = EncodingUtils.base64EncodeToString("0123456789abcdef0123456789abcdef");

    @Autowired
    private ApprovalTokens approvalTokens;

    @MockitoBean
    private JobResumeFacade jobResumeFacade;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void beforeEach() {
        when(jobResumeFacade.resumeApproval(anyString())).thenReturn(JobResumeOutcome.OK);
    }

    @Test
    void testApproveResumesJobForSignedToken() throws Exception {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, true);

        String signedToken = approvalTokens.toSignedToken(approvalId.toString(), Duration.ofHours(1));

        mockMvc.perform(post("/approvals/{id}", signedToken))
            .andExpect(status().isNoContent());

        assertThat(captureResumedToken()).isEqualTo(signedToken);
    }

    @Test
    void testApproveRejectsUnsignedToken() throws Exception {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, false);

        mockMvc.perform(get("/approvals/{id}", approvalId.toString()))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/approvals/{id}", approvalId.toString()))
            .andExpect(status().isBadRequest());

        verify(jobResumeFacade, never()).resumeApproval(anyString());
    }

    @Test
    void testApproveRejectsTamperedSignedToken() throws Exception {
        String signedToken = approvalTokens.toSignedToken(
            ApprovalId.of(JOB_ID, true)
                .toString(),
            Duration.ofHours(1));

        int signatureStart = signedToken.lastIndexOf('.') + 1;
        char replacement = signedToken.charAt(signatureStart) == 'A' ? 'B' : 'A';

        String tamperedToken =
            signedToken.substring(0, signatureStart) + replacement + signedToken.substring(signatureStart + 1);

        mockMvc.perform(post("/approvals/{id}", tamperedToken))
            .andExpect(status().isBadRequest());

        verify(jobResumeFacade, never()).resumeApproval(anyString());
    }

    @Test
    void testApproveRejectsMalformedToken() throws Exception {
        mockMvc.perform(post("/approvals/{id}", "not-a-token"))
            .andExpect(status().isBadRequest());

        verify(jobResumeFacade, never()).resumeApproval(anyString());
    }

    @Test
    void testApproveIsGoneWhenTheApprovalWasAlreadyResolved() throws Exception {
        when(jobResumeFacade.resumeApproval(anyString())).thenReturn(JobResumeOutcome.GONE);

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isGone());
    }

    @Test
    void testApproveIsUnprocessableWhenTheJobFailed() throws Exception {
        when(jobResumeFacade.resumeApproval(anyString())).thenReturn(JobResumeOutcome.JOB_FAILED);

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    void testApproveIsConflictWhileTheJobIsNotYetSuspended() throws Exception {
        when(jobResumeFacade.resumeApproval(anyString())).thenReturn(JobResumeOutcome.NOT_YET_SUSPENDED);

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isConflict());
    }

    @Test
    void testApproveIsBadRequestWhenTheApprovalFormRequiresInputs() throws Exception {
        when(jobResumeFacade.resumeApproval(anyString())).thenThrow(
            new ApprovalInputException(List.of("'reason' is required")));

        mockMvc.perform(post("/approvals/{id}", signedApprovalToken()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0]").value("'reason' is required"));
    }

    private String captureResumedToken() {
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);

        verify(jobResumeFacade).resumeApproval(tokenCaptor.capture());

        return tokenCaptor.getValue();
    }

    private String signedApprovalToken() {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, true);

        return approvalTokens.toSignedToken(approvalId.toString(), Duration.ofHours(1));
    }

    static class ApprovalTokensConfiguration {

        @Bean
        ApprovalTokens approvalTokens() {
            return new ApprovalTokensImpl(
                Clock.systemUTC(), SIGNING_SECRET, List.of(), Duration.ofHours(1), Duration.ofSeconds(30), false);
        }
    }
}

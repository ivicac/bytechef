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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.atlas.execution.domain.Job;
import com.bytechef.atlas.execution.facade.JobFacade;
import com.bytechef.atlas.execution.service.JobService;
import com.bytechef.platform.component.constant.MetadataConstants;
import com.bytechef.platform.workflow.execution.ApprovalId;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import com.bytechef.tenant.TenantContext;
import com.bytechef.test.extension.ObjectMapperSetupExtension;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * @author Ivica Cardic
 */
@ExtendWith({
    MockitoExtension.class, ObjectMapperSetupExtension.class
})
class ApprovalControllerTest {

    private static final long JOB_ID = 42L;
    private static final String TOKEN = "approval-token";

    @Mock
    private ApprovalTokens approvalTokens;

    @Mock
    private JobFacade jobFacade;

    @Mock
    private JobService jobService;

    private MockMvc mockMvc;

    @BeforeEach
    void beforeEach() {
        TenantContext.setCurrentTenantId("public");

        mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvalTokens, jobFacade, jobService))
            .build();
    }

    @AfterEach
    void afterEach() {
        TenantContext.resetCurrentTenantId();
    }

    @Test
    void testASuspendedJobIsResumedThroughItsSuspendedTaskExecution() throws Exception {
        stubToken(true);
        stubJob(Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1", MetadataConstants.TASK_EXECUTION_RESUME_ID, 7L));

        mockMvc.perform(post("/approvals/" + TOKEN))
            .andExpect(status().isNoContent());

        verify(jobFacade).resumeJob(JOB_ID, 7L, Map.of("approved", true));
        verify(jobFacade, never()).resumeApproval(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void testARejectionReachesTheSuspendedTaskAsApprovedFalse() throws Exception {
        stubToken(false);
        stubJob(Map.of(MetadataConstants.JOB_RESUME_ID, "resume-1", MetadataConstants.TASK_EXECUTION_RESUME_ID, 7L));

        mockMvc.perform(post("/approvals/" + TOKEN))
            .andExpect(status().isNoContent());

        verify(jobFacade).resumeJob(JOB_ID, 7L, Map.of("approved", false));
    }

    @Test
    void testALegacyWaitForApprovalJobStillTakesResumeApproval() throws Exception {
        ApprovalId approvalId = stubToken(true);
        stubJob(Map.of());

        mockMvc.perform(post("/approvals/" + TOKEN))
            .andExpect(status().isNoContent());

        verify(jobFacade).resumeApproval(JOB_ID, approvalId.getUuidAsString(), true);
        verify(jobFacade, never()).resumeJob(anyLong(), anyLong(), any());
    }

    @Test
    void testAnUnknownTokenIsNotFound() throws Exception {
        when(approvalTokens.resolveInnerToken(TOKEN)).thenReturn(Optional.empty());

        mockMvc.perform(post("/approvals/" + TOKEN))
            .andExpect(status().isNotFound());
    }

    private ApprovalId stubToken(boolean approved) {
        ApprovalId approvalId = ApprovalId.of(JOB_ID, approved);

        when(approvalTokens.resolveInnerToken(TOKEN)).thenReturn(Optional.of(approvalId.toString()));

        return approvalId;
    }

    private void stubJob(Map<String, ?> metadata) {
        Job job = new Job();

        job.setId(JOB_ID);
        job.setMetadata(metadata);

        when(jobService.getJob(JOB_ID)).thenReturn(job);
    }
}

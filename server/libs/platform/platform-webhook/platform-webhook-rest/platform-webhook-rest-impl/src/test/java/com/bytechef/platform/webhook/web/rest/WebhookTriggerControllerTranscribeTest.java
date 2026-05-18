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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.config.ApplicationProperties;
import com.bytechef.file.storage.token.FileEntryTokens;
import com.bytechef.platform.ai.stt.SttProvider.TranscriptResult;
import com.bytechef.platform.ai.stt.service.TranscribeService;
import com.bytechef.platform.component.service.TriggerDefinitionService;
import com.bytechef.platform.constant.PlatformType;
import com.bytechef.platform.file.storage.TempFileStorage;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutionFacade;
import com.bytechef.platform.webhook.executor.WebhookWorkflowExecutor;
import com.bytechef.platform.workflow.WorkflowExecutionId;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessor;
import com.bytechef.platform.workflow.execution.accessor.JobPrincipalAccessorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * @author Ivica Cardic
 */
class WebhookTriggerControllerTranscribeTest {

    private MockMvc mockMvc;

    private TranscribeService transcribeService;

    @BeforeEach
    void setUp() {
        ApplicationProperties applicationProperties = mock(ApplicationProperties.class);
        FileEntryTokens fileEntryTokens = mock(FileEntryTokens.class);
        JobPrincipalAccessorRegistry jobPrincipalAccessorRegistry = mock(JobPrincipalAccessorRegistry.class);
        TempFileStorage tempFileStorage = mock(TempFileStorage.class);
        TriggerDefinitionService triggerDefinitionService = mock(TriggerDefinitionService.class);
        transcribeService = mock(TranscribeService.class);
        WebhookWorkflowExecutionFacade webhookFacade = mock(WebhookWorkflowExecutionFacade.class);
        WebhookWorkflowExecutor webhookWorkflowExecutor = mock(WebhookWorkflowExecutor.class);

        JobPrincipalAccessor jobPrincipalAccessor = mock(JobPrincipalAccessor.class);

        when(jobPrincipalAccessorRegistry.getJobPrincipalAccessor(any())).thenReturn(jobPrincipalAccessor);
        when(jobPrincipalAccessor.isWorkflowEnabled(anyLong(), anyString())).thenReturn(true);

        WebhookTriggerController controller = new WebhookTriggerController(
            applicationProperties, fileEntryTokens, jobPrincipalAccessorRegistry, tempFileStorage, transcribeService,
            triggerDefinitionService, webhookFacade, webhookWorkflowExecutor,
            mock(com.bytechef.atlas.configuration.service.WorkflowService.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .build();
    }

    @Test
    void testTranscribeReturnsText() throws Exception {
        when(transcribeService.transcribe(any(), eq("audio/webm"), eq("en"), any()))
            .thenReturn(new TranscriptResult("hello", 1000L, "en"));

        String webhookId = WorkflowExecutionId.of(
            PlatformType.AUTOMATION, 1L, "test-workflow", "trigger_1")
            .toString();

        MockMultipartFile audio = new MockMultipartFile(
            "audio", "clip.webm", "audio/webm", new byte[] {
                1, 2, 3, 4
            });

        mockMvc.perform(multipart("/webhooks/" + webhookId + "/transcribe")
            .file(audio)
            .param("locale", "en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("hello"))
            .andExpect(jsonPath("$.durationMs").value(1000));
    }
}

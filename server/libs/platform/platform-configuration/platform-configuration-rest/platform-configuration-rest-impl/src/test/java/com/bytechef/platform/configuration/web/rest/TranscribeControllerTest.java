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

package com.bytechef.platform.configuration.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bytechef.platform.ai.stt.SttProvider.TranscriptResult;
import com.bytechef.platform.ai.stt.service.TranscribeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * @author Ivica Cardic
 */
class TranscribeControllerTest {

    private MockMvc mockMvc;
    private TranscribeService transcribeService;

    @BeforeEach
    void setUp() {
        transcribeService = mock(TranscribeService.class);

        mockMvc = MockMvcBuilders.standaloneSetup(new TranscribeController(transcribeService))
            .build();
    }

    @Test
    void testTranscribeReturnsText() throws Exception {
        when(transcribeService.transcribe(any(), any(), any(), any()))
            .thenReturn(new TranscriptResult("consolidated", 500L, "en"));

        MockMultipartFile audio = new MockMultipartFile(
            "audio", "clip.webm", "audio/webm", new byte[] {
                1, 2, 3
            });

        mockMvc.perform(multipart("/internal/transcribe").file(audio))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("consolidated"))
            .andExpect(jsonPath("$.durationMs").value(500));
    }
}

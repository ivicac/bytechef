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

package com.bytechef.platform.ai.stt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bytechef.platform.ai.stt.SttProvider;
import com.bytechef.platform.ai.stt.SttProvider.TranscribeRequest;
import com.bytechef.platform.ai.stt.SttProvider.TranscriptResult;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranscribeServiceTest {

    @Test
    void testTranscribeDelegatesToConfiguredProvider() {
        SttProvider provider = mock(SttProvider.class);

        when(provider.getKey()).thenReturn("FAKE");
        when(provider.transcribe(any(TranscribeRequest.class)))
            .thenReturn(new TranscriptResult("hello world", 1200L, "en-US"));

        TranscribeService transcribeService = new TranscribeService(
            Map.of("FAKE", provider), "FAKE");

        TranscriptResult result = transcribeService.transcribe(
            new ByteArrayInputStream(new byte[] {
                1, 2, 3
            }), "audio/webm", "en", Map.of());

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.durationMs()).isEqualTo(1200L);
    }

    @Test
    void testTranscribeRejectsUnsupportedMimeType() {
        TranscribeService transcribeService = new TranscribeService(Map.of(), "FAKE");

        assertThatThrownBy(() -> transcribeService.transcribe(
            new ByteArrayInputStream(new byte[] {
                1
            }), "application/json", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void testTranscribeFailsWhenProviderMissing() {
        TranscribeService transcribeService = new TranscribeService(Map.of(), "MISSING");

        assertThatThrownBy(() -> transcribeService.transcribe(
            new ByteArrayInputStream(new byte[] {
                1
            }), "audio/webm", null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING");
    }
}

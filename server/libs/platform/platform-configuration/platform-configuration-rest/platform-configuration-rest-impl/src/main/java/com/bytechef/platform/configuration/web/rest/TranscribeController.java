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

import com.bytechef.platform.ai.stt.SttProvider.TranscriptResult;
import com.bytechef.platform.ai.stt.service.TranscribeService;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Consolidated internal transcription endpoint. Session-cookie authenticated; used by both the AI Hub composer and the
 * workflow test panel composer.
 *
 * @author Ivica Cardic
 */
@RestController
@RequestMapping("/internal")
@PreAuthorize("isAuthenticated()")
public class TranscribeController {

    private final TranscribeService transcribeService;

    public TranscribeController(TranscribeService transcribeService) {
        this.transcribeService = transcribeService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<TranscribeResponse> transcribe(
        @RequestPart("audio") MultipartFile audio,
        @RequestParam(name = "locale", required = false) String locale) throws IOException {

        if (audio.getSize() > 25 * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .build();
        }

        TranscriptResult result = transcribeService.transcribe(
            audio.getInputStream(),
            audio.getContentType() == null ? "audio/webm" : audio.getContentType(),
            locale,
            Map.of());

        return ResponseEntity.ok(new TranscribeResponse(result.text(), result.durationMs(), result.detectedLocale()));
    }

    public record TranscribeResponse(String text, long durationMs, String locale) {
    }
}

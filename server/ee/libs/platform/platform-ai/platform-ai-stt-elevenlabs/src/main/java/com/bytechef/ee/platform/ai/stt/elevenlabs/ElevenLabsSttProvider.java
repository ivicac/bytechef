/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.stt.elevenlabs;

import com.bytechef.platform.ai.stt.SttProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class ElevenLabsSttProvider implements SttProvider {

    public static final String KEY = "ELEVENLABS_SCRIBE";

    private static final String MODEL = "scribe_v1";

    private final RestClient restClient;

    public ElevenLabsSttProvider(RestClient elevenLabsSttRestClient) {
        this.restClient = elevenLabsSttRestClient;
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public TranscriptResult transcribe(TranscribeRequest request) {
        String apiKey = (String) request.connectionParameters()
            .getOrDefault("apiKey", "");

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        body.add("file", new InputStreamResource(request.audio()) {
            @Override
            public String getFilename() {
                return "audio." + extension(request.mimeType());
            }

            @Override
            public long contentLength() throws IOException {
                return -1;
            }
        });
        body.add("model_id", MODEL);

        ElevenLabsResponse response = restClient.post()
            .uri("/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .body(ElevenLabsResponse.class);

        if (response == null) {
            throw new IllegalStateException("Empty response from ElevenLabs STT");
        }

        return new TranscriptResult(response.text, 0L, response.languageCode);
    }

    private static String extension(String mimeType) {
        return switch (mimeType) {
            case "audio/webm" -> "webm";
            case "audio/mp4" -> "mp4";
            case "audio/wav" -> "wav";
            case "audio/mpeg" -> "mp3";
            case "audio/ogg" -> "ogg";
            default -> "bin";
        };
    }

    @SuppressWarnings("PMD")
    private static final class ElevenLabsResponse {
        public String text;

        @JsonProperty("language_code")
        public String languageCode;
    }
}

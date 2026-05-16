/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.stt.deepgram;

import com.bytechef.platform.ai.stt.SttProvider;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Component
public class DeepgramSttProvider implements SttProvider {

    public static final String KEY = "DEEPGRAM_NOVA_3";

    private final RestClient restClient;

    public DeepgramSttProvider(RestClient deepgramSttRestClient) {
        this.restClient = deepgramSttRestClient;
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public TranscriptResult transcribe(TranscribeRequest request) {
        String apiKey = (String) request.connectionParameters()
            .getOrDefault("apiKey", "");

        String uri = UriComponentsBuilder.fromPath("/v1/listen")
            .queryParam("model", "nova-3")
            .queryParam("language", request.locale() == null ? "en" : request.locale())
            .queryParam("smart_format", "true")
            .build()
            .toUriString();

        DeepgramResponse response = restClient.post()
            .uri(uri)
            .header("Authorization", "Token " + apiKey)
            .contentType(MediaType.parseMediaType(request.mimeType()))
            .body(new InputStreamResource(request.audio()))
            .retrieve()
            .body(DeepgramResponse.class);

        if (response == null || response.results == null || response.results.channels == null
            || response.results.channels.isEmpty()
            || response.results.channels.get(0).alternatives == null
            || response.results.channels.get(0).alternatives.isEmpty()) {

            throw new IllegalStateException("Empty response from Deepgram STT");
        }

        String text = response.results.channels.get(0).alternatives.get(0).transcript;
        long durationMs = response.metadata == null || response.metadata.duration == null
            ? 0L : Math.round(response.metadata.duration * 1000);

        return new TranscriptResult(text == null ? "" : text, durationMs, request.locale());
    }

    @SuppressWarnings("PMD")
    private static final class DeepgramResponse {
        public Results results;
        public Metadata metadata;

        static final class Results {
            public List<Channel> channels;
        }

        static final class Channel {
            public List<Alternative> alternatives;
        }

        static final class Alternative {
            public String transcript;
        }

        static final class Metadata {
            public Double duration;
        }
    }
}

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

import com.bytechef.platform.ai.stt.SttProvider;
import com.bytechef.platform.ai.stt.SttProvider.TranscribeRequest;
import com.bytechef.platform.ai.stt.SttProvider.TranscriptResult;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TranscribeService {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
        "audio/webm", "audio/mp4", "audio/wav", "audio/mpeg", "audio/ogg");

    private final Map<String, SttProvider> providers;

    private final String providerKey;

    public TranscribeService(
        Map<String, SttProvider> providers,
        @Value("${bytechef.ai.stt.provider:OPENAI_GPT_4O_MINI_TRANSCRIBE}") String providerKey) {

        this.providers = providers;
        this.providerKey = providerKey;
    }

    public TranscriptResult transcribe(
        InputStream audio, String mimeType, String locale, Map<String, Object> connectionParameters) {

        if (!SUPPORTED_MIME_TYPES.contains(stripParameters(mimeType))) {
            throw new IllegalArgumentException("Unsupported mime type: " + mimeType);
        }

        SttProvider provider = providers.values()
            .stream()
            .filter(sttProvider -> providerKey.equals(sttProvider.getKey()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No SttProvider registered for key: " + providerKey));

        return provider.transcribe(new TranscribeRequest(audio, mimeType, locale, connectionParameters));
    }

    private static String stripParameters(String mimeType) {
        int semicolon = mimeType.indexOf(';');

        return semicolon < 0 ? mimeType : mimeType.substring(0, semicolon)
            .trim();
    }
}

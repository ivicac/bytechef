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

package com.bytechef.component.elevenlabs.cluster;

/**
 * Resolves the signed WebSocket URL ElevenLabs requires before a conversation can open. A seam, not an abstraction:
 * production fetches it with {@code GET /v1/convai/conversation/get-signed-url} through the real
 * {@link com.bytechef.component.definition.ActionContext}; the contract test hands the element a lambda that builds the
 * URL directly, with no network call at all.
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface ElevenLabsSignedUrlResolver {

    String resolve(String agentId, String apiKey);
}

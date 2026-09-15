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

package com.bytechef.component.ai.llm.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens the provider-side WebSocket of a voice agent. A seam, not an abstraction: production uses the JDK client, the
 * contract test hands the provider a fake socket it can drive frame by frame.
 *
 * <p>
 * {@link #jdk()} guarantees every {@link WebSocket.Listener} callback parameter -- and the {@link WebSocket} this
 * method itself returns -- is always the same {@link SerializedWebSocket}, never the JDK's raw socket: see
 * {@link SerializingWebSocketListener}. A provider's listener can therefore send through its callback parameter
 * directly, with no binding or fallback of its own, and rely on outbound sends never overlapping.
 * </p>
 *
 * @author Ivica Cardic
 */
@FunctionalInterface
public interface ProviderWebSocketConnector {

    WebSocket connect(URI uri, Map<String, String> headers, WebSocket.Listener listener);

    static ProviderWebSocketConnector jdk() {
        return (uri, headers, listener) -> {
            HttpClient httpClient = SharedHttpClient.get();

            WebSocket.Builder builder = httpClient.newWebSocketBuilder();

            headers.forEach(builder::header);

            AtomicReference<SerializedWebSocket> serializedWebSocketHolder = new AtomicReference<>();
            SerializingWebSocketListener serializingWebSocketListener =
                new SerializingWebSocketListener(listener, serializedWebSocketHolder);

            WebSocket rawWebSocket = builder.buildAsync(uri, serializingWebSocketListener)
                .join();

            // buildAsync(...)'s future can complete before the listener's onOpen has run -- the JDK schedules onOpen
            // on the client's executor separately from completing this future, so it is NOT guaranteed to have
            // happened by the time join() returns. Calling the same idempotent, identity-keyed serialized(...) here
            // (instead of just reading a holder onOpen alone would populate) is what makes connect() never return
            // null: whichever of this call and onOpen's own runs first creates the wrapper, and the other reuses it.
            return serializingWebSocketListener.serialized(rawWebSocket);
        };
    }
}

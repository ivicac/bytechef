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

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a provider's {@link WebSocket.Listener} so that every callback it receives -- {@code onOpen}, {@code onText},
 * {@code onBinary}, {@code onPing}, {@code onPong}, {@code onClose} and {@code onError} -- is always handed a
 * {@link SerializedWebSocket}, never the JDK's raw socket.
 *
 * <p>
 * The one and only {@link SerializedWebSocket} for a connection is built by {@link #serialized(WebSocket)}, which is
 * idempotent and identity-keyed on the raw socket: an {@link AtomicReference#compareAndSet} publishes the first
 * caller's wrapper, and every later call -- from any callback, or from {@link ProviderWebSocketConnector#jdk()} itself
 * -- returns that same published instance instead of building a second one. This is deliberate, not merely convenient:
 * the JDK does <em>not</em> guarantee {@code onOpen} has already run by the time {@code builder.buildAsync(...).join()}
 * returns -- {@code WebSocketImpl.newInstanceAsync} completes its future as soon as the HTTP upgrade response arrives,
 * and schedules the receive task (which invokes {@code onOpen}) onto the client's executor separately, so it can run
 * after {@code join()} has already returned. A connector that read a holder only {@code onOpen} ever populated could
 * see {@code join()} return before {@code onOpen} has run and get back {@code null}. Because
 * {@link ProviderWebSocketConnector#jdk()} calls the very same {@link #serialized(WebSocket)} method after
 * {@code join()}, whichever of the two racing call sites -- the connector or the listener's own first callback -- runs
 * first creates the wrapper, and the other one reuses it; {@code connect()} can never return {@code null}, and every
 * caller (the connector's caller and every listener callback) is guaranteed to share the identical instance.
 * </p>
 *
 * @author Ivica Cardic
 */
final class SerializingWebSocketListener implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(SerializingWebSocketListener.class);

    private final WebSocket.Listener delegate;
    private final AtomicReference<SerializedWebSocket> holder;

    SerializingWebSocketListener(WebSocket.Listener delegate, AtomicReference<SerializedWebSocket> holder) {
        this.delegate = delegate;
        this.holder = holder;
    }

    /**
     * Returns the one {@link SerializedWebSocket} for this connection, creating it around {@code raw} if no caller has
     * done so yet. Safe to call from more than one thread at once (see the class javadoc); every caller, regardless of
     * ordering, gets back the identical instance.
     */
    SerializedWebSocket serialized(WebSocket raw) {
        SerializedWebSocket existing = holder.get();

        if (existing != null) {
            warnIfDelegateChanged(existing, raw);

            return existing;
        }

        SerializedWebSocket created = new SerializedWebSocket(raw);

        if (holder.compareAndSet(null, created)) {
            return created;
        }

        // Lost the race to another thread between the read above and this compareAndSet; its winner is authoritative.
        SerializedWebSocket winner = holder.get();

        warnIfDelegateChanged(winner, raw);

        return winner;
    }

    private static void warnIfDelegateChanged(SerializedWebSocket serializedWebSocket, WebSocket raw) {
        if (serializedWebSocket.delegate() != raw) {
            log.warn(
                "A voice provider callback arrived with a raw WebSocket different from the one already serialized "
                    + "for this connection; continuing with the already-serialized instance. This should never happen.");
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        delegate.onOpen(serialized(webSocket));
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        return delegate.onText(serialized(webSocket), data, last);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        return delegate.onBinary(serialized(webSocket), data, last);
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        return delegate.onPing(serialized(webSocket), message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        return delegate.onPong(serialized(webSocket), message);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        return delegate.onClose(serialized(webSocket), statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        delegate.onError(serialized(webSocket), error);
    }
}

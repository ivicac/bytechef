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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Wraps a {@link WebSocket} so that its outbound sends never overlap.
 *
 * <p>
 * The JDK's {@code WebSocket} throws {@code IllegalStateException} when a send is issued before the previous send's
 * {@code CompletableFuture} has completed. A voice agent that sends continuous mic audio on one thread while a tool
 * call replies on a virtual thread of its own hits this directly: two outbound frames racing the same socket, one of
 * them silently killing whichever thread loses. Every outbound operation is chained onto the previous one instead, so
 * each starts only once the prior one has settled — successfully or not, so one failed send never wedges the ones
 * behind it.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class SerializedWebSocket implements WebSocket {

    private final WebSocket delegate;
    private final AtomicReference<CompletableFuture<Void>> queue =
        new AtomicReference<>(CompletableFuture.completedFuture(null));

    public SerializedWebSocket(WebSocket delegate) {
        this.delegate = delegate;
    }

    /**
     * The raw socket this instance wraps -- used only by {@link SerializingWebSocketListener} to detect (and warn
     * about) the raw JDK socket changing identity across callbacks, which should never happen.
     */
    WebSocket delegate() {
        return delegate;
    }

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
        return chain(() -> delegate.sendText(data, last));
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
        return chain(() -> delegate.sendBinary(data, last));
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        return chain(() -> delegate.sendPing(message));
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        return chain(() -> delegate.sendPong(message));
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
        return chain(() -> delegate.sendClose(statusCode, reason));
    }

    @Override
    public void request(long n) {
        delegate.request(n);
    }

    @Override
    public String getSubprotocol() {
        return delegate.getSubprotocol();
    }

    @Override
    public boolean isOutputClosed() {
        return delegate.isOutputClosed();
    }

    @Override
    public boolean isInputClosed() {
        return delegate.isInputClosed();
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    /**
     * Queues one outbound operation behind whichever one is currently in flight. {@code queue} itself never completes
     * exceptionally — each slot resolves once its operation settles, success or failure alike — so a failed send still
     * releases the next one waiting behind it.
     */
    private CompletableFuture<WebSocket> chain(Supplier<CompletableFuture<WebSocket>> operation) {
        CompletableFuture<WebSocket> result = new CompletableFuture<>();
        CompletableFuture<Void> nextSlot = new CompletableFuture<>();
        CompletableFuture<Void> previousSlot = queue.getAndSet(nextSlot);

        previousSlot.whenComplete((ignoredValue, ignoredThrowable) -> runOperation(operation, nextSlot, result));

        return result;
    }

    private void runOperation(
        Supplier<CompletableFuture<WebSocket>> operation, CompletableFuture<Void> nextSlot,
        CompletableFuture<WebSocket> result) {

        CompletableFuture<WebSocket> sendFuture;

        try {
            sendFuture = operation.get();
        } catch (RuntimeException runtimeException) {
            nextSlot.complete(null);
            result.completeExceptionally(runtimeException);

            return;
        }

        sendFuture.whenComplete((ignoredWebSocket, throwable) -> {
            nextSlot.complete(null);

            if (throwable != null) {
                result.completeExceptionally(throwable);
            } else {
                // Complete with this wrapper, not the raw delegate the JDK hands back, so a caller that chains a
                // further send off the completed value (webSocket.sendText(...).thenCompose(ws -> ws.sendBinary(...)))
                // keeps going through the serialized queue instead of falling back to the unserialized delegate.
                result.complete(this);
            }
        });
    }
}

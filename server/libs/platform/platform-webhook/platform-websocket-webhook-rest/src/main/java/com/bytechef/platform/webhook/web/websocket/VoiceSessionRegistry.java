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

package com.bytechef.platform.webhook.web.websocket;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Live browser voice sessions on this node, keyed by the ByteChef-issued session id. A session outlives a single
 * WebSocket: when the browser's socket drops the record is {@link #detach detached} and kept for
 * {@link #RESUME_WINDOW}, so a reconnect carrying the same id re-attaches to the same engine session instead of
 * starting a new conversation.
 *
 * @author Ivica Cardic
 */
@Component
public class VoiceSessionRegistry {

    public static final Duration RESUME_WINDOW = Duration.ofSeconds(30);

    /**
     * How long one send may block before the socket is considered unreliable. A 20 ms PCM16 frame at 24 kHz is under 1
     * KiB and takes microseconds to write, so this only trips on a genuinely stuck client.
     */
    static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /**
     * How much outbound data may queue behind a send in progress: over 20 seconds of 24 kHz PCM16 audio.
     */
    static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final Cache<String, Session> sessions = Caffeine.newBuilder()
        .expireAfterWrite(4, TimeUnit.HOURS)
        .maximumSize(10000)
        .build();
    private final Supplier<Instant> clock;

    @Autowired
    public VoiceSessionRegistry() {
        this(Instant::now);
    }

    VoiceSessionRegistry(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public Session register(
        String sessionId, WebSocketSession webSocketSession, @Nullable String workflowExecutionId) {

        Session session = new Session(sessionId, concurrent(webSocketSession), workflowExecutionId, clock.get());

        sessions.put(sessionId, session);

        return session;
    }

    public Optional<Session> get(String sessionId) {
        return Optional.ofNullable(sessions.getIfPresent(sessionId));
    }

    public boolean isAttached(String sessionId) {
        Session session = sessions.getIfPresent(sessionId);

        return session != null && session.detachedAt == null;
    }

    public void detach(String sessionId) {
        Session session = sessions.getIfPresent(sessionId);

        if (session != null) {
            synchronized (session) {
                session.webSocketSession = null;
                session.detachedAt = clock.get();
            }
        }
    }

    public Optional<Session> resume(String sessionId, WebSocketSession webSocketSession) {
        Session session = sessions.getIfPresent(sessionId);

        if (session == null) {
            return Optional.empty();
        }

        synchronized (session) {
            if (session.detachedAt == null) {
                return Optional.empty();
            }

            Instant now = clock.get();

            if (session.detachedAt.plus(RESUME_WINDOW)
                .isBefore(now)) {

                sessions.invalidate(sessionId);

                return Optional.empty();
            }

            session.webSocketSession = concurrent(webSocketSession);
            session.detachedAt = null;
            session.lastInboundAudioAt = now;

            return Optional.of(session);
        }
    }

    /**
     * Removes the session under the same monitor {@link #resume} and {@link #detach} hold, so a concurrent resume
     * either completes before the removal or finds the session gone — never hands back a session whose entry was just
     * removed.
     */
    public void remove(String sessionId) {
        Session session = sessions.getIfPresent(sessionId);

        if (session == null) {
            return;
        }

        synchronized (session) {
            sessions.invalidate(sessionId);
        }
    }

    /**
     * The registry's clock. Handlers measure silence and session age against it so both use the same time source as
     * {@link Session#startedAt()} and {@link Session#lastInboundAudioAt()}.
     */
    Instant now() {
        return clock.get();
    }

    /**
     * A session's socket is written from several threads at once — the provider's audio, the handler's
     * {@code connected} and {@code error} events, the timers' closes, tool results — and a servlet container rejects
     * concurrent writes. Wrapping it once, where it is registered or resumed, serializes every send path.
     */
    private static WebSocketSession concurrent(WebSocketSession webSocketSession) {
        if (webSocketSession instanceof ConcurrentWebSocketSessionDecorator) {
            return webSocketSession;
        }

        return new ConcurrentWebSocketSessionDecorator(
            webSocketSession, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES);
    }

    public final class Session {

        private final String sessionId;
        private final Instant startedAt;
        private final @Nullable String workflowExecutionId;
        private volatile @Nullable WebSocketSession webSocketSession;
        private volatile @Nullable Long engineSessionId;
        private volatile @Nullable Instant detachedAt;
        private volatile Instant lastInboundAudioAt;
        private volatile boolean ended;

        private Session(
            String sessionId, WebSocketSession webSocketSession, @Nullable String workflowExecutionId,
            Instant startedAt) {

            this.sessionId = sessionId;
            this.webSocketSession = webSocketSession;
            this.workflowExecutionId = workflowExecutionId;
            this.startedAt = startedAt;
            this.lastInboundAudioAt = startedAt;
        }

        public String sessionId() {
            return sessionId;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public @Nullable String workflowExecutionId() {
            return workflowExecutionId;
        }

        @SuppressFBWarnings("EI_EXPOSE_REP")
        public @Nullable WebSocketSession webSocketSession() {
            return webSocketSession;
        }

        public @Nullable Long engineSessionId() {
            return engineSessionId;
        }

        public void engineSessionId(long engineSessionId) {
            this.engineSessionId = engineSessionId;
        }

        public @Nullable Instant detachedAt() {
            return detachedAt;
        }

        public Instant lastInboundAudioAt() {
            return lastInboundAudioAt;
        }

        public void touchInboundAudio() {
            lastInboundAudioAt = clock.get();
        }

        /**
         * Whether a handler has decided this session is over. Set under the session's monitor before the engine is
         * stopped, so the emitter completing as a result of that stop is not mistaken for the provider hanging up.
         */
        public boolean ended() {
            return ended;
        }

        public void markEnded() {
            ended = true;
        }
    }
}

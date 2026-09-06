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

package com.bytechef.platform.ai.sensitivedata.tokenization;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

/**
 * Holds the token-to-value mapping for one request, mints stable tokens, and substitutes values back.
 *
 * <p>
 * <b>The mapping is sensitive data.</b> For as long as this session lives, it retains every PII value the detectors
 * found. {@link #close()} clears it, and callers must call it on every termination path — completion, error and
 * cancellation alike. A session that outlives its request is a PII store nobody designed.
 * </p>
 *
 * <p>
 * Thread-safe by construction: the streaming path is Reactor, so {@code push} is not guaranteed to stay on one thread
 * across a single stream. {@code computeIfAbsent} on a concurrent map is what makes "the same value always gets the
 * same token" hold under concurrency rather than only in a single-threaded test.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class PiiTokenSession {

    private static final String SESSION_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AtomicInteger nextOrdinal = new AtomicInteger(1);
    private final String sessionId;
    private final Map<String, String> tokenToValue = new ConcurrentHashMap<>();
    private final Map<String, String> valueToToken = new ConcurrentHashMap<>();

    private PiiTokenSession(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Creates a session with a fresh random discriminator.
     *
     * @return the new session
     */
    public static PiiTokenSession create() {
        StringBuilder builder = new StringBuilder(PiiToken.SESSION_ID_LENGTH);

        for (int index = 0; index < PiiToken.SESSION_ID_LENGTH; index++) {
            builder.append(SESSION_ID_ALPHABET.charAt(SECURE_RANDOM.nextInt(SESSION_ID_ALPHABET.length())));
        }

        return new PiiTokenSession(builder.toString());
    }

    /**
     * Returns the token standing for {@code value}, minting one on first sight. The same value always yields the same
     * token within a session, which is what lets a model see that two mentions are one person. Keyed solely on
     * {@code value}: if the same literal is ever detected under two different categories within one session, the first
     * sighting's category and ordinal win, and the second sighting's category is not recorded anywhere.
     *
     * @param category the span category the value was detected as
     * @param value    the detected value
     * @return the token text
     */
    public String tokenFor(String category, String value) {
        return valueToToken.computeIfAbsent(value, presentValue -> {
            String token = new PiiToken(category, nextOrdinal.getAndIncrement(), sessionId).text();

            tokenToValue.put(token, presentValue);

            return token;
        });
    }

    /**
     * Substitutes every token this session minted back to its value. A token this session does not know — an unknown
     * ordinal, or one minted by another session — is left exactly as it is, so an anomaly surfaces visibly instead of
     * becoming a silent wrong substitution.
     *
     * @param text the text to restore, or {@code null}
     * @return the text with known tokens replaced by their values, or {@code null} when {@code text} was {@code null}
     */
    public @Nullable String restore(@Nullable String text) {
        return restoreWithUnresolvedCount(text).text();
    }

    /**
     * As {@link #restore(String)}, but also reports how many tokens found in {@code text} this session could not
     * resolve back to a value — an unknown ordinal, or a token minted by another session. Exposing the count alongside
     * the restored text lets a caller record a metric for those misses without re-running the token regex a second
     * time.
     *
     * @param text the text to restore, or {@code null}
     * @return the restored text paired with how many tokens in it were left unresolved; the text is {@code null} when
     *         {@code text} was {@code null}
     */
    public RestoreResult restoreWithUnresolvedCount(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return new RestoreResult(text, 0);
        }

        // Deliberately NOT short-circuiting on tokenToValue.isEmpty(): a session that minted nothing this turn is
        // exactly the case a foreign/dead token needs to be caught in -- e.g. an earlier turn's now-closed-session
        // token replayed from retained chat history. Skipping the scan here would silently report zero unresolved
        // tokens for the one turn designed to surface them (see token_unresolved's javadoc on
        // AiGuardrails#restoreResponseText).

        Matcher matcher = PiiToken.pattern()
            .matcher(text);

        StringBuilder builder = new StringBuilder();
        int unresolvedCount = 0;

        while (matcher.find()) {
            String value = tokenToValue.get(matcher.group());

            if (value == null) {
                unresolvedCount++;
            }

            matcher.appendReplacement(builder, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }

        matcher.appendTail(builder);

        return new RestoreResult(builder.toString(), unresolvedCount);
    }

    /**
     * Returns this session's discriminator.
     *
     * @return the session id
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns how many distinct values this session holds.
     *
     * @return the mapping size
     */
    public int size() {
        return tokenToValue.size();
    }

    /**
     * Clears the mapping. Must be called on every termination path.
     */
    public void close() {
        tokenToValue.clear();
        valueToToken.clear();
    }

    /**
     * The result of {@link #restoreWithUnresolvedCount}: the restored text, and how many tokens found in it this
     * session could not resolve back to a value.
     *
     * @param text            the restored text, or {@code null} when the input text was {@code null}
     * @param unresolvedCount how many tokens in {@code text} were left unresolved
     */
    public record RestoreResult(@Nullable String text, int unresolvedCount) {
    }
}

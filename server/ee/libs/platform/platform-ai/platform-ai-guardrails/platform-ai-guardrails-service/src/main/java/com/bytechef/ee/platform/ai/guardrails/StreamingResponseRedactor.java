/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails;

import com.bytechef.ee.platform.ai.guardrails.detector.SensitiveDataRedactor;
import com.bytechef.ee.platform.ai.guardrails.detector.SensitiveKind;
import com.bytechef.ee.platform.ai.guardrails.detector.SensitiveSpan;
import com.bytechef.ee.platform.ai.guardrails.tokenization.PiiToken;
import com.bytechef.ee.platform.ai.guardrails.tokenization.PiiTokenSession;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

/**
 * Stateful, single-threaded redactor for masking PII/secrets in a streamed completion whose text arrives in fragments
 * that may split a sensitive value across two chunks. It buffers a bounded lookahead window and, each step, emits only
 * the leading portion of the buffer that ends at a "safe cut" — a position no matched span crosses. Any value that
 * straddles the tentative cut pulls the cut back to that value's start so the whole match stays buffered and is
 * redacted as one unit; any value still being received sits inside the retained window until it completes (or is
 * flushed at stream end). A secret split across chunk boundaries is therefore never emitted in the clear.
 *
 * <p>
 * Correctness: because no complete match crosses the safe cut, redacting the emitted segment in isolation equals the
 * corresponding portion of redacting the whole buffer, so the concatenation of every {@link #push} result plus the
 * final {@link #flush} equals {@code redact(fullStream, both kinds)}. This guarantee covers only stream-safe detectors
 * — see {@link SensitiveDataRedactor#streamSafeView()} — since a detector needing wider context than the lookahead
 * window would give different answers here than it gives over the whole document. The window bounds latency and also
 * the worst case: a value that is still incomplete (not yet matchable) and longer than the window may have a prefix
 * emitted before its pattern can match — the documented trade-off of scanning a stream without buffering it whole. Set
 * the window comfortably above the longest value you need to guarantee; the default covers every fixed-shape key/token
 * and typical JWTs.
 * </p>
 *
 * <p>
 * When constructed with a {@link PiiTokenSession}, a token (e.g. {@code [PII_EMAIL_1_k3n9]}) is treated exactly like
 * any other matched span for the purpose of the safe cut: {@link PiiToken#pattern()} feeds the same pull-back loop, so
 * a token is never split across two emitted chunks — {@code [PII_EMA} then {@code IL_1_k3n9]} would never match on
 * restore, and the user would see a raw token fragment. Each emitted segment (and the final {@link #flush}) is then
 * scanned FIRST and restored SECOND — the same ordering {@code AiGuardrails} uses for the non-streaming path, and for
 * the same reason: restoring first would hand the scanner the real value back, which it would immediately re-redact,
 * making the round trip a no-op.
 * </p>
 *
 * <p>
 * <b>Token protection has the same window precondition as every other value this class protects</b> — see the paragraph
 * above on values longer than the window. {@link PiiToken#pattern()} only matches a COMPLETE token; a token still
 * arriving produces no range at all, so the pull-back loop has nothing to hold onto until the closing {@code ]} is
 * already in the buffer. The window must therefore exceed the longest token you need to guarantee (a token's length
 * depends on its category name — {@code [PII_EMAIL_1_k3n9]} is 18 characters, a shorter category like
 * {@code [PII_SSN_1_k3n9]} is 16), or the token's own opening bracket can be evicted one push before its closing
 * bracket arrives, silently reproducing the documented "prefix emitted" trade-off for a token instead of an ordinary
 * secret. This is not merely theoretical: for a 16-character token shape like the SSN example, a window needs to be at
 * least 17 characters (length + 1) to keep that shape safe, and a 16-character window — exactly one short — produced
 * this exact failure during development of this feature, caught only by comparing against a larger window.
 * </p>
 *
 * <p>
 * <b>Restoration is independent of scanning.</b> The {@code kinds} a caller passes govern only whether {@link #push}
 * and {@link #flush} redact NEW sensitive spans they find in the streamed text — passing {@link EnumSet#noneOf(Class)
 * EnumSet.noneOf(SensitiveKind.class)} makes the scan step a no-op (see {@code SensitiveDataRedactor#redactWithSpans}'s
 * own empty-kinds short-circuit) and, correspondingly, skips even computing span candidates, since there is nothing
 * left to redact them for. That skip saves detector CPU only, not buffering latency: the window hold-back (the
 * {@code carry.length() <= window} guard in {@link #push}) and the pull-back loop run exactly the same either way — an
 * empty {@code kinds} does not make this class emit any sooner. Restoring a session's tokens is a different operation —
 * completing a transformation this class's own request-direction sibling already started — and runs whenever
 * {@code session} is non-{@code null}, regardless of {@code kinds}. Token ranges feed the pull-back loop whenever
 * {@code session} is present (so a token is never split even while ordinary scanning is switched off), and are skipped
 * entirely when it is not, since nothing in that case will ever restore them.
 * </p>
 *
 * <p>
 * Not thread-safe; use one instance per stream subscription.
 * </p>
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
public final class StreamingResponseRedactor {

    private static final int DEFAULT_WINDOW = 512;

    private final StringBuilder carry = new StringBuilder();
    private final EnumSet<SensitiveKind> kinds;
    private final @Nullable AiGuardrailMetrics metrics;
    private final @Nullable PiiTokenSession session;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    private final int window;

    private boolean redacted;

    /**
     * @param sensitiveDataRedactor a redactor restricted to stream-safe detectors — see
     *                              {@link SensitiveDataRedactor#streamSafeView()}. A detector needing wider context
     *                              than the lookahead window would give different answers here than it gives over the
     *                              whole document, so it is excluded rather than silently mis-scanned.
     */
    public StreamingResponseRedactor(SensitiveDataRedactor sensitiveDataRedactor) {
        this(sensitiveDataRedactor, DEFAULT_WINDOW, null, null, EnumSet.allOf(SensitiveKind.class));
    }

    /**
     * @param sensitiveDataRedactor a redactor restricted to stream-safe detectors
     * @param metrics               the instance to count detector failures through, tagged with the calling surface, or
     *                              {@code null} when the caller has none. Without it a detector that fails mid-stream
     *                              is logged but never counted, which is how this path was previously invisible on
     *                              every deployment.
     */
    public StreamingResponseRedactor(
        SensitiveDataRedactor sensitiveDataRedactor, @Nullable AiGuardrailMetrics metrics) {

        this(sensitiveDataRedactor, DEFAULT_WINDOW, metrics, null, EnumSet.allOf(SensitiveKind.class));
    }

    /**
     * @param sensitiveDataRedactor a redactor restricted to stream-safe detectors
     * @param metrics               the instance to count detector failures through, or {@code null}
     * @param session               the session that tokenized this call's request; each emitted segment (and the final
     *                              {@link #flush}) is scanned first and then restored through this session, so a token
     *                              the request minted comes back as its real value in the streamed response instead of
     *                              being forwarded to the caller verbatim
     */
    public StreamingResponseRedactor(
        SensitiveDataRedactor sensitiveDataRedactor, @Nullable AiGuardrailMetrics metrics,
        @Nullable PiiTokenSession session) {

        this(sensitiveDataRedactor, DEFAULT_WINDOW, metrics, session, EnumSet.allOf(SensitiveKind.class));
    }

    /**
     * As the three-argument form, but scanning only for {@code kinds} instead of every kind — see the class javadoc's
     * "Restoration is independent of scanning" paragraph. {@code AiGuardrails} uses this to pass
     * {@link EnumSet#noneOf(Class) EnumSet.noneOf(SensitiveKind.class)} when the operator has not opted into streaming
     * response scanning but {@code session} still needs its tokens restored.
     *
     * @param sensitiveDataRedactor a redactor restricted to stream-safe detectors
     * @param metrics               the instance to count detector failures through, or {@code null}
     * @param session               the session that tokenized this call's request
     * @param kinds                 the kinds to scan the streamed text for; empty to restore tokens without scanning
     */
    StreamingResponseRedactor(
        SensitiveDataRedactor sensitiveDataRedactor, @Nullable AiGuardrailMetrics metrics, PiiTokenSession session,
        EnumSet<SensitiveKind> kinds) {

        this(sensitiveDataRedactor, DEFAULT_WINDOW, metrics, session, kinds);
    }

    StreamingResponseRedactor(SensitiveDataRedactor sensitiveDataRedactor, int window) {
        this(sensitiveDataRedactor, window, null, null, EnumSet.allOf(SensitiveKind.class));
    }

    StreamingResponseRedactor(
        SensitiveDataRedactor sensitiveDataRedactor, int window, @Nullable AiGuardrailMetrics metrics) {

        this(sensitiveDataRedactor, window, metrics, null, EnumSet.allOf(SensitiveKind.class));
    }

    StreamingResponseRedactor(
        SensitiveDataRedactor sensitiveDataRedactor, int window, @Nullable PiiTokenSession session) {

        this(sensitiveDataRedactor, window, null, session, EnumSet.allOf(SensitiveKind.class));
    }

    /**
     * The canonical constructor every other one delegates to.
     *
     * @param kinds the kinds to scan the streamed text for; empty to restore {@code session}'s tokens without scanning
     *              at all
     */
    StreamingResponseRedactor(
        SensitiveDataRedactor sensitiveDataRedactor, int window, @Nullable AiGuardrailMetrics metrics,
        @Nullable PiiTokenSession session, EnumSet<SensitiveKind> kinds) {

        this.sensitiveDataRedactor = sensitiveDataRedactor;
        this.window = window;
        this.metrics = metrics;
        this.session = session;
        // Defensive copy: nothing needs the caller's live set, and the field's immutability should be structural
        // rather than incidental on every caller happening to pass a freshly constructed EnumSet.
        this.kinds = EnumSet.copyOf(kinds);
    }

    /**
     * Accepts the next streamed text fragment and returns the portion that is now safe to emit (redacted), which may be
     * empty when everything so far must still be held to protect against a value straddling a chunk boundary.
     *
     * @param text the next streamed fragment (may be {@code null} or empty)
     * @return the redacted text safe to emit now, possibly empty
     */
    public String push(String text) {
        if (text != null && !text.isEmpty()) {
            carry.append(text);
        }

        if (carry.length() <= window) {
            return "";
        }

        // Never emit within `window` chars of the end, so a value still arriving has room to complete before its span
        // is judged.
        int safeCut = carry.length() - window;

        // Span candidates are pointless to compute when nothing will ever be redacted for any kind -- every
        // registered detector would otherwise run over the whole buffer on every push for a redaction that never
        // happens. This saves detector CPU ONLY, not buffering latency: the window guard above and the pull-back
        // loop below run identically either way, so an empty kinds set does not make this class emit any sooner.
        // Token ranges are the mirror image: computing them is pointless when there is no session to ever restore
        // one, so they are skipped precisely when restoration cannot happen, not when scanning is off.
        List<SensitiveSpan> candidates =
            kinds.isEmpty() ? List.of() : sensitiveDataRedactor.detectCandidates(carry.toString(), metrics);
        List<int[]> tokenRanges = session == null ? List.of() : tokenRanges(carry);

        // Pull the cut back to the start of any matched span or token it lands inside, to a fixpoint (an earlier
        // span or token may in turn straddle the pulled-back cut). This must consider CANDIDATES, not the resolved
        // winners: a span that loses an overlap still occupies characters, and a cut inside it would still split a
        // value. Tokens join the same set of things the cut may not split -- see the class javadoc.
        boolean pulled = true;

        while (pulled) {
            pulled = false;

            for (SensitiveSpan candidate : candidates) {
                if (candidate.start() < safeCut && candidate.end() > safeCut) {
                    safeCut = candidate.start();
                    pulled = true;
                }
            }

            for (int[] tokenRange : tokenRanges) {
                if (tokenRange[0] < safeCut && tokenRange[1] > safeCut) {
                    safeCut = tokenRange[0];
                    pulled = true;
                }
            }
        }

        if (safeCut <= 0) {
            return "";
        }

        String rawSegment = carry.substring(0, safeCut);

        // Scan first, restore second -- see the class javadoc. Reversing this would hand the scanner back the real
        // value it just restored, which the scanner would immediately re-redact, making the round trip a no-op.
        // When kinds is empty this is a no-op over rawSegment -- see SensitiveDataRedactor#redactWithSpans's own
        // empty-kinds short-circuit, pinned by SensitiveDataRedactorTest#testEmptyKindSetRedactsNothing.
        String scanned = sensitiveDataRedactor.redact(rawSegment, kinds, metrics);

        if (!scanned.equals(rawSegment)) {
            redacted = true;
        }

        String emitted = restore(scanned);

        carry.delete(0, safeCut);

        return emitted;
    }

    /**
     * Returns the start/end offsets of every {@link PiiToken} recognised in {@code text}, so {@link #push} can feed
     * them into the same pull-back loop it already runs for matched spans and never cut through the middle of one.
     */
    private static List<int[]> tokenRanges(CharSequence text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = PiiToken.pattern()
            .matcher(text);

        while (matcher.find()) {
            ranges.add(new int[] {
                matcher.start(), matcher.end()
            });
        }

        return ranges;
    }

    /**
     * Redacts and returns everything still buffered, to be emitted once the stream completes. Idempotent afterwards
     * (returns empty until more is pushed).
     *
     * @return the redacted remainder, possibly empty
     */
    public String flush() {
        if (carry.length() == 0) {
            return "";
        }

        String raw = carry.toString();

        // Scan first, restore second -- see the class javadoc and push()'s matching comment.
        String scanned = sensitiveDataRedactor.redact(raw, kinds, metrics);

        if (!scanned.equals(raw)) {
            redacted = true;
        }

        String remainder = restore(scanned);

        carry.setLength(0);

        return remainder;
    }

    /**
     * Restores {@code scanned}'s tokens through {@code session} (unchanged when {@code session} is {@code null}),
     * recording {@code pii_restored} when at least one token was substituted and {@code token_unresolved} when a
     * token-shaped span in {@code scanned} could not be resolved back to a value -- the model mangled a token, or
     * emitted one this session never minted. Without this, a mangled token on the streaming path would surface as a raw
     * {@code [PII_*]} fragment in the response with no operator-visible signal at all, since
     * {@link PiiTokenSession#restore} alone throws the unresolved count away. Mirrors
     * {@code AiGuardrails#restoreResponseText}'s recording, which the streaming path does not otherwise go through.
     */
    private String restore(String scanned) {
        if (session == null) {
            return scanned;
        }

        PiiTokenSession.RestoreResult restoreResult = session.restoreWithUnresolvedCount(scanned);
        String restored = restoreResult.text();

        if (restoreResult.unresolvedCount() > 0) {
            record("token_unresolved");
        }

        if (!scanned.equals(restored)) {
            record("pii_restored");
        }

        return restored;
    }

    private void record(String event) {
        if (metrics != null) {
            metrics.record(event);
        }
    }

    /**
     * Returns whether any {@link #push} or {@link #flush} so far actually masked content, so the caller can emit a
     * single redaction metric per stream instead of one per chunk.
     *
     * @return {@code true} if at least one value has been redacted over the stream's lifetime
     */
    public boolean isRedacted() {
        return redacted;
    }
}

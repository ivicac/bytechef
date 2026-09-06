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

package com.bytechef.platform.ai.sensitivedata;

import com.bytechef.platform.ai.sensitivedata.tokenization.PiiTokenSession;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every registered {@link SensitiveDataDetector} over a piece of text, resolves the overlapping spans they report
 * into one non-overlapping accepted set, and applies the winners as {@code [REDACTED_*]} placeholders.
 *
 * <p>
 * This replaces the sequential {@code String.replaceAll} chain the guardrail engine used previously, in which each
 * pattern rewrote the text the next pattern was about to scan. That chain leaked part of any secret whose body
 * contained a credit-card-shaped digit run: the PII pass claimed the digits first and destroyed the text the secret
 * pattern needed, so {@code xoxb-1234567890123456-abcdef} was emitted as {@code xoxb-[REDACTED_CC]-abcdef} with the
 * token's prefix and suffix intact. Detecting against the original text and resolving centrally fixes that.
 * </p>
 *
 * <p>
 * <b>Resolution order</b> is total, so the outcome cannot depend on detector registration order: SECRET before PII,
 * then longer before shorter, then earlier before later, then category ascending. Candidates are taken greedily in that
 * order and a candidate overlapping an already-accepted span is dropped. The length tiebreak also reproduces, for a
 * reason that does not depend on list position, the one ordering property the old chain got right — an enclosing match
 * swallows a nested one.
 * </p>
 *
 * <p>
 * <b>Failure is open, per detector.</b> A detector that throws (or reports a span outside the text) is logged, counted
 * as {@code detector_failed}, and skipped for that call; the others still run. A model-backed detector's transient
 * failure must not take down every AI surface in the product. The residual risk is that content the failed detector
 * would have redacted proceeds unredacted, and where the caller passes no {@link SensitiveDataMetrics} the log line is
 * the only signal.
 * </p>
 *
 * <p>
 * Immutable and thread-safe, provided every registered detector is.
 * </p>
 *
 * <p>
 * {@code @Component} so this class is also reachable through ordinary Spring injection -- {@code AiGuardrails} (EE)
 * still builds its own instance by hand via the constructor below, since it needs a {@link #streamSafeView()}
 * derivative and predates this annotation, but a caller with no reason to build its own (e.g.
 * {@code PiiTokenBoundaryToolCallingManager}'s two wiring points, the AI Agent component and the AI Hub tool search
 * chain) can simply autowire the container's singleton. The constructor's {@code List<SensitiveDataDetector>} autowires
 * to every registered detector bean; in a CE-only deployment that list is empty (the built-in detectors are
 * {@code @ConditionalOnEEVersion}), so the bean exists but is genuinely inert, matching this class's usual behaviour
 * when it has nothing to detect.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
// CT_CONSTRUCTOR_THROW: the constructor validates its bounds and so can throw, which SpotBugs flags because this
// class is not final and a subclass could in principle be attacked through a finalizer. It cannot be made final --
// PiiTokenBoundaryToolCallingManagerTest subclasses it for throwing test doubles -- and nothing in this hierarchy
// declares a finalizer. Rejecting a null DetectionBounds is worth more than the theoretical attack: silently
// substituting the defaults would make a misconfigured caller look correctly configured.
@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
public class SensitiveDataRedactor {

    /**
     * The default minimum confidence a candidate span must meet or exceed to survive into resolution, {@code 0.4}.
     *
     * <p>
     * Task 1's catalogs score every pattern into one of three fixed bands — low ({@code 0.2}, the bare digit runs this
     * feature exists to suppress: an unadorned {@code \d{9}} or similar matches almost any nine-digit run, PII or not),
     * medium ({@code 0.6}, most named entity patterns with some structure but no checksum, e.g. {@code PHONE_NUMBER} or
     * a spaced/dashed national-identifier group like {@code UK_NHS}), and high ({@code 0.9}, patterns with essentially
     * no false-positive surface, e.g. {@code EMAIL_ADDRESS}, {@code IBAN_CODE}, or — since 2026-08-31, when
     * {@code CREDIT_CARD} gained a {@link PiiPatternCatalog.PiiPattern#validator()} — a Luhn-validated credit-card
     * match). {@code 0.4} sits in the open gap between low and medium, so it drops every low-band pattern while keeping
     * every medium- and high-band one — the intended effect — and, being strictly between rather than on a band edge,
     * remains correct regardless of whether the comparison at the boundary is {@code >=} or {@code >}.
     * </p>
     */
    public static final double DEFAULT_MIN_CONFIDENCE = 0.4;

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataRedactor.class);

    private static final Comparator<SensitiveSpan> RESOLUTION_ORDER = Comparator
        .comparingInt((SensitiveSpan span) -> span.kind() == SensitiveKind.SECRET ? 0 : 1)
        .thenComparing(
            Comparator.comparingInt(SensitiveSpan::length)
                .reversed())
        .thenComparingInt(SensitiveSpan::start)
        .thenComparing(SensitiveSpan::category);

    private final List<SensitiveDataDetector> detectors;
    private final DetectionBounds bounds;

    public SensitiveDataRedactor(List<SensitiveDataDetector> detectors) {
        this(detectors, DetectionBounds.DEFAULTS);
    }

    public SensitiveDataRedactor(List<SensitiveDataDetector> detectors, DetectionBounds bounds) {
        this.detectors = List.copyOf(detectors);
        this.bounds = Objects.requireNonNull(bounds, "bounds must not be null");
    }

    /**
     * The bounds a detection pass runs under. Detection is synchronous and pre-LLM, over text that routinely carries a
     * retrieved document, a pasted file or a whole conversation history, so without these it is unbounded in both size
     * and time on the request thread -- and the engine's fail-open catch cannot help, because a slow detector never
     * throws.
     *
     * @param timeout              the budget for the whole pass, checked between detectors. Cooperative, so it bounds
     *                             the aggregate but cannot interrupt a single pathological detector call. That is
     *                             acceptable while every pattern is hand-reviewed and bounded, and stops being
     *                             acceptable the moment operator-supplied regexes exist.
     * @param maxUnwindowableInput the input length above which a detector that cannot be applied to a fragment
     *                             ({@code streamSafe() == false}) is not run at all
     */
    public record DetectionBounds(Duration timeout, int maxUnwindowableInput) {

        public static final DetectionBounds DEFAULTS = new DetectionBounds(Duration.ofSeconds(2), 262144);

        public DetectionBounds {
            Objects.requireNonNull(timeout, "timeout must not be null");

            if (maxUnwindowableInput < 0) {
                throw new IllegalArgumentException(
                    "maxUnwindowableInput must be >= 0, got: " + maxUnwindowableInput);
            }
        }
    }

    /**
     * Returns a redactor over only the {@link SensitiveDataDetector#streamSafe()} subset of this one's detectors, for
     * the streaming path which can offer a detector no more than a bounded lookahead window.
     *
     * @return a redactor restricted to stream-safe detectors, or {@code this} when every detector already is
     */
    public SensitiveDataRedactor streamSafeView() {
        List<SensitiveDataDetector> streamSafeDetectors = new ArrayList<>(detectors.size());

        for (SensitiveDataDetector detector : detectors) {
            if (detector.streamSafe()) {
                streamSafeDetectors.add(detector);
            } else {
                log.info(
                    "Detector '{}' is not stream-safe and is excluded from streaming response redaction",
                    detector.name());
            }
        }

        if (streamSafeDetectors.size() == detectors.size()) {
            return this;
        }

        // Carries this redactor's bounds, not the defaults: a view that silently reverted would be invisible in
        // every test that does not configure them.
        return new SensitiveDataRedactor(streamSafeDetectors, bounds);
    }

    /**
     * Returns every candidate span reported by every detector, unresolved and possibly overlapping. The streaming
     * redactor needs the unresolved set: a span that loses an overlap still occupies characters, and a cut landing
     * inside one must still be pulled back.
     *
     * @param text    the text to scan, never {@code null}
     * @param metrics the metrics instance to count detector failures through, or {@code null}
     * @return the candidate spans, empty when {@code text} is empty or none are found
     */
    public List<SensitiveSpan> detectCandidates(String text, @Nullable SensitiveDataMetrics metrics) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<SensitiveSpan> candidates = new ArrayList<>();
        long deadline = System.nanoTime() + bounds.timeout()
            .toNanos();
        // The SAME instant the cooperative between-detector checks use, not a second budget. So the pass timeout is
        // now genuinely enforced: it bounds the whole pass as before AND, through MatchDeadline, any single match
        // inside it -- which is what the cooperative form alone could never do.
        MatchDeadline matchDeadline = MatchDeadline.at(deadline);

        // Windowable detectors run first, to completion. That ordering IS the guarantee that the regex pass -- where
        // every identifying pattern lives -- cannot be starved by a detector that runs long, and it needs no
        // detector to classify its own cost: streamSafe() already partitions them exactly.
        for (SensitiveDataDetector detector : detectors) {
            if (!detector.streamSafe()) {
                continue;
            }

            if (timedOut(deadline, detector, metrics)) {
                return List.copyOf(candidates);
            }

            collectSpans(detector, text, candidates, matchDeadline, metrics);
        }

        for (SensitiveDataDetector detector : detectors) {
            if (detector.streamSafe()) {
                continue;
            }

            if (text.length() > bounds.maxUnwindowableInput()) {
                log.warn(
                    "Sensitive-data detector '{}' cannot be applied to a fragment and was skipped for a {}-character " +
                        "input exceeding the {}-character limit; its coverage is absent for this call",
                    detector.name(), text.length(), bounds.maxUnwindowableInput());

                if (metrics != null) {
                    metrics.recordDetectorSkippedOversize(detector.name());
                }

                continue;
            }

            if (timedOut(deadline, detector, metrics)) {
                return List.copyOf(candidates);
            }

            collectSpans(detector, text, candidates, matchDeadline, metrics);
        }

        return List.copyOf(candidates);
    }

    /**
     * Reports whether the pass has exhausted its budget, recording {@code detector_timed_out} and warning once when it
     * has. Deliberately not {@code detector_failed}: that event means a detector threw, and the whole reason this bound
     * exists is that a slow detector never throws.
     */
    private static boolean timedOut(
        long deadline, SensitiveDataDetector detector, @Nullable SensitiveDataMetrics metrics) {

        if (System.nanoTime() < deadline) {
            return false;
        }

        log.warn(
            "Sensitive-data detection exceeded its budget while running '{}'; the spans returned for this call are " +
                "partial and some of the content was not scanned",
            detector.name());

        if (metrics != null) {
            metrics.recordDetectorTimedOut(detector.name());
        }

        return true;
    }

    /**
     * Returns {@code text} with every accepted span whose kind is in {@code kinds} replaced by its placeholder.
     *
     * <p>
     * The kind filter is applied to the CANDIDATES, before resolution. Filtering afterwards would let a span the caller
     * did not ask for consume an overlap and then be discarded, so a PII-only call over a secret containing a digit run
     * would return the text unredacted.
     * </p>
     *
     * @param text    the text to redact
     * @param kinds   the kinds the caller's policy has enabled
     * @param metrics the metrics instance to count detector failures through, or {@code null}
     * @return the redacted text, or {@code text} unchanged when nothing applies
     */
    public String redact(
        String text, Set<SensitiveKind> kinds, @Nullable SensitiveDataMetrics metrics) {

        return redact(text, kinds, DEFAULT_MIN_CONFIDENCE, metrics);
    }

    /**
     * As {@link #redact(String, Set, SensitiveDataMetrics)}, but with an explicit minimum confidence instead of
     * {@link #DEFAULT_MIN_CONFIDENCE}.
     *
     * @param text          the text to redact
     * @param kinds         the kinds the caller's policy has enabled
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @param metrics       the metrics instance to count detector failures and below-threshold drops through, or
     *                      {@code null}
     * @return the redacted text, or {@code text} unchanged when nothing applies
     */
    public String redact(
        String text, Set<SensitiveKind> kinds, double minConfidence, @Nullable SensitiveDataMetrics metrics) {

        return redactWithSpans(text, kinds, minConfidence, metrics).text();
    }

    /**
     * As {@link #redact}, but also returning the spans that were actually applied.
     *
     * <p>
     * This is the single implementation of the pipeline; {@link #redact} is a projection of it. The spans are needed by
     * callers that must report on WHAT was redacted rather than only substitute it — {@code AiGuardrails} counts its
     * {@code pii_redacted} / {@code secret_redacted} metrics off the accepted set, which is more accurate than
     * comparing strings. Before this existed that caller drove {@link #filterByKind}, {@link #resolve} and
     * {@link #apply} itself, giving the codebase two implementations of a pipeline whose whole point is that there is
     * exactly one: a step added here would silently not have reached the request-direction path.
     * </p>
     *
     * @param text    the text to redact
     * @param kinds   the kinds the caller's policy has enabled
     * @param metrics the metrics instance to count detector failures through, or {@code null}
     * @return the redacted text and the spans applied to produce it; the spans are empty when nothing applied
     */
    public RedactionResult redactWithSpans(
        String text, Set<SensitiveKind> kinds, @Nullable SensitiveDataMetrics metrics) {

        return redactWithSpans(text, kinds, DEFAULT_MIN_CONFIDENCE, metrics);
    }

    /**
     * As {@link #redactWithSpans(String, Set, SensitiveDataMetrics)}, but with an explicit minimum confidence instead
     * of {@link #DEFAULT_MIN_CONFIDENCE}.
     *
     * <p>
     * The confidence filter is applied to the CANDIDATES, before resolution, for the same reason {@link #filterByKind}
     * is: a weak span that resolution would otherwise let win an overlap must not be able to consume that overlap and
     * then be discarded, which would leave a stronger overlapping span unredacted. It runs before the kind filter.
     * </p>
     *
     * @param text          the text to redact
     * @param kinds         the kinds the caller's policy has enabled
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be redacted
     * @param metrics       the metrics instance to count detector failures and below-threshold drops through, or
     *                      {@code null}
     * @return the redacted text and the spans applied to produce it; the spans are empty when nothing applied
     */
    public RedactionResult redactWithSpans(
        String text, Set<SensitiveKind> kinds, double minConfidence, @Nullable SensitiveDataMetrics metrics) {

        return redactWithSpans(text, kinds, minConfidence, metrics, List.of());
    }

    /**
     * As the overload without {@code extraCandidates}, but folding in spans a caller found itself.
     *
     * <p>
     * The extra spans join <b>before</b> confidence filtering, kind filtering and resolution -- which is the entire
     * point, not an implementation detail. A workspace's own rule overlapping a built-in pattern has to be settled by
     * the one span-ordering rule that already exists; spans merged after resolution would be settled by which list they
     * came from, which is a second precedence concept the design explicitly refuses to introduce.
     * </p>
     *
     * @param extraCandidates spans found outside this redactor, e.g. by {@code CustomPatternEvaluator} over a
     *                        workspace's own rules. Empty is the common case and costs nothing.
     */
    public RedactionResult redactWithSpans(
        String text, Set<SensitiveKind> kinds, double minConfidence, @Nullable SensitiveDataMetrics metrics,
        List<SensitiveSpan> extraCandidates) {

        // text is non-null by contract -- callers (AiGuardrails' redactPii/redactSecrets/redactAll) guard null/empty
        // before ever delegating here. The `text == null` arm is kept anyway as defence-in-depth: this sits on a
        // redaction path, where failing soft (returning the input unchanged) beats throwing on a future caller that
        // does not honour the contract. It does not mean this parameter is expected to receive null.
        if (text == null || text.isEmpty() || kinds.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> candidates = filterByKind(
            filterByConfidence(withExtras(detectCandidates(text, metrics), extraCandidates), minConfidence, metrics),
            kinds);

        if (candidates.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> accepted = resolve(candidates);

        return new RedactionResult(apply(text, accepted), accepted);
    }

    /**
     * As {@link #redactWithSpans}, but PII spans become tokens minted by {@code session} while SECRET spans keep their
     * {@code [REDACTED_SECRET]} placeholder.
     *
     * <p>
     * Secrets deliberately do not tokenize. Restoring a secret would take a credential the guardrail successfully
     * caught and paste it back into the output, defeating the catch. The two treatments come from one pass over the
     * same resolved spans, so there is no second pipeline to drift.
     * </p>
     *
     * @param text    the text to tokenize
     * @param kinds   the kinds the caller's policy has enabled
     * @param session the session minting tokens for this request
     * @param metrics the metrics instance to count detector failures through, or {@code null}
     * @return the tokenized text and the spans applied to produce it
     */
    public RedactionResult tokenizeWithSpans(
        String text, Set<SensitiveKind> kinds, PiiTokenSession session, @Nullable SensitiveDataMetrics metrics) {

        return tokenizeWithSpans(text, kinds, session, DEFAULT_MIN_CONFIDENCE, metrics);
    }

    /**
     * As {@link #tokenizeWithSpans(String, Set, PiiTokenSession, SensitiveDataMetrics)}, but with an explicit minimum
     * confidence instead of {@link #DEFAULT_MIN_CONFIDENCE}.
     *
     * @param text          the text to tokenize
     * @param kinds         the kinds the caller's policy has enabled
     * @param session       the session minting tokens for this request
     * @param minConfidence the minimum confidence, inclusive, a candidate span must meet to be tokenized/redacted
     * @param metrics       the metrics instance to count detector failures and below-threshold drops through, or
     *                      {@code null}
     * @return the tokenized text and the spans applied to produce it
     */
    public RedactionResult tokenizeWithSpans(
        String text, Set<SensitiveKind> kinds, PiiTokenSession session, double minConfidence,
        @Nullable SensitiveDataMetrics metrics) {

        return tokenizeWithSpans(text, kinds, session, minConfidence, metrics, List.of());
    }

    /**
     * As the overload without {@code extraCandidates}, but folding in spans a caller found itself.
     *
     * <p>
     * The extra spans join <b>before</b> confidence filtering, kind filtering and resolution -- which is the entire
     * point, not an implementation detail. A workspace's own rule overlapping a built-in pattern has to be settled by
     * the one span-ordering rule that already exists; spans merged after resolution would be settled by which list they
     * came from, which is a second precedence concept the design explicitly refuses to introduce.
     * </p>
     *
     * @param extraCandidates spans found outside this redactor, e.g. by {@code CustomPatternEvaluator} over a
     *                        workspace's own rules. Empty is the common case and costs nothing.
     */
    public RedactionResult tokenizeWithSpans(
        String text, Set<SensitiveKind> kinds, PiiTokenSession session, double minConfidence,
        @Nullable SensitiveDataMetrics metrics, List<SensitiveSpan> extraCandidates) {

        // text is non-null by contract -- callers (AiGuardrails' redactPiiAndSecrets) guard null/empty before ever
        // delegating here. The `text == null` arm is kept anyway as defence-in-depth: this sits on a redaction path,
        // where failing soft (returning the input unchanged) beats throwing on a future caller that does not honour
        // the contract. It does not mean this parameter is expected to receive null.
        if (text == null || text.isEmpty() || kinds.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> candidates = filterByKind(
            filterByConfidence(withExtras(detectCandidates(text, metrics), extraCandidates), minConfidence, metrics),
            kinds);

        if (candidates.isEmpty()) {
            return new RedactionResult(text, List.of());
        }

        List<SensitiveSpan> accepted = resolve(candidates);

        String tokenized = apply(text, accepted, span -> {
            if (span.kind() == SensitiveKind.SECRET) {
                return span.placeholder();
            }

            return session.tokenFor(span.category(), text.substring(span.start(), span.end()));
        });

        return new RedactionResult(tokenized, accepted);
    }

    /**
     * The outcome of one redaction: the resulting text, and the non-overlapping spans that produced it.
     *
     * @param text     the redacted text
     * @param accepted the spans applied, in resolution order; empty when nothing was redacted
     */
    public record RedactionResult(String text, List<SensitiveSpan> accepted) {

        // Defensive copy rather than @SuppressFBWarnings: nothing needs the caller's live list, and a value record
        // handed to metric-counting code should not be able to change under it.
        public RedactionResult {
            accepted = List.copyOf(accepted);
        }
    }

    /**
     * Returns the subset of {@code candidates} whose kind is in {@code kinds}. Public because {@code AiGuardrails} sits
     * in the parent package and drives the three stages separately, so that it can count which kinds actually won
     * before applying them.
     *
     * @param candidates the unresolved candidate spans
     * @param kinds      the kinds to keep
     * @return the matching candidates, in their original order
     */
    static List<SensitiveSpan> filterByKind(List<SensitiveSpan> candidates, Set<SensitiveKind> kinds) {
        List<SensitiveSpan> filtered = new ArrayList<>(candidates.size());

        for (SensitiveSpan candidate : candidates) {
            if (kinds.contains(candidate.kind())) {
                filtered.add(candidate);
            }
        }

        return filtered;
    }

    /**
     * Returns the subset of {@code candidates} whose {@link SensitiveSpan#confidence()} is at or above
     * {@code minConfidence}, recording at most one {@code below_confidence_threshold} event through {@code metrics}
     * regardless of how many candidates were dropped.
     *
     * <p>
     * Runs before resolution, for the same reason {@link #filterByKind} does: a low-confidence span that resolution
     * would let win a tie-break must not be able to consume that overlap and then be discarded, which would leave a
     * stronger overlapping span unredacted. {@link #detectCandidates} deliberately does not call this — the streaming
     * safe-cut pulls its emit boundary back for any candidate, so a low-confidence match must still not be splittable
     * across chunks even though it will never be redacted.
     * </p>
     *
     * @param candidates    the unresolved candidate spans
     * @param minConfidence the minimum confidence, inclusive, a candidate must meet to be kept
     * @param metrics       the metrics instance to count the drop through, or {@code null}
     * @return the matching candidates, in their original order
     */
    static List<SensitiveSpan> filterByConfidence(
        List<SensitiveSpan> candidates, double minConfidence, @Nullable SensitiveDataMetrics metrics) {

        List<SensitiveSpan> kept = new ArrayList<>(candidates.size());
        boolean dropped = false;

        for (SensitiveSpan candidate : candidates) {
            if (candidate.confidence() >= minConfidence) {
                kept.add(candidate);
            } else {
                dropped = true;
            }
        }

        if (dropped && metrics != null) {
            metrics.recordBelowConfidenceThreshold();
        }

        return kept;
    }

    /**
     * Reduces overlapping candidates to a non-overlapping accepted set using the total order documented on this class.
     *
     * @param candidates the candidate spans, possibly overlapping and in any order
     * @return the accepted spans, none of which overlap another
     */
    static List<SensitiveSpan> resolve(List<SensitiveSpan> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<SensitiveSpan> ordered = new ArrayList<>(candidates);

        ordered.sort(RESOLUTION_ORDER);

        List<SensitiveSpan> accepted = new ArrayList<>();

        for (SensitiveSpan candidate : ordered) {
            if (!overlapsAny(candidate, accepted)) {
                accepted.add(candidate);
            }
        }

        return accepted;
    }

    /**
     * Replaces every accepted span with its placeholder, working right to left so that each replacement leaves the
     * offsets of the spans not yet applied valid.
     *
     * @param text     the original text the spans were located in
     * @param accepted non-overlapping spans, in any order
     * @return the redacted text
     */
    static String apply(String text, List<SensitiveSpan> accepted) {
        return apply(text, accepted, SensitiveSpan::placeholder);
    }

    /**
     * Replaces every accepted span with whatever {@code replacer} returns for it, working right to left so that each
     * replacement leaves the offsets of the spans not yet applied valid.
     *
     * <p>
     * The replacer is what lets one pass produce two treatments: redaction passes {@code SensitiveSpan::placeholder},
     * tokenization passes a function that mints a token for PII and keeps the placeholder for secrets. Without it,
     * tokenization would need a parallel copy of this loop.
     * </p>
     *
     * @param text     the original text the spans were located in
     * @param accepted non-overlapping spans, in any order
     * @param replacer produces the replacement text for one span
     * @return the rewritten text
     */
    static String apply(String text, List<SensitiveSpan> accepted, Function<SensitiveSpan, String> replacer) {
        if (accepted.isEmpty()) {
            return text;
        }

        List<SensitiveSpan> ordered = new ArrayList<>(accepted);

        ordered.sort(
            Comparator.comparingInt(SensitiveSpan::start)
                .reversed());

        StringBuilder builder = new StringBuilder(text);

        for (SensitiveSpan span : ordered) {
            builder.replace(span.start(), span.end(), replacer.apply(span));
        }

        return builder.toString();
    }

    private static List<SensitiveSpan> withExtras(
        List<SensitiveSpan> detected, List<SensitiveSpan> extraCandidates) {

        if (extraCandidates.isEmpty()) {
            return detected;
        }

        List<SensitiveSpan> merged = new ArrayList<>(detected.size() + extraCandidates.size());

        merged.addAll(detected);
        merged.addAll(extraCandidates);

        return merged;
    }

    private void collectSpans(
        SensitiveDataDetector detector, String text, List<SensitiveSpan> candidates,
        MatchDeadline matchDeadline, @Nullable SensitiveDataMetrics metrics) {

        try {
            List<SensitiveSpan> spans = detector.detect(text, matchDeadline);

            if (spans == null) {
                return;
            }

            for (SensitiveSpan span : spans) {
                if (span.end() > text.length()) {
                    throw new IllegalStateException(
                        "detector reported a span ending at " + span.end() + ", past the end of a " + text.length() +
                            "-character input");
                }
            }

            candidates.addAll(spans);
        } catch (DetectionTimeoutException detectionTimeoutException) {
            // NOT detector_failed. A detector that threw and one that was cut off mid-match are different facts, and
            // the whole reason this bound exists is that the second used to be invisible -- "a slow detector never
            // throws" was true until MatchDeadline made it false.
            log.warn(
                "Sensitive-data detector '{}' exceeded its match deadline; continuing without its spans: {}",
                detector.name(), detectionTimeoutException.getMessage());

            if (metrics != null) {
                metrics.recordDetectorTimedOut(detector.name());
            }
        } catch (StackOverflowError stackOverflowError) {
            // Deliberately catching an Error, which is normally wrong. Measured on this JVM: (a|aa)+$ against 4,000
            // characters overflows the stack in 8ms -- faster than any useful deadline, and an Error, so the
            // RuntimeException catch below never saw it. Left uncaught it kills the guarded call outright, which is a
            // worse outcome than losing one detector's spans. The stack has already unwound by the time we are here
            // and a Matcher holds no state the next call inherits, so continuing is safe.
            log.warn(
                "Sensitive-data detector '{}' overflowed the stack; continuing without its spans", detector.name());

            if (metrics != null) {
                metrics.recordDetectorFailure(detector.name());
            }
        } catch (RuntimeException exception) {
            log.warn("Sensitive-data detector '{}' failed; continuing without its spans", detector.name(), exception);

            if (metrics != null) {
                metrics.recordDetectorFailure(detector.name());
            }
        }
    }

    private static boolean overlapsAny(SensitiveSpan candidate, List<SensitiveSpan> accepted) {
        for (SensitiveSpan span : accepted) {
            if (candidate.overlaps(span)) {
                return true;
            }
        }

        return false;
    }
}

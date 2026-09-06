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

import java.util.List;

/**
 * Locates sensitive data in text. Implementations are contributed as Spring beans and collected into
 * {@code SensitiveDataRedactor}; contributing one requires no change to the guardrails engine.
 *
 * <p>
 * Every detector is handed the ORIGINAL text and never another detector's output, so a detector's result cannot be
 * corrupted by one that happens to run before it. Overlaps between detectors are resolved centrally by a total order
 * over the spans themselves, which is why registration order cannot affect the redacted result.
 * </p>
 *
 * <p>
 * Implementations must be thread-safe and side-effect-free: one instance serves every workspace and every concurrent
 * request. A detector that throws is caught, logged, counted, and skipped for that call — the other detectors still
 * run. See the design spec's section 8 for the residual risk that fail-open policy accepts.
 * </p>
 *
 * <p>
 * A detector that runs a regex should implement {@link #detect(String, MatchDeadline)} rather than only
 * {@link #detect(String)}. Matching against {@code deadline.bound(text)} is what makes a pathological pattern
 * interruptible; without it the only bound is the cooperative one between detectors, which cannot stop a match already
 * running.
 * </p>
 */
public interface SensitiveDataDetector {

    /**
     * Returns a short stable identifier used in log lines and diagnostics.
     *
     * @return the detector name
     */
    String name();

    /**
     * Returns every sensitive region found in {@code text}. Spans may overlap each other and may be returned in any
     * order; the caller resolves and orders them. Offsets must lie within {@code text}.
     *
     * @param text the text to scan; never {@code null} and never empty
     * @return the spans found, empty when none
     */
    List<SensitiveSpan> detect(String text);

    /**
     * As {@link #detect(String)}, but bounded: an implementation that runs a regex should match against
     * {@code deadline.bound(text)} so a pathological pattern is interrupted rather than left running on the caller's
     * thread.
     *
     * <p>
     * A default that ignores the deadline, on purpose. A detector that does not run regexes has nothing to bound this
     * way -- {@code OpenNlpSensitiveDataDetector} tokenizes first and then runs a model, and neither step reads through
     * a {@code CharSequence} -- and forcing every implementation to declare that would be noise. The bound belongs
     * where the hazard is.
     * </p>
     *
     * @param text     the text to scan; never {@code null} and never empty
     * @param deadline the budget for this detector's matching
     * @return the spans found, empty when none
     * @throws DetectionTimeoutException when matching ran past {@code deadline}
     */
    default List<SensitiveSpan> detect(String text, MatchDeadline deadline) {
        return detect(text);
    }

    /**
     * Returns whether this detector can be applied to an arbitrary substring of a document and give the same answer it
     * would give for the whole.
     *
     * <p>
     * A regex detector is local in this sense and the default is therefore {@code true}. A detector needing wider
     * context — sentence-level named-entity recognition, say — must return {@code false}: the streaming redactor scans
     * a bounded lookahead window, and feeding such a detector a window that starts mid-sentence produces different and
     * worse answers than feeding it the complete text. The streaming path skips detectors that return {@code false}, so
     * that a detector which cannot honestly cover a stream is visibly absent from it rather than silently contributing
     * nothing usable.
     * </p>
     *
     * @return {@code true} when this detector is safe to run over a windowed fragment
     */
    default boolean streamSafe() {
        return true;
    }
}

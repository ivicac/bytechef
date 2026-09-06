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

/**
 * The metrics seam {@code SensitiveDataRedactor} records a detector failure through. A CE module cannot depend on the
 * EE {@code AiGuardrailMetrics} type, so this narrow interface stands in for it on the redactor's public surface; EE's
 * {@code AiGuardrailMetrics} implements it and delegates to its own {@code record(String)} helper with the
 * {@code detector_failed} event, preserving its existing event name and surface tag.
 *
 * <p>
 * Kept a {@link FunctionalInterface} on purpose: {@link #recordDetectorFailure} is the only abstract method, so
 * existing single-method-reference implementations (e.g. {@code SensitiveDataMetricsTest}'s {@code recorded::add}) keep
 * compiling unchanged. {@link #recordBelowConfidenceThreshold} is a default no-op for the same reason — a caller that
 * only cares about detector failures is not forced to implement an event it never emits.
 * </p>
 */
@FunctionalInterface
public interface SensitiveDataMetrics {

    /**
     * Records that a {@link SensitiveDataDetector} threw and was skipped for the call it was scanning.
     *
     * @param detectorName the failing detector's {@link SensitiveDataDetector#name()}
     */
    void recordDetectorFailure(String detectorName);

    /**
     * Records that at least one candidate span was dropped from a single {@code redact}/{@code redactWithSpans}/
     * {@code tokenizeWithSpans} call because its confidence fell below the caller's {@code minConfidence}. Recorded at
     * most once per call, matching the existing family's incidence-counter shape (one increment per content, not one
     * per dropped span).
     */
    default void recordBelowConfidenceThreshold() {
        // No-op default: most callers of this seam (e.g. detector-failure-only test doubles) have no interest in this
        // event, and making it abstract would force every existing implementation to add a body for it.
    }
}

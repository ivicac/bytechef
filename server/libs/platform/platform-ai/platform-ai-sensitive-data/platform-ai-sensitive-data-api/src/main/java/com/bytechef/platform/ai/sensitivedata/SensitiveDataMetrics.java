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
     * Records that a detection pass abandoned its remaining work because it exceeded its configured budget, so the
     * spans it returned are partial. Recorded at most once per call, naming the detector that was running when the
     * budget ran out.
     *
     * <p>
     * Deliberately distinct from {@link #recordDetectorFailure}. That event means a detector threw and the engine
     * continued without it; this one means a detector was still working and was cut off. Conflating them would lose the
     * distinction the whole bound turns on -- the engine's fail-open catch cannot see a slow detector at all, because a
     * slow detector never throws.
     * </p>
     *
     * @param detectorName the detector that was running when the budget expired
     */
    default void recordDetectorTimedOut(String detectorName) {
        // No-op default, for the same reason recordBelowConfidenceThreshold is one.
    }

    /**
     * Records that a detector which cannot be applied to a fragment ({@link SensitiveDataDetector#streamSafe()} is
     * {@code false}) was not run at all, because the input exceeded the configured maximum for such a detector.
     *
     * <p>
     * This is the loud half of a deliberate coverage loss. Such a detector cannot be windowed by its own contract, so
     * on a very large input the choice is between skipping it and handing it a truncated prefix. A truncated prefix
     * would report a clean scan of a document only partly read; a skip reports, on a counter naming the detector, that
     * it did not run.
     * </p>
     *
     * @param detectorName the detector that was skipped
     */
    default void recordDetectorSkippedOversize(String detectorName) {
        // No-op default, for the same reason recordBelowConfidenceThreshold is one.
    }

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

    /**
     * Records that at least one PII token in a tool call's arguments was restored to its real value before
     * {@code PiiTokenBoundaryToolCallingManager}'s delegate ran the tool. Recorded at most once per
     * {@code executeToolCalls} invocation, no matter how many of that invocation's tool calls (or how many tokens
     * within any one of them) were actually restored -- matching {@link #recordBelowConfidenceThreshold}'s
     * incidence-counter shape.
     */
    default void recordToolArgsRestored() {
        // No-op default: a caller with no interest in the tool boundary (e.g. the response-direction-only production
        // implementation predating this event) is not forced to add a body for it.
    }

    /**
     * Records that at least one value in a tool's result was tokenized or redacted before
     * {@code PiiTokenBoundaryToolCallingManager} returned it to the model. Recorded at most once per
     * {@code executeToolCalls} invocation, no matter how many tool-response messages or accepted spans produced it --
     * matching {@link #recordToolArgsRestored}'s incidence-counter shape (once per invocation, not once per span).
     */
    default void recordToolResultTokenized() {
        // No-op default, for the same reason recordToolArgsRestored is.
    }

    /**
     * Records that a token-shaped span found in a tool call's arguments could not be resolved back to a value -- an
     * unknown ordinal, or one minted by another session. Reuses the same {@code token_unresolved} event the
     * response-direction restoration path already records (see {@code AiGuardrails#restoreResponseText} and
     * {@code StreamingResponseRedactor}), rather than inventing a separate name for the tool-boundary case. Recorded at
     * most once per {@code executeToolCalls} invocation.
     */
    default void recordTokenUnresolved() {
        // No-op default, for the same reason recordToolArgsRestored is.
    }

    /**
     * Records that at least one assistant tool-call argument in the conversation history
     * {@code PiiTokenBoundaryToolCallingManager} returns was retokenized before that history went out -- the tool
     * itself already ran against the real value; this is only about the copy of that value the returned history (and,
     * on the suspend/resume path, persisted task state) would otherwise carry forward in clear. A distinct event from
     * {@link #recordToolResultTokenized()}: that one is about a tool's RESULT reaching the model, this one is about the
     * assistant message that REQUESTED the tool call reaching it a turn later than intended. Recorded at most once per
     * {@code executeToolCalls} invocation, no matter how many assistant messages or tool calls within them were
     * retokenized -- matching {@link #recordToolResultTokenized()}'s incidence-counter shape.
     */
    default void recordAssistantHistoryRetokenized() {
        // No-op default, for the same reason recordToolArgsRestored is.
    }

    /**
     * Records that a restoration this call was entitled to perform was withheld by workspace policy -- the response
     * carried resolvable tokens and they were left in place because the destination was a workflow output and
     * {@code restoreIntoWorkflowOutput} is off.
     *
     * <p>
     * Recorded only when there was something to restore. Without that condition this counter would tick on every call
     * of every workflow under the setting, and an admin could not tell a workspace that is actually withholding data
     * from one that simply has no PII in flight -- which is the single question the setting is adopted or abandoned on.
     * </p>
     */
    default void recordRestoreSuppressed() {
        // No-op default, for the same reason recordToolArgsRestored is.
    }
}

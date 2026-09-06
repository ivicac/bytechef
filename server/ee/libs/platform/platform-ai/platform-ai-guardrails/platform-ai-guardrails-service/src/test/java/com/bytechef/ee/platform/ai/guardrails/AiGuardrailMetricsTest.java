/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
class AiGuardrailMetricsTest {

    @Test
    void testRecordIncrementsCounterByEventAndSurface() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "gateway");

        metrics.record("pii_redacted");
        metrics.record("pii_redacted");
        metrics.record("blocked_term");

        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "pii_redacted", "surface", "gateway")
            .count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "blocked_term", "surface", "gateway")
            .count()).isEqualTo(1.0);
    }

    @Test
    void testRecordIsNoOpWithoutRegistry() {
        AiGuardrailMetrics metrics = new AiGuardrailMetrics((MeterRegistry) null, "gateway");

        assertThatCode(() -> metrics.record("pii_redacted")).doesNotThrowAnyException();
    }

    @Test
    void testRecordDetectorFailureDelegatesToDetectorFailedEventUnderThisInstancesSurface() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        metrics.recordDetectorFailure("opennlp");

        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "detector_failed", "surface", "ai_hub")
            .count()).isEqualTo(1.0);
    }

    /**
     * Pins that {@code AiGuardrailMetrics} actually overrides {@code SensitiveDataMetrics#recordToolArgsRestored()}
     * rather than silently inheriting the interface's no-op default -- a caller through the
     * {@code SensitiveDataMetrics} seam (e.g. {@code PiiTokenBoundaryToolCallingManager}) would otherwise record
     * nothing and nobody would notice.
     */
    @Test
    void testRecordToolArgsRestoredDelegatesToToolArgsRestoredEventUnderThisInstancesSurface() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        metrics.recordToolArgsRestored();

        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "tool_args_restored", "surface", "ai_hub")
            .count()).isEqualTo(1.0);
    }

    /**
     * As {@link #testRecordToolArgsRestoredDelegatesToToolArgsRestoredEventUnderThisInstancesSurface}, for
     * {@code recordToolResultTokenized()}.
     */
    @Test
    void testRecordToolResultTokenizedDelegatesToToolResultTokenizedEventUnderThisInstancesSurface() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        metrics.recordToolResultTokenized();

        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "tool_result_tokenized", "surface", "ai_hub")
            .count()).isEqualTo(1.0);
    }

    /**
     * As {@link #testRecordToolArgsRestoredDelegatesToToolArgsRestoredEventUnderThisInstancesSurface}, for
     * {@code recordTokenUnresolved()} -- which reuses the pre-existing {@code token_unresolved} event name rather than
     * a tool-boundary-specific one.
     */
    @Test
    void testRecordTokenUnresolvedDelegatesToTokenUnresolvedEventUnderThisInstancesSurface() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        metrics.recordTokenUnresolved();

        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "token_unresolved", "surface", "ai_hub")
            .count()).isEqualTo(1.0);
    }

    /**
     * As {@link #testRecordToolArgsRestoredDelegatesToToolArgsRestoredEventUnderThisInstancesSurface}, for
     * {@code recordAssistantHistoryRetokenized()}.
     */
    @Test
    void testRecordAssistantHistoryRetokenizedDelegatesToAssistantHistoryRetokenizedEventUnderThisInstancesSurface() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        AiGuardrailMetrics metrics = new AiGuardrailMetrics(meterRegistry, "ai_hub");

        metrics.recordAssistantHistoryRetokenized();

        assertThat(meterRegistry.counter(
            AiGuardrailMetrics.COUNTER_NAME, "event", "assistant_history_retokenized", "surface", "ai_hub")
            .count()).isEqualTo(1.0);
    }
}

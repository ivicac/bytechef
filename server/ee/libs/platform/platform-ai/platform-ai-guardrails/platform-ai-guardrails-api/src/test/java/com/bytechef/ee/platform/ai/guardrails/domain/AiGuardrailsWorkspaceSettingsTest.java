/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the finding-5 fix from the 2026-08-31 final-branch review: unlike {@code PiiPattern}/{@code SecretPattern},
 * which validate their {@code score} to {@code [0.0, 1.0]} in a compact constructor,
 * {@link AiGuardrailsWorkspaceSettings} previously validated {@link AiGuardrailsWorkspaceSettings#minConfidence()}
 * nowhere -- at the record, the service, or the GraphQL layer. A {@code -1.0} override cleared
 * {@code confidence() >= minConfidence} for every span, reinstating this feature's own false-positive bug
 * workspace-wide; a {@code 1.5} override cleared no span in the catalog, silently disabling PII and secret redaction
 * while {@code redactPii}/{@code redactSecrets} kept reading {@code true}. The fix mirrors
 * {@code PiiPatternCatalog.PiiPattern}'s compact-constructor style exactly.
 *
 * @version ee
 */
class AiGuardrailsWorkspaceSettingsTest {

    @Test
    void testNullMinConfidenceIsValid() {
        AiGuardrailsWorkspaceSettings settings = settingsWithMinConfidence(null);

        assertThat(settings.minConfidence()).isNull();
    }

    @Test
    void testMinConfidenceWithinRangeIsValid() {
        assertThat(settingsWithMinConfidence(0.0)
            .minConfidence()).isEqualTo(0.0);
        assertThat(settingsWithMinConfidence(0.6)
            .minConfidence()).isEqualTo(0.6);
        assertThat(settingsWithMinConfidence(1.0)
            .minConfidence()).isEqualTo(1.0);
    }

    /**
     * Mutation evidence for the finding-5 fix: a negative override, if unvalidated, satisfies
     * {@code confidence() >= minConfidence} for every possible span (even one scoring the minimum {@code 0.0}),
     * silently reinstating the false-positive bug this whole feature exists to fix. Removing the compact constructor's
     * guard makes this test fail by no longer throwing.
     */
    @Test
    void testNegativeMinConfidenceIsRejected() {
        assertThatThrownBy(() -> settingsWithMinConfidence(-1.0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minConfidence");
    }

    /**
     * Mutation evidence for the finding-5 fix: an above-range override, if unvalidated, fails
     * {@code confidence() >= minConfidence} for every possible span (even one scoring the maximum {@code 1.0}),
     * silently disabling PII and secret redaction for the workspace while {@code redactPii}/{@code redactSecrets} keep
     * reading enabled. Removing the compact constructor's guard makes this test fail by no longer throwing.
     */
    @Test
    void testAboveRangeMinConfidenceIsRejected() {
        assertThatThrownBy(() -> settingsWithMinConfidence(1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minConfidence");
    }

    @Test
    void testNanMinConfidenceIsRejected() {
        assertThatThrownBy(() -> settingsWithMinConfidence(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiGuardrailsWorkspaceSettings settingsWithMinConfidence(Double minConfidence) {
        return new AiGuardrailsWorkspaceSettings(
            AiGuardrailsSettingsScope.WORKSPACE, 1L, null, null, null, null, null, null, null, minConfidence, null);
    }
}

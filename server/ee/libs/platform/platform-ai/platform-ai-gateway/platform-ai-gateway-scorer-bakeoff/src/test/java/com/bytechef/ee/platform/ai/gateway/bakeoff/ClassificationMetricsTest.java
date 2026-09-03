/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class ClassificationMetricsTest {

    private static final Offset<Double> TOLERANCE = Offset.offset(1e-6);

    private static BakeoffPrompt prompt(String id, String label) {
        return new BakeoffPrompt(id, "text", label, null, null, "bucket", "rationale");
    }

    @Test
    void testPerfectSeparationScoresOne() {
        List<BakeoffPrompt> prompts = List.of(
            prompt("a", "SIMPLE"), prompt("b", "SIMPLE"), prompt("c", "COMPLEX"), prompt("d", "COMPLEX"));

        double[] scores = {
            0.1, 0.2, 0.8, 0.9
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.accuracy()).isEqualTo(1.0, TOLERANCE);
        assertThat(result.precision()).isEqualTo(1.0, TOLERANCE);
        assertThat(result.recall()).isEqualTo(1.0, TOLERANCE);
        assertThat(result.areaUnderCurve()).isEqualTo(1.0, TOLERANCE);
    }

    @Test
    void testInvertedSeparationScoresZeroAreaUnderCurve() {
        List<BakeoffPrompt> prompts = List.of(prompt("a", "SIMPLE"), prompt("b", "COMPLEX"));

        double[] scores = {
            0.9, 0.1
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.areaUnderCurve()).isEqualTo(0.0, TOLERANCE);
        assertThat(result.accuracy()).isEqualTo(0.0, TOLERANCE);
    }

    @Test
    void testAllTiedScoresGiveHalfAreaUnderCurve() {
        List<BakeoffPrompt> prompts = List.of(
            prompt("a", "SIMPLE"), prompt("b", "COMPLEX"), prompt("c", "SIMPLE"), prompt("d", "COMPLEX"));

        double[] scores = {
            0.5, 0.5, 0.5, 0.5
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.areaUnderCurve()).isEqualTo(0.5, TOLERANCE);
    }

    @Test
    void testPrecisionAndRecallDifferWhenPredictionsAreSkewed() {
        List<BakeoffPrompt> prompts = List.of(
            prompt("a", "COMPLEX"), prompt("b", "COMPLEX"), prompt("c", "SIMPLE"), prompt("d", "SIMPLE"));

        double[] scores = {
            0.9, 0.1, 0.9, 0.1
        };

        ClassificationResult result = ClassificationMetrics.evaluate(prompts, scores, 0.5);

        assertThat(result.precision()).isEqualTo(0.5, TOLERANCE);
        assertThat(result.recall()).isEqualTo(0.5, TOLERANCE);
        assertThat(result.accuracy()).isEqualTo(0.5, TOLERANCE);
    }
}

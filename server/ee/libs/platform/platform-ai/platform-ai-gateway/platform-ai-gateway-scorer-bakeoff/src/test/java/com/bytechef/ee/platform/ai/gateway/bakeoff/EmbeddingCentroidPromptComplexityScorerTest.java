/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Uses a stub embedding model with hand-placed vectors so the mapping from cosine geometry to the 0-1 contract is
 * asserted exactly, and so CI never downloads an ONNX model.
 *
 * @version ee
 */
class EmbeddingCentroidPromptComplexityScorerTest {

    private static final float[] COMPLEX_VECTOR = {
        0.0f, 1.0f
    };
    private static final float[] SIMPLE_VECTOR = {
        1.0f, 0.0f
    };

    private static final List<BakeoffPrompt> TRAINING = List.of(
        new BakeoffPrompt("t-1", "simple one", "SIMPLE", null, null, "b", "r"),
        new BakeoffPrompt("t-2", "simple two", "SIMPLE", null, null, "b", "r"),
        new BakeoffPrompt("t-3", "complex one", "COMPLEX", null, null, "b", "r"),
        new BakeoffPrompt("t-4", "complex two", "COMPLEX", null, null, "b", "r"));

    private static final Map<String, float[]> VECTORS = Map.of(
        "complex one", COMPLEX_VECTOR,
        "complex two", COMPLEX_VECTOR,
        "simple one", SIMPLE_VECTOR,
        "simple two", SIMPLE_VECTOR);

    private final EmbeddingModel embeddingModel = new StubEmbeddingModel();

    private final EmbeddingCentroidPromptComplexityScorer scorer =
        new EmbeddingCentroidPromptComplexityScorer(embeddingModel, TRAINING);

    private static AiGatewayChatCompletionRequest request(String text) {
        return BakeoffCorpus.toRequest(new BakeoffPrompt("q", text, "SIMPLE", null, null, "query", "n/a"));
    }

    @Test
    void testPromptOnTheComplexCentroidScoresOne() {
        assertThat(scorer.score(request("complex one"))).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void testPromptOnTheSimpleCentroidScoresZero() {
        assertThat(scorer.score(request("simple one"))).isEqualTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void testEquidistantPromptScoresAHalf() {
        assertThat(scorer.score(request("midpoint"))).isEqualTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void testScoreIsAlwaysWithinUnitInterval() {
        assertThat(scorer.score(request("complex one"))).isBetween(0.0, 1.0);
        assertThat(scorer.score(request("simple one"))).isBetween(0.0, 1.0);
        assertThat(scorer.score(request("midpoint"))).isBetween(0.0, 1.0);
    }

    @Test
    void testEmptyPromptDoesNotThrow() {
        assertThat(scorer.score(request(""))).isBetween(0.0, 1.0);
    }

    @Test
    void testNullContentDoesNotThrow() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "bakeoff", List.of(new AiGatewayChatMessage(AiGatewayChatRole.USER, null)), null, null, null,
            false, null, null, null, null);

        assertThat(scorer.score(request)).isBetween(0.0, 1.0);
    }

    /**
     * Returns the placed vector for known training text, and the equidistant vector for anything else.
     */
    private static final class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public float[] embed(String text) {
            float[] placed = VECTORS.get(text.trim());

            if (placed != null) {
                return placed.clone();
            }

            return equidistantVector();
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            String text = document.getText();

            if (text == null) {
                return equidistantVector();
            }

            return embed(text);
        }

        private static float[] equidistantVector() {
            float halfRootTwo = (float) (1.0 / Math.sqrt(2.0));

            return new float[] {
                halfRootTwo, halfRootTwo
            };
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("The bake-off stub only implements embed(String)");
        }
    }
}

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
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class OpenNlpPromptComplexityScorerTest {

    private static final List<BakeoffPrompt> TRAINING = List.of(
        new BakeoffPrompt("t-1", "What is the capital of France?", "SIMPLE", null, null, "factual", "lookup"),
        new BakeoffPrompt("t-2", "Translate hello into Spanish.", "SIMPLE", null, null, "translation", "lookup"),
        new BakeoffPrompt("t-3", "List the days of the week.", "SIMPLE", null, null, "factual", "lookup"),
        new BakeoffPrompt(
            "t-4", "Prove that the square root of two is irrational and explain the contradiction.", "COMPLEX",
            null, null, "proof", "requires a proof"),
        new BakeoffPrompt(
            "t-5", "Design a fault tolerant queue and justify the trade offs you make.", "COMPLEX", null, null,
            "design", "requires a design and justification"),
        new BakeoffPrompt(
            "t-6", "Explain why the diagonalisation argument establishes undecidability.", "COMPLEX", null, null,
            "proof", "requires reasoning"));

    private final OpenNlpPromptComplexityScorer scorer = new OpenNlpPromptComplexityScorer(TRAINING);

    private static AiGatewayChatCompletionRequest request(String text) {
        return BakeoffCorpus.toRequest(new BakeoffPrompt("q", text, "SIMPLE", null, null, "query", "n/a"));
    }

    @Test
    void testScoreIsWithinUnitInterval() {
        assertThat(scorer.score(request("What is the capital of Spain?"))).isBetween(0.0, 1.0);
        assertThat(scorer.score(request("Prove that there are infinitely many primes."))).isBetween(0.0, 1.0);
    }

    @Test
    void testComplexTrainingVocabularyScoresAboveSimple() {
        double complexScore = scorer.score(request("Prove that the contradiction establishes irrationality."));
        double simpleScore = scorer.score(request("What is the capital of Spain?"));

        assertThat(complexScore).isGreaterThan(simpleScore);
    }

    @Test
    void testEmptyPromptDoesNotThrow() {
        assertThat(scorer.score(request(""))).isBetween(0.0, 1.0);
    }

    @Test
    void testNullContentDoesNotThrow() {
        AiGatewayChatCompletionRequest request = new AiGatewayChatCompletionRequest(
            "bakeoff", List.of(new AiGatewayChatMessage(AiGatewayChatRole.USER, null)), null, null, null, false,
            null, null, null, null);

        assertThat(scorer.score(request)).isBetween(0.0, 1.0);
    }
}

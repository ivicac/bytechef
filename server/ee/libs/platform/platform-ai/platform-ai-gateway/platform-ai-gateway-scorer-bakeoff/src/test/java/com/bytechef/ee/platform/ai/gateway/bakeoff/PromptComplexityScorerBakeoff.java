/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayRoutingStrategyType;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.routing.DeterministicPromptComplexityScorer;
import com.bytechef.ee.platform.ai.gateway.routing.OpenNlpPromptComplexityScorer;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bake-off runner. Excluded from the default test task by the module build file and invoked through the
 * {@code bakeoff} Gradle task; it is a measurement instrument, not a test, and asserts nothing about which scorer wins.
 * Its output is {@code build/bakeoff/report.md} and {@code build/bakeoff/scores.csv}.
 *
 * <p>
 * Runs the two shipped scorers, both loaded from {@code platform-ai-gateway-service} rather than reimplemented here, so
 * the harness measures production behaviour rather than a stand-in. Candidates B
 * ({@code EmbeddingCentroidPromptComplexityScorer}, in-process embeddings) and D (the same class over a remote
 * embedding model) were deleted after losing the 2026-09-03 run recorded in the design spec's outcome section (§13);
 * this module, its corpus and this runner are retained as the regression harness for any future candidate.
 *
 * @version ee
 */
class PromptComplexityScorerBakeoff {

    private static final List<AiGatewayModelTier> PRESENT_TIERS = List.of(
        AiGatewayModelTier.BASIC, AiGatewayModelTier.EFFICIENT, AiGatewayModelTier.STANDARD,
        AiGatewayModelTier.ADVANCED, AiGatewayModelTier.FRONTIER);

    private static final Path OUTPUT_DIRECTORY = Path.of("build", "bakeoff");

    @Test
    void testRunBakeoff() throws Exception {
        List<BakeoffPrompt> adversarial = BakeoffCorpus.load("adversarial.jsonl");

        Map<String, PromptComplexityScorer> scorers = new LinkedHashMap<>();

        scorers.put("baseline", new DeterministicPromptComplexityScorer());
        scorers.put("opennlp", new OpenNlpPromptComplexityScorer());

        List<AiGatewayChatCompletionRequest> requests = new ArrayList<>();

        for (BakeoffPrompt prompt : adversarial) {
            requests.add(BakeoffCorpus.toRequest(prompt));
        }

        Map<String, double[]> scoresByScorer = new LinkedHashMap<>();
        Map<String, ClassificationResult> resultsByScorer = new LinkedHashMap<>();
        Map<String, Map<AiGatewayModelTier, Integer>> distributionsByScorer = new LinkedHashMap<>();
        Map<String, Long> latencyByScorer = new LinkedHashMap<>();

        for (Map.Entry<String, PromptComplexityScorer> entry : scorers.entrySet()) {
            PromptComplexityScorer scorer = entry.getValue();

            for (AiGatewayChatCompletionRequest request : requests) {
                scorer.score(request);
            }

            double[] scores = new double[requests.size()];

            long startNanos = System.nanoTime();

            for (int index = 0; index < requests.size(); index++) {
                scores[index] = scorer.score(requests.get(index));
            }

            long elapsedNanos = System.nanoTime() - startNanos;

            scoresByScorer.put(entry.getKey(), scores);
            resultsByScorer.put(entry.getKey(), ClassificationMetrics.evaluate(adversarial, scores, 0.5));
            distributionsByScorer.put(
                entry.getKey(),
                RoutingOutcomeMetrics.tierDistribution(
                    scores, AiGatewayRoutingStrategyType.INTELLIGENT_BALANCED, PRESENT_TIERS));
            latencyByScorer.put(entry.getKey(), elapsedNanos / requests.size() / 1000);
        }

        Files.createDirectories(OUTPUT_DIRECTORY);

        StringBuilder markdown = new StringBuilder(
            BakeoffReport.toMarkdown(resultsByScorer, distributionsByScorer));

        markdown.append("\n| scorer | mean score() microseconds |\n|---|---|\n");

        for (Map.Entry<String, Long> entry : latencyByScorer.entrySet()) {
            markdown.append(String.format("| %s | %d |%n", entry.getKey(), entry.getValue()));
        }

        Files.writeString(
            OUTPUT_DIRECTORY.resolve("report.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(
            OUTPUT_DIRECTORY.resolve("scores.csv"), BakeoffReport.toCsv(adversarial, scoresByScorer),
            StandardCharsets.UTF_8);

        System.out.println(markdown);
    }
}

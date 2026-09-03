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
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * The bake-off runner. Excluded from the default test task by the module build file and invoked through the
 * {@code bakeoff} Gradle task; it is a measurement instrument, not a test, and asserts nothing about which scorer wins.
 * Its output is {@code build/bakeoff/report.md} and {@code build/bakeoff/scores.csv}.
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
        List<BakeoffPrompt> exemplars = BakeoffCorpus.load("exemplars.jsonl");
        List<BakeoffPrompt> adversarial = BakeoffCorpus.load("adversarial.jsonl");

        TransformersEmbeddingModel localEmbeddingModel = new TransformersEmbeddingModel();

        localEmbeddingModel.afterPropertiesSet();

        Map<String, PromptComplexityScorer> scorers = new LinkedHashMap<>();

        scorers.put("baseline", new DeterministicPromptComplexityScorer());
        scorers.put("opennlp", new OpenNlpPromptComplexityScorer(exemplars));
        scorers.put(
            "embedding-local", new EmbeddingCentroidPromptComplexityScorer(localEmbeddingModel, exemplars));

        String apiKey = remoteApiKey();

        if (apiKey == null) {
            System.out.println(
                "SKIPPED candidate D (embedding-remote): no bakeoff.embedding.apiKey system property and no "
                    + "BAKEOFF_EMBEDDING_API_KEY environment variable. The report below covers the baseline, "
                    + "candidate B and candidate C only.");
        } else {
            OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();

            EmbeddingModel remoteEmbeddingModel = new OpenAiEmbeddingModel(openAiClient);

            scorers.put(
                "embedding-remote",
                new EmbeddingCentroidPromptComplexityScorer(remoteEmbeddingModel, exemplars));
        }

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

    /**
     * Candidate D's key, from a system property or the environment. Absent, candidate D is skipped and said so in the
     * run output — never silently omitted.
     */
    private static String remoteApiKey() {
        String property = System.getProperty("bakeoff.embedding.apiKey");

        if (property != null && !property.isBlank()) {
            return property;
        }

        String environment = System.getenv("BAKEOFF_EMBEDDING_API_KEY");

        if (environment != null && !environment.isBlank()) {
            return environment;
        }

        return null;
    }
}

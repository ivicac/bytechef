/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatMessage;
import com.bytechef.ee.platform.ai.gateway.routing.PromptComplexityScorer;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Bake-off candidate B, reproducing Merge Gateway's technique: the labelled exemplars are embedded and averaged into a
 * SIMPLE centroid and a COMPLEX centroid, and a prompt is scored by where it falls between them.
 *
 * <p>
 * The cosine difference ranges over [-2, 2], so the mapping to the 0-1 contract is {@code (1 + difference) / 2}. It is
 * monotonic in "closer to the complex centroid", which is the only property {@code IntelligentRoutingStrategy} relies
 * on.
 *
 * @version ee
 */
public final class EmbeddingCentroidPromptComplexityScorer implements PromptComplexityScorer {

    private final float[] complexCentroid;
    private static final int MAX_EMBEDDING_CHARACTERS = 20_000;

    private final EmbeddingModel embeddingModel;
    private final float[] simpleCentroid;

    public EmbeddingCentroidPromptComplexityScorer(
        EmbeddingModel embeddingModel, List<BakeoffPrompt> exemplars) {

        this.embeddingModel = embeddingModel;

        this.complexCentroid = centroidOf(embeddingModel, exemplars, true);
        this.simpleCentroid = centroidOf(embeddingModel, exemplars, false);
    }

    @Override
    public double score(AiGatewayChatCompletionRequest request) {
        String content = concatenateContent(request);

        if (content.isBlank()) {
            return 0.0;
        }

        float[] embedding = embeddingModel.embed(truncateForEmbedding(content));

        double difference = cosineSimilarity(embedding, complexCentroid)
            - cosineSimilarity(embedding, simpleCentroid);

        return Math.max(0.0, Math.min((1.0 + difference) / 2.0, 1.0));
    }

    /**
     * Both embedding candidates must see the same input, and neither can see an unbounded one.
     *
     * <p>
     * The corpus deliberately contains a 100,000-character {@code long-but-trivial} prompt. A hosted embeddings API
     * rejects that outright — candidate D's first run died on {@code 400: This model's maximum context length is 8192
     * tokens, however you requested 15684} — while the local ONNX model of candidate B accepts it and silently
     * truncates to its own context window. Left alone, the two candidates would therefore be scored on different
     * inputs, with the local one appearing to handle a length it was in fact discarding.
     *
     * <p>
     * Truncating here makes that explicit and equal. The cap is characters rather than tokens because the scorer has no
     * tokenizer of its own; at the ~3.2 characters per token the failing request implies, 20,000 characters is
     * comfortably inside an 8192-token window for the mixed prose, code and non-English text in this corpus.
     *
     * <p>
     * This is not only a harness concern: a production embedding-based scorer sits in front of arbitrary gateway
     * traffic and needs the same bound, so measuring the truncated behaviour is measuring what would ship. Candidate C
     * reads the full text, which is a real asymmetry between the techniques and is recorded as such in the report.
     */
    private static String truncateForEmbedding(String text) {
        return text.length() <= MAX_EMBEDDING_CHARACTERS ? text : text.substring(0, MAX_EMBEDDING_CHARACTERS);
    }

    private static float[] centroidOf(
        EmbeddingModel embeddingModel, List<BakeoffPrompt> exemplars, boolean complex) {

        float[] sum = null;
        int count = 0;

        for (BakeoffPrompt exemplar : exemplars) {
            if (exemplar.isComplex() != complex) {
                continue;
            }

            float[] embedding = embeddingModel.embed(truncateForEmbedding(exemplar.text()));

            if (sum == null) {
                sum = new float[embedding.length];
            }

            for (int index = 0; index < embedding.length; index++) {
                sum[index] += embedding[index];
            }

            count++;
        }

        if (sum == null || count == 0) {
            throw new IllegalArgumentException(
                "The exemplar set contains no " + (complex ? "COMPLEX" : "SIMPLE") + " entries");
        }

        for (int index = 0; index < sum.length; index++) {
            sum[index] /= count;
        }

        return sum;
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        double dotProduct = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;

        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String concatenateContent(AiGatewayChatCompletionRequest request) {
        StringBuilder builder = new StringBuilder();

        for (AiGatewayChatMessage message : request.messages()) {
            String content = message.content();

            if (content != null) {
                builder.append(content)
                    .append(' ');
            }
        }

        return builder.toString();
    }
}

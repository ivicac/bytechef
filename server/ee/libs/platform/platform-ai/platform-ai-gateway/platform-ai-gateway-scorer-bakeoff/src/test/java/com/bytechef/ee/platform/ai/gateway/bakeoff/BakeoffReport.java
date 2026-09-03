/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import com.bytechef.ee.platform.ai.gateway.domain.AiGatewayModelTier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders bake-off results as Markdown for the spec's outcome section and as CSV for per-prompt inspection.
 *
 * @version ee
 */
public final class BakeoffReport {

    private BakeoffReport() {
    }

    public static String toMarkdown(
        Map<String, ClassificationResult> results,
        Map<String, Map<AiGatewayModelTier, Integer>> distributions) {

        StringBuilder builder = new StringBuilder();

        builder.append("| scorer | accuracy | precision | recall | AUC | n |\n");
        builder.append("|---|---|---|---|---|---|\n");

        for (Map.Entry<String, ClassificationResult> entry : new TreeMap<>(results).entrySet()) {
            ClassificationResult result = entry.getValue();

            builder.append(String.format(
                "| %s | %.3f | %.3f | %.3f | %.3f | %d |%n",
                entry.getKey(), result.accuracy(), result.precision(), result.recall(), result.areaUnderCurve(),
                result.count()));
        }

        if (!distributions.isEmpty()) {
            builder.append("\n| scorer | tier distribution |\n|---|---|\n");

            for (Map.Entry<String, Map<AiGatewayModelTier, Integer>> entry : new TreeMap<>(distributions)
                .entrySet()) {

                builder.append(String.format("| %s | %s |%n", entry.getKey(), entry.getValue()));
            }
        }

        return builder.toString();
    }

    public static String toCsv(List<BakeoffPrompt> prompts, Map<String, double[]> scoresByScorer) {
        Map<String, double[]> ordered = new TreeMap<>(scoresByScorer);

        StringBuilder builder = new StringBuilder("id,bucket,label,rationale");

        for (String scorer : ordered.keySet()) {
            builder.append(',')
                .append(scorer);
        }

        builder.append('\n');

        for (int index = 0; index < prompts.size(); index++) {
            BakeoffPrompt prompt = prompts.get(index);

            builder.append(escape(prompt.id()))
                .append(',')
                .append(escape(prompt.bucket()))
                .append(',')
                .append(escape(prompt.label()))
                .append(',')
                .append(escape(prompt.rationale()));

            for (double[] scores : ordered.values()) {
                builder.append(',')
                    .append(String.format("%.4f", scores[index]));
            }

            builder.append('\n');
        }

        return builder.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }

        return '"' + value.replace("\"", "\"\"") + '"';
    }
}

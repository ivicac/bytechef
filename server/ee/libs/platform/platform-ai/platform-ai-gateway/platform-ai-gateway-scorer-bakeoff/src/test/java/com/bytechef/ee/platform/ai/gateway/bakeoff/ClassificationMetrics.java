/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Accuracy, precision, recall and rank based ROC-AUC over a set of scored prompts. The area under the curve is computed
 * by the Mann-Whitney U identity using average ranks, so tied scores contribute 0.5 rather than being silently ordered
 * by input position.
 *
 * @version ee
 */
public final class ClassificationMetrics {

    private ClassificationMetrics() {
    }

    public static ClassificationResult evaluate(
        List<BakeoffPrompt> prompts, double[] scores, double threshold) {

        if (prompts.size() != scores.length) {
            throw new IllegalArgumentException(
                "prompts and scores must be the same length: " + prompts.size() + " vs " + scores.length);
        }

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        int correct = 0;

        for (int index = 0; index < scores.length; index++) {
            boolean actual = prompts.get(index)
                .isComplex();
            boolean predicted = scores[index] >= threshold;

            if (predicted == actual) {
                correct++;
            }

            if (predicted && actual) {
                truePositives++;
            } else if (predicted) {
                falsePositives++;
            } else if (actual) {
                falseNegatives++;
            }
        }

        double precision = truePositives + falsePositives == 0
            ? 0.0
            : (double) truePositives / (truePositives + falsePositives);
        double recall = truePositives + falseNegatives == 0
            ? 0.0
            : (double) truePositives / (truePositives + falseNegatives);

        return new ClassificationResult(
            (double) correct / scores.length, precision, recall, areaUnderCurve(prompts, scores), scores.length);
    }

    private static double areaUnderCurve(List<BakeoffPrompt> prompts, double[] scores) {
        List<Integer> order = new ArrayList<>();

        for (int index = 0; index < scores.length; index++) {
            order.add(index);
        }

        order.sort(Comparator.comparingDouble(index -> scores[index]));

        double[] ranks = new double[scores.length];

        int position = 0;

        while (position < order.size()) {
            int end = position;

            while (end + 1 < order.size() && scores[order.get(end + 1)] == scores[order.get(position)]) {
                end++;
            }

            double averageRank = (position + end + 2) / 2.0;

            for (int index = position; index <= end; index++) {
                ranks[order.get(index)] = averageRank;
            }

            position = end + 1;
        }

        double positiveRankSum = 0.0;
        int positives = 0;

        for (int index = 0; index < scores.length; index++) {
            if (prompts.get(index)
                .isComplex()) {

                positiveRankSum += ranks[index];
                positives++;
            }
        }

        int negatives = scores.length - positives;

        if (positives == 0 || negatives == 0) {
            return 0.5;
        }

        return (positiveRankSum - positives * (positives + 1) / 2.0) / ((double) positives * negatives);
    }
}

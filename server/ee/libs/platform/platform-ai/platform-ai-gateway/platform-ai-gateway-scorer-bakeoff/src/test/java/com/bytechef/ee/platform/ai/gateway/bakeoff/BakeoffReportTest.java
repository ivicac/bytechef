/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class BakeoffReportTest {

    private static final Map<String, ClassificationResult> RESULTS = Map.of(
        "baseline", new ClassificationResult(0.55, 0.5, 0.6, 0.58, 60),
        "opennlp", new ClassificationResult(0.70, 0.7, 0.7, 0.75, 60));

    @Test
    void testMarkdownContainsARowPerScorer() {
        String markdown = BakeoffReport.toMarkdown(RESULTS, Map.of());

        assertThat(markdown).contains("baseline");
        assertThat(markdown).contains("opennlp");
    }

    @Test
    void testMarkdownFormatsScoresToThreeDecimals() {
        String markdown = BakeoffReport.toMarkdown(RESULTS, Map.of());

        assertThat(markdown).contains("0.550");
    }

    @Test
    void testCsvHasAHeaderAndARowPerPrompt() {
        List<BakeoffPrompt> prompts = List.of(
            new BakeoffPrompt("adv-001", "text", "COMPLEX", null, null, "short-but-hard", "reason"));

        String csv = BakeoffReport.toCsv(prompts, Map.of("baseline", new double[] {
            0.25
        }));

        String[] lines = csv.split("\n");

        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("id,bucket,label");
        assertThat(lines[1]).startsWith("adv-001,short-but-hard,COMPLEX");
    }

    @Test
    void testCsvQuotesFieldsContainingCommas() {
        List<BakeoffPrompt> prompts = List.of(
            new BakeoffPrompt("adv-002", "text", "SIMPLE", null, null, "non-english", "a, b"));

        String csv = BakeoffReport.toCsv(prompts, Map.of("baseline", new double[] {
            0.1
        }));

        assertThat(csv).contains("\"a, b\"");
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * @version ee
 */
class BakeoffCorpusTest {

    @Test
    void testLoadReadsEveryLine() throws IOException {
        List<BakeoffPrompt> prompts = BakeoffCorpus.load("exemplars.jsonl");

        assertThat(prompts).hasSize(countNonBlankLines("exemplars.jsonl"));
    }

    /**
     * Counts the resource's own non-blank lines rather than asserting a fixed number: the corpus grows as prompts are
     * added, and a hard-coded size would fail on every addition while proving nothing about the loader.
     */
    private static int countNonBlankLines(String resourceName) throws IOException {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                Objects.requireNonNull(BakeoffCorpus.class.getResourceAsStream("/bakeoff/" + resourceName)),
                StandardCharsets.UTF_8))) {

            int count = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    count++;
                }
            }

            return count;
        }
    }

    @Test
    void testLoadParsesLabels() {
        List<BakeoffPrompt> prompts = BakeoffCorpus.load("exemplars.jsonl");

        BakeoffPrompt first = prompts.getFirst();

        assertThat(first.id()).isEqualTo("ex-001");
        assertThat(first.isComplex()).isFalse();
    }

    @Test
    void testLoadPreservesEmbeddedNewlines() {
        List<BakeoffPrompt> prompts = BakeoffCorpus.load("exemplars.jsonl");

        BakeoffPrompt multiline = prompts.get(2);

        assertThat(multiline.text()).contains("\n");
    }

    @Test
    void testToRequestCarriesToolCountAndMaxTokens() {
        BakeoffPrompt prompt = new BakeoffPrompt("t-1", "hello", "SIMPLE", 3, 512, "sanity", "trivial");

        AiGatewayChatCompletionRequest request = BakeoffCorpus.toRequest(prompt);

        assertThat(request.messages()).hasSize(1);
        assertThat(request.tools()).hasSize(3);
        assertThat(request.maxTokens()).isEqualTo(512);
    }

    @Test
    void testToRequestOmitsToolsWhenCountIsNull() {
        BakeoffPrompt prompt = new BakeoffPrompt("t-2", "hello", "SIMPLE", null, null, "sanity", "trivial");

        AiGatewayChatCompletionRequest request = BakeoffCorpus.toRequest(prompt);

        assertThat(request.tools()).isNull();
        assertThat(request.maxTokens()).isNull();
    }
}

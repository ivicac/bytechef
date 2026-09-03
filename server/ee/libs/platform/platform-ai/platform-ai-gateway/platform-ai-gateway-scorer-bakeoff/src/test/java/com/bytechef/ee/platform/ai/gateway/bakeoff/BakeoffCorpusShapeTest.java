/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.bakeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the corpus invariants the experiment design depends on. A corpus that drifts out of balance, or that overlaps
 * between training and evaluation, silently invalidates every number the bake-off produces.
 *
 * @version ee
 */
class BakeoffCorpusShapeTest {

    private static final List<BakeoffPrompt> ADVERSARIAL = BakeoffCorpus.load("adversarial.jsonl");
    private static final List<BakeoffPrompt> EXEMPLARS = BakeoffCorpus.load("exemplars.jsonl");

    private static Set<String> idsOf(List<BakeoffPrompt> prompts) {
        return prompts.stream()
            .map(BakeoffPrompt::id)
            .collect(Collectors.toSet());
    }

    private static long complexCount(List<BakeoffPrompt> prompts) {
        return prompts.stream()
            .filter(BakeoffPrompt::isComplex)
            .count();
    }

    @Test
    void testTrainingAndEvaluationSetsAreDisjoint() {
        Set<String> shared = idsOf(EXEMPLARS);

        shared.retainAll(idsOf(ADVERSARIAL));

        assertThat(shared).isEmpty();
    }

    @Test
    void testIdsAreUnique() {
        assertThat(idsOf(EXEMPLARS)).hasSameSizeAs(EXEMPLARS);
        assertThat(idsOf(ADVERSARIAL)).hasSameSizeAs(ADVERSARIAL);
    }

    @Test
    void testExemplarSetIsBalanced() {
        assertThat(EXEMPLARS).hasSizeGreaterThanOrEqualTo(36);

        long complex = complexCount(EXEMPLARS);

        assertThat(complex).isBetween(EXEMPLARS.size() * 2L / 5, EXEMPLARS.size() * 3L / 5);
    }

    @Test
    void testAdversarialSetIsBalanced() {
        assertThat(ADVERSARIAL).hasSizeGreaterThanOrEqualTo(55);

        long complex = complexCount(ADVERSARIAL);

        assertThat(complex).isBetween(ADVERSARIAL.size() * 2L / 5, ADVERSARIAL.size() * 3L / 5);
    }

    @Test
    void testEveryAdversarialBucketIsPopulated() {
        Set<String> buckets = ADVERSARIAL.stream()
            .map(BakeoffPrompt::bucket)
            .collect(Collectors.toSet());

        assertThat(buckets).contains(
            "short-but-hard", "long-but-trivial", "code-shaped-but-simple", "prose-but-complex",
            "multi-tool-but-trivial", "non-english", "degenerate");
    }

    @Test
    void testEveryEntryCarriesALabelAndARationale() {
        for (BakeoffPrompt prompt : ADVERSARIAL) {
            assertThat(prompt.label()).isIn(BakeoffPrompt.SIMPLE, BakeoffPrompt.COMPLEX);
            assertThat(prompt.rationale()).isNotBlank();
        }
    }
}

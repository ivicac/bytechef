/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.routing;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the single {@link PromptComplexityScorer} bean the gateway facade injects. The facade takes one constructor
 * parameter of this type, not a list, so at most one implementation may be on the context at a time; two {@code @Bean}
 * methods gated on complementary values of the same selector property, rather than two {@code @Component}-annotated
 * classes, is what keeps that true; {@code @ConditionalOnProperty} is not repeatable, so the pre-existing
 * {@code enabled} gate and the new selector gate cannot both sit on one class.
 *
 * <p>
 * {@code deterministic} is the default ({@code matchIfMissing = true}): a fresh install and every existing deployment
 * must keep routing exactly as before. The baseline sends the large majority of adversarial prompts to the cheapest
 * tier, where {@code opennlp} spreads across every tier including the most expensive one — flipping the default would
 * silently raise a customer's model spend, which is a change an operator opts into, not one they discover on a bill.
 * See the bake-off's outcome section in
 * {@code docs/superpowers/specs/2026-08-24-prompt-complexity-scorer-bakeoff-design.md} for the measurements behind that
 * choice.
 *
 * @version ee
 */
@Configuration
@ConditionalOnEEVersion
@ConditionalOnProperty(prefix = "bytechef.ai.gateway", name = "enabled", havingValue = "true")
public class PromptComplexityScorerConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "bytechef.ai.gateway", name = "prompt-complexity-scorer", havingValue = "deterministic",
        matchIfMissing = true)
    PromptComplexityScorer deterministicPromptComplexityScorer() {
        return new DeterministicPromptComplexityScorer();
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "bytechef.ai.gateway", name = "prompt-complexity-scorer", havingValue = "opennlp")
    PromptComplexityScorer openNlpPromptComplexityScorer() {
        return new OpenNlpPromptComplexityScorer();
    }
}

/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.gateway.routing;

import com.bytechef.ee.platform.ai.gateway.dto.AiGatewayChatCompletionRequest;

/**
 * Produces a 0.0 (simple) to 1.0 (complex) complexity score for a chat completion request, used by the intelligent
 * routing strategies to map a request onto a model cost tier. Exactly one implementation is on the context at a time,
 * chosen by {@code PromptComplexityScorerConfiguration} in {@code platform-ai-gateway-service}: a deterministic,
 * model-free scorer (the default) or an OpenNLP-trained one, selected by
 * {@code bytechef.ai.gateway.prompt-complexity-scorer}. See the bake-off recorded in
 * {@code docs/superpowers/specs/2026-08-24-prompt-complexity-scorer-bakeoff-design.md} for how the OpenNLP scorer was
 * chosen over embedding-based alternatives.
 *
 * @version ee
 */
public interface PromptComplexityScorer {

    double score(AiGatewayChatCompletionRequest request);
}

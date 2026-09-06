/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.ai.sensitivedata;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One workspace-defined detection pattern, compiled and ready to evaluate.
 *
 * <p>
 * The runtime shape of a custom rule, deliberately separate from however a rule is stored. Storage is per-workspace
 * policy and therefore EE; matching is a mechanism and therefore here, which is what lets the knowledge base reuse it
 * later without either depending on the other.
 * </p>
 *
 * <p>
 * <b>A pattern reaching this type has already been validated.</b> {@code CustomPatternValidator} is the gate, and it
 * runs at save time rather than here: rejecting a pattern on the request path would mean discovering at detection time
 * that a workspace's rule is unusable, which is both too late and reported to the wrong person.
 * </p>
 *
 * @param type        the span category, and the name that appears in tokens, metrics and violation records. Must
 *                    satisfy {@code PiiToken}'s grammar, which the validator enforces.
 * @param pattern     the compiled regex
 * @param kind        {@code PII} is reversible (tokenized); {@code SECRET} is never restored
 * @param score       base confidence, on the same rubric as the built-in catalog so one threshold governs both
 * @param contextRule optional promotion when a naming keyword sits near a match, sharing the built-in catalog's rule
 *                    rather than a parallel one
 *
 * @author Ivica Cardic
 */
public record CustomPattern(
    String type, Pattern pattern, SensitiveKind kind, double score,
    PiiPatternCatalog.@Nullable ContextRule contextRule) {

    public CustomPattern {
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0, got: " + score);
        }

        if (contextRule != null && contextRule.score() <= score) {
            throw new IllegalArgumentException(
                "a context rule must RAISE confidence: " + type + " scores " + score + " and its rule offers " +
                    contextRule.score());
        }
    }
}

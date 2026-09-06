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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Evaluates a workspace's own detection patterns.
 *
 * <p>
 * <b>Not a {@link SensitiveDataDetector}, and that is the design rather than an omission.</b> A registered detector is
 * handed text and a deadline and nothing else — {@code SensitiveDataRedactor#detectCandidates} takes no workspace — so
 * a detector bean could never see per-workspace rules. The thing that knows the workspace sits upstream, in
 * {@code AiGuardrails}, which resolves the rules with the rest of a workspace's policy and calls this. The spans then
 * join the redactor's own candidates <em>before</em> resolution, so an overlap between a custom rule and a built-in
 * pattern is settled by the one span-ordering rule that already exists rather than by which list it came from.
 * </p>
 *
 * <p>
 * Stateless and thread-safe: one call, one result, no fields.
 * </p>
 *
 * @author Ivica Cardic
 */
public final class CustomPatternEvaluator {

    private CustomPatternEvaluator() {
    }

    /**
     * Returns every span the given patterns find in {@code text}.
     *
     * <p>
     * Matching runs against {@code deadline.bound(text)}, so a rule that turns out to backtrack pathologically is
     * interrupted rather than left running on the request thread. That is not belt-and-braces: an operator-supplied
     * pattern is the case the bound was built for, and save-time validation cannot be the only defence because it can
     * only reject what it thought to test.
     * </p>
     *
     * @param text     the text to scan
     * @param patterns the workspace's enabled patterns; an empty list yields no spans and does no work
     * @param deadline the budget for this evaluation
     * @return the spans found, empty when none
     * @throws DetectionTimeoutException when a match ran past {@code deadline}
     */
    public static List<SensitiveSpan> detect(String text, List<CustomPattern> patterns, MatchDeadline deadline) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return List.of();
        }

        List<SensitiveSpan> spans = new ArrayList<>();

        for (CustomPattern customPattern : patterns) {
            Matcher matcher = customPattern.pattern()
                .matcher(deadline.bound(text));

            while (matcher.find()) {
                spans.add(
                    new SensitiveSpan(
                        customPattern.kind(), customPattern.type(), matcher.start(), matcher.end(),
                        PiiPatternCatalog.promotedScore(
                            customPattern.contextRule(), customPattern.score(), text, matcher.start(),
                            matcher.end())));
            }
        }

        return spans;
    }
}

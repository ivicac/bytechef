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

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

/**
 * Regex PII detection over {@link PiiPatternCatalog#curatedDefault()} — the always-on platform default, which is the
 * full catalog minus the contextual types. See the catalog's javadoc for why the two differ, and for why this catalog
 * is Presidio-<em>named</em> rather than Presidio-derived.
 *
 * <p>
 * Spans are emitted exactly as each pattern finds them, including any that overlap one another or spans from other
 * detectors — this detector performs no overlap resolution. {@code SensitiveDataRedactor.resolve} owns that
 * responsibility and applies a single total order across every detector's output.
 * </p>
 *
 * <p>
 * Not to be confused with the EE engine's older, unrelated {@code RegexPiiDetector} (5 patterns:
 * {@code EMAIL}/{@code SSN}/{@code CC}/{@code PHONE}/{@code IP}), which this consolidation deleted before this class
 * took the name. See {@code .agents/ai-guardrails.md}'s "Sensitive-data detectors" section for that history, and for
 * why the catalog's resemblance to Presidio's taxonomy is a naming resemblance only.
 * </p>
 * <p>
 * Contributed as a Spring bean under the same {@link ConditionalOnEEVersion} gate the deleted 5-pattern EE detector
 * carried, so the Spring-managed path {@link SensitiveDataDetectors}'s javadoc describes keeps collecting exactly the
 * two detectors {@link SensitiveDataDetectors#builtIn} returns.
 * </p>
 * <p>
 * A pattern may also carry a {@link PiiPatternCatalog.PiiPattern#validator()}, checked against the matched text after
 * the regex matches and before a span is emitted — {@code CREDIT_CARD}'s Luhn check is the only one today. This
 * detector applies whatever validator a pattern carries generically; it has no per-type branch, so a future checksum
 * (national-identifier check digits are an explicit non-goal here, deferred to their own project) is another pattern
 * supplying another validator, not a new {@code if} in this method.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class RegexPiiDetector implements SensitiveDataDetector {

    private final List<PiiPatternCatalog.PiiPattern> patterns = PiiPatternCatalog.curatedDefault();

    @Override
    public String name() {
        return "regex-pii";
    }

    @Override
    public List<SensitiveSpan> detect(String text) {
        return detect(text, MatchDeadline.unbounded());
    }

    @Override
    public List<SensitiveSpan> detect(String text, MatchDeadline deadline) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<SensitiveSpan> spans = new ArrayList<>();
        // One bounded view per pattern, not one for the whole loop: the view counts its own reads, and sharing it
        // would let a cheap pattern's reads spend a later pattern's budget.
        for (PiiPatternCatalog.PiiPattern piiPattern : patterns) {
            Matcher matcher = piiPattern.pattern()
                .matcher(deadline.bound(text));
            Predicate<String> validator = piiPattern.validator();

            while (matcher.find()) {
                if (validator != null && !validator.test(matcher.group())) {
                    continue;
                }

                spans.add(
                    new SensitiveSpan(
                        SensitiveKind.PII, piiPattern.type(), matcher.start(), matcher.end(),
                        confidenceOf(piiPattern, text, matcher.start(), matcher.end())));
            }
        }

        return spans;
    }

    /**
     * Returns the match's confidence: the pattern's base score, or its context rule's promoted score when a naming
     * keyword sits within the rule's window of the match.
     *
     * <p>
     * The window is measured from the match's own boundaries and clamped to the text, so a match at either end of the
     * input still gets whatever context exists on the side that has any.
     * </p>
     */
    private static double confidenceOf(
        PiiPatternCatalog.PiiPattern piiPattern, String text, int matchStart, int matchEnd) {

        PiiPatternCatalog.ContextRule contextRule = piiPattern.contextRule();

        if (contextRule == null) {
            return piiPattern.score();
        }

        int window = contextRule.window();
        String context = text.substring(
            Math.max(0, matchStart - window), Math.min(text.length(), matchEnd + window))
            .toLowerCase(Locale.ROOT);

        for (String keyword : contextRule.keywords()) {
            if (context.contains(keyword)) {
                return contextRule.score();
            }
        }

        return piiPattern.score();
    }
}

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
import java.util.regex.Matcher;
import org.springframework.stereotype.Component;

/**
 * Locates high-signal developer-secret shapes over {@link SecretPatternCatalog#ALL} — PEM private-key blocks, cloud and
 * provider API keys, and JSON web tokens. Every span is reported under the single category {@code SECRET}, so they all
 * redact to {@code [REDACTED_SECRET]} — naming the provider in the placeholder would itself disclose which service a
 * leaked credential belonged to.
 *
 * <p>
 * Excludes {@link #STRIPE_PUBLISHABLE_KEY_TYPE}: a Stripe publishable key is meant to be public (embedded in
 * client-side code), so it is not a secret and must not be redacted. See {@link SecretPatternCatalog}'s javadoc for why
 * that entry is split from {@code STRIPE_SECRET_KEY} rather than filtered out of a single unioned pattern.
 * </p>
 *
 * <p>
 * Entropy and random-string detection deliberately live elsewhere (the workflow layer's {@code SecretKeyDetectorUtils})
 * for callers who want them.
 * </p>
 *
 * <p>
 * Regex matching is local, so this detector is stream-safe.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class RegexSecretDetector implements SensitiveDataDetector {

    private static final String CATEGORY = "SECRET";

    /**
     * Publishable Stripe keys are public by design (see the class javadoc) -- excluded so this detector never redacts
     * one.
     */
    private static final String STRIPE_PUBLISHABLE_KEY_TYPE = "STRIPE_PUBLISHABLE_KEY";

    private final List<SecretPatternCatalog.SecretPattern> patterns = SecretPatternCatalog.ALL.stream()
        .filter(secretPattern -> !secretPattern.type()
            .equals(STRIPE_PUBLISHABLE_KEY_TYPE))
        .toList();

    @Override
    public String name() {
        return "regex-secret";
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

        for (SecretPatternCatalog.SecretPattern secretPattern : patterns) {
            Matcher matcher = secretPattern.pattern()
                .matcher(deadline.bound(text));

            while (matcher.find()) {
                spans.add(
                    new SensitiveSpan(
                        SensitiveKind.SECRET, CATEGORY, matcher.start(), matcher.end(), secretPattern.score()));
            }
        }

        return spans;
    }
}

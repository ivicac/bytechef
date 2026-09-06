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
 * Regex PII detection over {@link PiiPatternCatalog#curatedDefault()} — the always-on platform default, which is the
 * full Presidio catalog minus the contextual types. See the catalog's javadoc for why the two differ.
 *
 * <p>
 * Spans are emitted exactly as each pattern finds them, including any that overlap one another or spans from other
 * detectors — this detector performs no overlap resolution. {@code SensitiveDataRedactor.resolve} owns that
 * responsibility and applies a single total order across every detector's output.
 * </p>
 *
 * <p>
 * Contributed as a Spring bean under the same {@link ConditionalOnEEVersion} gate the replaced {@code RegexPiiDetector}
 * carried, so the Spring-managed path {@link SensitiveDataDetectors}'s javadoc describes keeps collecting exactly the
 * two detectors {@link SensitiveDataDetectors#builtIn} returns.
 * </p>
 *
 * @author Ivica Cardic
 */
@Component
@ConditionalOnEEVersion
public class PresidioRegexPiiDetector implements SensitiveDataDetector {

    private final List<PiiPatternCatalog.PiiPattern> patterns = PiiPatternCatalog.curatedDefault();

    @Override
    public String name() {
        return "presidio-regex-pii";
    }

    @Override
    public List<SensitiveSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<SensitiveSpan> spans = new ArrayList<>();

        for (PiiPatternCatalog.PiiPattern piiPattern : patterns) {
            Matcher matcher = piiPattern.pattern()
                .matcher(text);

            while (matcher.find()) {
                spans.add(SensitiveSpan.of(SensitiveKind.PII, piiPattern.type(), matcher.start(), matcher.end()));
            }
        }

        return spans;
    }
}

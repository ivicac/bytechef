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

package com.bytechef.component.ai.agent.guardrails.util;

import com.bytechef.component.definition.ComponentDsl;
import com.bytechef.component.definition.Option;
import com.bytechef.platform.ai.sensitivedata.PiiPatternCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for detecting and masking personally identifiable information (PII).
 *
 * @author Ivica Cardic
 */
public final class PiiDetectorUtils {

    /**
     * The patterns this component matches against, derived from the shared platform catalog so the patterns exist
     * exactly once ({@link PiiPatternCatalog#ALL}). This component keeps its own {@link PiiPattern} record — its
     * callers reference it by that type — but the pattern data itself, including each entry's optional
     * {@link PiiPatternCatalog.PiiPattern#validator() validator}, is no longer duplicated here.
     *
     * <p>
     * Carrying the validator through matters more on this path than on the platform one: the platform detector's
     * matches are still filtered by {@code SensitiveDataRedactor}'s confidence threshold afterward, but this
     * component's per-node picker has no threshold at all — a pattern's own precision (and any validator it supplies)
     * is the only defence against, e.g., a bare 16-digit order number being masked as {@code CREDIT_CARD}.
     * </p>
     */
    public static final List<PiiPattern> DEFAULT_PII_PATTERNS = PiiPatternCatalog.ALL.stream()
        .map(catalogPattern -> new PiiPattern(
            catalogPattern.type(), catalogPattern.pattern(), catalogPattern.validator()))
        .toList();

    /**
     * Returns the per-node PII detection picker options: one option per entry in {@link #DEFAULT_PII_PATTERNS}, pairing
     * each pattern's type with a human-readable label.
     *
     * <p>
     * The set of option values here must always equal the set of {@link PiiPattern#type()} values in
     * {@link #DEFAULT_PII_PATTERNS} — an entry added to the catalog with no matching option here would be silently
     * unselectable in the picker, and a stale option here would silently offer a type that detects nothing. This method
     * is hand-maintained (not derived from {@link #DEFAULT_PII_PATTERNS}) because its order also fixes the generated
     * definition JSON for the {@code pii} and {@code llm-pii} components, so it cannot simply follow the catalog's
     * grouping order. {@code PiiDetectorUtilsTest} pins the invariant instead, in
     * {@code testPickerOptionsMatchCatalogTypesExactly}.
     * </p>
     *
     * @return the picker options for PII detection type selection
     */
    public static List<Option<String>> getPiiDetectionOptions() {
        return List.of(
            ComponentDsl.option("Email address", "EMAIL_ADDRESS"),
            ComponentDsl.option("Phone number", "PHONE_NUMBER"),
            ComponentDsl.option("Credit card number", "CREDIT_CARD"),
            ComponentDsl.option("IP address (IPv4)", "IP_ADDRESS"),
            ComponentDsl.option("IBAN code", "IBAN_CODE"),
            ComponentDsl.option("Bitcoin/crypto address", "CRYPTO"),
            ComponentDsl.option("Date or date-time (ISO-8601)", "DATE_TIME"),
            ComponentDsl.option("Street address / location", "LOCATION"),
            ComponentDsl.option("Medical license", "MEDICAL_LICENSE"),
            ComponentDsl.option("US Social Security Number", "US_SSN"),
            ComponentDsl.option("US bank account number", "US_BANK_NUMBER"),
            ComponentDsl.option("US driver license", "US_DRIVER_LICENSE"),
            ComponentDsl.option("US Individual Taxpayer Identification Number (ITIN)", "US_ITIN"),
            ComponentDsl.option("US passport number", "US_PASSPORT"),
            ComponentDsl.option("UK NHS number", "UK_NHS"),
            ComponentDsl.option("UK National Insurance Number", "UK_NINO"),
            ComponentDsl.option("Spanish NIF", "ES_NIF"),
            ComponentDsl.option("Spanish NIE", "ES_NIE"),
            ComponentDsl.option("Italian fiscal code (Codice Fiscale)", "IT_FISCAL_CODE"),
            ComponentDsl.option("Italian VAT code (Partita IVA)", "IT_VAT_CODE"),
            ComponentDsl.option("Italian driver license", "IT_DRIVER_LICENSE"),
            ComponentDsl.option("Italian passport", "IT_PASSPORT"),
            ComponentDsl.option("Italian identity card", "IT_IDENTITY_CARD"),
            ComponentDsl.option("Polish PESEL", "PL_PESEL"),
            ComponentDsl.option("Singapore NRIC/FIN", "SG_NRIC_FIN"),
            ComponentDsl.option("Singapore UEN (Unique Entity Number)", "SG_UEN"),
            ComponentDsl.option("Australian Business Number (ABN)", "AU_ABN"),
            ComponentDsl.option("Australian Company Number (ACN)", "AU_ACN"),
            ComponentDsl.option("Australian Tax File Number (TFN)", "AU_TFN"),
            ComponentDsl.option("Australian Medicare card number", "AU_MEDICARE"),
            ComponentDsl.option("Indian Aadhaar number", "IN_AADHAAR"),
            ComponentDsl.option("Indian Permanent Account Number (PAN)", "IN_PAN"),
            ComponentDsl.option("Indian passport number", "IN_PASSPORT"),
            ComponentDsl.option("Indian vehicle registration", "IN_VEHICLE_REGISTRATION"),
            ComponentDsl.option("Indian voter ID (EPIC)", "IN_VOTER"),
            ComponentDsl.option("Finnish Personal Identity Code (HETU)", "FI_PERSONAL_IDENTITY_CODE"));
    }

    private PiiDetectorUtils() {
    }

    public static List<PiiPattern> filterByTypes(List<String> selectedTypes) {
        if (selectedTypes == null || selectedTypes.isEmpty()) {
            return Collections.emptyList();
        }

        return DEFAULT_PII_PATTERNS.stream()
            .filter(piiPattern -> selectedTypes.contains(piiPattern.type()))
            .toList();
    }

    /**
     * Detect PII in the given content using the provided patterns.
     *
     * @param content  the content to scan
     * @param patterns the patterns to use for detection
     * @return a list of PII matches found
     */
    public static List<PiiMatch> detect(String content, List<PiiPattern> patterns) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }

        List<PiiMatch> matches = new ArrayList<>();
        List<RegexParserUtils.RegexExecutionLimitException> budgetFailures = new ArrayList<>();

        for (PiiPattern piiPattern : patterns) {
            try {
                CharSequence bounded = RegexParserUtils.bounded(content);
                Pattern pattern = piiPattern.pattern();
                Predicate<String> validator = piiPattern.validator();

                Matcher matcher = pattern.matcher(bounded);

                while (matcher.find()) {
                    if (validator != null && !validator.test(matcher.group())) {
                        continue;
                    }

                    matches.add(
                        new PiiMatch(matcher.group(), matcher.start(), matcher.end(), piiPattern.type()));
                }
            } catch (RegexParserUtils.RegexExecutionLimitException exception) {
                budgetFailures.add(
                    new RegexParserUtils.RegexExecutionLimitException(
                        "pattern '" + piiPattern.type() + "': " + exception.getMessage(), exception));
            }
        }

        if (!budgetFailures.isEmpty()) {
            RegexParserUtils.RegexExecutionLimitException headline = budgetFailures.getFirst();

            budgetFailures.stream()
                .skip(1)
                .forEach(headline::addSuppressed);

            throw headline;
        }

        return matches;
    }

    public static List<PiiMatch> detect(String content, List<PiiPattern> patterns, List<Pattern> extraRegexes) {
        List<PiiMatch> matches = new ArrayList<>(detect(content, patterns));

        if (extraRegexes == null || extraRegexes.isEmpty()) {
            return matches;
        }

        List<RegexParserUtils.RegexExecutionLimitException> budgetFailures = new ArrayList<>();

        int index = 0;

        for (Pattern pattern : extraRegexes) {
            try {
                CharSequence bounded = RegexParserUtils.bounded(content);
                Matcher matcher = pattern.matcher(bounded);

                while (matcher.find()) {
                    matches.add(new PiiMatch(matcher.group(), matcher.start(), matcher.end(), "CUSTOM"));
                }
            } catch (RegexParserUtils.RegexExecutionLimitException exception) {
                budgetFailures.add(new RegexParserUtils.RegexExecutionLimitException(
                    "extraRegex[" + index + "] '" + pattern.pattern() + "': " + exception.getMessage(), exception));
            }

            index++;
        }

        if (!budgetFailures.isEmpty()) {
            RegexParserUtils.RegexExecutionLimitException headline = budgetFailures.getFirst();

            budgetFailures.stream()
                .skip(1)
                .forEach(headline::addSuppressed);

            throw headline;
        }

        return matches;
    }

    /**
     * Mask PII in the given content by replacing matches with mask tokens.
     *
     * <p>
     * Overlapping matches are deduplicated with a longest-wins policy before masking: when two patterns match the same
     * span (e.g. {@code [A-Z]\d{7}} fires for both US_DRIVER_LICENSE and IN_PASSPORT — byte-identical regexes — or
     * {@code \b\d{9}\b} overlaps AU_TFN and, as one of the digit lengths {@code \b\d{8,17}\b} spans, US_BANK_NUMBER),
     * only the longest span is kept. Without this, the first {@code builder.replace(start,end,…)} mutates the buffer so
     * the second replace writes into the middle of the first mask token and corrupts the output.
     *
     * <p>
     * {@code US_SSN} no longer belongs in either example: it used to carry a bare-{@code \b\d{9}\b} alternative that
     * overlapped {@code AU_TFN} identically, but that alternative was dropped (see {@code PiiPatternCatalog}'s class
     * javadoc) — {@code US_SSN} now matches only the dashed form, so it can no longer overlap a bare 9-digit run at
     * all.
     * </p>
     *
     * @param content the content to mask
     * @param matches the PII matches to mask
     * @return the masked content
     */
    public static String mask(String content, List<PiiMatch> matches) {
        if (content == null || content.isEmpty() || matches.isEmpty()) {
            return content;
        }

        List<PiiMatch> deduplicated = deduplicateOverlaps(matches);

        StringBuilder result = new StringBuilder(content);
        int lastStart = Integer.MAX_VALUE;

        for (PiiMatch match : deduplicated) {
            if (match.end() > lastStart) {
                throw new IllegalStateException(
                    "out-of-order match: [" + match.start() + "," + match.end() + ") overlaps " + lastStart);
            }

            result.replace(match.start(), match.end(), "<" + match.type() + ">");

            lastStart = match.start();
        }

        return result.toString();
    }

    private static List<PiiMatch> deduplicateOverlaps(List<PiiMatch> matches) {
        List<PiiMatch> byLength = new ArrayList<>(matches);

        byLength.sort(Comparator.<PiiMatch>comparingInt(match -> match.end() - match.start())
            .reversed()
            .thenComparingInt(PiiMatch::start));

        List<PiiMatch> kept = new ArrayList<>();

        for (PiiMatch candidate : byLength) {
            boolean overlaps = false;

            for (PiiMatch existing : kept) {
                if (candidate.start() < existing.end() && candidate.end() > existing.start()) {
                    overlaps = true;

                    break;
                }
            }

            if (!overlaps) {
                kept.add(candidate);
            }
        }

        kept.sort(Comparator.comparingInt(PiiMatch::start)
            .reversed());

        return kept;
    }

    /**
     * A PII pattern definition.
     *
     * <p>
     * {@code validator}, when present, is applied to each regex match's matched text — after the pattern matches and
     * before a {@link PiiMatch} is emitted — exactly mirroring how {@code RegexPiiDetector} applies
     * {@link PiiPatternCatalog.PiiPattern#validator()} on the platform path: generically, in {@link #detect}, with no
     * per-type branch. {@code CREDIT_CARD}'s Luhn check is the only validator any catalog entry supplies today.
     * </p>
     *
     * @param type      the type of PII (e.g., EMAIL_ADDRESS, PHONE_NUMBER)
     * @param pattern   the regex pattern to detect this PII type
     * @param validator an optional second gate over the matched text, applied after the regex matches and before a
     *                  match is emitted, or {@code null} when the regex match alone is the whole story
     */
    public record PiiPattern(String type, Pattern pattern, Predicate<String> validator) {

        // Convenience overload for the common case -- every entry but CREDIT_CARD -- mirrors
        // PiiPatternCatalog.PiiPattern's identical convenience overload.
        public PiiPattern(String type, Pattern pattern) {
            this(type, pattern, null);
        }
    }

    /**
     * A PII match found in content.
     *
     * @param value the matched value
     * @param start the start position in the content
     * @param end   the end position in the content
     * @param type  the type of PII detected
     */
    public record PiiMatch(String value, int start, int end, String type) {

        @Override
        public String toString() {
            return "PiiMatch{type=" + type + ", span=[" + start + ".." + end + "]}";
        }
    }
}

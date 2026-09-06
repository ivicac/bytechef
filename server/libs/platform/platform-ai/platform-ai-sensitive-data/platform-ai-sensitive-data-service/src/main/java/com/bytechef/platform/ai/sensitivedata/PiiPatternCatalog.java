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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single source of the Presidio-taxonomy PII patterns — 36 types, copied verbatim from what was the guardrails
 * component's own list, which is redirected here so the catalog exists exactly once.
 *
 * <p>
 * The catalog is a <b>menu</b>, not a default. {@link #ALL} is what per-node pickers select from;
 * {@link #curatedDefault()} is what always-on platform policy applies, and it excludes the {@link #CONTEXTUAL_TYPES} —
 * {@code DATE_TIME} and {@code LOCATION} — because a date or an address identifies nobody by itself, and masking every
 * one of them destroys the model's ability to reason about schedules and places. It also excludes the
 * {@link #LOW_SPECIFICITY_TYPES} for the same reason applied to a different failure mode: {@code US_BANK_NUMBER}'s
 * pattern, {@code \b\d{8,17}\b}, matches essentially any long number, so left in the default it tokenizes order
 * numbers, invoice numbers and ticket numbers alongside actual bank account numbers. Both excluded sets remain in
 * {@link #ALL} and stay reachable through the component's per-node picker.
 * </p>
 * <p>
 * <b>Open question, not yet resolved:</b> the criterion above — a pattern so unspecific that applying it
 * unconditionally degrades ordinary business text — has only been checked against {@code DATE_TIME}, {@code LOCATION}
 * and {@code US_BANK_NUMBER}. It has not been re-applied to the other bare-digit-run types still in
 * {@link #curatedDefault()}: {@code US_SSN}, {@code AU_TFN}, {@code PL_PESEL}, {@code MEDICAL_LICENSE}, {@code UK_NHS},
 * {@code AU_ACN}, {@code AU_MEDICARE} and {@code IN_AADHAAR}. A concrete collision: the input {@code "987654321"} (e.g.
 * a support-ticket number) matches both {@code US_SSN} (<code>\b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b</code>) and
 * {@code AU_TFN} (<code>\b\d{9}\b</code>) at the same span, and {@link SensitiveDataRedactor}'s tie-break — category
 * ascending — picks {@code AU_TFN}, so a plain business identifier is redacted and labelled an Australian Tax File
 * Number. Whether any of these eight types should move to {@link #LOW_SPECIFICITY_TYPES} is a product and compliance
 * decision (which national identifiers a privacy control stops detecting by default) left for the spec owner; see
 * {@code docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md} §5a and
 * {@code .agents/ai-guardrails.md}.
 * </p>
 *
 * @author Ivica Cardic
 */
// REDOS is suppressed because every pattern here uses only fixed {N,M}/{N} or possessive quantifiers; no
// catastrophic backtracking is possible. Field-level suppression is ignored due to how findsecbugs attributes
// Pattern.compile in the static initializer, so class-level is required. Copied from the source of these patterns,
// PiiDetectorUtils, which carries the identical suppression for the identical reason.
@SuppressFBWarnings("REDOS")
public final class PiiPatternCatalog {

    public static final Set<String> CONTEXTUAL_TYPES = Set.of("DATE_TIME", "LOCATION");

    /**
     * Types whose pattern is so unspecific that, applied unconditionally, it matches ordinary business text rather than
     * the entity it names. {@code US_BANK_NUMBER}'s {@code \b\d{8,17}\b} matches essentially any long digit run — order
     * numbers, invoice numbers, ticket numbers — so it is excluded from {@link #curatedDefault()} for the same reason
     * {@link #CONTEXTUAL_TYPES} is. See the class javadoc's "Open question" note: this same criterion has not yet been
     * re-applied to the other bare-digit-run types still in the default.
     */
    public static final Set<String> LOW_SPECIFICITY_TYPES = Set.of("US_BANK_NUMBER");

    public static final List<PiiPattern> ALL = List.of(
        // Global
        new PiiPattern(
            "EMAIL_ADDRESS",
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")),
        new PiiPattern(
            "PHONE_NUMBER",
            Pattern.compile("\\b[+]?[(]?[0-9]{3}[)]?[-\\s.]?[0-9]{3}[-\\s.]?[0-9]{4,6}\\b")),
        new PiiPattern(
            "CREDIT_CARD",
            Pattern.compile("\\b\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}\\b")),
        // No octet-range validation (accepts e.g. 999.999.999.999) and no IPv6 pattern -- copied verbatim from the
        // guardrails component's own pre-consolidation catalog, so neither gap is new there, but both are new to this
        // platform detector, which previously used the EE engine's octet-validated regex
        // ((?:25[0-5]|2[0-4]\d|1?\d?\d)). See docs/content/docs/platform/automation/deploy/ai-gateway.md, which says
        // "IPv4 addresses" precisely because IPv6 is not covered.
        new PiiPattern(
            "IP_ADDRESS",
            Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")),
        new PiiPattern(
            "IBAN_CODE",
            Pattern.compile("\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}([A-Z0-9]?){0,16}\\b")),
        new PiiPattern(
            "CRYPTO",
            Pattern.compile("\\b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\\b")),
        new PiiPattern(
            "DATE_TIME",
            Pattern.compile(
                "\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b")),
        new PiiPattern(
            "LOCATION",
            Pattern.compile(
                "\\b(?:[A-Za-z]++\\s+)++(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln"
                    + "|Place|Pl|Court|Ct|Way|Highway|Hwy)\\b")),
        new PiiPattern(
            "MEDICAL_LICENSE",
            Pattern.compile("\\b[A-Z]{2}\\d{6}\\b")),
        // USA
        new PiiPattern(
            "US_BANK_NUMBER",
            Pattern.compile("\\b\\d{8,17}\\b")),
        new PiiPattern(
            "US_DRIVER_LICENSE",
            Pattern.compile("\\b[A-Z]\\d{7}\\b")),
        new PiiPattern(
            "US_ITIN",
            Pattern.compile("\\b9\\d{2}-\\d{2}-\\d{4}\\b")),
        new PiiPattern(
            "US_PASSPORT",
            Pattern.compile("\\b[A-Z]\\d{8}\\b")),
        new PiiPattern(
            "US_SSN",
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b|\\b\\d{9}\\b")),
        // UK
        new PiiPattern(
            "UK_NHS",
            Pattern.compile("\\b\\d{3} \\d{3} \\d{4}\\b")),
        new PiiPattern(
            "UK_NINO",
            Pattern.compile("\\b[A-Z]{2}\\d{6}[A-Z]\\b")),
        // Spain
        new PiiPattern(
            "ES_NIF",
            Pattern.compile("\\b[A-Z]\\d{8}\\b")),
        new PiiPattern(
            "ES_NIE",
            Pattern.compile("\\b[A-Z]\\d{8}\\b")),
        // Italy
        new PiiPattern(
            "IT_FISCAL_CODE",
            Pattern.compile("\\b[A-Z]{6}\\d{2}[A-Z]\\d{2}[A-Z]\\d{3}[A-Z]\\b")),
        new PiiPattern(
            "IT_DRIVER_LICENSE",
            Pattern.compile("\\b[A-Z]{2}\\d{7}\\b")),
        new PiiPattern(
            "IT_VAT_CODE",
            Pattern.compile("\\bIT\\d{11}\\b")),
        new PiiPattern(
            "IT_PASSPORT",
            Pattern.compile("\\b[A-Z]{2}\\d{7}\\b")),
        new PiiPattern(
            "IT_IDENTITY_CARD",
            Pattern.compile("\\b[A-Z]{2}\\d{7}\\b")),
        // Poland
        new PiiPattern(
            "PL_PESEL",
            Pattern.compile("\\b\\d{11}\\b")),
        // Singapore
        new PiiPattern(
            "SG_NRIC_FIN",
            Pattern.compile("\\b[A-Z]\\d{7}[A-Z]\\b")),
        new PiiPattern(
            "SG_UEN",
            Pattern.compile("\\b\\d{8}[A-Z]\\b|\\b\\d{9}[A-Z]\\b")),
        // Australia
        new PiiPattern(
            "AU_ABN",
            Pattern.compile("\\b\\d{2} \\d{3} \\d{3} \\d{3}\\b")),
        new PiiPattern(
            "AU_ACN",
            Pattern.compile("\\b\\d{3} \\d{3} \\d{3}\\b")),
        new PiiPattern(
            "AU_TFN",
            Pattern.compile("\\b\\d{9}\\b")),
        new PiiPattern(
            "AU_MEDICARE",
            Pattern.compile("\\b\\d{4} \\d{5} \\d{1}\\b")),
        // India
        new PiiPattern(
            "IN_PAN",
            Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b")),
        new PiiPattern(
            "IN_AADHAAR",
            Pattern.compile("\\b\\d{4} \\d{4} \\d{4}\\b")),
        new PiiPattern(
            "IN_VEHICLE_REGISTRATION",
            Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z]{2}\\d{4}\\b")),
        new PiiPattern(
            "IN_VOTER",
            Pattern.compile("\\b[A-Z]{3}\\d{7}\\b")),
        new PiiPattern(
            "IN_PASSPORT",
            Pattern.compile("\\b[A-Z]\\d{7}\\b")),
        // Finland
        new PiiPattern(
            "FI_PERSONAL_IDENTITY_CODE",
            Pattern.compile("\\b\\d{6}[+-A]\\d{3}[A-Z0-9]\\b")));

    private PiiPatternCatalog() {
    }

    /**
     * Returns the always-on default: the full catalog minus the contextual types and the low-specificity types.
     *
     * @return the curated default pattern list
     */
    public static List<PiiPattern> curatedDefault() {
        return ALL.stream()
            .filter(piiPattern -> !CONTEXTUAL_TYPES.contains(piiPattern.type()))
            .filter(piiPattern -> !LOW_SPECIFICITY_TYPES.contains(piiPattern.type()))
            .toList();
    }

    /**
     * Returns the patterns whose types appear in {@code selectedTypes}, preserving catalog order. Mirrors
     * {@code PiiDetectorUtils.filterByTypes}'s selection semantics exactly, including returning an empty list when
     * {@code selectedTypes} is {@code null} or empty.
     *
     * @param selectedTypes the type names a caller opted into
     * @return the matching patterns
     */
    public static List<PiiPattern> filterByTypes(List<String> selectedTypes) {
        if (selectedTypes == null || selectedTypes.isEmpty()) {
            return List.of();
        }

        return ALL.stream()
            .filter(piiPattern -> selectedTypes.contains(piiPattern.type()))
            .toList();
    }

    /**
     * One named PII pattern.
     *
     * @param type    the Presidio entity type, which doubles as the span category
     * @param pattern the recognising regex
     */
    public record PiiPattern(String type, Pattern pattern) {
    }
}

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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The single source of the PII patterns — 36 types, copied verbatim from what was the guardrails component's own list,
 * which is redirected here so the catalog exists exactly once.
 *
 * <p>
 * <b>The taxonomy is Presidio-<em>named</em>, not Presidio-derived.</b> ByteChef has no dependency on, and makes no
 * calls to, Microsoft Presidio anywhere in this codebase. The entity names below ({@code EMAIL_ADDRESS},
 * {@code US_SSN}, {@code UK_NINO}, ...) match Presidio's published entity vocabulary almost universally, which is where
 * the "Presidio taxonomy" framing that used to appear here came from — but that framing was never checked against
 * Presidio's actual recognizer regexes, only against its type names. A pattern-by-pattern comparison (see
 * {@code docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md}) found regex-level correspondence for a
 * minority of the 47 entries in this catalog plus {@link SecretPatternCatalog}: 21 of 47 (45%) match Presidio's
 * published regex for the same entity, 13 (28%) have a Presidio counterpart with different, usually tighter,
 * constraints, and 13 (28%) — all 11 secret patterns plus {@code PHONE_NUMBER} and {@code LOCATION} — have no Presidio
 * counterpart at all (Presidio does no secret-scanning, and detects those two PII types via NER rather than a published
 * regex). See {@code .agents/ai-guardrails.md}'s "Taxonomy and curated default" section for the full breakdown.
 * </p>
 * <p>
 * The catalog is a <b>menu</b>, not a default. {@link #ALL} is what per-node pickers select from;
 * {@link #curatedDefault()} is what always-on platform policy applies, and it excludes the {@link #CONTEXTUAL_TYPES} —
 * {@code DATE_TIME} and {@code LOCATION} — because a date or an address identifies nobody by itself, and masking every
 * one of them destroys the model's ability to reason about schedules and places. {@code CONTEXTUAL_TYPES} remains in
 * {@link #ALL} and stays reachable through the component's per-node picker.
 * </p>
 * <p>
 * A second failure mode — {@code US_BANK_NUMBER}'s pattern, {@code \b\d{8,17}\b}, matches essentially any long number,
 * so left in unconditionally it tokenized order numbers, invoice numbers and ticket numbers alongside actual bank
 * account numbers — used to be handled the same way, by excluding {@code US_BANK_NUMBER} from {@link #curatedDefault()}
 * by name. That exclusion set is gone: every pattern now carries a {@link PiiPattern#score()} reflecting how specific
 * its shape is, and {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} drops any span below that bar before
 * redaction. {@code US_BANK_NUMBER} scores low and is filtered by the threshold like every other bare-digit-run
 * pattern, so it is back in {@link #curatedDefault()} by name — it simply no longer wins at the default threshold.
 * Scoring, not set membership, is now the single mechanism deciding which matches survive.
 * </p>
 * <p>
 * {@code US_SSN} previously carried a second, bare-9-digit alternative (<code>\b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b</code>)
 * that collided with {@code AU_TFN}'s identical <code>\b\d{9}\b</code> shape on inputs like {@code "987654321"} — a
 * plain business identifier (e.g. a support-ticket number) that {@link SensitiveDataRedactor}'s tie-break then labelled
 * an Australian Tax File Number. Confidence scoring ({@link PiiPattern#score()}) assumes one pattern has one
 * specificity, which this entry violated: its strong dashed branch and its weak bare-digit branch cannot share a single
 * score. Rather than split the entry, the bare-digit alternative was dropped — {@code AU_TFN} already carries the
 * identical <code>\b\d{9}\b</code> pattern, so the same bare-digit text still <em>matches</em> (as {@code AU_TFN}) and
 * the mislabelling above no longer occurs. {@code US_SSN} now matches only the dashed form and scores {@code 0.6}, not
 * the {@code 0.2} its former bare-digit branch would have earned.
 * </p>
 * <p>
 * <b>That "still matches" is true only at the regex layer, and stating it without the next sentence is what an earlier
 * draft of this note got wrong.</b> {@code AU_TFN} scores {@code 0.2} (Low), below
 * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} (Group 2 of the final closing pass, 2026-08-31) — so
 * {@code SSN 123456789} is filtered out before resolution and reaches nobody's model call. Before this feature, every
 * span scored {@code 1.0} and nothing was filtered, so that exact input was redacted (mislabelled {@code AU_TFN}, but
 * redacted). Today it is redacted by <em>nothing</em>. That is an intended coverage reduction the confidence rubric
 * produces for a bare 9-digit run — the same tradeoff already accepted for {@code MEDICAL_LICENSE}, {@code PL_PESEL}
 * and {@code US_BANK_NUMBER} — not a coverage-preserving rename of the mislabelling fix. Do not describe this as
 * "detection is unchanged"; it is unchanged only at the layer before the threshold is applied.
 * </p>
 * <p>
 * <b>A final-review sweep found the "score by effective regex shape" rule applied inconsistently.</b> Two defects were
 * found by inspecting every entry's compiled regex against the three-band rubric, rather than trusting each entry's
 * previously-recorded score:
 * </p>
 * <ul>
 * <li>{@code FI_PERSONAL_IDENTITY_CODE}'s century-marker character class was written {@code [+-A]}, an unintended
 * <em>range</em> (every character from {@code +} to {@code A}, which includes every digit) rather than the intended
 * 3-character set {@code {+, -, A}}. The pattern was effectively {@code \b\d{10}[A-Z0-9]\b} — a bare 11-character digit
 * run — and redacted arbitrary business identifiers of that length. Fixed; see that entry's comment.</li>
 * <li>{@code CREDIT_CARD}'s three {@code [-\s]?} separators were optional, so the pattern degenerated to a bare
 * {@code \b\d{16}\b} run whenever none of them fired — the identical trap already fixed on {@code PHONE_NUMBER}. Made
 * mandatory, mirroring that fix. <b>Superseded 2026-08-31</b> by a Luhn checksum instead — see that entry's comment and
 * {@link PiiPattern}'s javadoc; the separators are optional again and a bare 16-digit run is safe to match
 * unconditionally once it must also pass the checksum.</li>
 * </ul>
 * <p>
 * The sweep also found the {@code MEDICAL_LICENSE} correction above had been applied to the one entry a failing
 * acceptance test happened to surface, and never carried across the rest of the catalog: {@code US_DRIVER_LICENSE},
 * {@code US_PASSPORT}, {@code IT_DRIVER_LICENSE}, {@code IT_PASSPORT}, {@code IT_IDENTITY_CARD}, {@code IN_VOTER}, and
 * {@code IN_PASSPORT} are all the identical shape class — an unrestricted-letter prefix concatenated directly with a
 * digit run, no delimiter, no checksum, no bookending suffix — and are rescored Low for the same reason
 * {@code MEDICAL_LICENSE} was. Patterns that add real structure beyond a plain prefix — a bookending letter
 * ({@code UK_NINO}, {@code SG_NRIC_FIN}), a mandatory trailing check-style letter after a fixed-length digit run
 * ({@code SG_UEN}), several independent letter/digit groups ({@code IN_VEHICLE_REGISTRATION}), or five constrained
 * letter positions ({@code IN_PAN}) — were considered and deliberately left at Medium; see each of those entries'
 * comments for the reasoning kept alongside them.
 * </p>
 * <p>
 * <b>{@code ES_NIF} and {@code ES_NIE} were not merely miscategorized by that sweep — they were wrong at the regex
 * layer, and identically wrong.</b> Both carried {@code \b[A-Z]\d{8}\b} (a letter, then 8 digits), byte-identical to
 * each other, which matches neither real Spanish format and could never be distinguished from one another at runtime.
 * The real formats are digits-first: {@code NIF} (Spanish nationals) is 8 digits plus a trailing check letter
 * ({@code 12345678Z}); {@code NIE} (foreign residents) is one of {@code X}/{@code Y}/{@code Z} plus 7 digits plus a
 * trailing check letter ({@code X1234567L}). Corrected to {@code \b\d{8}[A-Z]\b} and {@code \b[XYZ]\d{7}[A-Z]\b}
 * respectively — verified by executing both against sample values of each real format, confirming each now matches its
 * own format and neither matches the other's sample. Neither correction adds check-letter validation: both formats have
 * a computable mod-23 check letter, and the {@link PiiPattern#validator()} seam introduced for {@code CREDIT_CARD}'s
 * Luhn check could carry one, but national check digits beyond Luhn are an explicit non-goal here — see
 * {@link RegexPiiDetector}'s javadoc — and remain a separate, later project.
 * </p>
 * <p>
 * Both corrected shapes are rescored Medium ({@code 0.6}), not Low, because — unlike the seven-entry prefix-only group
 * above — each now coincides with a shape this catalog already scores Medium elsewhere, and this catalog's own rule is
 * that the same effective regex shape gets the same score. {@code ES_NIF}'s corrected {@code \b\d{8}[A-Z]\b} is
 * byte-identical to {@code SG_UEN}'s first alternative below — a fixed-length digit run with a mandatory trailing
 * check-style letter, the exact reasoning that keeps {@code SG_UEN} at Medium. {@code ES_NIE}'s corrected
 * {@code \b[XYZ]\d{7}[A-Z]\b} is a bookended letter-digits-letter shape like {@code UK_NINO}/{@code SG_NRIC_FIN} below
 * (Medium for the same reason), with an even narrower 3-character leading-letter class than either.
 * </p>
 * <p>
 * <b>The {@code ES_NIF} fix introduces an exact-length collision with {@code SG_UEN}, found and left in place rather
 * than resolved.</b> Because the two regexes are now byte-identical, any text matching both (e.g. {@code
 * "12345678A"}) produces two candidate spans with the same start and the same length, and
 * {@link SensitiveDataRedactor}'s resolution order breaks that tie on category ascending — {@code "ES_NIF"} sorts
 * before {@code "SG_UEN"}, so {@code ES_NIF} always wins it. {@code SG_UEN}'s 9-digit alternative
 * ({@code \b\d{9}[A-Z]\b}) is a different length and is unaffected; only its 8-digit alternative becomes unreachable at
 * that length. Separately, {@code ES_NIE}'s corrected pattern overlaps {@code SG_NRIC_FIN} whenever the matched text
 * happens to start with {@code X}, {@code Y} or {@code Z} — a subset of {@code SG_NRIC_FIN}'s unrestricted leading
 * letter — and the identical tie-break favors {@code ES_NIE} in that narrower case, leaving {@code SG_NRIC_FIN}
 * reachable for every other leading letter. Neither entry has been restructured to avoid either collision.
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
     * How far either side of a match a naming keyword may sit to promote it. Bounded on purpose: without a window, one
     * keyword anywhere in a long document would promote every match in it.
     */
    private static final int CONTEXT_WINDOW = 40;

    /**
     * What a promoted match scores. High rather than medium, deliberately: a naming keyword beside one of these shapes
     * is strong evidence, and a medium promotion would leave the type filtered at any workspace that raised its
     * threshold above the default -- so the most careful workspaces would be the ones still missing real identifiers.
     */
    private static final double CONTEXT_PROMOTED_SCORE = 0.9;

    public static final List<PiiPattern> ALL = List.of(
        // Global
        new PiiPattern(
            "EMAIL_ADDRESS",
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            0.9),
        // Both [-\s.] separators are mandatory, not optional -- with `?` on each (the Presidio-era shape), every
        // delimiter degenerates to empty and the pattern collapses to \b\d{10,12}\b, a bare digit run structurally
        // identical to the low-band patterns this feature exists to suppress (see PiiPatternCatalogTest's finding
        // for the exact reproduction: "invoice 4500123987" matched as PHONE_NUMBER). Requiring both separators keeps
        // every formatted shape (555-123-4567, 555 123 4567, 555.123.4567, (555) 123-4567) while dropping the bare
        // unformatted run -- which US_BANK_NUMBER (Low, \b\d{8,17}\b) still matches as a below-threshold candidate,
        // so it is suppressed rather than unseen.
        new PiiPattern(
            "PHONE_NUMBER",
            Pattern.compile("\\b[+]?[(]?[0-9]{3}[)]?[-\\s.][0-9]{3}[-\\s.][0-9]{4,6}\\b"),
            0.6),
        // Spec owner's decision (2026-08-31, reversing decision D10 for this one type only -- see the design
        // spec's decision table): the previous fix made all three [-\s] separators mandatory, closing the
        // degenerate-bare-\b\d{16}\b-run trap already fixed on PHONE_NUMBER above, but with the side effect
        // that an unformatted card number ("4532015112830366") no longer matched here at all -- it fell
        // through to US_BANK_NUMBER (Low) and was suppressed, a regression from before this feature. Rather
        // than accept that regression, the separators are optional again AND the match is now gated by a Luhn
        // checksum (PiiPattern#validator(), isLuhnValid() below) -- the checksum is exactly what makes the
        // bare 16-digit form safe to match unconditionally: a bare run that is not a valid card number (e.g.
        // "1234567890123456", an ordinary order number) fails Luhn and is not emitted as CREDIT_CARD at all,
        // while one that passes is strong evidence, not merely a plausible shape. Rescored High (0.9): a
        // Luhn-valid 16-digit run is no longer "a dashed group, the rubric's own example shape" (Medium) but a
        // checksum-verified match, the same category of evidence IT_FISCAL_CODE/IBAN_CODE/EMAIL_ADDRESS/CRYPTO
        // carry at High.
        new PiiPattern(
            "CREDIT_CARD",
            Pattern.compile("\\b\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}\\b"),
            0.9,
            PiiPatternCatalog::isLuhnValid),
        // No octet-range validation (accepts e.g. 999.999.999.999) and no IPv6 pattern -- copied verbatim from the
        // guardrails component's own pre-consolidation catalog, so neither gap is new there, but both are new to this
        // platform detector, which previously used the EE engine's octet-validated regex
        // ((?:25[0-5]|2[0-4]\d|1?\d?\d)). See docs/content/docs/platform/automation/deploy/ai-gateway.md, which says
        // "IPv4 addresses" precisely because IPv6 is not covered.
        new PiiPattern(
            "IP_ADDRESS",
            Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"),
            0.6),
        new PiiPattern(
            "IBAN_CODE",
            Pattern.compile("\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}([A-Z0-9]?){0,16}\\b"),
            0.9),
        new PiiPattern(
            "CRYPTO",
            Pattern.compile("\\b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\\b"),
            0.9),
        new PiiPattern(
            "DATE_TIME",
            Pattern.compile(
                "\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b"),
            0.6),
        new PiiPattern(
            "LOCATION",
            Pattern.compile(
                "\\b(?:[A-Za-z]++\\s+)++(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln"
                    + "|Place|Pl|Court|Ct|Way|Highway|Hwy)\\b"),
            0.6),
        // Two unrestricted letters + six digits, no delimiter, no checksum, no formatted variant worth preserving --
        // functionally a bare alphanumeric run wearing an entity name, the same shape class as the low-band
        // bare-digit patterns (see PiiPatternCatalogTest's finding: this pattern matched a plain SKU, "AB123456").
        // Rescored Low; the regex itself has nothing to split out, unlike PHONE_NUMBER above.
        new PiiPattern(
            "MEDICAL_LICENSE",
            Pattern.compile("\\b[A-Z]{2}\\d{6}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "medical license",
                    "medical licence",
                    "dea number",
                    "physician license",
                    "practitioner number"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // USA
        new PiiPattern(
            "US_BANK_NUMBER",
            Pattern.compile("\\b\\d{8,17}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "bank account",
                    "account number",
                    "acct no",
                    "routing number",
                    "checking account",
                    "savings account"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // One unrestricted letter + seven digits, no delimiter, no checksum -- the same shape class as
        // MEDICAL_LICENSE above (Task 2's catalog sweep, following the identical reasoning applied there:
        // "AB123456" fired as MEDICAL_LICENSE, and "A1234567" fires here for the same structural reason).
        // IN_PASSPORT below carries the byte-identical regex and moves with it.
        new PiiPattern(
            "US_DRIVER_LICENSE",
            Pattern.compile("\\b[A-Z]\\d{7}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "driver license",
                    "driver's license",
                    "driving license",
                    "driving licence",
                    "dln"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        new PiiPattern(
            "US_ITIN",
            Pattern.compile("\\b9\\d{2}-\\d{2}-\\d{4}\\b"),
            0.6),
        // One unrestricted letter + eight digits, no delimiter, no checksum -- same shape class as
        // US_DRIVER_LICENSE above, one digit longer. ES_NIF and ES_NIE used to carry this byte-identical regex
        // (wrongly -- see the class javadoc); their corrected shapes no longer match it.
        new PiiPattern(
            "US_PASSPORT",
            Pattern.compile("\\b[A-Z]\\d{8}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "passport"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // Bare-9-digit alternative removed: it collided with AU_TFN's identical \b\d{9}\b shape and a single score
        // cannot serve both this dashed branch and that weak one. See the class javadoc.
        new PiiPattern(
            "US_SSN",
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            0.6),
        // UK
        new PiiPattern(
            "UK_NHS",
            Pattern.compile("\\b\\d{3} \\d{3} \\d{4}\\b"),
            0.6),
        // Bookended (letter(s)-digits-letter), not a plain prefix-then-digits run like IT_DRIVER_LICENSE above
        // -- a trailing letter after a fixed-length digit run is a much less common ordinary-business-code
        // convention than a leading letter prefix (compare "INV1234567"/"SKU AB123456" to "AB123456C"), and
        // the extra constrained letter position narrows the match space further than a prefix-only shape of
        // the same total length. Considered during Task 2's sweep and kept at Medium; see SG_NRIC_FIN below
        // for the same reasoning applied to a single-letter bookend.
        new PiiPattern(
            "UK_NINO",
            Pattern.compile("\\b[A-Z]{2}\\d{6}[A-Z]\\b"),
            0.6),
        // Spain
        // Corrected 2026-08-31: was the byte-identical, letter-first \b[A-Z]\d{8}\b as ES_NIE below, which matched
        // neither real format. Real NIF: 8 digits + a trailing check letter (e.g. "12345678Z"). See the class
        // javadoc for the correction and its fallout, including that this shape is byte-identical to SG_UEN's
        // 8-digit alternative below, which loses the resolver's category tie-break to this entry as a result.
        new PiiPattern(
            "ES_NIF",
            Pattern.compile("\\b\\d{8}[A-Z]\\b"),
            0.6),
        // Corrected 2026-08-31: was the byte-identical, letter-first \b[A-Z]\d{8}\b as ES_NIF above. Real NIE:
        // X/Y/Z + 7 digits + a trailing check letter (e.g. "X1234567L") -- bookended like UK_NINO/SG_NRIC_FIN
        // below, with a narrower 3-character leading-letter class than either. See the class javadoc; this shape
        // overlaps SG_NRIC_FIN below whenever the leading letter is X, Y or Z.
        new PiiPattern(
            "ES_NIE",
            Pattern.compile("\\b[XYZ]\\d{7}[A-Z]\\b"),
            0.6),
        // Italy
        new PiiPattern(
            "IT_FISCAL_CODE",
            Pattern.compile("\\b[A-Z]{6}\\d{2}[A-Z]\\d{2}[A-Z]\\d{3}[A-Z]\\b"),
            0.9),
        // Two unrestricted letters + seven digits, no delimiter, no checksum -- the same shape class as
        // MEDICAL_LICENSE (two letters + six digits, already Low) one digit longer, missed by that
        // correction the first time: "SKU AB1234567" fires here at Medium while "SKU AB123456" is correctly
        // clean, so whether an ordinary SKU is redacted turned on whether it happened to carry six digits or
        // seven. IT_PASSPORT and IT_IDENTITY_CARD below carry the byte-identical regex and move with it.
        new PiiPattern(
            "IT_DRIVER_LICENSE",
            Pattern.compile("\\b[A-Z]{2}\\d{7}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "patente di guida",
                    "patente",
                    "driver license",
                    "driving licence"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // Contrast with IT_DRIVER_LICENSE just above: the literal "IT" prefix is a fixed 2-character anchor,
        // not an unrestricted letter class, so this is not the same shape -- stays High.
        new PiiPattern(
            "IT_VAT_CODE",
            Pattern.compile("\\bIT\\d{11}\\b"),
            0.9),
        new PiiPattern(
            "IT_PASSPORT",
            Pattern.compile("\\b[A-Z]{2}\\d{7}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "passaporto",
                    "passport"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        new PiiPattern(
            "IT_IDENTITY_CARD",
            Pattern.compile("\\b[A-Z]{2}\\d{7}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "carta d'identita",
                    "carta di identita",
                    "identity card",
                    "documento di identita"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // Poland
        new PiiPattern(
            "PL_PESEL",
            Pattern.compile("\\b\\d{11}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "pesel",
                    "numer pesel"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // Singapore
        // Bookended (letter-digits-letter) -- see UK_NINO's comment above for why this stays Medium rather
        // than joining the demoted prefix-only group. Considered during Task 2's sweep and kept. ES_NIE above
        // overlaps this pattern whenever the leading letter is X, Y or Z; see the class javadoc.
        new PiiPattern(
            "SG_NRIC_FIN",
            Pattern.compile("\\b[A-Z]\\d{7}[A-Z]\\b"),
            0.6),
        // A fixed-length digit run with a mandatory trailing check-style letter is a materially different
        // shape from a bare digit run with nothing after it (PL_PESEL, AU_TFN, US_BANK_NUMBER) -- Presidio's
        // own tiering treats this shape as non-trivial for the same reason. Considered during Task 2's sweep
        // and kept at Medium; this is the closest call of the entries left alone. Its 8-digit alternative is
        // now byte-identical to ES_NIF above and always loses the resolver's category tie-break to it; see the
        // class javadoc.
        new PiiPattern(
            "SG_UEN",
            Pattern.compile("\\b\\d{8}[A-Z]\\b|\\b\\d{9}[A-Z]\\b"),
            0.6),
        // Australia
        new PiiPattern(
            "AU_ABN",
            Pattern.compile("\\b\\d{2} \\d{3} \\d{3} \\d{3}\\b"),
            0.6),
        new PiiPattern(
            "AU_ACN",
            Pattern.compile("\\b\\d{3} \\d{3} \\d{3}\\b"),
            0.6),
        new PiiPattern(
            "AU_TFN",
            Pattern.compile("\\b\\d{9}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "tax file number",
                    "tax file no",
                    "tfn"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        new PiiPattern(
            "AU_MEDICARE",
            Pattern.compile("\\b\\d{4} \\d{5} \\d{1}\\b"),
            0.6),
        // India
        // 5 fixed letter positions + 4 digits + 1 letter: far more constrained than a plain letter-prefix run
        // (compare IN_VOTER below, 3 letters), so left at Medium -- considered during Task 2's sweep and kept.
        new PiiPattern(
            "IN_PAN",
            Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b"),
            0.6),
        new PiiPattern(
            "IN_AADHAAR",
            Pattern.compile("\\b\\d{4} \\d{4} \\d{4}\\b"),
            0.6),
        // Two independent 2-letter + digit groups (a license-plate-style state/series/number structure), not a
        // single letter-prefix-then-digits run -- considered during Task 2's sweep and kept at Medium.
        new PiiPattern(
            "IN_VEHICLE_REGISTRATION",
            Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z]{2}\\d{4}\\b"),
            0.6),
        // Three unrestricted letters + seven digits, no delimiter, no checksum -- same shape class as
        // MEDICAL_LICENSE/IT_DRIVER_LICENSE above, one letter position wider. Task 2's catalog sweep.
        new PiiPattern(
            "IN_VOTER",
            Pattern.compile("\\b[A-Z]{3}\\d{7}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "voter id",
                    "epic no",
                    "voter card",
                    "elector"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // Same bare letter+7-digit shape as US_DRIVER_LICENSE above -- see that entry's comment.
        new PiiPattern(
            "IN_PASSPORT",
            Pattern.compile("\\b[A-Z]\\d{7}\\b"),
            0.2,
            new ContextRule(
                Set.of(
                    "passport"),
                CONTEXT_WINDOW, CONTEXT_PROMOTED_SCORE)),
        // Finland
        // CRITICAL fix: the century-marker class was originally written [+-A], which in a Java character class
        // is not the 3-character set {+, -, A} the century markers require (+ = 1800s, - = 1900s, A = 2000s) --
        // it is the RANGE from '+' (0x2B) to 'A' (0x41), which spans ",-./0123456789:;<=>?@" as well, i.e.
        // every digit. The pattern was therefore effectively \b\d{10}[A-Z0-9]\b, a bare eleven-character digit
        // run with no mandatory separator at all, and it redacted arbitrary 11-digit business identifiers
        // ("Order 20260825123 shipped") as this type -- see PiiPatternCatalogTest's finding for the exact
        // reproduction. Fixed by moving the hyphen to the end of the class ([+A-]), where it is a literal
        // character rather than a range operator, restoring the intended 3-character set. Re-scored, not just
        // re-patched: the corrected pattern is 6 digits + exactly one of 3 century-marker characters + 3 more
        // digits + 1 control character -- a genuine 6-1-3-1 structured shape with a narrow (3-character)
        // separator alphabet, the identical grouping AU_ABN/UK_NHS/IN_AADHAAR use at Medium -- so Medium is
        // still the right band for what the regex actually matches now, not merely what it used to claim.
        new PiiPattern(
            "FI_PERSONAL_IDENTITY_CODE",
            Pattern.compile("\\b\\d{6}[+A-]\\d{3}[A-Z0-9]\\b"),
            0.6));

    private PiiPatternCatalog() {
    }

    /**
     * Returns the always-on default: the full catalog minus the contextual types. Low-specificity patterns stay in this
     * list — {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE} is what keeps them from firing on ordinary business
     * text, not exclusion from here.
     *
     * @return the curated default pattern list
     */
    public static List<PiiPattern> curatedDefault() {
        return ALL.stream()
            .filter(piiPattern -> !CONTEXTUAL_TYPES.contains(piiPattern.type()))
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
     * <p>
     * {@code validator}, when present, is applied to each regex match's matched text (see {@link RegexPiiDetector})
     * <em>after</em> the pattern matches and before a span is emitted — a second, optional gate beyond the shape the
     * regex already describes. This is the seam a checksum lives on: {@code CREDIT_CARD} supplies a Luhn check so its
     * pattern can match both formatted and bare digit runs while still excluding the arbitrary bare runs that are not
     * valid card numbers. Every other entry passes {@code null} and behaves exactly as it did before this field
     * existed. Deliberately not a per-type branch inside the detector — see {@code RegexPiiDetector}'s javadoc — so the
     * eventual national-identifier checksum project (spec decision D10) has a shape to extend rather than a
     * special-cased detector to work around.
     * </p>
     *
     * @param type      the Presidio entity type, which doubles as the span category
     * @param pattern   the recognising regex
     * @param score     detection confidence between {@code 0.0} and {@code 1.0}, always one of {@code 0.2}/{@code 0.6}/
     *                  {@code 0.9} per {@code docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md} —
     *                  low for a bare digit/alphanumeric run anchored only by {@code \b}, medium for a constrained but
     *                  ambiguous shape, high for a structurally distinctive one
     * @param validator an optional second gate over the matched text, applied after the regex matches and before a span
     *                  is emitted, or {@code null} when the regex match alone is the whole story
     */
    public record PiiPattern(
        String type, Pattern pattern, double score, @Nullable Predicate<String> validator,
        @Nullable ContextRule contextRule) {

        public PiiPattern {
            if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be between 0.0 and 1.0, got: " + score);
            }

            // Raise-only is a SECURITY property, not tidiness. A rule that lowered a score would be an off switch
            // an attacker writes into the prompt: put "Order number:" in front of a real SSN and the guardrail
            // stops firing. Raising cannot be abused that way -- the worst an attacker achieves is being redacted.
            if (contextRule != null && contextRule.score() <= score) {
                throw new IllegalArgumentException(
                    "a context rule must RAISE confidence: " + type + " scores " + score +
                        " and its rule offers " + contextRule.score());
            }
        }

        // Convenience overload for the common case -- every entry but CREDIT_CARD -- so the 35 unaffected catalog
        // entries below did not need to change when this field was added.
        public PiiPattern(String type, Pattern pattern, double score) {
            this(type, pattern, score, null, null);
        }

        public PiiPattern(String type, Pattern pattern, double score, @Nullable Predicate<String> validator) {
            this(type, pattern, score, validator, null);
        }

        // Unambiguous against the validator overload above: ContextRule and Predicate are unrelated types.
        public PiiPattern(String type, Pattern pattern, double score, ContextRule contextRule) {
            this(type, pattern, score, null, contextRule);
        }
    }

    /**
     * Raises a match's confidence when a naming keyword sits near it.
     *
     * <p>
     * This is what buys back the coverage confidence scoring traded away. A pattern's score reflects how specific its
     * own shape is, and a bare digit or alphanumeric run scores {@code 0.2} -- below
     * {@link SensitiveDataRedactor#DEFAULT_MIN_CONFIDENCE}, so it is detected by nothing at the default threshold. That
     * is correct for {@code Order A12345678} and wrong for {@code Passport: A12345678}, and the difference is not in
     * the match: it is next to it.
     * </p>
     *
     * @param keywords the terms that promote a match, lower-cased -- matching lower-cases the haystack, so an
     *                 upper-case keyword here would never fire ({@code PiiPatternCatalogTest} pins that)
     * @param window   how many characters either side of the match a keyword may sit in. Bounded on purpose: without
     *                 it, one keyword anywhere in a long document would promote every match in it.
     * @param score    the promoted confidence, which must exceed the pattern's own base score
     */
    public record ContextRule(Set<String> keywords, int window, double score) {

        public ContextRule {
            if (keywords == null || keywords.isEmpty()) {
                throw new IllegalArgumentException("keywords must not be empty");
            }

            keywords = Set.copyOf(keywords);

            if (window <= 0) {
                throw new IllegalArgumentException("window must be > 0, got: " + window);
            }

            if (!Double.isFinite(score) || score <= 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be in (0.0, 1.0], got: " + score);
            }
        }
    }

    // Standard Luhn checksum (ISO/IEC 7812), applied to the matched text after any [-\s] separators are stripped.
    // CREDIT_CARD's only validator; see that entry's comment and PiiPattern's javadoc for why this lives here rather
    // than as a branch in RegexPiiDetector.
    private static boolean isLuhnValid(String matchedText) {
        String digits = matchedText.replaceAll("[-\\s]", "");

        int sum = 0;
        boolean doubleDigit = false;

        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';

            if (doubleDigit) {
                digit *= 2;

                if (digit > 9) {
                    digit -= 9;
                }
            }

            sum += digit;
            doubleDigit = !doubleDigit;
        }

        return sum % 10 == 0;
    }
}

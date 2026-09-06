# Sensitive-Data Confidence Scores — Provenance Table

**Date:** 2026-08-25
**Status:** reference table — input to `2026-08-25-sensitive-data-confidence-scoring-design.md`, Task 1 of the
implementation plan of the same name.
**Revision note (2026-08-25, same day):** re-banded after review. Every score in this document now comes from
the three-band rubric in §1 applied to *our own regex*, never adopted from Presidio. §1a explains why. The
Presidio-comparison column survives — renamed and clearly separated from scoring — because the comparison itself
is real evidence about a pattern's shape, useful independent of whether its number is borrowed.

**Revision note (2026-08-31, final-branch-review fixes):** the whole-branch review that closed out this feature
found two of this table's rows wrong at the moment they were written, not merely stale. Both are amended in place
(matching the precedent already set by the `MEDICAL_LICENSE` correction described in §8b), since these are
corrections to what the row always should have said, not later drift like `US_SSN` below:

- **`FI_PERSONAL_IDENTITY_CODE`'s regex text.** The row quoted `` \b\d{6}[+-A]\d{3}[A-Z0-9]\b `` and reasoned
  about it as a 6-1-3-1 structured shape. `[+-A]` is not the 3-character set `{+, -, A}` it was written to mean —
  in a Java (and every POSIX-flavored) character class, `-` between two other characters opens a *range*, so
  `[+-A]` is every character from `+` (0x2B) to `A` (0x41) inclusive, which includes every digit. The pattern was
  therefore effectively `` \b\d{10}[A-Z0-9]\b `` — a bare 11-character digit run — and matched ordinary 11-digit
  business identifiers at the row's own Medium score. The regex is corrected to `` \b\d{6}[+A-]\d{3}[A-Z0-9]\b ``
  (hyphen moved to the end of the class, where it is a literal rather than a range operator); the **score is
  unchanged** at `0.6` — the corrected pattern really is the 6-1-3-1 structured shape the original reasoning
  described, so the reasoning was right about the *intended* pattern, just not the one actually compiled.
- **`CREDIT_CARD`'s regex text.** The row quoted `` \b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b `` with all three
  separators optional, and reasoned about it as "a dashed group, the rubric's own example shape." An optional
  separator that never fires degenerates the pattern to a bare `` \b\d{16}\b `` run — the identical trap
  `PHONE_NUMBER`'s row below already documents being fixed for that entry — so an unformatted 16-digit number
  matched exactly as confidently as a properly dashed card number. Fixed the same way PHONE_NUMBER was: the three
  separators are now mandatory (`` \b\d{4}[-\s]\d{4}[-\s]\d{4}[-\s]\d{4}\b ``). Score unchanged at `0.6` — every
  genuinely formatted card number the row's reasoning describes still matches; only the bare-digit degenerate case
  is now excluded, falling through to `US_BANK_NUMBER` (Low) like `PHONE_NUMBER`'s excluded case does.
- **Nine rows rescored Low, applying the `MEDICAL_LICENSE` correction the rest of the way.** `US_DRIVER_LICENSE`,
  `US_PASSPORT`, `ES_NIF`, `ES_NIE`, `IT_DRIVER_LICENSE`, `IT_PASSPORT`, `IT_IDENTITY_CARD`, `IN_VOTER`, and
  `IN_PASSPORT` are the identical shape class `MEDICAL_LICENSE` was corrected for in the first version of this
  table — an unrestricted-letter prefix concatenated directly with a digit run, no delimiter, no checksum, no
  bookending suffix — and the correction was never swept across the rest of the catalog. Each row is amended below;
  see §8b for the updated band totals. (`ES_NIF`/`ES_NIE` did not stay in this group: the "closing pass, Group 2"
  revision note below found their regex itself wrong, not just under-scored, and rescored both to `0.6` once
  corrected — see that note.)

**Revision note (2026-08-31, closing pass, Group 1 — spec decision D10 reversed for `CREDIT_CARD` only):**
`CREDIT_CARD` gains an optional Luhn-checksum validator (`PiiPatternCatalog.PiiPattern#validator()`, a new field,
applied after the regex matches and before a span is emitted) to close a regression the amendment directly above
introduced: making the three separators mandatory correctly closed the degenerate-bare-`\b\d{16}\b`-run trap, but
its side effect was that an unformatted, genuinely valid card number (`4532015112830366`) no longer matched
`CREDIT_CARD` at all — it fell through to `US_BANK_NUMBER` (Low) and was suppressed, worse than before this
feature shipped. The checksum is what makes matching the bare form safe again: the separators are optional once
more (`` \b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b ``, same shape §5 originally scored), but a match is only
emitted when the digits pass Luhn, so an ordinary 16-digit business identifier (Luhn-invalid) still produces no
span. **Score raised to `0.9` (High)** — amended in place below, per the same precedent as the two regex-text
corrections above — because a Luhn-valid match is no longer merely a plausible shape (this table's own rubric
example for Medium) but a checksum-verified one, the same category of evidence `EMAIL_ADDRESS`/`IBAN_CODE`/
`IT_FISCAL_CODE`/`CRYPTO` carry at High. See the design spec's decision table (D10) for the reversal's rationale.
§8b's band totals are updated for this one move; see that section for the before/after split.

**Revision note (2026-08-31, closing pass, Group 2 — `ES_NIF`/`ES_NIE` regex correction):** the sweep two revision
notes above rescored `ES_NIF` and `ES_NIE` to `0.2` (Low) by carrying the `MEDICAL_LICENSE` correction across the
catalog, on the assumption that both were the "unrestricted-letter-prefix + digit run" shape class. They were not
— a follow-up check against real-format sample values (executed, not just read) found both patterns wrong at the
regex layer, not merely under-scored: both carried `` \b[A-Z]\d{8}\b `` (a letter, then 8 digits), byte-identical
to each other, so the two entries could never be told apart at runtime and neither matched a real Spanish ID. The
real formats are digits-first: NIF (Spanish nationals) is 8 digits plus a trailing check letter (`12345678Z`); NIE
(foreign residents) is `X`/`Y`/`Z` plus 7 digits plus a trailing check letter (`X1234567L`) — matching what §3's
`ES_NIF`/`ES_NIE` rows already said about Presidio's field order, which this correction now makes true of our own
regex too. Corrected to `` \b\d{8}[A-Z]\b `` (`ES_NIF`) and `` \b[XYZ]\d{7}[A-Z]\b `` (`ES_NIE`); both amended in
place below, per the same precedent as the other regex-text corrections in this note. **Both rescored `0.6`
(Medium), reversing the Group-1-sweep's `0.2`**: each corrected shape now coincides with a shape this catalog
already scores Medium — `ES_NIF`'s is byte-identical to `SG_UEN`'s first alternative (digit run + mandatory
trailing check-style letter); `ES_NIE`'s is a bookended letter-digits-letter shape like `UK_NINO`/`SG_NRIC_FIN`,
with a narrower 3-character leading-letter class than either. Scoring either Low while an identical shape scores
Medium elsewhere would violate this document's own "same effective shape, same score" rule. Neither correction adds
check-letter validation (both formats have a computable mod-23 check letter, and `PiiPattern#validator()` — the
seam `CREDIT_CARD`'s Luhn check introduced — could carry one) — national check digits beyond Luhn remain a
separate, later project. **A collision, found and left unresolved:** `ES_NIF`'s corrected regex is now
byte-identical to `SG_UEN`'s 8-digit alternative, so a matching input produces two same-start, same-length
candidate spans, and `SensitiveDataRedactor`'s resolution order breaks that tie on category ascending —
`"ES_NIF"` sorts before `"SG_UEN"`, so `ES_NIF` always wins; `SG_UEN`'s 9-digit alternative is a different length
and unaffected. Separately, `ES_NIE`'s corrected pattern overlaps `SG_NRIC_FIN` whenever the matched text starts
with `X`, `Y` or `Z` (a subset of `SG_NRIC_FIN`'s unrestricted leading letter), with `ES_NIE` winning the same
tie-break in that narrower case. Neither entry has been restructured to avoid either collision. §5's
`ES_NIF`/`ES_NIE` note (previously "not this task's job to fix") and §8b's band totals are both updated below.

**This document is a frozen, point-in-time derivation record, not a live source**, aside from the amendments
above and the `MEDICAL_LICENSE`/`PHONE_NUMBER` amendments already made before it. It exists to justify each of
the 47 scores against the rubric and the Presidio comparison at the moment the scoring work was done. Once those
scores shipped, `PiiPatternCatalog`/`SecretPatternCatalog` (`platform-ai-sensitive-data-service`) became the
single source of truth for what each pattern actually scores today — no test pins this table against the catalog,
deliberately, precisely because the table is not meant to track later drift. A reader who needs the current score
for any pattern should read the catalog, not this table. **One row is still deliberately left diverged**: §3's
`US_SSN` row still describes the original combined pattern (`` \b\d{3}-\d{2}-\d{4}\b\|\b\d{9}\b `` scoring `0.2`)
that existed when this document was written. A later fix (closing the `US_SSN`/`AU_TFN` collision on `987654321`
— see `2026-08-25-guardrails-consolidation-design.md` §5a) dropped the bare-digit alternative from `US_SSN`
entirely, so the live catalog's `US_SSN` is `` \b\d{3}-\d{2}-\d{4}\b `` only, scoring `0.6`. That change is
intentionally not back-ported into this table, unlike the amendments above — dropping an alternative branch is a
structural change to what the pattern *is*, not a correction to a mis-transcribed or mis-scored row; see
`PiiPatternCatalog`'s class javadoc for the current, authoritative account of that pattern.

## 0. What this document is, and what it corrects

**This codebase does not use Presidio.** There is no dependency, no HTTP client, no service call to Microsoft
Presidio anywhere in ByteChef. Every occurrence of the word "Presidio" in this codebase — the class
`PresidioRegexPiiDetector`, `PiiPatternCatalog`'s javadoc calling the catalog "Presidio-taxonomy", some test
method names — was introduced on an *assumption* that the entity-name vocabulary (`EMAIL_ADDRESS`, `US_SSN`,
`UK_NINO`, ...) indicated a Presidio origin. That assumption had never been verified against Presidio's actual
regexes, only against its entity-type *names*. `PiiDetectorUtilsParityTest`, repeatedly cited as evidence of
alignment with an external reference, contains no reference to Presidio at all — it is a set of detection tests
for international entity types, and the "parity" in its name is unexplained.

The comparison below was made **by inspection**, not by re-running Presidio: each of ByteChef's 47 catalog
entries (36 in `PiiPatternCatalog`, 11 in `SecretPatternCatalog`) was compared against Microsoft Presidio's
published predefined recognizer for the same entity type. The recognizer text was retrieved from the
`microsoft/presidio` GitHub repository (`main` branch, accessed 2026-08-25) through a fetch-and-summarize tool
rather than a byte-exact diff against the raw `.py` source for every file — see §6 for what that means for how
much to trust the quoted Presidio regex text specifically. No Presidio code or dependency was added anywhere;
this is a one-time, static, documented comparison, not a runtime integration, and — as of this revision — it no
longer determines any score in this document (§1a).

**Finding, stated plainly:** the entity-name vocabulary matches Presidio's almost universally — all 36 PII types
in `PiiPatternCatalog` have a same- or equivalently-named Presidio predefined recognizer. But the **regex itself**
matches Presidio's published pattern for only 21 of the 47 entries (45%). 13 entries (28%) have a Presidio
counterpart whose regex encodes different, usually tighter, constraints than ours. 13 entries (28%) — all 11
secret/credential patterns plus `PHONE_NUMBER` and `LOCATION` — have no Presidio regex counterpart at all. So the
"Presidio-taxonomy" claim is true of the *names*; it was never true of the *scores* — and, per §1a, it is not
meant to become true of the scores either, even for the 21 entries whose regex genuinely matches. See §4 for the
full split and §5 for the entries where the shape comparison was genuinely ambiguous.

## 1. The three score bands

Stated once, here, so every score in §3/§4 traces to a rule instead of a per-pattern judgement call. **Every
score in this document — regardless of whether the row's Presidio comparison is a match, a non-match, or no
counterpart — is one of these three values**, chosen by applying the rule to *our own regex's* shape. Presidio's
published number, when one exists, is never copied into the score column; see §1a for why.

| Band | Value | Rule |
|---|---|---|
| **High** | **0.9** | A fixed literal prefix plus a fixed-length (or narrowly bounded) body (`AKIA`+16, `ghp_`+36, `sk_live_`+…), or a structurally distinctive shape where a false positive from ordinary text is implausible (email, IBAN, PEM block, a 16-position typed national-ID format). |
| **Medium** | **0.6** | A constrained shape with some real ambiguity: a letter prefix plus digits, a dotted quad, a dashed/spaced digit group, a library-shaped identifier with no checksum. |
| **Low** | **0.2** | A bare digit or alphanumeric run anchored only by `\b` (`\b\d{9}\b`, `\b\d{8,17}\b`), or a pattern whose *only* usable alternative is that shape. These are the patterns this whole feature exists for. |

A threshold placed anywhere strictly between Low and Medium — e.g. `0.3`, `0.4`, or `0.5` — separates the two
classes cleanly: every Low-banded score (`0.2`) falls below it, every Medium- or High-banded score (`0.6`, `0.9`)
falls at or above it. No row's score falls outside `{0.2, 0.6, 0.9}`, and none falls below `0.2`. §8 confirms this
by inspection of the committed rows.

### 1a. Why Presidio's published numbers are not adopted, even on a genuine regex match

The first version of this document adopted Presidio's own score whenever a row's regex matched Presidio's
published pattern for that entity, deriving a score from the rubric above only when it didn't. That produced an
unusable result: several adopted ("matched") scores — `US_BANK_NUMBER` at `0.05`, `US_PASSPORT` at `0.1`,
`IT_PASSPORT` at `0.01` — sat *below* the derived Low band (`0.2`), which was supposed to be reserved for the
weakest shapes. `US_BANK_NUMBER`'s `\b\d{8,17}\b` is exactly the shape this feature exists to suppress; a real
passport number pattern scoring lower than that bare digit run means no single threshold can suppress the one
without also suppressing the other. The score space was inverted relative to intent.

The cause: Presidio's low numbers are calibrated **assuming its own context-word boosting** runs afterward.
Presidio's own `IT_PASSPORT` recognizer scores `0.01` — not because Passport numbers are unlikely to be
Passport numbers, but because Presidio's own documentation for that pattern treats `0.01` as "worthless on its
own, trust it near the word *passaporto*." `US_BANK_NUMBER`'s Presidio score of `0.05` carries the identical
assumption. This pipeline has no context-word boosting — the design spec that motivates this document makes it an
explicit non-goal — so a Presidio number written for a two-stage system means something different, and something
misleadingly weak, in a one-stage one. Adopting Presidio's vocabulary (the entity names) while not adopting its
machinery (context boosting), and then adopting its numbers anyway, repeats the same mistake a third time with a
different artifact: names without machinery, now scores without machinery.

**So every score in §3/§4 is derived from §1's rubric, applied to our own regex, independent of the Presidio
comparison outcome.** The Presidio-comparison column is kept because it is still real, useful evidence about
whether our regex's *shape* corresponds to a recognized entity pattern — just not evidence about what number to
assign it.

## 2. Column definitions

- **Presidio comparison** — whether our regex's match set is the same shape as (or a faithful subset of) a
  specific Presidio pattern/score-tier for the same entity:
  - `presidio-match` — our pattern is the same shape as (or a faithful, formatting-only subset of) a specific
    Presidio pattern.
  - `no-match` — our pattern accepts strings the compared Presidio pattern would reject, is missing a constraint
    that materially changes the false-positive profile, or spans several Presidio score tiers in a way that
    means no single Presidio pattern corresponds to it.
  - `no-counterpart` — Presidio publishes no regex recognizer for this entity (unsupported, NER-only,
    library-based rather than regex-based, or — for every secret/credential type — outside Presidio's scope).
  This column carries no scoring weight (§1a); it is recorded because it is real shape evidence, not because it
  selects a number.
- **Score** — always one of `0.2` / `0.6` / `0.9` (§1), derived from applying the rubric to *our* regex, chosen
  independently of the Presidio-comparison verdict.
- **Reason** — states, in order: (1) why the Presidio-comparison verdict holds, and (2) which band our own
  regex's score comes from and why. The two halves are independent by construction — see §1a.

## 3. PII catalog (`PiiPatternCatalog.ALL`, 36 entries)

| Type | Our regex | Presidio regex compared | Presidio comparison | Score | Reason |
|---|---|---|---|---|---|
| `EMAIL_ADDRESS` | `` \b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b `` | `EmailRecognizer`, "Email (Medium)": `` \b((([!#$%&'*+\-/=?^_&#96;{\|}~\w])\|([!#$%&'*+\-/=?^_&#96;{\|}~\w][!#$%&'*+\-/=?^_&#96;{\|}~\.\w]{0,}[!#$%&'*+\-/=?^_&#96;{\|}~\w]))[@]\w+(?:-+\w+)*(?:\.\w+(?:-+\w+)*)+)\b ``, score 0.5 | presidio-match | 0.9 | Verdict: same local-part@domain.tld shape; ours is a simpler subset. Score — High: structurally distinctive shape, the rubric's own named example. |
| `PHONE_NUMBER` | `` \b[+]?[(]?[0-9]{3}[)]?[-\s.][0-9]{3}[-\s.][0-9]{4,6}\b `` | `PhoneRecognizer` — no regex; uses the `phonenumbers` library's `PhoneNumberMatcher`, `SCORE = 0.4` for every match | no-counterpart | 0.6 | Verdict: not regex-based, no comparison possible. Score — Medium: a formatted digit group (area-code parens, a mandatory separator between each group) is constrained but genuinely ambiguous, plausibly colliding with other formatted digit groups. Correction (post Task 5): the original regex made both `[-\s.]` separators optional, which let the pattern degenerate to a bare `\b\d{10,12}\b` run — Low specificity wearing a Medium score — and fire on plain business identifiers like an unformatted invoice number. Both separators are now mandatory; the score stays Medium because every genuinely *formatted* shape this pattern targets remains exactly as ambiguous as before. |
| `CREDIT_CARD` | `` \b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b `` plus a Luhn-checksum validator run in code (2026-08-31 closing pass, Group 1: separators optional again, gated by the validator — see the revision note above) | `CreditCardRecognizer`, "All Credit Cards (weak)": `` \b(?!1\d{12}(?!\d))((4\d{3})\|(5[0-5]\d{2})\|(6\d{3})\|(1\d{3})\|(3\d{3}))[- ]?(\d{3,4})[- ]?(\d{3,4})[- ]?(\d{3,5})\b ``, score 0.3, plus a Luhn-checksum validator run in code | no-match | 0.9 | Verdict: Presidio restricts the leading group to major-issuer prefixes and Luhn-validates the result; ours accepts any 4-4-4-4 digit grouping with no issuer-prefix constraint, but — as of 2026-08-31 — does Luhn-validate the result too, in code alongside the regex rather than inside it. Score — High: raised from Medium once the match is gated by a checksum rather than shape alone, the same category of evidence `EMAIL_ADDRESS`/`IBAN_CODE`/`IT_FISCAL_CODE`/`CRYPTO` carry; a Luhn-valid 4-4-4-4 (or bare 16-digit) run is no longer merely a plausible shape, and an ordinary business identifier of the identical shape that fails Luhn is not matched at all, not merely scored low. |
| `IP_ADDRESS` | `` \b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b `` | `IpRecognizer`, "IPv4": dotted-quad with optional CIDR suffix, score 0.6 (IPv4/IPv4_mapped/IPv4_embedded/IPv6 all 0.6; `IPv6_unspecified` is 0.1) | presidio-match | 0.6 | Verdict: bare dotted-quad without CIDR or octet-range validation — a strict subset of Presidio's IPv4 pattern. Score — Medium: dotted quad, the rubric's own example shape. |
| `IBAN_CODE` | `` \b[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}([A-Z0-9]?){0,16}\b `` | `IbanRecognizer`, "IBAN Generic": `` (?<![A-Z0-9])([A-Z]{2}[0-9]{2}(?:[ -]?[A-Z0-9]{4}){2,6})((?:[ -]?[A-Z0-9]{4})?)((?:[ -]?[A-Z0-9]{1,3})?)(?![A-Z0-9]) ``, score 0.5 | presidio-match | 0.9 | Verdict: same country-code + check-digits + alphanumeric-BBAN structure. Score — High: structurally distinctive, the rubric's own named example. |
| `CRYPTO` | `` \b[13][a-km-zA-HJ-NP-Z1-9]{25,34}\b `` | `CryptoRecognizer`: `(bc1|[13])[a-km-zA-HJ-NP-Z0-9]{25,59}`, score 0.5 | presidio-match | 0.9 | Verdict: ours is the `1`/`3`-prefixed (P2PKH/P2SH) branch, missing the `bc1` bech32 alternative and capped at length 34 instead of 59 — a strict subset of the same pattern. Score — High: a fixed-alphabet, 26-35-character bounded-length shape; accidental occurrence in ordinary text is implausible. |
| `DATE_TIME` | `` \b\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:?\d{2})?)?\b `` | `DateRecognizer` — 13 patterns; the closest are "yyyy-mm-dd" (score 0.6, with month/day range validation) and "ISO 8601 DateTime with timezone" (score 0.8, time+tz mandatory) | no-match | 0.6 | Verdict: our single pattern spans both a date-only and a full-timestamp Presidio tier with no month/day range validation either way (`9999-99-99` matches), so it is broader than both, not a subset of either. Score — Medium: dashed digit group, undervalidated. |
| `LOCATION` | `` \b(?:[A-Za-z]++\s+)++(?:Street\|St\|Avenue\|Ave\|Road\|Rd\|Boulevard\|Blvd\|Drive\|Dr\|Lane\|Ln\|Place\|Pl\|Court\|Ct\|Way\|Highway\|Hwy)\b `` | No regex recognizer; Presidio detects `LOCATION` via NER (`SpacyRecognizer` / transformer models), not a predefined pattern | no-counterpart | 0.6 | Verdict: not comparable — Presidio's LOCATION is model-based, not regex-based. Score — Medium: the literal street-suffix vocabulary is fairly specific but can false-positive on non-address text ("Sesame Street" as a show title, a business named "... Court"). |
| `MEDICAL_LICENSE` | `` \b[A-Z]{2}\d{6}\b `` | `MedicalLicenseRecognizer` (US DEA number): `` [abcdefghjklmprstuxABCDEFGHJKLMPRSTUX]{1}[a-zA-Z]{1}\d{7}\|[abcdefghjklmprstuxABCDEFGHJKLMPRSTUX]{1}9\d{7} ``, score 0.4 | no-match | 0.2 | Verdict: different shape entirely — Presidio's DEA format is a restricted first letter + any letter + 7 digits (9 chars); ours is 2 unrestricted letters + 6 digits (8 chars). Correction (post Task 5): originally scored Medium as "letter prefix + digits, no checksum", but the two letters are wholly unrestricted (no DEA-style first-letter allowlist) and there is no delimiter — it is a bare alphanumeric run wearing an entity name, not a constrained shape, and it fired on a plain SKU ("AB123456") at the default threshold. Score — Low: same specificity class as the bare-digit-run patterns. |
| `US_BANK_NUMBER` | `\b\d{8,17}\b` | `UsBankRecognizer`: `\b[0-9]{8,17}\b`, score 0.05 | presidio-match | 0.2 | Verdict: byte-for-byte identical shape. Score — Low: bare digit run anchored only by `\b` — one of the rubric's own two named exemplar patterns, and one of the two bare-digit patterns this whole feature exists to suppress. |
| `US_DRIVER_LICENSE` | `\b[A-Z]\d{7}\b` | `UsLicenseRecognizer`, "Driver License - Alphanumeric (weak)": a large alternation including `[A-Z][0-9]{6,8}` as one branch, score 0.3 (a second, unrelated "Digits (very weak)" pattern is 0.01) | presidio-match | 0.2 | Verdict: our shape (1 letter + exactly 7 digits) is a strict subset of the `[A-Z][0-9]{6,8}` branch inside Presidio's single alternation. Correction (2026-08-31, see the revision note above): originally scored Medium, but one unrestricted letter concatenated directly with a digit run, no delimiter, no checksum, is the same shape class as `MEDICAL_LICENSE` — just missed by that correction the first time. Score — Low. |
| `US_ITIN` | `\b9\d{2}-\d{2}-\d{4}\b` | `UsItinRecognizer` — 3 tiers: "very weak" (partial dash) 0.05, "weak" (no dash, IRS-valid middle-digit ranges 50-65/70-88/90-92/94-99) 0.3, "medium" (full dash, same range validation) 0.5 | no-match | 0.6 | Verdict: matches the full-dash shape (Presidio's "medium" tier) but has no middle-digit range validation — accepts any 2 digits where Presidio requires an IRS-valid range. Score — Medium: dashed group, undervalidated. |
| `US_PASSPORT` | `\b[A-Z]\d{8}\b` | `UsPassportRecognizer`, "Passport Next Generation (very weak)": `\b[A-Z][0-9]{8}\b`, score 0.1 (a separate "Passport (very weak)" bare-9-digit pattern is also 0.05) | presidio-match | 0.2 | Verdict: byte-for-byte identical shape. Score — Low: same bare letter-prefix-plus-digit-run shape as `US_DRIVER_LICENSE` above, one digit longer. |
| `US_SSN` | `` \b\d{3}-\d{2}-\d{4}\b\|\b\d{9}\b `` | `UsSsnRecognizer` — 5 patterns across two score tiers: SSN1/SSN2/SSN3 (dash variants, incl. `\d{3}-\d{2}-\d{4}`) and SSN4 (bare `\d{9}`) all at 0.05; SSN5 (flexible delimiter incl. dash) at 0.5 | presidio-match | 0.2 | Verdict: our dashed alternative is byte-identical to SSN3 and our bare-digit alternative is byte-identical to SSN4 — both correspond to Presidio's 0.05 tier, not the 0.5 SSN5 tier. Score — Low: the bare-digit alternative (`\b\d{9}\b`) is itself the rubric's other named exemplar pattern and one of the two bare-digit patterns this feature exists to suppress; it dominates the risk profile of the whole pattern regardless of which alternative fires. This is `PiiPatternCatalog`'s own documented `AU_TFN` collision case on `'987654321'`. |
| `UK_NHS` | `\b\d{3} \d{3} \d{4}\b` | `NhsRecognizer`: `` \b([0-9]{3})[- ]?([0-9]{3})[- ]?([0-9]{4})\b ``, score 0.5 | presidio-match | 0.6 | Verdict: mandatory single space is a strict subset of Presidio's pattern (optional space or hyphen). Score — Medium: spaced digit group. |
| `UK_NINO` | `\b[A-Z]{2}\d{6}[A-Z]\b` | `UkNinoRecognizer`: `` \b(?!bg\|gb\|nk\|kn\|nt\|tn\|zz\|BG\|GB\|NK\|KN\|NT\|TN\|ZZ)([a-ceghj-pr-tw-zA-CEGHJ-PR-TW-Z]{1}[a-ceghj-npr-tw-zA-CEGHJ-NPR-TW-Z]{1}) ?([0-9]{2}) ?([0-9]{2}) ?([0-9]{2}) ?([a-dA-D]{1})\b ``, score 0.5 | no-match | 0.6 | Verdict: Presidio excludes invalid 2-letter prefixes (bg/gb/nk/...) and restricts the trailing letter to A-D; ours allows any 2 letters and any trailing letter A-Z. Score — Medium: letter prefix + digits. |
| `ES_NIF` | `` \b\d{8}[A-Z]\b `` (corrected 2026-08-31, closing pass Group 2 — see the revision note above; was the byte-identical, letter-first `` \b[A-Z]\d{8}\b `` shared with `ES_NIE`, which matched neither real format) | `EsNifRecognizer`: `` \b[0-9]?[0-9]{7}[-]?[A-Z]\b ``, score 0.5, plus a mod-23 checksum-letter validator | presidio-match | 0.6 | Verdict: now digits-first with a trailing check letter, matching Presidio's field order — a strict subset of Presidio's pattern (ours fixes the digit count at exactly 8 and has no optional dash; Presidio's is dash-optional and accepts 7 or 8 digits). No checksum validation on our side — see the catalog's class javadoc for why a mod-23 check-letter validator remains a separate, later project. Score — Medium, reversed from the `0.2` the prior sweep gave this row: the corrected shape is byte-identical to `SG_UEN`'s first alternative below (a fixed-length digit run with a mandatory trailing check-style letter), not the bare-alphanumeric-run shape `MEDICAL_LICENSE` was corrected for — see the revision note above for the full reasoning, including the resolver-tie-break collision this creates with `SG_UEN`. |
| `ES_NIE` | `` \b[XYZ]\d{7}[A-Z]\b `` (corrected 2026-08-31, closing pass Group 2 — see the revision note above; was the byte-identical, letter-first `` \b[A-Z]\d{8}\b `` shared with `ES_NIF`) | `EsNieRecognizer`: `` \b[X-Z]?[0-9]?[0-9]{7}[-]?[A-Z]\b ``, score 0.5 | presidio-match | 0.6 | Verdict: now `X`/`Y`/`Z` plus 7 digits plus a trailing check letter, matching Presidio's field order — a strict subset of Presidio's pattern (ours makes the leading letter mandatory where Presidio's is optional, and has no optional dash). Score — Medium, reversed from the `0.2` the prior sweep gave this row: the corrected shape is bookended (letter-digits-letter) like `UK_NINO`/`SG_NRIC_FIN` below, with an even narrower 3-character leading-letter class than either — see the revision note above, including the partial overlap this creates with `SG_NRIC_FIN` when the leading letter is `X`, `Y` or `Z`. |
| `IT_FISCAL_CODE` | `` \b[A-Z]{6}\d{2}[A-Z]\d{2}[A-Z]\d{3}[A-Z]\b `` | `ItFiscalCodeRecognizer`: a much longer pattern encoding real calendar-date validity and omocodia (digit/letter substitution) rules within the same 16-character positional structure, score 0.3 | no-match | 0.9 | Verdict: same 16-character positional shape as the real Codice Fiscale structure, but Presidio's pattern additionally validates day/month/checksum plausibility per position, which ours does not. Score — High: despite the missing validation, the 16-fixed-position mixed letter/digit shape is distinctive enough that an accidental false positive from ordinary text is implausible. |
| `IT_DRIVER_LICENSE` | `\b[A-Z]{2}\d{7}\b` | `ItDriverLicenseRecognizer`: `` \b(?i)(([A-Z]{2}\d{7}[A-Z])\|(U1[BCDEFGHLJKMNPRSTUWYXZ0-9]{7}[A-Z]))\b ``, score 0.2 | no-match | 0.2 | Verdict: Presidio requires a trailing check letter after the 7 digits; ours has no trailing letter. Score — Low: two unrestricted letters concatenated directly with a digit run, no delimiter, no checksum — the same shape class as `MEDICAL_LICENSE` one digit longer; `IT_PASSPORT`/`IT_IDENTITY_CARD` below carry the byte-identical regex and move with it. |
| `IT_VAT_CODE` | `\bIT\d{11}\b` | `it_vat_code`: `` \b([0-9][ _]?){11}\b ``, score 0.1 (bare digit run with optional separators; no letter class at all) | no-match | 0.9 | Verdict: Presidio's pattern has no letters in its alphabet at all — a bare digit run with optional separators; ours requires the literal `IT` prefix, which Presidio's pattern cannot match. Not a subset relationship in either direction. Score — High: fixed literal prefix + fixed-length digit body, the rubric's own named example shape (same reasoning as `AWS_ACCESS_KEY`'s `AKIA` prefix). |
| `IT_PASSPORT` | `\b[A-Z]{2}\d{7}\b` | `ItPassportRecognizer`: `(?i)\b[A-Z]{2}\d{7}\b`, score 0.01 | presidio-match | 0.2 | Verdict: identical shape (case-insensitivity aside). Score — Low: same shape class as `IT_DRIVER_LICENSE` above. |
| `IT_IDENTITY_CARD` | `\b[A-Z]{2}\d{7}\b` | `ItIdentityCardRecognizer` — 3 tiers, all 0.01; the "paper-based" tier is `` (?i)\b[A-Z]{2}\s?\d{7}\b `` | presidio-match | 0.2 | Verdict: ours (no space) is a strict subset of the paper-based tier's optional space. Score — Low: same shape class as `IT_DRIVER_LICENSE` above. |
| `PL_PESEL` | `\b\d{11}\b` | `PlPeselRecognizer`: `` [0-9]{2}([02468][1-9]\|[13579][012])(0[1-9]\|1[0-9]\|2[0-9]\|3[01])[0-9]{5} ``, score 0.4 (birth-date-validity encoded into the digit positions) | no-match | 0.2 | Verdict: Presidio's pattern validates that the middle digits decode to a real month/day; ours accepts any 11 digits. Score — Low: bare digit run anchored only by `\b`; the catalog's own javadoc already flags `PL_PESEL` as one of the unreviewed bare-digit-run types alongside `US_SSN`. |
| `SG_NRIC_FIN` | `\b[A-Z]\d{7}[A-Z]\b` | `SgFinRecognizer` — "Nric (weak)": `(?i)(\b[A-Z][0-9]{7}[A-Z]\b)`, score 0.3; "Nric (medium)" restricts the first letter to `[STFGM]`, score 0.5 | presidio-match | 0.6 | Verdict: our unrestricted first letter matches Presidio's "weak" tier exactly, not the restricted "medium" tier. Score — Medium: letter prefix/suffix + digits. |
| `SG_UEN` | `` \b\d{8}[A-Z]\b\|\b\d{9}[A-Z]\b `` | `SgUenRecognizer`: `` \b\d{8}[A-Z]\b\|\b\d{9}[A-Z]\b\|\b[TSR]\d{2}[A-Z]{2}\d{4}[A-Z]\b ``, single pattern, score 0.3 | presidio-match | 0.6 | Verdict: our two alternatives are byte-identical to the first two of Presidio's three. Score — Medium: digit run with a fixed trailing check letter, not a bare run. |
| `AU_ABN` | `\b\d{2} \d{3} \d{3} \d{3}\b` | `AuAbnRecognizer`, "ABN (Medium)": `` \b\d{2}\s\d{3}\s\d{3}\s\d{3}\b ``, score 0.1 (a separate bare-11-digit "ABN (Low)" tier is 0.01) | presidio-match | 0.6 | Verdict: byte-for-byte identical shape (Presidio's `\s` matches the same literal single space). Score — Medium: spaced digit group. |
| `AU_ACN` | `\b\d{3} \d{3} \d{3}\b` | `AuAcnRecognizer`, "ACN (Medium)": `\b\d{3}\s\d{3}\s\d{3}\b`, score 0.1 (bare-9-digit "ACN (Low)" tier is 0.01) | presidio-match | 0.6 | Verdict: identical shape. Score — Medium: spaced digit group. |
| `AU_TFN` | `\b\d{9}\b` | `AuTfnRecognizer` — "TFN (Medium)" (spaced) 0.1, "TFN (Low)" (bare 9 digits) 0.01, plus a mod-11 checksum validator in code | presidio-match | 0.2 | Verdict: bare, unspaced 9-digit shape matches Presidio's own "Low" tier for that exact shape. Score — Low: bare digit run anchored only by `\b` — the rubric's other named exemplar pattern, and the exact collision case documented in `PiiPatternCatalog`'s own javadoc against `US_SSN`. |
| `AU_MEDICARE` | `\b\d{4} \d{5} \d{1}\b` | `AuMedicareRecognizer`, "Medium": `` \b[2-6]\d{3}\s\d{5}\s\d\b ``, score 0.1 (bare-10-digit "Low" tier is 0.01) | no-match | 0.6 | Verdict: Presidio's "Medium" tier restricts the leading digit to 2-6; ours allows any leading digit 0-9 (see §5). Score — Medium: spaced digit group. |
| `IN_PAN` | `\b[A-Z]{5}\d{4}[A-Z]\b` | `InPanRecognizer`, "PAN (Medium)": `` \b([A-Za-z]{5}[0-9]{4}[A-Za-z]{1})\b ``, score 0.1 (a stricter "High" tier constrains the 4th letter to a fixed set, 0.5; a much looser "Low" tier is 0.01) | presidio-match | 0.6 | Verdict: identical shape to Presidio's unrestricted "Medium" tier. Score — Medium: letter prefix + digits + letter. |
| `IN_AADHAAR` | `\b\d{4} \d{4} \d{4}\b` | `InAadhaarRecognizer` — two tiers, both 0.01: bare 12 digits, and `` \b[0-9]{4}[- :][0-9]{4}[- :][0-9]{4}\b `` | presidio-match | 0.6 | Verdict: space separator is a subset of the spaced tier's separator class `[- :]`. Score — Medium: spaced digit group. |
| `IN_VEHICLE_REGISTRATION` | `\b[A-Z]{2}\d{2}[A-Z]{2}\d{4}\b` | `InVehicleRegistrationRecognizer`, "Standard (2)": `` \b[A-Z]{2}\d{2}[A-Z]{1,2}(?!0000)\d{4}\b ``, score 0.5 (9 tiers total, 0.01 to 0.85) | presidio-match | 0.6 | Verdict: ours is the fixed-2-letter case of Presidio's `{1,2}` series-letter range, missing only the `(?!0000)` all-zero-suffix exclusion. Score — Medium: structured letter/digit registration shape, no checksum. |
| `IN_VOTER` | `\b[A-Z]{3}\d{7}\b` | `InVoterRecognizer` — "Pattern 1" (letter + restricted consonant + letter + 7 digits) 0.4; "Pattern 2": `` \b([A-Za-z]){3}([0-9]){7}\b ``, score 0.3 | presidio-match | 0.2 | Verdict: byte-for-byte identical to Presidio's "Pattern 2". Score — Low: three unrestricted letters concatenated directly with a digit run, no delimiter, no checksum — the same shape class as `MEDICAL_LICENSE`/`IT_DRIVER_LICENSE`, one letter position wider. |
| `IN_PASSPORT` | `\b[A-Z]\d{7}\b` | `InPassportRecognizer`: `` \b[A-Z][1-9]\d\s?\d{4}[1-9]\b ``, score 0.1 | no-match | 0.2 | Verdict: Presidio constrains the first and last digit of the run to `[1-9]` and allows an internal optional space; ours has no internal structure. Score — Low: same bare letter-prefix-plus-7-digit shape as `US_DRIVER_LICENSE` above. |
| `FI_PERSONAL_IDENTITY_CODE` | `` \b\d{6}[+A-]\d{3}[A-Z0-9]\b `` (2026-08-31: corrected from the mis-transcribed `` [+-A] `` — see the revision note above; the two now denote the identical 3-character set, just written so the hyphen cannot open a range) | `FiPersonalIdentityCodeRecognizer`, "Medium": `` \b(\d{6})([-+ABCDEFYXWVU])(\d{3})([0123456789ABCDEFHJKLMNPRSTUVWXY])\b ``, score 0.5 | presidio-match | 0.6 | Verdict: same 6-1-3-1 digit/separator/digit/control-char structure; differences (narrower separator alphabet, wider control-char class) are character-class tightness in both directions, not a structural mismatch. Score — Medium: digit run with a genuine (now correctly-implemented) 3-character separator alphabet and a trailing control character — CRITICAL: as originally *compiled* (not as originally *written*), `` [+-A] `` was the range `+` (0x2B) through `A` (0x41), which includes every digit, making the live pattern effectively `` \b\d{10}[A-Z0-9]\b `` — a bare 11-character digit run that redacted ordinary business identifiers at this Medium score. The rubric's Medium banding was correct for the *intended* regex; the *compiled* regex did not match its own row for the life of this feature until this fix. |

## 4. Secret catalog (`SecretPatternCatalog.ALL`, 11 entries)

Presidio ships no secret/credential recognizers at all — its full predefined-recognizer set (confirmed from
`predefined_recognizers/__init__.py`) covers PII entity types (country-specific national IDs, contact info,
financial identifiers) plus a handful of generic technical patterns (email, phone, credit card, crypto address,
date, IBAN, IP, MAC address, URL, UUID). None of those are AWS/GitHub/Slack/Stripe/Google/OpenAI keys, JWTs, or
PEM blocks. So **every one of the 11 entries below is `no-counterpart`** — this is not a gap in the research, it
is a real gap in Presidio's scope (secret-scanning is a different product category, closer to TruffleHog/Gitleaks).
Every score below was always rubric-derived for these 11 (there is nothing to adopt), so §1a's change does not
alter them — they are included for completeness and internal consistency of the checks in §8.

| Type | Our regex | Presidio regex compared | Presidio comparison | Score | Reason |
|---|---|---|---|---|---|
| `PEM_PRIVATE_KEY` | `` -----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY----- `` | none found | no-counterpart | 0.9 | High: a structurally distinctive block marker (rubric's own PEM-block example); an accidental false positive is implausible. |
| `AWS_ACCESS_KEY` | `\bAKIA[0-9A-Z]{16}\b` | none found | no-counterpart | 0.9 | High: fixed literal prefix `AKIA` + fixed 16-char body (rubric's own example). |
| `AWS_SECRET_KEY` | `` (?i)aws.{0,20}?["'][0-9a-zA-Z/+]{40}["'] `` | none found | no-counterpart | 0.6 | Medium, not High: the "prefix" is a loose case-insensitive substring match on `aws` anywhere within 20 characters, not an anchored literal immediately preceding the body — a coincidental nearby mention of "aws" next to an unrelated quoted 40-char base64-ish string is plausible in config/log text. |
| `GITHUB_PAT` | `\bgh[pousr]_[A-Za-z0-9]{36}\b` | none found | no-counterpart | 0.9 | High: fixed literal prefix (`gh` + one of `p/o/u/s/r` + `_`) + fixed 36-char body. |
| `GITHUB_FINE_GRAINED_PAT` | `\bgithub_pat_[A-Za-z0-9_]{22,}\b` | none found | no-counterpart | 0.9 | High: fixed literal prefix `github_pat_`, 11 characters, unlikely to occur outside an actual token. |
| `SLACK_TOKEN` | `\bxox[baprs]-[A-Za-z0-9-]{10,}\b` | none found | no-counterpart | 0.9 | High: fixed literal prefix `xox` + one of `b/a/p/r/s` + `-`. |
| `STRIPE_SECRET_KEY` | `` \b(?:sk_(?:live\|test)_[0-9A-Za-z]{16,}\|[sr]k_live_[0-9a-zA-Z]{24})\b `` | none found | no-counterpart | 0.9 | High: fixed literal prefixes (`sk_live_`/`sk_test_`/`rk_live_`) with bounded-length bodies. |
| `STRIPE_PUBLISHABLE_KEY` | `\bpk_(?:live\|test)_[0-9A-Za-z]{16,}\b` | none found | no-counterpart | 0.9 | High on detection confidence: the regex reliably identifies its shape (`pk_live_`/`pk_test_` prefix). Note this key is *not secret by design* — a policy/verdict question handled elsewhere in the pipeline, not by this score. |
| `GOOGLE_API_KEY` | `\bAIza[0-9A-Za-z_-]{35}\b` | none found | no-counterpart | 0.9 | High: fixed literal prefix `AIza` + fixed 35-char body. |
| `OPENAI_KEY` | `\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b` | none found | no-counterpart | 0.9 | High: literal prefix `sk-` (optionally `sk-proj-`) combined with a 20+-char body makes an accidental match implausible, even though the bare `sk-` prefix alone is short. |
| `JWT` | `` \bey[0-9A-Za-z_-]+\.[0-9A-Za-z_-]+\.[0-9A-Za-z_-]+\b `` | none found | no-counterpart | 0.9 | High: the three-segment, dot-delimited, `ey`-prefixed token shape (base64url of `{"`) is structurally distinctive; unlikely to occur by chance in ordinary text. |

## 5. Where the shape comparison was genuinely ambiguous

These notes are about the **Presidio comparison** column only — since §1a, they no longer affect any score.

- **`UK_NHS`, `IN_AADHAAR`, `SG_UEN`, `AU_ABN`, `AU_ACN`, `IT_PASSPORT`, `IT_IDENTITY_CARD`, `IN_VOTER`, `IN_PAN`,
  `US_SSN`** — our pattern is a strict character-level subset of a Presidio pattern (missing an optional
  separator, a negative lookahead, or one alternative branch of a larger alternation, or — for `US_SSN`
  specifically — byte-identical to two of five patterns that happen to share one score tier), and Presidio
  scores the relevant pattern/tier uniformly regardless of which sub-case matches. Resolved as
  **presidio-match**.
- **`AU_MEDICARE`** was the closest call in the other direction. Its shape (spaced 4-5-1 digit groups) is
  identical to Presidio's "Medium" tier except for Presidio's `[2-6]` leading-digit constraint — a genuine,
  meaningful value-space restriction, not incidental formatting. Resolved as **no-match**, unlike the subset
  cases above where the missing piece was cosmetic (spacing/casing). The distinguishing question used
  throughout: *does the missing piece change which strings match, in a way that matters for false-positive risk,
  or only how they're written?* Formatting differences → presidio-match. Value-space differences → no-match.
- **`US_SSN`** specifically needed correcting during this revision: the first version of this document described
  both of our alternatives as corresponding to Presidio's ~0.05 tiers (which is `presidio-match` reasoning) but
  then labeled the row `differs` (`no-match`) anyway — an internal contradiction. It is `presidio-match`. This
  no longer affects the score (§1a); the score is `0.2` because the pattern's own bare-digit alternative is one
  of the two rubric-exemplar bare-digit-run shapes, independent of what Presidio calls it.
- **`FI_PERSONAL_IDENTITY_CODE`** — the differences (narrower separator alphabet, wider control-character class)
  are character-class tightness in both directions at once, not a one-directional broadening. Resolved as
  **presidio-match** because the structural shape (6-1-3-1 grouping) is identical and neither difference changes
  the fundamental risk profile the way `AU_MEDICARE`'s missing digit-range constraint does.
- **`IT_VAT_CODE`** — resolved as **no-match**: Presidio's pattern has no letter class in its alphabet at all
  (bare digits with optional separators), so it cannot match the literal `IT` prefix ours requires. This is not
  a subset relationship in either direction, just two different shapes for the same conceptual entity.
- **`ES_NIF` / `ES_NIE`** (fixed, 2026-08-31 closing pass Group 2 — no longer open) — this note originally flagged
  a shape mismatch rather than a comparison-ambiguity: our regex had the letter and digits in the reverse order
  from the real Spanish NIF/NIE format (letter-then-digits vs Presidio's digits-then-letter), so the two patterns
  didn't correspond well enough to compare meaningfully, which was itself informative — it meant these two catalog
  entries did not correctly detect real Spanish national IDs, independent of what score they carried. Recorded
  here when it surfaced during this comparison, deliberately left unfixed as out of that task's scope. It has
  since been fixed: both regexes are now digits-first, matching Presidio's field order (§3's rows above), which is
  why both now resolve as **presidio-match** rather than the **no-match** this bullet originally recorded.

## 6. A caveat on how the Presidio text was retrieved

The regex text quoted in the "Presidio regex compared" columns above was not read as raw bytes. It was retrieved
by fetching each recognizer's source file from `microsoft/presidio` and asking a summarizing tool to extract the
pattern(s) and score(s), one recognizer at a time. That is a reasonable way to gather this much source material,
but it means the quoted Presidio regex strings in §3/§4 should be read as **faithful paraphrase, not a verified
byte-exact transcription** — no diff was run against the raw `.py` files for every recognizer. Internal
cross-checks (the same score appearing consistently across independently-fetched files, e.g. several 0.01/0.1/0.5
values recurring in a way that matches Presidio's documented tiering conventions) give reasonable confidence the
transcriptions are accurate, but a reader who needs an exact byte match for some other purpose should re-fetch
the specific file rather than trust the quoted text here as canonical. This caveat does not weaken the verdicts
in §3/§4: the shape comparisons were made carefully against the retrieved text, and — as of §1a — no score in
this document depends on a Presidio number being exactly right, only on whether the general shape corresponds.

## 7. Sources consulted

All Presidio regexes and scores above were retrieved from `microsoft/presidio`, `main` branch, under
`presidio-analyzer/presidio_analyzer/predefined_recognizers/`, accessed 2026-08-25 (see §6 for the retrieval
method and its limits):

- `predefined_recognizers/__init__.py` — the full list of predefined recognizer classes, used to confirm which
  entity types Presidio does and does not cover (in particular, that it has no secret/credential recognizers).
- `predefined_recognizers/generic/{email,phone,credit_card,ip,iban,crypto,date}_recognizer.py`
- `predefined_recognizers/country_specific/us/{medical_license,us_bank,us_driver_license,us_itin,us_passport,us_ssn}_recognizer.py`
- `predefined_recognizers/country_specific/uk/{uk_nhs,uk_nino}_recognizer.py`
- `predefined_recognizers/country_specific/spain/{es_nif,es_nie}_recognizer.py`
- `predefined_recognizers/country_specific/italy/{it_fiscal_code,it_driver_license,it_vat_code,it_passport,it_identity_card}_recognizer.py` (module filename `it_vat_code.py`, not `it_vat_code_recognizer.py`)
- `predefined_recognizers/country_specific/poland/pl_pesel_recognizer.py`
- `predefined_recognizers/country_specific/singapore/{sg_fin,sg_uen}_recognizer.py`
- `predefined_recognizers/country_specific/australia/{au_abn,au_acn,au_tfn,au_medicare}_recognizer.py`
- `predefined_recognizers/country_specific/india/{in_pan,in_aadhaar,in_vehicle_registration,in_voter,in_passport}_recognizer.py`
- `predefined_recognizers/country_specific/finland/fi_personal_identity_code_recognizer.py`

No Presidio documentation site content was used; the source files above were preferred over any secondary
description of "Presidio's default scores," several of which are stale relative to the current recognizer
implementations (the repository has been substantially restructured into the `country_specific/` layout used
here).

## 8. Summary

### 8a. The Presidio-comparison split (shape evidence, not scoring — see §1a)

| | PII (36) | Secrets (11) | Total (47) |
|---|---|---|---|
| **presidio-match** | 23 | 0 | **23 (49%)** |
| **no-match** | 11 | 0 | **11 (23%)** |
| **no-counterpart** | 2 | 11 | **13 (28%)** |

(The `US_SSN` correction in this revision moved one row from `no-match` to `presidio-match` relative to the
first version of this document, which had 20/14/13; see §5. The 2026-08-31 closing pass Group 2 revision note
moved two more — `ES_NIF` and `ES_NIE` — the same direction, once their regex was corrected to the real
digits-first Spanish format and it started matching Presidio's own field order; see that note and §5. Combined,
the two corrections leave PII at **23/11/2**, versus this document's original 20/14/2.)

Restated plainly: the entity-**name** vocabulary is faithfully Presidio's; the entity-**detection** vocabulary
matches Presidio's actual regex for 49% of entries and diverges or has no counterpart for the other 51%. That gap
was never checked before this task, and — independent of it, per §1a — no score in this document was ever going
to be borrowed from Presidio regardless of the outcome, because Presidio's numbers assume context-word boosting
this pipeline does not implement.

### 8b. The score-band split, and the three checks requested before committing

**These totals are a tally of what §3/§4's rows literally print**, including `US_SSN`'s row, which — per this
document's header note — is deliberately left describing the original combined pattern (score `0.2`) rather than
back-ported to the live catalog's `0.6`. A reader wanting the live catalog's own band split (which counts
`US_SSN` at `0.6`, since the regex it now carries really is only the dashed form) should recompute it from
`PiiPatternCatalog.java`/`SecretPatternCatalog.java` directly — `.agents/ai-guardrails.md`'s "Confidence scoring"
section does exactly that, and its numbers differ from this table's by that one row.

| Band | Score | PII | Secrets | Total |
|---|---|---|---|---|
| **High** | 0.9 | 6 | 10 | **16** |
| **Medium** | 0.6 | 18 | 1 | **19** |
| **Low** | 0.2 | 12 | 0 | **12** |

(The `MEDICAL_LICENSE` correction — §3, post Task 5 — moved one row from Medium to Low relative to the first
version of this table, which had PII 27/4 and Total 28/4; see the `MEDICAL_LICENSE` row's Reason cell. The
2026-08-31 final-branch-review sweep — see the revision note at the top of this document — moved nine more rows
the same direction, applying that same correction the rest of the way across the catalog: `US_DRIVER_LICENSE`,
`US_PASSPORT`, `ES_NIF`, `ES_NIE`, `IT_DRIVER_LICENSE`, `IT_PASSPORT`, `IT_IDENTITY_CARD`, `IN_VOTER`,
`IN_PASSPORT`. That sweep left PII at **5/17/14** (High/Medium/Low, counting the frozen `US_SSN` row at `0.2` as
this table always has) and combined at **15/18/14**, versus 5/26/5 and 15/27/5 immediately before it. The
2026-08-31 closing pass (Group 1, this document's second 2026-08-31 revision note) then moved `CREDIT_CARD` from
Medium to High, landing on **6/16/14** PII and **16/17/14** combined. The closing pass Group 2 revision note then
moved `ES_NIF` and `ES_NIE` back out of Low, once their regex was corrected and rescored — the only two of the
Group-1 sweep's nine Low rows that did not stay there — landing on the **6/18/12** PII and **16/19/12** combined
split printed above; every other row from every prior move stayed put.)

Verified by inspection of the committed rows in §3/§4, and by mechanically recounting the score column
(`grep '^| \`' <file> | grep -oE '\| (presidio-match|no-match|no-counterpart) \| [0-9.]+ \|' | awk -F'|'
'{print $3}' | sed 's/ //g' | sort | uniq -c`, run separately against §3's 36 PII rows and §4's 11 secret rows)
after this revision:

1. **Every one of the 47 scores is exactly one of `{0.2, 0.6, 0.9}`.** Confirmed — 16 + 19 + 12 = 47, and no row's
   score cell contains any other value (§9 shows the grep used to check this mechanically).
2. **The two bare-digit patterns this feature exists for are in Low.** `US_BANK_NUMBER` (`\b\d{8,17}\b`) and
   `AU_TFN` (`\b\d{9}\b`) — the two regexes named verbatim in §1's Low rule — both score `0.2`. (`US_SSN` and
   `PL_PESEL` are also Low, for the same bare-digit-run reason, and `MEDICAL_LICENSE` — along with seven of the
   nine rows the 2026-08-31 sweep added (`ES_NIF`/`ES_NIE` did not stay Low; see the closing pass Group 2 revision
   note) — is Low for the closely related bare-*alphanumeric*-run reason: an unrestricted-letter-prefix,
   no-checksum, no-delimiter shape is the same specificity class even though it isn't purely digits; the rubric's
   two *named exemplars* are the first two, and both are correctly placed.)
3. **No row's score is below the Low value.** The lowest score anywhere in §3/§4 is `0.2`; nothing scores lower,
   because no score is copied from Presidio anymore (§1a) — every score is chosen from `{0.2, 0.6, 0.9}` directly.

## 9. Mechanical verification

Run against the committed table rows (lines matching `^| \`` — one per catalog entry) before this document was
committed:

```
$ grep -c '^| `' <file>                                                        → 47
$ grep '^| `' <file> | grep -c '| presidio-match |'                            → 21
$ grep '^| `' <file> | grep -c '| no-match |'                                  → 13
$ grep '^| `' <file> | grep -c '| no-counterpart |'                            → 13
$ grep -oE '\| (presidio-match|no-match|no-counterpart) \| [0-9.]+ \|' <file> \
    | awk -F'|' '{print $3}' | sed 's/ //g' | sort | uniq -c
    5 0.2
   27 0.6
   15 0.9
$ grep -oE '\| (presidio-match|no-match|no-counterpart) \| [0-9.]+ \|' <file> \
    | awk -F'|' '{print $3}' | sed 's/ //g' | grep -vE '^(0\.2|0\.6|0\.9)$'
    (no output — every score is one of the three band values)
$ grep -E '^\| `(US_BANK_NUMBER|AU_TFN)`' <file> \
    | grep -oE '\| (presidio-match|no-match|no-counterpart) \| [0-9.]+ \|'
    | presidio-match | 0.2 |
    | presidio-match | 0.2 |
```

21 + 13 + 13 = 47 (§8a). 4 + 28 + 15 = 47 (§8b). Both bare-digit exemplars score `0.2`. No score falls outside
`{0.2, 0.6, 0.9}`, and therefore none falls below `0.2`. **These counts are as they stood at the moment this
document was first committed**, before the three rounds of 2026-08-31 amendments described in the revision notes
at the top — first the final-branch-review sweep (`5/17/14` PII, `15/18/14` combined), then the closing-pass
Group 1 `CREDIT_CARD` rescore (`6/16/14` PII, `16/17/14` combined), then the closing-pass Group 2 `ES_NIF`/
`ES_NIE` regex correction and rescore (`6/18/12` PII, `16/19/12` combined) — all tallied afresh in §8a/§8b, which
carry the current totals. Re-running the same grep today reproduces the current, amended counts, not the ones
printed above — this section is left as the historical record of the original verification pass, matching how the
rest of this document treats its own history.

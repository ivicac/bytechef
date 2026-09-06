<!-- Extracted from CLAUDE.md so the agent-facing reference does not sit in every prompt.
     Load this when working on the standalone AI guardrails engine, the guardrails advisor, or
     either agent surface (canvas AI Agent, AI Hub). For the AI Gateway's own adapter (project
     overlay, moderation/injection classifiers, HTTP 422 mapping), see
     .agents/ai-gateway-guardrails.md. -->

# AI Guardrails (EE, standalone across surfaces)

Content guardrails (PII/secret redaction, blocked terms, moderation, injection detection,
response/streaming redaction) were extracted out of the AI Gateway into a standalone EE module so
they cover every LLM-calling surface, not just gateway-routed traffic. Spec:
`docs/superpowers/specs/2026-07-31-ai-guardrails-standalone-design.md`.

**2026-08-25 follow-up (ticket 732): the detection core moved to CE.** Before this, the
sensitive-data patterns existed twice — once in the guardrails **component**'s
`PiiDetectorUtils`/`SecretKeyDetectorUtils` (CE, 36-type Presidio-*named* taxonomy — see "Taxonomy
and curated default" below for what that naming claim does and doesn't mean), and separately, more
narrowly, in this EE engine's own regex detector (5 types: `EMAIL`/`SSN`/`CC`/`PHONE`/`IP`). The
paid engine detected *less* than the free component. The detection core — pattern catalogs,
detectors, the redactor, and PII tokenization — is now a single Community Edition module,
`server/libs/platform/platform-ai/platform-ai-sensitive-data/` (`-api` / `-service`), package
`com.bytechef.platform.ai.sensitivedata`. EE keeps everything that has no reason to be free: the
advisor, workspace settings/policy, GraphQL, streaming response redaction, and the optional OpenNLP
detector — all now consuming the CE core instead of maintaining their own copy of it. See
"Taxonomy and curated default" and "Secrets" below for what actually changed in detection coverage,
and `docs/superpowers/specs/2026-08-25-guardrails-consolidation-design.md` for the extraction
design.

## Module and engine

- Detection core (CE): `server/libs/platform/platform-ai/platform-ai-sensitive-data`
  (`-api` / `-service`), package `com.bytechef.platform.ai.sensitivedata`. Carries
  `SensitiveSpan`, `SensitiveKind`, the `SensitiveDataDetector` SPI, the `SensitiveDataMetrics`
  seam (implemented by EE's `AiGuardrailMetrics` — see "Sensitive-data detectors" below),
  `SensitiveDataRedactor`, the regex detectors, `PiiPatternCatalog`, `SecretPatternCatalog`, and the
  `tokenization` package (`PiiToken`, `PiiTokenSession`).
- Advisor/settings/surfaces (EE): `server/ee/libs/platform/platform-ai/platform-ai-guardrails`
  (`-api` / `-service` / `-graphql`, plus the optional `-opennlp` module). `AiGuardrails`,
  `AiGuardrailsAdvisor`, `StreamingResponseRedactor`, `AiGuardrailMetrics`,
  `AiGuardrailsWorkspaceSettings`, and the GraphQL surface all still live here, unchanged in shape —
  they now consume the CE core's types instead of defining their own.
- The engine, `AiGuardrails` (`-service`, package `com.bytechef.ee.platform.ai.guardrails`), is
  `@Component @ConditionalOnEEVersion` and registered **unconditionally** — it does NOT depend on
  `bytechef.ai.gateway.enabled` (default `false`). It operates on plain strings/lists of strings,
  not on any caller's DTO shape:
  - `applyToInputs(inputs, workspaceId)` — the original always-throw contract (kept for the
    gateway adapter's HTTP 422 behavior): redacts PII/secrets inline, throws
    `AiGatewayGuardrailException` on a blocked-term match or a flagged injection.
  - `checkInputs(inputs, workspaceId, metrics)` — non-throwing counterpart used by the advisor:
    returns one `GuardrailCheckResult(text, category)` per input; `category` is `null` unless a
    blocking violation tripped, in which case the offending content is already masked out of
    `text`. This is the ONLY entry point that checks model-based moderation (an optional
    `AiGatewayModerationClassifier` bean, gated on `moderation-enabled` / workspace
    `moderationEnabled`) — `applyToInputs` deliberately never moderates, so the AI Gateway
    adapter (which moderates its own DTO pipeline with its own classifier wiring) is never
    double-moderated. A moderation verdict has no locatable span, so unlike a blocked-term match
    (which masks only the matched term) a moderation downgrade under `REDACT_AND_CONTINUE`
    replaces the WHOLE message with `[REDACTED_MODERATED]`. Injection detection downgrades
    without masking anything beyond the pii/secret redaction already applied (an existing,
    unchanged behavior) — moderation intentionally does not mirror that, since "masking the
    offending content" for a whole-message judgment means masking the whole message. Fails open
    on a classifier error (the classifier's own responsibility, same as injection).
  - `scanResponseText(text, workspaceId)` — response-direction redaction only, never blocks.
  - `newStreamingResponseRedactor(workspaceId)` — returns a `StreamingResponseRedactor` (or
    `null`) when streaming response scanning is active (requires BOTH the workspace/global
    `scanResponses` policy AND the operator flag `response-scan-streaming-enabled`).
  - `resolveBlockingMode(workspaceId)` — the workspace's `BlockingMode`, defaulting to `BLOCK`.
  - `isActive(workspaceId)` — whether any guardrail is active for a workspace once global +
    workspace policy are unioned, including moderation (only counted active when a moderation
    classifier bean is present); callers use this to skip attaching an advisor entirely.
  - `redactPii` / `redactSecrets` / `redactAll` are instance methods (no longer static — see
    "Sensitive-data detectors" below for why) reused by the gateway adapter's project overlay and by
    `scanResponseText`/`StreamingResponseRedactor` for response-direction and streaming redaction.
    The old `sensitiveMatchRanges` helper is gone, superseded by `SensitiveDataRedactor`'s span-based
    detect/resolve/apply pipeline.
- Redaction always runs before the blocked-term check and injection detection, so those checks see
  already-redacted text. Regexes avoid nested optional quantifiers (ReDoS-safe).
- Effective policy per call = union of global `bytechef.ai.gateway.guardrails.*` properties
  (property names kept for compatibility — the AI Gateway is still the sole reader/writer of these
  keys) and the call's workspace `AiGuardrailsWorkspaceSettings` — additive: a level can enable a
  guardrail or add blocked terms, never turn one off.

## Sensitive-data detectors

- PII/secret redaction, wherever it runs (request-direction, response-direction, or the streaming
  path), is a **detect → resolve → apply** pipeline over the original text — not a chain of
  `replaceAll` calls in which each pattern rewrote the text the next pattern was about to scan. The
  old chain (`redactAll` = `redactSecrets(redactPii(x))`) leaked part of any secret whose body
  contained a credit-card-shaped digit run: the PII pass claimed the digits first and destroyed the
  text the secret pattern needed, so `xoxb-1234567890123456-abcdef` came out as
  `xoxb-[REDACTED_CC]-abcdef` (`CC`, before this rename — see "Taxonomy and curated default" above;
  under current terminology this would be `[REDACTED_CREDIT_CARD]`) — the token's prefix and suffix
  intact, disclosing that a Slack credential was present. Detecting against the untouched original
  and resolving overlaps centrally fixes it: the same input now redacts to `[REDACTED_SECRET]`.
- The SPI is `SensitiveDataDetector` (CE, `platform-ai-sensitive-data-api`, package
  `com.bytechef.platform.ai.sensitivedata`, still a zero-dependency module — not even Spring is on
  its compile path): `name()`, `detect(String text) -> List<SensitiveSpan>`, and
  `default streamSafe() -> true`. Contributing a detector is a bean (EE detectors stay
  `@Component @ConditionalOnEEVersion` — no engine change) collected by `SensitiveDataRedactor` (CE,
  `-service`), which runs each over the WHOLE original text and never another detector's output (so
  no detector can corrupt another's result), and resolves the combined candidate spans into a
  non-overlapping accepted set before applying placeholders right-to-left. The two built-ins are
  `RegexPiiDetector` (kind `PII`, patterns from `PiiPatternCatalog.curatedDefault()` — see "Taxonomy
  and curated default" below) and `RegexSecretDetector` (kind `SECRET`, patterns from
  `SecretPatternCatalog.ALL` minus `STRIPE_PUBLISHABLE_KEY` — see "Secrets" below).
  `RegexPiiDetector` was renamed from `PresidioRegexPiiDetector` on 2026-08-31: its old name asserted
  a Presidio origin nobody had verified, and the comparison in
  `docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md` found regex-level
  correspondence for only 21 of 47 catalog entries (45%) — see "Taxonomy and curated default" below.
  `RegexPiiDetector` reports each match under its own entity type as
  `SensitiveSpan.category()`, so the model/operator can tell an email from an SSN; `RegexSecretDetector`
  collapses everything to the single category `SECRET` instead, deliberately — see "Secrets" below for
  why. Both are CE classes (`platform-ai-sensitive-data-service`) but still gated
  `@Component @ConditionalOnEEVersion` for registration — the detection *code* is CE, but nothing runs
  it on a CE-only deployment today; that split is a licensing decision, not a technical one, and could
  change independently of where the code lives.
- **Resolution order is total, and independent of registration order**: SECRET spans before PII, then
  longer before shorter, then earlier before later, then category ascending — candidates are taken
  greedily in that order and a candidate overlapping an already-accepted span is dropped. Because the
  order is a property of the spans themselves (kind, length, offset, category) and never of which
  detector reported them or in what sequence detector beans happen to be registered, contributing a
  new detector cannot change how an existing detector's spans resolve. This is the property that
  makes the SPI safe to extend.
- `SensitiveSpan.kind()` (`PII`/`SECRET`) is closed — it is exactly the axis the `redactPii` /
  `redactSecrets` policy toggles govern, and a redaction call always names which kinds it wants
  filtered. `SensitiveSpan.category()` is open (a validated uppercase identifier) and drives
  presentation only: `SensitiveSpan.placeholder()` derives `[REDACTED_<category>]` with no lookup
  table, so a detector can introduce a new entity type (e.g. `PERSON`) without touching this module.
  The kind filter is applied to the CANDIDATES, before resolution — filtering the accepted set instead
  would let a span the caller did not ask for still win an overlap and then be discarded, so a
  PII-only redaction over a secret containing a digit run would return the text unredacted.
- `streamSafe()` exists because the streaming response path can only ever offer a detector a bounded
  lookahead window, not the whole document. A detector needing wider context than that (sentence-level
  NER, say) must declare `streamSafe() = false`; `SensitiveDataRedactor.streamSafeView()` (resolved
  once, at `AiGuardrails` construction) then excludes it from the streaming redactor entirely and logs
  the exclusion once, rather than feeding it a mid-sentence fragment and letting it silently produce a
  worse answer than it would give over the complete text — a detector that cannot honestly cover a
  stream is visibly absent from it, not silently contributing nothing usable. Both built-in regex
  detectors are local, so today's stream-safe set is unchanged.
- **Failure is fail-open, per detector.** A detector that throws (or reports a span past the end of
  the text) is caught, WARN-logged, counted as the `detector_failed` metric event, and skipped for
  that call — the remaining detectors still run, so one flaky detector cannot take down every AI
  surface. See Metrics below for which instance each call path counts that event through.
- **Optional detector: `platform-ai-guardrails-opennlp`** (EE, package
  `com.bytechef.ee.platform.ai.guardrails.opennlp`) plugs Apache OpenNLP named-entity recognition
  into the SPI to cover what the regex detectors above cannot — unstructured PII: person names,
  organizations, locations. It is a bean like any other detector (no engine change) and is **off by
  default**. **It ships no models, and Apache distributes none**: OpenNLP's Maven Central and
  models page carry sentence/tokenizer/POS models but zero English NER artifacts, and the only
  English NER models that exist are the legacy SourceForge 1.5 binaries — English-only,
  newswire-trained, roughly fifteen years old. This module is not a feature an operator merely
  switches on; it is inert until they supply their own compatible models (realistically ones
  trained on their own corpus, which is also the case where accuracy is adequate for destructive
  redaction). Anyone reaching for the legacy 1.5 models should understand they are pointing a
  fifteen-year-old newswire model at chat and code text, in a redaction path that rewrites prompts
  irreversibly before the LLM ever sees them. Configuration:
  ```yaml
  bytechef:
    ai:
      guardrails:
        opennlp:
          enabled: false
          tokenizer-model:                 # optional Resource; SimpleTokenizer when unset
          min-confidence: 0.85
          entity-models:                   # empty by default
            PERSON: file:/opt/bytechef/models/en-ner-person.bin
            ORGANIZATION: classpath:models/en-ner-organization.bin
  ```
  The bean registers only when BOTH `enabled=true` and `entity-models` is non-empty
  (`OpenNlpGuardrailsConfiguration`) — an enabled-but-empty configuration contributes nothing
  rather than a detector the engine would call on every request for no gain. `entity-models` keys
  ARE `SensitiveSpan` categories, not a separate vocabulary: a `PERSON` key produces
  `[REDACTED_PERSON]` through the same `placeholder()` mechanism described above, with no mapping
  table anywhere. Models are loaded **eagerly**, in the detector's constructor: a missing,
  unreadable, or corrupt model fails application startup rather than being caught later by the
  fail-open policy above — deliberately, because a lazily-loaded broken model would be caught,
  counted, and skipped, handing the operator a guardrail that silently protects nothing (a typo in
  a model path should fail loudly, not turn into silent non-coverage). `streamSafe()` returns
  **`false`**: NER over a bounded lookahead window that starts mid-sentence gives different, worse
  answers than over the whole text, so **streamed completions get regex redaction only** — batch
  response scanning and all request-direction scanning still cover NER. `min-confidence` (default
  `0.85`, `NameFinderME`'s per-span probability) is the only false-positive control on this path,
  and it carries more weight than a tuning knob normally would: redaction here is destructive and
  pre-model, so a spurious `PERSON` hit on "Claude", "Stripe", or "Redis" in a developer's prompt
  silently corrupts the text the model receives, with no signal to the user — exactly the input
  distribution a newswire-trained model produces on chat and code text. Every span this detector
  reports is `SensitiveKind.PII`, so it is gated by the same workspace `redactPii` toggle (or the
  gateway's `pii-redaction-enabled` property) as the regex PII detector — an operator who enables
  this module and configures valid models but leaves `redactPii` off gets zero redaction from it,
  silently; the detector's own startup log only confirms what it loaded, not that it is doing
  anything. And unlike the regex detectors, NER here is not a linear scan: `NameFinderME` runs
  beam-search sequence decoding over the entire input, synchronously, on the request path, before
  the LLM call, with no input-size bound anywhere in this module — materially more expensive than
  the regex detectors, and the engine's fail-open policy does not help, because a slow detector
  never throws. Finally, the same keys are
  mirrored on `ApplicationProperties.Ai.Guardrails.OpenNlp` (`server/libs/config/app-config`) — not
  redundancy: `ApplicationProperties` binds all of `bytechef.*` with `ignoreUnknownFields = false`,
  so an operator-set key with no field there fails EVERY app's context, including apps that do not
  carry this optional module. This is the trap for whoever adds the next optional module with
  operator-settable properties: a standalone `@ConfigurationProperties` class is fine on its own
  only as long as nothing ever sets its keys; the moment an operator can set `enabled: true`, the
  keys become present-in-a-property-source and strict binding needs a field for them somewhere every
  app still loads, module or no module.

## Taxonomy and curated default

`PiiPatternCatalog` (CE, `platform-ai-sensitive-data-service`) is the single source of PII patterns.

**This codebase does not use Presidio — no dependency, no HTTP client, no service call, anywhere.**
Every surviving occurrence of the word "Presidio" here (this catalog's "Presidio taxonomy" framing,
some test names) reflects only that the entity-name vocabulary (`EMAIL_ADDRESS`, `US_SSN`,
`UK_NINO`, ...) matches Microsoft Presidio's almost universally — it does not mean the detection
*logic* comes from Presidio. The detector that ran `curatedDefault()` was itself named
`PresidioRegexPiiDetector` until 2026-08-31, when it was renamed to `RegexPiiDetector` for the same
reason (see "Sensitive-data detectors" above). A pattern-by-pattern comparison against
Presidio's published recognizers, recorded in
`docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md` §3/§4 (counts confirmed by
`grep '^| \`' <file> | grep -c '| presidio-match |'` and the equivalent for `no-match`/
`no-counterpart`), found regex-level correspondence for a minority of the 47 catalog entries: 21
(45%) match Presidio's published regex for the same entity; 13 (28%) have a Presidio counterpart
with different, usually
tighter, constraints (`no-match`); and 13 (28%) — all 11 secret patterns plus `PHONE_NUMBER` and
`LOCATION` — have no Presidio counterpart at all (`no-counterpart`), because Presidio does no
secret-scanning and detects those two PII types via NER rather than a published regex. So the
"Presidio taxonomy" claim is true of the *names*; it was never true of the *regexes*, and — per that
document's §1a — it is not true of the *scores* either (see "Confidence scoring" below).

`PiiPatternCatalog.ALL` holds **36** typed patterns (`EMAIL_ADDRESS`, `PHONE_NUMBER`, `CREDIT_CARD`,
`IP_ADDRESS`, `IBAN_CODE`, `CRYPTO`, `DATE_TIME`, `LOCATION`, `MEDICAL_LICENSE`, plus per-country
types for the US, UK, Spain, Italy, Poland, Singapore, Australia, India, and Finland). This is
exactly the taxonomy the guardrails component's `PiiDetectorUtils` used to own by itself — copied
verbatim into the catalog, not narrowed — with the naming-not-logic caveat above.

- **`PiiPatternCatalog.curatedDefault()`** returns **34**: the full catalog minus `CONTEXTUAL_TYPES`
  (`DATE_TIME`, `LOCATION`) — now the *only* exclusion set. The two contextual types are excluded
  because a date or a street address doesn't by itself name a person, and masking every date and
  address in every prompt would cripple the model's ability to reason about schedules and places, for
  a privacy benefit that isn't there (a bare `LOCATION` or `DATE_TIME` match carries no identity on
  its own). Both stay in `ALL` for the component's per-node picker, where a workflow author who
  genuinely wants them masked can opt in explicitly for that one node.
  - **`US_BANK_NUMBER` is back in `curatedDefault()` by name — it was never removed from `ALL`.**
    It used to be the sole member of a second exclusion set, `LOW_SPECIFICITY_TYPES`, built for
    exactly one pattern: `\b\d{8,17}\b` matches essentially any long digit run, so left in
    unconditionally it tokenized order numbers, invoice numbers and ticket numbers, not just bank
    account numbers. `LOW_SPECIFICITY_TYPES` is **deleted**. What replaced it, and what it resolves —
    including the open question this bullet used to raise here about `US_SSN`, `AU_TFN`, `PL_PESEL`,
    `MEDICAL_LICENSE`, `UK_NHS`, `AU_ACN`, `AU_MEDICARE` and `IN_AADHAAR`, and the `US_SSN`/`AU_TFN`
    collision on `987654321` — is "Confidence scoring" below.
- **The component's picker selects from `ALL`, not `curatedDefault()`.**
  `PiiEntityOptions.getPiiDetectionOptions()` (guardrails module,
  `com.bytechef.component.ai.agent.guardrails.util`) exposes every one of the 36 types (including
  `DATE_TIME`/`LOCATION`/`US_BANK_NUMBER`) as options in the per-node PII-detection picker, pairing each
  `PiiPatternCatalog.ALL` type with a label from `PiiPatternLabels` (CE, `platform-ai-sensitive-data-service`,
  beside the catalog itself — the same class the AI Text Mask picker uses). The type list is still
  hand-maintained (not derived from the catalog) because its order also fixes the generated definition
  JSON for the `pii`/`llm-pii` components; `PiiEntityOptionsTest#testPickerOptionsMatchCatalogTypesExactly`
  pins that the two sets stay equal. `PiiDetectorUtils`, which used to own this picker, is deleted — PII
  detection for `pii`/`llm-pii` now runs directly on the platform engine (`SensitiveDataRedactor` over
  `RegexPiiDetector`), not through a component-owned detector class.
- **This replaces a narrower EE-only detector — of the same eventual name.** Before this extraction,
  the EE engine had its own `RegexPiiDetector` covering only 5 types (`EMAIL`, `SSN`, `CC`, `PHONE`,
  `IP`) — a paid engine detecting less than the free component. That class is deleted; the CE
  detector that runs `curatedDefault()` now (36-type catalog, 34-type curated default) was named
  `PresidioRegexPiiDetector` at first and renamed to `RegexPiiDetector` on 2026-08-31 (see
  "Sensitive-data detectors" above) — so the name `RegexPiiDetector` in this codebase's history
  refers to two different, non-overlapping classes: the deleted 5-type EE one, then the current
  36/34-type CE one. Platform-wide PII coverage on the advisor/gateway paths went from 5 types to 34
  (33 before the `US_BANK_NUMBER` exclusion was replaced by confidence scoring — see "Confidence
  scoring" below).
- **Category names changed.** The old 5-type detector used short category names (`EMAIL`, `SSN`,
  `CC`, `PHONE`, `IP`); the Presidio taxonomy's names are more specific: `EMAIL`→`EMAIL_ADDRESS`,
  `IP`→`IP_ADDRESS`, `CC`→`CREDIT_CARD`, `PHONE`→`PHONE_NUMBER`, `SSN`→`US_SSN`. Since
  `SensitiveSpan.placeholder()` derives `[REDACTED_<category>]` and `PiiToken`'s token format embeds
  the category verbatim (see "PII tokenization" below), this rename is visible everywhere a category
  name reaches an example string: `[REDACTED_EMAIL]` is now `[REDACTED_EMAIL_ADDRESS]`, and
  `[PII_EMAIL_1_k3n9]` is now `[PII_EMAIL_ADDRESS_1_k3n9]`.

## Confidence scoring

**2026-08-31 follow-up (ticket 732): every catalog pattern carries a confidence score, and a
default threshold now filters weak matches before redaction.** Before this, `curatedDefault()`
membership was the only lever: a pattern was either in the default or it wasn't, so a shape as weak as
`US_BANK_NUMBER`'s bare `\b\d{8,17}\b` needed its own hard-exclusion set (`LOW_SPECIFICITY_TYPES`,
now **deleted**) while structurally identical shapes still in the default — `US_SSN`, `AU_TFN`,
`PL_PESEL`, `MEDICAL_LICENSE`, `UK_NHS`, `AU_ACN`, `AU_MEDICARE`, `IN_AADHAAR` — kept firing on
ordinary business identifiers with no way to suppress them short of inventing another exclusion set
per pattern. Scoring replaces set membership as the mechanism: `PiiPatternCatalog.PiiPattern#score()`
and `SecretPatternCatalog.SecretPattern#score()` record, per pattern, how specific its own shape is —
always one of three bands, `0.2`/`0.6`/`0.9` (low/medium/high), per the rubric in
`docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md` §1 — and
`SensitiveDataRedactor#filterByConfidence` drops any candidate span scoring below
`SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE` (**`0.4`**, verified: `grep DEFAULT_MIN_CONFIDENCE
SensitiveDataRedactor.java`). Verified against `PiiPatternCatalog.java`/`SecretPatternCatalog.java` at
HEAD (post Group 1's `CREDIT_CARD` rescore below): 36 PII + 11 secret patterns = 47 total; band split
(low/medium/high) is **13/17/6** for PII and **0/1/10** for secrets — combined **13/18/16**. Reproduced
by parsing every `new PiiPattern("TYPE", ..., score)`/`new SecretPattern("TYPE", ..., score)` call out
of both catalog source files and tallying the trailing score literal per file — the same method used to
derive every count in this section; `curatedDefault()`'s own split (34 entries, `DATE_TIME`/`LOCATION`
excluded) is **13/15/6**.

- **2026-08-31 final-branch-review fixes: one CRITICAL regex bug and a sweep of the scoring rule the rest
  of the way across the catalog.** `FI_PERSONAL_IDENTITY_CODE`'s century-marker class was written
  `[+-A]`, an unintended character-class *range* (`+` through `A`, which includes every digit) rather
  than the literal 3-character set `{+, -, A}` it was meant to be — the pattern was effectively
  `\b\d{10}[A-Z0-9]\b`, a bare 11-digit run, and it redacted arbitrary 11-digit business identifiers at
  its Medium score for the life of this feature until fixed (regex only; the band stays Medium once the
  class means what it says). Separately, `CREDIT_CARD`'s three `[-\s]?` separators were optional, so it
  degenerated to a bare `\b\d{16}\b` run — the identical trap already fixed on `PHONE_NUMBER` — and is now
  fixed the same way (mandatory separators). And nine more patterns — `US_DRIVER_LICENSE`,
  `US_PASSPORT`, `ES_NIF`, `ES_NIE`, `IT_DRIVER_LICENSE`, `IT_PASSPORT`, `IT_IDENTITY_CARD`, `IN_VOTER`,
  `IN_PASSPORT` — were rescored Low, being the identical "unrestricted-letter-prefix directly against a
  digit run, no delimiter, no checksum, no bookending suffix" shape `MEDICAL_LICENSE` was already
  corrected for; that correction had only ever been applied to the one entry a failing acceptance test
  happened to surface. See `PiiPatternCatalog`'s class javadoc and
  `docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md`'s 2026-08-31 revision note for
  the full account, including which similarly-shaped patterns (`UK_NINO`, `SG_NRIC_FIN`, `SG_UEN`,
  `IN_PAN`, `IN_VEHICLE_REGISTRATION`) were considered and deliberately left at Medium.

- **2026-08-31 closing pass: `CREDIT_CARD` gains a Luhn checksum and moves to High, reversing spec
  decision D10 for this one type.** The mandatory-separator fix above closed the degenerate-match trap
  but, as a side effect, regressed coverage of an unformatted card number — `4532015112830366` fell
  through to `US_BANK_NUMBER` (Low) and was suppressed, worse than before this feature shipped. Rather
  than accept that regression, `PiiPatternCatalog.PiiPattern` gained a fourth, optional field —
  `validator`, a `Predicate<String>` checked against the matched text after the regex matches and before
  a span is emitted — with `CREDIT_CARD` the only entry supplying one (a standard Luhn check) and every
  other entry passing `null`. `RegexPiiDetector` applies whatever validator a pattern carries
  generically, with no `if (type.equals("CREDIT_CARD"))` branch, so the eventual national-identifier
  checksum project (still deferred, still out of scope — see the design spec's D10) has a shape to
  extend rather than a special case to work around. `CREDIT_CARD`'s separators are optional again
  (matching both formatted and bare 16-digit numbers), and it rescores High (`0.9`): a Luhn-valid match
  is no longer merely a plausible shape but a checksum-verified one. See `PiiPatternCatalog`'s class
  javadoc and the design spec's decision table (D10) for the full account.

- **The filter runs on candidates, after detection and before resolution** — the identical placement
  rule `filterByKind` already followed, for the identical reason: a low-confidence span that
  resolution would otherwise let win an overlap must not be able to consume that overlap and then be
  discarded, which would leave a stronger overlapping span unredacted. `detectCandidates` itself stays
  unfiltered, so the streaming safe-cut still protects a low-confidence match from being split across
  chunks even though it will never end up redacted.
- **Confidence is not sensitivity, and the two get re-conflated easily — this is the distinction most
  likely to be lost later.** A pattern's score answers "how likely is a match of this exact shape to
  actually be this entity type," not "does this entity type matter if it's real." `CONTEXTUAL_TYPES`
  (`DATE_TIME`, `LOCATION`) are reliably *detected* — their regexes rarely misfire on non-dates/
  non-addresses — but stay excluded from `curatedDefault()` entirely, by set membership, because the
  entity itself isn't identifying on its own; no confidence score fixes that, since their high hit
  rate against real dates/addresses is the problem, not their specificity. Conversely,
  `MEDICAL_LICENSE`, `US_BANK_NUMBER`, `PL_PESEL`, `AU_TFN`, and (since the 2026-08-31 sweep)
  `US_DRIVER_LICENSE`, `US_PASSPORT`, `ES_NIF`, `ES_NIE`, `IT_DRIVER_LICENSE`, `IT_PASSPORT`,
  `IT_IDENTITY_CARD`, `IN_VOTER`, and `IN_PASSPORT` are exactly the identity/financial data this feature
  exists to catch when a match is real — they score Low (`0.2`) purely because their regex (a bare
  `\b\d{9}\b`-shaped digit run, or an unrestricted-letter-prefix run directly against a digit run) is
  weak *evidence* that a given match genuinely is that entity, never evidence that the entity wouldn't
  matter if it were.
  `CONTEXTUAL_TYPES` stays a hard exclusion from `curatedDefault()` for the first reason; confidence
  scoring governs every remaining pattern for the second.
- **The eight-type open question (`2026-08-25-guardrails-consolidation-design.md` §5a) is resolved:
  none of the eight move.** The question was whether `US_SSN`, `AU_TFN`, `PL_PESEL`,
  `MEDICAL_LICENSE`, `UK_NHS`, `AU_ACN`,
  `AU_MEDICARE`, and `IN_AADHAAR` should join `US_BANK_NUMBER` in a hard-exclusion set. They don't,
  because that binary choice no longer exists: `MEDICAL_LICENSE`, `US_BANK_NUMBER`, `PL_PESEL`, and
  `AU_TFN` score Low (below the `0.4` default) and are suppressed at the platform default while
  staying in `curatedDefault()` by name; `US_SSN`, `UK_NHS`, `AU_ACN`, `AU_MEDICARE`, and `IN_AADHAAR`
  score Medium (`0.6`) because a dashed or spaced digit group carries more structure than a bare run.
- **The `US_SSN`/`AU_TFN` mislabelling on `987654321` is closed structurally, and the fix reduces
  coverage — say that plainly, don't call it coverage-preserving.** `US_SSN` used to carry a second,
  bare-9-digit alternative (`` \b\d{3}-\d{2}-\d{4}\b|\b\d{9}\b ``) byte-identical to `AU_TFN`'s
  `\b\d{9}\b`, so a plain business identifier (e.g. a 9-digit support-ticket number) matched both, and
  `SensitiveDataRedactor`'s tie-break picked `AU_TFN`, mislabelling it an Australian Tax File Number.
  Confidence scoring can't fix that by itself — a pattern has one score, and the dashed and bare-digit
  branches don't share a specificity — so the bare-digit alternative was dropped from `US_SSN`
  instead: `AU_TFN` already carries the identical `\b\d{9}\b` pattern, so the same input still
  *matches* (as `AU_TFN`) at the regex layer. **That is where an earlier version of this note stopped,
  and stopping there is wrong**: `AU_TFN` scores Low (`0.2`), below `DEFAULT_MIN_CONFIDENCE`, so
  `SSN 123456789` is dropped before resolution and redacted by nothing. Before this feature every span
  scored `1.0` unfiltered, so that exact input **was** redacted (mislabelled, but redacted); today it
  isn't, anywhere. That is an intended coverage reduction the confidence rubric produces for a bare
  9-digit run, the same tradeoff already accepted for `MEDICAL_LICENSE`, `PL_PESEL` and
  `US_BANK_NUMBER` — not "detection is unchanged." `US_SSN` now matches only the dashed form and
  scores Medium (`0.6`), not the Low (`0.2`) its former bare-digit branch would have earned. See
  `PiiPatternCatalog`'s class javadoc for the full account. `docs/superpowers/specs/2026-08-25-sensitive-data-confidence-scores.md`
  is a frozen provenance record from before this fix and still describes the old combined pattern for
  `US_SSN` — a documented, deliberate divergence (see that document's own header note), not a bug.
- **Workspace override.** `AiGuardrailsWorkspaceSettings.minConfidence` (nullable `Double`, EE) is an
  *override*, not a union member like the other policy fields (`AiGuardrails#resolvePolicy`): a
  workspace either sets its own threshold or it doesn't, since there's no global property counterpart
  to combine it with. `null` means "use `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE`", so every
  workspace that predates this field keeps behaving exactly as before, no migration required. GraphQL
  (`ai-guardrails-workspace-settings.graphqls`) exposes `minConfidence: Float` on both
  `AiGuardrailsWorkspaceSettings` and `AiGuardrailsWorkspaceSettingsInput` — confirmed present in that
  file and absent from `client/src/shared/middleware/graphql.ts` and every `.graphql` operation file
  under `client/src/graphql/`. **That gap is deliberate, not forgotten**: a settings-page UI for the
  threshold is an explicit non-goal of the scoring design (the default is what fixes the reported
  problem; tuning is the long tail, reachable through the API) — see
  `2026-08-25-sensitive-data-confidence-scoring-design.md` §10. Whoever builds that UI later will need
  to add the field to a `.graphql` operation file and rerun `npx graphql-codegen` (see "GraphQL
  Development Workflow" in `CLAUDE.md`) — it is not enough that the schema already carries it.
- **New metric: `below_confidence_threshold`.** Recorded through the same `SensitiveDataMetrics` seam
  as `detector_failed` (see Metrics below) — at most once per call regardless of how many candidates
  were dropped — and follows the identical per-surface split already described there for
  `detector_failed`.
- **OpenNLP composes two thresholds in sequence, not one — a real behavior change for existing OpenNLP
  users.** A span from `OpenNlpSensitiveDataDetector` has already cleared its own `minConfidence` (a
  *model noise floor* — "is this span real?" — `NameFinderME`'s per-span probability, default `0.85`)
  before it ever reaches `SensitiveDataRedactor`, which applies its own, independent `minConfidence` (a
  *policy floor* — "is this type of match worth acting on?"). A span must clear both; the pipeline
  cannot rescue a span the detector already discarded, no matter how low the policy floor is set. An
  operator who previously tuned only the detector's own `minConfidence` may now see fewer redactions,
  because a span that used to reach the output unconditionally can still be dropped by the pipeline's
  own floor. See `SensitiveDataRedactorTest#testSpansSurvivingTheModelFloorStillFaceThePipelineThreshold`
  (CE) for the composition this documents, and `OpenNlpSensitiveDataDetector`'s class javadoc for the
  "two thresholds in sequence" account in full.

## Secrets

`SecretPatternCatalog` (CE, `platform-ai-sensitive-data-service`) is now the single source of
secret-key/API-credential patterns, reconciled from what were two independently maintained lists —
the guardrails component's `SecretKeyDetectorUtils` (named-provider patterns, its own per-type
reporting) and the EE engine's `RegexSecretDetector` (which collapsed every match to one category).
See the class javadoc for the pattern-by-pattern reconciliation notes (which side's regex was kept
and why, for every pair that wasn't byte-identical between the two original lists).

- **`RegexSecretDetector` still collapses every match to the single category `SECRET`, deliberately**
  — a secret never tokenizes and never round-trips (see "PII tokenization" below), so its category
  only ever appears inside the placeholder `[REDACTED_SECRET]`, never in a token a model has to
  reason about. Naming the provider in the placeholder would itself disclose which service a leaked
  credential belonged to. The component keeps its own named-type reporting (`SecretMatch::type`) for
  its per-node output — that behavior is unchanged by the consolidation.
- **The Stripe entry was split.** The two original lists disagreed on scope for Stripe keys with no
  clean superset relationship: the component's pattern covered both `sk_`/`pk_`, live and test mode;
  the platform's covered only `sk_`/`rk_`, live mode. A naive union would have made the platform
  detector redact `pk_` (publishable) keys for the first time — and a Stripe publishable key is public
  by design, meant to be embedded in client-side code, not a secret. The catalog now carries two
  entries, `STRIPE_SECRET_KEY` (`sk_`/`rk_`, genuinely secret) and `STRIPE_PUBLISHABLE_KEY` (`pk_`,
  public). `RegexSecretDetector` consumes only `STRIPE_SECRET_KEY`.
  - **Behavior change**: a Stripe `pk_` publishable key is no longer redacted by the platform
    detector — it wasn't a secret and shouldn't have been treated like one, but this is a real change
    in what gets masked for anyone who was relying on the old union behavior.
  - The component still detects both split entries but folds them back into its original single
    `STRIPE_KEY` type via `SecretKeyDetectorUtils.CATALOG_TYPE_OVERRIDES`, so the component's own
    observable behavior (`SecretMatch::type`) is unchanged by the split.
- **Duplication is gone, and the PII side went further.** At this extraction, both `PiiDetectorUtils`
  and `SecretKeyDetectorUtils` (the guardrails component) stopped carrying their own pattern copies and
  read `PiiPatternCatalog`/`SecretPatternCatalog` instead, while keeping their own matching loops,
  per-node picker, and public APIs (`PiiMatch`/`SecretMatch`) unchanged. `PiiDetectorUtils` has since
  been deleted entirely (see "The component's picker selects from `ALL`" above): the `pii`/`llm-pii`
  components now call the platform engine (`SensitiveDataRedactor`/`RegexPiiDetector`) directly rather
  than through a component-owned detector, and the picker moved to `PiiEntityOptions`. Secret-key
  detection did not follow — `SecretKeyDetectorUtils` still owns its own matching loop and `SecretMatch`
  API today, deliberately: `RegexSecretDetector` (the platform engine's secret detector) is a strict
  subset of it, collapsing every match to one category and carrying no per-provider distinction (see
  "Secrets" above). The pattern list — PII and secret alike — exists exactly once in the codebase
  either way.

## PII tokenization

**This is a posture change, not a tuning knob.** A workspace that previously had a PII value
*destroyed* on the way to the model (`[REDACTED_EMAIL_ADDRESS]`, indistinguishable from any other
email in the same prompt) now has it *restored* on the way back, verbatim, once the completion
returns.
**Secrets are unaffected and stay destroyed** — `SensitiveKind.SECRET` spans are never tokenized,
on any path, regardless of whether a session is present (`SensitiveDataRedactor.tokenizeWithSpans`
keeps the `[REDACTED_SECRET]` placeholder for that kind; only `PII` spans mint tokens). There is no
per-guardrail toggle for this: tokenize-vs-redact is decided entirely by whether the caller threads
a `PiiTokenSession` into the call, not by workspace policy.

**One undocumented consequence of that kind-based split**: with `redactSecrets` off and `redactPii`
on, a credential that happens to contain a PII-shaped digit run (e.g. a numeric string a detector
reads as an SSN or phone number) is tokenized and restored like ordinary PII rather than destroyed —
dispatch in `redactPiiAndSecrets` treats a span as PII or SECRET, never both, so with secret
redaction disabled a value that is *actually* a credential gets whichever treatment its PII-shaped
reading wins. The restored value only ever returns to the caller who sent it in the first place, so
the posture is arguably unchanged in aggregate — but it is a direct, previously-undocumented
consequence of the PII/secret boundary being kind-based rather than "secret wins ties."

- **Token format**: `[PII_<CATEGORY>_<ordinal>_<sessionId>]`, e.g. `[PII_EMAIL_ADDRESS_1_k3n9]`
  (`PiiToken`, CE, `platform-ai-sensitive-data-service`, package
  `com.bytechef.platform.ai.sensitivedata.tokenization`). `CATEGORY` is the `SensitiveSpan` category
  (`EMAIL_ADDRESS`, `US_SSN`, etc. — see "Taxonomy and curated default" above for the full Presidio
  taxonomy and the old short names it replaced) so the model reasons about what kind of value it's
  holding, not an opaque blob. `ordinal` is 1-based and distinct per *value*
  within one session — two different email addresses in the same prompt get `_1_`/`_2_`, so the
  model can tell "forward bob's note to alice" apart instead of both collapsing into the same
  placeholder; a value repeated in the prompt reuses its first ordinal (`PiiTokenSession#tokenFor`
  is `computeIfAbsent`-keyed on the value). `sessionId` is a 4-character random discriminator that
  binds a token to the session that minted it — a token from one session is structurally
  unrestorable by another (`PiiTokenSession#restore` only ever consults its own map), not merely a
  policy nobody happens to violate.
- **`PiiTokenSession`** (`tokenization.PiiTokenSession`) holds the token↔value mapping for one call.
  `create()` / `tokenFor(category, value)` / `restore(text)` /
  `restoreWithUnresolvedCount(text)` / `sessionId()` / `size()` / `close()`. **The mapping is
  sensitive data** — for as long as a session lives it retains every PII value its detectors found,
  so it is closed on every termination path (success, error, cancellation) by every caller, never
  only on the happy path. It deliberately does **not** implement `AutoCloseable`: a sync call site
  uses `try { ... } finally { session.close(); }` (`AiGuardrailsAdvisor#adviseCall`,
  `AiGatewayFacadeImpl#chatCompletion`); a Reactor `Flux` call site releases it via
  `.doFinally(signalType -> session.close())` so completion, error, AND cancellation all release it
  exactly once, plus an explicit `close()` on the one path outside the `Flux` entirely — a
  `BLOCK`-mode rejection before anything is ever subscribed to, where `doFinally` would never run
  (`AiGuardrailsAdvisor#adviseStream`). `restore` on an unknown token (wrong session, or an ordinal
  this session never minted) leaves it exactly as-is rather than guessing, so a mangled or
  cross-session token surfaces visibly as a literal `[PII_...]` fragment instead of becoming a
  silent wrong substitution — see `token_unresolved` below.
- **Scan, then restore — never the reverse.** Every response-direction call site scans the model's
  output for PII/secrets FIRST (`scanResponseText`/`redactAll`/the streaming redactor's per-kind
  scan), and only THEN restores the session's tokens back to their real values
  (`restoreResponseText`, or the streaming redactor's internal `restore`). Reversing that order
  makes the whole feature a silent no-op: restore puts the real value back into the text, and the
  scanner — which cannot tell "old real PII the model leaked" from "PII this session just
  legitimately restored" — immediately re-redacts it, so every "no PII in the output" test still
  passes while the round trip never happens. This exact defect shipped once during this feature's
  development and was caught only by a mutation test that deliberately swapped the two calls and
  watched an assertion that pinned a specific restored value (not merely "no `[PII_*]` token
  present") start failing. Scanning first also has a genuine benefit beyond ordering-for-its-own-sake:
  a token never matches a PII/secret pattern, so scanning never disturbs it, and scanning first still
  means genuinely NEW PII the model produced in the clear (not from a token) is still caught and
  masked before the token restoration step runs.
- **Streaming extends the same safe-cut buffering to tokens.** `StreamingResponseRedactor`
  (constructed with a session) treats a complete token exactly like any other matched span for the
  purpose of deciding where it is safe to cut and emit: `PiiToken#pattern()` feeds the same
  pull-back loop that protects an ordinary secret from being split across two SSE chunks, so
  `[PII_EMAIL_A` then `DDRESS_1_k3n9]` is never emitted as two separate, meaningless fragments. **The
  window precondition applies to tokens too**: the regex only matches a *complete* token, so a token
  still arriving produces no range at all for the pull-back loop to hold onto until its closing `]`
  is already in the buffer — the lookahead window must exceed the longest token you need to
  guarantee, same as the pre-existing "window must exceed your longest secret" rule. A token's
  length depends on its category name, and since the Presidio taxonomy rename (see "Taxonomy and
  curated default" above) **the longest possible token today is 38 characters** —
  `[PII_FI_PERSONAL_IDENTITY_CODE_1_k3n9]`, from the longest type name, `FI_PERSONAL_IDENTITY_CODE`,
  at 25 characters; the shortest category names (`CRYPTO`, `US_SSN`, `AU_ABN`, all 6 characters) give
  a 19-character token. `StreamingResponseRedactor.DEFAULT_WINDOW` is 512, which holds this
  invariant comfortably for every token shape in the catalog today. This is not merely theoretical: a
  window that is too small for the token shapes in play reproduces the "prefix emitted early"
  trade-off for a token instead of an ordinary secret — under the old, shorter category names
  (`EMAIL`, `SSN`, etc., before this rename) a 16-character window was exactly one character short of
  the 16-character `[PII_SSN_1_k3n9]` shape's own requirement (length + 1) and reproduced exactly
  that failure during development of this feature, caught only by comparing against a larger window —
  see `StreamingResponseRedactor`'s class javadoc. Restoration itself is independent of the streaming
  scan policy: a workspace with `response-scan-streaming-enabled` off but a session that minted at
  least one token still gets that token restored (`AiGuardrails#newStreamingResponseRedactor(Long,
  AiGuardrailMetrics, PiiTokenSession)` — a session that minted nothing still returns `null` when
  scanning is also inactive, so a stream with nothing to restore and nothing to scan pays no
  lookahead latency for no benefit).
- **Entry points**: `AiGuardrails#newTokenSession()` (mints a session),
  `#tokenizeInputs(inputs, workspaceId, session, metrics)` (the tokenizing, non-throwing sibling of
  `checkInputs` — PII becomes tokens, secrets still redact, blocked-term/injection/moderation
  handling unchanged), `#applyToInputs(inputs, workspaceId, session)` (the tokenizing, THROWING
  sibling of the 2-arg `applyToInputs` — same unconditional throw on a blocked term or flagged
  injection, but deliberately does NOT check moderation, for the identical reason the 2-arg form
  does not: its only caller is the AI Gateway adapter's own throwing request path, which already
  moderates its own DTO pipeline with its own classifier wiring — routing it through the
  moderation-checking `tokenizeInputs` instead would silently reintroduce exactly the
  double-moderation the 2-arg form was built to avoid), and `#restoreResponseText(text, session,
  metrics)` (scans having already happened; records `pii_restored`/`token_unresolved`).
- **Per-surface status**: the canvas AI Agent and AI Hub (via `AiGuardrailsAdvisor`, both
  `adviseCall` and `adviseStream`) unconditionally tokenize today — the advisor always opens a
  session, so `tokenizeInputs` is what actually runs, not `checkInputs` (see "The advisor" below).
  The AI Gateway's synchronous `chatCompletion` path tokenizes too (`AiGatewayFacadeImpl` opens one
  session per HTTP exchange and threads it through `AiGatewayGuardrails#apply` on the way out and,
  on the way back, `#scanResponse` then `#restoreResponse` SEPARATELY rather than the combined
  `#redactResponse` — tracing runs in between the two, over the scanned-but-not-yet-restored
  response, so a persisted trace/span never holds this exchange's real PII values, only its token
  placeholders; see `AiGatewayFacadeImpl#chatCompletion`'s inline comment).
  **Not yet tokenized**: the AI Gateway's streaming `chatCompletionStream` path and its embeddings
  endpoint still redact PII irreversibly — closing that gap is follow-up work, not a Phase 1 claim.
- **Restoration covers only a choice's main text field, not tool calls or content blocks.** Neither
  the advisor's `applyResponseGuardrails` (which copies `original.getToolCalls()` verbatim) nor the
  gateway's `scanResponse`/`restoreResponse` (which rewrite only `message.content()`, leaving
  `contentBlocks`/`toolCalls` untouched) restores tokens found outside a choice's primary text — so a
  tool call built from this feature's own motivating example receives the literal
  `[PII_EMAIL_ADDRESS_1_k3n9]` token rather than the address. Not a regression (it received the
  equally literal `[REDACTED_EMAIL_ADDRESS]` there before tokenization existed), and tool boundaries
  are a declared non-goal (design spec §11) — but that non-goal is about *scanning* new PII in tool
  payloads (see "Surface wiring" below), not about *restoring* this session's own tokens, which is
  the gap called
  out here.
  **2026-08-31 follow-up (ticket 732): closed for the canvas AI Agent and AI Hub, by a different layer
  than the one this bullet describes.** `PiiTokenBoundaryToolCallingManager` restores tokens in a tool
  call's `arguments` before the delegate executes it — `applyResponseGuardrails` and the gateway's
  `scanResponse`/`restoreResponse` still copy `toolCalls`/`contentBlocks` verbatim exactly as described
  above, so nothing in this paragraph became inaccurate; the gap is closed by an earlier interception
  point (`ToolCallingManager.executeToolCalls`, below where the model's raw completion is handed off to
  actually run a tool), not by teaching either of those two methods something new. The AI Gateway itself
  is untouched by this — it proxies chat completions and runs no tools of its own, so it was never the
  surface this gap named. See "Tool boundary" below for the full mechanism, including the direction this
  paragraph does NOT cover (a tool's *result*, which is a live leak rather than a malfunctioning tool and
  is the more serious of the two).
- **Phase 1 sessions are request-scoped.** A session lives for exactly one call (or one stream) and
  is discarded at the end of it — a value tokenized in turn 1 of a multi-turn conversation gets a
  *different* token if it recurs in turn 3, because turn 3 opens its own fresh session with its own
  random `sessionId`. Cross-turn coherence (the same value getting the same token across an entire
  conversation) is explicitly deferred to Phase 2; nothing in Phase 1 persists a session past the
  call that created it.
- **Consequence for history-retaining surfaces, and the resulting constraint.** A surface that
  persists a turn's text and later replays it as context for a subsequent turn (most conversational
  chat UIs do this) will replay a dead token: turn 1's session is closed and its mapping cleared by
  the time turn 5 re-sends turn 1's response as history, so a model that echoes
  `[PII_EMAIL_ADDRESS_1_k3n9]` back in turn 5 hands it to a session with nothing to restore it with — the
  literal token string, not a value, is what the turn-5 caller sees (correctly counted as
  `token_unresolved`, not silently dropped, once the turn-5 session has minted at least one token of
  its own — see the streaming caveat above for the one case where even that counting doesn't happen).
  Separately, turn 2's own newly-minted tokens reuse ordinal 1 for whatever it detects first, since
  every session's `nextOrdinal` starts over at 1 — so `[PII_EMAIL_ADDRESS_1_...]` in turn 2's own
  fresh session output has nothing to do with `[PII_EMAIL_ADDRESS_1_...]` from turn 1's session, despite the
  matching ordinal. **Constraint: do not ship Phase 1 tokenization alone on a surface that retains
  chat history and replays it into a later turn** — either keep that surface on irreversible
  redaction until Phase 2's cross-turn coherence lands, or knowingly accept dead/colliding tokens as
  a documented limitation for it (design spec §10a). This is a documentation-only constraint today —
  there is deliberately no runtime property gating it; whether to add one is a separate, open
  decision for the humans, not something to infer from this note.

## Tool boundary

**2026-08-31 follow-up (ticket 732): PII tokenization now round-trips across a tool call, in both
directions.** Spec: `docs/superpowers/specs/2026-08-31-tool-boundary-pii-restoration-design.md`.
Before this, tools sat outside the tokenization round trip entirely: outbound, the model echoed a
token into a tool call's arguments and the tool malfunctioned on the placeholder (`send-email`
receiving `[PII_EMAIL_ADDRESS_1_k3n9]` instead of an address); inbound, a tool's result (a CRM lookup,
a database read) was appended as a `ToolResponseMessage` and sent back to the model on the next turn
with no scan at all, a live leak of exactly the data this feature exists to contain. The second
direction is the more serious: the first is a visible malfunction someone reports, the second is
invisible.

**This also means the feature withdraws a protection that previously existed by accident.** Because
the model only ever saw placeholders before, anything it echoed into a tool call was a placeholder —
so a tool forwarding to a third party (an HTTP node posting to a partner API, a CRM write) sent the
placeholder, not the real value. Nobody designed that; it was indiscriminate, protecting the partner
API and breaking `send-email` by the identical mechanism. After this feature, tools receive real
values: fixed for `send-email`, a real behavior change with privacy implications for a tool aimed at
a third party. There is no per-tool control to opt out of this — that policy is deferred to the
Component Policies per-action slice (`docs/superpowers/specs/2026-08-31-guardrail-action-policy-design.md`
§4) — so today an operator who does not want a specific tool seeing real PII has only the workspace-wide
PII protection toggle, not a per-tool one.

**2026-09-01 follow-up (ticket 732): the 2026-08-31 fix above reopened the exact leak it closed, one
hop later, on every non-`returnDirect` tool round.** `restoreToolCallArguments` rebuilds the
`AssistantMessage` that requested the tool call with real values so the tool receives them — but
Spring AI's `DefaultToolCallingManager.buildConversationHistoryAfterToolExecution` appends that EXACT
`AssistantMessage` object (by reference, not a copy) to the `conversationHistory` it returns in the
`ToolExecutionResult`. `tokenizeConversationHistory` (renamed from `tokenizeToolResults`) only ever
rewrote `ToolResponseMessage` entries in that history, so the real-valued assistant message passed
straight through untouched, one call after this class went to the trouble of tokenizing it. Net
effect: `ToolCallingAdvisor` sent the model provider `{"to":"bob@acme.io"}` in the very next prompt —
the model saw the real address it was never supposed to see, on the direction (outbound) this feature
already had special-cased as fail-open, precisely because nobody expected the outbound side to also be
a leak vector. The same real-valued message is also what suspend/resume persists to task state; see
below.

  **Why the original test suite could not have caught this.** Every stub delegate in
  `PiiTokenBoundaryToolCallingManagerTest` (`RecordingToolCallingManager`,
  `ReturningResultToolCallingManager`, `ReturningMultipleResultsToolCallingManager`) built its returned
  `conversationHistory` from a `ToolResponseMessage` alone, never including the assistant message
  `DefaultToolCallingManager` actually appends. The suite was structurally blind to this leak by
  construction, not by an oversight in any individual test — fixing the production code without first
  making the stubs realistic would have left that blindness in place for the next change. All three
  stubs now append `assistantMessageOf(chatResponse)` ahead of the tool response(s), matching
  `buildConversationHistoryAfterToolExecution`'s actual shape; against the unfixed production code with
  only the stubs corrected, the new tests fail exactly on the real value
  (`testTheToolReceivesRealValuesWhileTheReturnedAssistantMessageCarriesTheToken` et al.) — that failing
  run is the mechanism-confirmation this note is describing.

  **Fix, and what was rejected.** `tokenizeConversationHistory` now also re-tokenizes every
  `AssistantMessage` with tool calls in the returned history — not only the one this invocation's
  delegate call appended, but any earlier one already carried over from a prior tool round — through
  the same fail-closed `tokenizeOrRedact` pipeline already used for `ToolResponseMessage`. Rejected:
  identity-matching only the specific `AssistantMessage` object `restoreToolCallArguments` built, and
  leaving every other assistant message in the history untouched. That would have been cheaper (no
  redundant re-scan of already-tokenized entries across rounds) but ties correctness to
  `DefaultToolCallingManager`'s specific behavior of preserving that object by reference — a delegate
  that copies/rebuilds the assistant message instead would silently defeat identity matching and
  reopen the leak with no signal. Scanning by shape (any `AssistantMessage` with tool calls) instead of
  by identity is the same choice already made for `ToolResponseMessage`, is safe because
  `PiiTokenSession#tokenFor` is keyed only on the value (a value already tokenized earlier in the
  session comes back as the identical token, not a new one — the stability the "one value, one token"
  invariant depends on), and is a no-op on text already in token form (`[PII_EMAIL_ADDRESS_1_k3n9]`
  matches no detector). The tool itself is never re-executed by this pass — it already ran, against the
  real-valued `ChatResponse` `restoreToolCallArguments` produced, before this method ever sees the
  delegate's result.

  **New event: `assistant_history_retokenized`**, the fourth in this family, recorded at most once per
  `executeToolCalls` invocation like its siblings. Deliberately distinct from `tool_result_tokenized`:
  that one is about a tool's RESULT reaching the model, this one is about the assistant message that
  REQUESTED the tool call reaching it a turn later than intended — collapsing them into one event would
  make it impossible to tell which direction of the same invocation actually needed retokenizing.
  `AiGuardrailMetrics` implements it the same way as the other three (delegates to `record(String)`
  under this instance's `surface`), so the "verified gap" documented below it — `AgentToolCallingManagers`
  never actually receiving a `SensitiveDataMetrics` bean — applies to this fourth event exactly as it
  does to the first three; it is not a new gap, just one more event affected by the existing one.

  **Suspend/resume consequence: partially closed, partially open.** Traced through, not assumed:
  `AbstractAiAgentChatAction` wraps `AgentToolCallingManagers#getToolCallingManager` (i.e. THIS class)
  as the DELEGATE of `SuspendableToolCallingManager` — so `SuspendableToolCallingManager` always
  receives this class's already-fixed `ToolExecutionResult`, never the raw one. On a suspend, it calls
  `ConversationState.from(result.conversationHistory())` and stores that in
  `suspend.continueParameters()`, the persisted task state; `ConversationState#toEntry` copies an
  `AssistantMessage`'s `toolCall.arguments()` verbatim into the persisted `ToolCallEntry`. So the
  real-value leak this fix closes was also a leak into persisted state, not only into the next
  in-memory prompt — that half is now closed by the same change, for free, since
  `SuspendableToolCallingManager` reads the same `conversationHistory` this method returns. **Still
  open**: a token replayed on resume belongs to whatever session minted
  it, and `PiiTokenSession` is request-scoped and closed at the end of the call that created it (see
  "Phase 1 sessions are request-scoped" above) — so a resume that reopens a fresh session can never
  resolve a token from the suspended run's now-dead session, the same dead-token limitation
  "Consequence for history-retaining surfaces" already documents for the response-restoration path.
  That is a pre-existing, architectural limitation of request-scoped sessions, not something this fix
  attempts to solve; fixing it would mean persisting (or otherwise reconstructing) a session's
  token↔value mapping across suspend/resume, which is out of scope here and not something to infer as
  decided.

  **Tests** (`PiiTokenBoundaryToolCallingManagerTest`):
  `testTheToolReceivesRealValuesWhileTheReturnedAssistantMessageCarriesTheToken` (the tool still gets
  the real value; the returned history does not),
  `testTheSameValueKeepsOneTokenBetweenTheAssistantMessageAndTheToolResult` (the assistant message and
  the tool response agree on one token for one value),
  `testMultipleToolRoundsKeepTheSameTokenInEveryReturnedAssistantMessage` (a second round built on the
  first round's returned history does not mint a second token, and retokenizes its own newly appended
  assistant message just as reliably as the one carried over), and
  `testRecordsAssistantHistoryRetokenizedWhenAnAssistantToolCallArgumentIsRetokenized`/
  `testDoesNotRecordAssistantHistoryRetokenizedWhenNothingWasRetokenized` for the new event's incidence
  shape.

- **Class**: `PiiTokenBoundaryToolCallingManager` (CE, `platform-ai-sensitive-data-service`, package
  `com.bytechef.platform.ai.sensitivedata.tokenization`) decorates a Spring AI `ToolCallingManager`.
  `resolveToolDefinitions` always delegates unchanged — there is nothing to restore in a tool
  definition. `executeToolCalls(Prompt, ChatResponse)` reads a `PiiTokenSession` off the prompt's
  `ToolContext` via `PiiTokenSessionToolContext#from`; with no session present — every workspace with
  tokenization off, which is most tool calls in the product — it delegates the identical `ChatResponse`
  instance untouched: no rebuild, no allocation, genuinely inert.

- **Why a `ToolCallingManager` decorator, not a `ToolCallback` decorator (spec decision D2).** Four
  existing decorators (`ApprovalGateToolCallback`, `RehydrateContextToolCallback`, `MeteredToolCallback`,
  `ProgressReportingToolCallback`) all wrap individual callbacks at registration time, and that would
  have been the more familiar choice here too. It was rejected for one reason: **AI Hub resolves tools
  dynamically, at call time**, through tool search and `MapToolCallbackResolver` — a callback surfaced
  only after the model calls `searchTool` is never on the static list a registration-time wrapper can
  see, so that approach would cover most tools, miss some, and give no signal about which. Wrapping the
  manager instead sits below every registration path, dynamic or static, so "did we wrap every tool"
  stops being a question anyone has to keep answering correctly, forever. `ToolSearchPiiBoundaryTest`
  (`ai-hub-service`) pins exactly this case: a callback added only to the resolver map and to the
  prompt's live tool-call options — never passed to any construction-time wrapping step — still gets its
  arguments restored. **This is the decision most likely to be "simplified" back into a bug by someone
  who looks only at the agent component**, where per-callback wrapping would appear to work fine (the
  agent's tool list is static), and would silently stop covering AI Hub's dynamically resolved tools.

- **Two wrap points, both verified in source:**
  - `AgentToolCallingManagers#getToolCallingManager` (`server/libs/modules/components/ai/agent`,
    package `...tool`) is the sole factory for all three AI Agent actions, including
    `AiAgentRealtimeChatAction` — its WebSocket layer is transport around the same streaming call, not
    provider-side tool execution, so realtime is covered by this one wrap too (spec decision D7).
  - AI Hub's `ToolSearchAdvisorConfiguration#buildToolCallingManager` (`ai-hub-service`, package
    `...toolsearch`) wraps `PiiTokenBoundaryToolCallingManager` OUTERMOST, around the pre-existing
    `LazyToolCallingManager` → `UnknownToolRecoveringToolCallingManager` → `DefaultToolCallingManager`
    stack — a third layer added to a chain that already composed two, not a rewrite of it. Outermost
    placement is deliberate: it never forces `LazyToolCallingManager`'s memoized delegate to build
    early (this method's own call still happens lazily, inside the same `Supplier`), and it still
    tokenizes the synthetic tool-error text `UnknownToolRecoveringToolCallingManager` fabricates for an
    unresolvable tool name, keeping behavior uniform across both outcomes even though that text never
    contains real PII.

- **The session travels via `ToolContext`, not a `ThreadLocal`, because tool calls run on worker
  threads that inherit none of the request thread's `ThreadLocal` state** — `EnvironmentContext`,
  `TenantContext`, and the Spring `SecurityContext` are all lost on that hop, which is the entire reason
  `RehydrateContextToolCallback` exists for those three. A `PiiTokenSession` held in a `ThreadLocal`
  would simply be invisible at the point `PiiTokenBoundaryToolCallingManager` runs. `AiGuardrailsAdvisor`
  (`adviseCall`/`adviseStream`, via its private `withSessionInToolContext`) opens the session and merges
  it into the request's tool context with `PiiTokenSessionToolContext#into(existingContext, session)`
  under the fixed key `PiiTokenSessionToolContext.KEY` (`"bytechef.pii-token-session"`) — a MERGE, never
  a replace: `AgentToolInvocationContext`'s own workspace/user/environment/tenant/authentication keys
  live in that same map, and a replacing write would silently strip security-context rehydration off
  every tool call in the request. `into` always returns a new map and never mutates its input.

- **Fail-open / fail-closed asymmetry, and why it is not the engine's usual rule applied twice.** Every
  other fail-open policy in this engine (see "Sensitive-data detectors" above, "Failure is fail-open,
  per detector") exists because a broken guardrail must never be worse than no guardrail. That rule does
  **not** generalize to both directions of the tool boundary, and stating the asymmetry explicitly is
  the point — a future reader who applies the engine's usual rule uniformly across both directions
  reintroduces the leak this feature exists to close:
  - **Arguments fail open.** An unresolved token in a tool call's arguments — the model hallucinated
    one, or echoed a token from a session that has since closed — is left in the text exactly as it
    stood (`PiiTokenSession#restoreWithUnresolvedCount`), `token_unresolved` is recorded, and the tool
    then malfunctions *visibly* (a literal `[PII_EMAIL_ADDRESS_...]` string reaching an email API). The
    run is not aborted; this matches the precedent already set on the response-restoration path.
  - **Results fail closed — the deliberate local exception.** `tokenizeOrRedact` tries
    `SensitiveDataRedactor#tokenizeWithSpans` first; if that throws, it falls back to
    `SensitiveDataRedactor#redact`; if redaction ALSO throws, the text becomes the empty string. The
    original text is never returned on any failure path. Applying this engine's global "fail open, never
    block a call" rule here — a broken check just doesn't check — would hand the model provider exactly
    the data this feature exists to keep away from it, so this is a deliberate, local exception to that
    rule, not an inconsistency to "fix" later.

- **Secrets are never restored at the tool boundary, on either path.** Outbound,
  `restoreWithUnresolvedCount` only ever resolves tokens `PiiTokenSession` itself minted, and it mints
  tokens only for `SensitiveKind.PII` spans — a `SECRET` span is never given a token to begin with (see
  "PII tokenization" above), so there is no secret-shaped token for this path to ever restore. Inbound,
  `tokenizeOrRedact` calls `tokenizeWithSpans` with `Set.of(SensitiveKind.PII, SensitiveKind.SECRET)`:
  PII spans found in a tool's result become new tokens, but SECRET spans keep the irreversible
  `[REDACTED_SECRET]` placeholder, identical to every other redaction path in this module
  (`testASecretInAToolResultIsRedactedAndNotTokenized` pins this). Nothing at this boundary can turn a
  secret into something restorable, on any path, in either direction.

- **Metrics: three new incidence events (originally two; `assistant_history_retokenized` added by the
  2026-09-01 follow-up above), `token_unresolved` reused.** `tool_args_restored` (at least one token was
  substituted into a tool call's arguments), `tool_result_tokenized` (at least one value in a tool's
  result was tokenized or redacted), and `assistant_history_retokenized` (at least one assistant
  tool-call argument in the returned history was retokenized before going out) are recorded at most
  once per `executeToolCalls` invocation — the same incidence-counter shape as every other event in
  this family, not once per tool call, per response, or per span
  (`SensitiveDataMetrics#recordToolArgsRestored`/`#recordToolResultTokenized`/
  `#recordAssistantHistoryRetokenized`'s own javadoc explains why the guarding boolean lives outside the
  per-call/per-response/per-message loop). `token_unresolved` is reused rather than duplicated for the
  argument-restoration case; it already means what it needs to mean here.

  **Historical gap, now closed (ticket 732).** These four events were once never recorded for the canvas
  AI Agent surface. `AgentToolCallingManagers` used to take an `ObjectProvider<SensitiveDataMetrics>` in
  its Spring-wired constructor, but no `@Bean`/`@Component` anywhere produces a `SensitiveDataMetrics` —
  every consumer either takes it as an explicit method parameter or hand-constructs
  `new AiGuardrailMetrics(meterRegistry, surface)` at the call site, precisely because a singleton bean
  cannot carry a per-request or per-surface tag. So the provider always resolved empty and
  `getToolCallingManager` always wrapped with `metrics = null`. Restoration and tokenization THEMSELVES
  always worked on this surface — they need only `PiiTokenSession` and a detector-equipped
  `SensitiveDataRedactor`, which autowires fine — it was only the metrics wiring that was missing.

  The fix removed that constructor parameter (`AgentToolCallingManagers`'s javadoc now says
  "Deliberately takes no `ObjectProvider<SensitiveDataMetrics>`") and made `metrics` an explicit
  parameter of `getToolCallingManager`, resolved by the caller: `AbstractAiAgentChatAction` calls
  `AiGuardrailsAdvisorProvider#getMetrics(...)` per action execution, so the instance is both
  `ai_agent`-tagged and resolved fresh on every run rather than once at boot. AI Hub never shared this
  gap: `ToolSearchAdvisorConfiguration`'s `@Bean` methods construct
  `new AiGuardrailMetrics(meterRegistryProvider.getIfAvailable(), "ai_hub")` explicitly and thread it
  through `buildToolCallingManager`.

- **Testing** (`PiiTokenBoundaryToolCallingManagerTest`, `platform-ai-sensitive-data-service`, unless
  noted): the fail-closed cascade (`testAResultThatCannotBeTokenizedIsRedactedRatherThanReturnedRaw`),
  the secret case (`testASecretInAToolResultIsRedactedAndNotTokenized`), the unresolved-token case
  (`testAnUnresolvedTokenIsLeftInPlaceAndDoesNotAbortTheRun`,
  `testRecordsTokenUnresolvedWhenAnArgumentTokenCannotBeResolved`), and the one-token-per-value
  invariant across the whole call (`testTheSameValueKeepsOneTokenAcrossTheWholeCall`).
  `AgentToolCallingManagersTest` (`ai/agent`) and `ToolSearchPiiBoundaryTest` (`ai-hub-service`) cover
  the two wrap points. `ToolSearchPiiBoundaryTest` pins the BEHAVIOUR D2 was chosen to obtain — a tool
  resolved dynamically, at request time, still gets its arguments restored — but **not D2 itself**. D2 is
  a claim about which wrapping hooks exist in the AI Hub's wiring, and no execution test can demonstrate
  the absence of a hook: the test shows the chosen design works, not that the per-callback design would
  have failed. Both this doc and the test's own javadoc previously claimed the stronger thing; the
  javadoc now carries the distinction (ticket 732).

- **The session is stripped from the tool context before the delegate runs the tools**
  (`PiiTokenBoundaryToolCallingManager#withoutSession`, `PiiTokenSessionToolContext#without`, ticket
  732). `PiiTokenSession` is a live two-way token-to-value map, and Spring AI hands every
  `ToolCallback` the request's `ToolContext` — including a Script action exposed as an agent tool, which
  runs the tenant's own Java/JavaScript/Python/Ruby. Leaving the session reachable there would give that
  code a de-tokenizing oracle for every value the guardrail had protected in the request. The boundary
  needs the session only either side of the delegate call (restore arguments before, tokenize history
  after) and holds its own local reference for both, so nothing is lost by removing it from the map. The
  `PiiTokenBoundaryPolicy` on the same map is deliberately left in place: it is configuration (kinds +
  threshold), not data. `AgentToolInvocationContext`'s entries, which share this map, survive the strip —
  losing them would break security-context rehydration on the tool's worker thread and surface far from
  here as an authorization error inside an unrelated tool.

  **Two Spring AI builder "setters" are accumulators, not setters, and both bit this work.**
  `ToolCallingChatOptions.Builder#toolContext(Map)` does `putAll`, and `mutate()` has already copied the
  ORIGINAL map into the builder — so setting a stripped copy merges it back over itself and removes
  nothing. Clearing first with `.toolContext(null)` is load-bearing. `ChatClient.Builder#defaultAdvisors`
  behaves the same way (`DefaultChatClient.DefaultChatClientRequestSpec#advisors` calls `addAll`), which
  is what makes `CopilotGuardrailsAdvisorFactory#guardedChatClient(ChatClient)` safe to append with. The
  first strip attempt silently did nothing and was caught only by
  `testTheSessionIsStrippedFromTheToolContextTheToolItselfSees`; assume any "replace this map" against a
  Spring AI builder merges until proven otherwise.

## MCP outbound

**2026-09-02 follow-up (ticket 732): MCP is the fourth surface, and the only one that redacts rather
than tokenizes.** Spec: `docs/superpowers/specs/2026-09-02-mcp-outbound-guardrails-design.md`. Before
this, MCP servers had zero content inspection in either direction — verified by
`grep -rln 'guardrail\|Guardrail\|SensitiveDataRedactor\|PiiToken' server/libs/platform/platform-mcp
server/libs/ai/ai-mcp server/libs/automation/automation-ai/automation-ai-mcp-server --include='*.java'
--exclude-dir=build` returning nothing, before this feature. Copilot, the canvas AI Agent, and AI Hub
all talk to a model provider the tenant configured under the tenant's own key; on MCP the receiving
party — an external agent such as Claude, Cursor, or a partner's own agent — is not chosen by the tenant,
so an unredacted result is handed to a party ByteChef has no relationship with. **MCP is not the only
such surface, and earlier revisions of this section and of the spec wrongly said it was: A2A servers
(`A2AAgentExecutor`, `platform-ai-a2a`) hand an agent-backed workflow's textual response to another
party's agent on an inbound `message/send`, with no content inspection either.** A2A is uncovered, listed
under "Not covered" below — nothing regressed, it was never covered — and MCP is simply the surface this
work protects.

- **Redacts, never tokenizes, and this is deliberate, not an oversight.** Every other surface's
  tokenization works because there is a return path: the same request's `PiiTokenSession` restores the
  real value once the completion comes back (see "PII tokenization" above). MCP outbound has no return
  path through ByteChef — the external agent receives the result and keeps it — so a token minted here
  would never be restored by anyone: a broken value sitting in someone else's context, not a protected
  one. This surface calls `SensitiveDataRedactor.redact(...)` directly, the same irreversible path
  secrets always use, and no `PiiTokenSession` is ever opened for it.
- **`redactMcpResults`** (`AiGuardrailsWorkspaceSettings`, tenth and final field, EE `-api`) is a
  dedicated boolean switch, separate from `redactPii`/`redactSecrets` and gated on nothing else —
  `AiGuardrails#resolveMcpOutboundPolicy(workspaceId)` reads the settings row directly rather than going
  through `resolvePolicy`, because `redactMcpResults` has no global-property counterpart to union with.
  The switch only selects the surface; once it is on, the kinds and `minConfidence` still come from the
  same workspace fields every other surface reads (`resolveToolBoundaryPolicy`, reused rather than
  duplicated). It is a plain boolean, not a union member, and defaults off on every existing row.
  - **Consequence: enabling this switch ALONE redacts nothing.** With `redactPii` and `redactSecrets` both
    off — the shipped default — `resolveToolBoundaryPolicy` builds an empty kind set and
    `SensitiveDataRedactor.redactWithSpans` returns the text unchanged, so the workspace gets a resolved
    redactor, a metrics tag, and no redaction. That is the intended design (the switch picks the surface,
    the categories come from the settings above), but it is exactly what an operator gets wrong, so the
    settings page's toggle description says it in as many words.
  - **Why a separate switch at all, rather than reusing `redactPii`/`redactSecrets` directly**: an MCP
    tool is frequently how a customer hands data to their own agent *on purpose* — that is the tool's
    job. Turning on guardrails for chat surfaces must not silently start rewriting an MCP pipeline's
    payloads that a customer configured expecting the real values. `AiGuardrails#resolveMcpOutboundPolicy`'s
    own javadoc states this directly.
  - **It does NOT participate in `AiGuardrails#isActive`.** `isActive` gates whether a chat surface
    attaches a guardrails advisor at all (see "Module and engine" above); folding `redactMcpResults` into
    that union would mean turning on MCP redaction alone starts attaching an advisor to Copilot and the
    canvas AI Agent — exactly the cross-surface surprise the separate-switch design exists to avoid. A
    workspace can have `redactMcpResults = true` and every chat-surface guardrail off, and `isActive` for
    that workspace still reports `false`.
- **Fail-closed, inverting the engine's usual rule, and for the same reason as the tool boundary's result
  direction.** Every other fail-open policy in this engine exists so a broken guardrail is never worse
  than no guardrail (see "Sensitive-data detectors" above). That does not hold here: this is the last
  point before data leaves to a party ByteChef has no relationship with, and there is no second chance.
  `McpOutboundRedaction.redact` (`platform-ai-api`, package `...guardrails`) is the one place this rule is
  enforced: no redactor resolved (provider absent, or `redactMcpResults` off) returns the input unchanged;
  a resolved redactor that throws is WARN-logged and rethrown as `McpOutboundRedactionException` — the
  caller must never fall back to the unredacted payload on that exception. `RedactingToolCallback` follows
  the existing refusal idiom on this surface (both facades already throw `ConfigurationException` with an
  `McpServerErrorType` when a server or tool is disabled, and the MCP layer converts that into a tool
  error the calling agent sees) rather than inventing a second refusal channel.
  - **A failed settings lookup is not "off", and telling them apart is the whole point.** Both used to
    arrive at `McpOutboundRedaction` as a missing redactor, so a transient DB error during a `tools/call`
    on a workspace with `redactMcpResults = true` shipped raw records with `isError` false and a WARN
    saying global guardrails still applied. `AiGuardrails#resolveMcpOutboundPolicy` is therefore the ONE
    caller that reads `AiGuardrailsWorkspaceSettingsService#fetchSettings` directly instead of through
    this class's fail-open `findSettings` (which stays fail-open for the chat surfaces, correctly), and
    `McpOutboundRedaction.redact` resolves the redactor INSIDE its try so a resolution failure becomes
    the same `McpOutboundRedactionException` a redaction failure does.
  - **Exception messages are redacted too, on every path.** `McpToolUtils.toSharedSyncToolSpecification`
    (spring-ai-mcp 2.0.1) catches whatever a tool callback throws and puts `e.getMessage()` into the tool's
    text content, and those messages are payload-derived: `OpenApiClientUtils` throws
    `new ProviderException(statusCode, body.toString())` — the provider's raw response body IS the message
    — and `JobExecutionErrors.checkForError` rethrows a task error verbatim. So `RedactingToolCallback`
    catches the delegate's throw, redacts the message and rethrows it as a `RedactedToolExecutionException`
    (the original stays as the cause, for ByteChef's logs only), and both approval-resume funnels redact
    the message before it becomes tool-error text (`McpOutboundRedaction.redactFailureMessage`, which
    returns null rather than the raw message when redacting it fails; the funnel then emits its generic
    wording, still `isError = true`). A redaction failure while redacting a message never falls back to
    the raw message.
- **Embedded resolves its own `EMBEDDED`-scoped settings row, not the tenant-default one — fixed this
  session (ticket 732); before this fix it fell back to `PLATFORM`.** `EmbeddedMcpToolFacade` carries
  `ToolExecutionSurface.MCP_EMBEDDED` but no `workspaceId`, since embedded MCP servers are not
  workspace-scoped, and `EmbeddedMcpServerConfiguration` passes
  `McpOutboundRedactorProvider.SURFACE_EMBEDDED` as its `surface`.
  `McpOutboundRedactorProviderImpl#fetchRedactor` branches on that surface string: for
  `SURFACE_EMBEDDED` it calls `AiGuardrails#resolveEmbeddedMcpOutboundPolicy()` (which reads
  `AiGuardrailsSettingsScope.EMBEDDED` via `fetchEmbeddedSettings()` — see "Settings storage" above),
  and for every other surface it still calls the workspace-taking `resolveMcpOutboundPolicy(workspaceId)`,
  which resolves `PLATFORM` only when `workspaceId` is `null`. **Consequence, stated so it is not
  rediscovered later: an embedded deployment still cannot vary MCP outbound redaction per workspace —
  there is exactly one setting for the whole tenant — but that setting is now its own dedicated row
  rather than accidentally sharing the automation tenant-default row.**
- **Attachment point: `RedactingToolCallback`** (CE, `platform-ai-api`, package
  `com.bytechef.platform.ai.guardrails`) decorates a `ToolCallback` so its result is redacted before it
  leaves the process — `ToolCallback#call` already returns the result serialized, so one redaction pass
  over the string covers every nested field with no `Map`/`List`/array/record walk to keep exhaustive. The
  redactor is resolved **per call, never at registration**: MCP clients cache `tools/list` for a long
  time, so binding it when the tool list was assembled would mean flipping the workspace setting did
  nothing until the server restarted. The seam — `McpOutboundRedactor` (a `redact(String)`
  `@FunctionalInterface`) and `McpOutboundRedactorProvider` (`fetchRedactor(workspaceId, surface) ->
  Optional<McpOutboundRedactor>`) — lives in CE `platform-ai-api`, the same CE-SPI/EE-impl idiom as
  `AiGuardrailsAdvisorProvider`/`ToolExecutionRecorder`, so the automation and embedded MCP server modules
  never depend on the EE guardrails module directly. It hands back a fully resolved redactor rather than a
  policy object, deliberately: a policy would leak `SensitiveKind`, confidence thresholds, and metrics
  tagging into two MCP modules that have no business knowing them. `McpOutboundRedactorProviderImpl` (EE,
  `platform-ai-guardrails-service`) is the implementation: it resolves `AiGuardrails#resolveMcpOutboundPolicy`
  once per `fetchRedactor` call and closes over the resulting `PiiTokenBoundaryPolicy` plus a
  surface-tagged `AiGuardrailMetrics`, so the returned redactor doesn't re-resolve policy per value.
  `surface` is `"mcp_automation"` or `"mcp_embedded"`.
  - **Test it through `call(String, ToolContext)`, never `call(String)` alone.**
    `McpToolUtils.toSharedSyncToolSpecification` (spring-ai-mcp 2.0.1) invokes ONLY the two-argument
    overload; MCP never calls the one-argument one. A suite written against `call("{}")` exercises a path
    production does not take, so collapsing the two-argument override to `return delegate.call(toolInput,
    toolContext);` would leave it green while every MCP result went out unredacted.
    `RedactingToolCallbackTest` therefore drives redaction, fail-closed and per-call resolution through the
    two-argument overload, and keeps the one-argument one covered separately.
- **Two site families, five wrap points, both verified in source.** `AutomationMcpServerConfiguration`
  and `EmbeddedMcpServerConfiguration` each build tools from several streams (component-backed tools,
  workflow-backed tools, workspace-contributed tools) and each has a single private `guard(ToolCallback,
  ...)` helper that every stream must route through before `McpToolUtils.toAsyncToolSpecification(...)`
  wraps it into an `AsyncToolSpecification`.

## MCP approval-resume: the Critical gap found in review

**During review a Critical defect was found that the plan above never anticipated: `RedactingToolCallback`
only ever redacts a tool's FIRST synchronous result.** When a workflow-backed MCP tool pauses mid-call for
human-in-the-loop approval, the eventual output does not come back through the `ToolCallback` this
document just described at all. `ApprovalElicitingToolSpecifications.decorate` (automation) and its
embedded mirror, `EmbeddedApprovalElicitingToolSpecifications.decorate`, wrap the already-built
`AsyncToolSpecification`, not a `ToolCallback` — when the client accepts the elicitation, they call
`mcpToolFacade.awaitApprovedWorkflowRun(jobId)` or `mcpToolFacade.resolveApprovalAndAwait(...)` directly
inside a `runOnBoundedElastic` funnel and serialize the run's real output into the `CallToolResult`
themselves, entirely outside `RedactingToolCallback`. A workflow that returns a customer's SSN and pauses
for approval would have shipped that SSN to the external agent unredacted, on a code path
`McpOutboundGuardrailsCoverageTest` (below) could never see.

- **Why the coverage scans are structurally blind to this — the durable lesson here.** Both
  `McpOutboundGuardrailsCoverageTest`s scan their configuration file for a statement that contains
  `toAsyncToolSpecification` and requires it to also contain `guard(`. The approval-resume leak lives in
  `ApprovalElicitingToolSpecifications`/`EmbeddedApprovalElicitingToolSpecifications` — a different file
  that never calls `toAsyncToolSpecification` (it decorates a specification that already exists) and never
  touches a `ToolCallback` at all (it builds a `CallToolResult` straight from the resumed run's output). A
  `ToolCallback`-level scan cannot see a path with no `ToolCallback` in it, no matter how the pattern is
  tuned — this is not a gap in the regex, it is a gap in what the regex's target type can express. That is
  why the fix below carries its own tests (`ApprovalElicitingToolSpecificationsTest` /
  `EmbeddedApprovalElicitingToolSpecificationsTest`) rather than an attempt to extend either coverage scan
  to also catch it.
- **Fix: a shared helper, not a second implementation of the same rule.** `McpOutboundRedaction`
  (CE, `platform-ai-api`, package `...guardrails`) is `McpOutboundRedaction.redact(serializedResult,
  mcpOutboundRedactorProviderProvider, workspaceId, surface)` — the exact same fetch-and-fail-closed logic
  `RedactingToolCallback` used to inline, now factored out so both the guarded-`ToolCallback` path and the
  approval-resume path fail closed identically: no redactor resolved returns the input unchanged; a
  redactor that throws is WARN-logged and rethrown as `McpOutboundRedactionException`, never swallowed into
  the raw payload. `RedactingToolCallback#call` now delegates to it instead of carrying its own copy.
  `ApprovalElicitingToolSpecifications#runOnBoundedElastic` (and its embedded mirror) call
  `McpOutboundRedaction.redact` directly on the resumed run's serialized output, inside the same `.map(...)`
  step that builds the `CallToolResult` — so a redaction failure here surfaces as the funnel's own
  `onErrorResume` tool-error path (a workflow failure after approval and a redaction failure are handled
  identically: both become a visible tool error, never a silently unredacted result and never a false
  "returning the pending descriptor" that would re-prompt the reviewer for an approval already resolved).
- **This closes the gap for URL-mode and form-mode elicitation alike** — both `elicitViaUrl` and
  `elicitViaForm` funnel through the same `runOnBoundedElastic`, so there is exactly one place this redacts
  per module, matching the "one rule, enforced once" shape `McpOutboundRedaction` exists for.
- **Pinned by `ApprovalElicitingToolSpecificationsTest`/`EmbeddedApprovalElicitingToolSpecificationsTest`,
  not by either coverage scan** — see the point above for why a scan structurally cannot cover this path.
  Each `McpOutboundGuardrailsCoverageTest`'s own class javadoc now says so explicitly, so a future reader
  of the passing scan does not read it as "the approval-resume path is covered too."

## Not covered

State this plainly rather than implying MCP outbound redaction is a blanket guarantee:

- **A2A servers (`A2AAgentExecutor` / `A2AProtocolHandler`, `platform-ai-a2a`) are uncovered.** An inbound
  `message/send` from another party's agent runs an agent-backed workflow and its textual response goes
  straight back, unredacted — the same untrusted receiving party MCP has, through a different protocol.
  The response is assembled in the protocol layer rather than by a `ToolCallback`, so it needs its own
  attachment point and its own answer to which settings govern it; this work does not touch it
  (`grep -rn "McpOutboundRedact" server/libs/platform/platform-ai/platform-ai-a2a` returns nothing).
- **Management MCP (`ManagementMcpServerConfiguration`) remains completely uncovered.** It also returns
  real data (`queryDataTable`, `getAiSkillFileContent`, `searchContextStore`, among others), but it is
  built differently from the automation/embedded servers — annotation-scanned `@Tool` methods surfaced via
  `ToolCallbacks.from(...)`, no facade in front of them, and many of its tools carry no workspace to
  resolve a policy against. It needs its own attachment hook and its own answer to which settings should
  govern it; this work does not touch it, and no test here claims otherwise (`grep -rn
  "RedactingToolCallback\|McpOutboundRedact" server/libs/ai/ai-mcp/ai-mcp-server` returns nothing).
- **Inbound arguments are not redacted, and this is deliberate, not deferred.** Arguments are the calling
  agent's own data, sent by its own choice — redacting them would corrupt the workflow's inputs rather than
  protect anyone. (There is a separate, smaller, already-known concern: inbound arguments become workflow
  inputs persisted in `Job.inputs` and shown on the execution detail page, so ByteChef stores whatever
  third-party data the calling agent sent — a retention question, not an exfiltration one, and out of scope
  here.)
- **No per-server or per-tool granularity, and redaction is invisible in `ToolExecutionRecorder` history**
  — both named as explicit non-goals in the design spec, not gaps discovered after the fact.
- **Two coverage scans, pinned site counts.** `McpOutboundGuardrailsCoverageTest` exists once per module —
  `automation-ai-mcp-server` asserts exactly **3** `toAsyncToolSpecification` sites (component-backed
  tools, workflow-backed tools, workspace-contributed tools), `embedded-ai-mcp-server` asserts exactly
  **2** (component-backed and workflow-backed; embedded has no workspace-contributed-tool stream). Both
  counts are asserted, not merely observed — a new stream that changes the count fails the test until the
  count is updated deliberately, forcing a human to look at whether the new site is guarded rather than
  letting it silently join the guarded set or silently escape both the scan and the count.

## Settings storage

`AiGuardrailsWorkspaceSettings` (`-api`, package `...guardrails.domain`) is **property-backed, not
a table** — `AiGuardrailsWorkspaceSettingsServiceImpl` persists one `PropertyService` row per scope
keyed by `AiGuardrailsWorkspaceSettings.PROPERTY_KEY`.

**2026-09-02 follow-up (ticket 732): scope is now an explicit discriminator, not inferred from a
nullable `workspaceId`.** `AiGuardrailsSettingsScope` (`-api`, same package) is a three-value enum —
`PLATFORM`, `WORKSPACE`, `EMBEDDED` — and the first record component of `AiGuardrailsWorkspaceSettings`,
ahead of `workspaceId`; a compact-constructor check enforces `workspaceId` non-null exactly when
`scope == WORKSPACE`. Before this, a `null` `workspaceId` was overloaded to mean two different
things — the tenant default, and (once embedded MCP needed its own row) embedded — which is the exact
ambiguity the rest of this ticket's work exists to close; see "MCP outbound" above for the consequence
that ambiguity used to have. Deliberately not `Property.Scope` reused directly: that type is
persistence-layer and carries values (`AUTOMATION`, `PROJECT`, `INTEGRATION`) meaningless here, and a
settings record in an `-api` module should not depend on the property store's shape. Stored by
`name()`, as `blockingMode` already is in the same value map, so it is not ordinal-sensitive.

- **`WORKSPACE`** → `Property.Scope.WORKSPACE`, `scopeId = workspaceId`. `fetchSettings(Long)` keeps
  its pre-existing signature and its null-means-tenant-default meaning — it derives the scope
  internally (`workspaceId == null ? PLATFORM : WORKSPACE`) rather than requiring every caller to pass
  one explicitly.
- **`PLATFORM`** (the tenant default, `workspaceId == null`) → `Property.Scope.PLATFORM`,
  `scopeId = null` — NOT `Scope.WORKSPACE` with a sentinel id. This matches the existing convention
  for scope-less rows (`AiProviderConnectionSourceImpl`, the `"mcp.server"` property).
- **`EMBEDDED`** → `Property.Scope.EMBEDDED`, `scopeId = null`, reached through the additive
  `fetchEmbeddedSettings()` method rather than `fetchSettings(null)` (which still resolves
  `PLATFORM`) — mirroring `VariableServiceImpl`'s embedded rows (see "Variables (workspace / embedded
  organization, EE)" in CLAUDE.md). This is the row embedded MCP outbound redaction reads; see "MCP
  outbound" above and "Scope resolution, per surface" below.
- Only non-null fields are persisted (`null` means "inherit from tenant default"), so a partial
  override never clobbers other fields.
- Fields: five booleans (`redactPii`, `redactSecrets`, `moderationEnabled`,
  `injectionDetectionEnabled`, `scanResponses`), `blockedTerms` (comma-separated string),
  `blockingMode` (`BlockingMode` enum, `BLOCK` default | `REDACT_AND_CONTINUE` | `ALLOW`, INT-ordinal,
  append-only — governs only the blocking guardrails; redaction guardrails always
  redact-and-continue regardless of mode), `minConfidence` (nullable `Double`) — unlike every other
  field here, this one is an override rather than a union member; see "Confidence scoring" above for
  why and for the GraphQL-vs-client-codegen gap on this field specifically — and `redactMcpResults`
  (see "MCP outbound" above).

**Guardrails settings are NOT per-environment, for any scope, and that is deliberate, not an
oversight to fix.** Every read and write above goes through `PropertyService`'s 3-argument,
environment-less overloads (`fetchProperty(key, scope, scopeId)` / `save(key, value, scope, scopeId)`
— verified against `PropertyService`'s interface, which also declares 4-argument overloads carrying an
`environmentId`) — never those 4-argument ones. This is the convention across every sibling settings
service in the platform package; `VariableServiceImpl` is the deliberate exception, because variables
genuinely differ per environment (see "Variables (workspace / embedded organization, EE)" in
CLAUDE.md, which records "environment always set" for that service). Copying the variables model here
— so a workspace could have different guardrail toggles in `PRODUCTION` versus a sandbox environment —
would be a real feature change, not a documentation gap, and nobody should "fix" this by reaching for
that precedent.

## Scope resolution, per surface

Which settings row a call resolves against, now that scope is explicit:

| Surface | Resolves | Status |
|---|---|---|
| Canvas AI Agent (workflow run) | The run's workspace, via `jobPrincipalId` → `ProjectDeployment` → `Project.getWorkspaceId()` | Already correct; out of scope for this ticket |
| Automation MCP | The run's workspace, via `mcpServerId` → workspace | Already correct; out of scope for this ticket |
| AI Hub main agent | The session's server-verified workspace (`AiHubStateKeys.VERIFIED_WORKSPACE_ID`, placed by the controller after the workspace-membership check, never client-controllable) | Already correct |
| AI Hub delegation sub-agents | The same server-verified workspace, forwarded through `AgentToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` (`SubAgentGuardrailedChatClient` reads the identical tool-context key the main agent already populated from the verified state) | Correct on the `SubAgentGuardrailedChatClient` path only, where it inherits the main agent's verification. Sub-agents resolving through `CopilotGuardrailsAdvisorFactory` instead were on Copilot's broken path and got the tenant default; they are **fixed this session (ticket 732)** by the same `DeferredGuardrailsAdvisor` change, not separately — which is why the spec's problem table counts them as broken |
| Copilot — every `*SpringAIAgent`/`ChatClient` site behind `CopilotGuardrailsAdvisorFactory` (see "Surface wiring" above) | The session's server-verified workspace, read from the prompt's `ToolCallingChatOptions` tool context on every call (`DeferredGuardrailsAdvisor#workspaceId` → `AiGuardrailsAdvisorProvider#getAdvisorForWorkspace`) | **Fixed this session (ticket 732)** — previously always resolved `PLATFORM`; see "Copilot workspace authorization" below |
| Embedded MCP outbound redaction | The `EMBEDDED` row, unconditionally (`AiGuardrails#resolveEmbeddedMcpOutboundPolicy` → `fetchEmbeddedSettings()`) — embedded has no workspace to resolve | **Fixed this session (ticket 732)** — previously fell back to `PLATFORM` |
| Anything that resolves none of the above (e.g. a Copilot call whose prompt carries no tool context) | `PLATFORM`, the tenant default | The universal, deliberately-not-fail-closed fallback every method above already had |

**The `PLATFORM` row has no settings-page UI, by design, now that this lands.** Every surface either
resolves a real workspace, resolves the dedicated `EMBEDDED` row, or falls back to `PLATFORM` only in
the edge case above — no surface *depends* on `PLATFORM` in normal operation anymore. It stays
writable through the API (a GraphQL mutation with `scope: PLATFORM` and no `workspaceId`) and is still
what `bytechef.ai.gateway.guardrails.*` properties union with wherever it resolves, but a settings page
for a row nothing normally reads would be its own kind of misleading.

**Behaviour change worth restating here, not only in release notes.** Before this session, enabling
PII redaction — or any other toggle — on a workspace's Guardrails settings page did nothing to that
workspace's Copilot traffic: Copilot always resolved the tenant-default `PLATFORM` row regardless of
which workspace the session ran in. After this session, the same toggle changes Copilot's behaviour
for that workspace, exactly as it already changed the canvas AI Agent's and AI Hub's. Tenants who
configure guardrails only through the global `bytechef.ai.gateway.guardrails.*` properties are
unaffected either way — those still union into whichever row resolves, on every surface, unchanged.

## Copilot workspace authorization (ticket 732)

**A live cross-workspace vulnerability was found while verifying this ticket's precondition — that
scoping a security control by workspace id is only a real control if that id cannot be forged — and
was closed for the Copilot route.** Full chain, with line numbers, in
`.superpowers/sdd/2026-09-02-guardrails-settings-scope/task-1-report.md`. The load-bearing links:

- `POST /internal/ai/chat/{agentId}` (`CopilotApiController`) carried no workspace gate of its own.
- `CopilotChatFacadeImpl`'s only gate was workflow-keyed and returned early whenever the run named no
  `workflowId` — every slice agent that names none, including the asset-file agents, was authorized by
  nothing.
- The client-supplied `workspaceId` was copied verbatim into the tool context (`SliceSpringAIAgent`,
  `CopilotToolContextUtils`).
- `ListAssetFilesToolCallback` — and the asset-file write tools registered on the same BUILD agent —
  called straight through to `AssetFileFacadeImpl`, which carries **zero** `@PreAuthorize` annotations
  and authorizes nothing beyond a plain repository query keyed on `workspace_id`.

**Result: before this session, a member of workspace A could read *and write* workspace B's asset
files** by posting `{"workspaceId": B}` with `agentId=asset_file` — no membership in B required.
Cross-workspace within one tenant (`TenantContext` is still set server-side), not cross-tenant; a
tenant admin passes every workspace gate by design, so the finding is about non-admin members. And it
was **per-tool, not per-surface**: the sibling `ListProjectDeploymentsToolCallback` (same tool family,
same context key) lands on a facade method that already carries
`@PreAuthorize("hasPermission(#id, 'Workspace', 'DEPLOYMENT_VIEW')")` and was never exposed by this gap
— deployment tools were gated, asset-file tools were not.

**The fix closes the Copilot route only.** `CopilotChatFacadeImpl.authorizeAndInjectWorkspace` now
refuses a workspace the caller is not a member of before any agent runs, and writes the verified id
under a server-owned state key (`CopilotConstants.STATE_VERIFIED_WORKSPACE_ID`) that
`CopilotToolContextUtils`/`SliceSpringAIAgent` read instead of the client-supplied one — the same
verify-then-overwrite shape AI Hub already used for `VERIFIED_WORKSPACE_ID`.

**`AssetFileFacadeImpl` itself gained no authorization, and remains ungated.** It is still reachable
from the AI Hub and from the `asset-file` workflow component, both of which supply their own workspace
id with nothing above them to check it. This is not a safe drive-by to fix alongside this ticket:
`AssetFileGraphQlAccessGuard` already guards the user-facing GraphQL surface (it resolves the owning
workspace per file, or verifies membership for an explicit workspace id, before any facade call), and
the only place that ever sets the `AutomationAuthorizationContext.isSkipChecks()` escape hatch is
`SkipAutomationAuthorizationAspect` in the embedded connected-user path — it does not cover the AI
Hub's artifact generators or the `asset-file` component's workflow actions, so adding `@PreAuthorize`
to `AssetFileFacadeImpl` without first tracing what each of those callers is entitled to would risk
breaking legitimate callers as readily as it closes the gap. Recommend a separate, dedicated ticket.

### A second instance of the same class, in the same session

`WorkflowExecutionSpringAIAgent#toolContext` was a **second live route to the same defect, found after
the first was closed** — reported by the user, confirmed by reading source. It matters more than a
duplicate would, because it was not merely another unverified read: it seeded the tool context from
`CopilotToolContextUtils` (which by then wrote the *verified* workspace) and then **overwrote that same
map slot** from `state.parameters`, raw client input the chat facade never touches. So it silently undid
the fix above for the four beans built on it — ask/build in `CopilotConfiguration`, ask/build in
`EmbeddedCopilotConfiguration`.

**The aliasing is what made it invisible.** `WorkflowExecutionToolContextKeys.WORKSPACE_ID`,
`AutomationToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` and
`AiHubToolInvocationContext.TOOL_CONTEXT_WORKSPACE_ID_KEY` are three constants in three modules holding
one string, `"bytechef.assetFile.workspaceId"`. A writer reaching for "its own" key is writing everyone
else's. That is deliberate and documented — it is how one runtime populates another's tools — but it
means **setting the key is never additive; it is always a clobber.** Nothing may write it downstream of
`CopilotToolContextUtils`.

The exposed tools were `WorkflowExecutionTools`. Its `getWorkflowExecution` carries an explicit
fail-closed IDOR guard comparing the execution's owning workspace to the tool-context id — sound against
the threat its comment names (an hallucinated or injected execution id, which the LLM cannot pair with a
matching workspace), and **never sound against a client choosing both**, where it inverts into an oracle
returning full run detail (task inputs, outputs, errors) iff the execution belongs to the named
workspace. `listWorkflowExecutions` has no ownership check at all beyond that id, so it enumerated
wholesale. Both are sound now that the id is server-verified.

Fixed by **deleting** the workspace override, not re-sourcing it — the verified value is already in that
slot. The sibling `environmentId` override stays: `parameters.environmentId` is the environment of the
execution on screen, legitimately narrower than the session's selected environment, and it only ever
scopes within the verified workspace. Note the residual: **`environmentId` is client-supplied on every
Copilot surface** — `CopilotChatFacadeImpl` verifies the workspace and nothing else — so cross-environment
reads within a workspace you already belong to are not currently prevented. That is a surface-wide gap,
not this agent's, and wants its own change.

`ToolContextWorkspaceVerificationTest` was hardened in response, having passed this file clean: it now
derives alias constant names from the key literals instead of matching a hand-maintained name list, drops
the `put(`/`putIfNotNull(` call allowlist entirely (this agent wrote through a local `putLong(`), and adds
a positive rule — a file naming the key and reading the run `State` must also name a verified key. It runs
from a never-up-to-date `toolContextWorkspaceScan` Gradle task rather than as an input-declaring `test`
task; the latter made every spotless task a producer of its inputs and broke `check` outright.

### A third instance, on a different surface — and what the three have in common

Found by the branch's own final review, on the **management MCP** surface rather than Copilot, and
**not introduced by this branch**. `WorkspaceScopedFlatToolCallback` and
`WorkspaceScopedSubAgentToolCallback` took `workspaceId` straight from the MCP tool-call arguments and
wrote it into both tool-context key families with no membership check.

What made it exploitable rather than merely sloppy is that the management MCP endpoint
(`/api/management/{secretKey}/mcp`) authenticates an API key that **binds the caller to no workspace** —
`ManagementMcpServerApiKeyAuthenticationProvider` checks the tenant-wide path secret, the key type and
the environment, and nothing else. Omitting `workspaceId` was worse than supplying a forged one: it fell
back to `WorkspaceService.getWorkspaces()`, a bare `findAll()`, and returned **every workspace's id and
name in the tenant** in the `workspace_required` error — the target list, on request.

Both now resolve through `AccessibleWorkspaceResolver` (the caller's own workspaces, via
`WorkspaceFacade#getUserWorkspaces`, failing closed on no authenticated user). Auto-selection, the
candidate listing and the explicit-id check all read that one membership-filtered list, so the
enumeration closes with the same change.

**The pattern across all three is worth stating, because it is the thing to look for next.** In each
case the *guard* was fine and the *input to the guard* was not:

| # | Surface | What was trusted |
|---|---|---|
| 1 | Copilot asset-file agents | `state.workspaceId`, ungated by any facade |
| 2 | Copilot workflow-execution agent | `state.parameters.workspaceId`, overwriting an already-verified value |
| 3 | Management MCP tool callbacks | the `workspaceId` tool argument, with an API key that binds no workspace |

All three land on `AssetFileFacadeImpl`, which carries **zero** `@PreAuthorize`. Three independent
surfaces have now routed an unverified workspace id into it. The earlier judgement — that annotating it
is not a safe drive-by, because `SkipAutomationAuthorizationAspect` does not cover the AI Hub's
generators or the `asset-file` component's actions — still holds on its own terms. But its premise, that
this was defence-in-depth against a class seen once, does not. **Closing that facade is the thing that
stops instance #4, and it wants its own spec'd change rather than another per-surface patch.**

One control that did hold, all three times: `ProjectDeploymentFacadeImpl` carries
`@PreAuthorize("hasPermission(#workspaceId, 'Workspace', 'DEPLOYMENT_VIEW')")`, and deployment tools were
never exposed by any of them. The exposure is per-facade, not per-surface.

## Advisor ordering rule

Three positions, fixed by `GuardrailAdvisorOrder` (CE, `platform-ai-api`) rather than by registration order:

| Advisor | Order | Position |
|---|---|---|
| `AiGuardrailsAdvisor` (EE) / `DeferredGuardrailsAdvisor` (Copilot) | `WORKSPACE_FLOOR` = `HIGHEST_PRECEDENCE` | outermost |
| `CheckForViolationsAdvisor` (per node) | `NODE_CHECK` = `HIGHEST_PRECEDENCE + 1` | inside the floor |
| `SanitizeTextAdvisor` (per node) | `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1` | inside both |

**Request.** The floor detects once, publishes the `SensitiveSpan`s it found in USER messages under
`PublishedInputSpans.CONTEXT_KEY` in the advisor context (kind, category, offsets, confidence — never the
value), then tokenizes / redacts / allows / blocks. `CheckForViolationsAdvisor` hands the published spans to
its PREFLIGHT input children through `GuardrailContext.publishedInputSpans()`; `pii` and `secret-keys` judge
**published ∪ own detection over the text they receive** and report a span-derived verdict as
`Violation.SpanViolation`. So the strictest verdict wins whatever the chain position: a workspace on `ALLOW`
or `TOKENIZE` with a node that says "block PII" blocks; a workspace on `BLOCK` throws before the node runs.

**Response.** The chain unwinds inside-out: `sanitize-text` masks model-originated PII (tokens are invisible
to it) → `check-for-violations` judges model-originated PII (tokens invisible) → the floor scans, then
restores. **Restoration only ever returns a value to the party that supplied it.** A node output check that
fired on a restored value would be blocking the caller's own input back at them — which is exactly what the
pre-constant tie did.

**Why a tie is not "undefined".** `DefaultChatClient#buildAdvisorChain` → `DefaultAroundAdvisorChain.Builder#pushAll`
does `Deque#push` (an `addFirst`) per advisor and then a stable `OrderComparator` sort; reversed-then-stably-sorted,
a tie resolves to the LAST registration. `SpringAiTiedAdvisorOrderTest` pins it;
`NodeCheckJudgesPublishedSpansTest` proves both directions through the real chain, with the negative control
that un-publishing the spans makes the same node check blind.

One consequence to know: a node `sanitize-text` → `pii` on INPUT under a tokenizing workspace masks nothing —
the floor already replaced the PII with tokens. The model sees neither; only restoration on the response differs,
and the rule above says the caller gets their value back.

## Conversation-scoped token sessions

**Enabled for exactly one surface: AI Hub chat threads.** Nothing else. The Copilot panel, the AI Hub's own
delegation sub-agents (specialist subagents keep their own per-conversation sessions keyed
`<threadId>:<agentType>`, but never publish the marker below), the canvas AI Agent, `AI_HUB` title generation,
`API_CONNECTOR`, `AI_EVAL`, and the MCP surfaces all keep the Phase 1 shape described above: a fresh
`PiiTokenSession` per call, discarded at the end of it. This list is exhaustive as of this writing, not
illustrative — a surface not named here is request-scoped.

**The test is provenance, not the shape of the id.** Every chat surface already publishes
`ChatMemory.CONVERSATION_ID` as an advisor param — Spring AI turns advisor params into the request context every
advisor reads — so a conversation id is always visible in the request context. Visibility is not trust: the
canvas AI Agent's id is a workflow-author expression, and two end users sharing one such id would share one
token store, turning the privacy feature into a cross-user disclosure. `ConversationScope.trustedKey`
(`platform-ai-api`, package `...platform.ai.guardrails`) is the one place this is decided, and it trusts an id
only when the caller ALSO published `ConversationScope.PLATFORM_ISSUED_KEY` alongside it — a marker only
platform code that issued the id can set, which a workflow author has no path to reach. Any one of these being
missing yields a request-scoped call, not an error: no `PLATFORM_ISSUED_KEY`, no verified `USER_ID_KEY`, a
blank or absent conversation id, or a `null` `workspaceId`.

**Why AI Hub qualifies.** `ai_hub_chat.thread_id` carries a global UNIQUE constraint (not per-user), and
`AiHubChatServiceImpl#create` looks up an incoming thread id by `threadId` ALONE — never `(threadId, userId)` —
so a cross-user collision surfaces as an explicit `ConflictException` rather than silently handing one user's
thread to another. That constraint is what lets a thread id serve as a store key at all. `AiHubSpringAIAgent`
publishes the marker only alongside the controller-verified user id (seeded from
`AiHubStateKeys.AUTHENTICATED_USER_ID`, itself sourced from `userService.getCurrentUser()` upstream) — never a
user id derived from the thread itself, which would be circular: the whole point of the marker is to vouch for
the id, and an id cannot vouch for itself.

**Why the canvas AI Agent never will, permanently, for one configuration — and loses nothing for the other
two.** Its conversation id is an expression the workflow author writes into the `CHAT_MEMORY` element's
`conversationId` property; nothing distinguishes an author who typed `${trigger.customerId}` from one who typed
a literal string every invocation shares. There is no platform-verified identity behind it, so this
configuration cannot be made trustworthy without changing what the canvas lets an author configure — it is not
a bug to fix later. The narrower truth: with no `CHAT_MEMORY` element on the agent, or one with no explicit
`conversationId`, nothing is retained across turns at all, tokens included — so those two configurations lose
nothing by staying request-scoped, because they had no cross-turn state to begin with.

**The ordinal watermark, and why it exists.** `PiiTokenSession.rehydrate` resumes minting ABOVE the highest
ordinal already present in the map it is handed, rather than restarting at 1. Resuming at 1 would immediately
mint an ordinal a stored token already uses; a later turn would then restore the FIRST value into the SECOND
value's token slot — a cross-value disclosure inside the same session, not merely a wrong answer. An entry
whose key does not parse as a token is dropped rather than counted toward the watermark, since a token this
session cannot parse is one it can never be asked to restore either.

**The store fails soft, on all three operations.** `PiiTokenSessionStore#load`, `#save`, and `#evict` each
swallow their own failure after logging — a `load` failure yields no tokens (the caller mints a fresh,
request-scoped session, exactly as if the surface were never conversation-scoped), a `save` failure is logged
and dropped, and an `#evict` failure is logged and dropped. A store outage costs cross-turn coherence for that
conversation; it never costs the request.

**An empty session is never saved.** `AiGuardrails#saveTokenSession` skips the write entirely when the
session minted nothing, because the store treats an empty map as "this conversation has no session" and
deletes the row on that signal. If a turn's `load` failed transiently (a blip, not a real absence) and that
turn also minted nothing, an unconditional save would write an empty map and the store would read that as
license to delete a row the conversation still needs — destroying every token an earlier, successful turn had
accumulated. Skipping the write when there is nothing new to persist leaves the earlier row exactly as it was
for the next turn to load.

**Secrets are never in the store.** A `SensitiveKind.SECRET` span is redacted irreversibly on every path,
tokenized on none of them, so it never has a token to persist — `PiiTokenSessionStore`'s contract says this in
so many words, and it is true regardless of whether a session is request- or conversation-scoped.

**Lifecycle.** A conversation's row is evicted immediately when its chat is deleted
(`AiHubChatServiceImpl` calls `PiiTokenSessionStore#evict` on delete, itself fail-soft per the store contract
above) and otherwise swept after 30 days of inactivity by `AiGuardrailTokenSessionRetentionJob`, keyed off
`last_modified_date` rather than `created_date` since a session is one row a `save` overwrites turn after turn.
The row (`ai_guardrail_token_session`) is encrypted at rest as one JSON blob per conversation — the whole
token↔value map, not per-value — via the same `Encryption` component the rest of the platform uses.

## The advisor

`AiGuardrailsAdvisor` (`-service`, package `...guardrails.advisor`) implements Spring AI's
`CallAdvisor` + `StreamAdvisor`, constructed per-request with a resolved (nullable) `workspaceId`
and its own `AiGuardrailMetrics` instance tagged with the caller's `surface`:

- **Order**: `GuardrailAdvisorOrder.WORKSPACE_FLOOR` (`HIGHEST_PRECEDENCE`), with the per-node
  `CheckForViolationsAdvisor` at `GuardrailAdvisorOrder.NODE_CHECK` (`HIGHEST_PRECEDENCE + 1`) — distinct
  by construction. Before the constants existed both declared `HIGHEST_PRECEDENCE` and the node check,
  registered later, was OUTERMOST: Spring AI breaks an `Ordered` tie toward the LAST registration
  (`SpringAiTiedAdvisorOrderTest` pins this). See "Advisor ordering rule" above for what each direction
  sees.
- **Request direction**: a fresh `PiiTokenSession` is opened per call (`adviseCall`) or per stream
  (`adviseStream`), and every USER/SYSTEM message runs through `AiGuardrails#tokenizeInputs` — PII
  becomes this session's reversible tokens, secrets still redact irreversibly, and blocked-term /
  injection / moderation handling is unchanged from `checkInputs` (the non-throwing `checkInputs`
  path is still the method's documented fallback shape for a `null` session, but both advisor
  methods always pass a real one today, so `tokenizeInputs` is what actually runs — see "PII
  tokenization" above). In `BLOCK` mode a blocking violation throws `AiGuardrailViolationException`
  (category-only message, never the offending content) which aborts the call (or, for streaming,
  becomes `Flux.error`) — the session is closed on this path too, explicitly, since it's the one
  branch where the streaming `doFinally` below never runs (nothing was ever subscribed to). In
  `REDACT_AND_CONTINUE`, the masked text is patched into the request, the call proceeds, and a
  `blocking_downgraded` event is recorded. In `ALLOW` the **unmasked** text is patched in — the
  request goes out exactly as it arrived — and `guardrail_allowed` is recorded instead. See
  "Observe mode" below.
- **Response direction**: non-streaming responses are scanned via `scanResponseText`, THEN have this
  call's session tokens restored via `restoreResponseText` — scan first, restore second, see "PII
  tokenization" above for why the order is load-bearing — and rewritten in place
  (`response_redacted` recorded when scanning changed anything; `pii_restored`/`token_unresolved`
  recorded by `restoreResponseText` itself). Streaming responses are piped through one
  `StreamingResponseRedactor`, obtained from the session-carrying
  `newStreamingResponseRedactor(Long, AiGuardrailMetrics, PiiTokenSession)` overload, for the whole
  stream so a value split across an SSE chunk boundary — token or ordinary secret alike — is never
  emitted in the clear; the redactor's held-back remainder flushes as a trailing chunk once the
  upstream stream completes, and `response_redacted` is recorded at most once per stream (via
  `isRedacted()`, checked once after the stream ends). `pii_restored`/`token_unresolved` do NOT share
  that once-per-stream shape: `StreamingResponseRedactor#restore` runs, and can record either event,
  on every `push()`/`flush()` call whose safe-cut segment contains a token — i.e. potentially several
  times over one stream, once per emitted segment, not once per stream like `response_redacted`. The
  session is released via `.doFinally(signalType -> session.close())` on the returned `Flux`, so
  completion, error, and cancellation all release it exactly once.

## The surface registry, and the three sites that were unguardable (ticket 732)

`GuardrailSurface` (`server/libs/platform/platform-ai/platform-ai-api`, package
`com.bytechef.platform.ai.guardrails`) is the complete set of surface names. Before it existed the
names were four scattered definitions — two public constants on `McpOutboundRedactorProvider`, a
private one in `CopilotGuardrailsAdvisorFactory`, and `"ai_agent"` written as a bare literal at two
call sites. A misspelled literal would have produced a surface that silently resolved its own policy
and reported its own metrics under a name nothing else used, visible only as a metric that never
moved.

Seven surfaces, each a place where user or workspace data reaches a model:

| Constant | Value | Workspace comes from |
|---|---|---|
| `AI_AGENT` | `ai_agent` | the run's `jobPrincipalId`, never a caller |
| `AI_EVAL` | `ai_eval` | the loaded `ai_observability_trace` row's `workspace_id` |
| `AI_HUB` | `ai_hub` | the chat controller's already-verified workspace |
| `API_CONNECTOR` | `api_connector` | nothing — always the tenant default (see below) |
| `COPILOT` | `copilot` | the request's server-verified workspace |
| `MCP_AUTOMATION` | `mcp_automation` | the MCP server's workspace |
| `MCP_EMBEDDED` | `mcp_embedded` | nothing — embedded has no workspace |

### Why a direct model call is not merely unguarded but unguardable

Guardrails attach as a Spring AI `ChatClient` **advisor**. A site that calls
`chatModel.call(new Prompt(...))` never enters an advisor chain, so no settings change, no policy and
no future fix can bring it under guardrails without first rewriting the call. That is the distinction
`GuardrailSurfaceCoverageTest` exists to enforce: it walks every `src/main/java` file under `server/`
and fails on any direct model call whose class is not named in `EXEMPT_DIRECT_MODEL_CALLERS` with a
reason. The two prompt-based classifiers are the load-bearing exemptions — routing them through an
advisor chain would have the moderation check moderate itself and the injection check screen its own
screening prompt, which is unbounded recursion rather than merely wasteful.

Three sites were found this way, none of them by a failing test, and all three are now fixed:

- **`TitleGenerationService`** (AI Hub, surface `ai_hub`) formatted the user's own chat messages into
  a title prompt. In a workspace with `redactPii` enabled the chat turn was redacted and the title
  generated from that same turn was not — and the title is persisted and shown in the chat list.
  `AiHubChatGraphQlController` already held the caller's verified workspace and now threads it
  through.
- **`ApiConnectorAiServiceImpl`** (surface `api_connector`, both the synchronous path and the
  `@Async` preview job) sends scraped documentation plus the free-text instructions an admin typed.
  Its null workspace is the correct answer rather than a fallback: API connectors are tenant-level
  and admin-only — every facade and service method on them carries `hasAuthority(ADMIN)` — so there
  is no workspace to resolve.
- **`AiEvalExecutor`** (surface `ai_eval`) is the heaviest. Its prompt replays a trace's own input
  and output plus its retrieval spans to a judge model, so whatever the evaluated call sent was sent
  again, unguarded. The workspace comes from the loaded trace row, not from whoever asked for the
  evaluation.

### Two things the fixes cost, both worth knowing before the next one

- **`ChatClient…call().chatResponse()` is `@Nullable`; `chatModel.call(prompt)` was not.** It returns
  null exactly when the advisor chain short-circuits — which is what a blocking guardrail does. So
  the swap introduces an NPE on precisely the path the swap exists to enable, unless the caller
  treats null as its existing "no result" case. SpotBugs
  (`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`) catches it; two of the three sites needed the fix.
- **A `ChatClient` asks the model for its default options while building the request**, before any
  call reaches the model. A bare Mockito `ChatModel` answers null there and the client NPEs in
  `DefaultChatClientUtils.toChatClientRequest`, so every existing test that previously stubbed only
  `chatModel.call(...)` must also stub `getOptions()`. The swap is a drop-in at the production seam
  and not at the test seam.

### The scan's one blind spot, measured

`GuardrailSurfaceCoverageTest`'s real input is every production source under `server/`, which Gradle
has no way to know — its up-to-date check sees only that one module. Writing an unguarded caller in
another module and running `./gradlew test` reports **green**, because the task is skipped as
up-to-date. This was observed, not theorised: the first negative-control run of the `ai_hub` fix
reported the scan passing while the defect was live on disk, and needed `--rerun-tasks` to fire. CI
builds from a clean checkout, so the tripwire is load-bearing there; locally it is advisory.
Declaring the whole tree as a task input would rebuild the module on every server-side edit, which
costs more than the staleness does.

## Surface wiring

- **Canvas AI Agent (CE component, EE capability)**: the CE `ai/agent` component
  (`server/libs/modules/components/ai/agent`) must not depend on the EE module, so it reaches the
  advisor through a CE SPI, `AiGuardrailsAdvisorProvider`
  (`server/libs/platform/platform-ai/platform-ai-api`, package `com.bytechef.platform.ai.guardrails`
  — same `ToolExecutionRecorder`-style optional-bean idiom). `getAdvisor(platformType,
  jobPrincipalId, surface)` returns `Optional.empty()` when no EE implementation is registered or
  every guardrail category is disabled for the resolved workspace. `AbstractAiAgentChatAction`
  consults it via `@Nullable ObjectProvider` and registers the advisor first in
  `getChatClientRequestSpec`. The EE implementation, `AiGuardrailsAdvisorProviderImpl`
  (`platform-ai-guardrails-service`), resolves the workspace only for `PlatformType.AUTOMATION`
  with a non-null `jobPrincipalId` (interpreted as a `ProjectDeployment` id): `jobPrincipalId` →
  `ProjectDeploymentService.getProjectDeployment` → `Project.getWorkspaceId()`, memoized 5 minutes
  in a Caffeine cache. Embedded runs, a null `jobPrincipalId`, or any resolution failure fall back
  to the tenant-default (`workspaceId = null`) settings — only the workspace SCOPE is fail-open;
  guardrails are never silently skipped because attribution failed. In `BLOCK` mode a tripped
  guardrail fails the step normally, so `on-error` / error-workflow handling applies like any other
  task failure. Surface string: `"ai_agent"`.
- **AI Hub — all LLM turns**: `AiHubSpringAIAgent` wires the advisor at the ChatClient-construction
  seam, covering the ASK/BUILD agents and the per-request override clients used for task
  model overrides. `workspaceId` comes from the already-verified `WORKSPACE_ID` state key. Surface
  string: `"ai_hub"`. Subagent one-shot delegate ChatClients (skills, research, data_analyst, the
  manager subagents, etc.) are covered too: `SubAgentGuardrailedChatClient` wraps every delegate
  registration in `AiHubConfiguration` — a stateless decorator that captures the forwarded
  tool-context workspace id per call (fresh request spec per `prompt()`, so concurrent calls cannot
  cross workspaces) and attaches a fresh advisor when guardrails are active; a BLOCK inside a
  delegate surfaces as a leak-free tool error (`ToolErrors.runtimeFailure`, class name only).
  Residuals, recorded in the spec's decisions log: the parent agent never re-scans tool outputs
  (a delegate's completion reaches the client via tool-result events without passing the parent's
  response scan — the delegate's own advisor is what guards that content), and the MCP-surface
  manager subagent invocations are a different surface (management MCP, no AG-UI stream), still
  unguarded there.
- **Gateway**: see `.agents/ai-gateway-guardrails.md` — the gateway wraps the engine in its own
  adapter rather than using the advisor (its integration point is `AiGatewayFacadeImpl`, a plain
  request/response DTO pipeline, not a Spring AI `ChatClient`). `AiGatewayFacadeImpl#chatCompletion`
  (the synchronous path) opens one `PiiTokenSession` per HTTP exchange, threads it through
  `AiGatewayGuardrails#apply` on the request side, then on the response side calls `#scanResponse`
  and `#restoreResponse` as two separate steps (not the combined 4-arg `#redactResponse`, which
  exists for callers with nothing to sandwich between scan and restore) with tracing running on the
  scanned-but-not-yet-restored response in between — see "PII tokenization" above for why. All of it
  is closed in a `finally` around both request- and response-direction calls so a downstream
  exception still releases the session. The streaming `chatCompletionStream` path and the embeddings
  endpoint do not thread a session and still redact irreversibly — see "PII tokenization" above.
- **Copilot's own chat surface (closed this session, ticket 732)** — distinct from the "Copilot panels"
  gap in the appendix below, which is about Copilot *delegating* to AI Hub's shared intelligent-tool
  catalog, still unguarded. This entry is about Copilot's own `*SpringAIAgent`/`ChatClient` construction
  in `ai-copilot-service` (CE) and its EE counterparts `automation-ai-copilot`/`embedded-ai-copilot`.
  `CopilotGuardrailsAdvisorFactory` (CE, `ai-copilot-service`, package `...advisor`) is the single seam
  all three modules go through — `guardrailsAdvisors()` returns a `DeferredGuardrailsAdvisor` that
  resolves the session's server-verified workspace on every call, read off the prompt's
  `ToolCallingChatOptions` tool context (the same channel `AgentToolInvocationContext` travels on) via
  `AiGuardrailsAdvisorProvider#getAdvisorForWorkspace`, falling back to the tenant default
  (`workspaceId = null`) only when the prompt carries none — none of Copilot's panel agents or
  delegation sub-agents carry a `jobPrincipalId` (they're scoped to a user and a project/workspace, not
  a workflow run), so this workspace-taking entry point, added this session (ticket 732) alongside
  `getAdvisor`, is what makes Copilot workspace-scoped at all; **before this session the resolved
  workspace here was unconditionally the tenant default — see "Scope resolution, per surface" and
  "Copilot workspace authorization" above.** The tool-boundary metrics supplier
  (`CopilotGuardrailsAdvisorFactory#getMetrics`) is the one piece of this factory NOT yet workspace-aware
  — see "Metrics" below for why and what closing it would take. Every advisor returned here is tagged
  with the fixed surface string `"copilot"` (`CopilotGuardrailsAdvisorFactory.GUARDRAILS_SURFACE`), and
  the factory returns an empty list rather than `null` on a CE build or an EE app without the guardrails
  module, so every caller's `.advisors(...)`/`.defaultAdvisors(...)` call is a safe no-op either way.
  `automation-ai-copilot` and `embedded-ai-copilot` both import this same CE class rather than each
  building their own — so all three modules, and both directions of the EE split, currently share one
  surface tag; **`ai_hub`-style per-surface splitting does not exist here yet, so automation's and
  embedded's Copilot redaction volume cannot be told apart in the `copilot`-tagged metrics.**
  Enforcement is a source-scan test, not a runtime one, and each of the three modules has its own
  module-scoped `GuardrailsAdvisorCoverageTest`
  (`grep -rl "class GuardrailsAdvisorCoverageTest" server --include="*.java"`, confirmed present in
  `ai-copilot-service`, `automation-ai-copilot`, and `embedded-ai-copilot`, all passing at HEAD): it walks
  every `*Configuration.java` file under the module and fails the build if any
  `[A-Za-z]+SpringAIAgent.builder()` chain never calls `guardrailsAdvisors(`, and if any
  `ChatClient.builder(` call sits outside that module's own `chatClientBuilder(ChatModel)` wrapper.
  **Both invariants are in all three tests.** The `ChatClient.builder(` check started in the two EE
  modules and was backported to CE (ticket 732). The agent-chain check asserted only `.advisors(` until
  the same ticket tightened it: 33 of the 47 sites attach some other advisor — chat memory, a vector
  store — and satisfied `.advisors(` whether or not guardrails were among them, so the test that existed
  to catch the fourth round of this gap could not have caught a fifth of the same shape. Verified by
  negative control: replacing one site's `guardrailsAdvisors()` with `List.of()` now fails the scan and
  previously did not. Counted directly against the
  `*Configuration.java` files each test scans (`find <module>/src/main/java -name "*Configuration.java"
  -exec grep -oE "[A-Za-z]+SpringAIAgent\.builder\(\)" {} \;`, cross-checked against a direct
  `ChatClient.builder(` count with comments stripped): `ai-copilot-service` carries 33
  `*SpringAIAgent.builder()` call sites plus the one `ChatClient.builder(` inside its wrapper,
  `automation-ai-copilot` carries 8 plus 1, `embedded-ai-copilot` carries 6 plus 1 — 47
  `*SpringAIAgent.builder()` sites and 3 `ChatClient.builder(` wrapper sites, 50 in total, every one now
  routed through `CopilotGuardrailsAdvisorFactory`. **Treat any count quoted elsewhere for this work with
  suspicion and recompute it** — `AiGuardrailsAdvisor`'s own javadoc previously asserted "Both the AI Hub
  and Copilot chat surfaces register one instance of this advisor each" while Copilot registered zero,
  and this doc's own in-progress draft under-counted a related gap four times running before landing on
  a verified figure; the advisor's javadoc was corrected in the same commit that fixed the gap to stop
  naming specific covered surfaces at all, precisely because that kind of claim is what goes stale.

**Fifth mechanism, found and closed (ticket 732).** The count above measures the two builder idioms the
three `*Configuration.java` scans look for. It could not see a third way Copilot reaches a model:
`PropertyCopilotGeneratorImpl` and `WorkflowDescriptionCopilotGeneratorImpl` (EE
`server/ee/libs/ai/ai-copilot/ai-copilot-service`) called `chatModel.call(new Prompt(...))` directly.
They were invisible to those scans twice over — wrong file-name filter (`*Impl.java`, not
`*Configuration.java`) and wrong idiom. **A direct `ChatModel` call cannot be guarded at all**:
`AiGuardrailsAdvisor` is a `ChatClient` advisor, so there is no hook at the `ChatModel` layer. The
property generator is the one that mattered — its prompt embeds `WorkflowNodeOutputFacade` sample
output, data captured from real test runs against real connections. Both now go through
`CopilotGuardrailsAdvisorFactory#guardedChatClient`, and that EE module — which had no coverage test at
all — gained a `GuardrailsAdvisorCoverageTest` that scans every `.java` file under `src/main/java` (not
only `*Configuration.java`) for direct `ChatModel` invocations and for `ChatClient` invocation
statements missing `guardedChatClient(`. Both invariants negative-controlled.

**Nothing is resolved at construction any more (ticket 732).** Every `*SpringAIAgent` bean is a
singleton, so `guardrailsAdvisors()` ran once per bean at context refresh — while `AiGuardrails#isActive`
reads runtime settings editable from the guardrails settings UI. The staleness was asymmetric in the
dangerous direction: **enabled after boot did nothing at all** (no advisor had been attached, so Copilot
stayed unguarded for the life of the JVM while the settings UI reported guardrails as on), whereas
disabled after boot mostly self-corrected (the attached advisor re-reads workspace policy per call).
`guardrailsAdvisors()` now returns a `DeferredGuardrailsAdvisor` unconditionally — it holds the same
chain position at the same `HIGHEST_PRECEDENCE` order, resolves per call, and passes straight through
when nothing resolves, so CE builds and guardrails-off workspaces behave as before. The tool boundary
had the same defect in its metrics; `PiiTokenBoundaryToolCallingManager#wrap` now takes a
`Supplier<SensitiveDataMetrics>` rather than an instance (a single method, not an overload — overloading
on a `@Nullable` parameter makes every `wrap(..., null)` site ambiguous, since `null` has no static type
to resolve against).

**Known cost of that fix, not yet addressed.** `AiGuardrails.resolvePolicy` → `findSettings` →
`propertyService.fetchProperty` is **uncached**. When guardrails are active nothing changes — the advisor
already made 5+ uncached `resolvePolicy` calls per request and this adds one. When they are inactive, a
Copilot turn previously did zero settings reads (no advisor attached) and now does one. Small — an
indexed single-row read per human-initiated chat turn, not a hot loop — but real. A short-TTL cache in
`resolvePolicy` would remove it and also collapse the existing per-request reads; it is deliberately not
done, because a cache reintroduces exactly the staleness class this fix eliminated and the TTL is a
product decision.

## Settings UI

Workspace Settings → **AI Agents** → **Guardrails** tab
(`client/src/ee/pages/settings/automation/ai/guardrails/AiGuardrails.tsx`, rendered by
`AiAgents.tsx` under the tabbed route `/automation/settings/ai/agents/:tab`, so the page's URL is
`/automation/settings/ai/agents/guardrails`; `/automation/settings/ai/agents` redirects there.
`PrivateRoute(ROLE_ADMIN)` + `EEVersion` gated). GraphQL:
`aiGuardrailsWorkspaceSettings(workspaceId: ID, scope: AiGuardrailsSettingsScope)` returns `null` on a
missing row (the client synthesizes all-off/`BLOCK` defaults rather than the server manufacturing a
default record); `isAuthenticated()`-gated. `updateAiGuardrailsWorkspaceSettings` is `ROLE_ADMIN`-gated
on the GraphQL controller itself (the A2A precedent — no facade layer exists to own the check for this
feature) and, since ticket 732's scope discriminator (see "Settings storage" above), validates a
mismatched `scope`/`workspaceId` pair (e.g. `EMBEDDED` with a non-null `workspaceId`) itself, throwing
`GraphQlBadRequestException` rather than letting the record's compact-constructor
`IllegalArgumentException` reach `GlobalDataFetcherExceptionResolver` unmapped and surface as an opaque
`INTERNAL_ERROR`. The blocked-terms editor splits on comma+newline and re-joins comma-only, matching
what `AiGuardrails`' `parseBlockedTerms` expects. The old AI Gateway settings page's guardrail controls
relocated here; the gateway page links to this page for workspace-level configuration and keeps only
its own per-project guardrail overlay.

**Embedded gets its own page (ticket 732), not a checkbox on the workspace one.**
`client/src/ee/pages/settings/embedded/guardrails/EmbeddedGuardrails.tsx`, registered beside API Keys,
Signing Keys, and Variables under `/embedded/settings/guardrails` (`PrivateRoute(ROLE_ADMIN)` +
`EEVersion` gated, same as the automation page). It mirrors the automation page's fields and mutation
minus the workspace guard, always passing `scope: AiGuardrailsSettingsScope.Embedded` and no
`workspaceId` on both the read query and the write mutation — pinned by
`EmbeddedGuardrails.test.tsx`'s assertion that the query call actually carries `{scope: 'EMBEDDED'}`,
added after review flagged that nothing previously proved the read wiring couldn't silently regress to
reading the wrong row unnoticed.

## Context keywords (ticket 732, queue item 6 Phase A)

Confidence scoring gave every pattern a score reflecting how specific its own shape is, and
`SensitiveDataRedactor` drops anything below `DEFAULT_MIN_CONFIDENCE` (`0.4`). That was right — the
alternative was redacting every order number — but it was paid for, and the bill was larger than the
specs recorded.

**Eleven pattern types scored `0.2` and were therefore detected by nothing at the default threshold:**
`MEDICAL_LICENSE`, `US_BANK_NUMBER`, `US_DRIVER_LICENSE`, `US_PASSPORT`, `IT_DRIVER_LICENSE`,
`IT_PASSPORT`, `IT_IDENTITY_CARD`, `PL_PESEL`, `AU_TFN`, `IN_VOTER`, `IN_PASSPORT`. A real US passport
number went to the model in the clear, because `[A-Z]\d{8}` matches an order code just as well.

A score fixed per pattern cannot fix that, because the information needed is not in the match — it is
next to it. `Passport: A12345678` and `Order A12345678` differ only in context.

### The mechanism

`PiiPatternCatalog.PiiPattern` carries an optional `ContextRule(keywords, window, score)`. When a
keyword occurs within `window` characters **either side** of a match, `RegexPiiDetector` emits the
rule's score instead of the pattern's base score, and the existing confidence filter does the rest.
All eleven types above now carry keywords, window 40, promoted to `0.9`.

Promoted high rather than medium on purpose: a naming keyword beside a shape this specific is strong
evidence, and a medium promotion would leave the type filtered at any workspace that raised its
threshold — so the most careful workspaces would be the ones still missing real identifiers.

### Raise only — this is a security property, not tidiness

`PiiPattern`'s constructor **rejects** a rule whose score does not exceed the base. A rule that
*lowered* confidence would be an off switch an attacker writes into the prompt: put `order number:` in
front of a real SSN and the guardrail stops firing. Raising cannot be abused that way — the worst an
attacker achieves is being redacted.

The window is bounded for the mirror-image reason: without it, one keyword anywhere in a long document
would promote every match in it, reintroducing exactly the false positives the rubric exists to
suppress.

### Two things that are not there

- **Keyword lists are catalog content, not operator-editable.** They are reviewed with the patterns.
  An operator wanting different keywords writes a custom rule — which is Phase B.
- **No `context_keyword_promoted` metric.** `SensitiveDataDetector#detect(String)` takes no metrics
  parameter, so reporting would need an SPI overload. For a reviewed built-in list the tests are the
  proof; the metric earns its cost once operators write keywords.

### Phase B — operator-supplied rules

Built. A workspace defines its own patterns, stored in `ai_guardrail_custom_rule`, evaluated by
`CustomPatternEvaluator` alongside the built-in catalog.

**The gate is `CustomPatternValidator`, and it lives in the service rather than the controller** — so
every write path passes it, not just GraphQL. A validator the API remembers to call is one a future
importer or admin tool walks around, and an unvalidated operator pattern is an unbounded loop on the
request thread. Four defences:

1. **Every quantifier must be bounded.** This replaced the design's list of shapes to reject (nested
   quantifiers, overlapping alternation, unbounded backreferences), which needs a regex parser to
   apply and still misses cases. One scan, and catastrophic backtracking needs an unbounded repetition
   to explode into. Free dividend: a pattern whose every quantifier is `{n,m}` has a computable
   maximum match length, so **custom rules are windowable by construction** — the invariant item 7's
   withdrawn windowing could never establish for the built-in catalog. The cost is `\d{1,20}` instead
   of `\d+`.
2. **A timing check** whose adversarial inputs are derived from the candidate pattern. **Never from a
   list of famous ReDoS patterns**: measured on this JVM, `(a+)+$`, `(a*)*b`, `([a-zA-Z]+)*$` and
   `(a|aa)+$` all complete in under a millisecond at any input length worth testing, so a
   reputation-seeded check approves everything. `(x+x+)+y` — about three seconds on 1,000 characters —
   is the one that does explode, and it was found by probing.
3. **A length cap** on the pattern source, and a per-workspace **count cap** (default 50).
4. **A `StackOverflowError` catch.** `(a|aa)+$` over 4,000 characters overflows in ~8ms — faster than
   any deadline, and an `Error` the `RuntimeException` catch cannot see. Without it a malformed rule
   kills the save request instead of being rejected with a reason.

Rejections record `custom_rule_rejected_{name,length,syntax,timing}`. The defence is in the event name
rather than a metric tag because the counter carries only `event` and `surface` to stay cheap on
unbounded multi-tenant deployments, and four bounded names keep it queryable without a new dimension.

### Three things about custom rules that surprise people

- **A custom PII rule is gated on the workspace's `redactPii` master switch.** Write a rule, enable
  it, and it detects nothing if PII redaction is off. That is coherent — a custom PII rule *is* PII
  redaction, and exempting it would make custom rules the one way to get redaction a workspace never
  asked for — but it has its own test saying why, because someone will otherwise "fix" it.
- **Writes are `ROLE_ADMIN`, not a workspace scope.** `AI_GATEWAY_VIEW` is the only AI-gateway scope
  that exists and it maps to `VIEWER`. An invented `AI_GATEWAY_EDIT` would not create a scope: an
  unregistered token makes `hasPermission` **deny**, so every write would fail for everyone including
  admins. A test asserts no mutation names a scope, which caught exactly that mistake.
- **Rules are compiled per resolution, not cached.** A cache would introduce a window where "I just
  enabled my rule" takes effect at an unpredictable time. If it becomes measurable the fix is
  invalidation on write, not expiry on a timer.

### What custom rules do not cover

The MCP outbound paths resolve policy from an already-fetched settings row and carry no custom rules;
an unattributed (null-workspace) call applies none by definition. Both are stated rather than implied.

## Violation records (ticket 732, queue item 8)

Per-detection drill-down: which pattern fired, where in the text, how confident, on whose request,
and what was done about it. None of which `bytechef_ai_guardrail` can answer — it carries only
`event` and `surface`, deliberately, so it stays cheap on unbounded multi-tenant deployments and can
say how much is happening but never what.

### The one decision everything else follows from

**A record never stores the matched value, and `ai_guardrail_violation` has no column for one.**

A record useful for debugging obviously wants the matched text. Storing it would build a plaintext
database of exactly the values the guardrails exist to redact — with a longer retention than the
request that produced them, and a read API in front. So the row carries `category` (the rule that
fired), `span_start`/`span_length` (which locate the match without reproducing it), `confidence`,
`action`, `surface`, `workspace_id`, `environment` and `principal`.

The escape hatch — "a debug mode that keeps the raw value for 24 hours" — is **forbidden rather than
merely absent**. It is the thing that will be asked for, it is genuinely useful, and it converts this
table into the database it must not be, behind a toggle someone will leave on. The prohibition is
written in the entity javadoc, the changelog and `master.xml`, because whoever adds the column will
be reading one of those three and not this file.

`principal` is the one field that is PII and is stored anyway: it is attribution — who made the
request, which the workspace already knows — not the detected value. The test that pins the rule
uses a *different* email for the principal than for the matched value, precisely so it can tell those
two apart.

### Where the record is assembled, and why not in the engine

`AiGuardrails` computes the winning spans (it already did, to decide which counters to increment) and
now carries them out on `GuardrailCheckResult`. It does **not** assemble the record, because it knows
neither the surface nor the resolved `BlockingMode` — the advisor resolves the mode *after*
`checkInputs` returns. A record that could not say what was done about a detection would read every
observe-mode counterfactual as an enforcement.

So `AiGuardrailsAdvisor` assembles and submits, including **before the `BLOCK` throw**: a blocked call
is the one an operator is most likely to be asked about, and it would otherwise be the single case
with no record.

Redactions are recorded too, not only blocking violations. "Which pattern fired, and where" is mostly
a question about redactions; a store holding only blocks would answer it for the rarest case alone.

### Volume control

- **Off by default.** The counter stays the free path.
- **Asynchronous, on a bounded pool with an ABORT policy.** A record is evidence about a call, not
  part of it. `CallerRunsPolicy` — the tempting default — would quietly invert the guarantee under
  exactly the load that makes it matter.
- **Dropped rather than blocking**, and drops are counted. Without `violation_record_dropped`, "my
  rule never fired" and "we lost the row" look identical to the operator debugging that rule.
- **A daily per-workspace cap**, reported once per workspace per day rather than once per suppressed
  record. Sampling was the obvious alternative and is worse: a 1-in-N sample makes "did my new rule
  fire on *this* request?" unanswerable, which is the question the feature exists for. The cap
  preserves completeness up to a limit instead of degrading it everywhere.
- **Retention 30 days**, not audit's 365: these are machine detections at request volume, not human
  actions.

Events: `violation_record_written`, `violation_record_dropped`, `violation_records_capped`.

### Two things that are not built yet

- **Enablement is a global flag** (`bytechef.ai.guardrails.violation.enabled`), not the per-workspace
  setting the design specifies. The property that decision protects — a deployment that never asked
  for drill-down writes nothing and pays nothing — holds in full; what is deferred is granularity, and
  the workspace field is additive when it lands.
- **No keyed hash, so recurrence does not work.** The design's optional per-tenant HMAC would let an
  operator see the same value recurring across requests without seeing it. It is the one field with a
  real attack (a key plus a candidate list confirms values), so it needs key management decided first.
### The read surface

`aiGuardrailViolations(workspaceId, limit)` — GraphQL only, no settings-page UI, matching every
neighbouring guardrail feature. Authorization mirrors the settings controller: a workspace read needs
`AI_GATEWAY_VIEW` on that workspace, and the null-`workspaceId` tenant-default bucket is admin-only
because it has no workspace to scope against.

Two absences are deliberate and both are pinned by tests:

- **No by-id read.** Every read is scoped to a workspace the caller was already authorized for, so no
  lookup here can confirm another workspace's record exists. The probe-oracle problem is closed by not
  having the lookup rather than by making it return not-found.
- **No write surface.** Records are written by the guardrail path and swept by retention. A record
  whose subject can rewrite it is not evidence of anything.

`limit` defaults to 100 and is clamped to 1000 in the service. These rows exist at request volume, so
an unbounded read is a way to pull a workspace's whole retention window into one response.

## Detection bounds (ticket 732, queue item 7)

Sensitive-data detection is synchronous, on the request thread, before the model call, over the full
text of every prompt message. That text routinely carries a retrieved document, a pasted file, a tool
result or a whole conversation history — the model provider's context window is the only upstream
bound, and that is megabytes.

**The engine's fail-open catch cannot help, and this is the thing to understand about the whole
area.** `SensitiveDataRedactor#collectSpans` catches `RuntimeException` and continues without the
failing detector's spans. But a slow detector never throws — it returns, eventually. The safety valve
is shaped for a detector that breaks, not one that takes a minute.

Two bounds, both on `SensitiveDataRedactor#detectCandidates`, which is the single funnel every
detection path goes through:

- **A cooperative deadline** (`bytechef.ai.guardrails.detection.timeout`, default 2s) over the whole
  pass, checked between detectors. On expiry the remaining detectors are abandoned, the spans found so
  far are applied, and `detector_timed_out` fires naming the detector that was running.
- **A size above which an unwindowable detector is skipped**
  (`bytechef.ai.guardrails.detection.max-unwindowable-input`, default 256 KiB), recorded as
  `detector_skipped_oversize`. "Unwindowable" is `streamSafe() == false` — OpenNLP. Such a detector
  cannot be given a fragment by its own contract, so on a very large input the choice is between
  skipping it and handing it a truncated prefix. **A skip, deliberately**: both lose coverage, but a
  truncated prefix reports a clean scan of a document it only partly read, while a skip reports on a
  named counter that it did not run.

**Ordering carries a guarantee.** Stream-safe detectors run first, to completion; unwindowable ones
get the remaining budget. So the regex pass — where every identifying pattern lives — cannot be
starved by a detector that runs long, and no detector has to classify its own cost.

### Three things this deliberately does not do

- **It does not window.** Windowed detection was built and withdrawn: it is only behaviour-preserving
  if every pattern has a bounded maximum match, and that invariant does not hold in the catalogs.
  Forcing it produced a detection regression in `EMAIL_ADDRESS`. See the design spec's §0 for the full
  account, including four successively-less-vacuous versions of the test that was supposed to prove
  the invariant.
- **The deadline is enforced inside a match, not only between detectors.** `MatchDeadline#bound`
  hands the regex engine a `CharSequence` that throws `DetectionTimeoutException` once the deadline
  passes, so a pattern already backtracking is interrupted rather than left running. It uses the same
  deadline instant the between-detector checks use, so there is one budget, not two.
- **A `StackOverflowError` from a detector is caught too.** Deep recursion overflows in milliseconds —
  faster than any useful deadline — and is an `Error`, so the fail-open `catch (RuntimeException)`
  never saw it. Uncaught it takes the guarded call down, which is worse than losing one detector's
  spans.
- **It fails open.** A timed-out pass returns partial coverage rather than refusing the call, on every
  `BlockingMode` including `BLOCK`. A slow detector is not evidence of an attack, and refusing would
  turn a latency defect into an outage on a threshold nobody configured. `detector_timed_out` firing
  at all means a workspace is running unprotected on some fraction of its traffic — which is why the
  metric is mandatory rather than nice-to-have.

## Observe mode (`BlockingMode.ALLOW`)

Before this existed, `BlockingMode` offered only `BLOCK` and `REDACT_AND_CONTINUE`, so an operator
who wanted to know what a guardrail *would* catch had to turn it on and accept the consequences on
live traffic. Every guardrail change was a leap, and the cost of being wrong was a blocked or
mangled production call. `ALLOW` is the third value: the violation is detected and counted, and the
content is forwarded unmodified.

The intended sequence is turn a rule on in `ALLOW`, read `guardrail_allowed` for a week of real
traffic, then promote to `REDACT_AND_CONTINUE` or `BLOCK`. That is also why there is no rule tester —
testing against real traffic dominates testing against samples.

### What it changes, per violation category

| Category | Forwarded under `REDACT_AND_CONTINUE` | Forwarded under `ALLOW` |
|---|---|---|
| `blocked_term` | the term replaced with `[REDACTED_BLOCKED_TERM]` | the term left in place |
| `injection_flagged` | the text unchanged | the text unchanged — **no difference**; injection has no locatable span, so nothing was masked either way |
| `moderation_flagged` | the whole message replaced with `[REDACTED_MODERATED]` | the message left in place |

`injection_flagged` still records `guardrail_allowed` even though the text is identical, because the
operator needs to know the classifier fired.

### `ALLOW` does not turn PII redaction off

`BlockingMode` governs the three **blocking** guardrails only — blocked terms, injection,
moderation. PII and secret redaction are applied inline on every path and are unaffected by it, so a
prompt under `ALLOW` still has its email addresses tokenized. This trips people up because "forwarded
unmodified" reads absolute; it is relative to the blocking verdict.

### Where the arm lives, and why not in the engine

`AiGuardrails` stays mode-agnostic. `checkInputs`' contract is that the CALLER decides how to handle
a blocking violation — that is the whole reason it exists next to the always-throwing
`applyToInputs`, which the AI Gateway adapter depends on for its HTTP 422. So the engine computes
both candidate texts and `GuardrailCheckResult` carries them as `text()` (blocking-masked) and
`unmaskedText()` (PII/secret redaction only); `AiGuardrailsAdvisor` picks between them at the
`BlockingMode` switch it already had. Pushing the mode down into `AiGuardrails` would have put a
mode field on `EffectivePolicy` and created a standing obligation to prove the gateway's throwing
path ignores it.

`resolveBlockingMode` is still called only when something is actually blocked, so an unblocked
request costs exactly the settings lookups it did before `ALLOW` existed.

### The thing to say out loud before enabling it

**Under `ALLOW`, moderation-flagged content reaches the model.** That is the point — you are
observing, not enforcing — and it is opt-in per workspace. But `BLOCK` is the default for a reason,
and a workspace left in `ALLOW` indefinitely has a guardrail that reports and protects nothing.
`guardrail_allowed` is what makes that visible; a workspace with a rising `guardrail_allowed` and no
`blocking_downgraded` is one that never finished the rollout.

## Metrics

`bytechef_ai_guardrail{event, surface}` — `surface = gateway | ai_agent | ai_hub | copilot` (`copilot`
covers `ai-copilot-service`, `automation-ai-copilot`, and `embedded-ai-copilot` under one shared tag —
see "Surface wiring" above for why automation's and embedded's volume cannot be split apart today).
Events:
`pii_redacted`, `secret_redacted`, `blocked_term`, `moderation_flagged`, `injection_flagged`,
`response_redacted`, `detector_failed` (a `SensitiveDataDetector` threw and was skipped — see below
for which call paths actually count it), `below_confidence_threshold` (at least one candidate span
was dropped from a call because its confidence fell below `minConfidence` — see "Confidence scoring"
above; follows the identical per-surface split described below for `detector_failed`, through the
same `SensitiveDataMetrics` seam), plus `blocking_downgraded` when `REDACT_AND_CONTINUE`
converts a would-be block. **Tokenization adds three events, recorded incidence-style — at most once
per call, not once per span or token, matching how `pii_redacted`/`secret_redacted` are already
counted**: `pii_tokenized` (a `tokenizeInputs`/session-carrying `applyToInputs` call turned at least
one PII span into a token instead of a `[REDACTED_*]` placeholder — the tokenizing sibling of
`pii_redacted`, mutually exclusive with it per call since a call either has a session or doesn't),
`pii_restored` (`restoreResponseText` or the streaming redactor's internal restore step substituted
at least one known token back to its real value), and `token_unresolved` (one or more `[PII_*]`-shaped
spans in the text being restored could not be resolved back to a value — an anomaly: the model
mangled a token, or emitted a token-shaped string this session never minted; recorded alongside, not
instead of, `pii_restored` when both happen in the same call). **The tool boundary (2026-08-31
follow-up, ticket 732) adds two more, same incidence-style shape**: `tool_args_restored` (at least one
token was substituted into a tool call's arguments before the tool ran) and `tool_result_tokenized`
(at least one value in a tool's result was tokenized or redacted before reaching the model);
`token_unresolved` is reused, not duplicated, for an unresolved token found in a tool call's
arguments. **A fourth, `assistant_history_retokenized`, was added by the 2026-09-01 follow-up** (at
least one assistant tool-call argument in the returned conversation history was retokenized before
going out — see "Tool boundary" above for why that history needed a second pass at all). See "Tool
boundary" above for the mechanism, and for a verified gap: these four events are
never actually recorded for `surface=ai_agent` today, because nothing in the codebase supplies
`AgentToolCallingManagers`'s `ObjectProvider<SensitiveDataMetrics>` with a real bean. `moderation_flagged` is no longer gateway-only (superseded, F2 of the standalone-guardrails
follow-up): `AiGuardrails#checkInputs` now also checks moderation, so `ai_agent`/`ai_hub`-tagged
`moderation_flagged` events are emitted whenever a workspace enables moderation and a moderation
classifier bean is configured — the engine's `applyToInputs` (the gateway's own throwing path)
still never moderates, so gateway-tagged `moderation_flagged` events keep coming exclusively from
the gateway adapter's own resolution, unchanged.

Two independent `AiGuardrailMetrics` instances exist per call path, split by ENTRY POINT rather
than by event: `AiGuardrails#applyToInputs` (the gateway adapter's unconditional-throw entry
point) always records through the engine's own internal bean —
`@ConditionalOnProperty(bytechef.ai.gateway.enabled=true)`, so it is a no-op (engine still
functions, just doesn't emit) when the gateway is disabled, and always tagged `surface=gateway`
regardless of which caller triggered it, since that bean's `surface` is a single deployment-wide
property. `AiGuardrails#checkInputs` (the advisor's non-throwing entry point) takes an
`AiGuardrailMetrics` parameter instead and records EVERY request-direction event
(`pii_redacted`, `secret_redacted`, `blocked_term`, `injection_flagged`, `moderation_flagged`)
through whatever instance the caller supplies — `AiGuardrailsAdvisor` always passes its own per-request instance, tagged
with the caller's own `surface` (`ai_agent` / `ai_hub`), constructed by
`AiGuardrailsAdvisorProviderImpl` / `AiHubSpringAIAgent` respectively and independent of the
gateway toggle. The advisor also uses that same instance for the events it decides on its own
(`blocking_downgraded`, `response_redacted`), so every metric on the advisor path — request- and
response-direction alike — carries an accurate per-surface tag and emits regardless of whether the
gateway is enabled; only the gateway's own `applyToInputs` path is gated. Wired via
`ObjectProvider<MeterRegistry>` so registry-less apps start clean.

**`detector_failed` follows the same per-surface split as every other event, but it took a second
pass to get there.** `SensitiveDataRedactor`'s detect/redact methods take an
`@Nullable SensitiveDataMetrics` — the CE seam described in "Module and engine" above, since the CE
redactor cannot depend on the EE `AiGuardrailMetrics` type directly — and record `detector_failed`
through whatever is handed to them. EE always passes its own `AiGuardrailMetrics`, which implements
that interface, so every path that a surface-tagged instance can reach now hands one in:

- **Request direction** — `AiGuardrails#checkInputs` → `#checkInput` → `#redactPiiAndSecrets` threads
  the caller-supplied instance, so the event lands under the calling surface exactly like
  `pii_redacted`/`secret_redacted`.
- **Response direction** — `scanResponseText` and `redactAll` each have an overload taking an
  `@Nullable AiGuardrailMetrics`, and `AiGuardrailsAdvisor` passes the same per-surface instance it
  already uses for `response_redacted`. A detector failing while scanning an `ai_agent`/`ai_hub`
  completion is therefore counted under THAT surface, and counted at all regardless of the gateway
  toggle.
- **Streaming** — `StreamingResponseRedactor` takes an `@Nullable AiGuardrailMetrics` through its
  constructor and threads it into all three redactor calls;
  `AiGuardrails#newStreamingResponseRedactor(workspaceId, metrics)` is how the advisor supplies it.

Two paths deliberately still record through the engine's own constructor-injected bean, and that is
correct rather than a gap: the gateway adapter's `applyToInputs`, and the no-argument
`newStreamingResponseRedactor()` / bare `redactPii`/`redactSecrets`/`redactAll` calls the gateway's
project overlay makes. Those callers ARE the gateway, so the bean's fixed `surface=gateway` tag is
accurate for them — and being gated on `bytechef.ai.gateway.enabled` costs nothing, since the gateway
is by definition enabled when they run.

Note there is deliberately no `newStreamingResponseRedactor(AiGuardrailMetrics)` single-argument
overload: it would be ambiguous with `newStreamingResponseRedactor(Long workspaceId)` for a bare
`null` argument, forcing callers to cast. A caller supplying its own metrics instance passes it
alongside the workspace id.

**Known observability gap, not yet addressed (ticket 732): Copilot's tool boundary goes silently
uncounted for exactly the workspaces that turned guardrails on.** Now that `DeferredGuardrailsAdvisor`
resolves the session's server-verified workspace (see "Copilot workspace authorization" and "Surface
wiring" above), redaction on the request/response path is correctly workspace-scoped. The tool-boundary
metrics supplier is not: `CopilotGuardrailsAdvisorFactory#getMetrics` still calls
`AiGuardrailsAdvisorProvider#getMetrics(null, null, GUARDRAILS_SURFACE)`, which resolves against the
tenant-default row regardless of which workspace the call actually ran in — its own javadoc names the
constraint (a zero-argument `Supplier<SensitiveDataMetrics>` shape, fixed once at wrap time with no
per-call channel to pass a workspace through, shared with the canvas AI Agent's and AI Hub's tool
search's identical suppliers). `AiGuardrailMetrics` tags its counters only by `event` and `surface` —
never by workspace — so this is not a "wrong tag" gap: for a workspace that has guardrails on while the
tenant default has them off (the exact configuration this ticket exists to support), tool-boundary
redaction runs correctly, but `AiGuardrailsAdvisorProviderImpl#buildMetricsIfActive` returns `null`
against the inactive tenant default, so `tool_args_restored` / `tool_result_tokenized` /
`assistant_history_retokenized` / `token_unresolved` are never recorded for that call at all. The
tool boundary is observable only for workspaces whose guardrails happen to match the tenant default —
which is backwards from what an operator would expect, since it is precisely the workspaces that
diverge from the default an admin most wants visibility into. Closing this means giving the metrics
supplier access to the tool context so it can call `AiGuardrailsAdvisorProvider#getMetricsForWorkspace`
instead of the zero-argument form — deliberately deferred, since it changes a `Supplier` signature
shared by three surfaces (Copilot, the canvas AI Agent component, and AI Hub's tool search), not a
Copilot-local fix.

## Appendix: extracted from CLAUDE.md

### AI Guardrails (EE, standalone across surfaces)

Content guardrails (PII/secret redaction, blocked terms, moderation, injection detection, response/streaming
redaction) live in the standalone EE module `platform-ai-guardrails` (`-api`/`-service`/`-graphql`, under
`server/ee/libs/platform/platform-ai/`) — not inside the gateway. This is an extraction from the earlier
gateway-only implementation; see "AI Gateway content guardrails" below for the gateway's own adapter.

- **Engine**: `AiGuardrails` (`@Component @ConditionalOnEEVersion`) is registered UNCONDITIONALLY —
  decoupled from `bytechef.ai.gateway.enabled` (default false); it is inert when no workspace has anything
  enabled, so registering it unconditionally costs nothing and lets the agent surfaces work even with the
  gateway toggled off. PII/secret redaction itself is a detect → resolve → apply span pipeline
  (`SensitiveDataRedactor`) behind a bean-contributed `SensitiveDataDetector` SPI — as of the 2026-08-25
  extraction (ticket 732), the redactor, the SPI, the pattern catalogs, and PII tokenization all moved to
  the CE module `platform-ai-sensitive-data` (`-api`/`-service`), which this EE module now depends on rather
  than defining itself; see "Module and engine" and "Taxonomy and curated default" above for the full
  breakdown. The resolution order over overlapping spans is total, so detector registration order can
  never affect the redacted output.
- **Settings**: `AiGuardrailsWorkspaceSettings` is PROPERTY-BACKED, not a dedicated table — one
  `PropertyService` row per workspace (`Property.Scope.WORKSPACE`); the tenant default (null `workspaceId`)
  uses `Property.Scope.PLATFORM` with a null `scopeId`, the same convention as
  `AiProviderConnectionSourceImpl` / the `"mcp.server"` property. Five boolean toggles (`redactPii`,
  `redactSecrets`, `moderationEnabled`, `injectionDetectionEnabled`, `scanResponses`) plus a `blockedTerms`
  editor and `blockingMode` (`BLOCK` default | `REDACT_AND_CONTINUE` | `ALLOW`, INT-ordinal enum — governs only the
  blocking guardrails; redaction guardrails always redact-and-continue). GraphQL:
  `aiGuardrailsWorkspaceSettings(workspaceId)` returns `null` on a missing row (client synthesizes defaults),
  `isAuthenticated()`-gated; `updateAiGuardrailsWorkspaceSettings` is `ROLE_ADMIN`-gated on the controller
  itself (A2A precedent — no facade layer owns the check here). Settings UI: Workspace Settings → AI Agents →
  Guardrails tab (`/automation/settings/ai/agents/guardrails`, admin + EE gated). The former "AI" sidebar group
  is gone: Guardrails and System Prompt are tabs of one AI Agents page. The earlier `ai/guardrails` and
  `ai/system-prompt` routes were dropped rather than redirected — neither reached a release tag or `master`, so
  no bookmark could exist.
- **Agent surfaces**: `AiGuardrailsAdvisor` (Spring AI `CallAdvisor`/`StreamAdvisor`,
  `platform-ai-guardrails-service`) registers at `GuardrailAdvisorOrder.WORKSPACE_FLOOR`, one outside the
  per-node check advisor; the per-node input verdict unions the floor's published spans with its own
  detection (see "Advisor ordering rule"). The canvas AI
  Agent component (CE, `server/libs/modules/components/ai/agent`) reaches it through a CE SPI seam,
  `AiGuardrailsAdvisorProvider` (`platform-ai-api`, same idiom as `ToolExecutionRecorder` — optional bean,
  no-op on CE). AI Hub wires the advisor at the ChatClient-construction seam in `AiHubSpringAIAgent`,
  covering ASK/BUILD agents and task override clients.
- **Subagent delegate LLM calls (F3, ticket 732)**: closed. `SubAgentGuardrailedChatClient`
  (`ee.ai.hub.guardrails`, ai-hub-service) is a hand-written `ChatClient` decorator that wraps a delegate's
  own inner `ChatClient` so its one-shot `.call()`/`.stream()` attaches fresh, per-request advisors before
  delegating (`chatClient.mutate().defaultAdvisors(...)`'s per-request shape, deferred to request time since
  the delegate `ChatClient` bean is a singleton shared by every workspace). It no longer hardcodes which
  advisors: it dispatches a `List<SubAgentAdvisorContributor>`, of which `WorkspaceAdvisorContributor`
  (guardrails + workspace system prompt) is one — see "Subagent conversation memory and interactive
  questions" for the seam and the other contributor.
  It resolves the workspace id from the SAME forwarded `ToolContext` map every hand-rolled delegate
  `ToolCallback` (`SkillsAgentToolCallback`, `ResearchToolCallback`, etc.)
  already builds and passes to `.toolContext(Map)` — via `AgentToolInvocationContext
  .TOOL_CONTEXT_WORKSPACE_ID_KEY` — so no delegate class needed to change. One seam in
  `AiHubConfiguration` (`#wrapDelegate`) covers both delegate families the hub has: the
  catalog-backed intelligent delegates (via `registerIntelligentToolCallbacks`) and the AI-hub-owned
  generative one-shots research/data_analyst/image_generator/slide_builder (via
  `registerSubAgentToolCallbacks`). It does NOT reach the Copilot panels or the management MCP
  surface, which build the same delegates with an identity `chatClientDecorator`. A
  BLOCK-mode violation inside a delegate call throws `AiGuardrailViolationException` synchronously out of
  `.call()`; every delegate's pre-existing `catch (RuntimeException)` arm converts it to a tool-error string
  via `ToolErrors.runtimeFailure(...)` (class-name only, not `getMessage()`) rather than crashing the turn.
  **Still uncovered, inherent to the advisor approach**: a delegate's completion still returns to the
  *parent* as a tool message (skips the parent's own input scan) and streams to the client as tool-result
  events (skips the parent's response scan) — the delegate's OWN advisor now redacts/blocks its own
  request+response, but the parent agent never re-scans tool outputs. **Also still uncovered: the
  Copilot panels and the management MCP surface.** They build the same intelligent delegates from the
  shared `IntelligentToolCatalog` with an identity `chatClientDecorator`, so no
  `SubAgentGuardrailedChatClient` is applied and those delegate calls run without the workspace's
  guardrails or system prompt — the same seam asymmetry that leaves them without session memory. (The
  older `SubAgentToolCallback` construction path this bullet used to flag is gone, but the surfaces it
  named still are not covered.)
- **Model-based moderation (F2, ticket 732)**: covers every advisor-fronted surface, not just the gateway.
  `AiGuardrails#checkInputs` (the advisor's non-throwing entry point) takes an optional
  `AiGatewayModerationClassifier` (same SPI the gateway adapter's own classifier already implements — no
  cycle, the module already depended on `platform-ai-gateway-api`) and checks it when `moderation-enabled` /
  workspace `moderationEnabled` is active, fail-open on a classifier error like injection. The throwing
  `AiGuardrails#applyToInputs` (the gateway adapter's own path) deliberately never moderates — the gateway
  already moderates its own DTO pipeline with its own classifier + project overlay, so running it a second
  time here would double-moderate. `isActive` now also counts moderation as an activation reason. Because a
  moderation verdict has no locatable span (unlike a blocked term), a `REDACT_AND_CONTINUE` downgrade
  replaces the WHOLE message with `[REDACTED_MODERATED]` (category/metric `moderation_flagged`) rather than
  masking a substring — a deliberate departure from injection's existing downgrade (which still forwards
  only the pii/secret-redacted original text, unchanged by this work). The settings UI's old "currently
  applies to AI Gateway traffic only" caveat on the moderation toggle is gone; it now names the
  `bytechef.ai.gateway.guardrails.moderation-model` property required for the toggle to take effect. See
  the design spec's decisions log for why REDACT_AND_CONTINUE was implemented as whole-message masking
  rather than "moderation never downgrades."
- **PII tokenization (Phase 1, ticket 732 follow-up)**: PII redaction became reversible. A
  `PiiTokenSession` mints stable, per-value tokens (`[PII_<CATEGORY>_<ordinal>_<sessionId>]`) instead
  of collapsing every match of a category into the same `[REDACTED_*]` placeholder; the real value is
  restored into the model's response after response scanning runs (scan THEN restore — reversing the
  order is a silent no-op, since restoring first hands the scanner the real value back and it
  immediately re-redacts it). Secrets are unaffected and stay irreversibly redacted always. The
  canvas AI Agent and AI Hub (via `AiGuardrailsAdvisor`) and the AI Gateway's synchronous
  `chatCompletion` now tokenize; the gateway's streaming path and its embeddings endpoint do not yet.
  Sessions are request-scoped and hold sensitive data for their lifetime, so every call site closes
  one on every termination path, not just the happy path. Phase 1 does not persist a session across
  conversation turns — cross-turn token coherence is Phase 2. New metrics: `pii_tokenized`,
  `pii_restored`, `token_unresolved`. See "PII tokenization" above for the full breakdown.
- **Metric**: `bytechef_ai_guardrail{event, surface}` (`surface = gateway | ai_agent | ai_hub`), generalized
  from the old gateway-only counter; wired via `ObjectProvider<MeterRegistry>`. The per-surface split is
  clean for EVERY event on the advisor path, including request-direction ones: `AiGuardrails#checkInputs`
  (the advisor's non-throwing entry point) takes an `AiGuardrailMetrics` parameter and records
  `pii_redacted`/`secret_redacted`/`blocked_term`/`injection_flagged`/`moderation_flagged` through whatever instance
  `AiGuardrailsAdvisor` passes in — its own per-request, surface-tagged instance — not through the engine's
  own bean. Only `AiGuardrails#applyToInputs` (the gateway adapter's throwing entry point) still records
  through the engine's own internal `AiGuardrailMetrics` bean, gated on `bytechef.ai.gateway.enabled` and
  tagged `surface=gateway`. See `.agents/ai-guardrails.md` for the full breakdown.
- **Optional NER detector.** `platform-ai-guardrails-opennlp` (EE, off by default) plugs Apache OpenNLP
  into the `SensitiveDataDetector` SPI for unstructured PII (names, organizations) with no engine
  change. It ships no models — Apache distributes none — and is not stream-safe, so streamed
  completions still get regex redaction only. See the "OpenNLP detector" section above.
- Spec: `docs/superpowers/specs/2026-07-31-ai-guardrails-standalone-design.md`. Agent docs:
  `.agents/ai-guardrails.md` (engine, advisor, surfaces) and `.agents/ai-gateway-guardrails.md`
  (gateway adapter specifics, project overlay).

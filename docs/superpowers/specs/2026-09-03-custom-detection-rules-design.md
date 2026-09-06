# Custom Detection Rules and Context Keywords — Design

**Status:** split 2026-09-03. **Phase A (context keywords) implemented. Phase B (operator-supplied rules) UNBLOCKED 2026-09-03 — its prerequisite is built; the feature itself is not.**
**Plan:** `docs/superpowers/plans/2026-09-03-context-keywords.md`
**Queue item:** 6 — "custom rules"
**Deferred from:** `2026-08-31-guardrail-action-policy-design.md` §9 — "org-defined regex with context keywords is the successor to confidence scoring … what would let a bare digit run score high near `SSN:` and low near an order number, buying back the recall the confidence work traded away"
**Relates to:** `2026-08-25-sensitive-data-confidence-scoring-design.md` (the trade this buys back), `2026-09-03-detector-input-bounds-design.md` (queue item 7 — its deadline is a precondition here, see §5)

## 0. The split, and why Phase B cannot ship yet

This design describes two separable features and already ranks them: §1 says context keywords "should
be built first, and it is most of the value". Reading the code turned that ranking into a hard split.

**Phase A — context keywords on the built-in catalog — is implemented.** No operator-supplied regex,
so no ReDoS exposure, so no dependency on anything in queue item 7.

**Phase B — operator-defined rules — was blocked by this design's own D7, and no longer is.** §5a
states that item 7's detector deadline is "the only defence that bounds an *arbitrary* pattern rather
than a recognised-bad one". The deadline item 7 shipped was cooperative — checked between detectors —
so it bounded a pass but could not interrupt a regex already backtracking.

**`MatchDeadline` closed that on 2026-09-03.** It hands the regex engine a `CharSequence` that throws
`DetectionTimeoutException` once the deadline passes, using the same deadline instant the
between-detector checks already used. Measured: `(x+x+)+y` over 1,000 characters runs 3,056ms
unbounded and is interrupted at 200ms under a 200ms deadline, with no measurable cost on ordinary text.

**Two things Phase B must still do, both learned from building the bound:**

1. **Catch `StackOverflowError`, not only the timeout.** `(a|aa)+$` over 4,000 characters overflows the
   stack in about 8ms — faster than any useful deadline, and an `Error`, so a `catch (RuntimeException)`
   does not see it. `SensitiveDataRedactor` now catches it; a Phase B rule-validation path that runs a
   candidate pattern at save time must do the same, or a malformed rule kills the save request.
2. **Do not rely on the textbook ReDoS patterns when testing.** `(a+)+$`, `(a*)*b`,
   `([a-zA-Z]+)*$` and `(a|aa)+$` all complete in under a millisecond on this JVM's engine at any
   input length worth testing. §5c's adversarial timing check will pass everything if it is seeded from
   a list of famous patterns. The one that actually explodes here was found by probing.

§5b–5d's save-time defences remain worth building, and remain insufficient alone — the runtime bound is
what makes an arbitrary pattern safe.

### What reading the code changed about Phase A's value

**Eleven pattern types are currently detected by nothing at the default threshold, not the four this
design named.** Every pattern scoring `0.2` sits below `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE`
(`0.4`), and that is eleven of the catalog's thirty-five curated entries:

`MEDICAL_LICENSE`, `US_BANK_NUMBER`, `US_DRIVER_LICENSE`, `US_PASSPORT`, `IT_DRIVER_LICENSE`,
`IT_PASSPORT`, `IT_IDENTITY_CARD`, `PL_PESEL`, `AU_TFN`, `IN_VOTER`, `IN_PASSPORT`.

A real US passport number in a prompt is forwarded to the model in the clear today, because
`[A-Z]\d{8}` matches an order code just as well. That is the coverage the confidence work traded
away — correctly, since the alternative was redacting every order code — and it is exactly what
context keywords buy back: `Passport: A12345678` promotes, `Order A12345678` does not.

### Deferred from Phase A

**`context_keyword_promoted` is not recorded.** `SensitiveDataDetector#detect(String)` takes no
metrics parameter, so the detector cannot report, and reporting would need either an SPI overload or a
constructor change to a class built by a static factory. For a built-in, reviewed keyword list the
tests are the proof that promotion happens; the metric earns its cost once *operators* write keywords,
which is Phase B. Stated because §7 lists it, and it is not there.

## 1. Two features, and the second one is the reason to build the first

They are usually named together as "custom rules", but they are separable and the ranking is not
obvious:

**Context keywords** — a match's confidence is raised or lowered by what sits near it.
**Custom rules** — an operator defines their own pattern.

The second is the one customers ask for. The first is the one that fixes a defect this codebase
already shipped, and it applies to the **built-in** catalog whether or not anyone ever writes a custom
rule. It should be built first, and it is most of the value.

### The defect it fixes, stated concretely

`PiiPatternCatalog` scores every pattern by how specific its own shape is, and
`SensitiveDataRedactor` drops candidates below a confidence floor. That was the right call and it was
paid for:

> `AU_TFN` scores Low (`0.2`), below the platform default, so the match is dropped before resolution
> and `SSN 123456789` is redacted by nothing, where before this feature it was redacted (mislabelled
> `AU_TFN`, but redacted).
> — `2026-08-25-guardrails-consolidation-design.md` §5a

The consolidation spec calls that "an intended coverage reduction the confidence rubric produces". It
is intended, and it is still a real loss: **a genuine Tax File Number in a prompt is now forwarded to
the model in the clear**, because its shape is indistinguishable from an order number's.

A score fixed per pattern cannot resolve that, because the information needed is not in the match — it
is next to it. `SSN 123456789` and `Order 123456789` differ only in context.

### What context keywords do

A pattern gains an optional set of keywords and a proximity window. When a keyword occurs within the
window of a match, the match's score is raised to a configured ceiling; otherwise it keeps its base
score.

Applied to the eight bare-digit national-identifier types the confidence work suppressed, this buys
back exactly the recall it traded, without reintroducing the false positives — `Order 123456789`
still scores `0.2` and is still dropped.

**Raise only, never lower — at least in this design.** A negative keyword ("this is an order number,
suppress") is the symmetric idea and it is a footgun: an attacker who knows the negative keywords
writes `Order number: <real SSN>` and disables the guardrail with a prefix. Raising cannot be abused
that way, because the worst an attacker achieves is being redacted.

## 2. Custom rules

A workspace-defined rule is:

| Field | Notes |
|---|---|
| `name` | The identifier that appears in metrics, tokens (`[PII_<NAME>_1_k3n9]`) and violation records. Must satisfy the token grammar — `[A-Z][A-Z0-9_]*` — or it mints tokens that `PiiToken`'s own pattern cannot parse back. |
| `regex` | Operator-supplied. §5 is entirely about this field. |
| `kind` | `PII` or `SECRET`. Determines reversibility: secrets are never tokenized, only redacted. |
| `score` | Base confidence, on the same `0.2`/`0.6`/`0.9` rubric as the catalog, so one threshold governs both. |
| `contextKeywords`, `contextWindow`, `contextScore` | §1. |
| `enabled` | Rules are created disabled. See §6. |

Custom rules are **additive to the catalog, never overrides**. An operator cannot redefine `US_SSN`;
they can add `ACME_ACCOUNT_ID`. Allowing an override would let a workspace silently weaken a built-in
protection through a settings field, and the resulting behaviour would be un-diagnosable from the
catalog alone.

Overlap with a built-in match resolves through the existing span-overlap rule in
`SensitiveDataRedactor`. No new precedence concept.

## 3. Where they live

EE, per workspace, alongside `AiGuardrailsWorkspaceSettings` — rules are policy, and policy is the EE
half of the CE/EE split the consolidation design settled (D3: "EE keeps policy, scope, gateway,
metrics, OpenNLP").

The **mechanism** — a detector that evaluates a supplied list of patterns with context scoring — goes
in CE `platform-ai-sensitive-data`, next to `RegexPiiDetector`. That follows the same rule the
consolidation applied to the catalog: CE owns detection, EE owns which detections a workspace has
asked for. It also means the node guardrails and the knowledge base can use context keywords later
without a second implementation.

Not a JSON blob on the settings record. A rule has seven fields, a validation lifecycle and its own
enable flag; storing it as JSON in a property value makes it unqueryable and unvalidatable at the
storage layer. Its own table, workspace-scoped.

## 4. Context keywords apply to built-in patterns too

This is the half that ships value on day one. `PiiPattern` gains the same three optional fields, and
the catalog populates them for the types the confidence work suppressed — `AU_TFN`, `PL_PESEL`,
`MEDICAL_LICENSE`, `US_BANK_NUMBER` and the Medium-scored national identifiers.

The keyword lists are part of the catalog and reviewed with it, not operator-editable. An operator
who wants different keywords for a built-in type writes a custom rule — which keeps the catalog a
single reviewed artefact, the property the consolidation work spent a whole sub-project establishing.

## 5. The operator-supplied regex, which is the real design problem

`PiiPatternCatalog` carries this, and it is load-bearing:

```java
// REDOS is suppressed because every pattern here uses only fixed {N,M}/{N} or possessive
// quantifiers; no …
@SuppressFBWarnings("REDOS")
```

That rationale is a property of a **hand-reviewed** catalog. A workspace-supplied regex has no such
guarantee, and this feature runs it on the request thread, before the model call, over attacker-
influenced text. `(a+)+$` against a long line of `a`s is a hang, and the input is a prompt.

Four defences, and none of them is sufficient alone:

**5a. Queue item 7 is a hard prerequisite.** The detector deadline from
`2026-09-03-detector-input-bounds-design.md` is the only defence that bounds an *arbitrary* pattern
rather than a recognised-bad one. A custom-rule feature shipped without it puts an unbounded
operator-controlled loop on the request path. **This design should not be implemented before that
one.**

**5b. Reject constructs that make catastrophic backtracking possible**, at save time — nested
quantifiers, alternation inside a quantified group with overlapping branches, unbounded backreferences.
This is a static check, it has false positives, and it should refuse rather than warn: an operator who
hits a false positive can rewrite the pattern, whereas one who ignores a warning hangs production.

**5c. Time-box the pattern at save time against an adversarial corpus** — long runs of the pattern's
own character classes, not the operator's happy-path sample. A pattern that takes more than a few
milliseconds on a pathological input is rejected with the input that broke it. This catches what 5b's
syntax check misses, which is most of what it misses.

**5d. Cap the pattern length and the rule count per workspace.** Not a real defence on its own,
but it bounds the blast radius of everything the first three let through.

## 6. Rules are created disabled, and observe mode is the on-ramp

A new rule starts `enabled = false`. Enabling it is a second, deliberate act.

More importantly, this is where queue item 5 pays off: the intended sequence is **write the rule,
enable it under `BlockingMode.ALLOW`, watch `guardrail_allowed` and the violation records for a week,
then enforce.** That is the "test against real traffic dominates testing against samples" argument the
action-policy design made when it declined to build a rule tester — and it is only true if observe
mode exists, which it now does.

A rule tester over pasted sample text was considered again here and rejected again, for the same
reason plus one more: the samples an operator pastes are the cases they already thought of, and the
false positives that matter are the ones they did not.

## 7. Observability

Custom rules use the existing events, tagged by rule name where the metric already carries a name
dimension. Two additions:

- `custom_rule_rejected` — a rule failed validation at save time, tagged with which defence caught it
  (`syntax`, `timing`, `length`). Tells an operator's admin whether the rejection rules are too tight,
  which is otherwise invisible.
- `context_keyword_promoted` — a match's score was raised by a nearby keyword. This is the metric that
  proves §1's recall buy-back is actually happening, rather than the keywords being configured and
  never matching.

## 8. Testing

- **A bare digit run scores low alone and high next to its keyword**, and the redaction outcome
  differs accordingly at the default threshold. This is §1's entire claim in one test, and it must
  assert the *outcome*, not the score, or it pins the arithmetic instead of the behaviour.
- **The keyword window is respected** — the same keyword outside the window does not promote. Without
  this, a keyword anywhere in a long document promotes everything.
- **`Order 123456789` is still not redacted** with `AU_TFN`'s keywords configured. The false positive
  the confidence work fixed must stay fixed; this is the regression direction.
- **A catastrophic-backtracking pattern is rejected at save time**, with the timing defence exercised
  by a pattern that passes the syntax check — otherwise 5c is untested and 5b is doing all the work.
- **A rule with a name that violates the token grammar is rejected**, since the failure mode is
  otherwise a token `PiiToken` cannot parse and therefore a permanently unresolved value.
- **A custom rule cannot shadow a built-in type name.**
- **A newly created rule is disabled and detects nothing** until enabled.
- **A custom rule of kind `SECRET` is redacted, never tokenized.**

## 9. Non-goals

- **Negative/suppressing keywords.** §1 — an attacker-writable off switch.
- **Overriding or disabling built-in patterns.** §2. Type selection by policy is a separate, already-
  named follow-up (consolidation D11).
- **A rule tester over pasted samples.** §6.
- **Sharing rules across workspaces, or a rule library.** Worth doing; needs an org-level scope
  decision that the Component Policies slice is already carrying for a neighbouring question.
- **Non-regex rule types** — dictionary lists, checksums, ML. The catalog's `validator` hook already
  covers checksums for built-ins; exposing it to operators is a different feature.
- **A settings-page UI.** GraphQL only, matching every neighbouring guardrail feature.

## 10. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Context keywords ship first and apply to built-in patterns | They fix a shipped coverage loss and need no operator input to be worth something |
| D2 | Keywords raise confidence only, never lower it | A negative keyword is an attacker-writable off switch: `Order number: <real SSN>` |
| D3 | Built-in keyword lists are catalog content, not operator-editable | Keeps the catalog one reviewed artefact; operators extend via custom rules |
| D4 | Custom rules are additive; built-ins cannot be overridden | An override silently weakens a built-in protection through a settings field |
| D5 | Mechanism in CE, rule storage in EE | Matches the consolidation split: CE owns detection, EE owns policy; lets the KB reuse it later |
| D6 | Own table, not a JSON blob on the settings record | Seven fields, a validation lifecycle and an enable flag |
| D7 | Queue item 7's detector deadline is a **hard prerequisite** — **satisfied 2026-09-03** | It is the only defence that bounds an arbitrary pattern rather than a recognised-bad one. `MatchDeadline` provides it; see §0 |
| D7a | Phase B must also catch `StackOverflowError` | Deep recursion overflows in ~8ms, faster than any deadline, and is an `Error` the fail-open catch does not see |
| D8 | Save-time validation refuses rather than warns | An ignored warning hangs production; a false positive costs a rewrite |
| D9 | Adversarial timing check in addition to the syntax check | The syntax check misses most of what matters |
| D10 | Rules are created disabled | Enabling is a deliberate second act |
| D11 | No rule tester; observe mode is the on-ramp | Real traffic dominates samples, and the samples an operator pastes are the cases they already thought of |
| D12 | Rule names must satisfy the token grammar | Otherwise the rule mints tokens `PiiToken` cannot parse back, producing permanently unresolved values |

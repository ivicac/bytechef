# Detector Input Bounds — Design

**Status:** implemented 2026-09-03, WITHOUT §4a — see "What shipped, and what did not"
**Plan:** `docs/superpowers/plans/2026-09-03-detector-input-bounds.md`
**Queue item:** 7 — "OpenNLP input cap"
**Relates to:** `2026-08-24-guardrails-opennlp-detector-design.md` (the detector this was found in), `2026-08-25-guardrails-consolidation-design.md` §5b (which restates the defect and says it "should be closed before a third detector is added")

## 0. What shipped, and what did not

**§4b (the deadline) and §4c (the unwindowable-input skip) shipped. §4a (windowing) did not, and the
reason is worth more than the feature would have been.**

Windowing was implemented, tested, and then withdrawn. It works only if every pattern has a bounded
maximum match — otherwise a pattern reports a span whose length depends on how much input it was
handed, and the same document redacts differently at different window sizes. That invariant does not
hold, is not cheaply checkable, and trying to force it caused a regression:

- Four successive versions of the "no pattern matches longer than the overlap" test were each
  partially vacuous. A repeating-alphabet haystack bounded every match to one cycle. Hand-written
  seeds only stressed prefixes I had guessed — it caught `OPENAI_KEY` solely because `sk-` happened to
  be on the list, and missed five unbounded patterns beside it. Seeds derived by scanning for a
  literal prefix stopped at the first character class, so `xox[baprs]-…` and `sk_(?:live|test)_…`
  escaped. A structural check for unbounded quantifiers was finally complete — and too strict, because
  `[A-Za-z0-9._%+-]+@` is a legitimate unbounded quantifier that the `@` bounds in practice.
- Acting on the structural check, I bounded `EMAIL_ADDRESS`'s TLD group at `{2,24}`. That **stopped
  detecting** `bob@acme.io` followed by a letter run: with the group bounded, no prefix of the run can
  satisfy the trailing `\b`, so the pattern matched nothing at all. Over-redaction became
  non-detection, in the most important pattern in the catalog. Reverted.

The withdrawal costs less than it sounds. Windowing bounded per-call cost; the deadline bounds
elapsed time, which is the harm §1 actually describes — a stalled request thread. What is genuinely
lost is the ability to keep detection cheap on a very large document without a deadline firing, and
that is now visible through `detector_timed_out` rather than silently absent.

**Windowing should be revisited only alongside a catalog-wide bounded-match invariant**, which needs
its own work: a max-match analysis that understands that `+` before a literal separator is bounded
while `+` at the end of a pattern is not.

## 1. The defect

Sensitive-data detection runs **synchronously, on the calling thread, before the model call**, over
the full text of every prompt message and every completion, with **no bound on the input size and no
deadline**.

`SensitiveDataRedactor#collectSpans` is the whole of the protection:

```java
try {
    List<SensitiveSpan> spans = detector.detect(text);
    …
} catch (RuntimeException exception) {
    log.warn("Sensitive-data detector '{}' failed; continuing without its spans", detector.name(), exception);
    …
}
```

That catch is the engine's fail-open policy, and **it cannot help here, which is the part worth
stating twice**: a slow detector never throws. It returns, eventually. The safety valve is shaped
for the wrong failure — it catches a detector that breaks, not one that takes a minute.

### Why it is worse now than when it was written

Two changes since:

- **The catalog grew roughly sevenfold.** EE's five-pattern detector was replaced by the CE catalog —
  36 PII patterns plus 11 secret patterns — and each runs over the whole text. The consolidation
  design accepted that cost explicitly (§5b) and named this defect as the reason it becomes urgent.
- **OpenNLP arrived.** A regex pass is fast per pattern; an NER pass tokenizes the document and runs a
  maximum-entropy model over every token, and `NameFinderME` is constructed **per call** because it is
  not thread-safe. That is a different order of cost, and it is the detector the queue item is named
  after.

### What the input actually is

Not "a user's question". The guarded text is every message in the prompt, which routinely includes a
retrieved document, a pasted file, a tool result, or an entire conversation history. There is no
upstream limit that keeps it small — the model provider's context window is the only bound, and that
is megabytes.

### The blast radius of one slow call

The advisor runs on the request thread. A multi-megabyte prompt therefore stalls that thread for the
whole detection pass, before any model call starts. On the AI Hub and Copilot that is a hung request;
on the canvas AI Agent it is a task worker held out of the pool. Nothing times out, nothing sheds
load, and nothing reports it — there is no metric for "detection took a long time", so the first
signal is a latency complaint.

## 2. What is already bounded, and what that proves

**The streaming path is already bounded, though not in the way this design first claimed.**
`StreamingResponseRedactor` scans a bounded carry buffer (`DEFAULT_WINDOW = 512`) and uses the spans
it finds to pull its cut back out of a match. It never offsets a span into document coordinates,
because it has no document — the buffer *is* its coordinate space.

So the useful precedent is narrower than "windowed detection already works here": what exists is a
bounded scan, and **offsetting spans from window coordinates back into document coordinates is new
code**. An implementer should not go looking for a helper to reuse. (This paragraph replaces an
earlier revision of it that claimed the offsetting existed; it did not.)

It also already has the concept this design needs for the expensive detector:
`SensitiveDataDetector#streamSafe()` returns `false` for a detector that needs wider context than a
window can give, and the streaming path **skips** it. OpenNLP returns `false`.

So the situation is precisely inverted between the two paths:

| | Non-streaming (`adviseCall`, tool boundary, MCP) | Streaming (`adviseStream`) |
|---|---|---|
| Input size | unbounded | bounded to a 512-char window |
| OpenNLP | runs, over the whole text | skipped entirely |

The expensive detector runs only on the path with no bound. That is the defect in one line.

## 3. What must not be the answer

**A truncation cap is a security regression, not a fix.** "Detect over the first N characters" makes
the guardrail stop protecting exactly when the payload is largest, and does it silently. An attacker
who wants PII to pass unredacted appends padding. Any design that truncates has to explain why that
is acceptable, and I do not think it can be.

**Failing closed on size is an outage with a threshold.** Refusing every prompt above N turns a
performance defect into a availability one, on a limit the operator did not know existed until it
fired.

Both are listed because both are the obvious first answers.

## 4. Design

Three parts. The first is the actual fix; the second and third are what make it safe to ship and
possible to tune.

### 4a. Window the non-streaming path, with overlap — coverage preserved

Detection over a long input becomes detection over consecutive windows, with an overlap equal to the
longest match the catalog can produce, and spans offset back into document coordinates. Unlike the
streaming carry buffer (§2), a non-stream windows the *whole* document rather than a lookahead, so
**coverage is preserved exactly** — nothing is skipped, and a match straddling a boundary is caught by
the overlap.

This bounds the cost of any single detector invocation. It does **not** bound total cost, which stays
linear in document length. That is correct and deliberate: linear is the honest cost of scanning a
document, and the pathological cases here are not linear-with-a-big-input, they are
superlinear-per-call.

**The overlap cannot be *derived* from the catalog, and an earlier revision of this section said it
must be.** A compiled `Pattern` does not expose a maximum match length, and computing one would mean
parsing the regex source. So the overlap is a configured value with a generous default, and the claim
it rests on is pinned empirically instead: a test drives every catalog pattern against adversarial
input built from its own character classes and asserts no match exceeds the default. That is a weaker
guarantee than derivation and a stronger one than a comment — and it is the same shape as the
consolidation design's warning that the analogous streaming precondition ("the window must exceed the
longest possible token") went stale once when the token format changed.

### 4b. A cooperative deadline over the whole pass

Windowing bounds one detector's work on one window. It does not bound the aggregate: a large enough
document is still many windows times many patterns.

So the pass carries a deadline, checked **between windows and between detectors**. On expiry the
remaining work is abandoned, the spans found so far are applied, and `detector_timed_out` is recorded
with the name of the detector that was running.

**This WAS cooperative, and that limitation is now closed.** Checking between units cannot interrupt a
single pathological unit — a regex engine in catastrophic backtracking does not come back to be asked.
This section said bounding that would need a `CharSequence` that throws once the clock runs out, and
judged it more machinery than the defect justified while every pattern was hand-reviewed.

**Built 2026-09-03 as `MatchDeadline`**, once operator-supplied patterns became the next thing to ship.
It hands the engine a `CharSequence` throwing `DetectionTimeoutException`, using the same deadline
instant the between-detector checks already use — one budget, enforced both between detectors and
inside a match. Measured rather than assumed: `(x+x+)+y` over 1,000 characters runs 3,056ms and 664
million `charAt` calls unbounded, and is interrupted at 200ms under a 200ms deadline, with no
measurable cost on ordinary text.

Two things that probing turned up, both recorded because neither was expected. The textbook ReDoS
patterns — `(a+)+$`, `(a*)*b`, `([a-zA-Z]+)*$`, `(a|aa)+$` — do **not** explode on this JVM's engine
at any input length worth testing, so the pattern above had to be found by measurement. And
`(a|aa)+$` over 4,000 characters raises `StackOverflowError` in about 8ms: faster than any useful
deadline, and an `Error`, so the fail-open `catch (RuntimeException)` never saw it. That is caught
separately now (D7c).

**It fails open**, and that is the uncomfortable half. A timed-out pass returns partial coverage. The
alternative, failing closed, refuses a legitimate call because detection was slow, which is worse in
the common case and corresponds to no threat — a slow detector is not evidence of an attack. But
partial coverage is exactly the silent degradation §3 rejects for truncation, so it may only ship with
the metric, and the metric is not optional.

**Ordering carries the guarantee that matters.** Windowable detectors run first, to completion, and
the unwindowable ones run afterwards with whatever budget remains. So the cheap regex pass — where
every identifying pattern lives — cannot be starved by the expensive detector, without needing a
detector to classify its own cost.

### 4c. The unwindowable detector is skipped above a size, not truncated

`SensitiveDataDetector#streamSafe()` already means "can this detector be applied to a fragment and
give the same answer?" A detector that returns `false` — OpenNLP does — **cannot be windowed**, by its
own contract. Feeding it a window that starts mid-sentence is precisely what that flag exists to
prevent.

That leaves it as the one detector §4a cannot bound and §4b can only bound cooperatively. The honest
control is a size above which it does not run at all, recorded as `detector_skipped_oversize`.

**A skip, deliberately, rather than a truncation.** Both lose coverage on a large document; they
differ in what they claim. Truncation reports a clean scan of a document it only partly read. A skip
reports that the detector did not run, on a counter with the detector's name on it. §3 rejects
truncation for making the guardrail stop protecting silently — the skip is the same coverage loss
made loud.

It is also consistent with how this detector is already treated: the streaming path skips it outright
today, and it ships no models, so it is inert until an operator configures one.

## 5. Configuration

Both bounds are `ApplicationProperties` fields, which this codebase requires (strict binding — a
property that is not a field fails startup):

- `bytechef.ai.guardrails.detection.window-size` — default 65536 (64 KiB). Large enough that ordinary
  prompts take a single window and the behaviour is byte-identical to today.
- `bytechef.ai.guardrails.detection.window-overlap` — default 1024. §4a's overlap.
- `bytechef.ai.guardrails.detection.timeout` — a `Duration`, default 2s. Chosen so a normal prompt
  never approaches it; this is a stall breaker, not a tuning knob.
- `bytechef.ai.guardrails.detection.max-unwindowable-input` — default 262144 (256 KiB). §4c's skip
  threshold. Distinct from the window size because it answers a different question: not "how much do
  I hand a detector at once", but "how much is too much for a detector I cannot chunk at all".

All four are fields on `ApplicationProperties.Ai.Guardrails.Detection`, beside the existing
`open-nlp` block. This is not optional bookkeeping — binding is strict, so a key with no field fails
application startup, which would turn "operator tunes the timeout" into an outage.

Not per-workspace. A workspace-level timeout would let a tenant set it to zero and silently disable
detection for itself, which is a policy hole dressed as a performance setting.

## 6. Observability

- `detector_timed_out` — the pass abandoned remaining work for this content; **tagged with the name of
  the detector that was running**, since knowing which one is the entire diagnostic value.
- `detector_skipped_oversize` — an unwindowable detector was not run because the input exceeded §4c's
  threshold; also tagged with the detector name.

Two new events, and they stay separate from `detector_failed`, which keeps its meaning — a detector
that threw, not one that ran long or was skipped. Conflating them would lose exactly the distinction
§1 says the current fail-open design gets wrong.

Both arrive on the `SensitiveDataMetrics` seam. That seam's events past the first are `default`
no-ops, so a new one is silently unimplemented in production unless the EE bean overrides it — which
is why `AiGuardrailMetricsTest#testEverySensitiveDataMetricsEventIsImplementedRatherThanLeftAsANoOpDefault`
(queue item 10) will fail until both are wired. That is the interlock working as intended, not an
obstacle.

## 7. Testing

- **A document longer than the window produces the same spans as the same document scanned whole**,
  including a match placed deliberately across a window boundary. This is the coverage-preservation
  claim and it is the one that matters; without the boundary case it pins nothing.
- **Spans from a later window carry document coordinates, not window coordinates.** An off-by-window
  offset bug replaces the wrong text and is invisible in a single-window test.
- **A detector that sleeps past the deadline is abandoned, the spans found before it are still
  applied, and `detector_timed_out` is recorded with its name.** All three, since a timeout that
  discards partial results is a different (and worse) behaviour than the one designed.
- **A windowable detector runs before an unwindowable one and is unaffected by the latter's
  timeout** — pinned with a sleeping unwindowable detector and a regex detector that must still
  produce its spans. Otherwise §4b's ordering guarantee is a claim no test makes.
- **An unwindowable detector is skipped above the threshold**, `detector_skipped_oversize` is
  recorded with its name, and the windowable detectors still return their spans for that same
  oversized input.
- **An ordinary short prompt takes exactly one window and produces byte-identical output to today.**
  The no-regression pin.
- The deadline test must not use a fixed sleep as its assertion of timing; it asserts the *outcome*
  (abandoned, metric recorded), with the sleeping detector as the fixture.

## 8. Non-goals

- **Asynchronous or off-thread detection.** It would move the stall rather than remove it, and the
  advisor contract is synchronous.
- **A per-workspace timeout.** §5.
- **Bounding total cost for very large documents.** Linear in length is the correct cost of scanning
  a document; this design bounds per-call and per-request work, not the honest linear part.
- **Streaming-path changes.** It is already bounded and already skips OpenNLP.
- **Rejecting oversized prompts.** §3.

## 9. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Window with overlap; never truncate | Truncation makes the guardrail stop protecting exactly when the payload is largest, silently |
| D2 | Overlap derived from the catalog's longest match, not a literal | The analogous streaming precondition went stale once when the token format changed |
| D3 | A deadline, not a size cap, is the stall breaker | Elapsed time is the thing that hurts; a size cap only proxies it, and proxies it badly for a pathological detector |
| D4 | The deadline fails open | A slow detector is not evidence of an attack; refusing the call is worse in the common case |
| D5 | `detector_timed_out` is mandatory, tagged by detector name | D4 ships partial coverage; without the metric that is the silent degradation D1 rejects |
| D6 | Windowable detectors run first, to completion; unwindowable ones get the remaining budget | Gives the "regex coverage is never starved" guarantee by ordering, with no new SPI method |
| D7 | **No `cheap()` flag.** `streamSafe()` already partitions the detectors exactly | The two would be 100% correlated across every detector that exists; a second flag saying the same thing is a way for them to disagree later |
| D7a | An unwindowable detector is **skipped** above a size, never handed a truncated prefix | Both lose coverage; only truncation reports a clean scan of a document it partly read |
| D7b | ~~The deadline is cooperative, checked between units~~ **Superseded 2026-09-03** | The throwing `CharSequence` this row said would be needed was built (`MatchDeadline`), so the deadline is enforced inside a match as well as between detectors. Measurement drove it: the textbook ReDoS patterns do not explode on this JVM, and the one that does was found by probing rather than by reputation |
| D7c | A `StackOverflowError` from a detector is caught | Found while probing D7b: `(a\|aa)+$` over 4,000 characters overflows the stack in 8ms — faster than any useful deadline, and an `Error`, so the fail-open `catch (RuntimeException)` never saw it. Uncaught it takes the guarded call down, which is worse than losing one detector's spans |
| D8 | Bounds are global properties, not per-workspace | A per-workspace timeout is a policy hole dressed as a performance setting |

## 10. Open question, ruled

**Should `detector_timed_out` also downgrade the call?** A workspace running `BlockingMode.BLOCK`
arguably wants a timed-out detection to be treated as a failed check rather than a passed one — the
fail-closed position, applied narrowly to workspaces that have already chosen strictness. That is
defensible and it is not what D4 says.

**Ruled: no. D4 stands as written, for now.** Shipping the narrowing unasked would turn a timed-out
detection into a user-visible refusal, on a threshold the operator never configured and cannot see
until it fires — converting a latency defect into an outage for exactly the workspaces that were most
careful. The metric makes the exposure visible, and a workspace that decides it wants fail-closed can
have it as its own change, with the setting named and documented.

Cost if this ruling is wrong: a `BLOCK` workspace runs unprotected on the tail of a large document
during a detector stall, and learns about it from `detector_timed_out` rather than from a refusal.
That is a real gap and it is why the metric is mandatory rather than nice-to-have.

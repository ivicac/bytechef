# Guardrail Violation Records — Design

**Status:** **implemented 2026-09-03**, minus two things §0 records: the keyed hash (D3) and per-workspace
enablement (D7 ships as a global default-false flag instead).
**Plan:** `docs/superpowers/plans/2026-09-03-guardrail-violation-records.md`
**Queue item:** 8 — "violation records"
**Deferred from:** `2026-08-31-guardrail-action-policy-design.md` §9, which names this "per-detection records naming the matched field and calling user" and says it is "worth most once custom rules exist, since drill-down is how you debug your own rules"
**Depends on, for its value:** queue item 6 (custom detection rules). It is buildable before, and worth less.

## 0. What reading the code changed

Three findings, each of which moves a decision rather than merely adding detail.

**The spans never escape the engine.** `AiGuardrails#redactPiiAndSecrets` computes
`RedactionResult#accepted()` — the winning spans, each already carrying exactly the fields §2 wants
(`category`, `start`, `end`, `confidence`) — and uses them only to decide which counters to
increment. `GuardrailCheckResult` carries text and a category, nothing else. So the record model does
not need inventing: it maps onto `SensitiveSpan` one-to-one. What it needs is a way out.

**The action is not known when the spans are.** `AiGuardrailsAdvisor` resolves `BlockingMode` *after*
`checkInputs` returns. D11 requires the record to carry the action taken, so the record cannot be
assembled inside the engine. Together with the previous point this settles the seam: **the engine
hands the accepted spans out on `GuardrailCheckResult`, and the advisor — which alone knows the
workspace, the surface and the action — assembles and submits the record.** The alternative, giving
the engine a recorder collaborator, would have had it record an action it cannot see.

**There is no guardrails table, and no JDBC wiring.** `AiGuardrailsWorkspaceSettings` is a record over
the shared `property` table; the guardrails modules have never owned a schema. So this is the first
one, and it needs a changelog directory, an `includeAll` line in `master.xml` with a context filter and
an ordering comment, and a `*JdbcRepositoryConfiguration` — infrastructure the design did not account
for. The entity goes in `-api/domain` and the repository in `-service/repository`, following
`platform-ai-eval` exactly rather than inventing a module.

### Deferred from this implementation

**D3's keyed hash ships later, not now.** It is off by default in the design, it is the one field with
a real attack (an attacker holding the key and a candidate list can confirm values), and it needs key
management and rotation decided. Everything else in §2 is independent of it. Deferring it is
consistent with the design rather than a departure from it — but it means the recurrence use case
("this same value appeared in 40 requests") does not work yet, and that should not be claimed.

## 1. What is missing

Today a guardrail detection produces exactly one thing: an increment of
`bytechef_ai_guardrail{event, surface}`. Two low-cardinality tags, deliberately — the counter's own
javadoc says no workspace or project dimension, "so the meter stays cheap on unbounded multi-tenant
deployments."

That is the right shape for a counter and it answers only one question: *how much is happening?* It
cannot answer any of:

- Which workspace is generating this?
- Which pattern fired — `US_SSN`, or `AU_TFN` on the same digits?
- Was that a real detection or a false positive on an order number?
- Which user's request was blocked, so support can explain it to them?

The third is the one that matters most, and it is the reason this lands after custom rules: when an
operator writes their own regex, "it fired 400 times yesterday" is not debuggable. They need to see
*what* it fired on.

## 2. The tension this design exists to resolve

**A record useful for debugging wants the matched text. Storing the matched text builds a plaintext
PII database — the exact thing the feature exists to prevent.**

This is not a corner case to be handled later; it is the whole design problem. Every naive version of
"violation records" is a table of the sensitive values the product just spent a release learning to
redact, with a longer retention than the request that produced them and a GraphQL read surface in
front.

So the central decision is stated first, before anything else:

> **A violation record never stores the matched value, in any form that can be read back.**

Everything below follows from that, including the parts that make the feature less convenient than it
would otherwise be.

### What a record can carry instead

| Field | Why it is safe, and what it buys |
|---|---|
| Pattern/rule name (`US_SSN`, or a custom rule id) | Names *which* rule fired. This is the field that makes custom rules debuggable. |
| Offset and length in the scanned text | Locates the match without reproducing it. Enough to say "characters 412–421 of the third message". |
| A **keyed** hash of the matched value (HMAC, per-tenant key) | Lets an operator see that the *same* value recurred across 40 requests without ever seeing it. Keyed, not plain: a plain SHA-256 of an SSN or an email address is trivially reversible by enumeration, which would make an "anonymised" column a disclosure. |
| Confidence score | The confidence work traded recall for precision; this is how an operator sees which side of that trade a given detection landed on. |
| Surface, workspace, environment, principal, correlation id | Attribution. |
| Action taken (`BLOCK` / `REDACT_AND_CONTINUE` / `ALLOW`) | Pairs with observe mode: a record under `ALLOW` is a *what would have happened*, and that must be distinguishable at read time from an enforcement. |

**The keyed hash is optional and off by default.** It is the one field that has a plausible attack —
an attacker with the key and a candidate list can confirm values — so it exists behind an explicit
setting, with the key held in `server/libs/core/encryption` and rotatable. An operator who does not
turn it on gets everything else.

### The escape hatch that must not be built

"Add a debug mode that stores the raw value for 24 hours." It will be asked for, it is the obviously
useful thing, and it converts the store into the plaintext PII database on a per-workspace toggle
that someone will leave on. If a version of this ships, it belongs in its own spec with its own
threat model, not as a flag in this one.

## 3. Where the records live

**Not in `platform-audit`, and this is the mirror image of the action-policy spec's D4.** That spec
refused to build a second policy home when one existed. Here the existing store is a genuinely poor
fit and the honest answer is a new table:

| | `persistent_audit_event` | What a violation record needs |
|---|---|---|
| Keyed on | `principal` (VARCHAR 256), `event_date`, `event_type` | workspace + environment + surface |
| Workspace scope | **none — no `workspace_id` column** | required; this is a per-workspace drill-down |
| Detail | a `persistent_audit_event_data` key/value side table, `value` VARCHAR(256) | typed columns; a key/value side table per detection is two writes per match |
| Volume | user actions — a handful per session | potentially every message of every request |

The volume row is decisive. Audit events are human actions; violation records are machine detections
on a hot path. Writing them through a schema designed for the former would make the audit table's own
retention and query characteristics a function of guardrail traffic.

`AuditEventRetentionJob` is nonetheless the pattern to copy for retention — a scheduled sweep with a
configured horizon — rather than something to invent.

## 4. Volume control, decided rather than discovered

A row per detection per message per request is write amplification on the request path, which is the
same path §1's counter was deliberately kept cheap for. Three controls, all of which must exist
before this ships:

- **Off by default, per workspace.** A workspace that has not asked for drill-down pays nothing. This
  is the primary control and the others are for workspaces that opt in.
- **Written asynchronously, and never on the request thread.** A failed or slow record write must not
  affect the guarded call. A dropped record is acceptable; a stalled model call is not. This is the
  same fail-open reasoning the detector-bounds design (queue item 7) applies to detection itself, and
  for the same reason.
- **A per-workspace daily cap**, after which recording stops for the day and a single
  `violation_records_capped` event fires. Uncapped, one runaway workspace fills the table for
  everyone on a shared deployment.

Sampling was considered and rejected as the primary control: a 1-in-N sample makes "did my new rule
fire on this request?" unanswerable, which is the question the feature exists for. The cap preserves
completeness up to a limit rather than degrading it everywhere.

## 5. Read surface

GraphQL only, admin-scoped, matching the confidence-threshold and action-policy precedent — no
settings-page UI in this project, stated rather than silently omitted.

The authorization shape is not novel and must not be invented: `AuditEventFacadeAuthorizationTest`
already exists for the neighbouring store and its shape applies. Records are workspace-scoped, so the
facade checks workspace membership, and a record id from another workspace must be indistinguishable
from a missing one — the same rule `VariableServiceImpl` follows for variables, so ids never leak
across scopes.

## 6. Observability

- `violation_record_written` — a record was persisted.
- `violation_record_dropped` — the async writer shed a record (queue full, write failed). **Not
  optional**: §4's "a dropped record is acceptable" is only acceptable if drops are visible, otherwise
  an operator debugging a rule cannot distinguish "it never fired" from "we lost the row".
- `violation_records_capped` — the daily cap was reached for a workspace.

## 7. Testing

- **No matched value appears in a written record, for any field.** The load-bearing test of the whole
  design. It must assert over the *serialized* record rather than field-by-field, or it pins only the
  fields someone remembered.
- **The keyed hash differs across tenants for the same input**, so a record cannot be correlated
  between workspaces, and **is stable within one tenant**, or the recurrence use case it exists for
  does not work.
- **A record under `ALLOW` is marked as observed, not enforced**, and is distinguishable at read time.
- **A slow or failing record write does not delay or fail the guarded call**, and increments
  `violation_record_dropped`.
- **The daily cap stops writes and fires `violation_records_capped` exactly once**, not once per
  suppressed record.
- **A record id from another workspace reads as not-found, not as forbidden** — the probe-oracle rule.
- **Recording off by default**: an unconfigured workspace writes nothing. Verified rather than assumed.

## 8. Non-goals

- **Storing matched values, in any mode.** §2.
- **A settings-page UI.** §5.
- **Cross-workspace or org-wide reporting.** A tenant-admin roll-up is a separate surface with a
  separate authorization question.
- **Alerting on records.** The counter already exists for thresholds; alerting belongs with metrics,
  not with a drill-down store.
- **Retaining records beyond the configured horizon for compliance evidence.** If a customer needs
  guardrail activity as an audit artefact, that is `platform-audit`'s job and a different retention
  policy, decided with legal input rather than here.

## 9. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | A record never stores the matched value | Otherwise the privacy feature builds a plaintext PII store with a read API in front of it |
| D2 | Pattern name + offset + length + confidence carry the debugging value instead | Locates and identifies a match without reproducing it |
| D3 | Recurrence correlation uses a **keyed**, per-tenant hash, off by default | A plain hash of an SSN or email is reversible by enumeration; "anonymised" would be a disclosure |
| D4 | No raw-value debug mode, not even time-limited | It is the obviously useful thing and it converts the store into exactly what D1 forbids |
| D5 | A new table, not `platform-audit` | Audit is principal-keyed with no workspace column, a 256-char key/value side table, and human-action volumes |
| D6 | Retention copies `AuditEventRetentionJob`'s shape | Scheduled sweep with a configured horizon; no reason to invent one |
| D7 | Off by default, per workspace | The counter stays the free path; drill-down is opted into |
| D8 | Written asynchronously, dropped rather than blocking | A dropped record is acceptable; a stalled model call is not |
| D9 | A daily per-workspace cap, with its own event | One runaway workspace must not fill a shared deployment's table |
| D10 | Cap rather than sample | Sampling makes "did my rule fire on this request?" unanswerable, which is the question the feature exists for |
| D11 | Records carry the action taken | An `ALLOW` record is a counterfactual and must not read as an enforcement |

## 10. Sequencing note

This is buildable today and its value is mostly latent until custom rules (queue item 6) exist —
built-in patterns are already documented and scored, so "which pattern fired" is answerable from the
catalog. If the two are built in the order the queue lists them, expect the drill-down to look
over-engineered until the first custom rule is written. That is a reason to sequence 6 before 8, not
a reason to shrink 8.

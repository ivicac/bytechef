# Enterprise Parity Gaps — Tracking & Scope

**Date:** 2026-06-01
**Status:** Umbrella tracking doc (not an implementation spec)
**Branch context:** Authored against `0_732`.

## Purpose

A competitive review of **n8n Enterprise Edition** and **Activepieces Enterprise
Edition** against ByteChef surfaced a set of enterprise governance / security /
lifecycle features. After the `0_732` branch, most of those are already implemented
in ByteChef. This document tracks the **seven confirmed remaining gaps**, fixes their
scope boundaries, and proposes a build order.

This is a tracking doc only. Each feature gets its own
`docs/superpowers/specs/YYYY-MM-DD-<feature>-design.md` (design → plan →
implementation) when it is picked up. Nothing here is a commitment to a specific
implementation; the per-feature specs own that.

## Already closed on `0_732` (do not re-litigate)

These were originally flagged as gaps but are implemented. Listed here so the
competitive picture is unambiguous and they are not respec'd by mistake.

| Capability | Edition | Where it lives |
|---|---|---|
| Git source control + dev/staging/prod environments (push/pull on publish, env-aware deployment) | EE | `server/ee/libs/automation/automation-configuration/*` (`ProjectGitConfiguration`, `ProjectGitSyncEventListenerImpl`, `EnvironmentServiceImpl`); `platform-configuration` `Environment` enum; client `projectGit.mutations.ts`, `useEnvironmentStore.ts` |
| SAML 2.0 SSO (per-tenant dynamic relying-party registration, SP metadata) | EE | `server/ee/libs/config/security-sso-config/.../saml2/*` |
| SCIM 2.0 user/group provisioning (RFC 7644, bearer auth) | EE | `server/ee/libs/platform/platform-user/platform-user-scim/*` |
| Custom / granular project roles (26 `PermissionScope` values, built-in role subset hierarchy, audit) | EE | `server/ee/libs/automation/automation-configuration/*` (`CustomRole`, `PermissionScope`, `BuiltInRoleScopes`, `CustomRoleServiceImpl`, `CustomRoleGraphQlController`) |
| External secrets — HashiCorp Vault + AWS Secrets Manager credential stores | EE | `platform-connection-credential-store-hashicorp-vault`, `platform-connection-credential-store-aws-secrets-manager` |
| TOTP 2FA (opt-in, per user) | CE | `server/libs/config/security-config/.../web/filter/TwoFactorVerificationFilter.java`, `TwoFactorAuthentication*`; client `AccountProfileMfa.tsx`, `MfaVerification.tsx` |

## Confirmed remaining gaps

Seven features, all targeted at **Enterprise Edition (EE)**. Build order is
**effort-ascending** (ship value fastest): **A → B → C → D → E → F → G**.

> Effort key: **S** ≈ extend an existing pattern (~1–2 days). **M** ≈ new but
> well-trodden subsystem. **L** ≈ new subsystem with significant UI.

---

### A. Additional external-secret providers — Azure Key Vault, GCP Secrets Manager, 1Password

- **Edition:** EE
- **Effort:** S
- **Builds on:** the existing credential-store pattern —
  `platform-connection-credential-store-hashicorp-vault` and
  `platform-connection-credential-store-aws-secrets-manager`. Each provider is a new
  sibling module implementing the same `ConnectionCredentialStore` SPI plus an
  `EnvironmentPostProcessor`-style config bean.
- **In scope:** read/write credential storage for Azure Key Vault, GCP Secret
  Manager, and 1Password (Connect / Secrets Automation API), selectable per the same
  configuration mechanism as Vault/AWS.
- **Out of scope:** a generic "bring-your-own secrets backend" plugin SPI; secret
  rotation orchestration; provider-specific versioning UIs.
- **Closes vs:** n8n external-secrets provider breadth.
- **Open questions:** 1Password auth model (Connect server vs. Service Account
  token); whether all three ship together or 1Password trails the two cloud KMS
  providers.

---

### B. Org-wide enforced 2FA policy

- **Edition:** EE
- **Effort:** S
- **Builds on:** the existing CE TOTP 2FA (`TwoFactorVerificationFilter`,
  `TwoFactorAuthenticationCustomizer`). Today 2FA is opt-in per user and gated by a
  global property. This adds an **EE admin policy** that requires 2FA for all members
  of a tenant/workspace.
- **In scope:** a tenant/workspace-scoped "require 2FA" setting (admin-only);
  enrollment enforcement on login for users who have not yet set up TOTP (redirect to
  setup before granting a full session); admin visibility of who is/ isn't enrolled.
- **Out of scope:** non-TOTP second factors (WebAuthn/passkeys, SMS); per-role 2FA
  exemptions beyond the existing SSO-user exemption; recovery-code management redesign
  (reuse current flow).
- **Closes vs:** n8n "we enforce 2FA".
- **Open questions:** policy scope level — tenant-wide vs. per-workspace; grace period
  vs. hard block on next login; interaction with SSO/SAML users (typically exempt
  since the IdP owns MFA).

---

### C. LDAP / Active Directory authentication

- **Edition:** EE
- **Effort:** M
- **Builds on:** new module beside `server/ee/libs/config/security-sso-config`,
  using Spring Security LDAP. No existing LDAP code in the repo today.
- **In scope:** LDAP/AD bind authentication (sign in with directory credentials);
  attribute → user mapping; just-in-time account creation on first successful bind;
  configuration of LDAP server, base DN, bind credentials, search filters.
- **Out of scope:** scheduled directory sync / write-back to LDAP; LDAP group → role
  mapping in v1 (note as a follow-up); LDAP as the audit source of truth.
- **Closes vs:** n8n LDAP support. Note: with SAML + SCIM already shipped, most modern
  enterprises route through the IdP; validate that target buyers require direct LDAP
  bind rather than SAML before prioritizing.
- **Open questions:** whether group→role mapping is required for v1; AD-specific quirks
  (sAMAccountName, referrals, nested groups) in or out of v1.

---

### D. Execution-data retention / pruning policies

- **Edition:** EE
- **Effort:** M
- **Builds on:** the Atlas execution tables (job/task execution history) plus a new
  scheduled pruning job and admin configuration surface.
- **In scope:** admin-configurable retention (e.g., max age and/or max count of
  executions, optionally split by status — keep failures longer than successes); a
  scheduled prune job; safe batched deletes that respect FK relationships and
  file-storage artifacts referenced by executions.
- **Out of scope:** cold-storage archival/export before delete; per-workflow retention
  overrides in v1 (note as follow-up); retention for AI observability traces (separate
  subsystem with its own lifecycle).
- **Closes vs:** n8n execution-data pruning.
- **Open questions:** scope level for the policy (instance vs. tenant vs. workspace);
  whether file-storage artifacts tied to pruned executions are deleted in the same job
  or a separate sweep; default retention values.

---

### E. Log streaming to external sinks (Syslog / Sentry / Datadog / SIEM)

- **Edition:** EE
- **Effort:** M–L
- **Builds on:** partial overlap with the existing audit-event infrastructure
  (`platform-audit`). This is a new event fan-out subsystem that forwards
  execution/audit/log events to configured external destinations.
- **In scope:** pluggable destination types (at minimum: generic webhook, Syslog,
  Sentry; Datadog via its intake API); per-destination event filtering; delivery with
  retry/backpressure handling; admin configuration of destinations and credentials.
- **Out of scope:** building an in-product log search/index (that is feature G's
  neighbor, not this); guaranteed exactly-once delivery; transforming/normalizing into
  every vendor's bespoke schema beyond a documented common envelope.
- **Closes vs:** n8n log streaming.
- **Open questions:** which event classes are streamable (workflow execution events,
  audit events, system logs — which in v1); reuse of audit-event plumbing vs. a
  dedicated event bus; credential storage for destinations (can reuse feature A's
  secret stores).

---

### F. Workflow version diff (staging ↔ production visual comparison)

- **Edition:** EE
- **Effort:** L
- **Builds on:** the existing git-sync layer and `Environment` model — the backend
  already pushes/pulls workflow definitions per environment. This adds **diff
  computation** between two versions/environments and a **diff UI** in the editor.
- **In scope:** compute a structured diff between a workflow's definition in two
  environments (or two git revisions); render it in the client (added/removed/changed
  nodes, parameters, connections); surface it at promotion time so users see what will
  change before pushing dev → staging → prod.
- **Out of scope:** in-app pull-request / approval review flow (note as a possible
  follow-up); three-way merge / conflict resolution (current git layer is
  pull-overwrites); diffing of execution data.
- **Closes vs:** n8n workflow diffs.
- **Open questions:** diff granularity (node-level vs. full-JSON structural diff);
  whether diff is computed server-side and shipped to the client or computed
  client-side from two definitions; how it ties into the existing promote action.

---

### G. Insights / workflow-analytics dashboard

- **Edition:** EE
- **Effort:** L (largest)
- **Builds on:** new aggregation pipeline over execution history plus a new dashboard
  UI. Distinct from AI observability (which tracks LLM traces/sessions, not workflow
  operational metrics).
- **In scope:** operational analytics over a configurable time window — execution
  counts, success/failure rates, run durations, top failing workflows, and a
  per-workflow "time saved" metric (fixed or dynamic per workflow); a dashboard view
  in the client.
- **Out of scope:** arbitrary custom report builder; data export/BI connector in v1;
  real-time streaming dashboards (periodic aggregation is acceptable for v1);
  cost analytics (separate from LLM usage tracking, which already exists).
- **Closes vs:** n8n Insights.
- **Open questions:** aggregation strategy (on-read vs. pre-aggregated rollup tables);
  scope of "time saved" definition and where it is configured; retention interaction
  with feature D (aggregates must outlive pruned raw executions).

## Cross-feature dependency notes

- **A → E:** log-streaming destinations need credentials; reuse feature A's external
  secret stores rather than inventing a second secret-handling path.
- **D ↔ G:** retention pruning (D) deletes raw execution rows; the insights dashboard
  (G) must read from pre-aggregated rollups so analytics survive pruning. If G is
  built after D, design G's rollups to be populated before D prunes.
- **F depends on** the existing git/environment layer already on `0_732`; no new
  persistence, but it consumes the same workflow definitions the git layer manages.
- **E reuses** `platform-audit` event plumbing where possible instead of a parallel
  event bus.
- **B and C** both touch authentication but are independent; B extends CE 2FA, C is a
  new EE auth method. They can proceed in parallel if desired.

## Out of scope for the whole initiative (for now)

- Azure-only OCR / non-OpenAI embedding wiring (unrelated AI-config gaps).
- LDAP write-back / scheduled directory sync (read/bind only in C).
- WebAuthn / passkeys / SMS second factors (B is TOTP enforcement only).
- Generic "bring-your-own backend" plugin SPIs for secrets (A) or log sinks (E).
- In-app PR/approval review and three-way merge for workflow versions (F).
- Custom report builder and BI export for analytics (G).

## Next step

Pick the first feature to spec in depth (recommended: **A**, the smallest and
pattern-following). That feature then runs the full brainstorming → design spec →
implementation plan cycle in its own document.

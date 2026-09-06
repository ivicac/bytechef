---
title: PII
description: Rule-based detection of personally-identifiable information - emails, phones, SSNs, IBANs, credit cards, IPs, and locale-specific identifiers
---

`PII` is a rule-based detector for personally-identifiable information. It runs in the **preflight stage** of `Check For Violations` (or `Sanitize Text`) and emits matched spans for the parent advisor's longest-first masking pass.

---

## What It Detects

The built-in entity catalogue covers globally-recognized identifiers and several locale-specific forms:

| Locale | Entities |
|---|---|
| **Global** | `EMAIL_ADDRESS`, `PHONE_NUMBER`, `CREDIT_CARD`, `IP_ADDRESS` (v4), `IBAN_CODE`, `DATE_TIME`, `LOCATION`, `MEDICAL_LICENSE`, `CRYPTO` |
| **United States** | `US_SSN`, `US_DRIVER_LICENSE`, `US_PASSPORT`, `US_BANK_NUMBER`, `US_ITIN` |
| **United Kingdom** | `UK_NHS`, `UK_NINO` |
| **Italy** | `IT_FISCAL_CODE`, `IT_DRIVER_LICENSE`, `IT_VAT_CODE`, `IT_IDENTITY_CARD`, `IT_PASSPORT` |
| **Spain, Poland, Singapore, Australia, India, Finland** | Regional national-ID and passport formats |

Open the **Entities** dropdown in the editor for the live list - new entities are added behind the same property without a version bump.

---

## Properties

| Property | Description |
|---|---|
| **Type** | `All` scans every built-in entity. `Selected` enables only the entities listed in **Entities** |
| **Entities** | Subset of entity types to scan (visible only when **Type** is `Selected`) |

For project-specific patterns (internal ticket IDs, study identifiers, custom account formats), attach the standalone **Custom Regex** action alongside `PII` in the same `Check For Violations` parent. Both run in the preflight stage; their masks merge in the same longest-first pass so overlap (an internal id contained inside an email, etc.) is handled correctly.

---

## Two Variants

`PII` exposes two cluster elements with the same configuration surface:

- **PII (check)** - emits a violation so the parent advisor blocks the request and lists the entity types that fired in the violation diagnostic.
- **PII (sanitize)** - emits the same mask entities but the parent advisor rewrites the text instead of blocking.

Both feed into the parent's mask pass, so PII matches merge with secret-key, URL, and custom-regex matches in a single longest-first redaction.

---

## Mask Placeholders

Each entity type renders as `<TYPE>` - `<EMAIL_ADDRESS>`, `<US_SSN>`, `<CREDIT_CARD>`, etc. The placeholder is stable across runs so downstream tools (a chat-memory store, a logging pipeline) can rely on matching on the placeholder string.

---

## Examples

Scan everything, block on any hit:

```json
{
  "type": "pii/v1/piiCheck",
  "parameters": { "type": "ALL" }
}
```

Scan only EU-relevant identifiers, mask in place:

```json
{
  "type": "pii/v1/piiSanitize",
  "parameters": {
    "type": "SELECTED",
    "entities": ["EMAIL_ADDRESS", "PHONE_NUMBER", "IBAN_CODE", "IT_FISCAL_CODE", "UK_NINO"]
  }
}
```

---

## PII Protection and Tool Calls

This section is about the **always-on platform guardrail** referenced a few times above (Settings → AI Agents → Guardrails → **Redact PII**), not the `PII` component on this page. The component only runs where you wire it into a `Check For Violations` or `Sanitize Text` chain; the workspace setting runs on every AI Agent and AI Hub turn regardless - including the tool calls an agent makes along the way.

When that always-on protection is on and an agent's turn involves a tool - `send-email`, a CRM lookup, an HTTP request to a partner API - two things happen that didn't before:

- **A tool now receives the real value, not a placeholder.** Previously, the model only ever saw a placeholder like `[PII_EMAIL_ADDRESS_1_k3n9]` in place of a real email address, so a tool the model called with that value received the placeholder too - `send-email` would try to send to `[PII_EMAIL_ADDRESS_1_k3n9]` instead of the real address. It now receives the real address, which is what makes `send-email` (and any tool like it) actually work.
- **Data a tool returns is protected before the model sees it.** A tool that looks up a customer record, queries a database, or otherwise returns PII now has that PII protected before it ever reaches the model, not just when the model's own reply is scanned afterward.

**This is a real change in what leaves your instance, not just a bug fix:** before this, a tool that sends data to a third party - an HTTP action posting to a partner API, a CRM write - only ever received the placeholder, because that was all the model had to give it. Nobody designed that as a protection; it was a side effect of how placeholders worked, and it protected a partner API by the exact same mechanism that broke `send-email`. Now that every tool receives the real value, that accidental protection is gone. If you have a workflow where an AI Agent calls a tool that forwards data outward, and you were relying on that tool only ever seeing a placeholder, it now sees the real value instead.

There is currently **no per-tool control**. Protection is on for every tool a guarded AI Agent or AI Hub turn calls, or off if you turn off **Redact PII** for the workspace - there is no way yet to let one tool see real values while another keeps seeing placeholders.

**The capability cost**: because a tool's result is protected before the model sees it, the model can no longer reason about the *content* of PII a tool returns. It can see that a tool call produced an email address and pass that address on to another tool, but it cannot read the address, summarize it, or make a decision based on what it says.

---

## Edge Cases

- **Overlap with URLs guardrail**: emails contain a domain-shaped substring; URLs are handled by the separate `URLs` guardrail. The longest-first mask pass across PII + URLs + secret-key + custom-regex matches guarantees the email is masked as a whole `<EMAIL_ADDRESS>` rather than being split.
- **Overlap with secret keys**: a JWT looks like base64 with two dots. PII does not classify JWTs, but if you author a custom regex that matches both, the longest match wins.
- **Phone number false positives**: the phone regex matches the common `NNN-NNN-NNNN` shape, with an optional country code (`+1`), optional parentheses around the area code, and a mandatory separator (dash, dot, or whitespace) between each group. A bare, unformatted digit run - an order number or tracking ID with no punctuation - will not match; a formatted number that happens to fit the 3-3-{4-6} grouping (`555-123-4567`, `555.123.4567`, `555 123 4567`) will, whether or not it's an actual phone number. If your domain has structured, separator-delimited non-PII numbers that match the shape, pair PII with a Custom Regex allowlist or scope detection via **Type: Selected**.
- **US_SSN no longer matches a bare 9-digit run**: ticking **US Social Security Number** only catches the dashed form (`123-45-6789`); a bare `123456789` no longer matches under `US_SSN` at all. This component has no confidence threshold to fall back on (that mechanism only applies to the always-on platform guardrail path), and the compensating type is a *different* picker option with a different placeholder: tick **Australian Tax File Number** (`AU_TFN`) as well if you need bare 9-digit runs caught, and expect the mask to read `<AU_TFN>`, not `<US_SSN>`.
- **CREDIT_CARD matches unformatted numbers too, gated by a Luhn checksum**: the pattern's separators (dash, space) are optional, so ticking **Credit card number** matches a bare 16-digit run (`4111111111111111`) exactly as readily as a dashed or spaced one (`4111-1111-1111-1111`) - but only when the digits pass the Luhn checksum. This component has no confidence threshold to fall back on (that mechanism only applies to the always-on platform guardrail path), so the checksum is the only thing standing between an ordinary 16-digit business identifier (an order or invoice number, say) and a false `<CREDIT_CARD>` mask; a run that fails Luhn is not masked as `CREDIT_CARD` at all.

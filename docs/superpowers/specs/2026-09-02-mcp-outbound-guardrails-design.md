# MCP outbound guardrails — design

**Status:** approved, not implemented
**Ticket:** 732
**Date:** 2026-09-02

## Problem

An external agent — Claude, Cursor, a partner's own agent — calls a ByteChef MCP tool. The
workflow or component runs, reads a customer record, and the result is returned into that agent's
model context. ByteChef has no relationship with that model and no visibility into what happens
to the data afterwards.

MCP servers today have **zero** content inspection in either direction. Verified:

```
grep -rln 'guardrail\|Guardrail\|SensitiveDataRedactor\|PiiToken' \
  server/libs/platform/platform-mcp server/libs/ai/ai-mcp \
  server/libs/automation/automation-ai/automation-ai-mcp-server \
  --include='*.java' --exclude-dir=build
```

returns nothing. Authentication exists; content inspection does not.

Copilot, the canvas AI Agent, and AI Hub all talk to a provider the tenant configured under the
tenant's own key. MCP does not — the receiving party is an external agent the tenant did not
choose.

It is not, however, the *only* such surface, and an earlier draft of this document claimed it was.
**A2A servers reach the same kind of party**: `A2AAgentExecutor` returns an agent-backed
workflow's textual response to an inbound `message/send` from another party's agent, with no
content inspection in either direction. Nothing about A2A regresses here — it was never covered —
but it is a second uncovered exit, listed under Scope below and in `.agents/ai-guardrails.md`'s
"Not covered" so it is not mistaken for something this work protects.

## Scope

**In:** outbound results from the **automation** and **embedded** MCP servers.

**Out, deliberately:**

- **Inbound arguments.** They are the calling agent's own data, sent by its own choice.
  Redacting them would corrupt the workflow's inputs rather than protect anyone. (There is a
  separate, smaller concern — inbound arguments become workflow inputs persisted in `Job.inputs`
  and displayed on execution detail pages, so ByteChef stores third-party data. That is a
  retention question, not an exfiltration one, and is not this work.)
- **A2A servers** (`A2AAgentExecutor` / `A2AProtocolHandler`, `platform-ai-a2a`). An inbound
  `message/send` runs an agent-backed workflow and its textual response goes straight back to the
  calling agent, unredacted — the same untrusted receiving party MCP has, reached through a
  different protocol. It needs its own attachment point (the response is assembled in the protocol
  layer, not by a `ToolCallback`) and its own answer to which settings govern it; deliberately not
  in this work, and no test or document here claims otherwise.
- **Management MCP** (`ManagementMcpServerConfiguration`). It also returns real data
  (`queryDataTable`, `getAiSkillFileContent`, `searchContextStore`), but it is built differently:
  annotation-scanned `@Tool` methods via `ToolCallbacks.from(...)`, no facade, and many of its
  tools carry no workspace. It needs its own hook and its own answer to which settings govern.
- Making redaction visible in `ToolExecutionRecorder` history.
- Per-server or per-tool granularity.

## Why redaction, not tokenization

Tokenization works elsewhere because there is a return path: the advisor mints
`[PII_EMAIL_ADDRESS_0_abc]`, the provider sees the token, and the same `PiiTokenSession` restores
the real value when the response comes back.

MCP outbound has no return path through us. The external agent receives the result and keeps it.
A token we never get the chance to restore is a broken value in someone else's context, not a
protected one.

So this surface uses `SensitiveDataRedactor.redact(...)` — the irreversible path — and no
`PiiTokenSession` is involved at any point.

## Attachment point

### Why the `ToolCallback`

`ToolCallback.call(String toolInput, ToolContext toolContext)` returns a **`String`**: the result
already serialized by the callback's `ToolCallResultConverter` (verified against
spring-ai-model 2.0.1, `org/springframework/ai/tool/ToolCallback.java:53`).

That is the right granularity. A single `redact` over the serialized payload covers every nested
field by construction. The alternative — walking the result `Object` — has to handle `Map`,
`List`, arrays, records and nulls, and one unhandled node type leaks silently with no signal.
This mirrors why the PII tool boundary works on message text rather than on tool-result objects.

MCP tool callbacks are also all known at registration time (they come from `McpProject` /
`McpTool` rows), unlike AI Hub's tool-search catalog callbacks — which is why spec decision D2
rejected a per-callback decorator there and why it is correct here.

### Rejected alternatives

**Wrap the supplier inside `toolExecutionRecorder.record(builder, supplier)`.** Both facades
already call it, with `workspaceId` and `ToolExecutionSurface` in scope, and redaction would land
inside the recorded execution. Rejected because it is four call sites across two facades, and a
fifth tool path added later escapes silently — the exact failure mode that produced five rounds of
Copilot coverage gaps.

**Redact inside `ToolExecutionRecorder` itself.** One place, automatic coverage. Rejected: the
recorder records, it does not mutate results, and it is shared with `AI_AGENT` and
`EMBEDDED_API_*`, so a bug there reaches every surface.

**Decorate the `AsyncToolSpecification`** (as `ApprovalElicitingToolSpecifications.decorate` does).
Rejected: at that level the payload is a `CallToolResult` of content blocks, reintroducing the
structure-walk problem the `String` return avoids.

### The five wrap points

`AutomationMcpServerConfiguration` — three streams:

1. component tools — `mcpToolFacade.getFunctionToolCallback(mcpTool)`
2. workflow tools — `mcpToolFacade.getFunctionToolCallbacks(mcpProject)`, wrapped **before**
   `ApprovalElicitingToolSpecifications.decorate`, which operates on the spec
3. workspace-contributor tools — `provider.getFunctionToolCallbacks(workspaceId)`

`EmbeddedMcpServerConfiguration` — two streams: component tools and workflow tools.

Stream 3 is included deliberately. Those callbacks run on a customer's MCP server and are as
reachable by the external agent as any other tool on it.

Each configuration gets **one** private `guard(ToolCallback)` helper that its streams call, so the
wrapping logic is written once per file.

## The SPI seam

The MCP configurations are CE (`automation-ai-mcp-server`) and EE (`embedded-ai-mcp-server`);
guardrails policy lives in EE (`platform-ai-guardrails-service`). A CE seam is required, following
the `AiGuardrailsAdvisorProvider` / `ToolExecutionRecorder` idiom.

**In `platform-ai-api` (CE), package `com.bytechef.platform.ai.guardrails`:**

```java
public interface McpOutboundRedactorProvider {

    /**
     * @param workspaceId the MCP server's workspace, or null for the tenant default (embedded)
     * @param surface     "mcp_automation" or "mcp_embedded", for metrics tagging
     * @return a resolved redactor, or empty when outbound redaction is off for this workspace
     */
    Optional<McpOutboundRedactor> fetchRedactor(@Nullable Long workspaceId, String surface);
}

public interface McpOutboundRedactor {

    String redact(String serializedResult);
}
```

The SPI hands back a **fully resolved** redactor rather than a policy. Returning a policy would
leak three EE concepts — `SensitiveKind`, confidence thresholds, metrics tagging — into two MCP
modules that have no business knowing them. Returning `String -> String` leaves the CE side with
nothing to get wrong, and matches `AiGuardrailsAdvisorProvider` returning a built `Advisor`
instead of the settings behind it.

The EE implementation closes over the resolved policy, the `SensitiveDataRedactor` and an
`AiGuardrailMetrics` tagged with the given surface.

**Resolution is per call, not per registration.** MCP clients cache `tools/list` for a long time;
binding the redactor when the tool list is assembled would mean flipping the setting does nothing
until the server restarts. This is the same defect fixed for Copilot in
`DeferredGuardrailsAdvisor` — enabling a guardrail after boot must take effect.

## Settings

One nullable field on `AiGuardrailsWorkspaceSettings`
(`platform-ai-guardrails-api`, package `...guardrails.domain`):

```java
Boolean redactMcpResults    // null = not set at this level = off
```

plus its key in `AiGuardrailsWorkspaceSettingsServiceImpl`'s `toMap` and `toSettings`.

**No migration.** The record is persisted as a `PropertyService` row: `toMap` writes only non-null
fields, and `toSettings` reads `(Boolean) value.get(KEY_REDACT_MCP_RESULTS)`. Every existing row
lacks the key, deserializes it as `null`, and is therefore off — the chosen default.

### Why a separate switch

A customer who turns on PII redaction for their AI agents must not silently start receiving
`[REDACTED_EMAIL_ADDRESS]` in an MCP pipeline that was working. An MCP tool is frequently how a
customer deliberately hands their own data to their own agent. Inheriting the workspace's
`redactPii` would change that payload with no warning.

The cost is accepted: a customer wanting blanket protection flips a second switch, and "is PII
protected everywhere" has a two-part answer.

### Resolution path

A new method on `AiGuardrails` (EE):

```java
public @Nullable PiiTokenBoundaryPolicy resolveMcpOutboundPolicy(@Nullable Long workspaceId)
```

returning null when `redactMcpResults` is unset/false, and otherwise the kinds (from the
workspace's own `redactPii` / `redactSecrets`) and `minConfidence`.

**It reuses the existing `PiiTokenBoundaryPolicy` record rather than defining a new one.** Its
fields are `(Set<SensitiveKind> kinds, double minConfidence)` — a kinds-and-threshold pair with
nothing token-specific in it, which is exactly what this path needs. Its *name* is wrong here:
this surface never tokenizes, and a type called `PiiTokenBoundary*` on a redaction-only path is
the kind of misleading name that goes stale and misleads a later reader.

Duplicating the record to get a better name would be worse — two identical types drifting apart.
The right fix is to rename it to something surface-neutral (`SensitiveDataPolicy`) in a small
separate change, since it is referenced from `PiiTokenBoundaryToolCallingManager`,
`PiiTokenBoundaryPolicyToolContext` and `AiGuardrails#resolveToolBoundaryPolicy`. **That rename is
not part of this work** — it is recorded here so the naming debt is deliberate and visible rather
than accidental.

**It must not participate in `AiGuardrails.isActive`.** `isActive` gates whether chat surfaces
attach a guardrails advisor at all; adding `redactMcpResults` to its union would make enabling MCP
redaction start attaching advisors to Copilot and the canvas agent — precisely the cross-surface
surprise this design avoids.

`redactMcpResults` is the switch, not a duplicate category list: which kinds and what threshold
come from the settings the workspace already has.

### Embedded resolves to the tenant default

`EmbeddedMcpToolFacade` carries `ToolExecutionSurface.MCP_EMBEDDED` but no `workspaceId` —
embedded is `Property.Scope.EMBEDDED`, not workspace-scoped. It passes `workspaceId = null`, which
resolves the tenant-default settings row. This matches embedded Copilot, which already resolves
`getAdvisor(null, null, ...)`.

Consequence, stated so it is not discovered later: an embedded deployment cannot vary MCP outbound
redaction per workspace. There is one setting for the tenant.

## Failure policy — fail closed

`SensitiveDataRedactor` already absorbs per-detector failures internally: a detector that throws,
or reports a span past the end of the text, is skipped and the others still run. A throw out of
`redact` is therefore a total failure, not a flaky NER model.

On that, `RedactingToolCallback` **throws** rather than returning the raw payload, and logs at
WARN. Throwing is how this surface already signals a refusal: both facades throw
`ConfigurationException` with an `McpServerErrorType` when the server or the tool is disabled, and
the MCP layer converts it into a tool error the calling agent sees. The decorator follows that
existing pattern rather than inventing a second refusal channel.

What it must never do is return the delegate's result on a redaction failure — that is the one
outcome that silently defeats the control.

This inverts the engine's usual fail-open rule, for the same reason the PII tool boundary's result
direction does: this is the last point before data leaves to a party we have no relationship with,
and there is no second chance. A broken integration is recoverable; an exfiltrated record is not.

## Components

| Component | Module | Responsibility |
|---|---|---|
| `McpOutboundRedactorProvider`, `McpOutboundRedactor` | `platform-ai-api` (CE) | the seam |
| `McpOutboundRedactorProviderImpl` | `platform-ai-guardrails-service` (EE) | resolve policy, build a surface-tagged redactor |
| `AiGuardrails#resolveMcpOutboundPolicy` | `platform-ai-guardrails-service` (EE) | settings → `PiiTokenBoundaryPolicy` or null |
| `RedactingToolCallback` | `platform-ai-api` (CE) | the decorator; per-call resolution; fail closed |
| `guard(ToolCallback)` helper | each MCP configuration | one wrap site per file |
| `redactMcpResults` | `AiGuardrailsWorkspaceSettings` (EE api) + its service impl | the switch |

`RedactingToolCallback` lives in `platform-ai-api`, next to the SPI it consumes, because that is
the placement needing **no new module dependencies**. Verified:

- `platform-mcp-server-support` — the intuitive home, since this is MCP code — declares neither
  `platform-ai-api` nor any spring-ai artifact, so putting it there costs two new dependencies.
- Both `automation-ai-mcp-server` and `embedded-ai-mcp-server` already declare
  `platform-ai-api`, and both already use `org.springframework.ai.tool.ToolCallback` directly, so
  they see the decorator and its parameter type without anything being added.

`platform-ai-api` declares `spring-ai-model` as `implementation`, which is sufficient: consumers
reach `ToolCallback` through their own dependency, not transitively through this one.

## Coverage enforcement

An `McpOutboundGuardrailsCoverageTest` per MCP module, scanning its own configuration sources
(comments stripped) and failing when a statement containing `toAsyncToolSpecification(` does not
also contain `guard(`.

Five wrap points across two files is exactly the shape that produced five rounds of Copilot gaps,
and a sixth stream added later is the likely regression. Each scan must be negative-controlled in
both directions before it is trusted: removing a `guard(` call must fail it, and the clean tree
must pass.

## Testing

**`RedactingToolCallback`** (unit, no Spring):

- redacts a result when the provider resolves a redactor
- returns the delegate's result untouched when the provider is empty
- returns a tool error, not the raw payload, when the redactor throws
- resolves per call: with the provider empty on the first call and populated on the second, the
  second call is redacted — the pin against binding at registration

**`McpOutboundRedactorProviderImpl`** (unit):

- empty when `redactMcpResults` is unset, and when it is explicitly false
- present when set, carrying the kinds implied by `redactPii` / `redactSecrets`
- honours the workspace's `minConfidence`, falling back to
  `SensitiveDataRedactor.DEFAULT_MIN_CONFIDENCE`
- `workspaceId = null` resolves the tenant-default row

**Settings round-trip** (`AiGuardrailsWorkspaceSettingsServiceTest`): a stored map without the new
key deserializes to `null`, and a settings object with `null` does not write the key.

**Coverage scans:** one per module, negative-controlled.

## Open question, deferred

Whether an MCP server shared with a partner and one used internally should be able to differ
(per-server granularity) is a real product question. It needs a column on `mcp_server`, UI and API
surface, and is deliberately not in this design. The workspace-level switch is the smaller first
step and does not foreclose it.

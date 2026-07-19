# Expose ByteChef AI Agent over the A2A (Agent2Agent) protocol

Date: 2026-07-19
Status: Proposed — protocol core landed; app wiring pending

## Problem

ByteChef supports A2A only as a **client**, and even that is currently disabled:
`AiAgentUtilsAgentClientTool` (using `io.github.a2asdk:a2a-java-sdk-client`) is commented
out of `AiAgentUtilsComponentHandler`. There is **no A2A server surface** — no agent card,
no JSON-RPC `message/send`, no streaming. External A2A clients (Claude, Cursor, other
agents) therefore cannot call a ByteChef AI Agent.

The goal is to expose a ByteChef agent **as** an A2A-compliant server so external A2A
clients can discover it (agent card) and send it tasks (`message/send`).

## What "an agent" is in ByteChef

An AI Agent is not a standalone addressable entity — it is an `aiAgent` component action
(`AiAgentChatAction.perform`) that runs **inside a workflow execution** (it needs a model
cluster element, tools, memory, and an `ActionContext`). So "expose the AI Agent over A2A"
concretely means **expose an agent-backed workflow as an A2A endpoint**, mirroring how the
MCP server exposes workflows that carry a `workflow/newWorkflowCall` trigger
(`AutomationMcpToolFacade` + `McpProjectWorkflow`).

## Decision

Bridge inbound A2A `message/send` calls onto the existing synchronous
workflow-execution path, and generate the agent card from workflow/agent metadata. Reuse
the official `a2a-java-sdk-spec` record types for wire compatibility; do **not** pull the
client SDK's transport machinery (that is what makes the client tool runtime-fragile).

## Landed in this change (protocol core) — `platform-ai-a2a`

A self-contained, unit-tested CE library that carries no dependency on the execution stack:

- **`A2AAgentExecutor`** (SPI) — `A2AAgentResult execute(A2AAgentRequest)`. The seam an app
  module implements to actually run the addressed agent.
- **`A2AAgentRequest` / `A2AAgentResult`** — resolved bridge input/output (agent id, user
  text, context id; response text / error).
- **`A2AAgentDescriptor` (+ `A2ASkill`)** — transport-agnostic agent description.
- **`A2AAgentCardFactory`** — builds the wire-format `io.a2a.spec.AgentCard`
  (`/.well-known/agent-card.json`): text input/output over the `JSONRPC` transport,
  `streaming=false`, protocol version `0.3.0`.
- **`A2AProtocolHandler`** — dispatches a parsed JSON-RPC request for one agent:
  `message/send` → concatenate the inbound message's text parts → `A2AAgentExecutor` →
  return a completed/failed `Task`. Unknown methods → `MethodNotFoundError` (-32601);
  missing/empty message → `InvalidParamsError` (-32602). Agent exceptions become a FAILED
  task, not a transport error.

The response text is placed on the task's `status.message` (an AGENT-role text message),
which ByteChef's own A2A client already reads (it falls back from artifacts to
status.message).

## Remaining app wiring (needs the running stack; not in this change)

1. **HTTP surface** — a `@Configuration` registering a `RouterFunction` (mirror
   `AutomationMcpServerConfiguration`) for:
   - `GET /api/automation/a2a/{secretKey}/.well-known/agent-card.json` →
     `A2AAgentCardFactory.create(descriptor)` serialized with the A2A Jackson module.
   - `POST /api/automation/a2a/{secretKey}` → deserialize the JSON-RPC body into a
     `SendMessageRequest` (the spec ships `NonStreamingJSONRPCRequestDeserializer`), call
     `A2AProtocolHandler.handle(...)`, serialize the response.
2. **Execution bridge** — an `A2AAgentExecutor` impl that resolves the `{secretKey}` to an
   agent-backed workflow and runs it synchronously (reuse the same facade the MCP
   `newWorkflowCall` path uses), mapping the workflow output to `A2AAgentResult`.
3. **Auth** — reuse the per-secret-key + `*ApiKeyAuthenticationProvider` pattern the MCP
   servers use; the secret is the tenant anchor.
4. **Opt-in registration** — an `A2aProjectWorkflow`-style mapping (mirror
   `McpProjectWorkflow`) so specific agents/workflows are explicitly exposed, plus the
   descriptor (name/description/skills) surfaced on the card.
5. **Streaming (later)** — add `message/stream` → SSE, reusing `AiAgentStreamChatAction`,
   and flip `AgentCapabilities.streaming` to true.

## De-risking note

The A2A **client** tool is disabled because the client SDK's transport (ServiceLoader /
gRPC / JSON-RPC transport wiring) is runtime-fragile in the packaged app. The server core
here deliberately avoids that: it depends only on `a2a-java-sdk-spec` (pure Jackson POJOs,
deps: `a2a-java-sdk-common` + jackson). Re-enabling the client tool and validating the full
client SDK at runtime is a separate, parallel task and a good first end-to-end smoke test
of A2A interop once the server surface is wired.

## Testing

- `A2AProtocolHandlerTest` — message/send success → COMPLETED task with agent text;
  agent-failure and thrown-exception → FAILED task; unknown method → -32601; missing
  message → -32602.
- `A2AAgentCardFactoryTest` — card advertises text modes, JSONRPC transport, skills.
- End-to-end (needs the app): point an external A2A client at the agent-card URL and send a
  task; assert the routed workflow runs and the response returns as a completed task.

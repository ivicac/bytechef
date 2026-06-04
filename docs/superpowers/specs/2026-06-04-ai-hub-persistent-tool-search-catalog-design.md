# AI Hub persistent tool-search catalog (static tools + cluster-element activation)

**Date:** 2026-06-04
**Status:** Design — pending review
**Branch:** `0_732`

## Summary

Register the full set of automation/platform `@Tool` bean classes
(`projectTools`, `projectWorkflowTools`, `componentTools`, `taskTools`,
`taskDispatcherTools`, `scriptTools`, `clusterElementTools`, plus the read-only
variants for ASK mode) as first-class AI Hub tools — but deliver them through a
**persistent, embed-once tool-search catalog** instead of per-request static tool
callbacks, so they are not re-embedded on every user turn.

This folds together three pieces of work:

- **A — capability:** give the AI Hub agents direct access to project / workflow /
  component / task tools (closing the gap where AI Hub could only reach
  `listProjects` indirectly through the `workflow_editor_agent` subagent).
- **B — performance/infra:** embed those tools once at startup into a persistent
  search session rather than relying on the advisor's per-turn self-indexing (which,
  with `VectorToolSearcher`, re-embeds every registered tool on every user turn).
- **C — activate the dormant catalog:** wire search to read the cluster-element
  Workspace catalog (`CATALOG_SESSION_ID`), which the feeder already pre-embeds but
  which nothing currently queries, so component-action tools become discoverable.

## Background — verified current behavior

The AI Hub tool-search stack (all verified by reading the source, not inferred):

1. **`SpringAIAgent.getChatRequest`** (`ag-ui/integrations/spring-ai`) merges the
   agent's static `.toolCallbacks(...)` onto the request, attaches the advisors
   (including `ToolSearchToolCallAdvisor`), and sets
   `ChatMemory.CONVERSATION_ID = input.threadId()`.

2. **`ToolCallAdvisor`** (Spring AI core `spring-ai-client-chat`) forces
   `internalToolExecutionEnabled(false)` and runs the tool-calling loop itself, so
   the `ToolSearchToolCallAdvisor` hooks (`doInitializeLoop` / `doBeforeCall`) are
   genuinely in control of what tools the model sees.

3. **`ToolSearchToolCallAdvisor`** (forked, `spring-ai-tool-search-tool/tool-search`):
   - `doInitializeLoop`: `clearIndex(conversationId)`, then for **every** resolved
     tool definition (which includes the request's `.toolCallbacks(...)` — confirmed
     via `DefaultToolCallingManager.resolveToolDefinitions`, which returns
     `chatOptions.getToolCallbacks()` plus resolver-resolved tool names) calls
     `indexTool(conversationId, ...)`. Each `indexTool` is a `vectorStore.add`, i.e.
     an embedding call.
   - `doBeforeCall`: replaces the advertised tool list with `{toolSearchTool}` plus
     any tools already discovered in prior `toolSearchTool` responses. **So static
     tools are gated — never all sent to the model at once — but they ARE re-embedded
     every turn.**
   - `doFinalizeLoop`: `clearIndex(conversationId)`.

4. **`VectorToolSearcher.search`** (forked, `tool-searcher-vectorstore`): does a
   global similarity search, then filters results to those whose `sessionId` metadata
   **equals** `request.sessionId` (single-session match, line ~113). The advisor's
   request `sessionId` is the per-conversation id.

5. **`ToolSearchCatalogFeeder`** pre-embeds the cluster-element catalog under
   `CATALOG_SESSION_ID` and per-task subsets under `:task:<id>` sessions, with a
   SHA-256 hash-skip + `ai_hub_tool_search_catalog_meta` table to avoid re-embedding
   unchanged catalogs. **This persistent catalog is currently dormant: nothing passes
   `CATALOG_SESSION_ID` or a task session to `search`, so it is never queried.** The
   only live search corpus is the advisor's per-turn self-index under the conversation
   id.

### The cost this design removes

Because the advisor re-indexes the request's resolved tools every turn, each static
tool registered on an agent costs one embedding call per user turn. Registering the
full project/workflow/component/task set (~46 BUILD tool methods) as static callbacks
would add ~46 embedding calls per turn. This design moves those tools to a persistent
session embedded once at startup (hash-skipped), reducing their per-turn embedding
cost to zero.

## Goals

Two pieces, sharing one mechanism (search reading persistent sessions):

1. **Static tools embed once.** AI Hub ASK and BUILD agents can discover and execute the
   full automation/platform tool set via tool search, with those tools embedded **once**
   at startup (hash-skipped) — never re-embedded per user turn.
2. **Activate the cluster-element Workspace catalog.** The thousands of component-action
   (cluster-element) tools the feeder already pre-embeds under `CATALOG_SESSION_ID`
   become discoverable via search (today they are pre-embedded but never queried).

- Preserve the ASK = read-only / BUILD = read-write boundary.
- Keep the existing `workflow_editor_agent` (and other subagent) delegation intact —
  these tools are added *alongside* it.
- Minimal, clearly-marked changes to the forked `spring-ai-tool-search-tool` modules.

## Non-goals

- **Not** changing the per-task / personal-agent tool delivery path: per-task attached
  tools keep flowing through `additionalToolCallbacks` (self-indexed per turn under the
  conversation session) with their pinned connection/parameters. The feeder's per-task
  sessions (`:task:<id>`) remain dormant — out of scope here.
- **Not** reducing the per-turn embedding cost of the *existing* built-in AI Hub static
  tools (open-tab, list-data-tables, subagent delegations, etc.). Only the new
  automation/platform tool beans move to the persistent catalog; the built-ins keep
  self-indexing per turn. The same `populateGlobalTools` mechanism can migrate them in a
  follow-up.

## Why per-mode separation is required (not optional)

ASK must expose read-only variants (`ReadProjectTools`) and BUILD the full mutating
set (`ProjectTools`). But `ReadProjectTools.listProjects` and
`ProjectTools.listProjects` share the **same tool name** (`listProjects`). A single
shared catalog/resolver holding both would collide on tool name. Today ASK and BUILD
share one `toolSearchToolCallAdvisor` bean (both agents inject the same provider).

Therefore the persistent global catalog must be **per mode**: a separate global
session + resolver entries for ASK and for BUILD. This drives the bean split below.

## Target design

### Sessions involved per request

Three kinds of session feed a search, unioned per request:

- `CATALOG_SESSION_ID` (`ai_hub_tool_catalog`) — the **existing** Workspace catalog of
  cluster-element component actions, pre-embedded once by `feeder.populate()`. Shared by
  both modes (component actions have no read/write variants). **Newly wired into search**
  by this design — it is the dormant catalog being activated.
- `ai_hub_tool_catalog:global:ask` / `:global:build` — **new** per-mode persistent
  sessions for the automation/platform tool beans (read-only variants for ASK, full set
  for BUILD). Pre-embedded once at startup.
- the per-conversation session (`conversationId` = threadId) — the advisor's existing
  per-turn self-index, carrying per-task `additionalToolCallbacks` and the existing
  built-in AI Hub static tools. Unchanged.

The per-mode separation of the global static session is required by the name-collision
argument above; `CATALOG_SESSION_ID` needs no per-mode split.

### Tool membership

| Tool bean | ASK global (`:global:ask`) | BUILD global (`:global:build`) |
|---|---|---|
| `ReadProjectTools` / `ProjectTools` | `ReadProjectTools` | `ProjectTools` |
| `ReadProjectWorkflowTools` / `ProjectWorkflowTools` | `ReadProjectWorkflowTools` | `ProjectWorkflowTools` |
| `ComponentTools` (read-only) | ✅ | ✅ |
| `TaskTools` (read-only) | ✅ | ✅ |
| `TaskDispatcherTools` (read-only) | ✅ | ✅ |
| `ScriptTools` (mutating) | ❌ | ✅ |
| `ClusterElementTools` (mutating) | ❌ | ✅ |

### Search union

`VectorToolSearcher` gains an immutable `Set<String> additionalSessionIds`
(constructor arg, default empty). `search` matches results whose `sessionId` is
**`request.sessionId` OR in `additionalSessionIds`**. This is the minimal forked-
searcher change and keeps the upstream advisor's `search` call untouched.

Each mode's searcher is configured with `additionalSessionIds = { CATALOG_SESSION_ID,
<mode global session> }`. So a single search call returns matches from the
per-conversation self-index (request session), the shared cluster-element Workspace
catalog, and the mode's global static catalog — all three populations the agent can use.
Because these persistent sessions are configured into the searcher (not passed per
request), each mode needs its own searcher instance.

**Shared store vs per-instance clear-tracking (implementation trap):** all
`VectorToolSearcher` instances share the same `toolSearchPgVectorStore`, so a row
indexed by any instance is visible (via `similaritySearch` + session filter) to every
instance. But `clearIndex` deletes only the doc ids tracked in *that instance's*
in-memory `sessionToolIds` map. Therefore the **feeder must own clear+index of the
global sessions through a single, stable searcher instance** (its injected
`vectorToolSearcher`); the per-mode searcher beans are read-only consumers that only
*query* (their `additionalSessionIds` filter) and never index/clear the global
sessions. This keeps clear-tracking consistent and avoids orphaning rows.

### Module boundary (cross-module seam)

`platform-ai-hub` (home of `ToolSearchAdvisorConfiguration`, the pgvector store, the
feeder, and `ClusterElementToolCallback`) depends on `mcp-tool-platform` but **not**
`mcp-tool-automation`. It can therefore see `ComponentTools` / `TaskTools` /
`TaskDispatcherTools` but not `ProjectTools` / `ProjectWorkflowTools` /
`ReadProjectTools` / `ReadProjectWorkflowTools` / `ScriptTools` / `ClusterElementTools`.
Adding `mcp-tool-automation` to `platform-ai-hub` would invert the platform→automation
layering, so instead:

- `platform-ai-hub` defines a small holder type
  `record AiHubGlobalToolCatalog(String sessionId, List<ToolCallback> toolCallbacks)`.
  (`Mode` already lives in `platform-ai-hub`, so per-mode wiring here is in-bounds.)
- `automation-ai-hub` (which already depends on both `platform-ai-hub` and the
  `mcp-tool-*` modules) provides two `AiHubGlobalToolCatalog` beans — one for ASK
  (read-only variants), one for BUILD (full set) — each built via
  `ToolCallbacks.from(...)`. The opaque `List<ToolCallback>` is wrapped in the holder so
  Spring does **not** auto-collect every `ToolCallback` bean in the context.
- `ToolSearchAdvisorConfiguration` consumes the `AiHubGlobalToolCatalog` beans
  (`ObjectProvider<AiHubGlobalToolCatalog>`, matched to mode by `sessionId`), builds the
  per-mode searcher + advisor, and feeds each catalog's tools at startup.

### Execution / resolution

Discovered global tools must resolve to executable callbacks. In `doBeforeCall`, a
discovered tool name not present in the per-request `cachedToolCallbacks` is added to
`selectedToolNames` and resolved at execution time by the advisor's
`ToolCallingManager` → `StaticToolCallbackResolver`. So we add each mode's global tool
callbacks (via `ToolCallbacks.from(beans...)`) to that mode's `StaticToolCallbackResolver`.
No advisor code change is needed for resolution.

### Startup embedding

New `ToolSearchCatalogFeeder.populateGlobalTools(String sessionId, List<ToolCallback> toolCallbacks)`:
- Extract `(name, summary)` from each `ToolCallback.getToolDefinition()` (name +
  description).
- Reuse the existing SHA-256 hash + `ai_hub_tool_search_catalog_meta` skip keyed on
  `sessionId`, and the `indexCatalog`-style clear-then-index loop (generalized to take
  `(name, summary)` pairs rather than `ClusterElementDefinition`).
- Called once per mode at `ApplicationReadyEvent`, in addition to the existing
  `populate()` for cluster elements.

## Components to change

1. **`VectorToolSearcher`** (`spring-ai-tool-search-tool/tool-searcher-vectorstore`,
   ByteChef fork): add `Set<String> additionalSessionIds` constructor param; widen the
   session filter in `search`. Marked as a ByteChef fork edit (consistent with existing
   M7 fork notes).

2. **`ToolSearchCatalogFeeder`** (`platform-ai-hub`): add `populateGlobalTools(sessionId, toolCallbacks)`;
   refactor the shared index/hash routine to accept generic `(name, summary)` entries so
   both cluster-element and global-tool paths reuse it.

3. **`ToolSearchAdvisorConfiguration`** (`platform-ai-hub`): split the single advisor /
   searcher into per-mode beans:
   - `aiHubAskToolSearchVectorToolSearcher` (additional sessions `{CATALOG_SESSION_ID,
     :global:ask}`), `aiHubBuildToolSearchVectorToolSearcher` (additional sessions
     `{CATALOG_SESSION_ID, :global:build}`) — both over the same `toolSearchPgVectorStore`.
     The shared `CATALOG_SESSION_ID` is what activates cluster-element discovery.
   - `aiHubAskToolSearchToolCallAdvisor` / `aiHubBuildToolSearchToolCallAdvisor`, each
     with its searcher and a `StaticToolCallbackResolver` containing the cluster-element
     callbacks **plus** the mode's global tool callbacks.
   - Inject the global tool beans; build mode-specific `List<ToolCallback>` via
     `ToolCallbacks.from(...)`.
   - In `populateCatalogOnAppReady`, keep `feeder.populate()` (cluster elements) and add
     `feeder.populateGlobalTools(...)` for both mode sessions. The feeder owns indexing of
     all persistent sessions (see clear-tracking trap above); the per-mode searcher beans
     only query.

4. **`AiHubConfiguration`** (`automation-ai-hub`): **remove** the static
   `.toolCallbacks(...)` registration of the global tools added in A (so they are not
   self-indexed per turn). Inject the per-mode `ToolSearchToolCallAdvisor` by qualifier
   into the matching agent. Keep the prompt disambiguation notes from A.

## Backward compatibility & risk

- **Cluster-element discovery becomes live**: today the cluster-element catalog is
  pre-embedded but never searched, so component actions are effectively undiscoverable via
  AI Hub tool search. After this change they *are* discoverable. This is the intended
  behavior change (piece C) — but it means the ASK and BUILD agents will start surfacing
  component-action tools in search results where they previously surfaced none. Worth a
  focused review of search-result quality (top-K noise) once live.
- **Per-task delivery and existing built-in static tools are untouched**: per-task tools
  still arrive via `additionalToolCallbacks`; built-ins still self-index per turn. The new
  persistent sessions are additive.
- **Bean split risk**: ASK and BUILD now have distinct advisor/searcher beans. The
  cluster-element callback map is built once and shared into both resolvers; only the
  global-tool entries differ. Must verify no other consumer depends on a single
  `toolSearchToolCallAdvisor` bean (today only the two agents inject it).
- **Tool-name collisions within a mode**: the BUILD flat set is the same combination the
  management MCP server already registers together (known-good, no internal collisions);
  ASK uses the read-only variants. No collisions with existing AI Hub tool names were
  found (only an incidental comment match on `searchComponents`).
- **Fork maintenance**: the searcher edit is small and isolated; flagged with a fork
  note so future upstream merges are obvious.
- **Stale pgvector rows**: same benign-staleness property the feeder already documents —
  the session-id metadata filter keeps rows from prior JVMs from matching; a future
  cleanup job can prune.

## Testing

- Unit: `populateGlobalTools` indexes the expected `(name, summary)` pairs and respects
  the hash-skip; `VectorToolSearcher.search` returns results across `request.sessionId`
  and every id in `additionalSessionIds` (including `CATALOG_SESSION_ID`).
- Unit: per-mode resolver contains the expected tool names (ASK excludes mutating tools;
  BUILD includes them) and there are no duplicate tool names within a mode.
- Integration: with AI Hub enabled, a BUILD turn can discover + execute `listProjects`
  via tool search; an ASK turn can discover the read-only variant but not `createProject`.
- Integration: a cluster-element component action (e.g. a connected component's action)
  is now discoverable via `toolSearchTool` in both modes — i.e. the `CATALOG_SESSION_ID`
  catalog is actually queried (piece C).
- Performance: confirm the global static tools and cluster-element catalog are **not**
  re-embedded per turn — only the per-conversation self-index runs each turn (assert
  `vectorStore.add` is not called for the persistent-session tools on a second turn).

## Open questions

- Should the per-mode searcher be two beans, or one searcher whose `search` takes the
  extra session set per call (would require the forked advisor to pass it through)?
  Current choice: two searcher beans (keeps the advisor untouched).
- **Search-result quality once the cluster-element catalog is live**: with thousands of
  component actions now in scope, does top-K need tuning (similarity threshold,
  per-category filtering) to avoid noisy picks? The feeder docstring already anticipates
  this (the per-task subset feature was the v2 answer); we may want a follow-up to wire
  per-task narrowing into search. Out of scope here but flagged.
- Do we want a metric/log line confirming global-catalog hash-skip on cold start (mirrors
  the existing `populate()` log)?

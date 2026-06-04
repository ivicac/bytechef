# AI Hub persistent tool-search catalog for global static tools

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

This folds together two pieces of work:

- **A — capability:** give the AI Hub agents direct access to project / workflow /
  component / task tools (closing the gap where AI Hub could only reach
  `listProjects` indirectly through the `workflow_editor_agent` subagent).
- **B — performance/infra:** embed those tools once at startup into a persistent
  search session rather than relying on the advisor's per-turn self-indexing.

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

- AI Hub ASK and BUILD agents can discover and execute the full automation/platform
  tool set via tool search.
- The global static tools embed **once** at startup (hash-skipped), not per turn.
- Preserve the ASK = read-only / BUILD = read-write boundary.
- Keep the existing `workflow_editor_agent` (and other subagent) delegation intact —
  these tools are added *alongside* it.
- Minimal, clearly-marked changes to the forked `spring-ai-tool-search-tool` modules.

## Non-goals

- **Not** activating the dormant cluster-element workspace catalog or per-task feeder
  sessions for search. Their current behavior (cluster-element executable callbacks in
  the advisor's resolver; per-task tools delivered via `additionalToolCallbacks` and
  self-indexed per turn) is unchanged. (Chosen scope: "dedicated global session only.")
- **Not** changing how per-task / personal-agent tool subsets are discovered.
- **Not** reducing the per-turn embedding cost of the *existing* built-in AI Hub static
  tools (open-tab, list-data-tables, etc.). Only the new global tool beans move to the
  persistent catalog. (A future change could migrate the rest.)

## Why per-mode separation is required (not optional)

ASK must expose read-only variants (`ReadProjectTools`) and BUILD the full mutating
set (`ProjectTools`). But `ReadProjectTools.listProjects` and
`ProjectTools.listProjects` share the **same tool name** (`listProjects`). A single
shared catalog/resolver holding both would collide on tool name. Today ASK and BUILD
share one `toolSearchToolCallAdvisor` bean (both agents inject the same provider).

Therefore the persistent global catalog must be **per mode**: a separate global
session + resolver entries for ASK and for BUILD. This drives the bean split below.

## Target design

### New persistent sessions

- `ai_hub_tool_catalog:global:ask` — ASK-mode global tools (read-only variants +
  read-only catalogs).
- `ai_hub_tool_catalog:global:build` — BUILD-mode global tools (full set).

Distinct from the existing `CATALOG_SESSION_ID` (cluster elements) and `:task:<id>`
sessions, which remain untouched.

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

Because the global session is configured into the searcher (not passed per request),
each mode needs its own searcher instance carrying the right global session.

**Shared store vs per-instance clear-tracking (implementation trap):** all
`VectorToolSearcher` instances share the same `toolSearchPgVectorStore`, so a row
indexed by any instance is visible (via `similaritySearch` + session filter) to every
instance. But `clearIndex` deletes only the doc ids tracked in *that instance's*
in-memory `sessionToolIds` map. Therefore the **feeder must own clear+index of the
global sessions through a single, stable searcher instance** (its injected
`vectorToolSearcher`); the per-mode searcher beans are read-only consumers that only
*query* (their `additionalSessionIds` filter) and never index/clear the global
sessions. This keeps clear-tracking consistent and avoids orphaning rows.

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
   - `aiHubAskToolSearchVectorToolSearcher` (additional session `:global:ask`),
     `aiHubBuildToolSearchVectorToolSearcher` (additional session `:global:build`) —
     both over the same `toolSearchPgVectorStore`.
   - `aiHubAskToolSearchToolCallAdvisor` / `aiHubBuildToolSearchToolCallAdvisor`, each
     with its searcher and a `StaticToolCallbackResolver` containing the cluster-element
     callbacks **plus** the mode's global tool callbacks.
   - Inject the global tool beans; build mode-specific `List<ToolCallback>` via
     `ToolCallbacks.from(...)`.
   - In `populateCatalogOnAppReady`, also call `feeder.populateGlobalTools(...)` for both
     mode sessions.

4. **`AiHubConfiguration`** (`automation-ai-hub`): **remove** the static
   `.toolCallbacks(...)` registration of the global tools added in A (so they are not
   self-indexed per turn). Inject the per-mode `ToolSearchToolCallAdvisor` by qualifier
   into the matching agent. Keep the prompt disambiguation notes from A.

## Backward compatibility & risk

- **No change to existing tool behavior**: cluster-element resolver entries, per-task
  delivery, and the existing built-in AI Hub static tools are untouched. The new global
  sessions are purely additive to search.
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
  the hash-skip; `VectorToolSearcher.search` returns results from both
  `request.sessionId` and `additionalSessionIds`.
- Unit: per-mode resolver contains the expected tool names (ASK excludes mutating tools;
  BUILD includes them) and there are no duplicate tool names within a mode.
- Integration: with AI Hub enabled, a BUILD turn can discover + execute `listProjects`
  via tool search; an ASK turn can discover the read-only variant but not `createProject`.
- Regression: existing cluster-element / per-task discovery behavior unchanged.

## Open questions

- Should the per-mode searcher be two beans, or one searcher whose `search` takes the
  extra session set per call (would require the forked advisor to pass it through)?
  Current choice: two searcher beans (keeps the advisor untouched).
- Do we want a metric/log line confirming global-catalog hash-skip on cold start (mirrors
  the existing `populate()` log)?

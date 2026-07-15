# Serve the AI Hub tool-search catalog from the build-time component index

**Date:** 2026-07-15
**Status:** Design
**Branch:** 0_732

## Problem

The build-time component index (`META-INF/bytechef/component-index.json`) exists so the platform
does not load every `ComponentHandler` at startup: the components-list view is served from
lightweight index stubs, and a single component loads on demand via its recorded `providerClassName`.

The AI Hub tool-search path defeats this. Two cross-component aggregates call the full-catalog
`getComponentDefinitions()` (via `ClusterElementDefinitionService.getClusterElementDefinitions(TOOLS)`),
forcing every component to load:

1. **Search population** — `ToolSearchCatalogFeeder.populate()` enumerates every tool-typed cluster
   element to build the pgvector embedding corpus.
2. **Dispatch callbacks** — `ToolSearchAdvisorConfiguration.buildClusterElementToolCallbacks()`
   builds one executable `ClusterElementToolCallback` per tool, generating each tool's input JSON
   schema from its property tree up front.

A third caller, the GraphQL "add tools" picker (`AiHubTaskToolGraphQlController`, two call sites),
does the same full-load enumeration purely to list tool names/descriptions.

Earlier work (commits `7cbcb9adb86`, `6ad49707742`) deferred this load off startup to the first AI
Hub chat turn, but the first turn still loads the **entire** catalog. The goal now is to remove the
full-catalog load entirely from the tool-search path: **list/populate operations load no components;
a component loads only when its specific tool is surfaced to the model or executed** — the same
"only when an individual action / trigger / property is needed" rule that per-component detail
already follows.

## Goals

- `ToolSearchCatalogFeeder.populate()` builds the search corpus from the index — zero component
  loads.
- Tool-search dispatch callbacks are built from the index by tool name; each tool's input schema is
  generated lazily, loading only that one component the first time the tool is surfaced.
- The GraphQL tool picker's list is served from the index (freed by the same reusable enumeration).
- The enumeration is a **reusable** `ClusterElementDefinitionService` method backed by the index, so
  the follow-up aggregates (cluster-element list, connections list) can reuse the pattern.

## Non-goals

- Connections list, unified API, and other `getComponentDefinitions()` aggregates. They follow the
  same recipe (index summaries + per-item lazy detail) but are out of scope for this spec. Unified
  API aggregates full action schemas across all components and may not be convertible without the
  rejected "bake schemas into the index" tradeoff; it is explicitly deferred.
- Baking cluster-element input schemas into the index. Rejected: many ByteChef properties are
  dynamic (options functions, connection/input-dependent properties), so a build-time schema
  snapshot would be incomplete or stale. Schemas are generated on demand from the live definition.
- `populateForTask` / per-task tool subsets — already resolve one component per bound tool, already
  lazy, unchanged.

## Design

### §1 — Reusable index-served cluster-element enumeration

The index `Entry` already carries `List<ClusterElementSummary>` (name, title, description, and the
cluster-element type: typeName/typeKey/typeLabel/typeMultipleElements/typeRequired). Nothing new
needs to be stored.

Convert the type-only `ClusterElementDefinitionService.getClusterElementDefinitions(ClusterElementType)`
to be **index-served**:

- Add a package-visible query on `ComponentDefinitionRegistry` (e.g.
  `getClusterElementDefinitionStubsFromIndex(ClusterElementType)`) that reads the `ComponentIndex`
  it already holds, iterates entries, filters `clusterElements` by the requested type, and returns
  **stub** `ClusterElementDefinition`s — component name/version, element name, title, description,
  type — with **no property tree and no handler load**. Reuse the existing
  `ComponentIndex.toStubClusterElementDefinition` projection.
- `ClusterElementDefinitionServiceImpl.getClusterElementDefinitions(type)` returns these stubs when
  the index is present, and falls back to the current full path (`getComponentDefinitions()` filter)
  when it is absent — matching the registry's established "index authoritative when present, full
  load otherwise" contract for EE apps that ship without a generated index.

Caller audit — after §3 no caller of the type-only enumeration needs property trees:
`ToolSearchCatalogFeeder.populate` (§2), `buildClusterElementToolCallbacks` (§3), and
`AiHubTaskToolGraphQlController` (×2, listing only). The internal
`ClusterElementDefinitionServiceImpl:559` fallback path is re-checked to ensure it does not rely on
stub properties.

This single method is the reusable seed the later aggregates copy.

### §2 — Search population from the index

`ToolSearchCatalogFeeder.populate()` (and the global-tools path where it enumerates cluster
elements) sources its tool list from §1. `buildSummary` uses only title + description — both present
on the stub — so **populate loads no components**. The content hash, embedding-skip, and pgvector
indexing are unchanged: they operate on `toolName` + `summary` text, which are identical whether
sourced from a stub or a fully-loaded definition (both are static, build-captured text).

`populateForTask` is unchanged — it already resolves one component per bound tool.

### §3 — Lazy per-tool dispatch callbacks

`buildClusterElementToolCallbacks` builds one `ClusterElementToolCallback` per tool **name** from §1
(name + description only — no schema, no load).

`ClusterElementToolCallback.getToolDefinition()` currently returns a pre-supplied `inputSchema`
field. Make the schema **lazy and memoized**: on first `getToolDefinition()` access the callback
resolves the real cluster element via
`clusterElementDefinitionService.getClusterElementDefinition(componentName, componentVersion,
clusterElementName)` (loads exactly that one component) and generates the schema with
`JsonSchemaGeneratorUtils.generateInputSchema(...)`. Subsequent accesses return the cached schema.

Building the name→callback map therefore touches only tool names; a schema materializes only when a
tool is actually surfaced to the model (after a `searchTool` hit). `executeTool` already resolves one
component on invocation, so execution is unchanged.

A `ClusterElementToolCallback` is shared (registered once with the resolver, reused across turns and
threads), so the lazy schema must be memoized **thread-safely** — a `MemoizationUtils.memoize`
supplier or equivalent double-checked hold, resolved at most once per callback under concurrent
`getToolDefinition()` access.

The existing malformed-tool handling (log-and-skip so one bad tool does not poison the map) is
preserved — moved to the lazy schema-generation point.

### Data flow after the change

```
populate()            -> ComponentIndex stubs (title/description)            -> pgvector   [0 loads]
searchTool(query)     -> pgvector vector search -> tool names + summaries                 [0 loads]
surface discovered T  -> ClusterElementToolCallback[T].getToolDefinition()
                         -> lazy: load component(T), generate schema, cache               [1 load: T]
execute T             -> clusterElementDefinitionService.executeTool(...)                 [1 load: T]
GraphQL tool picker   -> ComponentIndex stubs (name/description)                          [0 loads]
```

## Tradeoff: validation timing

The runtime full-load path (`loadComponentDefinitionsMap`) validates every component's
property/output trees at load. Removing the full-catalog load from the tool-search path means that
eager validation no longer runs for components the tool-search path would otherwise have loaded.

Validation is **not lost**: `ComponentDefinitionRegistry.loadComponentDefinitionsByName` runs
`validate(...)` on each component when it is loaded on demand, so a component's trees are still
validated the first time it is used. The build-time `ComponentIndexGenerator` loads every
component's `getDefinition()`, so a component that cannot even produce a definition breaks the build.

What shifts is the **failure mode**: a malformed property tree in a component that no one touches
surfaces at first use rather than at startup. Accepted: the build already fails on a component that
cannot produce a definition, so the boot-time smoke-test signal is only partially weakened (deep
property-tree validation defers to first use).

## Testing

- **§1 enumeration** — unit-test `getClusterElementDefinitions(type)` against a fake
  `ComponentIndex`: returns stubs (component/name/title/description/type present, properties empty)
  with no handler load; falls back to the full path when no index is present.
- **§2 population** — assert `populate()` indexes each tool-typed element's summary from stub fields
  and triggers no full-catalog load. Existing feeder tests (service mocked) stay green.
- **§3 lazy dispatch** — mirror the deferral tests already in the tree: building the callback map
  calls only `getToolDefinition().name()` (no load); the first `inputSchema()` access resolves the
  component once (verify `getClusterElementDefinition` called once), the second is cached; a
  malformed single tool logs-and-skips without poisoning the map.
- **Regression + observation** — full `ai-hub-service` suite stays green; with `ai.hub.enabled=true`
  and a copilot embedding key, the first AI Hub turn no longer logs `Loaded component 'X' on demand`
  for the whole catalog — only for tools actually surfaced.

## Affected files

- `platform-component-service`: `ComponentDefinitionRegistry` (new index-stub cluster-element
  query), `ClusterElementDefinitionServiceImpl` (index-served enumeration + fallback),
  `ComponentIndex` (reuse `toStubClusterElementDefinition`).
- `ai-hub-service`: `ToolSearchCatalogFeeder` (populate from enumeration),
  `ToolSearchAdvisorConfiguration.buildClusterElementToolCallbacks` (build by name),
  `ClusterElementToolCallback` (lazy memoized input schema).
- `ai-hub-graphql`: `AiHubTaskToolGraphQlController` (freed by §1; no change needed beyond inheriting
  the stub-backed enumeration).

## Follow-ups (separate specs)

- Cluster-element list aggregate — reuses §1 directly (same facet).
- Connections list aggregate — same recipe with a `connections()` index query (index already has
  `ConnectionSummary`).
- Unified API — evaluate separately; likely needs full definitions.

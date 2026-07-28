# Embedded Workflow Builder Fast Boot (iframe retained)

**Date:** 2026-07-28
**Status:** Approved
**Scope:** client, sdks/frontend/embedded, security-config (SpaWebFilter)

## Problem

The embedded SDK's `EmbeddedWorkflowBuilder` renders the builder in an iframe pointing at
`{baseUrl}/embedded/workflow-builder/{workflowUuid}`. Customers report slow first paint.

Measurement (2026-07-28) shows the cost is **not** the iframe itself but the boot path and
bundling behind it:

- `index.html → main.tsx` statically imports the **main app router graph** (~96 files) and only
  branches to `embeddedWorkflowBuilderRoutes` at runtime, after downloading a shell the embedded
  app never uses. It also `await`s `getApplicationInfo()` before first render and mounts
  `ReactQueryDevtools`.
- The editor's initial chunk statically imports **monaco-editor** (73 MB unpacked source,
  4 import sites), **@assistant-ui/react** (5.8 MB, 39 import sites) and **posthog-js** (40 MB,
  3 import sites) — none needed for first meaningful paint. `@xyflow/react` is the canvas and
  legitimately belongs to first paint.

An alternative — extracting the editor (760 files / ~138k lines / 55 external deps) into a shared
package the SDK renders directly — was considered and **rejected for now**: it does not reduce the
editor's own weight, and the drivers that would force it (SSR, host routing participation) are not
current requirements. See "Rejected alternative" below.

## Design

### 1. Dedicated entry point

- New `client/workflow-builder.html` + `client/src/workflow-builder.tsx`, added as a third rollup
  input (`workflowBuilder`) beside `main` and `connect` in `vite.config.mts` (the `connect.html`
  precedent).
- The entry mounts `EmbeddedWorkflowBuilderApp` directly with only the providers the builder
  needs: I18n, ThemeProvider, QueryClientProvider, TooltipProvider, Toaster. Explicitly absent:
  `src/routes.tsx`, `ReactQueryDevtools`, commandbar, userGuiding, the account fetch and `/login`
  redirect.
- `getApplicationInfo()` is **not awaited before render**; the builder renders its skeleton
  immediately and the query resolves in the background through react-query.
- In `main.tsx`, `getMainRouter` becomes a dynamic import so the legacy embedded branch stops
  downloading the main router graph, and the main app's own entry is unaffected.

### 2. New URL, server forward, backward compatibility

- New path: `/embedded/builder/{workflowUuid}` (fast path).
- `SpaWebFilter` forwards `/embedded/builder/**` to `/workflow-builder.html`. All other SPA paths
  keep forwarding to `/index.html`.
- The legacy `/embedded/workflow-builder/**` URL keeps working **permanently** through
  `index.html` and the runtime branch in `main.tsx` — old SDK versions lose nothing; they simply
  do not gain the fast path.
- SDK change (`EmbeddedWorkflowBuilder.tsx`): iframe `src` moves to the new path; optionally a
  `<link rel="preload">` of the entry chunk. The `EMBED_READY`/`EMBED_INIT` postMessage protocol
  is unchanged. SDK minor version bump.

### 3. Code splitting

| Dependency | Treatment |
|---|---|
| monaco-editor | `MonacoEditorWrapper` behind `React.lazy`, loaded when the code-editor sheet opens; `MonacoTypes` becomes type-only. Suspense fallback in the sheet. |
| @assistant-ui/react | The copilot panel component tree lazy-loads on first panel open. `useWorkflowTestChatStore` gets type-only/dynamic treatment. |
| posthog-js | Dynamic import on idle in the main app; the embedded entry omits it entirely. |
| @xyflow/react | Stays static — the canvas is first paint. |
| tiptap / ag-ui / JsonSchemaBuilder | Out of scope this pass (property panel is arguably first meaningful paint; diminishing returns). |

### 4. Verification

- Build assertion: the `workflowBuilder` entry chunk graph must not contain monaco, assistant-ui
  or posthog modules (grep the vite manifest in CI or a check script).
- `SpaWebFilterIntTest`: `/embedded/builder/x` → `/workflow-builder.html`;
  `/embedded/workflow-builder/x` → `/index.html` (regression-pinned).
- Playwright smoke via the SDK test-app: canvas visible on load; opening the code-editor sheet
  loads Monaco; opening the copilot loads the panel.
- Existing `npm run check` and builder tests unchanged.

### Error handling

- Stale-deploy chunk 404s are prevented by existing html revalidation cache headers
  (`StaticResourcesWebConfiguration`).
- A failed lazy chunk (Monaco, copilot) surfaces the sheet/panel error boundary, never a blank
  editor.

## Rejected alternative: full SDK inclusion (shared editor package)

Extracting the editor into a workspace package consumed by both `client/` and the SDK removes the
second React/shell and enables SSR and host routing — but none of those were the reported pain;
first paint was. The extraction is months of work: 55 peer deps pinned against customer apps,
Tailwind v4 CSS delivery, 214 generated middleware files need a home, and module-global singletons
(zustand stores, query client, fetch interceptor) must become injectable. If SSR or host-routing
requirements emerge, the staged path is: extract the package **behind the iframe first** (no
customer-visible change, proves the boundary), then flip the SDK to render it directly.

## Decisions log

- New URL (`/embedded/builder/*`) rather than re-pointing the old one — old URL never sunsets.
- Split scope: Monaco + assistant-ui + posthog; xyflow static; tiptap out of scope.
- Iframe stays; extraction explicitly deferred, with a staged path recorded.

# Client conventions

Loaded when Claude works with files under `client/`. Repo-wide rules stay in the root `CLAUDE.md`.

### Client ESLint sort-keys Rule

- Object keys must be in natural ascending (alphabetical) order in client code
- Applies to mock objects, hoisted state, test data, and component props
- ESLint `--fix` does NOT auto-fix sort-keys - must be fixed manually
- Example: `{content: 'x', id: 'y'}` not `{id: 'y', content: 'x'}`

### Client Interface Naming Convention

- Interface names must end with `I` or `Props` (enforced by `@typescript-eslint/naming-convention`)
- Example: `EnvironmentConfigI`, `BadgePropsType` — not `EnvironmentConfig`

### Client Import Destructure Sort Order

- Named imports must be sorted alphabetically within `{}` (enforced by `bytechef/sort-import-destructures`)
- `type` keyword imports sort by their name, not grouped separately
- Example: `import {BoxIcon, CheckIcon, type LucideIcon, WrenchIcon} from 'lucide-react'`

### Non-null Assertion on Optional Chain (Client)

- `@typescript-eslint/no-non-null-asserted-optional-chain` forbids `obj?.prop!`
- Instead, filter nulls first, then assert: `.filter((item) => item?.id != null).map((item) => { const id = item!.id!; ... })`

### Ref Name Suffix (Client)

- `useRef` variables must end with `Ref` suffix (enforced by `bytechef/ref-name-suffix`) — e.g., `fileInputRef`, `totalToUploadRef`

### Lucide Icon Imports (Client)

- Always import icons with the `Icon` suffix: `SearchIcon`, `DatabaseIcon`, `Loader2Icon`
- Not: `Search`, `Database`, `Loader2`

### List row rhythm (Client)

Every `*ListItem` row is two columns (title/tags on the left, version-or-switch + date on the right)
centred against each other with `items-center`. The two columns only line up if they are the **same
height**, so all 16 of them share one rhythm: first row `min-h-8`, an 8px gap (`gap-y-2` on the right
column, the left column's long-standing `mt-2`), second row `min-h-7`. Changing one column's gap or
dropping a `min-h` silently offsets that list's second row against its neighbour — the failure is
invisible in the file you edited and only shows on screen.

### CSS Class Merging (Client)

- Use `twMerge` from `tailwind-merge` for conditional class merging
- Do not use `cn()` utility

### React Patterns (Client)

- Use `fieldset` (with `border-0`) for semantic form grouping instead of `div`
- Use `useMemo` for computed values instead of IIFEs in JSX
- Prefer `||` over `??` for JSX fallbacks (e.g., `trigger || defaultTrigger`)

### Zustand subscriptions (Client)

- Never call a store hook without a selector (`useWorkflowDataStore()`) in the workflow editor tree. A bare
  call re-renders on every write to that store, and the editor's node details panel writes `sampleOutputs`
  from an effect whose input is recomputed on each render. In the embedded builder root that pairing was a
  "Maximum update depth exceeded" loop the moment a node was added. Select what you need through `useShallow`,
  or a single field with `(state) => state.field`.

### React Hook Ordering (Client)

- Order hooks in components/custom hooks: `useState` → `useRef` → custom store hooks → other custom hooks → derived values/`useMemo`/`useCallback` → `useEffect` → `return`
- All `useEffect` calls go last, immediately before the `return` statement
- Group multiple declarations of the same hook type consecutively (e.g., all `useRef` calls together, then `.current` assignments in a separate block)

### Client Error Handling

- `useFetchInterceptor.ts` provides centralized error handling for all fetch requests including GraphQL
- GraphQL errors are automatically parsed and displayed as toast notifications
- Individual `onError` handlers on mutations are therefore not necessary for basic error display
- Only add per-mutation `onError` if you need custom behavior beyond the global toast (e.g., resetting form state)

### Temporal Dead Zone (TDZ) with Synchronous Callbacks

- `const x = fn(callback)` — if `fn` calls `callback` synchronously, `x` is not yet assigned inside `callback`
- Accessing `x` inside such a callback throws `ReferenceError: Cannot access 'x' before initialization`
- Fix: defer access to `x` via `setTimeout` or store in a mutable ref before the call

### Sidebar navigation groups (Client)

Nav arrays live in `client/src/shared/navigation/navigationItems.ts` (App.tsx only feature-flag
filters them). `NavigationItemI` has an optional `group`; `AppSidebar` folds CONSECUTIVE items
sharing a `group` into one section at the position of their first item — non-adjacent items with the
same group form SEPARATE sections, so keep group members adjacent in the arrays. Flag filtering runs
before grouping, so a group renders with whatever members survive their flags.

Expanded, a labeled section renders as a `Collapsible` + `SidebarMenuSub` whose parent row carries an
icon from `NAVIGATION_GROUP_ICONS` — including a group of one, whose label is the only thing saying
where the item belongs. On the collapsed icon rail there is no label to show, so a one-item group
flattens to its own link and only multi-item groups become hover flyouts
(`AppSidebarCollapsedGroup`). Exactly one group is open at a time: the group holding the current
route opens itself, and a manual toggle wins until the route changes — `manuallyOpenedGroup` is
`string | null | undefined`, and collapsing that tri-state to `string | null` makes closing the group
you are standing in indistinguishable from having no preference, so it springs straight back open.

The active row is the LONGEST matching href, not merely a matching one: nav entries nest (Tool
Invocations lives under `/automation/executions`), and a plain prefix test lights up parent and child
at once.

Current groups: automation Deploy / Monitor / AI / Resources; embedded Build / Configure /
Monitor / Resources. Ungrouped rows: automation AI Hub (Chats in CE — the two are edition-exclusive),
Approval Tasks, Projects and Connections; embedded Connections alone.

The embedded Build group and the automation Projects row are Development-only.
`shared/navigation/developmentOnlyRoutes.ts` is the one list
pairing each authoring surface with the deployed one that replaces it (Projects — including the Agents and
Data Syncs tabs and the agents and data syncs inside a project — → Deployments, Integrations and
Automations → Integration Instances); `App.tsx` hides the rows
outside DEVELOPMENT and `useDevelopmentOnlyRouteGuard` redirects anyone standing on one. Both rules
must come from that list — a row you cannot reach and a page you cannot leave are the same bug from
opposite ends, and splitting them is how Projects once got the redirect while Agents got neither.
Switching environment changes a store and navigates nowhere, so a route loader alone cannot see it;
the loaders on `projects` and the embedded index exist only to spare the most common entry a flash.
No edition test is needed — CE has no environment selector and never leaves DEVELOPMENT.

### Graph dispatcher canvas (Client)

A `graph/v1` dispatcher renders as a free-form `graphFrame` box, not as a chain. `layoutGraphFrames`
is a PRE-PASS: it lays each frame's members out first, hands the outer engine one sized leaf node,
and re-appends members and their routes afterwards — so nothing the outer layout does can disturb
them, and a nested graph must be processed innermost-first. Member positions are FRAME-relative
(`metadata.ui.nodePosition`); `toFrameChildPosition`/`fromFrameChildPosition` in `graphFrameGeometry`
are the only sanctioned crossing to canvas coordinates — open-coding the header offset flings the
group. Routing is an explicit `parameters.transitions: [{from, to, condition?}]` list, never a
per-node `next`: declaration order within a `from` IS conditional priority, so a transition's index
is its identity. `graphTransition`/`graphStart` edges are free-form routes, not chain links — a
cyclic pair would corrupt dagre's and ELK's ranking, so both engines strip them, and chain walkers
(`collectChainSuccessorNodes`) must skip them. `onConnect` is scoped by handle suffix
(`resolveGraphConnection`): only graph transition handles connect, and only within one graph.

### Vitest mock factory hoisting (Client)

`vi.mock(...)` calls hoist to the top of the file, so module-scope `const` declarations are NOT yet
initialised when the factory runs. Referencing `setStateMock` (or any module-scope ref) inside a
`vi.mock` factory crashes with `Cannot access X before initialization`.

Use `vi.hoisted(() => ({...}))` to declare the refs alongside the mocks. Example:

```ts
const {navigateMock, setStateMock} = vi.hoisted(() => ({
    navigateMock: vi.fn(),
    setStateMock: vi.fn(),
}));

vi.mock('@/shared/components/copilot/stores/useCopilotStore', () => ({
    useCopilotStore: {setState: setStateMock},
}));
```

This pattern shows up in `Agents.test.tsx` and any test that mocks Zustand
stores or router hooks via factory-injected mocks.

### Zustand Store Testing

- Reset store state in `beforeEach` via `store.setState({...initial...})` — avoids cross-test leakage
- Access store imperatively via `store.getState()` for assertions (no hook needed)
- Export stores (e.g., `export const featureFlagsStore`) to enable direct state manipulation in tests
- Use `renderHook` from `@testing-library/react` for hooks that wrap stores
- **Never flush async store updates with a fixed sleep** (`await act(async () => { await new Promise(r => setTimeout(r, 10)); })`).
  A fixed delay races an async chain whose latency is unbounded under parallel vitest workers: it passes in
  isolation and fails intermittently in full runs, on a different test each time. Wait for the condition instead:
  `await waitFor(() => { expect(store.getState().someFlag).toBe(expected); }, {interval: 5, timeout: 5000})`.
  The timeout is a ceiling paid only on genuine failure, not a delay every test waits out.
- A test that kicks off an async store update **must wait for it to settle before it ends**, even when the
  assertion only covers intermediate state — otherwise its pending `setTimeout` callbacks fire during a later
  test and mutate the shared store there (`beforeEach` reset cannot help; the timer outlives the reset).

### PostHog Mock

- Global mock in `.vitest/setup.ts` — `onFeatureFlags: vi.fn()`, `isFeatureEnabled: vi.fn().mockReturnValue(false)`
- `onFeatureFlags` returns `() => void` (unsubscribe); mock overrides must return a function: `return () => {}`
- `import('posthog-js')` dynamic imports resolve to the same mock; multiple synchronous calls share one Promise
- `useFeatureFlagsStore` resolves a flag through a four-hop chain: `import('posthog-js')` → promise microtask →
  `onFeatureFlags` callback → `setTimeout(…, 0)`. Tests must wait on the resulting store state (or on the
  `onFeatureFlags` subscription), never on elapsed time — see the Zustand note above.
- Test files are isolated (vitest `forks` pool, `isolate: true`), so `featureFlagsStore` and the PostHog mock
  **cannot** leak between files; contamination is always within a single file.

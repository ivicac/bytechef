# Cluster Elements Inline Box Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a cluster root's elements inline on the main workflow canvas, inside an auto-sizing box that the overall layout reflows around, as a toggleable alternative to today's full-screen dialog editor.

**Architecture:** The cluster root node is *already* the React Flow parent of its elements (`parentId: clusterRootId`), so box mode sizes and paints the parent that exists rather than introducing a frame node — this is the one deliberate divergence from `graph/v1`, which needs a separate `graphFrame` node because its members have no parent link. A `layoutClusterFrames` pre-pass builds the element nodes with the dialog's existing factories, places them with the dialog's existing placer, sizes the root from their bounding box, strips them from the arrays handed to the layout engine, and re-appends them afterwards. Clicking any node in the box opens the ordinary `WorkflowNodeDetailsPanel`; no dialog is involved.

**Tech Stack:** React 19.2, TypeScript 6.0, `@xyflow/react` (React Flow), Zustand (with `persist` middleware), TailwindCSS 4.3, Vitest 4, `@testing-library/react`.

**Spec:** `docs/superpowers/specs/2026-08-31-cluster-elements-inline-box-design.md`

## Global Constraints

- **Client only.** No server, GraphQL, or Liquibase changes. `clusterElements` and `metadata.ui.nodePosition` are unchanged on the wire.
- **Editor only.** The execution view and `mcp-apps/workflow-editor` keep today's single-node rendering. Do not modify anything under `mcp-apps/`.
- **Feature flag `ff-5470`.** Every user-reachable part of box mode is behind it. Create the flag in PostHog before manual testing; a flag referenced once is still live, never delete an `ff-` check as dead code. If a different number is chosen, it appears in exactly one place (Task 1, `useClusterElementsViewModeStore.ts`).
- **Dialog mode must not change.** The frozen regression bar is **three** files: `ClusterElementsCanvasDialog.test.tsx`, `clusterElementsUtils.test.ts` and `useClusterElementStep.test.ts`. They pass untouched at every commit; if a task requires editing one of them, stop and report — that is a design violation, not a test to fix.
- **`useClusterElementsLayout.test.ts` is NOT frozen** (Ruling B, amended during Task 3). It seeds a store field BY NAME, so freezing it through a field rename forced dead production code and a real behaviour change into a refactor billed as behaviour-preserving. It may be edited when a rename or a fixture's structural fidelity requires it — the bar's purpose is "the dialog behaves identically", not "these files are sacred". Its assertions still stand as the dialog-mode contract: change a fixture only to make it describe production more accurately, never to make a failing assertion pass.
- **Naming.** Interfaces end in `I` or `Props`. Refs end in `Ref`. Icons import with the `Icon` suffix (`LockIcon`, not `Lock`). Object keys sort alphabetically (ESLint `sort-keys`; `--fix` does **not** fix these — sort by hand). Named imports sort alphabetically inside `{}`. No short or cryptic variable names, including arrow-function parameters.
- **Class merging** uses `twMerge` from `tailwind-merge`. Never `cn()`.
- **Hook order** in every component: `useState` → `useRef` → store hooks → other custom hooks → `useMemo`/`useCallback` → `useEffect` → `return`.
- **Commit messages:** `<ticket> client - <description>`; the ticket for this work is `0_732`.
- **Before every commit:** `cd client && npm run format`. Before the final commit of each task: `cd client && npm run check` — **set the tool timeout to 600000ms**, it is auto-backgrounded at 120s and a stale `node_modules` after a rebase looks exactly like a botched rebase (`npm install` first if you have just rebased).

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.ts` | The box-vs-dialog choice, persisted to localStorage |
| `client/src/pages/platform/workflow-editor/utils/clusterFrame/clusterFrameGeometry.ts` | Frame constants, size computation, content-origin conversion |
| `client/src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.ts` | The pre-pass: build, place, size, strip, re-append |
| `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.tsx` | Box chrome: border, header row, controls, chain handles |
| `client/src/pages/platform/cluster-element-editor/hooks/useClusterElementNodes.ts` | Shared node/edge/definition builder for both surfaces |

**Modified (principal):** `shared/types.ts` (the `clusterFrame` field), `utils/layoutUtils.tsx` (`getDagreNodeSize`), `utils/graph/graphMemberPlacement.ts` (`getMemberSize`), `hooks/useLayout.tsx` (wiring), `nodes/WorkflowNode.tsx` + `nodes/AiAgentNode.tsx` (shell), `WorkflowEditorLayout.tsx` (panel mounting), `components/WorkflowEditorToolbar.tsx` (the toggle), plus the untangling sites in Tasks 7–8.

---

### Task 1: View-mode store and toolbar toggle

Ships the switch with nothing behind it yet. Nothing reads the store until Task 6, so this is inert by construction.

**Files:**
- Create: `client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.ts`
- Create: `client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.test.ts`
- Modify: `client/src/pages/platform/workflow-editor/components/WorkflowEditorToolbar.tsx`

**Interfaces:**
- Consumes: nothing.
- Produces: `useClusterElementsViewModeStore` — a Zustand store with `{clusterElementsViewMode: ClusterElementsViewModeType; setClusterElementsViewMode: (mode: ClusterElementsViewModeType) => void}`, where `export type ClusterElementsViewModeType = 'box' | 'dialog'`. Default `'dialog'`. Tasks 6, 9, 11 and 12 read it.

- [ ] **Step 1: Write the failing test**

Create `client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.test.ts`:

```ts
import {beforeEach, describe, expect, it} from 'vitest';

import useClusterElementsViewModeStore from './useClusterElementsViewModeStore';

describe('useClusterElementsViewModeStore', () => {
    beforeEach(() => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
    });

    it('defaults to the dialog editor', () => {
        expect(useClusterElementsViewModeStore.getState().clusterElementsViewMode).toBe('dialog');
    });

    it('switches to box mode', () => {
        useClusterElementsViewModeStore.getState().setClusterElementsViewMode('box');

        expect(useClusterElementsViewModeStore.getState().clusterElementsViewMode).toBe('box');
    });

    it('persists under a stable storage key', () => {
        useClusterElementsViewModeStore.getState().setClusterElementsViewMode('box');

        expect(JSON.parse(window.localStorage.getItem('bytechef.cluster-elements-view-mode')!).state).toEqual({
            clusterElementsViewMode: 'box',
        });
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.test.ts
```

Expected: FAIL — `Failed to resolve import "./useClusterElementsViewModeStore"`.

- [ ] **Step 3: Write the store**

Create `client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.ts`:

```ts
import {create} from 'zustand';
import {devtools, persist} from 'zustand/middleware';

export type ClusterElementsViewModeType = 'box' | 'dialog';

/**
 * How cluster roots render on the main canvas. 'dialog' is today's behaviour and the default: the
 * root is one node and opening it raises the full-screen cluster editor. 'box' renders the root as
 * an auto-sizing container with its elements inline, and opens the ordinary node details panel.
 *
 * Per user rather than per workflow, and deliberately not written to the workflow definition — two
 * people may view the same workflow differently, and toggling must never mark it dirty.
 */
const useClusterElementsViewModeStore = create<ClusterElementsViewModeStateI>()(
    devtools(
        persist(
            (set) => ({
                clusterElementsViewMode: 'dialog' as ClusterElementsViewModeType,

                setClusterElementsViewMode: (clusterElementsViewMode: ClusterElementsViewModeType) =>
                    set({clusterElementsViewMode}),
            }),
            {
                name: 'bytechef.cluster-elements-view-mode',
            }
        )
    )
);

interface ClusterElementsViewModeStateI {
    clusterElementsViewMode: ClusterElementsViewModeType;
    setClusterElementsViewMode: (clusterElementsViewMode: ClusterElementsViewModeType) => void;
}

export default useClusterElementsViewModeStore;
```

Move the `interface` above the `create` call if the linter objects to use-before-define; the repo's other stores declare it first.

- [ ] **Step 4: Run test to verify it passes**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.test.ts
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Add the toolbar toggle**

In `WorkflowEditorToolbar.tsx`, add to the existing imports (keeping both import lists alphabetical):

```tsx
import useClusterElementsViewModeStore from '@/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore';
import useFeatureFlagsStore from '@/shared/stores/useFeatureFlagsStore';
import {BoxIcon, SquareDashedIcon} from 'lucide-react';
```

Inside the component, alongside the other store hooks:

```tsx
const {clusterElementsViewMode, setClusterElementsViewMode} = useClusterElementsViewModeStore();

const ff_5470 = useFeatureFlagsStore()('ff-5470');
```

With the other `useCallback`s:

```tsx
const handleToggleClusterElementsViewMode = useCallback(() => {
    setClusterElementsViewMode(clusterElementsViewMode === 'box' ? 'dialog' : 'box');
}, [clusterElementsViewMode, setClusterElementsViewMode]);
```

In the `Panel position="top-left"` block, directly after the closing `</Tooltip>` of the layout-engine button and inside the same `<Panel>`:

```tsx
{ff_5470 && (
    <Tooltip>
        <TooltipTrigger asChild>
            <Button
                aria-label={
                    clusterElementsViewMode === 'box'
                        ? 'Show cluster elements in the editor dialog'
                        : 'Show cluster elements inline'
                }
                className={twMerge(clusterElementsViewMode === 'box' && 'text-content-brand-primary')}
                icon={clusterElementsViewMode === 'box' ? <BoxIcon /> : <SquareDashedIcon />}
                onClick={handleToggleClusterElementsViewMode}
                size="icon"
                variant="outline"
            />
        </TooltipTrigger>

        <TooltipContent className="rounded-lg bg-surface-tooltip text-content-onsurface-primary" side="bottom">
            {clusterElementsViewMode === 'box'
                ? 'Show cluster elements in the editor dialog'
                : 'Show cluster elements inline'}
        </TooltipContent>
    </Tooltip>
)}
```

Wrap the two buttons in a `<div className="flex items-center gap-1">` if the panel does not already provide spacing.

- [ ] **Step 6: Verify the toolbar still renders**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/components/WorkflowEditorToolbar.test.tsx
```

Expected: PASS. The mocked feature flag returns `false` (`.vitest/setup.ts`), so the new button is absent and no existing assertion moves.

- [ ] **Step 7: Format and commit**

```bash
cd client && npm run format
```

```bash
git add client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.ts client/src/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore.test.ts client/src/pages/platform/workflow-editor/components/WorkflowEditorToolbar.tsx
git commit -m "0_732 client - Add the cluster elements view mode toggle"
```

---

### Task 2: `clusterFrame` size authority

Teaches every place that asks "how big is this node?" to respect a pre-pass-computed cluster box. Nothing writes `clusterFrame` yet, so every branch added here is unreachable — which is exactly why it is safe to land first.

**Files:**
- Modify: `client/src/shared/types.ts:231-232`
- Modify: `client/src/pages/platform/workflow-editor/utils/layoutUtils.tsx:205-212`
- Modify: `client/src/pages/platform/workflow-editor/utils/graph/graphMemberPlacement.ts:23-29`
- Test: `client/src/pages/platform/workflow-editor/utils/graph/graphMemberPlacement.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `NodeDataType.clusterFrame?: {clusterRootId: string; height: number; width: number}`. Tasks 4, 5 and 6 write and read it.

- [ ] **Step 1: Write the failing test**

Append to `client/src/pages/platform/workflow-editor/utils/graph/graphMemberPlacement.test.ts` (inside the existing top-level `describe`, or as a new `describe` at the end of the file):

```ts
describe('getMemberSize via collectGraphMemberSizes', () => {
    it('prefers a cluster root box over its measured card size', () => {
        const nodes: Node[] = [
            {
                data: {
                    clusterFrame: {clusterRootId: 'aiAgent_1', height: 320, width: 640},
                    graphData: {graphId: 'graph_1', index: 0},
                    workflowNodeName: 'aiAgent_1',
                },
                id: 'aiAgent_1',
                measured: {height: 80, width: 240},
                position: {x: 0, y: 0},
                type: 'workflow',
            },
        ];

        expect(collectGraphMemberSizes('graph_1', nodes)).toEqual([
            {height: 320, name: 'aiAgent_1', width: 640},
        ]);
    });

    it('falls back to the measured size when no pre-pass box is present', () => {
        const nodes: Node[] = [
            {
                data: {graphData: {graphId: 'graph_1', index: 0}, workflowNodeName: 'task_1'},
                id: 'task_1',
                measured: {height: 80, width: 240},
                position: {x: 0, y: 0},
                type: 'workflow',
            },
        ];

        expect(collectGraphMemberSizes('graph_1', nodes)).toEqual([{height: 80, name: 'task_1', width: 240}]);
    });
});
```

Add `collectGraphMemberSizes` to the file's existing import from `./graphMemberPlacement` and `Node` to its `@xyflow/react` import if absent, keeping both lists alphabetical. If `collectGraphMemberSizes` returns a different shape than `{height, name, width}`, read its current signature and match it — the assertion that matters is 320/640 versus 80/240.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/graph/graphMemberPlacement.test.ts
```

Expected: FAIL on the first case — received `{height: 80, width: 240}`, the measured card, because `getMemberSize` reads the DOM.

- [ ] **Step 3: Add the type field**

In `client/src/shared/types.ts`, immediately after the existing `graphFrame` field (line 231-232):

```ts
    /**
     * Present on a cluster ROOT node in box mode; carries the auto-computed size of the box its
     * elements are drawn inside. Unlike `graphFrame` this does not mark a separate container node —
     * the root is its elements' React Flow parent already, so the root itself is the box.
     */
    clusterFrame?: {clusterRootId: string; height: number; width: number};
```

- [ ] **Step 4: Add the `getDagreNodeSize` arm**

In `layoutUtils.tsx`, immediately after the existing `graphFrame` early return (which ends at line 211):

```ts
    // A cluster root in box mode is handed to the engine as a sized leaf for the same reason a graph
    // frame is: the pre-pass has already placed its elements and computed the box they need, and the
    // elements themselves are stripped from the arrays the engine sees.
    const clusterFrame = (node.data as NodeDataType)?.clusterFrame;

    if (clusterFrame) {
        return {height: clusterFrame.height, width: clusterFrame.width};
    }
```

`getElkNodeSize` (`elkLayoutUtils.ts:496`) delegates to `getDagreNodeSize` at line 512, so it inherits this. Read those lines to confirm the delegation is unconditional before assuming it; if ELK short-circuits on `node.type` first, add the same early return there too.

- [ ] **Step 5: Make `getMemberSize` prefer the pre-pass size**

In `graphMemberPlacement.ts`, replace the body of `getMemberSize` (lines 23-29):

```ts
function getMemberSize(node: Node): {height: number; width: number} {
    // A pre-pass box outranks the DOM. `measured` is what React Flow reports once the node is on
    // screen, which for a cluster root in box mode is the small card it painted before the box was
    // sized — measuring that would size the graph frame to the card and only correct itself on some
    // later layout. The DOM fallback is for nodes nobody has sized, not an override for ones that
    // have been.
    const nodeData = node.data as NodeDataType;
    const preComputedBox = nodeData.clusterFrame ?? nodeData.graphFrame;

    if (preComputedBox) {
        return {height: preComputedBox.height, width: preComputedBox.width};
    }

    return {
        height: node.measured?.height ?? node.height ?? GRAPH_MEMBER_NOMINAL_SIZE.height,
        width: node.measured?.width ?? node.width ?? GRAPH_MEMBER_NOMINAL_SIZE.width,
    };
}
```

Add `NodeDataType` to the file's import from `@/shared/types` if it is not already imported.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/graph/ src/pages/platform/workflow-editor/utils/layoutUtils.test.tsx src/pages/platform/workflow-editor/utils/elkLayoutUtils.test.ts
```

Expected: PASS. The `graphFrame` branch of `getMemberSize` is new behaviour for graph frames nested in graph frames — if an existing graph test now fails, that is a real regression: report it rather than adjusting the test.

- [ ] **Step 7: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

(Tool timeout 600000ms.)

```bash
git add client/src/shared/types.ts client/src/pages/platform/workflow-editor/utils/layoutUtils.tsx client/src/pages/platform/workflow-editor/utils/graph/graphMemberPlacement.ts client/src/pages/platform/workflow-editor/utils/graph/graphMemberPlacement.test.ts
git commit -m "0_732 client - Let a pre-pass cluster box outrank the measured node size"
```

---

### Task 3: Shared element-node builder and per-root definitions

Extracts the node/edge/definition assembly from the dialog's layout hook so the main canvas can call it, and widens the definition store from one root to many. Behaviour-preserving: the dialog is switched to the extracted hook in the same commit and its tests must pass unchanged.

**Files:**
- Create: `client/src/pages/platform/cluster-element-editor/hooks/useClusterElementNodes.ts`
- Create: `client/src/pages/platform/cluster-element-editor/hooks/useClusterElementNodes.test.tsx` (JSX in the query-client wrapper)
- Modify: `client/src/pages/platform/cluster-element-editor/hooks/useClusterElementsLayout.ts`
- Modify: `client/src/pages/platform/workflow-editor/stores/useWorkflowEditorStore.ts`
- Modify: `client/src/pages/platform/workflow-editor/nodes/WorkflowNode.tsx:690-710`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `useClusterElementNodes(clusterRootIds: string[]): {nodesByRootId: Record<string, Node[]>; edgesByRootId: Record<string, Edge[]>; definitionsReady: boolean}` — builds each root's element nodes and edges, and resolves the component definitions they need. `definitionsReady` is false until every nested root definition has resolved; callers must not lay out before it is true or the box visibly reflows when definitions land.
  - `useWorkflowEditorStore.clusterRootComponentDefinitions: Record<string, ComponentDefinition>` replacing the singular `mainClusterRootComponentDefinition`, with `setClusterRootComponentDefinition(clusterRootId, definition)`. Tasks 4 and 6 read it.

- [ ] **Step 1: Write the failing test**

Create `client/src/pages/platform/cluster-element-editor/hooks/useClusterElementNodes.test.tsx`:

```ts
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {renderHook, waitFor} from '@testing-library/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it} from 'vitest';

import useClusterElementNodes from './useClusterElementNodes';

const AI_AGENT_DEFINITION = {
    clusterElementTypes: [
        {label: 'Model', multipleElements: false, name: 'MODEL'},
        {label: 'Tools', multipleElements: true, name: 'TOOLS'},
    ],
    name: 'aiAgent',
    version: 1,
};

const WORKFLOW_DEFINITION = JSON.stringify({
    tasks: [
        {
            clusterElements: {
                model: {label: 'GPT', name: 'model_1', type: 'openai/v1/model'},
            },
            label: 'AI Agent',
            name: 'aiAgent_1',
            type: 'aiAgent/v1/chat',
        },
    ],
});

function wrapper({children}: {children: ReactNode}) {
    return <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>;
}

describe('useClusterElementNodes', () => {
    beforeEach(() => {
        useWorkflowDataStore.setState({workflow: {definition: WORKFLOW_DEFINITION, id: 'workflow_1'}});
        useWorkflowEditorStore.setState({
            clusterRootComponentDefinitions: {aiAgent_1: AI_AGENT_DEFINITION},
        });
    });

    it('builds one node per declared element type, plus the root card handles', async () => {
        const {result} = renderHook(() => useClusterElementNodes(['aiAgent_1']), {wrapper});

        await waitFor(() => {
            expect(result.current.definitionsReady).toBe(true);
        });

        const nodeIds = result.current.nodesByRootId.aiAgent_1.map((node) => node.id);

        // The filled MODEL slot renders its element; the empty multi-valued TOOLS slot renders a
        // placeholder. Both parent to the root.
        expect(nodeIds).toContain('model_1');
        expect(result.current.nodesByRootId.aiAgent_1.every((node) => node.parentId === 'aiAgent_1')).toBe(true);
        expect(result.current.nodesByRootId.aiAgent_1.some((node) => node.type === 'placeholder')).toBe(true);
    });

    it('returns an empty result for a root with no definition yet', () => {
        useWorkflowEditorStore.setState({clusterRootComponentDefinitions: {}});

        const {result} = renderHook(() => useClusterElementNodes(['aiAgent_1']), {wrapper});

        expect(result.current.nodesByRootId.aiAgent_1 ?? []).toEqual([]);
    });
});
```

Adjust `AI_AGENT_DEFINITION` to whatever `getFilteredClusterElementTypes` actually requires — read `clusterElementsUtils.ts` first and mirror the shape its own tests use.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/cluster-element-editor/hooks/useClusterElementNodes.test.tsx
```

Expected: FAIL — module not found.

- [ ] **Step 3: Widen the definitions store**

In `useWorkflowEditorStore.ts`, replace `mainClusterRootComponentDefinition` and its setter with:

```ts
    /**
     * Component definitions of the cluster roots currently rendered, keyed by root workflow node
     * name. A map rather than a single slot because box mode can show several roots at once; the
     * dialog only ever populates one key.
     */
    clusterRootComponentDefinitions: Record<string, ComponentDefinition>;
    setClusterRootComponentDefinition: (clusterRootId: string, definition: ComponentDefinition) => void;
```

with the implementation:

```ts
            clusterRootComponentDefinitions: {},

            setClusterRootComponentDefinition: (clusterRootId, definition) =>
                set((state) => ({
                    clusterRootComponentDefinitions: {
                        ...state.clusterRootComponentDefinitions,
                        [clusterRootId]: definition,
                    },
                })),
```

Then update every reader. `WorkflowNode.tsx:704` becomes:

```ts
            clusterRootComponentDefinition:
                nestedClusterRootDefinition ||
                clusterRootComponentDefinitions[(data.parentClusterRootId as string) ?? data.workflowNodeName],
```

Find the rest with:

```bash
grep -rn "mainClusterRootComponentDefinition" --include="*.ts*" client/src
```

Every hit must be re-pointed; leave none behind.

- [ ] **Step 4: Write the hook**

Create `useClusterElementNodes.ts`. Move — do not copy — these blocks out of `useClusterElementsLayout.ts`: the `getClusterRootQueryParameters` callback, the `getClusterRootDefinitionQuery` callback, the nested-definitions `useEffect`, and the `allNodes`/`taskEdges` `useMemo`. Generalise each from the single `rootClusterElementNodeData` to the `clusterRootIds` argument, resolving each root's task with the existing `getTask({tasks, workflowNodeName})`. Keep every existing comment — the one about collecting definitions for *every* element rather than only those carrying a `clusterElements` object records a real bug and must survive the move.

Signature:

```ts
export default function useClusterElementNodes(clusterRootIds: string[]): {
    definitionsReady: boolean;
    edgesByRootId: Record<string, Edge[]>;
    nodesByRootId: Record<string, Node[]>;
}
```

- [ ] **Step 5: Switch the dialog to the hook**

In `useClusterElementsLayout.ts`, replace the removed blocks with:

```ts
    const clusterRootIds = useMemo(
        () => (rootClusterElementNodeData?.workflowNodeName ? [rootClusterElementNodeData.workflowNodeName] : []),
        [rootClusterElementNodeData?.workflowNodeName]
    );

    const {definitionsReady, edgesByRootId, nodesByRootId} = useClusterElementNodes(clusterRootIds);

    const rootId = clusterRootIds[0];
    const allNodes = useMemo(() => (rootId ? (nodesByRootId[rootId] ?? []) : []), [nodesByRootId, rootId]);
    const taskEdges = useMemo(() => (rootId ? (edgesByRootId[rootId] ?? []) : []), [edgesByRootId, rootId]);
```

Replace the hook's existing "wait for nested definitions" guard —

```ts
        if (
            clusterRootQueryParameters.length > 0 &&
            Object.keys(nestedClusterRootsComponentDefinitions || {}).length === 0
        ) {
            return;
        }
```

— with `if (!definitionsReady) { return; }`, and add `definitionsReady` to that effect's dependency array.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/cluster-element-editor/ src/pages/platform/workflow-editor/components/ClusterElementsCanvasDialog.test.tsx
```

Expected: PASS, including `useClusterElementsLayout.test.ts` **unmodified**. If it fails, the extraction changed behaviour — fix the hook, not the test.

- [ ] **Step 7: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/cluster-element-editor client/src/pages/platform/workflow-editor/stores/useWorkflowEditorStore.ts client/src/pages/platform/workflow-editor/nodes/WorkflowNode.tsx
git commit -m "0_732 client - Extract the cluster element node builder and key definitions by root"
```

---

### Task 4: `layoutClusterFrames` geometry and pre-pass

The pure core: given canvas nodes and the per-root element nodes, place the elements, size each root, and partition the arrays. No wiring — Task 5 does that.

**Files:**
- Create: `client/src/pages/platform/workflow-editor/utils/clusterFrame/clusterFrameGeometry.ts`
- Create: `client/src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.ts`
- Create: `client/src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.test.ts`

**Interfaces:**
- Consumes: `NodeDataType.clusterFrame` (Task 2); `useClusterElementNodes`'s `nodesByRootId` / `edgesByRootId` shape (Task 3).
- Produces:
  - `CLUSTER_FRAME_HEADER_HEIGHT = 40`, `CLUSTER_FRAME_PADDING = 32`, `CLUSTER_FRAME_MIN_WIDTH = 420`, `CLUSTER_FRAME_MIN_HEIGHT = 220`.
  - `toClusterFrameChildPosition({x, y}): {x, y}` — content-origin → parent-relative, adding the header height. `fromClusterFrameChildPosition` is its inverse. These are the only sanctioned crossing between the two coordinate spaces; open-coding the header offset misplaces the whole box.
  - `computeClusterFrameSize(childBoxes: ClusterMemberBoxI[]): {height: number; width: number}`, with `export interface ClusterMemberBoxI {height: number; width: number; x: number; y: number}`.
  - `layoutClusterFrames(nodes: Node[], edges: Edge[], elementsByRootId: {edgesByRootId: Record<string, Edge[]>; nodesByRootId: Record<string, Node[]>}): LayoutClusterFramesResultI` where `export interface LayoutClusterFramesResultI {memberEdges: Edge[]; memberNodes: Node[]; outerEdges: Edge[]; outerNodes: Node[]}`. Task 5 consumes it.

- [ ] **Step 1: Write the failing test**

Create `layoutClusterFrames.test.ts`:

```ts
import {Node} from '@xyflow/react';
import {describe, expect, it} from 'vitest';

import {
    CLUSTER_FRAME_HEADER_HEIGHT,
    CLUSTER_FRAME_MIN_HEIGHT,
    CLUSTER_FRAME_MIN_WIDTH,
    CLUSTER_FRAME_PADDING,
} from './clusterFrameGeometry';
import {layoutClusterFrames} from './layoutClusterFrames';

const ROOT_ID = 'aiAgent_1';

function buildRootNode(): Node {
    return {
        data: {clusterRoot: true, workflowNodeName: ROOT_ID},
        id: ROOT_ID,
        position: {x: 0, y: 0},
        type: 'workflow',
    };
}

function buildElementNode(id: string, position: {x: number; y: number}): Node {
    return {
        data: {clusterElementType: 'model', parentClusterRootId: ROOT_ID, workflowNodeName: id},
        id,
        measured: {height: 60, width: 200},
        parentId: ROOT_ID,
        position,
        type: 'workflow',
    };
}

describe('layoutClusterFrames', () => {
    it('sizes the root from its elements and moves them out of the outer array', () => {
        const elementNode = buildElementNode('model_1', {x: 400, y: 300});

        const result = layoutClusterFrames([buildRootNode()], [], {
            edgesByRootId: {[ROOT_ID]: []},
            nodesByRootId: {[ROOT_ID]: [elementNode]},
        });

        const rootNode = result.outerNodes.find((node) => node.id === ROOT_ID)!;

        expect(rootNode.data.clusterFrame).toEqual({
            clusterRootId: ROOT_ID,
            height: 300 + 60 + CLUSTER_FRAME_PADDING + CLUSTER_FRAME_HEADER_HEIGHT,
            width: 400 + 200 + CLUSTER_FRAME_PADDING,
        });

        expect(result.outerNodes.map((node) => node.id)).toEqual([ROOT_ID]);
        expect(result.memberNodes.map((node) => node.id)).toEqual(['model_1']);
    });

    it('floors the box at its minimum size', () => {
        const result = layoutClusterFrames([buildRootNode()], [], {
            edgesByRootId: {[ROOT_ID]: []},
            nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 0, y: 0})]},
        });

        expect(result.outerNodes[0].data.clusterFrame).toEqual({
            clusterRootId: ROOT_ID,
            height: CLUSTER_FRAME_MIN_HEIGHT,
            width: CLUSTER_FRAME_MIN_WIDTH,
        });
    });

    it('leaves a root with no elements untouched', () => {
        const result = layoutClusterFrames([buildRootNode()], [], {edgesByRootId: {}, nodesByRootId: {}});

        expect(result.outerNodes[0].data.clusterFrame).toBeUndefined();
        expect(result.memberNodes).toEqual([]);
    });

    it('keeps a cluster root inside a graph member out of the outer array', () => {
        const rootInGraph: Node = {
            data: {clusterRoot: true, graphData: {graphId: 'graph_1', index: 0}, workflowNodeName: ROOT_ID},
            id: ROOT_ID,
            position: {x: 0, y: 0},
            type: 'workflow',
        };

        const result = layoutClusterFrames([rootInGraph], [], {
            edgesByRootId: {[ROOT_ID]: []},
            nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 400, y: 300})]},
        });

        // The elements must not reach layoutGraphFrames: findGraphMemberOwner walks dispatcher
        // nesting fields and does not follow parentId, so it cannot classify them as frame members
        // and would leave them at root scope with a frame-relative position.
        expect(result.outerNodes.map((node) => node.id)).toEqual([ROOT_ID]);
        expect(result.memberNodes.map((node) => node.id)).toEqual(['model_1']);
        expect((result.outerNodes[0].data.clusterFrame as {width: number}).width).toBeGreaterThan(
            CLUSTER_FRAME_MIN_WIDTH
        );
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.test.ts
```

Expected: FAIL — modules not found.

- [ ] **Step 3: Write the geometry module**

Create `clusterFrameGeometry.ts`:

```ts
/** Height of the box's header row, which members are never placed inside. */
export const CLUSTER_FRAME_HEADER_HEIGHT = 40;

/** Breathing room between the outermost member and the box edge. */
export const CLUSTER_FRAME_PADDING = 32;

export const CLUSTER_FRAME_MIN_HEIGHT = 220;
export const CLUSTER_FRAME_MIN_WIDTH = 420;

export interface ClusterMemberBoxI {
    height: number;
    width: number;
    x: number;
    y: number;
}

/**
 * Content-origin coordinates → the parent-relative coordinates React Flow positions a child by.
 * The two differ by the header band, and this pair is the ONLY sanctioned crossing between them:
 * open-coding the offset drifts the whole box against its contents.
 */
export function toClusterFrameChildPosition(contentPosition: {x: number; y: number}): {x: number; y: number} {
    return {x: contentPosition.x, y: contentPosition.y + CLUSTER_FRAME_HEADER_HEIGHT};
}

export function fromClusterFrameChildPosition(childPosition: {x: number; y: number}): {x: number; y: number} {
    return {x: childPosition.x, y: childPosition.y - CLUSTER_FRAME_HEADER_HEIGHT};
}

/**
 * The box a set of members needs. Measured from the content origin outward, so a member at x=0
 * still gets padding on its right and the header is added once on top.
 */
export function computeClusterFrameSize(childBoxes: ClusterMemberBoxI[]): {height: number; width: number} {
    if (childBoxes.length === 0) {
        return {height: CLUSTER_FRAME_MIN_HEIGHT, width: CLUSTER_FRAME_MIN_WIDTH};
    }

    const right = Math.max(...childBoxes.map((childBox) => childBox.x + childBox.width));
    const bottom = Math.max(...childBoxes.map((childBox) => childBox.y + childBox.height));

    return {
        height: Math.max(CLUSTER_FRAME_MIN_HEIGHT, bottom + CLUSTER_FRAME_PADDING + CLUSTER_FRAME_HEADER_HEIGHT),
        width: Math.max(CLUSTER_FRAME_MIN_WIDTH, right + CLUSTER_FRAME_PADDING),
    };
}
```

- [ ] **Step 4: Write the pre-pass**

Create `layoutClusterFrames.ts`:

```ts
import {NodeDataType} from '@/shared/types';
import {Edge, Node} from '@xyflow/react';

import {ClusterMemberBoxI, computeClusterFrameSize, toClusterFrameChildPosition} from './clusterFrameGeometry';

export interface LayoutClusterFramesResultI {
    /** Edges living entirely inside a box, to append after the outer layout returns. */
    memberEdges: Edge[];
    /** Box children (`parentId` set, parent-relative positions), parent-before-child order. */
    memberNodes: Node[];
    /** The outer arrays with every box child removed and every box root sized. */
    outerEdges: Edge[];
    outerNodes: Node[];
}

/**
 * Sizes each cluster root to the box its elements need and partitions the elements out of the arrays
 * the layout engine sees.
 *
 * Runs BEFORE `layoutGraphFrames`, and the stripping is why: `getOwningDispatcherId` walks dispatcher
 * nesting fields and does not follow `parentId`, so `findGraphMemberOwner` cannot recognise a cluster
 * element inside a graph member. Left in the outer array, such an element would keep a
 * parent-relative position while its root moved into the graph frame — a double offset. Removing
 * them here means the graph pre-pass never has to classify them at all, and the root reaches it as an
 * ordinary sized leaf.
 *
 * Positions are NOT recomputed here. Element nodes arrive already placed — either from a stored
 * `metadata.ui.nodePosition` or from the cluster placer that built them — and those positions are
 * root-relative by construction, so no coordinate translation is needed on either mode switch.
 */
export function layoutClusterFrames(
    nodes: Node[],
    edges: Edge[],
    elementsByRootId: {edgesByRootId: Record<string, Edge[]>; nodesByRootId: Record<string, Node[]>}
): LayoutClusterFramesResultI {
    const {edgesByRootId, nodesByRootId} = elementsByRootId;

    const memberNodes: Node[] = [];
    const memberEdges: Edge[] = [];

    const outerNodes = nodes.map((node) => {
        const elementNodes = nodesByRootId[node.id];

        if (!elementNodes?.length) {
            return node;
        }

        const positionedElementNodes = elementNodes.map((elementNode) => ({
            ...elementNode,
            parentId: node.id,
            position: toClusterFrameChildPosition(elementNode.position),
        }));

        const childBoxes: ClusterMemberBoxI[] = elementNodes.map((elementNode) => ({
            height: elementNode.measured?.height ?? elementNode.height ?? 0,
            width: elementNode.measured?.width ?? elementNode.width ?? 0,
            x: elementNode.position.x,
            y: elementNode.position.y,
        }));

        const frameSize = computeClusterFrameSize(childBoxes);

        memberNodes.push(...positionedElementNodes);
        memberEdges.push(...(edgesByRootId[node.id] ?? []));

        return {
            ...node,
            data: {
                ...(node.data as NodeDataType),
                clusterFrame: {clusterRootId: node.id, height: frameSize.height, width: frameSize.width},
            },
            height: frameSize.height,
            width: frameSize.width,
        };
    });

    const memberNodeIds = new Set(memberNodes.map((memberNode) => memberNode.id));

    return {
        memberEdges,
        memberNodes,
        outerEdges: edges.filter((edge) => !memberNodeIds.has(edge.source) && !memberNodeIds.has(edge.target)),
        outerNodes: outerNodes.filter((node) => !memberNodeIds.has(node.id)),
    };
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/clusterFrame/
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor/utils/clusterFrame
git commit -m "0_732 client - Add the cluster frame geometry and layout pre-pass"
```

---

### Task 5: Wire the pre-pass into the layout

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/hooks/useLayout.tsx:1175-1215`, plus `getTasksStructuralFingerprint` at `:188`
- Test: `client/src/pages/platform/workflow-editor/hooks/tests/useLayout.clusterFrame.test.ts` (create)

**Interfaces:**
- Consumes: `layoutClusterFrames` and `LayoutClusterFramesResultI` (Task 4); `useClusterElementNodes` (Task 3); `useClusterElementsViewModeStore` (Task 1).
- Produces: the canvas node array now carries sized cluster roots with parented element children whenever box mode is on.

- [ ] **Step 1: Write the failing test**

Create `useLayout.clusterFrame.test.ts` covering the fingerprint only — the layout effect itself is exercised end-to-end in Task 6:

```ts
import {WorkflowTask} from '@/shared/middleware/platform/configuration';
import {describe, expect, it} from 'vitest';

import {getTasksStructuralFingerprint} from '../useLayout';

const AGENT_TASK = {
    clusterElements: {model: {name: 'model_1', type: 'openai/v1/model'}},
    name: 'aiAgent_1',
    type: 'aiAgent/v1/chat',
} as unknown as WorkflowTask;

describe('getTasksStructuralFingerprint with cluster elements', () => {
    it('changes when an element is added', () => {
        const withTool = {
            ...AGENT_TASK,
            clusterElements: {
                ...AGENT_TASK.clusterElements,
                tools: [{name: 'tool_1', type: 'slack/v1/sendMessage'}],
            },
        } as unknown as WorkflowTask;

        expect(getTasksStructuralFingerprint([AGENT_TASK])).not.toBe(getTasksStructuralFingerprint([withTool]));
    });

    it('changes when an element position moves, because the box size follows it', () => {
        const moved = {
            ...AGENT_TASK,
            clusterElements: {
                model: {metadata: {ui: {nodePosition: {x: 900, y: 40}}}, name: 'model_1', type: 'openai/v1/model'},
            },
        } as unknown as WorkflowTask;

        expect(getTasksStructuralFingerprint([AGENT_TASK])).not.toBe(getTasksStructuralFingerprint([moved]));
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/hooks/tests/useLayout.clusterFrame.test.ts
```

Expected: FAIL — both fingerprints identical, because `collectGraphLayoutSignature` walks graph parameters only.

- [ ] **Step 3: Extend the fingerprint**

In `useLayout.tsx`, inside `getTasksStructuralFingerprint`, add each task's `clusterElements` to the signature sink alongside the existing graph signature — the elements' identities *and* their `metadata.ui.nodePosition` values, since positions are what determine the box size and therefore the outer layout:

```ts
    // A box is sized from where its elements sit, and the surrounding flow is laid out around that
    // box — so an element moving is a STRUCTURAL change to the outer canvas, not a cosmetic one.
    if (task.clusterElements) {
        sink.push(JSON.stringify(task.clusterElements));
    }
```

Place it where the existing per-task signature accumulation happens; read lines 188-252 and follow the shape already there rather than inventing a second mechanism.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/hooks/tests/useLayout.clusterFrame.test.ts
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Wire the pre-pass**

In `useLayout.tsx`, add the hook call near the other store reads:

```tsx
const clusterElementsViewMode = useClusterElementsViewModeStore((state) => state.clusterElementsViewMode);

const boxModeClusterRootIds = useMemo(
    () =>
        clusterElementsViewMode === 'box'
            ? layoutNodes
                  .filter((node) => (node.data as NodeDataType).clusterRoot)
                  .map((node) => node.id)
            : [],
    [clusterElementsViewMode, layoutNodes]
);

const {definitionsReady, edgesByRootId, nodesByRootId} = useClusterElementNodes(boxModeClusterRootIds);
```

If `layoutNodes` is computed inside the layout effect rather than in the hook body, derive `boxModeClusterRootIds` from the workflow tasks instead (a task is a cluster root when it has a `clusterElements` object) — the point is that the hook call sits in the component body, not inside the effect.

Then replace the `layoutGraphFrames(...)` call so the cluster pre-pass runs first and its members are appended last:

```tsx
        // Cluster boxes are sized and their elements partitioned out BEFORE graph frames, so the
        // graph pre-pass never has to classify a cluster element (findGraphMemberOwner cannot — it
        // does not follow parentId) and sees each cluster root as an ordinary sized leaf.
        const framedClusters = layoutClusterFrames(layoutNodes, edges, {edgesByRootId, nodesByRootId});

        layoutGraphFrames(framedClusters.outerNodes, framedClusters.outerEdges, layoutDirection, layoutFunction)
            .then((framedGraphs) =>
                layoutFunction({
                    canvasHeight: canvasHeightRef.current,
                    canvasWidth: canvasWidthRef.current,
                    direction: layoutDirection,
                    edges: framedGraphs.outerEdges,
                    nodes: framedGraphs.outerNodes,
                    savedPositionCrossAxisShift,
                }).then((elements) => {
                    autoPlacedGraphPositionsRef.current = framedGraphs.autoPlaced;

                    const layoutElementNodes = [
                        ...elements.nodes,
                        ...framedGraphs.memberNodes,
                        ...framedClusters.memberNodes,
                    ];

                    return {
                        ...elements,
                        edges: [
                            ...elements.edges,
                            ...framedGraphs.memberEdges,
                            ...framedClusters.memberEdges,
                        ],
                        nodes: readOnlyWorkflow ? toReadOnlyLayoutNodes(layoutElementNodes) : layoutElementNodes,
                    };
                })
            )
```

Cluster members must be appended **after** graph members: React Flow requires parents before children, and a cluster root can itself be a graph member.

Guard the whole effect on definitions being ready, so the box never lays out at the wrong size and then jumps:

```tsx
        if (boxModeClusterRootIds.length > 0 && !definitionsReady) {
            return;
        }
```

Add `boxModeClusterRootIds`, `definitionsReady`, `edgesByRootId` and `nodesByRootId` to the effect's dependency array.

- [ ] **Step 6: Verify the whole layout suite**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/hooks/ src/pages/platform/workflow-editor/utils/
```

Expected: PASS. Box mode is off by default, so every existing layout test takes the unchanged path.

- [ ] **Step 7: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor/hooks
git commit -m "0_732 client - Run the cluster frame pre-pass before the layout engine"
```

---

### Task 6: The box shell

First visible box. After this task, flipping the toggle renders elements inline; clicking them still opens the dialog (Task 9 fixes that).

**Files:**
- Create: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.tsx`
- Create: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx`
- Modify: `client/src/pages/platform/workflow-editor/nodes/WorkflowNode.tsx`
- Modify: `client/src/pages/platform/workflow-editor/nodes/AiAgentNode.tsx`

**Interfaces:**
- Consumes: `NodeDataType.clusterFrame` (Task 2), `CLUSTER_FRAME_HEADER_HEIGHT` (Task 4).
- Produces: `ClusterFrameShell` — `{children: ReactNode; data: NodeDataType; nodeId: string}`. Task 11 adds the destination buttons to its header; Task 12 adds lock and reset.

- [ ] **Step 1: Write the failing test**

Create `ClusterFrameShell.test.tsx`:

```tsx
import {NodeDataType} from '@/shared/types';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import ClusterFrameShell from './ClusterFrameShell';

vi.mock('@xyflow/react', () => ({
    Handle: () => null,
    Position: {Bottom: 'bottom', Left: 'left', Right: 'right', Top: 'top'},
}));

const CLUSTER_ROOT_DATA = {
    clusterFrame: {clusterRootId: 'aiAgent_1', height: 320, width: 640},
    label: 'AI Agent',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

describe('ClusterFrameShell', () => {
    it('paints the box at the size the pre-pass computed', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        const shell = screen.getByTestId('cluster-frame-shell');

        expect(shell).toHaveStyle({height: '320px', width: '640px'});
        expect(screen.getByText('root card')).toBeInTheDocument();
    });

    it('renders the children bare when no box has been computed', () => {
        render(
            <ClusterFrameShell data={{label: 'AI Agent'} as NodeDataType} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.queryByTestId('cluster-frame-shell')).not.toBeInTheDocument();
        expect(screen.getByText('root card')).toBeInTheDocument();
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx
```

Expected: FAIL — module not found.

- [ ] **Step 3: Write the shell**

Create `ClusterFrameShell.tsx`:

```tsx
import {NodeDataType} from '@/shared/types';
import {Handle, Position} from '@xyflow/react';
import {ReactNode, memo} from 'react';

import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import {mapHandlePosition} from '../utils/directionUtils';
import {CLUSTER_FRAME_HEADER_HEIGHT} from '../utils/clusterFrame/clusterFrameGeometry';
import styles from './NodeTypes.module.css';

interface ClusterFrameShellProps {
    children: ReactNode;
    data: NodeDataType;
    nodeId: string;
}

/**
 * The box a cluster root's elements are drawn inside. Unlike a graph frame this is not a node of its
 * own: the root IS its elements' React Flow parent, so the shell wraps the root's own card and takes
 * the size the pre-pass wrote onto `data.clusterFrame`.
 *
 * Renders its children bare when there is no box — dialog mode, and the first paint before the
 * pre-pass has run — so the same node component serves both modes without branching at the call site.
 *
 * The chain handles live here rather than on the card, so the surrounding flow connects to the box.
 */
const ClusterFrameShell = ({children, data, nodeId}: ClusterFrameShellProps) => {
    const layoutDirection = useLayoutDirectionStore((state) => state.layoutDirection);

    const clusterFrame = data.clusterFrame;

    if (!clusterFrame) {
        return children;
    }

    return (
        <div
            className="rounded-lg border-2 border-dashed border-stroke-neutral-secondary bg-surface-neutral-secondary/40"
            data-nodetype="clusterFrame"
            data-testid="cluster-frame-shell"
            style={{height: clusterFrame.height, width: clusterFrame.width}}
        >
            <div
                className="flex items-center justify-between px-3"
                style={{height: CLUSTER_FRAME_HEADER_HEIGHT}}
            >
                <span className="text-sm font-semibold text-content-neutral-secondary">{data.label}</span>
            </div>

            {children}

            <Handle
                className={styles.handle}
                id={`${nodeId}-top`}
                position={mapHandlePosition(Position.Top, layoutDirection)}
                type="target"
            />

            <Handle
                className={styles.handle}
                id={`${nodeId}-bottom`}
                position={mapHandlePosition(Position.Bottom, layoutDirection)}
                type="source"
            />
        </div>
    );
};

export default memo(ClusterFrameShell);
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Wrap the node components**

In both `WorkflowNode.tsx` and `AiAgentNode.tsx`, wrap the component's returned JSX:

```tsx
    return (
        <ClusterFrameShell data={data} nodeId={id}>
            {/* the existing returned tree, unchanged */}
        </ClusterFrameShell>
    );
```

Suppress the card's own chain handles when `data.clusterFrame` is set, so the chain attaches to the shell and not to the card inside it — find the existing `<Handle>` elements carrying the `-top` / `-bottom` ids and guard them with `{!data.clusterFrame && ...}`.

Also mark every element node non-connectable. Cluster edges are derived from the component definition and are never drawn by hand — the dialog canvas says so with `nodesConnectable={false}`, but the main canvas is connectable, so the elements must opt out per node. In `layoutClusterFrames`'s member mapping (Task 4), add `connectable: false` beside `parentId`. Without it, dragging from an element's handle offers to wire it into the task chain, which the definition has no way to represent.

- [ ] **Step 6: Verify the node suites**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/nodes/
```

Expected: PASS. `data.clusterFrame` is undefined in every existing test, so the shell returns its children untouched.

- [ ] **Step 7: Manual check**

Start the app, open a workflow with an AI Agent, flip the toolbar toggle to box mode. Expected: the agent renders as a dashed box with its model/tools/memory nodes inline, and the tasks above and below it reflow around the box. Clicking a node still opens the dialog — that is Task 9.

- [ ] **Step 8: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor/nodes
git commit -m "0_732 client - Render cluster roots as an inline box"
```

---

### Task 7: Untangle the redundant flag conjuncts

Removes `clusterElementsCanvasOpen` from the ten sites where the real predicate already sits beside it, and replaces the two paste guards. Behaviour-preserving in dialog mode by construction: each removed term is implied by the one that remains.

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/components/hooks/useWorkflowNodeDetailsPanel.ts:464,765,1034,1411,1415,1480`
- Modify: `client/src/pages/platform/workflow-editor/nodes/WorkflowNode.tsx:692,723`
- Modify: `client/src/pages/platform/workflow-editor/components/WorkflowNodesPopoverMenuOperationList.tsx:132,336,441,519`
- Modify: `client/src/pages/platform/workflow-editor/edges/WorkflowEdge.tsx:184`
- Modify: `client/src/pages/platform/workflow-editor/nodes/PlaceholderNode.tsx:55`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new. `clusterElementsCanvasOpen` afterwards means only "the dialog is open".

- [ ] **Step 1: Delete the redundant conjuncts**

At each listed line, drop `clusterElementsCanvasOpen &&` and keep the rest of the condition verbatim. For example `useWorkflowNodeDetailsPanel.ts:765`:

```ts
        if (isClusterElement) {
```

and `WorkflowNode.tsx:692`:

```ts
        const clusterRootRequirementMet =
            (isMainRootClusterElement || isNestedClusterRoot) && clusterRootComponentDefinition;
```

Remove `clusterElementsCanvasOpen` from each affected dependency array and from the `useShallow` selector when it becomes unused in that file. For the `if/else if` pairs (`useWorkflowNodeDetailsPanel.ts:1411/1415`, `WorkflowNodesPopoverMenuOperationList.tsx:441`), rewrite the branch condition in terms of `isClusterElement` alone — read the surrounding twenty lines first; a plain deletion inverts the meaning of an `else if`.

- [ ] **Step 2: Replace the two paste guards**

`WorkflowEdge.tsx:184` and `PlaceholderNode.tsx:55` both become:

```ts
    // A cluster element placeholder is not a task slot, so a copied task cannot go in it. Keyed on
    // the node rather than on a canvas-wide flag: in box mode ordinary placeholders and edges sit on
    // the same canvas as cluster ones, and a canvas-wide test would disable paste across the whole
    // workflow whenever it contains an agent.
    const showPasteButton = useMemo(
        () => !data.clusterElementType && !!copiedNode && copiedWorkflowId === workflow.id,
        [copiedNode, copiedWorkflowId, data.clusterElementType, workflow.id]
    );
```

Use whatever the local variable is actually named; only the predicate changes.

- [ ] **Step 3: Verify nothing regressed**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/ src/pages/platform/cluster-element-editor/
```

Expected: PASS, with `ClusterElementsCanvasDialog.test.tsx` and every cluster-element-editor test unmodified.

- [ ] **Step 4: Confirm the remaining uses are all dialog-open**

```bash
grep -rn "clusterElementsCanvasOpen" --include="*.ts*" client/src | grep -v "\.test\." | grep -v "state\."
```

Expected: only `useLayout.tsx:879,911`, `WorkflowEditorLayout.tsx:230,347,359,374`, `useWorkflowEditorLayout.ts:54`, `handleDeleteTask.ts:278`, `useWorkflowNodeDetailsPanel.ts:1377`, the store declaration, and `useAiAgentToolActions.tsx`. Tasks 8 and 9 handle the last few. Anything else means a site was missed.

- [ ] **Step 5: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor
git commit -m "0_732 client - Drop the dialog flag from conditions that already test the node"
```

---

### Task 8: Resolve the cluster root from the node in hand

The three sites that used `rootClusterElementNodeData` to mean "the root we are working in" — which has no answer when several boxes share a canvas.

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/utils/handleDeleteTask.ts:278`
- Modify: `client/src/pages/platform/workflow-editor/components/hooks/useWorkflowNodeDetailsPanel.ts:1377`
- Test: `client/src/pages/platform/workflow-editor/utils/tests/handleDeleteTask.clusterElement.test.ts` (create)

**Interfaces:**
- Consumes: `NodeDataType.parentClusterRootId`.
- Produces: `resolveClusterRootId(data: NodeDataType): string | undefined` exported from `handleDeleteTask.ts`'s module or a small sibling util — returns `data.parentClusterRootId` for an element, `data.workflowNodeName` for a root, `undefined` otherwise.

- [ ] **Step 1: Write the failing test**

```ts
import {NodeDataType} from '@/shared/types';
import {describe, expect, it} from 'vitest';

import {resolveClusterRootId} from '../resolveClusterRootId';

describe('resolveClusterRootId', () => {
    it('returns the parent root for an element', () => {
        expect(
            resolveClusterRootId({parentClusterRootId: 'aiAgent_1', workflowNodeName: 'model_1'} as NodeDataType)
        ).toBe('aiAgent_1');
    });

    it('returns itself for a root', () => {
        expect(resolveClusterRootId({clusterRoot: true, workflowNodeName: 'aiAgent_1'} as NodeDataType)).toBe(
            'aiAgent_1'
        );
    });

    it('returns undefined for a plain task', () => {
        expect(resolveClusterRootId({workflowNodeName: 'task_1'} as NodeDataType)).toBeUndefined();
    });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/tests/handleDeleteTask.clusterElement.test.ts
```

Expected: FAIL — module not found.

- [ ] **Step 3: Write the resolver**

Create `client/src/pages/platform/workflow-editor/utils/resolveClusterRootId.ts`:

```ts
import {NodeDataType} from '@/shared/types';

/**
 * The cluster root a node belongs to, asked of the node rather than of the editor. Box mode can show
 * several roots at once, so "the root currently open" (`rootClusterElementNodeData`) stops being a
 * well-formed question for anything operating on a specific node.
 */
export function resolveClusterRootId(data: NodeDataType): string | undefined {
    if (data.parentClusterRootId) {
        return data.parentClusterRootId;
    }

    return data.clusterRoot ? data.workflowNodeName : undefined;
}
```

- [ ] **Step 4: Re-point the two call sites**

`handleDeleteTask.ts:278` — replace the `clusterElementsCanvasOpen && rootClusterElementNodeData` branch condition with a root resolved from the node being deleted:

```ts
    } else if (clusterRootId) {
        const mainRootClusterElementTask = getTask({tasks: workflowTasks, workflowNodeName: clusterRootId});
```

where `clusterRootId` is `resolveClusterRootId(data)` computed at the top of the function. Delete the now-unused `clusterElementsCanvasOpen` parameter from the props interface and from every caller — `grep -rn "handleDeleteTask" --include="*.ts*" client/src` to find them.

`useWorkflowNodeDetailsPanel.ts:1377` — the effect that collects cluster element component operations. Replace the `!clusterElementsCanvasOpen` guard and the `rootClusterElementNodeData?.workflowNodeName` lookup with the root resolved from `currentNode`:

```ts
    useEffect(() => {
        const clusterRootId = currentNode ? resolveClusterRootId(currentNode as NodeDataType) : undefined;

        if (!clusterRootId || !workflow.definition) {
            return;
        }

        const mainClusterRootTask = getTask({
            tasks: JSON.parse(workflow.definition).tasks,
            workflowNodeName: clusterRootId,
        });
        // …unchanged from here
    }, [currentNode, workflow]);
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/ src/pages/platform/cluster-element-editor/
```

Expected: PASS, dialog tests unmodified.

- [ ] **Step 6: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor
git commit -m "0_732 client - Resolve the cluster root from the node rather than the open dialog"
```

---

### Task 9: Open the details panel instead of the dialog

Delivers the headline requirement: "there is no popup anymore".

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/hooks/useNodeClick.ts:75`
- Modify: `client/src/pages/platform/workflow-editor/WorkflowEditorLayout.tsx:347,374`
- Test: `client/src/pages/platform/workflow-editor/hooks/tests/useNodeClick.boxMode.test.ts` (create)

**Interfaces:**
- Consumes: `useClusterElementsViewModeStore` (Task 1).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```ts
import useClusterElementsViewModeStore from '@/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {NodeDataType} from '@/shared/types';
import {act, renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it} from 'vitest';

import useNodeClick from '../useNodeClick';

const CLUSTER_ROOT_DATA = {
    clusterRoot: true,
    componentName: 'aiAgent',
    label: 'AI Agent',
    name: 'aiAgent_1',
    type: 'aiAgent/v1/chat',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

describe('useNodeClick in box mode', () => {
    beforeEach(() => {
        useWorkflowEditorStore.setState({clusterElementsCanvasOpen: false});
    });

    it('opens the dialog for a cluster root in dialog mode', () => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});

        const {result} = renderHook(() => useNodeClick(CLUSTER_ROOT_DATA, 'aiAgent_1'));

        act(() => result.current());

        expect(useWorkflowEditorStore.getState().clusterElementsCanvasOpen).toBe(true);
    });

    it('leaves the dialog closed in box mode', () => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'box'});

        const {result} = renderHook(() => useNodeClick(CLUSTER_ROOT_DATA, 'aiAgent_1'));

        act(() => result.current());

        expect(useWorkflowEditorStore.getState().clusterElementsCanvasOpen).toBe(false);
    });
});
```

Mirror the mocking already used by the neighbouring tests in `hooks/tests/` — `useNodeClick` reads several stores, and those tests establish the pattern. Use `vi.hoisted` for any mock the factory references.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/hooks/tests/useNodeClick.boxMode.test.ts
```

Expected: FAIL on the second case — the dialog opens regardless of mode.

- [ ] **Step 3: Make the dialog conditional**

`useNodeClick.ts:75`:

```ts
            // In box mode the elements are already on the canvas, so a click means "show me this
            // node's details", not "take me to another editor".
            if (
                !!data.clusterRoot &&
                !clusterElementsCanvasOpen &&
                useClusterElementsViewModeStore.getState().clusterElementsViewMode === 'dialog'
            ) {
                setClusterElementsCanvasOpen(true);
            }
```

- [ ] **Step 4: Mount the panels for cluster nodes in box mode**

`WorkflowEditorLayout.tsx:347` and `:374` currently read
`currentNode?.type && !isMainRootClusterElement && !clusterElementsCanvasOpen`. The exclusion of cluster roots exists only because the dialog renders its own panel instance. Compute once, above the return:

```tsx
    // The dialog renders its own details panel, so the root-level one stands down while it is open.
    // In box mode there is no dialog, so cluster roots and elements get this panel like anything else.
    const showRootLevelDetailsPanel =
        !!currentNode?.type && !clusterElementsCanvasOpen && (clusterElementsViewMode === 'box' || !isMainRootClusterElement);
```

and use `showRootLevelDetailsPanel` at both sites (the second keeps its `&& dataPillPanelOpen`).

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/
```

Expected: PASS, 2 new tests included.

- [ ] **Step 6: Manual check**

In box mode, click the root card, a tool, and an empty placeholder in turn. Expected: the details panel opens on the right for each, the dialog never appears, and the panel's tabs behave as they do for an ordinary task.

- [ ] **Step 7: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor
git commit -m "0_732 client - Open the node details panel for cluster elements in box mode"
```

---

### Task 10: Drag, lock and reset inside the box

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.tsx`
- Modify: `client/src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.ts`
- Modify: `client/src/pages/platform/workflow-editor/hooks/useWorkflowEditorCanvas.ts` (the node-drag-stop handler)
- Test: `client/src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.test.ts`

**Interfaces:**
- Consumes: `layoutClusterFrames` (Task 4), `ClusterFrameShell` (Task 6), `saveClusterElementNodesPosition` (existing).
- Produces: `data.clusterFrame` gains no fields; per-root lock state lives in a new `Record<string, boolean>` on `useWorkflowEditorStore` — `clusterFrameLockedByRootId`, defaulting to locked for an absent key.

- [ ] **Step 1: Write the failing test**

Append to `layoutClusterFrames.test.ts`:

```ts
    it('marks elements draggable only when their root is unlocked', () => {
        const elements = {edgesByRootId: {[ROOT_ID]: []}, nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 0, y: 0})]}};

        expect(layoutClusterFrames([buildRootNode()], [], elements, {}).memberNodes[0].draggable).toBe(false);

        expect(
            layoutClusterFrames([buildRootNode()], [], elements, {[ROOT_ID]: false}).memberNodes[0].draggable
        ).toBe(true);
    });
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/utils/clusterFrame/layoutClusterFrames.test.ts
```

Expected: FAIL — `layoutClusterFrames` takes three arguments and stamps no `draggable`.

- [ ] **Step 3: Stamp draggability**

Add a fourth parameter `lockedByRootId: Record<string, boolean>` and, in the member mapping:

```ts
        // Members are draggable ONLY when their root is unlocked, and independently of the canvas-wide
        // drag lock — the same per-node override graph members and sticky notes use. `extent` keeps a
        // drag inside the box; the header band is excluded so a member cannot be parked under the
        // controls.
        const draggable = lockedByRootId[node.id] === false;

        const positionedElementNodes = elementNodes.map((elementNode) => ({
            ...elementNode,
            draggable,
            extent: [[0, CLUSTER_FRAME_HEADER_HEIGHT], [Infinity, Infinity]] as [[number, number], [number, number]],
            parentId: node.id,
            position: toClusterFrameChildPosition(elementNode.position),
        }));
```

Update the Task 5 call site to pass `useWorkflowEditorStore.getState().clusterFrameLockedByRootId`.

- [ ] **Step 4: Add the header controls**

In `ClusterFrameShell.tsx`, read the mutation from the editor provider and the lock state from the store, alongside the existing `layoutDirection` read:

```tsx
const clusterFrameLockedByRootId = useWorkflowEditorStore((state) => state.clusterFrameLockedByRootId);
const setClusterFrameLocked = useWorkflowEditorStore((state) => state.setClusterFrameLocked);

const {updateWorkflowMutation} = useWorkflowEditor();

const locked = clusterFrameLockedByRootId[nodeId] !== false;
```

`clusterFrameLockedByRootId` and `setClusterFrameLocked(clusterRootId, locked)` are added to `useWorkflowEditorStore` in this task, defaulting to `{}` — an absent key reads as locked, matching the dialog's `setNodesLocked(true)` on mount. Then add to the header row (right-aligned), rendering only when `updateWorkflowMutation` exists — a read-only canvas has nothing to edit with, so the controls are omitted entirely rather than disabled:

```tsx
                {updateWorkflowMutation && (
                    <div className="flex items-center gap-1">
                        <button
                            aria-label={locked ? 'Unlock node movement' : 'Lock node movement'}
                            className={HEADER_BUTTON_CLASSNAME}
                            onClick={handleToggleLock}
                            type="button"
                        >
                            {locked ? <LockIcon className="size-3.5" /> : <LockOpenIcon className="size-3.5" />}
                        </button>

                        <button
                            aria-label="Reset layout"
                            className={HEADER_BUTTON_CLASSNAME}
                            onClick={handleResetLayout}
                            type="button"
                        >
                            <BrushCleaningIcon className="size-3.5" />
                        </button>
                    </div>
                )}
```

with `const HEADER_BUTTON_CLASSNAME = 'nodrag flex items-center gap-1 rounded px-2 py-1 text-xs text-content-neutral-secondary hover:bg-surface-neutral-secondary';` at module scope — the same class `GraphFrameNode` uses. `handleResetLayout` clears the saved positions for that root via the existing `clearAllClusterElementPositions` util and persists through `updateWorkflowMutation`.

- [ ] **Step 5: Persist a drop**

In the canvas's node-drag-stop handler, when the dragged node has a `parentClusterRootId`, call the existing `saveClusterElementNodesPosition`, converting the dropped position back through `fromClusterFrameChildPosition` first — the stored `nodePosition` is content-origin, the live one is parent-relative, and skipping the conversion drifts every element down by the header height on each drag.

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/
```

Expected: PASS.

- [ ] **Step 7: Manual check**

Unlock a box, drag a tool, reload the page: it stays where it was dropped. Lock it, try to drag: nothing moves. Reset layout: elements return to their computed positions and the box resizes to match.

- [ ] **Step 8: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor
git commit -m "0_732 client - Add lock, reset and element dragging inside the cluster box"
```

---

### Task 11: Box header destinations

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.tsx`
- Modify: `client/src/pages/platform/workflow-editor/components/stores/useClusterElementsCanvasDialogStore.ts`
- Test: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx`

**Interfaces:**
- Consumes: `ClusterFrameShell` (Task 6), the existing `useClusterElementsCanvasDialogStore` flags `showAiAgentEditor`, `showDataStreamEditor`, `evalsPanelOpen`.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```tsx
    it('opens the AI Agent editor preset, not the canvas view', async () => {
        const user = userEvent.setup();

        render(
            <ClusterFrameShell data={AI_AGENT_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        await user.click(screen.getByLabelText('Switch to AI Agent editor'));

        expect(useClusterElementsCanvasDialogStore.getState().showAiAgentEditor).toBe(true);
        expect(useWorkflowEditorStore.getState().clusterElementsCanvasOpen).toBe(true);
        expect(useWorkflowEditorStore.getState().rootClusterElementNodeData?.workflowNodeName).toBe('aiAgent_1');
    });
```

`AI_AGENT_ROOT_DATA` is `CLUSTER_ROOT_DATA` plus `componentName: 'aiAgent'`.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx
```

Expected: FAIL — no such button.

- [ ] **Step 3: Add the destination buttons**

In the shell's header, before the lock/reset group, add buttons for the AI Agent editor (`TextInitialIcon`), the DataStream editor (same icon, different label), Skills (`ZapIcon`, an external link to `/automation/settings/ai/skills`) and Evals (`FlaskConicalIcon`, behind `ff_4553`). Show each under the same conditions `ClusterElementsWorkflowEditorHeader` uses today — read that file and mirror `isAiAgentClusterRoot` / `isDataStreamClusterRoot` / `isDataStreamSimpleModeAvailable` rather than reimplementing the tests.

Each handler seeds the root and opens the destination:

```tsx
    const handleOpenAiAgentEditor = useCallback(() => {
        setRootClusterElementNodeData(data);
        setShowAiAgentEditor(true);
        setClusterElementsCanvasOpen(true);
    }, [data, setClusterElementsCanvasOpen, setRootClusterElementNodeData, setShowAiAgentEditor]);
```

Seeding `rootClusterElementNodeData` explicitly is what lets `AiAgentEditor`, `useAiAgentTools` and Evals stay unchanged inside: they each read that single field, and box mode simply tells them which root they were opened on.

- [ ] **Step 4: Return to the canvas on close**

In `ClusterElementsCanvasDialog`, when a destination is closed and the view mode is `'box'`, clear `clusterElementsCanvasOpen` rather than falling back to the dialog's canvas view — in box mode that view is a surface the user never asked for.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/ src/pages/platform/cluster-element-editor/
```

Expected: PASS.

- [ ] **Step 6: Format, check and commit**

```bash
cd client && npm run format && npm run check
```

```bash
git add client/src/pages/platform/workflow-editor
git commit -m "0_732 client - Open the agent editor, wizard and evals from the cluster box header"
```

---

### Task 12: The playground beside the canvas

The last piece: the agent testing chat as a main-canvas side panel rather than a destination, because being next to the canvas is the whole point of it.

**Files:**
- Modify: `client/src/pages/platform/workflow-editor/WorkflowEditorLayout.tsx`
- Modify: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.tsx`
- Test: `client/src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx`

**Interfaces:**
- Consumes: `ClusterFrameShell` (Task 6), `AiAgentTestingPanel` (existing), `useClusterElementsCanvasDialogStore.testingPanelOpen` (existing).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```tsx
    it('opens the playground without opening the dialog', async () => {
        const user = userEvent.setup();

        render(
            <ClusterFrameShell data={AI_AGENT_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        await user.click(screen.getByLabelText('Test agent'));

        expect(useClusterElementsCanvasDialogStore.getState().testingPanelOpen).toBe(true);
        expect(useWorkflowEditorStore.getState().clusterElementsCanvasOpen).toBe(false);
        expect(useWorkflowEditorStore.getState().rootClusterElementNodeData?.workflowNodeName).toBe('aiAgent_1');
    });
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/nodes/ClusterFrameShell.test.tsx
```

Expected: FAIL — no such button.

- [ ] **Step 3: Add the Test agent control**

In the shell's header, shown when `isAiAgentClusterRoot`:

```tsx
    const handleOpenPlayground = useCallback(() => {
        setRootClusterElementNodeData(data);
        setTestingPanelOpen(true);
    }, [data, setRootClusterElementNodeData, setTestingPanelOpen]);
```

Note what it does *not* do: it never sets `clusterElementsCanvasOpen`. The playground is a panel beside the canvas, not a destination over it.

- [ ] **Step 4: Mount the panel on the main canvas**

In `WorkflowEditorLayout.tsx`, beside the existing `WorkflowTestChatPanel` block (`:368`):

```tsx
            {clusterElementsViewMode === 'box' && testingPanelOpen && (
                <Suspense fallback={null}>
                    <AiAgentTestingPanel onClose={handleClosePlayground} />
                </Suspense>
            )}
```

Add it as a `lazy(...)` import alongside the other lazily-loaded panels. Do **not** carry over the dialog's `right-[465px]` / `right-[450px]` / `right-[915px]` shift classes — those encode dialog geometry. The main canvas arranges its panels through the existing layout; let this one join it the way `WorkflowTestChatPanel` does.

Gate on `clusterElementsViewMode === 'box'` so dialog mode keeps rendering its own instance and no workflow ever shows two.

- [ ] **Step 5: Point the Copilot control at the canvas's own panel**

The main canvas already has a Copilot panel of its own, driven by `useCopilotPanelStore`
(`WorkflowEditorLayout.tsx:12,95`) — distinct from the dialog's `copilotPanelOpen`. So the box
header's Copilot button opens that one and mounts nothing new:

```tsx
    const handleOpenCopilot = useCallback(() => {
        setRootClusterElementNodeData(data);
        setCopilotPanelOpen(true);
    }, [data, setCopilotPanelOpen, setRootClusterElementNodeData]);
```

with `setCopilotPanelOpen` from `useCopilotPanelStore`. Using the dialog's store here would set a flag
nothing on this canvas reads, and the button would appear to do nothing.

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd client && npx vitest run src/pages/platform/workflow-editor/ src/pages/platform/cluster-element-editor/
```

Expected: PASS.

- [ ] **Step 7: Manual check**

In box mode, click ▶ on an agent box. Expected: the chat opens on the right with the canvas and its box still visible on the left; opening the details panel as well leaves all three legible; sending a message runs against the agent the button belonged to.

- [ ] **Step 8: Full verification**

```bash
cd client && npm run check
```

(Tool timeout 600000ms.) Expected: lint, typecheck and the full test suite pass.

- [ ] **Step 9: Format and commit**

```bash
cd client && npm run format
```

```bash
git add client/src/pages/platform/workflow-editor
git commit -m "0_732 client - Open the agent playground beside the canvas in box mode"
```

---

## Verification checklist

Run before declaring the feature done. Every line needs a command or an observation, not an assumption.

- [ ] `cd client && npm run check` passes (timeout 600000ms).
- [ ] `git diff master --stat -- client/src/pages/platform/cluster-element-editor` shows no changes to `useClusterElementsLayout.test.ts`, `clusterElementsUtils.test.ts` or `useClusterElementStep.test.ts`.
- [ ] `grep -rn "clusterElementsCanvasOpen" --include="*.ts*" client/src | grep -v "\.test\."` returns only dialog-open sites.
- [ ] With `ff-5470` off: no toggle in the toolbar, and a cluster root behaves exactly as on `master`.
- [ ] Dialog mode with the flag on: the dialog opens on click, its canvas, editor, wizard, evals, playground and Copilot all work.
- [ ] Box mode: elements render inline; adding a tool grows the box and reflows the tasks around it; drag/lock/reset persist; clicking any member opens the details panel and never the dialog.
- [ ] Box mode with two agents in one workflow: both render, each header opens *its own* agent's editor and playground.
- [ ] An AI Agent used as a `graph/v1` node renders as a box inside the graph frame, and the graph frame is sized around the box rather than around the small card.
- [ ] The execution view and an MCP app workflow viewer are unchanged in both modes.

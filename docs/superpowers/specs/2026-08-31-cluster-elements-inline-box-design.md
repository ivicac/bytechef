# Cluster elements as an inline box on the main canvas

- **Date**: 2026-08-31
- **Status**: Approved design; implementation plan to follow
- **Ticket**: 0_732 (user request: "when I drop a cluster element it is automatically inside a box —
  the similar one in graph task dispatcher — but that applies all layouting logic we have now in the
  existing cluster element editor … there is no node then box, the cluster element is immediately set
  inside a box … when I click on any node inside box, WorkflowNodeDetails panel opens as usual. The
  difference is there is no popup anymore")
- **Related**: `2026-08-17-graph-dispatcher-freeform-canvas-design.md` (the box idiom this borrows),
  `.agents/agents.md` (cluster roots, AI Agent)

## Problem

A cluster root's elements are only visible inside `ClusterElementsCanvasDialog` — a full-screen dialog
hosting a *second* React Flow instance with its own store, node factories, edge factories and layout.
Editing an agent therefore means leaving the workflow: the surrounding flow disappears, and the
details panel that opens is a different instance from the one the main canvas uses.

The `graph/v1` dispatcher solved the same visibility problem the other way — a box on the main canvas
whose members are laid out inside it and whose size feeds the outer layout, so the flow reshapes
around it. This spec applies that idiom to cluster roots, as a **second rendering mode** the user
switches between; the dialog editor stays and remains the default.

## Decisions (user, 2026-08-31)

1. **Root inside the box, at the top.** No separate chain node above the box, unlike `graph/v1`.
2. **Layout controls match the dialog**: lock/unlock and reset. Elements drag when unlocked; the root
   is pinned and never draggable.
3. **Canvas-wide toggle, per user**, persisted to `localStorage`. The workflow definition is not
   touched, so two people may view the same workflow in different modes.
4. **The dialog's other surfaces stay reachable** from the box header — the editor, wizard and Evals
   full-screen; the playground and Copilot beside the canvas (§7).
5. **Editor only.** Execution view and the `mcp-apps/workflow-editor` viewers keep today's single-node
   rendering.
6. **`clusterElementsCanvasOpen` is untangled**, not overloaded (§4).

## 1. Anatomy: the root node *is* the box

Cluster element nodes are already created with `parentId: clusterRootId`
(`clusterElementsNodesUtils.tsx:49,115,181`), so the root is already their React Flow parent and their
stored `metadata.ui.nodePosition` is already **root-relative**, not canvas-absolute. Box mode does not
introduce a parent; it sizes and paints the parent that exists.

```
┌───────────────────────────┐
│ AI Agent      🔒 ↺ │ ⬚⚡🧪▶✨│  ← ClusterFrameShell: border, header, controls
│                           │
│      [ ⬡ AI Agent ]       │  ← the existing root card, at the content origin
│       ╱      │      ╲     │
│  [model]  [tools]  [mem]  │  ← existing element nodes, parentId = root id
└───────────────────────────┘
```

- The root node keeps its id (`workflowNodeName`). Chain edges, `collectChainSuccessorNodes`, task
  insertion, `getTask`, delete and the details panel all address it unchanged — **no chain-identity
  churn**.
- `WorkflowNode` and `AiAgentNode` wrap their existing output in a new `ClusterFrameShell` when box
  mode is on. The shell owns the border, the header row and the box dimensions; the card renders at
  the content origin. Neither node file grows appreciably — the shell is its own component.
- The chain handles (top target, bottom source) move from the card's edges to the **shell's** edges,
  so the flow connects to the box.
- `data.clusterFrame = {height, width}` is written by the layout pre-pass, mirroring the existing
  `data.graphFrame`.

**Why this differs from `graph/v1`.** A graph member is a task in a list with no parent relationship
to the dispatcher, so a graph needs a distinct `graphFrame` node to parent its members to. Cluster
elements already have that relationship; a second node would mean re-parenting every element and
re-pointing every chain edge to gain nothing visible.

**Cost accepted.** The root node's React Flow box becomes the whole frame, so anything that reasons
about "the root's size" — `calculateNodeWidth`, hit-testing, the `clusterRoot` arm of
`getDagreNodeSize` — must distinguish the card from the frame. The `data.clusterFrame` early return
(symmetrical with the existing `graphFrame` early return at `layoutUtils.tsx:207`) is where that
distinction is made once.

## 2. Where the element nodes come from

`createClusterElementsNodes` and `createClusterElementsEdges` are reused **verbatim**; they already
emit main-canvas-shaped nodes. Both move behind a shared `useClusterElementNodes` hook called by the
dialog and by the main canvas.

Two pieces of the dialog's `useClusterElementsLayout` move into that hook:

- **The nested-definition fetch.** The `queryClient.fetchQuery` loop that resolves each nested cluster
  root's `ComponentDefinition` is not dialog-specific and is required before the first layout, or the
  box visibly re-flows when the definitions land.
- **The root definition query**, currently driven by `rootClusterElementNodeData`.

`useWorkflowEditorStore.mainClusterRootComponentDefinition` is **singular** — sufficient when only one
root is ever shown. The main canvas can show several boxes at once, so it becomes a map keyed by root
id.

`rootClusterElementNodeData` stays **singular**, and its meaning widens only slightly: from "the root
the dialog is open on" to "the root whose surface is open". That is enough because every consumer of
it — `AiAgentEditor`, `useAiAgentTools`, `AiAgentPromptField`, `AiAgentTestingPanel`, Evals — is
single-root by nature: you edit one agent, you test one agent. Those surfaces therefore need **no
internal change**; the box header seeds the field when opening one, exactly as `useWorkflowEditorLayout`
seeds it on dialog open today.

The box *rendering* never reads it. Each box resolves its own root from its own node data, which is
what lets several boxes coexist.

## 3. Layout: the `layoutClusterFrames` pre-pass

A pre-pass over cluster roots, run before the active engine, modelled on `layoutGraphFrames` but
simpler — positions are already relative and there are no per-member subtrees to normalise:

1. Build each root's element nodes and edges (§2).
2. Place them with the existing `getClusterElementsLayoutElements`, with its `canvasCenterX`
   root-centering replaced by a fixed content origin below the header. Saved
   `metadata.ui.nodePosition` values are honoured exactly as today (`containsNodePosition`).
3. Frame size = bounding box of the placed children + padding + header, floored at a minimum size.
4. Strip the children and their edges from the outer arrays, write `data.clusterFrame` on the root so
   the engine sizes it as that box, run the outer engine, then re-append children and edges. Because
   children are parent-relative, the outer result cannot disturb them; because the root is an ordinary
   chain node, the surrounding flow reflows around a growing box through the existing relayout and
   `animateNodePositions` tween.
5. `getTasksStructuralFingerprint` gains each root's `clusterElements` signature and its members'
   positions, so adding a tool re-runs the outer layout.

Nested cluster roots stay **flat inside the one box**, exactly as in the dialog — no nested frames.
Their `parentId` chains already nest correctly in React Flow.

### A cluster root inside a graph member

`graph/v1` members may be any task, and an AI Agent as a graph node is a headline case (LLM routing),
so this shape is supported rather than excluded. It needs two things beyond "run cluster first":

- **Strip first, re-append last.** `layoutClusterFrames` removes cluster children and their edges from
  the outer arrays *before* `layoutGraphFrames` runs, and they are re-appended only after the outer
  engine returns. The graph pre-pass therefore never sees them. This matters because
  `getOwningDispatcherId` (`elkLayoutUtils.ts:561`) walks dispatcher-nesting fields (`graphData`,
  `loopData`, …) and does **not** follow `parentId` or `parentClusterRootId` — so `findGraphMemberOwner`
  cannot tell that a cluster element belongs inside a graph member. Left in place, the elements would
  stay in the outer arrays while their root moved into the frame, giving children a frame-relative
  position on top of an already-frame-relative parent.
- **`getMemberSize` must prefer the pre-pass size.** It currently measures a member from
  `node.measured ?? node.height` (`graphMemberPlacement.ts:27`) — the DOM, not `getDagreNodeSize` — so
  `data.clusterFrame` would never be consulted and the frame would be sized to the small card until a
  later layout corrected it. It becomes `data.clusterFrame ?? data.graphFrame ?? measured`, granting
  the pre-pass the same authority `getDagreNodeSize` already grants it at `layoutUtils.tsx:207`. The
  DOM fallback exists for nodes nobody has sized, not to override ones that have been.

No post-order walk over cluster frames is needed — cluster roots do not nest inside one another as
frames (§3, nested roots stay flat).

## 4. Untangling `clusterElementsCanvasOpen`

`clusterElementsCanvasOpen` stands in for two different questions — "the dialog is open" and "this node
is a cluster element" — which have been the same fact until now, because the only way to see a cluster
element was with the dialog open. Box mode separates them.

**No new abstraction is introduced.** At most sites the real predicate is already sitting beside the
flag as a redundant conjunct:

```ts
clusterElementsCanvasOpen && currentNode?.clusterElementType            // panel:464
clusterElementsCanvasOpen && isClusterElement                           // panel:765, 1034
clusterElementsCanvasOpen && currentComponentDefinition?.clusterElement
                          && currentNode?.parentClusterRootId            // panel:1480
clusterElementsCanvasOpen && (isMainRootClusterElement || isNestedClusterRoot)
                                                                        // WorkflowNode:692, 723
clusterElementsCanvasOpen && clusterElement                             // popover:132
clusterElementsCanvasOpen && clusterElementType                         // popover:336
```

The work sorts into four piles:

1. **Delete the redundant conjunct** (~10 sites, listed above plus `popover:441,519` and
   `panel:1411/1415`). Behaviour-preserving in dialog mode by construction — the removed term is
   implied by the one that remains — and correct in box mode for free.
2. **Leave alone** (~6 sites). These genuinely mean "the dialog is open" and must keep meaning it:
   `useLayout:879,911` (skip main-canvas layout while the dialog covers it — box mode *wants* it to
   run), `WorkflowEditorLayout:230,359` (mounting the dialog), `useWorkflowEditorLayout:54` (seeding
   `rootClusterElementNodeData`).
3. **Two paste guards get a per-node test.** `WorkflowEdge:184` and `PlaceholderNode:55` use the flag
   as a proxy for "this is a cluster element placeholder, don't offer paste". They become
   `!!data.clusterElementType` — one expression each, no helper. A canvas-wide flag cannot serve here:
   it would disable paste on every ordinary edge and placeholder in any workflow containing an agent.
4. **Three sites reach for the singular root.** `handleDeleteTask:278`,
   `useWorkflowNodeDetailsPanel:1377` and the `1411/1415` operation lookup use
   `rootClusterElementNodeData` to mean "the root we are working in". With several boxes on a canvas
   that has no answer, so they resolve the root from the node in hand. This is the only genuinely new
   logic in the area, and it is §2's map surfacing again rather than a flag problem.

Separately, **`WorkflowEditorLayout:347/374` inverts**: the root-level `WorkflowNodeDetailsPanel` (and
`DataPillPanel`) render today only when the dialog is closed *and* the current node is not a cluster
root. In box mode they must render for cluster roots and elements too. That is the user's "there is no
popup anymore", and it is required under any approach.

**Rejected alternative.** Setting the flag true whenever a box is on the canvas keeps the call sites
untouched but breaks the canvas twice over: pile 3 loses paste everywhere, and pile 2 stops
main-canvas layout from running at all. It is not a naming compromise.

## 5. The toggle

A `ToggleGroup` in `WorkflowEditorToolbar`, beside the layout-engine and direction controls, backed by
`useClusterElementsViewModeStore` persisted to `localStorage`. Behind a PostHog `ff-` flag so it ships
dark; with the flag off the toggle is absent and only dialog mode is reachable. The §4 split still
lands under the flag, so dialog mode is behaviour-preserving rather than untouched — the regression
bar in §8 is what holds that.

`useNodeClick`'s `setClusterElementsCanvasOpen(true)` on a cluster root becomes conditional on the
mode: dialog mode opens the dialog as now, box mode opens the details panel instead.

Switching modes with the details panel open keeps it open on the same node — the panel is the same
component either way.

## 6. Interactions in box mode

| Action | Behaviour |
|---|---|
| Click any node in the box | `WorkflowNodeDetails` opens — same panel, same tabs, no dialog |
| Drag element | Only when unlocked; clamped to non-negative frame coordinates; persists to `metadata.ui.nodePosition` via the existing `saveClusterElementNodesPosition` |
| Drag root | Never; pinned at the content origin |
| Lock / Reset | Box header; same semantics as the dialog's `nodesLocked` / `layoutResetCounter`, but scoped **per root** rather than per canvas |
| Add element | Existing placeholder nodes, unchanged; popover opens with the cluster-element tab shown and triggers hidden |
| Test agent | Opens the playground beside the canvas, not over it (§7) |
| Delete element | Existing path, with the root resolved from the node in hand (§4, pile 4) |
| Connect | Disabled inside the box, matching `nodesConnectable={false}` in the dialog — cluster edges are derived from the definition, never drawn |

Lock state starts locked, as the dialog does (`setNodesLocked(true)` on mount), and is not persisted.

## 7. Header controls: destinations and side panels

The box header carries the dialog header's row, each control shown under the same conditions as today
(`isAiAgentClusterRoot`, `isDataStreamClusterRoot`, `ff_4553`). But the controls are not alike, and
box mode has to treat them differently — the dialog's own layout already says which is which.

**Destinations — open full-screen, replace the canvas.** AI Agent editor, DataStream wizard, Evals.
Each opens the **same `ClusterElementsCanvasDialog` shell**, preset onto that surface
(`showAiAgentEditor` / `showDataStreamEditor` / `evalsPanelOpen`) and bypassing the dialog's canvas
view. Nothing is rebuilt: the dialog stops being the entry point to cluster editing and becomes a
destination for the surfaces that need the width. Closing returns to the canvas, not to the dialog's
canvas view.

**Side panels — open beside the canvas.** The **playground** (`AiAgentTestingPanel`, the ▶ Test agent
control) and Copilot. In the dialog these are already right-hand panels that shift left for the
details and Copilot panels (`ClusterElementsCanvasDialog:261`) — their value is being side-by-side
with the canvas you are editing, so making them destinations would defeat them.

On the main canvas that idiom already exists: `WorkflowTestChatPanel` and `CopilotPanel` mount at
`WorkflowEditorLayout:368` beside the canvas. The playground joins them there, so box mode gives
canvas-with-your-box on the left and agent chat on the right — the dialog's arrangement minus the
dialog. Its dialog-specific shift classes (`right-[465px]`, `right-[450px]`, `right-[915px]`) are
dropped in favour of the existing main-canvas panel arrangement; hardcoded offsets do not survive the
move.

Both kinds seed `rootClusterElementNodeData` from the box they were opened on (§2), which is what lets
these single-root surfaces stay unchanged inside.

## 8. Testing

**Unit** — `layoutClusterFrames`: frame sizing from children, honoured saved positions, children
stripped from and re-appended to the outer arrays, nested cluster roots flat. `getMemberSize`: prefers
`clusterFrame` / `graphFrame` over `measured`, falls back to `measured` for an unsized node — the
regression guard for a cluster root inside a graph member. `useClusterElementsViewModeStore`:
persistence and default.

**Component** — box renders root, elements and placeholders; clicking a member opens the details panel
and does *not* open the dialog; drag blocked when locked and persisted when unlocked; destination
buttons open their surface preset correctly; the playground opens beside the canvas rather than over
it, and coexists with the details panel.

**Regression** — dialog mode must behave identically. `ClusterElementsCanvasDialog.test.tsx`,
`useClusterElementsLayout.test.ts` and `clusterElementsUtils.test.ts` pass untouched; that is the
acceptance bar for the §4 split.

Client checks: `npm run check` from `client/` (allow ~10 minutes).

## 9. Out of scope

- Execution view and `mcp-apps/workflow-editor` viewers — they keep today's single-node rendering.
- Nested frames for nested cluster roots.
- Removing the dialog canvas. Collapsing the two canvases into one is the plausible endgame once box
  mode proves out, but it strands the AI Agent editor, the DataStream wizard and Evals, and it is not
  what was asked for here.
- Server changes. This is entirely client-side; `clusterElements` and `metadata.ui.nodePosition` are
  unchanged on the wire.

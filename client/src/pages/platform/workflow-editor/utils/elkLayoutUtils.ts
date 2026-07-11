import {
    LayoutDirectionType,
    PLACEHOLDER_NODE_HEIGHT,
    PLACEHOLDER_NODE_WIDTH,
    TRIGGER_PLACEHOLDER_NODE_ID,
} from '@/shared/constants';
import {NodeDataType} from '@/shared/types';
import {Edge, Node} from '@xyflow/react';

import {getCrossAxis} from './directionUtils';
import {ELK_FRAME_DISPATCHER_COMPONENT_NAMES} from './isElkLayoutSupported';
import {
    GetLayoutElementsProps,
    filterAndDedupeLayoutEdges,
    getDagreNodeSize,
    getLayoutElements,
    positionTriggerPlaceholder,
} from './layoutUtils';
import {applySavedPositions} from './postDagreConstraints';

import type {ElkExtendedEdge, ElkNode} from 'elkjs/lib/elk-api';

export const ELK_ROOT_ID = '__root__';

// Flow-axis rhythm, mirroring dagre's model: anchor nodes get a 100px footprint
// with the 72px icon centered inside (14px slack each side) and layers sit 52px
// apart. Node→node edges therefore read as 14+52+14 = 80px, while box-adjacent
// edges (condition→frame bar, bar→next node) read as 14+52 = 66px — symmetric
// around every frame and tight enough that the TRUE/FALSE labels sit 38px off
// the box instead of floating.
const ELK_LAYER_SPACING = 52;

const ANCHOR_MAIN_FOOTPRINT = 100;

// Cross-axis gap between sibling branch columns.
const ELK_SIBLING_SPACING = 50;

// The TRUE/FALSE case labels hang ~28px below a condition's icon, so the frame's
// top bar is pulled this much toward the condition (condition→bar gap becomes
// ELK_LAYER_SPACING + slack − pull) to keep the labels visually attached to the
// box instead of floating above it. Only the top side — the bottom edge keeps
// the standard box gap.
const TOP_BAR_LABEL_PULL = 28;

// Extra footprint below a bottom bar (bar pinned to the footprint start), so
// edges LEAVING a box read like node→node edges: bar→next node becomes
// extension + ELK_LAYER_SPACING + slack = 80, and nested→enclosing bottom-bar
// merge stubs become extension + ELK_LAYER_SPACING = 66 instead of a cramped 52.
const BOTTOM_BAR_EXIT_EXTENSION = 14;

// Size of a node's visual anchor: the 72px icon box whose edges carry the
// connection handles (see `w-[72px]` in TaskDispatcherTopGhostNode.tsx and the
// icon button in WorkflowNode.tsx, matching PLACEHOLDER_DOM_CROSS_SIZE in
// postDagreConstraints.ts). Ghost bars span the same 72px on the cross axis.
const NODE_ANCHOR_SIZE = 72;

// Main-axis size of a ghost bar's rendered hairline.
const GHOST_BAR_THICKNESS = 2;

// Cross-axis footprint of a case placeholder column: narrower than a full
// 240px task column so an empty condition frame renders as a compact box
// instead of one as wide as a fully populated frame.
const CASE_PLACEHOLDER_CROSS_FOOTPRINT = 200;

// The loop-back rail tick rendered by TaskDispatcherLeftGhostNode: a 2×16px
// element (`w-0.5 h-4` in TB) — the rail LINE itself is drawn by the edges
// running top ghost → left ghost → bottom ghost.
const LEFT_RAIL_TICK_SIZE = 16;

const LEFT_GHOST_ID_SUFFIX = '-taskDispatcher-left-ghost';

// Rail ring geometry: the rail aligns with the bar's LEFT END (straight left
// edge, clean corners), moving further left only when body content or nested
// rings require it.
const RAIL_CONTENT_PADDING = 20;
const RAIL_NESTED_RING_INDENT = 50;

// An EMPTY loop renders with the same proportions as an empty condition box:
// the "+" placeholder sits on the ring's right edge at +half-width and the
// rail mirrors it at −half-width, giving the same 250px-wide rounded box that
// empty condition branches produce (their case columns sit at the same offset).
const LOOP_RING_HALF_WIDTH = (CASE_PLACEHOLDER_CROSS_FOOTPRINT + ELK_SIBLING_SPACING) / 2;

const FRAME_ID_SUFFIX = '__frame';

export function getFrameId(dispatcherId: string): string {
    return `${dispatcherId}${FRAME_ID_SUFFIX}`;
}

function isFrameDispatcherNode(node: Node): boolean {
    const nodeData = node.data as NodeDataType;

    return nodeData.taskDispatcher === true && ELK_FRAME_DISPATCHER_COMPONENT_NAMES.includes(nodeData.componentName);
}

// Ghost bar ids embed the dispatcher kind: `<id>-condition-top-ghost`,
// `<id>-loop-bottom-ghost`, ...
function getGhostIds(dispatcherNode: Node): {bottomGhostId: string; topGhostId: string} {
    const componentName = (dispatcherNode.data as NodeDataType).componentName;

    return {
        bottomGhostId: `${dispatcherNode.id}-${componentName}-bottom-ghost`,
        topGhostId: `${dispatcherNode.id}-${componentName}-top-ghost`,
    };
}

// Crossing minimization is free to swap the two branch chains of a condition,
// which would put the caseFalse chain under the TRUE handle (and vice versa)
// while the handle sides stay fixed — forcing model order pins branches to the
// order they are emitted in (caseTrue before caseFalse, see buildScopeChildren).
const getElkLayoutOptions = (direction: LayoutDirectionType): Record<string, string> => ({
    'elk.algorithm': 'layered',
    'elk.direction': direction === 'TB' ? 'DOWN' : 'RIGHT',
    'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
    'elk.layered.crossingMinimization.forceNodeModelOrder': 'true',
    'elk.layered.spacing.nodeNodeBetweenLayers': String(ELK_LAYER_SPACING),
    'elk.padding': '[top=0,left=0,bottom=0,right=0]',
    'elk.spacing.nodeNode': String(ELK_SIBLING_SPACING),
});

// ELK centers nodes within a layer band on the flow axis, so a shallow frame
// sitting beside a deeper sibling subtree gets pushed down the band, inflating
// its condition→box gap. Aligning to the band's flow-axis start keeps every
// box gap uniform regardless of sibling depth.
const getChildAlignmentOptions = (direction: LayoutDirectionType): Record<string, string> => ({
    'elk.alignment': direction === 'TB' ? 'TOP' : 'LEFT',
});

/**
 * ELK footprint of a node. Cross-axis sizes come from the shared dagre size
 * function (they control how far apart parallel branch chains sit). MAIN-axis
 * footprints: anchor nodes (tasks/triggers/conditions) get ANCHOR_MAIN_FOOTPRINT
 * with the 72px icon centered inside; ghost bars and placeholders get exactly
 * their rendered DOM size. This yields the same visible rhythm at every nesting
 * depth: node→node edges of ELK_LAYER_SPACING + 2×slack, box-adjacent edges of
 * ELK_LAYER_SPACING + 1×slack — the consistency guarantee of the ELK engine.
 */
function getElkNodeSize(node: Node, direction: LayoutDirectionType): {height: number; width: number} {
    const {height, width} = getDagreNodeSize(node, direction);

    const isSmallNode = node.type === 'placeholder' || node.type === 'triggerPlaceholder';

    let mainAxisSize = ANCHOR_MAIN_FOOTPRINT;

    if (node.type === 'taskDispatcherTopGhostNode') {
        mainAxisSize = GHOST_BAR_THICKNESS;
    } else if (node.type === 'taskDispatcherBottomGhostNode') {
        mainAxisSize = GHOST_BAR_THICKNESS + BOTTOM_BAR_EXIT_EXTENSION;
    } else if (node.type === 'placeholder') {
        // DOM box is 28px tall but 72px wide (mx-[22px] margins around the "+"),
        // so the main-axis footprint differs by direction
        mainAxisSize = direction === 'TB' ? PLACEHOLDER_NODE_HEIGHT : NODE_ANCHOR_SIZE;
    } else if (isSmallNode) {
        mainAxisSize = direction === 'TB' ? height : width;
    }

    let crossAxisSize = direction === 'TB' ? width : height;

    if (node.type === 'placeholder') {
        crossAxisSize = CASE_PLACEHOLDER_CROSS_FOOTPRINT;
    }

    if (direction === 'TB') {
        return {height: mainAxisSize, width: crossAxisSize};
    }

    return {height: crossAxisSize, width: mainAxisSize};
}

/**
 * Returns the id of the dispatcher that owns this node inside its frame, or
 * undefined for root-scope nodes. Auxiliary nodes (ghost bars, rail ghosts,
 * placeholders) carry the owning dispatcher's id in taskDispatcherId, while a
 * dispatcher node itself carries its OWN id there and must stay OUTSIDE its
 * frame — hence the id inequality check. This must not rely on per-dispatcher
 * id fields (the loop bottom ghost has no loopId, only taskDispatcherId).
 * Child tasks — including nested dispatcher nodes — carry
 * conditionData.conditionId / loopData.loopId.
 */
function getOwningDispatcherId(node: Node): string | undefined {
    const nodeData = node.data as NodeDataType;

    if (nodeData.taskDispatcherId && nodeData.taskDispatcherId !== node.id) {
        return nodeData.taskDispatcherId;
    }

    return nodeData.conditionData?.conditionId || nodeData.loopData?.loopId;
}

/**
 * Builds a hierarchical ELK graph from the flat ReactFlow node/edge lists.
 * Each condition contributes a compound frame node (sibling of the condition
 * task node) containing the condition's ghosts, placeholders, child tasks and,
 * recursively, nested condition frames. Edges are remapped so that an endpoint
 * living inside a frame is represented by that frame at the deepest scope
 * common to both endpoints — no edge ever crosses a hierarchy boundary, so
 * ELK's default SEPARATE_CHILDREN handling lays out every frame interior as an
 * independent sub-graph with identical spacing options.
 */
export function buildElkGraph(nodes: Node[], edges: Edge[], direction: LayoutDirectionType): ElkNode {
    const nodesById = new Map(nodes.map((node) => [node.id, node]));

    const frameDispatcherIds = nodes.filter((node) => isFrameDispatcherNode(node)).map((node) => node.id);

    const frameDispatcherIdSet = new Set(frameDispatcherIds);

    // A node's owning dispatcher id may reference a dispatcher that no longer
    // exists in the node list (e.g. stale conditionData left behind after the
    // dispatcher itself was deleted). Falling back to the root scope here
    // guarantees the node still gets an ELK box and a laid-out position
    // instead of silently keeping its stale coordinates.
    const getScope = (nodeId: string): string => {
        const node = nodesById.get(nodeId);

        if (!node) {
            return ELK_ROOT_ID;
        }

        const owningDispatcherId = getOwningDispatcherId(node);

        if (!owningDispatcherId || !frameDispatcherIdSet.has(owningDispatcherId)) {
            return ELK_ROOT_ID;
        }

        return owningDispatcherId;
    };

    // Scope chain from a scope up to the root, e.g. ['condition_2', 'condition_1', '__root__']
    const getScopeChain = (scope: string): string[] => {
        const chain = [scope];

        // Guards against cyclic ownership from malformed state (e.g. duplicate node names
        // producing conditions that reference each other as their owning scope): once a scope
        // is seen twice we stop walking and fall back to the root scope instead of looping forever.
        const visitedScopes = new Set([scope]);

        let currentScope = scope;

        while (currentScope !== ELK_ROOT_ID) {
            currentScope = getScope(currentScope);

            if (visitedScopes.has(currentScope)) {
                chain.push(ELK_ROOT_ID);

                break;
            }

            visitedScopes.add(currentScope);

            chain.push(currentScope);
        }

        return chain;
    };

    const getCommonScope = (sourceScope: string, targetScope: string): string => {
        const targetChainScopes = new Set(getScopeChain(targetScope));

        return getScopeChain(sourceScope).find((scope) => targetChainScopes.has(scope)) || ELK_ROOT_ID;
    };

    // Representative of a node at a given (ancestor) scope: the node itself when it
    // lives directly in that scope, otherwise the frame of its topmost enclosing
    // dispatcher below that scope.
    const getRepresentativeInScope = (nodeId: string, scope: string): string => {
        if (getScope(nodeId) === scope) {
            return nodeId;
        }

        let enclosingDispatcherId = getScope(nodeId);

        // Guards against cyclic ownership from malformed state (e.g. duplicate node names
        // producing dispatchers that reference each other as their owning scope): once a scope
        // is seen twice we stop walking and represent the last valid dispatcher reached instead
        // of looping forever.
        const visitedScopes = new Set([enclosingDispatcherId]);

        while (getScope(enclosingDispatcherId) !== scope) {
            const nextScope = getScope(enclosingDispatcherId);

            if (visitedScopes.has(nextScope)) {
                break;
            }

            visitedScopes.add(nextScope);

            enclosingDispatcherId = nextScope;
        }

        return getFrameId(enclosingDispatcherId);
    };

    const elkEdgesByScope = new Map<string, ElkExtendedEdge[]>();
    const seenEdgeKeys = new Set<string>();

    edges.forEach((currentEdge) => {
        if (!nodesById.has(currentEdge.source) || !nodesById.has(currentEdge.target)) {
            return;
        }

        // Loop-back rail ghosts are decorations positioned by a post-layout fixup
        // (dagre parity: constrainLeftGhostPositions) — they are not part of the
        // ELK graph, so their edges must not be either
        if (currentEdge.source.endsWith(LEFT_GHOST_ID_SUFFIX) || currentEdge.target.endsWith(LEFT_GHOST_ID_SUFFIX)) {
            return;
        }

        const commonScope = getCommonScope(getScope(currentEdge.source), getScope(currentEdge.target));

        const sourceRepresentative = getRepresentativeInScope(currentEdge.source, commonScope);
        const targetRepresentative = getRepresentativeInScope(currentEdge.target, commonScope);

        if (sourceRepresentative === targetRepresentative) {
            return;
        }

        const edgeKey = `${sourceRepresentative}=>${targetRepresentative}`;

        if (seenEdgeKeys.has(edgeKey)) {
            return;
        }

        seenEdgeKeys.add(edgeKey);

        const scopeEdges = elkEdgesByScope.get(commonScope) || [];

        scopeEdges.push({id: `elk-edge-${edgeKey}`, sources: [sourceRepresentative], targets: [targetRepresentative]});

        elkEdgesByScope.set(commonScope, scopeEdges);
    });

    // Model order is load-bearing: forceNodeModelOrder pins branch sides to the
    // order members are emitted in, so caseTrue members must precede caseFalse
    // members regardless of their order in the flat node array (a lone FALSE
    // placeholder is created before the TRUE chain tasks, for example). Ghosts
    // rank first; a nested condition's frame inherits its condition's branch.
    const getConditionCaseRank = (node: Node | undefined): number => {
        if (!node) {
            return -1;
        }

        const nodeData = node.data as NodeDataType;
        const conditionCase = nodeData.conditionCase || nodeData.conditionData?.conditionCase;

        if (conditionCase === 'caseTrue') {
            return 0;
        }

        if (conditionCase === 'caseFalse') {
            return 1;
        }

        return -1;
    };

    const buildScopeChildren = (scope: string): ElkNode[] => {
        const memberEntries: Array<{caseRank: number; child: ElkNode}> = [];

        nodes.forEach((node) => {
            if (getScope(node.id) !== scope || node.type === 'taskDispatcherLeftGhostNode') {
                return;
            }

            const {height, width} = getElkNodeSize(node, direction);

            memberEntries.push({
                caseRank: getConditionCaseRank(node),
                child: {height, id: node.id, layoutOptions: getChildAlignmentOptions(direction), width},
            });
        });

        frameDispatcherIds.forEach((dispatcherId) => {
            if (getScope(dispatcherId) !== scope) {
                return;
            }

            memberEntries.push({
                caseRank: getConditionCaseRank(nodesById.get(dispatcherId)),
                child: {
                    children: buildScopeChildren(dispatcherId),
                    edges: elkEdgesByScope.get(dispatcherId) || [],
                    id: getFrameId(dispatcherId),
                    layoutOptions: {...getElkLayoutOptions(direction), ...getChildAlignmentOptions(direction)},
                },
            });
        });

        // Array.prototype.sort is stable, so within a branch the chain order is preserved
        memberEntries.sort((firstEntry, secondEntry) => firstEntry.caseRank - secondEntry.caseRank);

        return memberEntries.map((memberEntry) => memberEntry.child);
    };

    return {
        children: buildScopeChildren(ELK_ROOT_ID),
        edges: elkEdgesByScope.get(ELK_ROOT_ID) || [],
        id: ELK_ROOT_ID,
        layoutOptions: getElkLayoutOptions(direction),
    };
}

type ElkInstanceType = {layout: (graph: ElkNode) => Promise<ElkNode>};

let elkInstance: ElkInstanceType | null = null;

const loadElk = async (): Promise<ElkInstanceType> => {
    if (!elkInstance) {
        const {default: ELK} = await import('elkjs/lib/elk.bundled.js');

        elkInstance = new ELK() as unknown as ElkInstanceType;
    }

    return elkInstance;
};

/**
 * Approximate rendered ANCHOR box of a node: the 72px icon square whose center
 * carries the edge handles (see the `left: 36px` handle offset in
 * WorkflowNode.tsx and `ghost.x = condition.x` in
 * constrainConditionGhostsCrossAxis), not the full visual footprint. ELK is fed
 * footprint sizes (shared with dagre via getDagreNodeSize); this anchor box is
 * centered inside that footprint on both axes when converting to ReactFlow's
 * top-left positions, so handle lines line up across node types (regular
 * nodes, triggers, ghosts) regardless of how wide/tall each type's footprint
 * reservation is.
 */
function getRenderedNodeSize(node: Node, direction: LayoutDirectionType): {height: number; width: number} {
    const isGhostNode = node.type === 'taskDispatcherTopGhostNode' || node.type === 'taskDispatcherBottomGhostNode';
    const isSmallNode = node.type === 'placeholder' || node.type === 'triggerPlaceholder';

    if (direction === 'LR') {
        if (isGhostNode) {
            return {height: NODE_ANCHOR_SIZE, width: GHOST_BAR_THICKNESS};
        }

        if (node.type === 'taskDispatcherLeftGhostNode') {
            // `h-0.5 w-4` in TaskDispatcherLeftGhostNode.tsx
            return {height: GHOST_BAR_THICKNESS, width: LEFT_RAIL_TICK_SIZE};
        }

        if (node.type === 'placeholder') {
            // The 28px "+" square renders with mx-[22px] margins (PlaceholderNode.tsx),
            // so the node's DOM box is 72px wide with the "+" at its center
            return {height: PLACEHOLDER_NODE_HEIGHT, width: NODE_ANCHOR_SIZE};
        }

        if (isSmallNode) {
            return {height: PLACEHOLDER_NODE_HEIGHT, width: PLACEHOLDER_NODE_WIDTH};
        }

        return {height: NODE_ANCHOR_SIZE, width: NODE_ANCHOR_SIZE};
    }

    if (isGhostNode) {
        return {height: GHOST_BAR_THICKNESS, width: NODE_ANCHOR_SIZE};
    }

    if (node.type === 'taskDispatcherLeftGhostNode') {
        // `h-4 w-0.5` in TaskDispatcherLeftGhostNode.tsx
        return {height: LEFT_RAIL_TICK_SIZE, width: GHOST_BAR_THICKNESS};
    }

    if (node.type === 'placeholder') {
        // See LR branch: the placeholder's DOM box is 72px wide (mx-[22px] margins)
        return {height: PLACEHOLDER_NODE_HEIGHT, width: NODE_ANCHOR_SIZE};
    }

    if (isSmallNode) {
        return {height: PLACEHOLDER_NODE_HEIGHT, width: PLACEHOLDER_NODE_WIDTH};
    }

    return {height: NODE_ANCHOR_SIZE, width: NODE_ANCHOR_SIZE};
}

type AbsoluteBoxType = {height: number; width: number; x: number; y: number};

/**
 * Drop-in alternative to getLayoutElements() backed by ELK's hierarchical
 * layered layout. Positions only — node/edge creation is untouched. Falls back
 * to the dagre path if ELK fails for any reason.
 */
export const getElkLayoutElements = async ({
    canvasHeight,
    canvasWidth,
    direction = 'TB',
    edges,
    nodes,
    savedPositionCrossAxisShift = 0,
}: GetLayoutElementsProps): Promise<{edges: Edge[]; nodes: Node[]}> => {
    try {
        const elk = await loadElk();

        const layoutedGraph = await elk.layout(buildElkGraph(nodes, edges, direction));

        const frameDispatcherKindById = new Map(
            nodes
                .filter((node) => isFrameDispatcherNode(node))
                .map((node) => [node.id, (node.data as NodeDataType).componentName])
        );

        // Flatten ELK's parent-relative coordinates to absolute footprint boxes
        const absoluteBoxes = new Map<string, AbsoluteBoxType>();

        const flattenElkNode = (elkNode: ElkNode, offsetX: number, offsetY: number): void => {
            (elkNode.children || []).forEach((child) => {
                let absoluteX = offsetX + (child.x || 0);
                let absoluteY = offsetY + (child.y || 0);

                // ELK anchors the dispatcher→frame edge anywhere along the wide frame
                // boundary, so a straight edge does not imply aligned centers. Shift each
                // frame (and thereby its whole subtree) so the dispatcher node sits midway
                // between its branch ENTRY axes — with branches of unequal width, the
                // frame's bounding-box center drifts toward the wider subtree, so the box
                // center is only the fallback anchor. A loop's rail ghost is not an entry
                // (the loop body must center under the loop node, rail hanging left). The
                // dispatcher is flattened before its frame because nodes precede frames
                // within a rank in buildScopeChildren's member order.
                if (child.id.endsWith(FRAME_ID_SUFFIX)) {
                    const dispatcherId = child.id.slice(0, -FRAME_ID_SUFFIX.length);
                    const dispatcherBox = absoluteBoxes.get(dispatcherId);
                    const dispatcherKind = frameDispatcherKindById.get(dispatcherId);

                    if (dispatcherBox && dispatcherKind) {
                        const dispatcherCenter =
                            direction === 'TB'
                                ? dispatcherBox.x + dispatcherBox.width / 2
                                : dispatcherBox.y + dispatcherBox.height / 2;

                        const topGhostId = `${dispatcherId}-${dispatcherKind}-top-ghost`;
                        const branchEntryCenters: number[] = [];

                        (child.edges || []).forEach((frameEdge) => {
                            if (!(frameEdge.sources || []).includes(topGhostId)) {
                                return;
                            }

                            (frameEdge.targets || []).forEach((entryId) => {
                                if (entryId.endsWith(LEFT_GHOST_ID_SUFFIX)) {
                                    return;
                                }

                                const entryChild = (child.children || []).find(
                                    (frameChild) => frameChild.id === entryId
                                );

                                if (!entryChild) {
                                    return;
                                }

                                branchEntryCenters.push(
                                    direction === 'TB'
                                        ? (entryChild.x || 0) + (entryChild.width || 0) / 2
                                        : (entryChild.y || 0) + (entryChild.height || 0) / 2
                                );
                            });
                        });

                        const frameAnchor =
                            branchEntryCenters.length > 0
                                ? branchEntryCenters.reduce((sum, entryCenter) => sum + entryCenter, 0) /
                                  branchEntryCenters.length
                                : (direction === 'TB' ? child.width || 0 : child.height || 0) / 2;

                        if (direction === 'TB') {
                            absoluteX += dispatcherCenter - absoluteX - frameAnchor;
                        } else {
                            absoluteY += dispatcherCenter - absoluteY - frameAnchor;
                        }
                    }
                }

                absoluteBoxes.set(child.id, {
                    height: child.height || 0,
                    width: child.width || 0,
                    x: absoluteX,
                    y: absoluteY,
                });

                flattenElkNode(child, absoluteX, absoluteY);
            });
        };

        flattenElkNode(layoutedGraph, 0, 0);

        // Canvas centering: put the trigger row midpoint on the canvas cross-axis center
        const crossAxis = getCrossAxis(direction);
        const canvasCrossDimension = direction === 'LR' && canvasHeight ? canvasHeight : canvasWidth;

        const entryCenters = nodes
            .filter((node) => (node.data as NodeDataType).trigger === true && node.id !== TRIGGER_PLACEHOLDER_NODE_ID)
            .map((node) => {
                const box = absoluteBoxes.get(node.id);

                if (!box) {
                    return canvasCrossDimension / 2;
                }

                return crossAxis === 'x' ? box.x + box.width / 2 : box.y + box.height / 2;
            });

        const entryAnchor =
            entryCenters.length > 0
                ? (Math.min(...entryCenters) + Math.max(...entryCenters)) / 2
                : canvasCrossDimension / 2;

        const centeringOffset = canvasCrossDimension / 2 - entryAnchor;

        // Convert footprint boxes to rendered top-left positions
        const allNodes: Node[] = nodes.map((node) => {
            const box = absoluteBoxes.get(node.id);

            if (!box) {
                return node;
            }

            const renderedSize = getRenderedNodeSize(node, direction);

            const position = {
                x: box.x + (box.width - renderedSize.width) / 2,
                y: box.y + (box.height - renderedSize.height) / 2,
            };

            // A bottom bar's footprint carries BOTTOM_BAR_EXIT_EXTENSION below the
            // bar; pin the bar to the footprint start so the extension lengthens
            // the exit edge instead of splitting around the bar.
            if (node.type === 'taskDispatcherBottomGhostNode') {
                const mainAxis = crossAxis === 'x' ? 'y' : 'x';

                position[mainAxis] = box[mainAxis];
            }

            position[crossAxis] += centeringOffset;

            return {...node, position};
        });

        // Deterministic fixup: center each frame dispatcher's ghost bars on the
        // dispatcher node's own rendered cross-axis center. ELK's frame box is
        // sized to the widest branch, and the dispatcher node itself lives
        // OUTSIDE that frame (see buildElkGraph), so ELK has no reason to line
        // the ghosts up with it — this pins the bars exactly under/over the node.
        nodes.forEach((node) => {
            if (!isFrameDispatcherNode(node)) {
                return;
            }

            const dispatcherNode = allNodes.find((candidateNode) => candidateNode.id === node.id);

            if (!dispatcherNode) {
                return;
            }

            const {bottomGhostId, topGhostId} = getGhostIds(node);

            const dispatcherRenderedSize = getRenderedNodeSize(node, direction);
            const dispatcherCrossCenter =
                dispatcherNode.position[crossAxis] +
                (crossAxis === 'x' ? dispatcherRenderedSize.width : dispatcherRenderedSize.height) / 2;

            [topGhostId, bottomGhostId].forEach((ghostId) => {
                const ghostNode = allNodes.find((candidateNode) => candidateNode.id === ghostId);

                if (!ghostNode) {
                    return;
                }

                const ghostRenderedSize = getRenderedNodeSize(ghostNode, direction);
                const ghostCrossSize = crossAxis === 'x' ? ghostRenderedSize.width : ghostRenderedSize.height;

                ghostNode.position = {
                    ...ghostNode.position,
                    [crossAxis]: dispatcherCrossCenter - ghostCrossSize / 2,
                };
            });

            const mainAxis = crossAxis === 'x' ? 'y' : 'x';

            const topGhostNode = allNodes.find((candidateNode) => candidateNode.id === topGhostId);
            const bottomGhostNode = allNodes.find((candidateNode) => candidateNode.id === bottomGhostId);

            if (!topGhostNode || !bottomGhostNode) {
                return;
            }

            // Pull the box's top bar toward the dispatcher so the box reads as
            // attached to its node (and, for conditions, so the TRUE/FALSE labels
            // sit on the box edge instead of floating). TB only: in LR the labels
            // extend 64px along the MAIN axis toward the box (`-right-16` in
            // WorkflowNode.tsx), so pulling would run the box edge through them.
            if (direction === 'TB') {
                topGhostNode.position = {
                    ...topGhostNode.position,
                    [mainAxis]: topGhostNode.position[mainAxis] - TOP_BAR_LABEL_PULL,
                };
            }

            // Center the dispatcher's aux members — empty-branch placeholders and
            // the loop-back rail tick — midway between the two ghost bars on the
            // main axis (dagre parity: centerDispatcherPlaceholdersOnMainAxis).
            // ELK's layering otherwise parks them at whatever layer the sibling
            // chain's depth dictates.
            const frameMainCenter =
                (topGhostNode.position[mainAxis] + bottomGhostNode.position[mainAxis] + GHOST_BAR_THICKNESS) / 2;

            allNodes.forEach((candidateNode) => {
                const candidateData = candidateNode.data as NodeDataType;

                const isCenterableAuxNode =
                    candidateNode.type === 'placeholder' || candidateNode.type === 'taskDispatcherLeftGhostNode';

                if (!isCenterableAuxNode || candidateData.taskDispatcherId !== node.id) {
                    return;
                }

                const auxRenderedSize = getRenderedNodeSize(candidateNode, direction);
                const auxMainSize = mainAxis === 'x' ? auxRenderedSize.width : auxRenderedSize.height;

                const auxPosition: {x: number; y: number} = {
                    ...candidateNode.position,
                    [mainAxis]: frameMainCenter - auxMainSize / 2,
                };

                // A loop's "+" placeholder sits ON the ring's right edge at the same
                // offset as an empty condition's case column, so the edge runs
                // straight through it and empty loops match empty condition boxes
                if (candidateNode.type === 'placeholder' && (node.data as NodeDataType).componentName === 'loop') {
                    const auxCrossSize = crossAxis === 'x' ? auxRenderedSize.width : auxRenderedSize.height;

                    auxPosition[crossAxis] = dispatcherCrossCenter + LOOP_RING_HALF_WIDTH - auxCrossSize / 2;
                }

                candidateNode.position = auxPosition;
            });
        });

        // Loop-back rails are decorations excluded from the ELK graph; position
        // them per dagre's constrainLeftGhostPositions: at least the base ring
        // width left of the top bar, further left if the body content or nested
        // rails require it. Innermost-first so nested rings indent outward.
        const nodesById = new Map(nodes.map((node) => [node.id, node]));

        const isDescendantOfDispatcher = (candidateNode: Node, dispatcherId: string): boolean => {
            const visitedOwnerIds = new Set<string>();

            let currentOwnerId = getOwningDispatcherId(candidateNode);

            while (currentOwnerId && !visitedOwnerIds.has(currentOwnerId)) {
                if (currentOwnerId === dispatcherId) {
                    return true;
                }

                visitedOwnerIds.add(currentOwnerId);

                const ownerNode = nodesById.get(currentOwnerId);

                if (!ownerNode) {
                    return false;
                }

                currentOwnerId = getOwningDispatcherId(ownerNode);
            }

            return false;
        };

        const railNodes = allNodes.filter((candidateNode) => candidateNode.type === 'taskDispatcherLeftGhostNode');

        const descendantIdsByRailId = new Map<string, Set<string>>();

        railNodes.forEach((railNode) => {
            const railDispatcherId = (railNode.data as NodeDataType).taskDispatcherId;
            const descendantIds = new Set<string>();

            if (railDispatcherId) {
                allNodes.forEach((candidateNode) => {
                    if (candidateNode.id !== railNode.id && isDescendantOfDispatcher(candidateNode, railDispatcherId)) {
                        descendantIds.add(candidateNode.id);
                    }
                });
            }

            descendantIdsByRailId.set(railNode.id, descendantIds);
        });

        railNodes.sort(
            (firstRail, secondRail) =>
                (descendantIdsByRailId.get(firstRail.id)?.size || 0) -
                (descendantIdsByRailId.get(secondRail.id)?.size || 0)
        );

        railNodes.forEach((railNode) => {
            const railDispatcherId = (railNode.data as NodeDataType).taskDispatcherId;

            if (!railDispatcherId) {
                return;
            }

            const dispatcherKind = frameDispatcherKindById.get(railDispatcherId);
            const topBarNode = allNodes.find(
                (candidateNode) => candidateNode.id === `${railDispatcherId}-${dispatcherKind}-top-ghost`
            );

            if (!topBarNode) {
                return;
            }

            const descendantIds = descendantIdsByRailId.get(railNode.id) || new Set<string>();

            let leftmostContentCross = Infinity;
            let leftmostChildRailCross = Infinity;

            allNodes.forEach((candidateNode) => {
                if (!descendantIds.has(candidateNode.id)) {
                    return;
                }

                if (candidateNode.type === 'taskDispatcherLeftGhostNode') {
                    leftmostChildRailCross = Math.min(leftmostChildRailCross, candidateNode.position[crossAxis]);
                } else if (
                    candidateNode.type !== 'taskDispatcherTopGhostNode' &&
                    candidateNode.type !== 'taskDispatcherBottomGhostNode'
                ) {
                    // Ghost bars span the anchor width from the bar-aligned rail
                    // position by construction — only real body content pushes the
                    // rail further out
                    leftmostContentCross = Math.min(leftmostContentCross, candidateNode.position[crossAxis]);
                }
            });

            // Bar-left-end alignment gives a straight left edge with clean corners;
            // body content pushes the rail out by its hug padding, nested rings by
            // their indent, and an empty loop mirrors its "+" placeholder so the
            // ring matches an empty condition box's proportions
            const barAlignedCross = topBarNode.position[crossAxis];
            const contentRequired =
                leftmostContentCross === Infinity ? Infinity : leftmostContentCross - RAIL_CONTENT_PADDING;
            const nestingRequired =
                leftmostChildRailCross === Infinity ? Infinity : leftmostChildRailCross - RAIL_NESTED_RING_INDENT;

            const railRenderedSize = getRenderedNodeSize(railNode, direction);
            const railCrossSize = crossAxis === 'x' ? railRenderedSize.width : railRenderedSize.height;

            const hasOwnPlaceholder = allNodes.some(
                (candidateNode) =>
                    candidateNode.type === 'placeholder' &&
                    (candidateNode.data as NodeDataType).taskDispatcherId === railDispatcherId
            );

            const dispatcherCenter = topBarNode.position[crossAxis] + NODE_ANCHOR_SIZE / 2;

            const emptyRingMirror = hasOwnPlaceholder
                ? dispatcherCenter - LOOP_RING_HALF_WIDTH - railCrossSize / 2
                : Infinity;

            railNode.position = {
                ...railNode.position,
                [crossAxis]: Math.min(barAlignedCross, contentRequired, nestingRequired, emptyRingMirror),
            };
        });

        // A trailing "+" placeholder fed by a dispatcher's bottom ghost was aligned
        // by ELK against the frame's PRE-shift box, so the entry-axis frame shift
        // leaves it off the chain — pin it back onto the bottom bar's axis.
        edges.forEach((currentEdge) => {
            if (!currentEdge.source.endsWith('-bottom-ghost')) {
                return;
            }

            const targetNode = allNodes.find((candidateNode) => candidateNode.id === currentEdge.target);

            const targetData = targetNode?.data as NodeDataType | undefined;

            if (!targetNode || targetNode.type !== 'placeholder' || targetData?.taskDispatcherId) {
                return;
            }

            const barNode = allNodes.find((candidateNode) => candidateNode.id === currentEdge.source);

            if (!barNode) {
                return;
            }

            const barRenderedSize = getRenderedNodeSize(barNode, direction);
            const barCrossCenter =
                barNode.position[crossAxis] + (crossAxis === 'x' ? barRenderedSize.width : barRenderedSize.height) / 2;

            const targetRenderedSize = getRenderedNodeSize(targetNode, direction);
            const targetCrossSize = crossAxis === 'x' ? targetRenderedSize.width : targetRenderedSize.height;

            targetNode.position = {
                ...targetNode.position,
                [crossAxis]: barCrossCenter - targetCrossSize / 2,
            };
        });

        positionTriggerPlaceholder(allNodes, direction);

        // applySavedPositions also propagates a moved dispatcher's delta to its
        // ghosts, placeholders, and children (iteratively, handling nesting), so a
        // dispatcher with a saved position carries its whole frame rigidly with it.
        applySavedPositions(allNodes, crossAxis, savedPositionCrossAxisShift);

        return {edges: filterAndDedupeLayoutEdges(allNodes, edges), nodes: allNodes};
    } catch (error) {
        console.error('ELK layout failed, falling back to dagre', error);

        return getLayoutElements({canvasHeight, canvasWidth, direction, edges, nodes, savedPositionCrossAxisShift});
    }
};

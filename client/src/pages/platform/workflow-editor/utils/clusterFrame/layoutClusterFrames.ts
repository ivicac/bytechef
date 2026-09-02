import {calculateNodeWidth} from '@/pages/platform/cluster-element-editor/utils/clusterElementsUtils';
import {NODE_HEIGHT} from '@/shared/constants';
import {NodeDataType} from '@/shared/types';
import {Edge, Node} from '@xyflow/react';

import {
    CLUSTER_FRAME_HEADER_HEIGHT,
    ClusterFrameContentOriginI,
    ClusterMemberBoxI,
    computeClusterFrameContentOrigin,
    computeClusterFrameSize,
    getClusterMemberSize,
    toClusterFrameChildPosition,
} from './clusterFrameGeometry';
import {placeClusterMembers} from './placeClusterMembers';

/**
 * Members are clamped out of the header band but otherwise free to move anywhere inside the box,
 * which grows to fit them — nothing bounds the right or bottom edge, by design. The left bound is
 * the box's own edge rather than the content origin, so a member the placer put left of the root
 * card can still be dragged back to where it started.
 */
const CLUSTER_FRAME_MEMBER_EXTENT: [[number, number], [number, number]] = [
    [0, CLUSTER_FRAME_HEADER_HEIGHT],
    [Infinity, Infinity],
];

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
 * Content-space position of every member, i.e. relative to the ROOT CARD rather than to each
 * member's own immediate parent.
 *
 * A member one level down is positioned against the root card, but a member of a NESTED cluster root
 * is positioned against that nested root — `metadata.ui.nodePosition` is always relative to the
 * immediate parent. Sizing the box needs all of them in one space, so the parent chain is summed
 * here. `createClusterElementsNodes` emits parents before their children, which is what lets a
 * single forward pass resolve every depth.
 */
function collectContentPositions(memberNodes: Node[], clusterRootId: string): Map<string, {x: number; y: number}> {
    const contentPositions = new Map<string, {x: number; y: number}>();

    for (const memberNode of memberNodes) {
        const parentPosition =
            memberNode.parentId === clusterRootId ? {x: 0, y: 0} : contentPositions.get(memberNode.parentId ?? '');

        contentPositions.set(memberNode.id, {
            x: (parentPosition?.x ?? 0) + memberNode.position.x,
            y: (parentPosition?.y ?? 0) + memberNode.position.y,
        });
    }

    return contentPositions;
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
 * Positions ARE recomputed here, by the same cluster placer the dialog runs (`placeClusterMembers`):
 * an element arrives carrying only its stored `metadata.ui.nodePosition`, which is `{x: 0, y: 0}` for
 * every element nobody has dragged yet.
 *
 * Only DIRECT members cross into frame coordinates. A nested root's own children are positioned
 * against that nested root, which is itself a direct member already carrying the offset — adding it
 * again would double-count the header band, and (because the same coordinates are persisted on drag)
 * would silently rewrite stored positions the dialog then reads back in a different frame.
 */
export function layoutClusterFrames(
    nodes: Node[],
    edges: Edge[],
    elementsByRootId: {edgesByRootId: Record<string, Edge[]>; nodesByRootId: Record<string, Node[]>},
    lockedByRootId: Record<string, boolean>
): LayoutClusterFramesResultI {
    const {edgesByRootId, nodesByRootId} = elementsByRootId;

    const memberNodes: Node[] = [];
    const memberEdges: Edge[] = [];

    const outerNodes = nodes.map((node) => {
        const elementNodes = nodesByRootId[node.id];

        if (!elementNodes?.length) {
            return node;
        }

        const nodeData = node.data as NodeDataType;
        const rootEdges = edgesByRootId[node.id] ?? [];

        const placedElementNodes = placeClusterMembers({
            clusterRootId: node.id,
            edges: rootEdges,
            memberNodes: elementNodes,
            rootClusterElements: nodeData.clusterElements,
        });

        const contentPositions = collectContentPositions(placedElementNodes, node.id);

        // The root's own data does not carry its type count at this point, but the placer emits at
        // least one direct member per filtered element type -- a placeholder for an empty or multi
        // type, the element itself for a filled single one -- so the distinct types among the direct
        // members ARE the count the card is drawn from.
        const clusterElementTypesCount = new Set(
            placedElementNodes
                .filter((elementNode) => elementNode.parentId === node.id)
                .map((elementNode) => (elementNode.data as {clusterElementType?: string}).clusterElementType)
                .filter((clusterElementType): clusterElementType is string => !!clusterElementType)
        ).size;

        // The root card is part of the box's contents too, so the frame has to contain it even when
        // every member sits well inside its footprint.
        const childBoxes: ClusterMemberBoxI[] = [
            // At the card's REAL width: calculateNodeWidth grows it with the number of element types,
            // and reserving the fixed CLUSTER_ROOT_NODE_WIDTH instead left a five-type agent's card
            // running past the box it was supposed to sit inside.
            {
                height: NODE_HEIGHT,
                width: calculateNodeWidth(clusterElementTypesCount),
                x: 0,
                y: 0,
            },
            ...placedElementNodes.map((elementNode) => {
                const contentPosition = contentPositions.get(elementNode.id) ?? elementNode.position;

                return {
                    ...getClusterMemberSize(elementNode),
                    x: contentPosition.x,
                    y: contentPosition.y,
                };
            }),
        ];

        const frameSize = computeClusterFrameSize(childBoxes);
        const contentOrigin: ClusterFrameContentOriginI = computeClusterFrameContentOrigin(childBoxes, frameSize.width);

        // Members are draggable ONLY when their root is unlocked, and independently of the canvas-wide
        // drag lock — the same per-node override graph members and sticky notes use. A placeholder
        // ("+") node is excluded: it carries no `parentClusterRootId`, so a drag on it would fall
        // through the drag-stop handler's cluster branch into the generic outer-flow one and fire a
        // save whose position keys no real task name.
        const isLocked = lockedByRootId[node.id] !== false;

        const positionedElementNodes = placedElementNodes.map((elementNode) => ({
            ...elementNode,
            connectable: false,
            draggable: !isLocked && elementNode.type !== 'placeholder',
            extent: CLUSTER_FRAME_MEMBER_EXTENT,
            position:
                elementNode.parentId === node.id
                    ? toClusterFrameChildPosition(elementNode.position, contentOrigin)
                    : elementNode.position,
        }));

        memberNodes.push(...positionedElementNodes);
        memberEdges.push(...rootEdges);

        return {
            ...node,
            data: {
                ...nodeData,
                clusterFrame: {
                    clusterRootId: node.id,
                    contentOrigin,
                    height: frameSize.height,
                    width: frameSize.width,
                },
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

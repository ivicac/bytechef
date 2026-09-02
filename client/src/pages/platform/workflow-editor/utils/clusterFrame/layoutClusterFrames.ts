import {NodeDataType} from '@/shared/types';
import {Edge, Node} from '@xyflow/react';

import {
    CLUSTER_FRAME_HEADER_HEIGHT,
    ClusterMemberBoxI,
    computeClusterFrameSize,
    toClusterFrameChildPosition,
} from './clusterFrameGeometry';

/**
 * Members are clamped out of the header band but otherwise free to move anywhere inside the box,
 * which grows to fit them — nothing bounds the right or bottom edge, by design.
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

        // Members are draggable ONLY when their root is unlocked, and independently of the canvas-wide
        // drag lock — the same per-node override graph members and sticky notes use. A placeholder
        // ("+") node is excluded: it carries no `parentClusterRootId`, so a drag on it would fall
        // through the drag-stop handler's cluster branch into the generic outer-flow one and fire a
        // save whose position keys no real task name.
        const isLocked = lockedByRootId[node.id] !== false;

        const positionedElementNodes = elementNodes.map((elementNode) => ({
            ...elementNode,
            connectable: false,
            draggable: !isLocked && elementNode.type !== 'placeholder',
            extent: CLUSTER_FRAME_MEMBER_EXTENT,
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

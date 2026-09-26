import {Edge, Node} from '@xyflow/react';

const TOP_GHOST_PATTERN = /-(parallel|forkJoin)-top-ghost$/;

const PLACEHOLDER_NODE_TYPES = ['placeholder', 'readonlyPlaceholder'];

/**
 * Removes the add-a-branch "+" of every parallel and fork-join, with the two edges that make it the
 * right side of the frame. The editor offers adding a branch from a chip on the last lane instead
 * (AddBranchChip), and a read-only canvas offers nothing. The frame then holds only its real lanes,
 * so the engine centres the dispatcher over them. A single lane keeps its box: it hangs from the
 * bar's right end opposite the left rail, the way a one-task each does.
 *
 * A dispatcher without lanes keeps its "+", which is then the only thing closing its empty frame.
 */
export default function removeTrailingBranchPlaceholders(nodes: Node[], edges: Edge[]): {edges: Edge[]; nodes: Node[]} {
    const nodesById = new Map(nodes.map((node) => [node.id, node]));

    const removedNodeIds = new Set<string>();

    edges.forEach((edge) => {
        if (!TOP_GHOST_PATTERN.test(edge.source) || edge.sourceHandle !== `${edge.source}-right`) {
            return;
        }

        const placeholderNode = nodesById.get(edge.target);

        if (!placeholderNode || !PLACEHOLDER_NODE_TYPES.includes(placeholderNode.type ?? '')) {
            return;
        }

        const laneEdges = edges.filter(
            (laneEdge) =>
                laneEdge.source === edge.source &&
                laneEdge.target !== placeholderNode.id &&
                nodesById.get(laneEdge.target)?.type !== 'taskDispatcherLeftGhostNode'
        );

        if (laneEdges.length === 0) {
            return;
        }

        removedNodeIds.add(placeholderNode.id);
    });

    if (removedNodeIds.size === 0) {
        return {edges, nodes};
    }

    return {
        edges: edges.filter((edge) => !removedNodeIds.has(edge.source) && !removedNodeIds.has(edge.target)),
        nodes: nodes.filter((node) => !removedNodeIds.has(node.id)),
    };
}

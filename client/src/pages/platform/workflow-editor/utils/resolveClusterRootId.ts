import {NodeDataType} from '@/shared/types';

/**
 * The cluster root a node belongs to, asked of the node rather than of the editor. Box mode can show
 * several roots at once, so "the root currently open" (`rootClusterElementNodeData`) stops being a
 * well-formed question for anything operating on a specific node -- the answer has to come from the
 * node's own data instead.
 *
 * Resolution order:
 * 1. `topLevelClusterRootId` -- set by `createClusterElementNodes` on every element, at any nesting
 *    depth, and threaded UNCHANGED through its recursion. This is the OUTERMOST root, the only one
 *    addressable via `getTask`/`workflowTasks` (a nested cluster root lives inside its parent's
 *    `clusterElements`, not in `workflowTasks`), so callers that look a task up by `workflowNodeName`
 *    need this rather than the immediate parent.
 * 2. `parentClusterRootId` -- a fallback for element data that predates/omits `topLevelClusterRootId`
 *    (e.g. a caller building a minimal, synthetic `NodeDataType` for a first-level element by hand).
 *    Correct for a first-level element, where the immediate parent already IS the outermost root; not
 *    reliable for anything nested deeper.
 * 3. The node's own name, when the node itself IS a cluster root (`clusterRoot: true`).
 */
export function resolveClusterRootId(data: NodeDataType): string | undefined {
    if (data.topLevelClusterRootId) {
        return data.topLevelClusterRootId;
    }

    if (data.parentClusterRootId) {
        return data.parentClusterRootId;
    }

    return data.clusterRoot ? data.workflowNodeName : undefined;
}

/**
 * The workflow node name a request about a cluster element must carry as `workflowNodeName`: the
 * TOP-LEVEL root task's. The server resolves the element inside that task's `clusterElements`
 * (by type, then by name, nested types walked), so the root is the only name it accepts there.
 *
 * The node is the source of truth: every member the shared builder emits carries the root as
 * `topLevelClusterRootId`, and a root names itself. `rootClusterElementNodeData` is a fallback for
 * a cluster element with no ids only -- it is seeded while the dialog is open and left behind on
 * the main canvas afterwards, so it can name the wrong root there. A node that is neither a
 * cluster element nor a root resolves to nothing, so callers keep their own-name fallback for
 * ordinary tasks.*/
export function resolveMainClusterRootName(
    currentNode: NodeDataType | undefined,
    rootClusterElementNodeData: NodeDataType | undefined
): string | undefined {
    const resolvedFromNode = currentNode ? resolveClusterRootId(currentNode) : undefined;

    if (resolvedFromNode) {
        return resolvedFromNode;
    }

    // The store is consulted only for a cluster element the node graph could not place, and never
    // for a plain task: on the main canvas the field is whatever root last opened a destination --
    // nothing there clears it -- so preferring it sent a tool's requests to the root of a
    // different box, and would hand a plain task a root it does not have.
    return currentNode?.clusterElementType ? rootClusterElementNodeData?.workflowNodeName : undefined;
}

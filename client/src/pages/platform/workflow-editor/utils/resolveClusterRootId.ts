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

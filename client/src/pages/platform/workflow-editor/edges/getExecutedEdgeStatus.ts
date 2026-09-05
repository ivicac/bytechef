import {WorkflowTestNodeStateI} from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {type Edge, type Node} from '@xyflow/react';

/**
 * Resolves the workflow-node name a canvas node contributes to test-run node states. Structural ghost nodes
 * (task dispatcher top/bottom ghosts, case ghosts) carry their owning dispatcher's id as `taskDispatcherId`,
 * so the dispatcher's executed state flows through its plumbing edges.
 */
export function resolveTestStateNodeName(node?: Node): string | undefined {
    if (!node) {
        return undefined;
    }

    const data = node.data as {taskDispatcherId?: string; workflowNodeName?: string} | undefined;

    return data?.workflowNodeName ?? data?.taskDispatcherId ?? node.id;
}

export interface DisabledNodeBypassI {
    disabledTaskNames: Set<string>;
    edges: Edge[];
    nodes: Node[];
}

function isDisabledTaskNode(node: Node, disabledTaskNames: Set<string>): boolean {
    const data = node.data as {disabled?: boolean; workflowNodeName?: string} | undefined;

    if (!data?.workflowNodeName) {
        return false;
    }

    return Boolean(data.disabled) || disabledTaskNames.has(data.workflowNodeName);
}

function skipDisabledNodes(
    node: Node | undefined,
    direction: 'downstream' | 'upstream',
    {disabledTaskNames, edges, nodes}: DisabledNodeBypassI
): Node | undefined {
    const visitedNodeIds = new Set<string>();

    let currentNode = node;

    while (currentNode && isDisabledTaskNode(currentNode, disabledTaskNames) && !visitedNodeIds.has(currentNode.id)) {
        const currentNodeId = currentNode.id;

        visitedNodeIds.add(currentNodeId);

        const adjacentEdges = edges.filter((edge) =>
            direction === 'downstream' ? edge.source === currentNodeId : edge.target === currentNodeId
        );

        if (adjacentEdges.length !== 1) {
            return currentNode;
        }

        const adjacentNodeId = direction === 'downstream' ? adjacentEdges[0].target : adjacentEdges[0].source;

        const adjacentNode = nodes.find((candidateNode) => candidateNode.id === adjacentNodeId);

        if (!adjacentNode) {
            return currentNode;
        }

        currentNode = adjacentNode;
    }

    return currentNode;
}

/**
 * A disabled task is skipped at run time, so it never gets a test state and every edge touching it would drop out of
 * the executed path -- a single disabled step left a gray gap in the middle of a red or green trail. The run did pass
 * through that spot, so each endpoint that is a disabled task is replaced by the nearest enabled node beyond it: the
 * source walks upstream, the target downstream. Both edges around a disabled node then resolve to the same enabled
 * pair and color alike.
 *
 * The walk only follows a single edge. A disabled node with several edges on that side has no one path through it,
 * so it is kept as-is and the edge stays neutral.
 */
export function bypassDisabledEdgeEndpoints(
    sourceNode: Node | undefined,
    targetNode: Node | undefined,
    disabledNodeBypass: DisabledNodeBypassI
): {sourceNode: Node | undefined; targetNode: Node | undefined} {
    return {
        sourceNode: skipDisabledNodes(sourceNode, 'upstream', disabledNodeBypass),
        targetNode: skipDisabledNodes(targetNode, 'downstream', disabledNodeBypass),
    };
}

/**
 * An edge is part of the executed path when BOTH endpoints resolve to nodes that ran. Requiring both ends keeps
 * the untaken side of a condition gray: its bottom-ghost resolves to the (executed) dispatcher, but the untaken
 * branch child never ran, so child->ghost edges stay neutral.
 *
 * "Ran" means COMPLETED **or** FAILED on the source side, not COMPLETED alone. A task dispatcher whose nested
 * child fails is itself reported FAILED, and its ghost nodes borrow that status, so demanding COMPLETED there
 * broke the trail at the dispatcher's own boundary: `condition_1 -> top ghost -> condition_2` went gray between
 * two executed nodes. Widening the source cannot light an untaken branch, because the target still has to have
 * run.
 *
 * An edge into a FAILED node is COMPLETED, not FAILED: the data reached that node fine, and it was the node that
 * failed. Red belongs to the node alone, so the green trail runs right up to the step that broke.
 */
function resolveNodeStatus(
    node: Node | undefined,
    workflowTestNodeStates: Record<string, WorkflowTestNodeStateI>
): WorkflowTestNodeStateI['status'] | undefined {
    const name = resolveTestStateNodeName(node);

    if (!name) {
        return undefined;
    }

    const nodeState = workflowTestNodeStates[name];

    if (nodeState) {
        return nodeState.status;
    }

    // Triggers never produce a task execution of their own, but if anything ran at all the trigger
    // necessarily fired — treat it as completed so the trigger's outgoing edge joins the executed path.
    if ((node?.data as {trigger?: boolean} | undefined)?.trigger && Object.keys(workflowTestNodeStates).length > 0) {
        return 'COMPLETED';
    }

    return undefined;
}

/** One dispatch of one `graph/v1` member node, reduced to what the executed-transition rule needs. */
export interface GraphNodeExecutionI {
    nodeName: string;
    startDate?: Date;
    status?: string;
}

/** The transition an edge draws, alongside every node dispatch the graph it belongs to made. */
export interface GraphTransitionExecutionI {
    from: string;
    nodeExecutions: GraphNodeExecutionI[];
    to: string;
}

/**
 * Decides whether the run actually TOOK a transition — something the node-state rule this file's
 * default export applies to every other edge type cannot say.
 *
 * A graph routes to exactly ONE target per node completion, so in a conditional fan-out several
 * targets can complete across different visits of the same source without their transitions ever
 * having been followed: "source completed and target completed" would light every one of them.
 *
 * The transition that WAS taken is the one whose `to` was dispatched immediately after a dispatch of
 * `from`, in start order. The dispatcher runs one member at a time, so adjacency in that order is
 * exactly the routing decision it made. A cycle can take one transition several times; a failure on
 * any of those visits paints the edge red, mirroring how a failed node colors its incoming edge
 * everywhere else on the canvas.
 *
 * A dynamic transition needs no special case: its `to` is an expression, so it names no member and
 * no dispatch can ever match it.
 *
 * Known ambiguity: the match is on endpoint NAMES, so two transitions declaring the same
 * `(from, to)` under different conditions are indistinguishable here and both light up when either
 * is taken. Telling them apart needs the dispatcher to record WHICH transition index it followed,
 * which it does not — a task execution carries only its `__node` stamp — so this cannot be resolved
 * client-side.
 */
export function getExecutedGraphTransitionStatus({
    from,
    nodeExecutions,
    to,
}: GraphTransitionExecutionI): 'COMPLETED' | 'FAILED' | undefined {
    // A transition missing an endpoint name matches nothing. `collectGraphNodeExecutions` names an
    // unidentifiable dispatch '', so without this a nameless endpoint would pair with one.
    if (!from || !to) {
        return undefined;
    }

    // Sorted rather than trusted: the caller assembles these from a task execution tree whose order
    // is the server's, not necessarily chronological. `sort` is stable, so equal start dates keep
    // that original order instead of being reshuffled.
    const orderedExecutions = [...nodeExecutions].sort(
        (left, right) => (left.startDate?.getTime() ?? 0) - (right.startDate?.getTime() ?? 0)
    );

    let status: 'COMPLETED' | 'FAILED' | undefined;

    for (let index = 0; index < orderedExecutions.length - 1; index += 1) {
        const followingExecution = orderedExecutions[index + 1];

        if (orderedExecutions[index].nodeName !== from || followingExecution.nodeName !== to) {
            continue;
        }

        if (followingExecution.status === 'FAILED') {
            return 'FAILED';
        }

        if (followingExecution.status === 'COMPLETED') {
            status = 'COMPLETED';
        }
    }

    return status;
}

export default function getExecutedEdgeStatus(
    sourceNode: Node | undefined,
    targetNode: Node | undefined,
    workflowTestNodeStates: Record<string, WorkflowTestNodeStateI>,
    graphTransition?: GraphTransitionExecutionI
): 'COMPLETED' | 'FAILED' | undefined {
    // A graph transition is decided by the routing the run performed, never by the two endpoints'
    // node states — see `getExecutedGraphTransitionStatus`. Every other edge type falls through.
    if (graphTransition) {
        return getExecutedGraphTransitionStatus(graphTransition);
    }

    // A "+" placeholder is an insertion point, not a step: nothing runs there and no data passes through it. It
    // carries its dispatcher's id as `taskDispatcherId`, so without this the edges around a parallel's or
    // fork-join's add-a-branch slot would borrow the dispatcher's status and paint a path the run never took.
    if (sourceNode?.type === 'placeholder' || targetNode?.type === 'placeholder') {
        return undefined;
    }

    const sourceStatus = resolveNodeStatus(sourceNode, workflowTestNodeStates);

    // A bottom ghost is a join, and the status it borrows is the dispatcher's aggregate: one failed branch makes
    // it FAILED for every branch. What an edge into the join shows is whether ITS branch got there, which only the
    // source can say -- a completed branch joined, a failed one stopped short of the join.
    if (targetNode?.type === 'taskDispatcherBottomGhostNode') {
        return sourceStatus === 'COMPLETED' ? 'COMPLETED' : undefined;
    }

    if (sourceStatus !== 'COMPLETED' && sourceStatus !== 'FAILED') {
        return undefined;
    }

    const targetStatus = resolveNodeStatus(targetNode, workflowTestNodeStates);

    if (targetStatus === 'COMPLETED' || targetStatus === 'FAILED') {
        return 'COMPLETED';
    }

    return undefined;
}

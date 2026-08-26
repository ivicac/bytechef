import {WorkflowTestNodeStateI} from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {CONDITION_CASE_FALSE, CONDITION_CASE_TRUE} from '@/shared/constants';
import {type Edge, type Node} from '@xyflow/react';

import {toConditionCaseKey} from './collectTakenEmptyConditionCases';

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

function resolveNodeStatus(
    node: Node | undefined,
    workflowTestNodeStates: Record<string, WorkflowTestNodeStateI>
): WorkflowTestNodeStateI['status'] | undefined {
    const name = resolveTestStateNodeName(node);

    if (!name) {
        return undefined;
    }

    return workflowTestNodeStates[name]?.status;
}

const isPlaceholderNode = (node: Node | undefined): boolean =>
    node?.type === 'placeholder' || node?.type === 'readonlyPlaceholder';

export function isConditionPlaceholderNode(node: Node | undefined): boolean {
    return isPlaceholderNode(node) && Boolean((node?.data as {conditionId?: string} | undefined)?.conditionId);
}

function isTakenEmptyConditionCase(placeholderNode: Node | undefined, takenEmptyConditionCases?: Set<string>): boolean {
    const data = placeholderNode?.data as {conditionCase?: string; conditionId?: string} | undefined;

    if (!data?.conditionId || !data.conditionCase || !takenEmptyConditionCases) {
        return false;
    }

    return takenEmptyConditionCases.has(toConditionCaseKey(data.conditionId, data.conditionCase));
}

function hasOnlyEmptyConditionCases(node: Node | undefined): boolean {
    const parameters = (node?.data as {parameters?: {[key: string]: unknown}} | undefined)?.parameters;

    if (!parameters || !(CONDITION_CASE_TRUE in parameters || CONDITION_CASE_FALSE in parameters)) {
        return false;
    }

    return [parameters[CONDITION_CASE_TRUE], parameters[CONDITION_CASE_FALSE]].every(
        (caseTasks) => !Array.isArray(caseTasks) || caseTasks.length === 0
    );
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
    takenEmptyConditionCases?: Set<string>,
    graphTransition?: GraphTransitionExecutionI
): 'COMPLETED' | 'FAILED' | undefined {
    // A graph transition is decided by the routing the run performed, never by the two endpoints'
    // node states — see `getExecutedGraphTransitionStatus`. Every other edge type falls through.
    if (graphTransition) {
        return getExecutedGraphTransitionStatus(graphTransition);
    }

    if (isPlaceholderNode(sourceNode) || isPlaceholderNode(targetNode)) {
        const placeholderNode = isPlaceholderNode(sourceNode) ? sourceNode : targetNode;

        return isTakenEmptyConditionCase(placeholderNode, takenEmptyConditionCases) ? 'COMPLETED' : undefined;
    }

    if (targetNode?.type === 'taskDispatcherTopGhostNode' && hasOnlyEmptyConditionCases(sourceNode)) {
        return undefined;
    }

    const sourceStatus = resolveNodeStatus(sourceNode, workflowTestNodeStates);

    if (targetNode?.type === 'taskDispatcherBottomGhostNode') {
        return sourceStatus === 'COMPLETED' ? 'COMPLETED' : undefined;
    }

    if (!isReachedStatus(sourceNode, sourceStatus)) {
        return undefined;
    }

    const targetStatus = resolveNodeStatus(targetNode, workflowTestNodeStates);

    if (isReachedStatus(targetNode, targetStatus)) {
        return 'COMPLETED';
    }

    return undefined;
}

function isTaskDispatcherEntryNode(node: Node | undefined): boolean {
    return (
        node?.type === 'taskDispatcherTopGhostNode' ||
        Boolean((node?.data as {taskDispatcher?: boolean} | undefined)?.taskDispatcher)
    );
}

function isReachedStatus(node: Node | undefined, status: WorkflowTestNodeStateI['status'] | undefined): boolean {
    if (status === 'COMPLETED' || status === 'FAILED') {
        return true;
    }

    return status === 'RUNNING' && isTaskDispatcherEntryNode(node);
}

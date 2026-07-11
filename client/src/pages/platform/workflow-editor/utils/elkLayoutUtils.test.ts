import {Edge, Node} from '@xyflow/react';
import {describe, expect, it} from 'vitest';

import {buildElkGraph, getElkLayoutElements, getFrameId} from './elkLayoutUtils';

import type {ElkNode} from 'elkjs/lib/elk-api';

// The distance between consecutive node origins on the main axis: the 72px
// icon anchor box plus the uniform CHAIN_GAP. Identical for every consecutive
// pair, at every nesting depth, in both TB and LR — the engine's core invariant.
const CHAIN_GAP = 80;

// Box-adjacent edges (condition→frame bar, bar→next node): layer gap + one
// 14px anchor slack.
const BOX_GAP = 66;

// The frame's top bar is pulled 28px toward the condition so the TRUE/FALSE
// labels read as attached to the box (TOP_BAR_LABEL_PULL in elkLayoutUtils).
const TOP_BOX_GAP = 38;
const BAR_TO_CHILD_GAP = 94;
const CHAIN_STEP = 72 + CHAIN_GAP;

const taskNode = (
    id: string,
    conditionParent?: {conditionCase: 'caseTrue' | 'caseFalse'; conditionId: string}
): Node => ({
    data: {
        componentName: 'mailchimp',
        ...(conditionParent
            ? {
                  conditionData: {
                      conditionCase: conditionParent.conditionCase,
                      conditionId: conditionParent.conditionId,
                      index: 0,
                  },
              }
            : {}),
        workflowNodeName: id,
    },
    id,
    position: {x: 0, y: 0},
    type: 'workflow',
});

const conditionNode = (
    id: string,
    conditionParent?: {conditionCase: 'caseTrue' | 'caseFalse'; conditionId: string}
): Node => ({
    data: {
        componentName: 'condition',
        ...(conditionParent
            ? {
                  conditionData: {
                      conditionCase: conditionParent.conditionCase,
                      conditionId: conditionParent.conditionId,
                      index: 0,
                  },
              }
            : {}),
        taskDispatcher: true,
        taskDispatcherId: id,
        workflowNodeName: id,
    },
    id,
    position: {x: 0, y: 0},
    type: 'workflow',
});

const conditionGhostNodes = (conditionId: string): Node[] => [
    {
        data: {conditionId, taskDispatcherId: conditionId},
        id: `${conditionId}-condition-top-ghost`,
        position: {x: 0, y: 0},
        type: 'taskDispatcherTopGhostNode',
    },
    {
        data: {conditionId, taskDispatcherId: conditionId},
        id: `${conditionId}-condition-bottom-ghost`,
        position: {x: 0, y: 0},
        type: 'taskDispatcherBottomGhostNode',
    },
];

const conditionPlaceholderNode = (conditionId: string, side: 'left' | 'right'): Node => ({
    data: {
        conditionCase: side === 'left' ? 'caseTrue' : 'caseFalse',
        conditionId,
        label: '+',
        taskDispatcherId: conditionId,
    },
    id: `${conditionId}-condition-${side}-placeholder-0`,
    position: {x: 0, y: 0},
    type: 'placeholder',
});

const loopNode = (
    id: string,
    owner?: {conditionCase: 'caseTrue' | 'caseFalse'; conditionId: string} | {loopId: string}
): Node => ({
    data: {
        componentName: 'loop',
        ...(owner && 'conditionId' in owner
            ? {
                  conditionData: {
                      conditionCase: owner.conditionCase,
                      conditionId: owner.conditionId,
                      index: 0,
                  },
              }
            : {}),
        ...(owner && 'loopId' in owner ? {loopData: {index: 0, loopId: owner.loopId}} : {}),
        taskDispatcher: true,
        taskDispatcherId: id,
        workflowNodeName: id,
    },
    id,
    position: {x: 0, y: 0},
    type: 'workflow',
});

const loopChildTaskNode = (id: string, loopId: string): Node => ({
    data: {componentName: 'mailchimp', loopData: {index: 0, loopId}, workflowNodeName: id},
    id,
    position: {x: 0, y: 0},
    type: 'workflow',
});

// Mirrors createLoopNode: top ghost, loop-back rail ghost, bottom ghost — the
// bottom ghost genuinely has no loopId in its data, only taskDispatcherId
const loopAuxNodes = (loopId: string): Node[] => [
    {
        data: {loopId, taskDispatcherId: loopId},
        id: `${loopId}-loop-top-ghost`,
        position: {x: 0, y: 0},
        type: 'taskDispatcherTopGhostNode',
    },
    {
        data: {loopId, taskDispatcherId: loopId},
        id: `${loopId}-taskDispatcher-left-ghost`,
        position: {x: 0, y: 0},
        type: 'taskDispatcherLeftGhostNode',
    },
    {
        data: {taskDispatcherId: loopId},
        id: `${loopId}-loop-bottom-ghost`,
        position: {x: 0, y: 0},
        type: 'taskDispatcherBottomGhostNode',
    },
];

const loopPlaceholderNode = (loopId: string): Node => ({
    data: {label: '+', loopId, taskDispatcherId: loopId},
    id: `${loopId}-loop-placeholder-0`,
    position: {x: 0, y: 0},
    type: 'placeholder',
});

const edge = (source: string, target: string): Edge => ({id: `${source}=>${target}`, source, target});

// Base loop wiring per createLoopEdges: loop→top, top→rail→bottom (rail), plus
// top→content→bottom supplied by the caller
const loopStructureEdges = (loopId: string): Edge[] => [
    edge(loopId, `${loopId}-loop-top-ghost`),
    edge(`${loopId}-loop-top-ghost`, `${loopId}-taskDispatcher-left-ghost`),
    edge(`${loopId}-taskDispatcher-left-ghost`, `${loopId}-loop-bottom-ghost`),
];

const childIds = (elkNode: ElkNode | undefined): string[] => (elkNode?.children ?? []).map((child) => child.id).sort();

const findChild = (elkNode: ElkNode, id: string): ElkNode | undefined =>
    (elkNode.children ?? []).find((child) => child.id === id);

const collectScopeEdgeViolations = (elkNode: ElkNode): string[] => {
    const violations: string[] = [];

    const memberIds = new Set((elkNode.children ?? []).map((child) => child.id));

    (elkNode.edges ?? []).forEach((scopeEdge) => {
        [...(scopeEdge.sources ?? []), ...(scopeEdge.targets ?? [])].forEach((endpointId) => {
            if (!memberIds.has(endpointId)) {
                violations.push(`${scopeEdge.id}: ${endpointId} not in scope ${elkNode.id}`);
            }
        });
    });

    (elkNode.children ?? []).forEach((child) => violations.push(...collectScopeEdgeViolations(child)));

    return violations;
};

const singleConditionFixture = () => {
    const nodes: Node[] = [
        taskNode('task1'),
        conditionNode('condition_1'),
        ...conditionGhostNodes('condition_1'),
        taskNode('childTrue1', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
        taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
        taskNode('task2'),
    ];

    const edges: Edge[] = [
        edge('task1', 'condition_1'),
        edge('condition_1', 'condition_1-condition-top-ghost'),
        edge('condition_1-condition-top-ghost', 'childTrue1'),
        edge('condition_1-condition-top-ghost', 'childFalse1'),
        edge('childTrue1', 'condition_1-condition-bottom-ghost'),
        edge('childFalse1', 'condition_1-condition-bottom-ghost'),
        edge('condition_1-condition-bottom-ghost', 'task2'),
    ];

    return {edges, nodes};
};

describe('buildElkGraph', () => {
    it('lays a linear chain flat in the root scope', () => {
        const nodes = [taskNode('task1'), taskNode('task2'), taskNode('task3')];
        const edges = [edge('task1', 'task2'), edge('task2', 'task3')];

        const graph = buildElkGraph(nodes, edges, 'TB');

        expect(childIds(graph)).toEqual(['task1', 'task2', 'task3']);
        expect(graph.edges).toHaveLength(2);
    });

    it('wraps condition members in a frame and keeps the condition node outside it', () => {
        const {edges, nodes} = singleConditionFixture();

        const graph = buildElkGraph(nodes, edges, 'TB');

        expect(childIds(graph)).toEqual(['condition_1', getFrameId('condition_1'), 'task1', 'task2']);

        const frame = findChild(graph, getFrameId('condition_1'));

        expect(childIds(frame)).toEqual([
            'childFalse1',
            'childTrue1',
            'condition_1-condition-bottom-ghost',
            'condition_1-condition-top-ghost',
        ]);
    });

    it('remaps root edges onto the frame', () => {
        const {edges, nodes} = singleConditionFixture();

        const graph = buildElkGraph(nodes, edges, 'TB');

        const rootEdgePairs = (graph.edges ?? []).map(
            (scopeEdge) => `${scopeEdge.sources[0]}=>${scopeEdge.targets[0]}`
        );

        expect(rootEdgePairs.sort()).toEqual([
            `condition_1=>${getFrameId('condition_1')}`,
            `${getFrameId('condition_1')}=>task2`,
            'task1=>condition_1',
        ]);
    });

    it('produces no cross-hierarchy edges anywhere', () => {
        const {edges, nodes} = singleConditionFixture();

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);
    });

    it('places empty-branch placeholders inside the frame', () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionPlaceholderNode('condition_1', 'left'),
            conditionPlaceholderNode('condition_1', 'right'),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-left-placeholder-0'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-right-placeholder-0'),
            edge('condition_1-condition-left-placeholder-0', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-right-placeholder-0', 'condition_1-condition-bottom-ghost'),
        ];

        const frame = findChild(buildElkGraph(nodes, edges, 'TB'), getFrameId('condition_1'));

        expect(childIds(frame)).toContain('condition_1-condition-left-placeholder-0');
        expect(childIds(frame)).toContain('condition_1-condition-right-placeholder-0');
    });

    it('nests a condition frame inside its parent frame', () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionNode('condition_2', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            ...conditionGhostNodes('condition_2'),
            taskNode('innerChild', {conditionCase: 'caseTrue', conditionId: 'condition_2'}),
            taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_2'),
            edge('condition_2', 'condition_2-condition-top-ghost'),
            edge('condition_2-condition-top-ghost', 'innerChild'),
            edge('innerChild', 'condition_2-condition-bottom-ghost'),
            edge('condition_2-condition-bottom-ghost', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'childFalse1'),
            edge('childFalse1', 'condition_1-condition-bottom-ghost'),
        ];

        const graph = buildElkGraph(nodes, edges, 'TB');

        const outerFrame = findChild(graph, getFrameId('condition_1'));

        expect(childIds(outerFrame)).toEqual([
            'childFalse1',
            'condition_1-condition-bottom-ghost',
            'condition_1-condition-top-ghost',
            'condition_2',
            getFrameId('condition_2'),
        ]);

        const innerFrame = findChild(outerFrame!, getFrameId('condition_2'));

        expect(childIds(innerFrame)).toEqual([
            'condition_2-condition-bottom-ghost',
            'condition_2-condition-top-ghost',
            'innerChild',
        ]);

        expect(collectScopeEdgeViolations(graph)).toEqual([]);
    });

    it('terminates on cyclic condition ownership from malformed state', () => {
        const nodes: Node[] = [
            conditionNode('condition_a', {conditionCase: 'caseTrue', conditionId: 'condition_b'}),
            ...conditionGhostNodes('condition_a'),
            conditionNode('condition_b', {conditionCase: 'caseTrue', conditionId: 'condition_a'}),
            ...conditionGhostNodes('condition_b'),
        ];

        const edges: Edge[] = [edge('condition_a-condition-top-ghost', 'condition_b-condition-top-ghost')];

        const graph = buildElkGraph(nodes, edges, 'TB');

        expect(graph.id).toEqual('__root__');
    });

    it('falls back to the root scope when conditionData references a missing condition', () => {
        const nodes: Node[] = [
            taskNode('task1'),
            taskNode('orphanTask', {conditionCase: 'caseTrue', conditionId: 'condition_missing'}),
            taskNode('task2'),
        ];

        const edges: Edge[] = [edge('task1', 'orphanTask'), edge('orphanTask', 'task2')];

        const graph = buildElkGraph(nodes, edges, 'TB');

        expect(childIds(graph)).toEqual(['orphanTask', 'task1', 'task2']);
    });
});

const positionOf = (layoutedNodes: Node[], id: string): {x: number; y: number} => {
    const layoutedNode = layoutedNodes.find((node) => node.id === id);

    if (!layoutedNode) {
        throw new Error(`Node ${id} missing from layout result`);
    }

    return layoutedNode.position;
};

describe('getElkLayoutElements', () => {
    it('spaces a TB chain uniformly (footprint gap = 50)', async () => {
        const nodes = [taskNode('task1'), taskNode('task2'), taskNode('task3')];
        const edges = [edge('task1', 'task2'), edge('task2', 'task3')];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const firstGap = positionOf(result.nodes, 'task2').y - positionOf(result.nodes, 'task1').y;
        const secondGap = positionOf(result.nodes, 'task3').y - positionOf(result.nodes, 'task2').y;

        expect(firstGap).toBe(CHAIN_STEP);
        expect(secondGap).toBe(CHAIN_STEP);
    });

    it('spaces an LR chain uniformly on the x axis', async () => {
        const nodes = [taskNode('task1'), taskNode('task2'), taskNode('task3')];
        const edges = [edge('task1', 'task2'), edge('task2', 'task3')];

        const result = await getElkLayoutElements({
            canvasHeight: 800,
            canvasWidth: 1000,
            direction: 'LR',
            edges,
            nodes,
        });

        const firstGap = positionOf(result.nodes, 'task2').x - positionOf(result.nodes, 'task1').x;
        const secondGap = positionOf(result.nodes, 'task3').x - positionOf(result.nodes, 'task2').x;

        // LR footprint width is 120 (see getDagreNodeSize) + 50 spacing
        expect(firstGap).toBe(CHAIN_STEP);
        expect(secondGap).toBe(CHAIN_STEP);
    });

    it('uses the same gap inside a nested condition branch as at the root', async () => {
        const nodes: Node[] = [
            taskNode('task1'),
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            taskNode('childTrue1', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childTrue2', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
            taskNode('task2'),
        ];

        const edges: Edge[] = [
            edge('task1', 'condition_1'),
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'childTrue1'),
            edge('childTrue1', 'childTrue2'),
            edge('childTrue2', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'childFalse1'),
            edge('childFalse1', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-bottom-ghost', 'task2'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const rootGap = positionOf(result.nodes, 'condition_1').y - positionOf(result.nodes, 'task1').y;
        const branchGap = positionOf(result.nodes, 'childTrue2').y - positionOf(result.nodes, 'childTrue1').y;

        expect(branchGap).toBe(CHAIN_STEP);
        expect(rootGap).toBe(CHAIN_STEP);
    });

    it('drops synthetic frame nodes from the result', async () => {
        const {edges, nodes} = singleConditionFixture();

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        expect(result.nodes.map((node) => node.id)).not.toContain(getFrameId('condition_1'));
        expect(result.nodes).toHaveLength(nodes.length);
    });

    it('centers the condition node and its ghosts on the same cross-axis line', async () => {
        const {edges, nodes} = singleConditionFixture();

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const conditionPosition = positionOf(result.nodes, 'condition_1');
        const topGhostPosition = positionOf(result.nodes, 'condition_1-condition-top-ghost');
        const bottomGhostPosition = positionOf(result.nodes, 'condition_1-condition-bottom-ghost');

        // rendered widths: condition anchor 72, ghosts 72
        const conditionCenter = conditionPosition.x + 36;
        const topGhostCenter = topGhostPosition.x + 36;
        const bottomGhostCenter = bottomGhostPosition.x + 36;

        expect(Math.abs(topGhostCenter - conditionCenter)).toBeLessThanOrEqual(1);
        expect(Math.abs(bottomGhostCenter - conditionCenter)).toBeLessThanOrEqual(1);
    });

    it('still lays out a node whose conditionData references a missing condition', async () => {
        const nodes: Node[] = [
            taskNode('task1'),
            taskNode('orphanTask', {conditionCase: 'caseTrue', conditionId: 'condition_missing'}),
            taskNode('task2'),
        ];

        const edges: Edge[] = [edge('task1', 'orphanTask'), edge('orphanTask', 'task2')];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const orphanPosition = positionOf(result.nodes, 'orphanTask');

        expect(Number.isFinite(orphanPosition.x)).toBe(true);
        expect(Number.isFinite(orphanPosition.y)).toBe(true);
        expect(orphanPosition).not.toEqual({x: 0, y: 0});
    });

    it('uses the same gap three levels deep as at the root (TB)', async () => {
        const nodes: Node[] = [
            taskNode('task1'),
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionNode('condition_2', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            ...conditionGhostNodes('condition_2'),
            conditionNode('condition_3', {conditionCase: 'caseTrue', conditionId: 'condition_2'}),
            ...conditionGhostNodes('condition_3'),
            taskNode('deepChild1', {conditionCase: 'caseTrue', conditionId: 'condition_3'}),
            taskNode('deepChild2', {conditionCase: 'caseTrue', conditionId: 'condition_3'}),
        ];

        const edges: Edge[] = [
            edge('task1', 'condition_1'),
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_2'),
            edge('condition_2', 'condition_2-condition-top-ghost'),
            edge('condition_2-condition-top-ghost', 'condition_3'),
            edge('condition_3', 'condition_3-condition-top-ghost'),
            edge('condition_3-condition-top-ghost', 'deepChild1'),
            edge('deepChild1', 'deepChild2'),
            edge('deepChild2', 'condition_3-condition-bottom-ghost'),
            edge('condition_3-condition-bottom-ghost', 'condition_2-condition-bottom-ghost'),
            edge('condition_2-condition-bottom-ghost', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const rootGap = positionOf(result.nodes, 'condition_1').y - positionOf(result.nodes, 'task1').y;
        const innermostGap = positionOf(result.nodes, 'deepChild2').y - positionOf(result.nodes, 'deepChild1').y;

        expect(innermostGap).toBe(CHAIN_STEP);
        expect(rootGap).toBe(CHAIN_STEP);
    });

    it('uses the same gap inside a nested condition branch as at the root (LR)', async () => {
        const nodes: Node[] = [
            taskNode('task1'),
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            taskNode('childTrue1', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childTrue2', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
            taskNode('task2'),
        ];

        const edges: Edge[] = [
            edge('task1', 'condition_1'),
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'childTrue1'),
            edge('childTrue1', 'childTrue2'),
            edge('childTrue2', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'childFalse1'),
            edge('childFalse1', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-bottom-ghost', 'task2'),
        ];

        const result = await getElkLayoutElements({
            canvasHeight: 800,
            canvasWidth: 1000,
            direction: 'LR',
            edges,
            nodes,
        });

        const rootGap = positionOf(result.nodes, 'condition_1').x - positionOf(result.nodes, 'task1').x;
        const branchGap = positionOf(result.nodes, 'childTrue2').x - positionOf(result.nodes, 'childTrue1').x;

        expect(branchGap).toBe(CHAIN_STEP);
        expect(rootGap).toBe(CHAIN_STEP);
    });

    it('honors saved node positions', async () => {
        const nodes = [taskNode('task1'), taskNode('task2')];

        (nodes[1].data as Record<string, unknown>).metadata = {ui: {nodePosition: {x: 400, y: 900}}};

        const result = await getElkLayoutElements({
            canvasWidth: 1000,
            direction: 'TB',
            edges: [edge('task1', 'task2')],
            nodes,
        });

        expect(positionOf(result.nodes, 'task2')).toEqual({x: 400, y: 900});
    });

    it('keeps the caseTrue branch on the TRUE side in TB', async () => {
        const {edges, nodes} = singleConditionFixture();

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        expect(positionOf(result.nodes, 'childTrue1').x).toBeLessThan(positionOf(result.nodes, 'childFalse1').x);
    });

    it('keeps the caseTrue branch on top in LR', async () => {
        const {edges, nodes} = singleConditionFixture();

        const result = await getElkLayoutElements({
            canvasHeight: 800,
            canvasWidth: 1000,
            direction: 'LR',
            edges,
            nodes,
        });

        expect(positionOf(result.nodes, 'childTrue1').y).toBeLessThan(positionOf(result.nodes, 'childFalse1').y);
    });

    it('keeps branch sides when the caseFalse branch outweighs the caseTrue branch', async () => {
        // TRUE branch: lone placeholder; FALSE branch: nested condition subtree.
        // Crossing minimization would swap these without model-order forcing.
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionPlaceholderNode('condition_1', 'left'),
            conditionNode('condition_2', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
            ...conditionGhostNodes('condition_2'),
            taskNode('innerChild', {conditionCase: 'caseTrue', conditionId: 'condition_2'}),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-left-placeholder-0'),
            edge('condition_1-condition-left-placeholder-0', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_2'),
            edge('condition_2', 'condition_2-condition-top-ghost'),
            edge('condition_2-condition-top-ghost', 'innerChild'),
            edge('innerChild', 'condition_2-condition-bottom-ghost'),
            edge('condition_2-condition-bottom-ghost', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // rendered centers: both the placeholder DOM box (mx-margins around the +) and the condition anchor are 72 wide
        const placeholderCenter = positionOf(result.nodes, 'condition_1-condition-left-placeholder-0').x + 36;
        const nestedConditionCenter = positionOf(result.nodes, 'condition_2').x + 36;

        expect(placeholderCenter).toBeLessThan(nestedConditionCenter);

        // Even with a wide caseFalse subtree, the condition sits midway between its
        // two branch entry axes — NOT over the frame's bounding-box center, which
        // would drift toward the wider subtree
        const conditionCenter = positionOf(result.nodes, 'condition_1').x + 36;

        expect(Math.abs((placeholderCenter + nestedConditionCenter) / 2 - conditionCenter)).toBeLessThanOrEqual(1);

        // The empty-branch placeholder sits midway between the two ghost bars on
        // the main axis, however deep the sibling branch is
        const topGhostBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;
        const bottomGhostBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;
        const placeholderMainCenter = positionOf(result.nodes, 'condition_1-condition-left-placeholder-0').y + 14;

        expect(Math.abs(placeholderMainCenter - (topGhostBarY + bottomGhostBarY + 2) / 2)).toBeLessThanOrEqual(1);
    });

    it('gives frame exit edges the node-to-node rhythm', async () => {
        const {edges, nodes} = singleConditionFixture();

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // Leaving a box reads like a node→node edge: bottom bar → next node = CHAIN_GAP
        const bottomGhostBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;
        const nextTaskTop = positionOf(result.nodes, 'task2').y;

        expect(nextTaskTop - (bottomGhostBarY + 2)).toBe(CHAIN_GAP);
    });

    it('keeps merge stubs between nested and enclosing bottom bars at the box gap', async () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionPlaceholderNode('condition_1', 'left'),
            conditionNode('condition_2', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
            ...conditionGhostNodes('condition_2'),
            taskNode('innerChild', {conditionCase: 'caseTrue', conditionId: 'condition_2'}),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-left-placeholder-0'),
            edge('condition_1-condition-left-placeholder-0', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_2'),
            edge('condition_2', 'condition_2-condition-top-ghost'),
            edge('condition_2-condition-top-ghost', 'innerChild'),
            edge('innerChild', 'condition_2-condition-bottom-ghost'),
            edge('condition_2-condition-bottom-ghost', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const innerBottomBarY = positionOf(result.nodes, 'condition_2-condition-bottom-ghost').y;
        const outerBottomBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;

        expect(outerBottomBarY - (innerBottomBarY + 2)).toBe(BOX_GAP);
    });

    it('centers a short branch chain between the bars when its sibling is taller', async () => {
        // dagre parity (centerDispatcherChildrenOnMainAxis): the FALSE branch's
        // lone task floats centered in the frame interior instead of hugging the
        // top bar; the tall TRUE chain keeps its designed 94/66 gaps
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            taskNode('childTrue1', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childTrue2', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childTrue3', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'childTrue1'),
            edge('childTrue1', 'childTrue2'),
            edge('childTrue2', 'childTrue3'),
            edge('childTrue3', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'childFalse1'),
            edge('childFalse1', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1200, direction: 'TB', edges, nodes});

        const topGhostBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;
        const bottomGhostBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;

        // Tall TRUE chain keeps the designed asymmetric gaps
        expect(positionOf(result.nodes, 'childTrue1').y - (topGhostBarY + 2)).toBe(BAR_TO_CHILD_GAP);
        expect(bottomGhostBarY - (positionOf(result.nodes, 'childTrue3').y + 72)).toBe(BOX_GAP);

        // Short FALSE chain floats centered in the interior
        const interiorCenter = (topGhostBarY + 2 + bottomGhostBarY) / 2;
        const falseChildCenter = positionOf(result.nodes, 'childFalse1').y + 36;

        expect(Math.abs(falseChildCenter - interiorCenter)).toBeLessThanOrEqual(1);
    });

    it('pins a trailing placeholder after a condition onto the chain axis', async () => {
        const {edges, nodes} = singleConditionFixture();

        nodes.push({data: {label: '+'}, id: 'final-placeholder', position: {x: 0, y: 0}, type: 'placeholder'});
        edges.push(edge('condition_1-condition-bottom-ghost', 'final-placeholder'));

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const conditionCenter = positionOf(result.nodes, 'condition_1').x + 36;
        const trailingPlaceholderCenter = positionOf(result.nodes, 'final-placeholder').x + 36;

        expect(Math.abs(trailingPlaceholderCenter - conditionCenter)).toBeLessThanOrEqual(1);
    });

    it('centers an empty condition frame on the condition node with a uniform gap', async () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionPlaceholderNode('condition_1', 'left'),
            conditionPlaceholderNode('condition_1', 'right'),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-left-placeholder-0'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-right-placeholder-0'),
            edge('condition_1-condition-left-placeholder-0', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-right-placeholder-0', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // The frame box (spanned by the two case placeholders) is centered on the
        // condition's 72px anchor box
        const conditionCenter = positionOf(result.nodes, 'condition_1').x + 36;
        const leftPlaceholderCenter = positionOf(result.nodes, 'condition_1-condition-left-placeholder-0').x + 36;
        const rightPlaceholderCenter = positionOf(result.nodes, 'condition_1-condition-right-placeholder-0').x + 36;

        expect(Math.abs((leftPlaceholderCenter + rightPlaceholderCenter) / 2 - conditionCenter)).toBeLessThanOrEqual(1);

        // The visible edge from the condition icon to the frame's top ghost bar is
        // exactly BOX_GAP (layer gap + one anchor slack)
        const conditionBottom = positionOf(result.nodes, 'condition_1').y + 72;
        const topGhostBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;

        expect(topGhostBarY - conditionBottom).toBe(TOP_BOX_GAP);
    });

    it('keeps uniform box gaps in a frame with one populated and one empty branch', async () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            taskNode('loggerTask', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            conditionPlaceholderNode('condition_1', 'right'),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'loggerTask'),
            edge('loggerTask', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-right-placeholder-0'),
            edge('condition_1-condition-right-placeholder-0', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const conditionBottom = positionOf(result.nodes, 'condition_1').y + 72;
        const topGhostBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;
        const loggerTaskTop = positionOf(result.nodes, 'loggerTask').y;
        const bottomGhostBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;

        expect(topGhostBarY - conditionBottom).toBe(TOP_BOX_GAP);
        expect(loggerTaskTop - (topGhostBarY + 2)).toBe(BAR_TO_CHILD_GAP);
        expect(bottomGhostBarY - (loggerTaskTop + 72)).toBe(BOX_GAP);

        // Condition sits midway between the populated chain and the empty-branch placeholder
        const conditionCenter = positionOf(result.nodes, 'condition_1').x + 36;
        const loggerCenter = positionOf(result.nodes, 'loggerTask').x + 36;
        const placeholderCenter = positionOf(result.nodes, 'condition_1-condition-right-placeholder-0').x + 36;

        expect(Math.abs((loggerCenter + placeholderCenter) / 2 - conditionCenter)).toBeLessThanOrEqual(1);
    });

    it('moves the whole frame with a dispatcher that has a saved position', async () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            taskNode('loggerTask', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            conditionPlaceholderNode('condition_1', 'right'),
        ];

        (nodes[0].data as Record<string, unknown>).metadata = {ui: {nodePosition: {x: 400, y: 900}}};

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'loggerTask'),
            edge('loggerTask', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-right-placeholder-0'),
            edge('condition_1-condition-right-placeholder-0', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // The dispatcher honors its saved position...
        expect(positionOf(result.nodes, 'condition_1')).toEqual({x: 400, y: 900});

        // ...and its ghosts and children shift rigidly with it, keeping the frame's
        // internal geometry (uniform CHAIN_GAPs, ghosts centered on the condition)
        const topGhostBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;
        const loggerTaskTop = positionOf(result.nodes, 'loggerTask').y;
        const bottomGhostBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;

        expect(topGhostBarY - (900 + 72)).toBe(TOP_BOX_GAP);
        expect(loggerTaskTop - (topGhostBarY + 2)).toBe(BAR_TO_CHILD_GAP);
        expect(bottomGhostBarY - (loggerTaskTop + 72)).toBe(BOX_GAP);

        const topGhostCenter = positionOf(result.nodes, 'condition_1-condition-top-ghost').x + 36;

        expect(Math.abs(topGhostCenter - (400 + 36))).toBeLessThanOrEqual(1);
    });

    it('floats a shallow sibling frame centered while keeping its box gap uniform', async () => {
        // condition_1's TRUE branch holds a shallow nested condition (empty
        // branches) while the FALSE branch holds a deeper one — the shallow
        // subtree floats centered in the outer interior as one rigid unit
        // (dagre parity: centerDispatcherChildrenOnMainAxis), so its
        // condition→box gap must stay the uniform TOP_BOX_GAP throughout
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            conditionNode('condition_4', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            ...conditionGhostNodes('condition_4'),
            conditionPlaceholderNode('condition_4', 'left'),
            conditionPlaceholderNode('condition_4', 'right'),
            conditionNode('condition_2', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
            ...conditionGhostNodes('condition_2'),
            taskNode('loggerTask', {conditionCase: 'caseTrue', conditionId: 'condition_2'}),
            conditionPlaceholderNode('condition_2', 'right'),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_4'),
            edge('condition_4', 'condition_4-condition-top-ghost'),
            edge('condition_4-condition-top-ghost', 'condition_4-condition-left-placeholder-0'),
            edge('condition_4-condition-top-ghost', 'condition_4-condition-right-placeholder-0'),
            edge('condition_4-condition-left-placeholder-0', 'condition_4-condition-bottom-ghost'),
            edge('condition_4-condition-right-placeholder-0', 'condition_4-condition-bottom-ghost'),
            edge('condition_4-condition-bottom-ghost', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_2'),
            edge('condition_2', 'condition_2-condition-top-ghost'),
            edge('condition_2-condition-top-ghost', 'loggerTask'),
            edge('loggerTask', 'condition_2-condition-bottom-ghost'),
            edge('condition_2-condition-top-ghost', 'condition_2-condition-right-placeholder-0'),
            edge('condition_2-condition-right-placeholder-0', 'condition_2-condition-bottom-ghost'),
            edge('condition_2-condition-bottom-ghost', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1400, direction: 'TB', edges, nodes});

        // The shallow frame's bar keeps the uniform box gap to its condition...
        const shallowConditionBottom = positionOf(result.nodes, 'condition_4').y + 72;
        const shallowTopGhostBarY = positionOf(result.nodes, 'condition_4-condition-top-ghost').y;

        expect(shallowTopGhostBarY - shallowConditionBottom).toBe(TOP_BOX_GAP);

        // ...the deep defining chain keeps the designed entry gap...
        const outerTopGhostBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;

        expect(positionOf(result.nodes, 'condition_2').y - (outerTopGhostBarY + 2)).toBe(BAR_TO_CHILD_GAP);

        // ...and the shallow subtree floats centered in the outer interior
        const outerBottomGhostBarY = positionOf(result.nodes, 'condition_1-condition-bottom-ghost').y;
        const outerInteriorCenter = (outerTopGhostBarY + 2 + outerBottomGhostBarY) / 2;

        const shallowSubtreeStart = positionOf(result.nodes, 'condition_4').y;
        const shallowSubtreeEnd = positionOf(result.nodes, 'condition_4-condition-bottom-ghost').y + 2;
        const shallowSubtreeCenter = (shallowSubtreeStart + shallowSubtreeEnd) / 2;

        expect(Math.abs(shallowSubtreeCenter - outerInteriorCenter)).toBeLessThanOrEqual(1);
    });
});

describe('getElkLayoutElements with loops', () => {
    const populatedLoopFixture = () => {
        const nodes: Node[] = [
            taskNode('task1'),
            loopNode('loop_1'),
            ...loopAuxNodes('loop_1'),
            loopChildTaskNode('loopChild1', 'loop_1'),
            loopChildTaskNode('loopChild2', 'loop_1'),
            taskNode('task2'),
        ];

        const edges: Edge[] = [
            edge('task1', 'loop_1'),
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loopChild1'),
            edge('loopChild1', 'loopChild2'),
            edge('loopChild2', 'loop_1-loop-bottom-ghost'),
            edge('loop_1-loop-bottom-ghost', 'task2'),
        ];

        return {edges, nodes};
    };

    it('wraps loop members in a frame and keeps the loop node outside it', () => {
        const {edges, nodes} = populatedLoopFixture();

        const graph = buildElkGraph(nodes, edges, 'TB');

        expect(childIds(graph)).toEqual(['loop_1', getFrameId('loop_1'), 'task1', 'task2']);

        const frame = findChild(graph, getFrameId('loop_1'));

        // The loop-back rail is a decoration positioned after layout, not an ELK member
        expect(childIds(frame)).toEqual([
            'loopChild1',
            'loopChild2',
            'loop_1-loop-bottom-ghost',
            'loop_1-loop-top-ghost',
        ]);

        expect(collectScopeEdgeViolations(graph)).toEqual([]);
    });

    it('centers the loop body under the loop node with the rail to the left', async () => {
        const {edges, nodes} = populatedLoopFixture();

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // Content chain sits on the loop axis; the rail is not an entry
        const loopCenter = positionOf(result.nodes, 'loop_1').x + 36;
        const childCenter = positionOf(result.nodes, 'loopChild1').x + 36;

        expect(Math.abs(childCenter - loopCenter)).toBeLessThanOrEqual(1);

        // Rail hugs the body content (icons start at the bar's left end, hug pad 20)
        const railX = positionOf(result.nodes, 'loop_1-taskDispatcher-left-ghost').x;
        const topBarX = positionOf(result.nodes, 'loop_1-loop-top-ghost').x;

        expect(railX).toBe(topBarX - 20);

        // Loop boxes get the same top-bar pull as conditions; uniform chain step inside
        const loopBottom = positionOf(result.nodes, 'loop_1').y + 72;
        const topGhostBarY = positionOf(result.nodes, 'loop_1-loop-top-ghost').y;
        const bottomGhostBarY = positionOf(result.nodes, 'loop_1-loop-bottom-ghost').y;
        const firstChildTop = positionOf(result.nodes, 'loopChild1').y;
        const secondChildTop = positionOf(result.nodes, 'loopChild2').y;

        expect(topGhostBarY - loopBottom).toBe(TOP_BOX_GAP);
        expect(firstChildTop - (topGhostBarY + 2)).toBe(BAR_TO_CHILD_GAP);
        expect(secondChildTop - firstChildTop).toBe(CHAIN_STEP);
        expect(bottomGhostBarY - (secondChildTop + 72)).toBe(BOX_GAP);
    });

    it('centers an empty loop placeholder inside the frame', async () => {
        const nodes: Node[] = [loopNode('loop_1'), ...loopAuxNodes('loop_1'), loopPlaceholderNode('loop_1')];

        const edges: Edge[] = [
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loop_1-loop-placeholder-0'),
            edge('loop_1-loop-placeholder-0', 'loop_1-loop-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // The empty ring renders SQUARE: the "+" sits on the right edge and the
        // rail mirrors it, each half the ring's own bar-to-bar span off the axis
        const topGhostBarY = positionOf(result.nodes, 'loop_1-loop-top-ghost').y;
        const bottomGhostBarY = positionOf(result.nodes, 'loop_1-loop-bottom-ghost').y;
        const ringHalfWidth = (bottomGhostBarY - topGhostBarY + 2) / 2;

        const loopCenter = positionOf(result.nodes, 'loop_1').x + 36;
        const placeholderCenter = positionOf(result.nodes, 'loop_1-loop-placeholder-0').x + 36;

        expect(placeholderCenter - loopCenter).toBe(ringHalfWidth);

        const railCenter = positionOf(result.nodes, 'loop_1-taskDispatcher-left-ghost').x + 1;

        expect(loopCenter - railCenter).toBe(ringHalfWidth);

        const placeholderMainCenter = positionOf(result.nodes, 'loop_1-loop-placeholder-0').y + 14;

        expect(Math.abs(placeholderMainCenter - (topGhostBarY + bottomGhostBarY + 2) / 2)).toBeLessThanOrEqual(1);
    });

    it('renders the empty loop ring square in LR too', async () => {
        const nodes: Node[] = [loopNode('loop_1'), ...loopAuxNodes('loop_1'), loopPlaceholderNode('loop_1')];

        const edges: Edge[] = [
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loop_1-loop-placeholder-0'),
            edge('loop_1-loop-placeholder-0', 'loop_1-loop-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({
            canvasHeight: 800,
            canvasWidth: 1000,
            direction: 'LR',
            edges,
            nodes,
        });

        // LR ring span differs from TB (no label pull, wider placeholder
        // footprint) — the square derives from the direction's own span
        const topGhostBarX = positionOf(result.nodes, 'loop_1-loop-top-ghost').x;
        const bottomGhostBarX = positionOf(result.nodes, 'loop_1-loop-bottom-ghost').x;
        const ringHalfWidth = (bottomGhostBarX - topGhostBarX + 2) / 2;

        const loopCenter = positionOf(result.nodes, 'loop_1').y + 36;
        const placeholderCenter = positionOf(result.nodes, 'loop_1-loop-placeholder-0').y + 14;

        expect(placeholderCenter - loopCenter).toBe(ringHalfWidth);

        const railCenter = positionOf(result.nodes, 'loop_1-taskDispatcher-left-ghost').y + 1;

        expect(loopCenter - railCenter).toBe(ringHalfWidth);
    });

    it('keeps a loop on its branch side inside a condition and centers its body', async () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            loopNode('loop_1', {conditionCase: 'caseTrue', conditionId: 'condition_1'}),
            ...loopAuxNodes('loop_1'),
            loopChildTaskNode('loopChild1', 'loop_1'),
            taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'loop_1'),
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loopChild1'),
            edge('loopChild1', 'loop_1-loop-bottom-ghost'),
            edge('loop_1-loop-bottom-ghost', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'childFalse1'),
            edge('childFalse1', 'condition_1-condition-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1400, direction: 'TB', edges, nodes});

        // Loop (caseTrue) stays left of the FALSE branch child
        expect(positionOf(result.nodes, 'loop_1').x).toBeLessThan(positionOf(result.nodes, 'childFalse1').x);

        // The loop body centers under the loop node even when nested
        const loopCenter = positionOf(result.nodes, 'loop_1').x + 36;
        const loopChildCenter = positionOf(result.nodes, 'loopChild1').x + 36;

        expect(Math.abs(loopChildCenter - loopCenter)).toBeLessThanOrEqual(1);

        // Uniform pulled top gap at nesting depth
        const loopBottom = positionOf(result.nodes, 'loop_1').y + 72;
        const loopTopBarY = positionOf(result.nodes, 'loop_1-loop-top-ghost').y;

        expect(loopTopBarY - loopBottom).toBe(TOP_BOX_GAP);
    });

    it('lays out a condition nested inside a loop body', async () => {
        const nodes: Node[] = [
            loopNode('loop_1'),
            ...loopAuxNodes('loop_1'),
            conditionNode('condition_1', undefined),
            ...conditionGhostNodes('condition_1'),
            conditionPlaceholderNode('condition_1', 'left'),
            conditionPlaceholderNode('condition_1', 'right'),
        ];

        (nodes[4].data as Record<string, unknown>).loopData = {index: 0, loopId: 'loop_1'};

        const edges: Edge[] = [
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'condition_1'),
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-left-placeholder-0'),
            edge('condition_1-condition-top-ghost', 'condition_1-condition-right-placeholder-0'),
            edge('condition_1-condition-left-placeholder-0', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-right-placeholder-0', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-bottom-ghost', 'loop_1-loop-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1400, direction: 'TB', edges, nodes});

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);

        // The nested condition centers on the loop axis, and its own frame keeps
        // the condition label pull
        const loopCenter = positionOf(result.nodes, 'loop_1').x + 36;
        const conditionCenter = positionOf(result.nodes, 'condition_1').x + 36;

        expect(Math.abs(conditionCenter - loopCenter)).toBeLessThanOrEqual(1);

        const conditionBottom = positionOf(result.nodes, 'condition_1').y + 72;
        const conditionTopBarY = positionOf(result.nodes, 'condition_1-condition-top-ghost').y;

        expect(conditionTopBarY - conditionBottom).toBe(TOP_BOX_GAP);
    });

    it('keeps uniform gaps in a loop nested inside a loop', async () => {
        const nodes: Node[] = [
            loopNode('loop_1'),
            ...loopAuxNodes('loop_1'),
            loopNode('loop_2', {loopId: 'loop_1'}),
            ...loopAuxNodes('loop_2'),
            loopChildTaskNode('innerChild', 'loop_2'),
        ];

        const edges: Edge[] = [
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loop_2'),
            ...loopStructureEdges('loop_2'),
            edge('loop_2-loop-top-ghost', 'innerChild'),
            edge('innerChild', 'loop_2-loop-bottom-ghost'),
            edge('loop_2-loop-bottom-ghost', 'loop_1-loop-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1400, direction: 'TB', edges, nodes});

        const outerLoopBottom = positionOf(result.nodes, 'loop_1').y + 72;
        const outerTopBarY = positionOf(result.nodes, 'loop_1-loop-top-ghost').y;
        const innerLoopBottom = positionOf(result.nodes, 'loop_2').y + 72;
        const innerTopBarY = positionOf(result.nodes, 'loop_2-loop-top-ghost').y;

        expect(outerTopBarY - outerLoopBottom).toBe(TOP_BOX_GAP);
        expect(innerTopBarY - innerLoopBottom).toBe(TOP_BOX_GAP);

        // Merge stub inner bottom bar → outer bottom bar keeps the box gap
        const innerBottomBarY = positionOf(result.nodes, 'loop_2-loop-bottom-ghost').y;
        const outerBottomBarY = positionOf(result.nodes, 'loop_1-loop-bottom-ghost').y;

        expect(outerBottomBarY - (innerBottomBarY + 2)).toBe(BOX_GAP);

        // Nested rings indent: the outer rail sits RAIL_NESTED_RING_INDENT left
        // of the inner rail (innermost positioned first, dagre parity)
        const innerRailX = positionOf(result.nodes, 'loop_2-taskDispatcher-left-ghost').x;
        const outerRailX = positionOf(result.nodes, 'loop_1-taskDispatcher-left-ghost').x;

        expect(outerRailX).toBe(innerRailX - 50);
    });

    it('lays out childless dispatchers as plain chain nodes inside a loop', async () => {
        const loopBreakNode: Node = {
            data: {
                componentName: 'loopBreak',
                loopData: {index: 0, loopId: 'loop_1'},
                taskDispatcher: true,
                taskDispatcherId: 'loopBreak_1',
                workflowNodeName: 'loopBreak_1',
            },
            id: 'loopBreak_1',
            position: {x: 0, y: 0},
            type: 'workflow',
        };

        const nodes: Node[] = [
            loopNode('loop_1'),
            ...loopAuxNodes('loop_1'),
            loopChildTaskNode('loopChild1', 'loop_1'),
            loopBreakNode,
        ];

        const edges: Edge[] = [
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loopChild1'),
            edge('loopChild1', 'loopBreak_1'),
            edge('loopBreak_1', 'loop_1-loop-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        // loopBreak gets no frame and steps like an ordinary task
        expect(result.nodes.map((node) => node.id)).not.toContain(getFrameId('loopBreak_1'));

        const chainStep = positionOf(result.nodes, 'loopBreak_1').y - positionOf(result.nodes, 'loopChild1').y;

        expect(chainStep).toBe(CHAIN_STEP);
    });
});

describe('getElkLayoutElements with branches', () => {
    const branchNode = (id: string, caseKeys: string[]): Node => ({
        data: {
            componentName: 'branch',
            parameters: {cases: caseKeys.map((caseKey) => ({key: caseKey, tasks: []}))},
            taskDispatcher: true,
            taskDispatcherId: id,
            workflowNodeName: id,
        },
        id,
        position: {x: 0, y: 0},
        type: 'workflow',
    });

    const branchGhostNodes = (branchId: string): Node[] => [
        {
            data: {branchId, taskDispatcherId: branchId},
            id: `${branchId}-branch-top-ghost`,
            position: {x: 0, y: 0},
            type: 'taskDispatcherTopGhostNode',
        },
        {
            data: {branchId, taskDispatcherId: branchId},
            id: `${branchId}-branch-bottom-ghost`,
            position: {x: 0, y: 0},
            type: 'taskDispatcherBottomGhostNode',
        },
    ];

    const branchCasePlaceholderNode = (branchId: string, caseKey: string): Node => ({
        data: {branchId, caseKey, label: '+', taskDispatcherId: branchId},
        id: `${branchId}-branch-${caseKey}-placeholder-0`,
        position: {x: 0, y: 0},
        type: 'placeholder',
    });

    const branchChildTaskNode = (id: string, branchId: string, caseKey: string): Node => ({
        data: {branchData: {branchId, caseKey, index: 0}, componentName: 'mailchimp', workflowNodeName: id},
        id,
        position: {x: 0, y: 0},
        type: 'workflow',
    });

    it('orders branch case columns by the params-derived canonical order, not array order', async () => {
        // Ordering trap per spec: the case_a placeholder is created BEFORE all
        // chain tasks in the flat array, but canonically default < case_a < case_b
        const nodes: Node[] = [
            branchNode('branch_1', ['case_a', 'case_b']),
            ...branchGhostNodes('branch_1'),
            branchCasePlaceholderNode('branch_1', 'case_a'),
            branchChildTaskNode('defaultChild', 'branch_1', 'default'),
            branchChildTaskNode('caseBChild', 'branch_1', 'case_b'),
        ];

        const edges: Edge[] = [
            edge('branch_1', 'branch_1-branch-top-ghost'),
            edge('branch_1-branch-top-ghost', 'defaultChild'),
            edge('defaultChild', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'branch_1-branch-case_a-placeholder-0'),
            edge('branch_1-branch-case_a-placeholder-0', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'caseBChild'),
            edge('caseBChild', 'branch_1-branch-bottom-ghost'),
        ];

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);

        const result = await getElkLayoutElements({canvasWidth: 1600, direction: 'TB', edges, nodes});

        const defaultCenter = positionOf(result.nodes, 'defaultChild').x + 36;
        const caseACenter = positionOf(result.nodes, 'branch_1-branch-case_a-placeholder-0').x + 36;
        const caseBCenter = positionOf(result.nodes, 'caseBChild').x + 36;

        expect(defaultCenter).toBeLessThan(caseACenter);
        expect(caseACenter).toBeLessThan(caseBCenter);

        // Odd case count: the middle column sits on the branch axis, and the
        // branch node sits midway between the outer columns
        const branchCenter = positionOf(result.nodes, 'branch_1').x + 36;

        expect(Math.abs(caseACenter - branchCenter)).toBeLessThanOrEqual(1);
        expect(Math.abs((defaultCenter + caseBCenter) / 2 - branchCenter)).toBeLessThanOrEqual(1);

        // Standard box gaps at the branch frame
        const branchBottom = positionOf(result.nodes, 'branch_1').y + 72;
        const topGhostBarY = positionOf(result.nodes, 'branch_1-branch-top-ghost').y;

        expect(topGhostBarY - branchBottom).toBe(TOP_BOX_GAP);
    });

    it('pins the middle case column to the branch axis even with an asymmetric outer case', async () => {
        // dagre parity (alignBranchCaseChildren): the middle case's edges leave
        // the bar's bottom handle and must run straight — a wide outer subtree
        // must not drag the middle column off the axis (mean-centering would)
        const nodes: Node[] = [
            branchNode('branch_1', ['case_a', 'case_b']),
            ...branchGhostNodes('branch_1'),
            {
                data: {
                    branchData: {branchId: 'branch_1', caseKey: 'default', index: 0},
                    componentName: 'condition',
                    taskDispatcher: true,
                    taskDispatcherId: 'condition_9',
                    workflowNodeName: 'condition_9',
                },
                id: 'condition_9',
                position: {x: 0, y: 0},
                type: 'workflow',
            },
            ...conditionGhostNodes('condition_9'),
            conditionPlaceholderNode('condition_9', 'left'),
            conditionPlaceholderNode('condition_9', 'right'),
            branchChildTaskNode('caseAChild', 'branch_1', 'case_a'),
            branchChildTaskNode('caseBChild', 'branch_1', 'case_b'),
        ];

        const edges: Edge[] = [
            edge('branch_1', 'branch_1-branch-top-ghost'),
            edge('branch_1-branch-top-ghost', 'condition_9'),
            edge('condition_9', 'condition_9-condition-top-ghost'),
            edge('condition_9-condition-top-ghost', 'condition_9-condition-left-placeholder-0'),
            edge('condition_9-condition-top-ghost', 'condition_9-condition-right-placeholder-0'),
            edge('condition_9-condition-left-placeholder-0', 'condition_9-condition-bottom-ghost'),
            edge('condition_9-condition-right-placeholder-0', 'condition_9-condition-bottom-ghost'),
            edge('condition_9-condition-bottom-ghost', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'caseAChild'),
            edge('caseAChild', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'caseBChild'),
            edge('caseBChild', 'branch_1-branch-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 2000, direction: 'TB', edges, nodes});

        // Middle case (case_a) sits exactly on the branch axis despite the wide
        // default-case condition subtree on the left
        const branchCenter = positionOf(result.nodes, 'branch_1').x + 36;
        const caseACenter = positionOf(result.nodes, 'caseAChild').x + 36;

        expect(Math.abs(caseACenter - branchCenter)).toBeLessThanOrEqual(1);
    });

    it('ranks unknown case keys last', async () => {
        const nodes: Node[] = [
            branchNode('branch_1', ['case_a']),
            ...branchGhostNodes('branch_1'),
            branchChildTaskNode('strayChild', 'branch_1', 'zzz_unknown'),
            branchChildTaskNode('caseAChild', 'branch_1', 'case_a'),
            branchChildTaskNode('defaultChild', 'branch_1', 'default'),
        ];

        const edges: Edge[] = [
            edge('branch_1', 'branch_1-branch-top-ghost'),
            edge('branch_1-branch-top-ghost', 'defaultChild'),
            edge('defaultChild', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'caseAChild'),
            edge('caseAChild', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'strayChild'),
            edge('strayChild', 'branch_1-branch-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1600, direction: 'TB', edges, nodes});

        const defaultCenter = positionOf(result.nodes, 'defaultChild').x;
        const caseACenter = positionOf(result.nodes, 'caseAChild').x;
        const strayCenter = positionOf(result.nodes, 'strayChild').x;

        expect(defaultCenter).toBeLessThan(caseACenter);
        expect(caseACenter).toBeLessThan(strayCenter);
    });

    it('lays out a loop inside a branch case and keeps canonical order', async () => {
        const nodes: Node[] = [
            branchNode('branch_1', ['case_a']),
            ...branchGhostNodes('branch_1'),
            branchChildTaskNode('defaultChild', 'branch_1', 'default'),
            {
                data: {
                    branchData: {branchId: 'branch_1', caseKey: 'case_a', index: 0},
                    componentName: 'loop',
                    taskDispatcher: true,
                    taskDispatcherId: 'loop_1',
                    workflowNodeName: 'loop_1',
                },
                id: 'loop_1',
                position: {x: 0, y: 0},
                type: 'workflow',
            },
            ...loopAuxNodes('loop_1'),
            loopChildTaskNode('loopChild1', 'loop_1'),
        ];

        const edges: Edge[] = [
            edge('branch_1', 'branch_1-branch-top-ghost'),
            edge('branch_1-branch-top-ghost', 'defaultChild'),
            edge('defaultChild', 'branch_1-branch-bottom-ghost'),
            edge('branch_1-branch-top-ghost', 'loop_1'),
            ...loopStructureEdges('loop_1'),
            edge('loop_1-loop-top-ghost', 'loopChild1'),
            edge('loopChild1', 'loop_1-loop-bottom-ghost'),
            edge('loop_1-loop-bottom-ghost', 'branch_1-branch-bottom-ghost'),
        ];

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);

        const result = await getElkLayoutElements({canvasWidth: 1600, direction: 'TB', edges, nodes});

        // default column left of the case_a loop subtree
        expect(positionOf(result.nodes, 'defaultChild').x).toBeLessThan(positionOf(result.nodes, 'loop_1').x);

        // The nested loop body still centers under the loop node
        const loopCenter = positionOf(result.nodes, 'loop_1').x + 36;
        const loopChildCenter = positionOf(result.nodes, 'loopChild1').x + 36;

        expect(Math.abs(loopChildCenter - loopCenter)).toBeLessThanOrEqual(1);
    });
});

describe('getElkLayoutElements with parallel and fork-join', () => {
    const parallelNode = (id: string): Node => ({
        data: {componentName: 'parallel', taskDispatcher: true, taskDispatcherId: id, workflowNodeName: id},
        id,
        position: {x: 0, y: 0},
        type: 'workflow',
    });

    // Mirrors createParallelNode: bottom ghost carries only taskDispatcherId
    const parallelAuxNodes = (parallelId: string, options: {withRail: boolean}): Node[] => [
        {
            data: {parallelId, taskDispatcherId: parallelId},
            id: `${parallelId}-parallel-top-ghost`,
            position: {x: 0, y: 0},
            type: 'taskDispatcherTopGhostNode',
        },
        ...(options.withRail
            ? [
                  {
                      data: {parallelId, taskDispatcherId: parallelId},
                      id: `${parallelId}-taskDispatcher-left-ghost`,
                      position: {x: 0, y: 0},
                      type: 'taskDispatcherLeftGhostNode',
                  } as Node,
              ]
            : []),
        {
            data: {label: '+', parallelId, taskDispatcherId: parallelId},
            id: `${parallelId}-parallel-placeholder-0`,
            position: {x: 0, y: 0},
            type: 'placeholder',
        },
        {
            data: {taskDispatcherId: parallelId},
            id: `${parallelId}-parallel-bottom-ghost`,
            position: {x: 0, y: 0},
            type: 'taskDispatcherBottomGhostNode',
        },
    ];

    const parallelChildTaskNode = (id: string, parallelId: string, index: number): Node => ({
        data: {componentName: 'mailchimp', parallelData: {index, parallelId}, workflowNodeName: id},
        id,
        position: {x: 0, y: 0},
        type: 'workflow',
    });

    // Mirrors createForkJoinNode: camelCase 'forkJoin' id segment; placeholders
    // carry a top-level branchIndex
    const forkJoinNode = (id: string): Node => ({
        data: {componentName: 'fork-join', taskDispatcher: true, taskDispatcherId: id, workflowNodeName: id},
        id,
        position: {x: 0, y: 0},
        type: 'workflow',
    });

    const forkJoinAuxNodes = (forkJoinId: string, options: {withRail: boolean}): Node[] => [
        {
            data: {forkJoinId, taskDispatcherId: forkJoinId},
            id: `${forkJoinId}-forkJoin-top-ghost`,
            position: {x: 0, y: 0},
            type: 'taskDispatcherTopGhostNode',
        },
        ...(options.withRail
            ? [
                  {
                      data: {forkJoinId, taskDispatcherId: forkJoinId},
                      id: `${forkJoinId}-taskDispatcher-left-ghost`,
                      position: {x: 0, y: 0},
                      type: 'taskDispatcherLeftGhostNode',
                  } as Node,
              ]
            : []),
        {
            data: {taskDispatcherId: forkJoinId},
            id: `${forkJoinId}-forkJoin-bottom-ghost`,
            position: {x: 0, y: 0},
            type: 'taskDispatcherBottomGhostNode',
        },
    ];

    const forkJoinPlaceholderNode = (forkJoinId: string, branchIndex: number): Node => ({
        data: {branchIndex, forkJoinId, label: '+', taskDispatcherId: forkJoinId},
        id: `${forkJoinId}-forkJoin-placeholder-${branchIndex}`,
        position: {x: 0, y: 0},
        type: 'placeholder',
    });

    const forkJoinChildTaskNode = (id: string, forkJoinId: string, branchIndex: number, index: number): Node => ({
        data: {componentName: 'mailchimp', forkJoinData: {branchIndex, forkJoinId, index}, workflowNodeName: id},
        id,
        position: {x: 0, y: 0},
        type: 'workflow',
    });

    it('orders parallel columns by index with the trailing placeholder last', async () => {
        const nodes: Node[] = [
            parallelNode('parallel_1'),
            ...parallelAuxNodes('parallel_1', {withRail: false}),
            parallelChildTaskNode('p1', 'parallel_1', 0),
            parallelChildTaskNode('p2', 'parallel_1', 1),
            parallelChildTaskNode('p3', 'parallel_1', 2),
        ];

        const edges: Edge[] = [
            edge('parallel_1', 'parallel_1-parallel-top-ghost'),
            edge('parallel_1-parallel-top-ghost', 'p1'),
            edge('p1', 'parallel_1-parallel-bottom-ghost'),
            edge('parallel_1-parallel-top-ghost', 'p2'),
            edge('p2', 'parallel_1-parallel-bottom-ghost'),
            edge('parallel_1-parallel-top-ghost', 'p3'),
            edge('p3', 'parallel_1-parallel-bottom-ghost'),
            edge('parallel_1-parallel-top-ghost', 'parallel_1-parallel-placeholder-0'),
            edge('parallel_1-parallel-placeholder-0', 'parallel_1-parallel-bottom-ghost'),
        ];

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);

        const result = await getElkLayoutElements({canvasWidth: 2000, direction: 'TB', edges, nodes});

        const p1Center = positionOf(result.nodes, 'p1').x + 36;
        const p2Center = positionOf(result.nodes, 'p2').x + 36;
        const p3Center = positionOf(result.nodes, 'p3').x + 36;
        const placeholderCenter = positionOf(result.nodes, 'parallel_1-parallel-placeholder-0').x + 36;

        expect(p1Center).toBeLessThan(p2Center);
        expect(p2Center).toBeLessThan(p3Center);
        expect(p3Center).toBeLessThan(placeholderCenter);

        // Even column count (3 tasks + placeholder): dispatcher on the mean
        const parallelCenter = positionOf(result.nodes, 'parallel_1').x + 36;
        const columnMean = (p1Center + p2Center + p3Center + placeholderCenter) / 4;

        expect(Math.abs(columnMean - parallelCenter)).toBeLessThanOrEqual(1);

        const parallelBottom = positionOf(result.nodes, 'parallel_1').y + 72;
        const topGhostBarY = positionOf(result.nodes, 'parallel_1-parallel-top-ghost').y;

        expect(topGhostBarY - parallelBottom).toBe(TOP_BOX_GAP);
    });

    it('renders an empty parallel as a square ring', async () => {
        const nodes: Node[] = [parallelNode('parallel_1'), ...parallelAuxNodes('parallel_1', {withRail: true})];

        const edges: Edge[] = [
            edge('parallel_1', 'parallel_1-parallel-top-ghost'),
            edge('parallel_1-parallel-top-ghost', 'parallel_1-taskDispatcher-left-ghost'),
            edge('parallel_1-taskDispatcher-left-ghost', 'parallel_1-parallel-bottom-ghost'),
            edge('parallel_1-parallel-top-ghost', 'parallel_1-parallel-placeholder-0'),
            edge('parallel_1-parallel-placeholder-0', 'parallel_1-parallel-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const topGhostBarY = positionOf(result.nodes, 'parallel_1-parallel-top-ghost').y;
        const bottomGhostBarY = positionOf(result.nodes, 'parallel_1-parallel-bottom-ghost').y;
        const ringHalfWidth = (bottomGhostBarY - topGhostBarY + 2) / 2;

        const parallelCenter = positionOf(result.nodes, 'parallel_1').x + 36;
        const placeholderCenter = positionOf(result.nodes, 'parallel_1-parallel-placeholder-0').x + 36;
        const railCenter = positionOf(result.nodes, 'parallel_1-taskDispatcher-left-ghost').x + 1;

        expect(placeholderCenter - parallelCenter).toBe(ringHalfWidth);
        expect(parallelCenter - railCenter).toBe(ringHalfWidth);
    });

    it('orders fork-join branch columns by branchIndex with chains inside', async () => {
        const nodes: Node[] = [
            forkJoinNode('forkJoin_1'),
            ...forkJoinAuxNodes('forkJoin_1', {withRail: false}),
            forkJoinPlaceholderNode('forkJoin_1', 2),
            forkJoinChildTaskNode('f1', 'forkJoin_1', 0, 0),
            forkJoinChildTaskNode('f2', 'forkJoin_1', 0, 1),
            forkJoinChildTaskNode('g1', 'forkJoin_1', 1, 0),
        ];

        const edges: Edge[] = [
            edge('forkJoin_1', 'forkJoin_1-forkJoin-top-ghost'),
            edge('forkJoin_1-forkJoin-top-ghost', 'f1'),
            edge('f1', 'f2'),
            edge('f2', 'forkJoin_1-forkJoin-bottom-ghost'),
            edge('forkJoin_1-forkJoin-top-ghost', 'g1'),
            edge('g1', 'forkJoin_1-forkJoin-bottom-ghost'),
            edge('forkJoin_1-forkJoin-top-ghost', 'forkJoin_1-forkJoin-placeholder-2'),
            edge('forkJoin_1-forkJoin-placeholder-2', 'forkJoin_1-forkJoin-bottom-ghost'),
        ];

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);

        const result = await getElkLayoutElements({canvasWidth: 2000, direction: 'TB', edges, nodes});

        const branch0Center = positionOf(result.nodes, 'f1').x + 36;
        const branch1Center = positionOf(result.nodes, 'g1').x + 36;
        const placeholderCenter = positionOf(result.nodes, 'forkJoin_1-forkJoin-placeholder-2').x + 36;

        expect(branch0Center).toBeLessThan(branch1Center);
        expect(branch1Center).toBeLessThan(placeholderCenter);

        // Odd column count (2 branches + placeholder): median column on the axis
        const forkJoinCenter = positionOf(result.nodes, 'forkJoin_1').x + 36;

        expect(Math.abs(branch1Center - forkJoinCenter)).toBeLessThanOrEqual(1);

        // Chain rhythm inside branch 0
        expect(positionOf(result.nodes, 'f2').y - positionOf(result.nodes, 'f1').y).toBe(CHAIN_STEP);
    });

    it('renders an empty fork-join as a square ring via camelCase ghost ids', async () => {
        const nodes: Node[] = [
            forkJoinNode('forkJoin_1'),
            ...forkJoinAuxNodes('forkJoin_1', {withRail: true}),
            forkJoinPlaceholderNode('forkJoin_1', 0),
        ];

        const edges: Edge[] = [
            edge('forkJoin_1', 'forkJoin_1-forkJoin-top-ghost'),
            edge('forkJoin_1-forkJoin-top-ghost', 'forkJoin_1-taskDispatcher-left-ghost'),
            edge('forkJoin_1-taskDispatcher-left-ghost', 'forkJoin_1-forkJoin-bottom-ghost'),
            edge('forkJoin_1-forkJoin-top-ghost', 'forkJoin_1-forkJoin-placeholder-0'),
            edge('forkJoin_1-forkJoin-placeholder-0', 'forkJoin_1-forkJoin-bottom-ghost'),
        ];

        const result = await getElkLayoutElements({canvasWidth: 1000, direction: 'TB', edges, nodes});

        const topGhostBarY = positionOf(result.nodes, 'forkJoin_1-forkJoin-top-ghost').y;
        const bottomGhostBarY = positionOf(result.nodes, 'forkJoin_1-forkJoin-bottom-ghost').y;
        const ringHalfWidth = (bottomGhostBarY - topGhostBarY + 2) / 2;

        const forkJoinCenter = positionOf(result.nodes, 'forkJoin_1').x + 36;
        const placeholderCenter = positionOf(result.nodes, 'forkJoin_1-forkJoin-placeholder-0').x + 36;
        const railCenter = positionOf(result.nodes, 'forkJoin_1-taskDispatcher-left-ghost').x + 1;

        // The 38px pulled top gap proves the camelCase ghost ids resolved
        const forkJoinBottom = positionOf(result.nodes, 'forkJoin_1').y + 72;

        expect(topGhostBarY - forkJoinBottom).toBe(TOP_BOX_GAP);
        expect(placeholderCenter - forkJoinCenter).toBe(ringHalfWidth);
        expect(forkJoinCenter - railCenter).toBe(ringHalfWidth);
    });

    it('lays out a parallel inside a condition branch', async () => {
        const nodes: Node[] = [
            conditionNode('condition_1'),
            ...conditionGhostNodes('condition_1'),
            {
                data: {
                    componentName: 'parallel',
                    conditionData: {conditionCase: 'caseTrue', conditionId: 'condition_1', index: 0},
                    taskDispatcher: true,
                    taskDispatcherId: 'parallel_1',
                    workflowNodeName: 'parallel_1',
                },
                id: 'parallel_1',
                position: {x: 0, y: 0},
                type: 'workflow',
            },
            ...parallelAuxNodes('parallel_1', {withRail: false}),
            parallelChildTaskNode('p1', 'parallel_1', 0),
            taskNode('childFalse1', {conditionCase: 'caseFalse', conditionId: 'condition_1'}),
        ];

        const edges: Edge[] = [
            edge('condition_1', 'condition_1-condition-top-ghost'),
            edge('condition_1-condition-top-ghost', 'parallel_1'),
            edge('parallel_1', 'parallel_1-parallel-top-ghost'),
            edge('parallel_1-parallel-top-ghost', 'p1'),
            edge('p1', 'parallel_1-parallel-bottom-ghost'),
            edge('parallel_1-parallel-top-ghost', 'parallel_1-parallel-placeholder-0'),
            edge('parallel_1-parallel-placeholder-0', 'parallel_1-parallel-bottom-ghost'),
            edge('parallel_1-parallel-bottom-ghost', 'condition_1-condition-bottom-ghost'),
            edge('condition_1-condition-top-ghost', 'childFalse1'),
            edge('childFalse1', 'condition_1-condition-bottom-ghost'),
        ];

        expect(collectScopeEdgeViolations(buildElkGraph(nodes, edges, 'TB'))).toEqual([]);

        const result = await getElkLayoutElements({canvasWidth: 2000, direction: 'TB', edges, nodes});

        // Parallel (caseTrue) stays left of the FALSE branch child
        expect(positionOf(result.nodes, 'parallel_1').x).toBeLessThan(positionOf(result.nodes, 'childFalse1').x);
    });
});

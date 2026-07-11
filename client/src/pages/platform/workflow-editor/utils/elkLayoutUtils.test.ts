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

const edge = (source: string, target: string): Edge => ({id: `${source}=>${target}`, source, target});

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

        expect(topGhostBarY - conditionBottom).toBe(BOX_GAP);
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

        expect(topGhostBarY - conditionBottom).toBe(BOX_GAP);
        expect(loggerTaskTop - (topGhostBarY + 2)).toBe(BOX_GAP);
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

        expect(topGhostBarY - (900 + 72)).toBe(BOX_GAP);
        expect(loggerTaskTop - (topGhostBarY + 2)).toBe(BOX_GAP);
        expect(bottomGhostBarY - (loggerTaskTop + 72)).toBe(BOX_GAP);

        const topGhostCenter = positionOf(result.nodes, 'condition_1-condition-top-ghost').x + 36;

        expect(Math.abs(topGhostCenter - (400 + 36))).toBeLessThanOrEqual(1);
    });
});

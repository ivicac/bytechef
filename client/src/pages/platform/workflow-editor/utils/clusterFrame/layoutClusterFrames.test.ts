import {Edge, Node} from '@xyflow/react';
import {describe, expect, it} from 'vitest';

import {
    CLUSTER_FRAME_HEADER_HEIGHT,
    CLUSTER_FRAME_MIN_HEIGHT,
    CLUSTER_FRAME_MIN_WIDTH,
    CLUSTER_FRAME_PADDING,
    fromClusterFrameChildPosition,
    toClusterFrameChildPosition,
} from './clusterFrameGeometry';
import {layoutClusterFrames} from './layoutClusterFrames';

const ROOT_ID = 'aiAgent_1';

function buildRootNode(): Node {
    return {
        data: {clusterRoot: true, workflowNodeName: ROOT_ID},
        id: ROOT_ID,
        position: {x: 0, y: 0},
        type: 'workflow',
    };
}

function buildElementNode(id: string, position: {x: number; y: number}): Node {
    return {
        data: {clusterElementType: 'model', parentClusterRootId: ROOT_ID, workflowNodeName: id},
        id,
        measured: {height: 60, width: 200},
        parentId: ROOT_ID,
        position,
        type: 'workflow',
    };
}

// A "+" placeholder, as `createPlaceholderNode` builds it: no `parentClusterRootId`, `type: 'placeholder'`.
function buildPlaceholderElementNode(id: string, position: {x: number; y: number}): Node {
    return {
        data: {clusterElementType: 'model', label: '+'},
        id,
        measured: {height: 40, width: 40},
        parentId: ROOT_ID,
        position,
        type: 'placeholder',
    };
}

describe('layoutClusterFrames', () => {
    it('sizes the root from its elements and moves them out of the outer array', () => {
        const elementNode = buildElementNode('model_1', {x: 400, y: 300});

        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {[ROOT_ID]: [elementNode]},
            },
            {}
        );

        const rootNode = result.outerNodes.find((node) => node.id === ROOT_ID)!;

        expect(rootNode.data.clusterFrame).toEqual({
            clusterRootId: ROOT_ID,
            height: 300 + 60 + CLUSTER_FRAME_PADDING + CLUSTER_FRAME_HEADER_HEIGHT,
            width: 400 + 200 + CLUSTER_FRAME_PADDING,
        });

        expect(result.outerNodes.map((node) => node.id)).toEqual([ROOT_ID]);
        expect(result.memberNodes.map((node) => node.id)).toEqual(['model_1']);
    });

    it('floors the box at its minimum size', () => {
        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 0, y: 0})]},
            },
            {}
        );

        expect(result.outerNodes[0].data.clusterFrame).toEqual({
            clusterRootId: ROOT_ID,
            height: CLUSTER_FRAME_MIN_HEIGHT,
            width: CLUSTER_FRAME_MIN_WIDTH,
        });
    });

    it('leaves a root with no elements untouched', () => {
        const result = layoutClusterFrames([buildRootNode()], [], {edgesByRootId: {}, nodesByRootId: {}}, {});

        expect(result.outerNodes[0].data.clusterFrame).toBeUndefined();
        expect(result.memberNodes).toEqual([]);
    });

    it('keeps a cluster root inside a graph member out of the outer array', () => {
        const rootInGraph: Node = {
            data: {clusterRoot: true, graphData: {graphId: 'graph_1', index: 0}, workflowNodeName: ROOT_ID},
            id: ROOT_ID,
            position: {x: 0, y: 0},
            type: 'workflow',
        };

        const result = layoutClusterFrames(
            [rootInGraph],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 400, y: 300})]},
            },
            {}
        );

        // The elements must not reach layoutGraphFrames: findGraphMemberOwner walks dispatcher
        // nesting fields and does not follow parentId, so it cannot classify them as frame members
        // and would leave them at root scope with a frame-relative position.
        expect(result.outerNodes.map((node) => node.id)).toEqual([ROOT_ID]);
        expect(result.memberNodes.map((node) => node.id)).toEqual(['model_1']);
        expect((result.outerNodes[0].data.clusterFrame as {width: number}).width).toBeGreaterThan(
            CLUSTER_FRAME_MIN_WIDTH
        );
    });

    it('partitions edges between the box and the outer array in both directions', () => {
        const memberEdge: Edge = {id: 'edge_root_model', source: ROOT_ID, target: 'model_1'};
        const edgeTouchingMember: Edge = {id: 'edge_model_other', source: 'model_1', target: 'other_node'};
        const chainEdge: Edge = {id: 'edge_chain', source: 'task_1', target: 'task_2'};

        const result = layoutClusterFrames(
            [buildRootNode()],
            [edgeTouchingMember, chainEdge],
            {
                edgesByRootId: {[ROOT_ID]: [memberEdge]},
                nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 0, y: 0})]},
            },
            {}
        );

        // An edge recorded against the root in edgesByRootId lands in memberEdges.
        expect(result.memberEdges.map((edge) => edge.id)).toEqual(['edge_root_model']);

        // An edge from the outer `edges` argument that merely touches a member node id is stripped
        // too (the id-based filter this task exists to guarantee), while an edge touching neither
        // member survives untouched.
        expect(result.outerEdges.map((edge) => edge.id)).toEqual(['edge_chain']);
    });

    it('marks elements draggable only when their root is unlocked', () => {
        const elements = {
            edgesByRootId: {[ROOT_ID]: []},
            nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 0, y: 0})]},
        };

        expect(layoutClusterFrames([buildRootNode()], [], elements, {}).memberNodes[0].draggable).toBe(false);

        expect(layoutClusterFrames([buildRootNode()], [], elements, {[ROOT_ID]: false}).memberNodes[0].draggable).toBe(
            true
        );
    });

    // A placeholder carries no `parentClusterRootId`, so the drag-stop handler's cluster branch would
    // never recognise a drag on one — it would fall through to the generic outer-flow branch and fire
    // a save whose position key matches no real task name. Never making it draggable in the first
    // place avoids that dead-end drag entirely, unlocked root or not.
    it('never marks a placeholder element draggable, even on an unlocked root', () => {
        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {
                    [ROOT_ID]: [
                        buildElementNode('model_1', {x: 0, y: 0}),
                        buildPlaceholderElementNode('model-placeholder-0', {x: 260, y: 0}),
                    ],
                },
            },
            {[ROOT_ID]: false}
        );

        const placeholderNode = result.memberNodes.find((node) => node.id === 'model-placeholder-0')!;
        const elementNode = result.memberNodes.find((node) => node.id === 'model_1')!;

        expect(placeholderNode.draggable).toBe(false);
        expect(elementNode.draggable).toBe(true);
    });
});

describe('toClusterFrameChildPosition / fromClusterFrameChildPosition', () => {
    it('are inverses of each other', () => {
        const contentPosition = {x: 123, y: 456};

        expect(fromClusterFrameChildPosition(toClusterFrameChildPosition(contentPosition))).toEqual(contentPosition);
    });
});

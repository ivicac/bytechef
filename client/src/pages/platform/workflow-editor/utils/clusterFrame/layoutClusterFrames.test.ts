import {CLUSTER_ROOT_NODE_WIDTH} from '@/shared/constants';
import {Edge, Node} from '@xyflow/react';
import {describe, expect, it} from 'vitest';

import {
    CLUSTER_FRAME_HEADER_HEIGHT,
    CLUSTER_FRAME_MIN_HEIGHT,
    CLUSTER_FRAME_MIN_WIDTH,
    CLUSTER_FRAME_PADDING,
    fromClusterFrameChildPosition,
    getClusterMemberSize,
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

/**
 * A member whose position the user has already set. Saved positions are the only ones the placer
 * honours verbatim (`containsNodePosition`), so every sizing assertion below uses one — an element
 * WITHOUT a saved position is placed by the placer, which is what
 * `clusterFrameFromDefinition.test.tsx` covers end to end.
 *
 * No `measured`: production never has it, and sizing off it is what made every member 0x0.
 */
function buildElementNode(id: string, position: {x: number; y: number}, parentId: string = ROOT_ID): Node {
    return {
        data: {
            clusterElementType: 'model',
            metadata: {ui: {nodePosition: position}},
            parentClusterRootElementsTypeCount: 1,
            parentClusterRootId: parentId,
            workflowNodeName: id,
        },
        id,
        parentId,
        position,
        type: 'workflow',
    };
}

// A "+" placeholder, as `createPlaceholderNode` builds it: no `parentClusterRootId`, `type: 'placeholder'`.
function buildPlaceholderElementNode(id: string, position: {x: number; y: number}): Node {
    return {
        data: {clusterElementType: 'model', label: '+'},
        id,
        parentId: ROOT_ID,
        position,
        type: 'placeholder',
    };
}

const ELEMENT_SIZE = getClusterMemberSize(buildElementNode('sizing_probe', {x: 0, y: 0}));

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
            contentOrigin: {x: 0, y: CLUSTER_FRAME_HEADER_HEIGHT},
            height: 300 + ELEMENT_SIZE.height + CLUSTER_FRAME_HEADER_HEIGHT + CLUSTER_FRAME_PADDING,
            width: 400 + ELEMENT_SIZE.width + CLUSTER_FRAME_PADDING,
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
            contentOrigin: {x: 0, y: CLUSTER_FRAME_HEADER_HEIGHT},
            height: CLUSTER_FRAME_MIN_HEIGHT,
            width: CLUSTER_FRAME_MIN_WIDTH,
        });
    });

    // The root card is part of the box's contents, so a box whose members all sit inside its
    // footprint still has to be wide and tall enough to hold the card itself.
    it('reserves room for the root card', () => {
        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: 0, y: 600})]},
            },
            {}
        );

        const clusterFrame = result.outerNodes[0].data.clusterFrame as {height: number; width: number};

        expect(clusterFrame.width).toBeGreaterThanOrEqual(CLUSTER_ROOT_NODE_WIDTH + CLUSTER_FRAME_PADDING);
        expect(clusterFrame.height).toBeGreaterThanOrEqual(
            600 + ELEMENT_SIZE.height + CLUSTER_FRAME_HEADER_HEIGHT + CLUSTER_FRAME_PADDING
        );
    });

    // A saved position left of the root card is routine, not exotic — the placer computes a leftmost
    // child's x as `handleX - CLUSTER_ELEMENT_NODE_WIDTH / 2` and `saveClusterElementNodesPosition`
    // writes those values back. Without a content origin such a member renders outside the border,
    // and the member drag extent then refuses to let it be dragged back.
    it('pushes the content origin in so a member at a negative x still lands inside the box', () => {
        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {[ROOT_ID]: [buildElementNode('model_1', {x: -120, y: 200})]},
            },
            {}
        );

        const clusterFrame = result.outerNodes[0].data.clusterFrame as {
            contentOrigin: {x: number; y: number};
            height: number;
            width: number;
        };

        expect(clusterFrame.contentOrigin.x).toBe(120);
        expect(result.memberNodes[0].position.x).toBe(0);
        expect(result.memberNodes[0].position.x + ELEMENT_SIZE.width).toBeLessThanOrEqual(clusterFrame.width);

        // The stored value is unchanged by the shift: the drag-stop handler subtracts the same
        // origin again, so a member's persisted position never drifts because a sibling moved left.
        expect(fromClusterFrameChildPosition(result.memberNodes[0].position, clusterFrame.contentOrigin)).toEqual({
            x: -120,
            y: 200,
        });
    });

    // Only DIRECT members cross into frame coordinates. A nested root's own children are positioned
    // against that nested root, which already carries the offset.
    it('keeps nested members parented to their nested root and out of frame coordinates', () => {
        const nestedRootNode = buildElementNode('sub_agent_1', {x: 100, y: 200});
        const nestedMemberNode = buildElementNode('nested_model_1', {x: 10, y: 150}, 'sub_agent_1');

        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {[ROOT_ID]: [nestedRootNode, nestedMemberNode]},
            },
            {}
        );

        const placedNestedRoot = result.memberNodes.find((node) => node.id === 'sub_agent_1')!;
        const placedNestedMember = result.memberNodes.find((node) => node.id === 'nested_model_1')!;

        expect(placedNestedRoot.parentId).toBe(ROOT_ID);
        expect(placedNestedRoot.position).toEqual(toClusterFrameChildPosition({x: 100, y: 200}));

        expect(placedNestedMember.parentId).toBe('sub_agent_1');
        expect(placedNestedMember.position).toEqual({x: 10, y: 150});
    });

    // A nested member sits inside the box too, so the frame has to contain it even though its own
    // position is measured from its nested root rather than from the root card.
    it('sizes the box around a nested member', () => {
        const result = layoutClusterFrames(
            [buildRootNode()],
            [],
            {
                edgesByRootId: {[ROOT_ID]: []},
                nodesByRootId: {
                    [ROOT_ID]: [
                        buildElementNode('sub_agent_1', {x: 500, y: 200}),
                        buildElementNode('nested_model_1', {x: 300, y: 150}, 'sub_agent_1'),
                    ],
                },
            },
            {}
        );

        const clusterFrame = result.outerNodes[0].data.clusterFrame as {height: number; width: number};

        expect(clusterFrame.width).toBeGreaterThanOrEqual(500 + 300 + ELEMENT_SIZE.width);
        expect(clusterFrame.height).toBeGreaterThanOrEqual(200 + 150 + ELEMENT_SIZE.height);
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

describe('getClusterMemberSize', () => {
    // The regression this guards: member nodes are rebuilt fresh on every layout and carry neither
    // `measured` nor `width`/`height`, so a size read from those was 0 for every member in production
    // and non-zero only in fixtures that hand-set it.
    it('sizes an unmeasured member from its kind rather than to zero', () => {
        const size = getClusterMemberSize(buildElementNode('model_1', {x: 0, y: 0}));

        expect(size.height).toBeGreaterThan(0);
        expect(size.width).toBeGreaterThan(0);
    });

    it('sizes a nested cluster root wider than a plain element, and a placeholder smaller', () => {
        const nestedClusterRootNode = buildElementNode('sub_agent_1', {x: 0, y: 0});

        nestedClusterRootNode.data.clusterElementTypesCount = 2;

        expect(getClusterMemberSize(nestedClusterRootNode).width).toBeGreaterThan(ELEMENT_SIZE.width);
        expect(getClusterMemberSize(buildPlaceholderElementNode('p', {x: 0, y: 0})).width).toBeLessThan(
            ELEMENT_SIZE.width
        );
    });

    it('falls back to the measured size for a shape it does not recognise', () => {
        const unknownNode: Node = {
            data: {},
            id: 'unknown',
            measured: {height: 55, width: 155},
            position: {x: 0, y: 0},
            type: 'workflow',
        };

        expect(getClusterMemberSize(unknownNode)).toEqual({height: 55, width: 155});
    });
});

describe('toClusterFrameChildPosition / fromClusterFrameChildPosition', () => {
    it('are inverses of each other', () => {
        const contentPosition = {x: 123, y: 456};

        expect(fromClusterFrameChildPosition(toClusterFrameChildPosition(contentPosition))).toEqual(contentPosition);
    });

    it('are inverses of each other under a shifted content origin', () => {
        const contentPosition = {x: -120, y: 456};
        const contentOrigin = {x: 120, y: CLUSTER_FRAME_HEADER_HEIGHT};

        expect(
            fromClusterFrameChildPosition(toClusterFrameChildPosition(contentPosition, contentOrigin), contentOrigin)
        ).toEqual(contentPosition);
    });
});

import {act, renderHook} from '@testing-library/react';
import {Node, ReactFlowProvider} from '@xyflow/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useWorkflowDataStore from '../../stores/useWorkflowDataStore';
import {
    fromClusterFrameChildPosition,
    toClusterFrameChildPosition,
} from '../../utils/clusterFrame/clusterFrameGeometry';
import {clearAllWorkflowMutations} from '../../utils/workflowMutationGuard';
import useWorkflowEditorCanvas from '../useWorkflowEditorCanvas';

const {editorContext, updateWorkflowMutationMock} = vi.hoisted(() => {
    const mutation = {isPending: false, mutate: vi.fn()};

    return {
        editorContext: {updateWorkflowMutation: mutation as typeof mutation | undefined},
        updateWorkflowMutationMock: mutation,
    };
});

vi.mock('../useLayout', () => ({
    default: () => ({autoPlacedGraphPositionsRef: {current: {}}}),
}));

vi.mock('../useHandleDrop', () => ({
    default: () => [vi.fn(), vi.fn(), vi.fn(), vi.fn()],
}));

vi.mock('../useStickyNotes', () => ({
    default: () => ({handleAddStickyNote: vi.fn()}),
}));

vi.mock('../../providers/workflowEditorProvider', () => ({
    useWorkflowEditor: () => editorContext,
}));

const ROOT_ID = 'aiAgent_1';

function buildDefinition(elementPosition: {x: number; y: number}): string {
    return JSON.stringify({
        tasks: [
            {
                clusterElements: {
                    model: {
                        metadata: {ui: {nodePosition: elementPosition}},
                        name: 'model_1',
                        type: 'openAiChat/v1',
                    },
                },
                name: ROOT_ID,
                type: 'aiAgent/v1',
            },
        ],
    });
}

function buildCanvasNodes(elementContentPosition: {x: number; y: number}): Node[] {
    return [
        {
            data: {
                clusterFrame: {clusterRootId: ROOT_ID, height: 320, width: 640},
                clusterRoot: true,
                workflowNodeName: ROOT_ID,
            },
            id: ROOT_ID,
            position: {x: 0, y: 0},
            type: 'workflow',
        },
        {
            data: {clusterElementType: 'model', parentClusterRootId: ROOT_ID, workflowNodeName: 'model_1'},
            draggable: true,
            id: 'model_1',
            measured: {height: 60, width: 200},
            parentId: ROOT_ID,
            position: toClusterFrameChildPosition(elementContentPosition),
            type: 'workflow',
        },
    ];
}

function renderCanvas() {
    return renderHook(() => useWorkflowEditorCanvas({componentDefinitions: [], taskDispatcherDefinitions: []}), {
        wrapper: ({children}: {children: ReactNode}) => <ReactFlowProvider>{children}</ReactFlowProvider>,
    });
}

function findNode(id: string): Node {
    return useWorkflowDataStore.getState().nodes.find((node) => node.id === id)!;
}

/** Reads the element's persisted content-origin position back out of the definition the last save sent. */
function getSavedElementPosition(): {x: number; y: number} | undefined {
    const [[mutationVariables]] = updateWorkflowMutationMock.mutate.mock.calls.slice(-1);

    const savedTasks: Array<{
        clusterElements: {model: {metadata?: {ui?: {nodePosition?: {x: number; y: number}}}}};
    }> = JSON.parse((mutationVariables as {workflow: {definition: string}}).workflow.definition).tasks;

    return savedTasks[0].clusterElements.model.metadata?.ui?.nodePosition;
}

describe('useWorkflowEditorCanvas cluster element dragging', () => {
    beforeEach(() => {
        editorContext.updateWorkflowMutation = updateWorkflowMutationMock;
        updateWorkflowMutationMock.mutate.mockClear();

        // `saveWorkflowDefinition` drops a save that arrives while a previous one is still in
        // flight, and the mock mutation never settles — without this reset every save after the
        // first in a test would be silently dropped instead of fired.
        clearAllWorkflowMutations();

        useWorkflowDataStore.setState({
            edges: [],
            nodes: buildCanvasNodes({x: 0, y: 0}),
            savedPositionCrossAxisShift: 0,
            workflow: {
                definition: buildDefinition({x: 0, y: 0}),
                id: 'workflow-1',
                nodeNames: [],
                // The flattened DTO copy of the definition's tasks, kept in sync alongside it in the
                // live app. `saveWorkflowDefinition` matches an existing task through THIS array (not
                // the parsed definition) to decide whether to update the root in place or insert a
                // new one — an empty array here would make every save look like a brand new task and
                // duplicate the root instead of updating it.
                tasks: [{clusterRoot: true, name: ROOT_ID, type: 'aiAgent/v1'}],
                version: 3,
            },
        });
    });

    it('converts a dropped element back through the header offset before persisting it', () => {
        const {result} = renderCanvas();

        // The live drag position is parent-relative (it includes the header band); dropped at what
        // reads on screen as content-origin (220, 140), React Flow's own position is offset by the
        // header height.
        act(() => {
            result.current.handleNodeDragStop({} as MouseEvent, {
                ...findNode('model_1'),
                position: toClusterFrameChildPosition({x: 220, y: 140}),
            });
        });

        expect(getSavedElementPosition()).toEqual({x: 220, y: 140});
    });

    // Pins the conversion direction itself: persisting the raw, un-converted parent-relative
    // position (skipping fromClusterFrameChildPosition) is exactly the header-height drift the
    // brief warns about, and this assertion fails the moment that conversion is dropped.
    it('does not persist the raw parent-relative position unconverted', () => {
        const {result} = renderCanvas();

        const droppedPosition = toClusterFrameChildPosition({x: 220, y: 140});

        act(() => {
            result.current.handleNodeDragStop({} as MouseEvent, {
                ...findNode('model_1'),
                position: droppedPosition,
            });
        });

        expect(getSavedElementPosition()).not.toEqual(droppedPosition);
        expect(getSavedElementPosition()).toEqual(fromClusterFrameChildPosition(droppedPosition));
    });

    it('does nothing when there is no mutation to persist through', () => {
        editorContext.updateWorkflowMutation = undefined;

        const {result} = renderCanvas();

        act(() => {
            result.current.handleNodeDragStop({} as MouseEvent, {
                ...findNode('model_1'),
                position: toClusterFrameChildPosition({x: 220, y: 140}),
            });
        });

        expect(updateWorkflowMutationMock.mutate).not.toHaveBeenCalled();
    });

    it('does nothing when the cluster root task carries no cluster elements', () => {
        useWorkflowDataStore.setState({
            workflow: {
                definition: JSON.stringify({tasks: [{name: ROOT_ID, type: 'aiAgent/v1'}]}),
                id: 'workflow-1',
                nodeNames: [],
                version: 3,
            },
        });

        const {result} = renderCanvas();

        act(() => {
            result.current.handleNodeDragStop({} as MouseEvent, {
                ...findNode('model_1'),
                position: toClusterFrameChildPosition({x: 220, y: 140}),
            });
        });

        expect(updateWorkflowMutationMock.mutate).not.toHaveBeenCalled();
    });
});

// A cluster root can itself contain a nested cluster root (e.g. an agentic tool with its own model),
// and an element inside THAT nested box carries `parentClusterRootId` naming the nested root — not
// the outermost one `getTask`/`workflowTasks` can actually address. `topLevelClusterRootId` is what
// resolves back to a real task entry, threaded unchanged through the nesting by
// `createClusterElementNodes`.
describe('useWorkflowEditorCanvas cluster element dragging, nested a level deeper', () => {
    const NESTED_ROOT_ID = 'agenticTool_1';
    const NESTED_ELEMENT_ID = 'nestedModel_1';

    function buildNestedDefinition(elementPosition: {x: number; y: number}): string {
        return JSON.stringify({
            tasks: [
                {
                    clusterElements: {
                        tools: [
                            {
                                clusterElements: {
                                    model: {
                                        metadata: {ui: {nodePosition: elementPosition}},
                                        name: NESTED_ELEMENT_ID,
                                        type: 'openAiChat/v1',
                                    },
                                },
                                clusterRoot: true,
                                name: NESTED_ROOT_ID,
                                type: 'agenticTool/v1',
                            },
                        ],
                    },
                    name: ROOT_ID,
                    type: 'aiAgent/v1',
                },
            ],
        });
    }

    function buildNestedElementNode(elementContentPosition: {x: number; y: number}): Node {
        return {
            data: {
                clusterElementType: 'model',
                parentClusterRootId: NESTED_ROOT_ID,
                topLevelClusterRootId: ROOT_ID,
                workflowNodeName: NESTED_ELEMENT_ID,
            },
            draggable: true,
            id: NESTED_ELEMENT_ID,
            measured: {height: 60, width: 200},
            parentId: NESTED_ROOT_ID,
            position: toClusterFrameChildPosition(elementContentPosition),
            type: 'workflow',
        };
    }

    function getSavedNestedElementPosition(): {x: number; y: number} | undefined {
        const [[mutationVariables]] = updateWorkflowMutationMock.mutate.mock.calls.slice(-1);

        const savedTasks: Array<{
            clusterElements: {
                tools: Array<{clusterElements: {model: {metadata?: {ui?: {nodePosition?: {x: number; y: number}}}}}}>;
            };
        }> = JSON.parse((mutationVariables as {workflow: {definition: string}}).workflow.definition).tasks;

        return savedTasks[0].clusterElements.tools[0].clusterElements.model.metadata?.ui?.nodePosition;
    }

    beforeEach(() => {
        editorContext.updateWorkflowMutation = updateWorkflowMutationMock;
        updateWorkflowMutationMock.mutate.mockClear();
        clearAllWorkflowMutations();

        useWorkflowDataStore.setState({
            edges: [],
            nodes: [buildNestedElementNode({x: 0, y: 0})],
            savedPositionCrossAxisShift: 0,
            workflow: {
                definition: buildNestedDefinition({x: 0, y: 0}),
                id: 'workflow-1',
                nodeNames: [],
                tasks: [{clusterRoot: true, name: ROOT_ID, type: 'aiAgent/v1'}],
                version: 3,
            },
        });
    });

    it('resolves through the outermost root rather than the immediate (nested) parent', () => {
        const {result} = renderCanvas();

        act(() => {
            result.current.handleNodeDragStop({} as MouseEvent, {
                ...findNode(NESTED_ELEMENT_ID),
                position: toClusterFrameChildPosition({x: 220, y: 140}),
            });
        });

        // A save keyed on the immediate parent (`agenticTool_1`) would have found no such task at the
        // top level of the definition and saved nothing at all.
        expect(updateWorkflowMutationMock.mutate).toHaveBeenCalledTimes(1);
        expect(getSavedNestedElementPosition()).toEqual({x: 220, y: 140});
    });
});

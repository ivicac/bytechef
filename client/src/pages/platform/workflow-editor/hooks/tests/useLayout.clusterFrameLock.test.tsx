import {act, renderHook, waitFor} from '@testing-library/react';
import {Node} from '@xyflow/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useClusterElementsViewModeStore from '../../stores/useClusterElementsViewModeStore';
import useLayoutEngineStore from '../../stores/useLayoutEngineStore';
import useWorkflowDataStore from '../../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../../stores/useWorkflowEditorStore';
import useLayout from '../useLayout';

// Hoisted so the SAME object identity is returned on every call — the real useClusterElementNodes
// memoizes these with useMemo, stable across re-renders unless the underlying definition changes.
// A mock that hands back a fresh object literal on every call would make the layout effect re-run on
// every single render regardless of what actually changed, silently defeating the very thing this
// test exists to pin: whether toggling the lock, and ONLY that, is what re-triggers it. Written with
// literal ids rather than ROOT_ID/ELEMENT_ID constants because vi.hoisted runs before any module-level
// `const` below it is initialised.
const {clusterElementEdgesByRootId, clusterElementNodesByRootId} = vi.hoisted(() => ({
    clusterElementEdgesByRootId: {aiAgent_1: [] as unknown[]},
    clusterElementNodesByRootId: {
        aiAgent_1: [
            {
                data: {clusterElementType: 'model', parentClusterRootId: 'aiAgent_1', workflowNodeName: 'model_1'},
                id: 'model_1',
                measured: {height: 60, width: 200},
                position: {x: 0, y: 0},
                type: 'workflow',
            },
        ],
    },
}));

const ROOT_ID = 'aiAgent_1';
const ELEMENT_ID = 'model_1';

// `useClusterElementNodes` does its own component-definition fetching over react-query, which this
// test has no interest in exercising — box mode's cluster element/edge arrays are the input this
// hook's own layout wiring is under test with, not how they got built.
vi.mock('../../../cluster-element-editor/hooks/useClusterElementNodes', () => ({
    default: () => ({
        definitionsReady: true,
        edgesByRootId: clusterElementEdgesByRootId,
        nodesByRootId: clusterElementNodesByRootId,
    }),
}));

function findElementNode(): Node {
    return useWorkflowDataStore.getState().nodes.find((node) => node.id === ELEMENT_ID)!;
}

describe('useLayout reacts to a cluster box lock toggle', () => {
    beforeEach(() => {
        // ELK is a web-worker-backed engine with nothing to gain here — dagre is a plain synchronous
        // computation and the two are otherwise interchangeable for what this test checks.
        useLayoutEngineStore.getState().setLayoutEngine('dagre');
        useClusterElementsViewModeStore.getState().setClusterElementsViewMode('box');
        useWorkflowEditorStore.setState({clusterFrameLockedByRootId: {}});

        useWorkflowDataStore.setState({
            edges: [],
            isWorkflowLoaded: true,
            layoutResetCounter: 0,
            nodes: [],
            savedPositionCrossAxisShift: 0,
            workflow: {
                definition: JSON.stringify({
                    tasks: [
                        {
                            clusterElements: {
                                model: {name: ELEMENT_ID, type: 'openAiChat/v1'},
                            },
                            name: ROOT_ID,
                            type: 'aiAgent/v1',
                        },
                    ],
                    triggers: [{name: 'trigger_1', parameters: {}, type: 'manual/v1/manual'}],
                }),
                id: 'workflow-1',
                nodeNames: [],
                tasks: [
                    {
                        clusterElements: {model: {name: ELEMENT_ID, type: 'openAiChat/v1'}},
                        clusterRoot: true,
                        name: ROOT_ID,
                        type: 'aiAgent/v1',
                    },
                ],
                triggers: [{name: 'trigger_1', parameters: {}, type: 'manual/v1/manual'}],
                version: 1,
            },
        });
    });

    it('re-stamps a member node draggable the moment its box is unlocked, without any other trigger', async () => {
        renderHook(() =>
            useLayout({canvasHeight: 800, canvasWidth: 1200, componentDefinitions: [], taskDispatcherDefinitions: []})
        );

        await waitFor(() => expect(findElementNode()).toBeDefined());
        await waitFor(() => expect(findElementNode().draggable).toBe(false));

        act(() => {
            useWorkflowEditorStore.getState().setClusterFrameLocked(ROOT_ID, false);
        });

        await waitFor(() => expect(findElementNode().draggable).toBe(true), {interval: 10, timeout: 5000});
    });
});

import {act, renderHook} from '@testing-library/react';
import {Node} from '@xyflow/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useWorkflowDataStore from '../../workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore, {WorkflowEditorI} from '../../workflow-editor/stores/useWorkflowEditorStore';
import useClusterElementsDataStore from '../stores/useClusterElementsDataStore';
import useClusterElementsLayout from './useClusterElementsLayout';

vi.mock('@/shared/queries/platform/componentDefinitions.queries', () => ({
    ComponentDefinitionKeys: {
        componentDefinition: (request: {componentName: string; componentVersion: number}) => [
            'componentDefinition',
            request.componentName,
            request.componentVersion,
        ],
    },
    useGetComponentDefinitionQuery: () => ({data: componentDefinitionsByName.aiAgent}),
}));

vi.mock('@/shared/middleware/platform/configuration', () => ({
    ComponentDefinitionApi: class {
        getComponentDefinition({componentName}: {componentName: string}) {
            return Promise.resolve(componentDefinitionsByName[componentName]);
        }
    },
}));

import useClusterElementsDataStore from '../stores/useClusterElementsDataStore';
import useClusterElementsLayout from './useClusterElementsLayout';

const getWorkflowDefinition = (chatMemoryType: string, chatMemoryName: string) =>
    JSON.stringify({
        tasks: [
            {
                clusterElements: {
                    chatMemory: {clusterElements: {}, label: 'Memory', name: chatMemoryName, type: chatMemoryType},
                },
                label: 'AI Agent',
                name: 'aiAgent_1',
                type: 'aiAgent/v1/chat',
            },
        ],
    });

const getNodeIds = () => useClusterElementsDataStore.getState().nodes.map((node) => node.id);

const getRootX = () =>
    useClusterElementsDataStore.getState().nodes.find((node) => node.id === 'aiAgent_1')?.position.x ?? Number.NaN;

describe('useClusterElementsLayout', () => {
    let pendingAnimationFrameCallbacks: Array<FrameRequestCallback> = [];

    const flushAnimationFrames = (time: number) => {
        const callbacks = pendingAnimationFrameCallbacks;

        pendingAnimationFrameCallbacks = [];

        callbacks.forEach((callback) => callback(time));
    };

    beforeEach(() => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});

        pendingAnimationFrameCallbacks = [];

        vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
            pendingAnimationFrameCallbacks.push(callback);

            return pendingAnimationFrameCallbacks.length;
        });
        vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {
            pendingAnimationFrameCallbacks = [];
        });

        useClusterElementsDataStore.setState({
            edges: [],
            isNodeDragging: false,
            isPositionSaving: false,
            nodes: [],
        });

        useWorkflowDataStore.setState({
            workflow: {definition: getWorkflowDefinition('awsChatMemory/v2/chatMemory', 'awsChatMemory_1'), id: 'w1'},
        } as never);

        useWorkflowEditorStore.setState({
            clusterRootComponentDefinitions: {
                [ROOT_NODE_NAME]: {
                    clusterElement: false,
                    clusterRoot: true,
                    connectionRequired: false,
                    name: 'aiAgent',
                    version: 1,
                },
            },
            nestedClusterRootsComponentDefinitions: {},
            rootClusterElementNodeData: {
                clusterRoot: true,
                componentName: 'aiAgent',
                name: ROOT_NODE_NAME,
                operationName: 'chat',
                type: 'aiAgent/v1/chat',
                workflowNodeName: 'aiAgent_1',
            },
        } as Partial<WorkflowEditorI>);

        useWorkflowNodeDetailsPanelStore.setState({workflowNodeDetailsPanelOpen: true} as never);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    const renderLayout = async () => {
        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        const wrapper = ({children}: {children: ReactNode}) =>
            createElement(QueryClientProvider, {client: queryClient}, children);

        const renderedHook = renderHook(() => useClusterElementsLayout(), {wrapper});

        await waitFor(() => expect(getNodeIds()).toContain('awsChatMemory_1-model-placeholder-0'));

        return renderedHook;
    };

    const setDetailsPanelOpen = (workflowNodeDetailsPanelOpen: boolean) =>
        act(() => {
            useWorkflowNodeDetailsPanelStore.setState({workflowNodeDetailsPanelOpen} as never);
        });

    const replaceAwsMemoryWithJdbcMemory = async () => {
        act(() => {
            useWorkflowDataStore.setState({
                workflow: {
                    definition: getWorkflowDefinition('jdbcChatMemory/v2/chatMemory', 'jdbcChatMemory_1'),
                    id: 'w1',
                },
            } as never);
        });

        await waitFor(() =>
            expect(useWorkflowEditorStore.getState().nestedClusterRootsComponentDefinitions).toHaveProperty(
                'jdbcChatMemory'
            )
        );

        await waitFor(() => expect(getNodeIds()).toContain('jdbcChatMemory_1-dataSource-placeholder-0'));
    };

    it('keeps the nested root placeholders laid out while the panel-close animation is running', async () => {
        await renderLayout();

        // Replacing the selected memory closes the details panel, which starts the canvas-width animation...
        setDetailsPanelOpen(false);

        expect(pendingAnimationFrameCallbacks.length).toBeGreaterThan(0);

        // ...and the saved workflow then swaps the memory component while the animation is still in flight.
        await replaceAwsMemoryWithJdbcMemory();

        act(() => {
            flushAnimationFrames(0);
            flushAnimationFrames(10_000);
        });

        expect(getNodeIds()).toEqual(
            expect.arrayContaining([
                'jdbcChatMemory_1',
                'jdbcChatMemory_1-dataSource-placeholder-0',
                'jdbcChatMemory_1-model-placeholder-0',
            ])
        );
        expect(getNodeIds()).not.toContain('awsChatMemory_1');
    });

    it('slides the canvas to its new width when the panel closes', async () => {
        await renderLayout();

        const initialRootX = getRootX();

        setDetailsPanelOpen(false);

        act(() => {
            flushAnimationFrames(0);
            flushAnimationFrames(100);
        });

        const midSlideRootX = getRootX();

        act(() => {
            flushAnimationFrames(10_000);
        });

        const slidRootX = getRootX();

        expect(midSlideRootX).toBeGreaterThan(initialRootX);
        expect(slidRootX).toBeGreaterThan(midSlideRootX);
        expect(pendingAnimationFrameCallbacks).toHaveLength(0);
    });

    it('replaces the running slide when the panel reopens mid-animation', async () => {
        await renderLayout();

        setDetailsPanelOpen(false);

        act(() => {
            flushAnimationFrames(0);
            flushAnimationFrames(100);
        });

        const midSlideRootX = getRootX();

        setDetailsPanelOpen(true);

        expect(window.cancelAnimationFrame).toHaveBeenCalled();

        act(() => {
            flushAnimationFrames(0);
            flushAnimationFrames(10_000);
        });

        expect(getRootX()).toBeLessThan(midSlideRootX);
    });

    it('stops the slide when the canvas unmounts', async () => {
        const {unmount} = await renderLayout();

        setDetailsPanelOpen(false);

        const nodesBeforeUnmount = useClusterElementsDataStore.getState().nodes;

        unmount();

        expect(window.cancelAnimationFrame).toHaveBeenCalled();
        expect(pendingAnimationFrameCallbacks).toHaveLength(0);
        expect(useClusterElementsDataStore.getState().nodes).toBe(nodesBeforeUnmount);
    });
});

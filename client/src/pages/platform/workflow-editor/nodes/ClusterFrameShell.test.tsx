import {TooltipProvider} from '@/components/ui/tooltip';
import {applicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {NodeDataType} from '@/shared/types';
import {act, render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {useClusterElementsCanvasDialogStore} from '../components/stores/useClusterElementsCanvasDialogStore';
import useClusterFrameCollapsedStore from '../stores/useClusterFrameCollapsedStore';
import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import ClusterFrameShell from './ClusterFrameShell';

vi.mock('@xyflow/react', () => ({
    Handle: ({className, id}: {className?: string; id?: string}) => <div className={className} data-testid={id} />,
    Position: {Bottom: 'bottom', Left: 'left', Right: 'right', Top: 'top'},
}));

const {editorContext, saveWorkflowDefinitionMock, updateWorkflowMutationMock} = vi.hoisted(() => {
    const mutation = {isPending: false, mutate: vi.fn()};

    return {
        editorContext: {updateWorkflowMutation: mutation as typeof mutation | undefined},
        saveWorkflowDefinitionMock: vi.fn(),
        updateWorkflowMutationMock: mutation,
    };
});

vi.mock('../providers/workflowEditorProvider', () => ({
    useWorkflowEditor: () => editorContext,
}));

vi.mock('../utils/saveWorkflowDefinition', () => ({default: saveWorkflowDefinitionMock}));

const CLUSTER_ROOT_DATA = {
    clusterFrame: {clusterRootId: 'aiAgent_1', height: 320, width: 640},
    label: 'AI Agent',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

const AI_AGENT_ROOT_DATA = {
    ...CLUSTER_ROOT_DATA,
    componentName: 'aiAgent',
    name: 'aiAgent_1',
} as unknown as NodeDataType;

function buildWorkflowWithClusterRoot(): void {
    useWorkflowDataStore.setState({
        workflow: {
            definition: JSON.stringify({
                tasks: [
                    {
                        clusterElements: {
                            model: {
                                metadata: {ui: {nodePosition: {x: 40, y: 20}}},
                                name: 'model_1',
                                type: 'openAiChat/v1',
                            },
                        },
                        name: 'aiAgent_1',
                        type: 'aiAgent/v1',
                    },
                ],
            }),
            id: 'workflow-1',
            nodeNames: [],
            // The flattened DTO copy of the definition's tasks — `saveWorkflowDefinition` matches
            // through this array (not the parsed definition) to update the root in place instead of
            // inserting a duplicate. See the equivalent note in useWorkflowEditorCanvas.clusterDrag.
            tasks: [{clusterRoot: true, name: 'aiAgent_1', type: 'aiAgent/v1'}],
            version: 1,
        },
    });
}

describe('ClusterFrameShell', () => {
    beforeEach(() => {
        editorContext.updateWorkflowMutation = updateWorkflowMutationMock;
        saveWorkflowDefinitionMock.mockReset();

        useWorkflowEditorStore.setState({
            clusterElementsCanvasOpen: false,
            clusterFrameLockedByRootId: {},
            rootClusterElementNodeData: undefined,
        });
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
        useWorkflowDataStore.setState({workflow: {definition: undefined, id: 'workflow-1', nodeNames: [], version: 1}});
        useClusterElementsCanvasDialogStore.setState({
            showDataStreamEditor: false,
            testingPanelOpen: false,
        });
        applicationInfoStore.setState({featureFlags: {}});
    });

    it('paints the box at the size the pre-pass computed', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        const shell = screen.getByTestId('cluster-frame-shell');

        expect(shell).toHaveStyle({height: '320px', width: '640px'});
        expect(screen.getByText('root card')).toBeInTheDocument();
    });

    it('renders the children bare when no box has been computed', () => {
        render(
            <ClusterFrameShell data={{label: 'AI Agent'} as NodeDataType} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.queryByTestId('cluster-frame-shell')).not.toBeInTheDocument();
        expect(screen.getByText('root card')).toBeInTheDocument();
    });

    it('renders the lock and reset header controls, starting locked', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.getByRole('button', {name: 'Unlock node movement'})).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Reset layout'})).toBeInTheDocument();
    });

    // An execution view (or any other surface with no mutation to edit the workflow with) gets
    // neither control, rather than rendering them disabled — the same choice GraphFrameNode makes.
    it('leaves the header controls out when there is no mutation to edit the workflow with', () => {
        editorContext.updateWorkflowMutation = undefined;

        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.queryByRole('button', {name: 'Unlock node movement'})).not.toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Reset layout'})).not.toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Collapse cluster elements'})).not.toBeInTheDocument();
        expect(screen.getByText('AI Agent')).toBeInTheDocument();
    });

    it('unlocks and relocks this root only when the lock control is clicked', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        act(() => {
            screen.getByRole('button', {name: 'Unlock node movement'}).click();
        });

        expect(useWorkflowEditorStore.getState().clusterFrameLockedByRootId.aiAgent_1).toBe(false);
        expect(screen.getByRole('button', {name: 'Lock node movement'})).toBeInTheDocument();

        act(() => {
            screen.getByRole('button', {name: 'Lock node movement'}).click();
        });

        expect(useWorkflowEditorStore.getState().clusterFrameLockedByRootId.aiAgent_1).toBe(true);
    });

    it('records this root as collapsed under the open workflow when the collapse control is used', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        act(() => {
            screen.getByRole('button', {name: 'Collapse cluster elements'}).click();
        });

        expect(useClusterFrameCollapsedStore.getState().collapsedByWorkflowId).toEqual({
            'workflow-1': {aiAgent_1: true},
        });
    });

    it('clears every saved element position for this root when Reset layout is used', () => {
        buildWorkflowWithClusterRoot();

        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        act(() => {
            screen.getByRole('button', {name: 'Reset layout'}).click();
        });

        expect(saveWorkflowDefinitionMock).toHaveBeenCalledTimes(1);

        const [[callArguments]] = saveWorkflowDefinitionMock.mock.calls;

        expect(callArguments.updateWorkflowMutation).toBe(updateWorkflowMutationMock);
        expect(callArguments.nodeData.workflowNodeName).toBe('aiAgent_1');
        expect(callArguments.nodeData.componentName).toBe('aiAgent');
        expect(callArguments.nodeData.clusterElements.model.metadata.ui.nodePosition).toBeUndefined();
    });

    it('does nothing when Reset layout is used with no cluster root task in the definition', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        act(() => {
            screen.getByRole('button', {name: 'Reset layout'}).click();
        });

        expect(saveWorkflowDefinitionMock).not.toHaveBeenCalled();
    });

    it('carries the graph transition endpoints on the box when the root is a graph member', () => {
        render(
            <TooltipProvider>
                <ClusterFrameShell
                    data={{...AI_AGENT_ROOT_DATA, graphData: {graphId: 'graph_1', index: 0}} as NodeDataType}
                    nodeId="aiAgent_1"
                >
                    <div>root card</div>
                </ClusterFrameShell>
            </TooltipProvider>
        );

        expect(screen.getByTestId('aiAgent_1-graph-transition-target')).toBeInTheDocument();
        expect(screen.getByTestId('aiAgent_1-graph-transition-source')).toBeInTheDocument();
        expect(screen.getByTestId('aiAgent_1-graph-transition-dynamic')).toBeInTheDocument();
    });

    it('leaves the graph transition endpoints off a box that belongs to no graph', () => {
        render(
            <TooltipProvider>
                <ClusterFrameShell data={AI_AGENT_ROOT_DATA} nodeId="aiAgent_1">
                    <div>root card</div>
                </ClusterFrameShell>
            </TooltipProvider>
        );

        expect(screen.queryByTestId('aiAgent_1-graph-transition-target')).not.toBeInTheDocument();
        expect(screen.queryByTestId('aiAgent_1-graph-transition-source')).not.toBeInTheDocument();
    });

    it('opens the playground without opening the dialog', async () => {
        const user = userEvent.setup();

        render(
            <TooltipProvider>
                <ClusterFrameShell data={AI_AGENT_ROOT_DATA} nodeId="aiAgent_1">
                    <div>root card</div>
                </ClusterFrameShell>
            </TooltipProvider>
        );

        await user.click(screen.getByLabelText('Test agent'));

        expect(useClusterElementsCanvasDialogStore.getState().testingPanelOpen).toBe(true);
        expect(useWorkflowEditorStore.getState().clusterElementsCanvasOpen).toBe(false);
        expect(useWorkflowEditorStore.getState().rootClusterElementNodeData?.workflowNodeName).toBe('aiAgent_1');
    });
});

describe('ClusterFrameShell trigger cluster root', () => {
    const TRIGGER_ROOT_DATA = {
        clusterFrame: {clusterRootId: 'trigger_1', height: 320, width: 640},
        componentName: 'browser',
        label: 'Browser Voice Session',
        name: 'trigger_1',
        trigger: true,
        workflowNodeName: 'trigger_1',
    } as unknown as NodeDataType;

    beforeEach(() => {
        editorContext.updateWorkflowMutation = updateWorkflowMutationMock;
        saveWorkflowDefinitionMock.mockReset();

        useWorkflowEditorStore.setState({
            clusterElementsCanvasOpen: false,
            clusterFrameLockedByRootId: {},
            rootClusterElementNodeData: undefined,
        });
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
        useWorkflowDataStore.setState({
            workflow: {
                definition: JSON.stringify({
                    tasks: [],
                    triggers: [
                        {
                            clusterElements: {
                                tools: [],
                                voiceAgent: {
                                    metadata: {ui: {nodePosition: {x: 40, y: 20}}},
                                    name: 'voiceAgent_1',
                                    type: 'deepgram/v1/voiceAgent',
                                },
                            },
                            name: 'trigger_1',
                            type: 'browser/v1/voiceSession',
                        },
                    ],
                }),
                id: 'workflow-1',
                nodeNames: [],
                version: 1,
            },
        });
        applicationInfoStore.setState({featureFlags: {}});
    });

    it('clears the voice agent position of a trigger box when Reset layout is used', () => {
        render(
            <ClusterFrameShell data={TRIGGER_ROOT_DATA} nodeId="trigger_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        act(() => {
            screen.getByRole('button', {name: 'Reset layout'}).click();
        });

        expect(saveWorkflowDefinitionMock).toHaveBeenCalledTimes(1);

        const [[callArguments]] = saveWorkflowDefinitionMock.mock.calls;

        expect(callArguments.nodeData.workflowNodeName).toBe('trigger_1');
        expect(callArguments.nodeData.componentName).toBe('browser');
        expect(callArguments.nodeData.clusterElements.voiceAgent.metadata.ui.nodePosition).toBeUndefined();
    });

    // Nothing flows into a trigger, so its box offers no incoming connection point.
    it('hides the chain target handle of a trigger box but keeps its source handle', () => {
        render(
            <ClusterFrameShell data={TRIGGER_ROOT_DATA} nodeId="trigger_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.getByTestId('trigger_1-top')).toHaveClass('hidden');
        expect(screen.getByTestId('trigger_1-bottom')).not.toHaveClass('hidden');
    });

    it('keeps the chain target handle of a task box', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.getByTestId('aiAgent_1-top')).not.toHaveClass('hidden');
    });
});

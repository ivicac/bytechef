import {NodeDataType} from '@/shared/types';
import {act, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import ClusterFrameShell from './ClusterFrameShell';

vi.mock('@xyflow/react', () => ({
    Handle: () => null,
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

        useWorkflowEditorStore.setState({clusterFrameLockedByRootId: {}});
        useWorkflowDataStore.setState({workflow: {definition: undefined, id: 'workflow-1', nodeNames: [], version: 1}});
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
});

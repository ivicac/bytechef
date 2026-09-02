import {useAiAgentEvalsStore} from '@/pages/platform/cluster-element-editor/ai-agent-evals/stores/useAiAgentEvalsStore';
import useClusterElementsViewModeStore from '@/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import {NodeDataType} from '@/shared/types';
import {render, screen} from '@/shared/util/test-utils';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import ClusterElementsCanvasDialog from './ClusterElementsCanvasDialog';
import {useClusterElementsCanvasDialogStore} from './stores/useClusterElementsCanvasDialogStore';

// Regression coverage for the box header's "Agent Evals" button, and for what went wrong the first
// time this was "fixed". The original bug: handleOpenEvals sets evalsPanelOpen (in
// useAiAgentEvalsStore) and opens the dialog, but on mount useClusterElementsCanvasDialog's own
// preference-restoring effects default showAiAgentEditor to true too, so the dialog rendered its
// AiAgentEditor branch first. The FIRST fix hoisted evalsPanelOpen above every other branch in
// ClusterElementsCanvasDialog -- but AiAgentEditor.tsx:49-64 already has its own early return
// rendering AiAgentEvals (with AiAgentHeader chrome and a Copilot button) whenever evalsPanelOpen is
// set, so evals was ALREADY reachable through the editor branch. The hoist made that richer surface
// unreachable, silenced its Copilot control, and turned that early return into dead code -- verified
// only because this file mocked AiAgentEditor away entirely, which erased the exact branch the bug
// lived in and let the hoist look correct.
//
// This file therefore does NOT mock AiAgentEditor away, and does NOT mock the dialog's own
// useClusterElementsCanvasDialog hook or useClusterElementsCanvasDialogStore -- it exercises the real
// branch-selection logic those own, and only stubs the heavy leaf UI components neither test below
// reads from. What it can honestly prove is that the DIALOG chooses the AiAgentEditor branch (not its
// own duplicate overlay) and that closing clears evalsPanelOpen so it cannot leak into the next root.
// Whether AiAgentEditor itself then renders evals correctly is AiAgentEditor's own contract, covered by
// cluster-element-editor/ai-agent-editor/AiAgentEditor.test.tsx.

vi.mock('react-router-dom', async (importOriginal) => ({
    ...(await importOriginal<typeof import('react-router-dom')>()),
    useParams: () => ({projectId: '1', projectWorkflowId: '1'}),
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => () => false,
}));

vi.mock('@/shared/components/copilot/stores/useCopilotPostTurnRegistry', () => ({
    default: {getState: () => ({register: () => () => {}})},
}));

vi.mock('@/shared/queries/automation/projectWorkflows.queries', () => ({
    ProjectWorkflowKeys: {projectWorkflow: () => ['projectWorkflow']},
}));

vi.mock('@/pages/platform/cluster-element-editor/components/ClusterElementsWorkflowEditor', () => ({
    default: () => <div>canvas view</div>,
}));

vi.mock('@/pages/platform/cluster-element-editor/components/ClusterElementsWorkflowEditorHeader', () => ({
    default: () => null,
}));

// Renders a marker (so tests can tell this branch was chosen) plus a close button wired to the real
// onClose prop, so a test can trigger the dialog's actual handleClose/handleOpenChange close path
// without needing AiAgentEditor's own heavy dependencies (react-query, useAiAgentEditor, etc).
vi.mock('@/pages/platform/cluster-element-editor/ai-agent-editor/AiAgentEditor', () => ({
    default: ({onClose}: {onClose?: () => void}) => (
        <div>
            ai agent editor
            {onClose && <button onClick={onClose}>close ai agent editor</button>}
        </div>
    ),
}));

vi.mock(
    '@/pages/platform/cluster-element-editor/ai-agent-editor/components/ai-agent-testing-panel/AiAgentTestingPanel',
    () => ({default: () => null})
);

// Only rendered by the DIALOG's own standalone evals overlay (the branch the first fix hoisted) --
// AiAgentEditor's own evals view is mocked away above, so this text appearing proves the overlay this
// round reverted to keeping subordinate, not AiAgentEditor's internal evals rendering.
vi.mock('@/pages/platform/cluster-element-editor/ai-agent-evals/AiAgentEvals', () => ({
    default: () => <div>dialog-own-evals-overlay</div>,
}));

vi.mock('@/pages/platform/cluster-element-editor/data-stream-editor/DataStreamEditor', () => ({
    default: () => <div>data stream editor</div>,
}));

vi.mock('@/pages/platform/workflow-editor/components/WorkflowNodeDetailsPanel', () => ({default: () => null}));

vi.mock('@/pages/platform/workflow-editor/stores/useDataPillPanelStore', () => ({
    default: (selector: (state: unknown) => unknown) => selector({dataPillPanelOpen: false}),
}));

vi.mock('@/pages/platform/workflow-editor/components/WorkflowEditorSkeletons', () => ({
    DataPillPanelSkeleton: () => null,
}));

vi.mock('@/shared/components/copilot/CopilotPanel', () => ({default: () => null}));

const AI_AGENT_ROOT_DATA = {
    componentName: 'aiAgent',
    name: 'aiAgent_1',
    type: 'aiAgent/v1/chat',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

const DATA_STREAM_ROOT_DATA = {
    componentName: 'dataStream',
    name: 'dataStream_1',
    type: 'dataStream/v1',
    workflowNodeName: 'dataStream_1',
} as unknown as NodeDataType;

const renderDialog = (onOpenChange: () => void = vi.fn()) =>
    render(
        <ClusterElementsCanvasDialog
            onOpenChange={onOpenChange}
            open
            previousComponentDefinitions={[]}
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            updateWorkflowMutation={{} as any}
            workflowNodeOutputs={[]}
            workflowReferenceId={1}
        />
    );

describe('ClusterElementsCanvasDialog - evals reachability and cleanup', () => {
    beforeEach(() => {
        useWorkflowDataStore.setState({workflow: {definition: undefined, id: 'workflow-1', nodeNames: [], version: 1}});
        useWorkflowEditorStore.setState({clusterElementsCanvasOpen: false, rootClusterElementNodeData: undefined});
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterElementsCanvasDialogStore.setState({
            copilotPanelOpen: false,
            editorPreferences: {},
            showAiAgentEditor: false,
            showDataStreamEditor: false,
            testingPanelOpen: false,
        });
        useAiAgentEvalsStore.setState({
            evalsPanelOpen: false,
            evalsTab: 'tests',
            selectedRunId: null,
            selectedTestId: null,
        });
        useWorkflowNodeDetailsPanelStore.getState().reset();
    });

    it("renders the AI Agent editor branch, not the dialog's own duplicate evals overlay, when evalsPanelOpen is set for an agent root", () => {
        useWorkflowEditorStore.setState({rootClusterElementNodeData: AI_AGENT_ROOT_DATA});
        useAiAgentEvalsStore.setState({evalsPanelOpen: true});

        renderDialog();

        expect(screen.getByText('ai agent editor')).toBeInTheDocument();
        expect(screen.queryByText('dialog-own-evals-overlay')).not.toBeInTheDocument();
        expect(screen.queryByText('canvas view')).not.toBeInTheDocument();
    });

    it('clears evalsPanelOpen when the dialog closes, so it cannot leak into a differently-typed root reopened after it', async () => {
        const user = userEvent.setup();
        const onOpenChange = vi.fn();

        useWorkflowEditorStore.setState({rootClusterElementNodeData: AI_AGENT_ROOT_DATA});
        useAiAgentEvalsStore.setState({evalsPanelOpen: true});

        // The new root's showDataStreamEditor must end up false, so the dialog falls through to its
        // canvas-view branch -- the only branch a stale evalsPanelOpen could still hijack, since
        // DataStreamEditor (like AiAgentEditor) is checked ahead of it and would otherwise win
        // regardless of evalsPanelOpen's value, making the leak unobservable. This reproduces "switch
        // to canvas view" having been chosen for this DataStream root in an earlier session.
        useClusterElementsCanvasDialogStore.setState({editorPreferences: {'1:dataStream_1': false}});

        const {rerender} = renderDialog(onOpenChange);

        expect(screen.getByText('ai agent editor')).toBeInTheDocument();

        await user.click(screen.getByRole('button', {name: 'close ai agent editor'}));

        expect(onOpenChange).toHaveBeenCalledWith(false);
        expect(useAiAgentEvalsStore.getState().evalsPanelOpen).toBe(false);

        // Simulate box mode reopening the dialog immediately afterward for a DIFFERENT, non-agent root
        // (the box header's own handlers reseed rootClusterElementNodeData and reopen unconditionally,
        // never through this dialog's own close/reopen lifecycle) -- before this round's fix,
        // evalsPanelOpen from the agent root's session would still have been true here.
        useWorkflowEditorStore.setState({rootClusterElementNodeData: DATA_STREAM_ROOT_DATA});

        rerender(
            <ClusterElementsCanvasDialog
                onOpenChange={onOpenChange}
                open
                previousComponentDefinitions={[]}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                updateWorkflowMutation={{} as any}
                workflowNodeOutputs={[]}
                workflowReferenceId={1}
            />
        );

        expect(await screen.findByText('canvas view')).toBeInTheDocument();
        expect(screen.queryByText('dialog-own-evals-overlay')).not.toBeInTheDocument();
        expect(screen.queryByText('ai agent editor')).not.toBeInTheDocument();
        expect(screen.queryByText('data stream editor')).not.toBeInTheDocument();
    });
});

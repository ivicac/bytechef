import {TooltipProvider} from '@/components/ui/tooltip';
import {NodeDataType} from '@/shared/types';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen, within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {ReactNode} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import useClusterElementsViewModeStore from '../stores/useClusterElementsViewModeStore';
import useClusterFrameCollapsedStore from '../stores/useClusterFrameCollapsedStore';
import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import useWorkflowIssuesStore from '../stores/useWorkflowIssuesStore';
import AiAgentNode from './AiAgentNode';

const {handleDeleteTaskMock, handleDeleteTriggerMock, recordedMenuProps, testNodeStatesState} = vi.hoisted(() => ({
    handleDeleteTaskMock: vi.fn(),
    handleDeleteTriggerMock: vi.fn(),
    recordedMenuProps: {
        contextMenu: undefined as Record<string, unknown> | undefined,
        dropdownMenu: undefined as Record<string, unknown> | undefined,
    },
    testNodeStatesState: {value: {} as Record<string, {status: 'RUNNING' | 'COMPLETED' | 'FAILED'}>},
}));

vi.mock('@xyflow/react', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@xyflow/react')>()),
    Handle: ({className, type}: {className?: string; type: string}) => (
        <div className={className} data-testid={`handle-${type}`} />
    ),
}));

vi.mock('@/pages/platform/workflow-editor/components/WorkflowNodeContextMenu', () => ({
    default: ({children, ...contextMenuProps}: {children: ReactNode}) => {
        recordedMenuProps.contextMenu = contextMenuProps;

        return <div>{children}</div>;
    },
}));

vi.mock('@/pages/platform/workflow-editor/components/WorkflowNodeDropdownMenu', () => ({
    default: (dropdownMenuProps: Record<string, unknown>) => {
        recordedMenuProps.dropdownMenu = dropdownMenuProps;

        return null;
    },
}));

vi.mock('../providers/workflowEditorProvider', () => ({
    useWorkflowEditor: () => ({
        cancelWorkflowQueries: vi.fn(),
        invalidateWorkflowQueries: vi.fn(),
        updateWorkflowMutation: {mutate: vi.fn()},
    }),
}));

vi.mock('@/shared/queries/platform/workflowNodeDescriptions.queries', () => ({
    useGetWorkflowNodeDescriptionQuery: () => ({data: undefined}),
}));

vi.mock('../hooks/useWorkflowTestNodeStates', () => ({
    default: () => testNodeStatesState.value,
}));

vi.mock('../hooks/useNodeClick', () => ({
    default: () => vi.fn(),
}));

vi.mock('./ClusterFrameShell', () => ({
    default: ({children}: {children: ReactNode}) => <div>{children}</div>,
}));

vi.mock('../utils/handleDeleteTask', () => ({default: handleDeleteTaskMock}));

vi.mock('../utils/handleDeleteTrigger', () => ({default: handleDeleteTriggerMock}));

const AI_AGENT_DATA = {
    clusterElements: {},
    clusterRoot: true,
    componentName: 'aiAgent',
    label: 'AI Agent',
    name: 'aiAgent_1',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

const DISABLED_BADGE_TITLE = 'Disabled — skipped during execution';

const FRAMED_AI_AGENT_DATA = {
    ...AI_AGENT_DATA,
    clusterFrame: {clusterRootId: 'aiAgent_1', contentOrigin: {x: 32, y: 40}, height: 320, width: 640},
} as unknown as NodeDataType;

function definitionWithDisabledLoop() {
    return JSON.stringify({
        tasks: [
            {
                disabled: true,
                name: 'loop_1',
                parameters: {iteratee: [{name: 'aiAgent_1', parameters: {}, type: 'aiAgent/v1'}]},
                type: 'loop/v1',
            },
        ],
    });
}

function nodeClassName(container: HTMLElement) {
    return container.querySelector('[data-nodetype="clusterRoot"]')?.className ?? '';
}

function renderNode(data: NodeDataType) {
    const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

    return render(
        <QueryClientProvider client={queryClient}>
            <TooltipProvider>
                <AiAgentNode data={data} id="aiAgent_1" />
            </TooltipProvider>
        </QueryClientProvider>
    );
}

describe('AiAgentNode', () => {
    beforeEach(() => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'box'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
        useWorkflowDataStore.setState({
            workflow: {definition: '{}', id: 'workflow-1', nodeNames: [], tasks: [], triggers: [], version: 1},
        });

        testNodeStatesState.value = {};
    });

    it("shows the agent's own test status on the compact card", () => {
        testNodeStatesState.value = {aiAgent_1: {status: 'COMPLETED'}};

        const {container} = renderNode(AI_AGENT_DATA);

        expect(container.querySelector('.border-green-500')).not.toBeNull();
    });

    it('rings a tool icon on the compact card by the outcome of its calls', () => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});

        testNodeStatesState.value = {httpClient_1: {status: 'FAILED'}};

        const {container} = renderNode({
            ...AI_AGENT_DATA,
            clusterElements: {tools: [{name: 'httpClient_1', type: 'httpClient/v1/get'}]},
        } as unknown as NodeDataType);

        expect(container.querySelector('li.border-red-500')).not.toBeNull();
    });

    it('expands a collapsed cluster root back into its frame', async () => {
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {'workflow-1': {aiAgent_1: true}}});

        renderNode(AI_AGENT_DATA);

        await userEvent.click(screen.getByRole('button', {name: 'Expand cluster elements'}));

        expect(useClusterFrameCollapsedStore.getState().collapsedByWorkflowId['workflow-1']?.aiAgent_1).toBeUndefined();
    });

    it('offers no expand control in dialog mode, even for a collapsed root', () => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {'workflow-1': {aiAgent_1: true}}});

        renderNode(AI_AGENT_DATA);

        expect(screen.queryByRole('button', {name: 'Expand cluster elements'})).not.toBeInTheDocument();
    });

    it('offers no expand control for a root that is not collapsed', () => {
        renderNode(AI_AGENT_DATA);

        expect(screen.queryByRole('button', {name: 'Expand cluster elements'})).not.toBeInTheDocument();
    });

    it('draws its own chain handles as a compact card', () => {
        renderNode(AI_AGENT_DATA);

        expect(screen.getByTestId('handle-target')).toBeInTheDocument();
        expect(screen.getByTestId('handle-source')).toBeInTheDocument();
    });

    it('leaves the chain handles to the frame when drawn as an inline frame', () => {
        renderNode(FRAMED_AI_AGENT_DATA);

        expect(screen.queryByTestId('handle-target')).not.toBeInTheDocument();
        expect(screen.queryByTestId('handle-source')).not.toBeInTheDocument();
    });

    it('renders muted when the agent task carries its own disabled flag', () => {
        const {container} = renderNode({...AI_AGENT_DATA, disabled: true} as NodeDataType);

        expect(nodeClassName(container)).toContain('opacity-50');
        expect(nodeClassName(container)).toContain('grayscale');
    });

    it('does not render muted when the agent task is enabled', () => {
        const {container} = renderNode(AI_AGENT_DATA);

        expect(nodeClassName(container)).not.toContain('opacity-50');
        expect(nodeClassName(container)).not.toContain('grayscale');
    });

    it('shows the disabled badge for the agent task own disabled flag', () => {
        renderNode({...AI_AGENT_DATA, disabled: true} as NodeDataType);

        expect(screen.getByTitle(DISABLED_BADGE_TITLE)).toBeInTheDocument();
    });

    it('renders muted without a badge when only an ancestor dispatcher is disabled', () => {
        useWorkflowDataStore.setState({
            workflow: {
                definition: definitionWithDisabledLoop(),
                id: 'workflow-1',
                nodeNames: [],
                tasks: [],
                triggers: [],
                version: 1,
            },
        });

        const {container} = renderNode(AI_AGENT_DATA);

        expect(nodeClassName(container)).toContain('opacity-50');
        expect(nodeClassName(container)).toContain('grayscale');
        expect(screen.queryByTitle(DISABLED_BADGE_TITLE)).not.toBeInTheDocument();
    });
});

describe('AiAgentNode issue badge', () => {
    beforeEach(() => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
        useWorkflowDataStore.setState({
            workflow: {definition: '{}', id: 'workflow-1', nodeNames: [], tasks: [], triggers: [], version: 1},
        });
        useWorkflowIssuesStore.setState({
            liveIssues: {},
            sweepIssues: [
                {
                    kind: 'MISSING_REQUIRED',
                    message: 'Missing required property: userPrompt',
                    nodeName: 'aiAgent_1',
                    severity: 'ERROR',
                    source: 'SWEEP',
                },
            ],
            validatorIssues: [],
        });

        testNodeStatesState.value = {};
    });

    afterEach(() => {
        useWorkflowIssuesStore.setState({liveIssues: {}, sweepIssues: [], validatorIssues: []});
    });

    it('anchors the badge to the card rather than to the whole node element', () => {
        renderNode(AI_AGENT_DATA);

        const badge = screen.getByRole('img', {name: '1 issue'});

        let anchor = badge.parentElement;

        while (anchor && !anchor.className.split(/\s+/).includes('relative')) {
            anchor = anchor.parentElement;
        }

        expect(anchor, 'the badge has no positioned ancestor to be offset from').not.toBeNull();
        expect(within(anchor!).queryByText('aiAgent_1')).toBeNull();
        expect(within(anchor!).getAllByRole('button').length).toBeGreaterThan(0);
    });
});

// Paste is typed: a copied trigger has no insertion point after an agent task, so only a copied task
// of the same workflow may be pasted after it.
describe('AiAgentNode paste', () => {
    beforeEach(() => {
        recordedMenuProps.contextMenu = undefined;
        recordedMenuProps.dropdownMenu = undefined;
        testNodeStatesState.value = {};

        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
        useWorkflowDataStore.setState({
            workflow: {definition: '{}', id: 'workflow-1', nodeNames: [], tasks: [], triggers: [], version: 1},
        });
    });

    afterEach(() => {
        useWorkflowEditorStore.setState({copiedNode: undefined, copiedWorkflowId: undefined});
    });

    it('offers paste for a task copied from the same workflow', () => {
        useWorkflowEditorStore.setState({
            copiedNode: {componentName: 'httpClient', name: 'httpClient_1'} as NodeDataType,
            copiedWorkflowId: 'workflow-1',
        });

        renderNode(AI_AGENT_DATA);

        expect(recordedMenuProps.contextMenu?.canPaste).toBe(true);
        expect(recordedMenuProps.dropdownMenu?.canPaste).toBe(true);
    });

    it('offers no paste for a copied trigger', () => {
        useWorkflowEditorStore.setState({
            copiedNode: {componentName: 'webhook', name: 'trigger_2', trigger: true} as NodeDataType,
            copiedWorkflowId: 'workflow-1',
        });

        renderNode(AI_AGENT_DATA);

        expect(recordedMenuProps.contextMenu?.canPaste).toBe(false);
        expect(recordedMenuProps.dropdownMenu?.canPaste).toBe(false);
    });

    it('offers no paste for a task copied from another workflow', () => {
        useWorkflowEditorStore.setState({
            copiedNode: {componentName: 'httpClient', name: 'httpClient_1'} as NodeDataType,
            copiedWorkflowId: 'workflow-2',
        });

        renderNode(AI_AGENT_DATA);

        expect(recordedMenuProps.contextMenu?.canPaste).toBe(false);
    });
});

// A trigger that is a cluster root (the browser voice session) draws with this compact card outside
// box mode, but it is not a task: copy, cut and disable are task actions, only a workflow with another
// trigger may delete it, and deleting it has to go through the trigger path rather than remove a task.
describe('AiAgentNode trigger cluster root', () => {
    const VOICE_SESSION_TRIGGER_DATA = {
        clusterRoot: true,
        componentName: 'browser',
        label: 'Browser Voice Session',
        name: 'trigger_1',
        operationName: 'voiceSession',
        trigger: true,
        version: 1,
        workflowNodeName: 'trigger_1',
    } as unknown as NodeDataType;

    function setWorkflowTriggers(triggers: Array<{name: string; type: string}>) {
        useWorkflowDataStore.setState({
            workflow: {definition: '{}', id: 'workflow-1', nodeNames: [], tasks: [], triggers, version: 1},
        });
    }

    beforeEach(() => {
        handleDeleteTaskMock.mockReset();
        handleDeleteTriggerMock.mockReset();
        recordedMenuProps.contextMenu = undefined;
        recordedMenuProps.dropdownMenu = undefined;
        testNodeStatesState.value = {};

        setWorkflowTriggers([{name: 'trigger_1', type: 'browser/v1/voiceSession'}]);

        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
    });

    it('offers no task-only actions on the only trigger of the workflow', () => {
        renderNode(VOICE_SESSION_TRIGGER_DATA);

        const expectedActions = {
            showCopyAction: false,
            showCutAction: false,
            showDeleteAction: false,
            showDisableAction: false,
            showInfoAction: true,
            showRenameAction: true,
        };

        expect(recordedMenuProps.contextMenu).toMatchObject(expectedActions);
        expect(recordedMenuProps.dropdownMenu).toMatchObject(expectedActions);
    });

    it('deletes the trigger through the trigger path when the workflow has another trigger', () => {
        setWorkflowTriggers([
            {name: 'trigger_1', type: 'browser/v1/voiceSession'},
            {name: 'trigger_2', type: 'webhook/v1/onReceive'},
        ]);

        renderNode(VOICE_SESSION_TRIGGER_DATA);

        expect(recordedMenuProps.contextMenu?.showDeleteAction).toBe(true);

        (recordedMenuProps.contextMenu?.onDelete as () => void)();

        expect(handleDeleteTriggerMock).toHaveBeenCalledWith(expect.objectContaining({triggerName: 'trigger_1'}));
        expect(handleDeleteTaskMock).not.toHaveBeenCalled();
    });

    it('hides the incoming handle of a trigger card', () => {
        renderNode(VOICE_SESSION_TRIGGER_DATA);

        expect(screen.getByTestId('handle-target')).toHaveClass('hidden');
    });

    it('keeps every task action and the incoming handle on an agent task', () => {
        renderNode(AI_AGENT_DATA);

        expect(recordedMenuProps.contextMenu).toMatchObject({
            showCopyAction: true,
            showCutAction: true,
            showDeleteAction: true,
            showDisableAction: true,
        });
        expect(screen.getByTestId('handle-target')).not.toHaveClass('hidden');
    });
});

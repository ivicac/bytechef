import {TooltipProvider} from '@/components/ui/tooltip';
import {NodeDataType} from '@/shared/types';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen, within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {ReactFlowProvider} from '@xyflow/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useClusterElementsViewModeStore from '../stores/useClusterElementsViewModeStore';
import useClusterFrameCollapsedStore from '../stores/useClusterFrameCollapsedStore';
import useWorkflowIssuesStore from '../stores/useWorkflowIssuesStore';
import AiAgentNode from './AiAgentNode';

// Mutable slice of the workflow data store so each test can supply its own definition.
const {
    handleDeleteTaskMock,
    handleDeleteTriggerMock,
    recordedMenuProps,
    workflowDataStoreState,
    workflowEditorStoreState,
} = vi.hoisted(() => ({
    handleDeleteTaskMock: vi.fn(),
    handleDeleteTriggerMock: vi.fn(),
    recordedMenuProps: {
        contextMenu: undefined as Record<string, unknown> | undefined,
        dropdownMenu: undefined as Record<string, unknown> | undefined,
    },
    workflowDataStoreState: {
        definition: '{"tasks": []}',
        triggers: [] as Array<{name: string; type: string}>,
    },
    workflowEditorStoreState: {
        clusterRootComponentDefinitions: {} as Record<string, unknown>,
    },
}));

// Render the context menu as a passthrough so the node content is asserted directly.
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

vi.mock('@/pages/platform/workflow-editor/providers/workflowEditorProvider', () => ({
    useWorkflowEditor: () => ({
        cancelWorkflowQueries: vi.fn(),
        invalidateWorkflowQueries: vi.fn(),
        updateWorkflowMutation: {mutate: vi.fn()},
    }),
}));

vi.mock('@/pages/platform/workflow-editor/utils/getNodeLabel', () => ({
    getNodeLabel: () => 'AI Agent',
}));

vi.mock('@/shared/queries/platform/workflowNodeDescriptions.queries', () => ({
    useGetWorkflowNodeDescriptionQuery: () => ({data: undefined}),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: (selector: (state: {currentEnvironmentId: number}) => unknown) =>
        selector({currentEnvironmentId: 1}),
}));

vi.mock('../hooks/useNodeClick', () => ({
    default: () => vi.fn(),
}));

vi.mock('../utils/handleDeleteTask', () => ({default: handleDeleteTaskMock}));

vi.mock('../utils/handleDeleteTrigger', () => ({default: handleDeleteTriggerMock}));

// Only the icon extractor is stubbed: the handle geometry helpers are the thing under test in the
// box-mode block below, so they have to be the real ones.
vi.mock('../../cluster-element-editor/utils/clusterElementsUtils', async (importOriginal) => ({
    ...(await importOriginal<typeof import('../../cluster-element-editor/utils/clusterElementsUtils')>()),
    extractClusterElementIcons: () => [],
}));

vi.mock('../stores/useLayoutDirectionStore', () => ({
    default: (selector: (state: {layoutDirection: string}) => unknown) => selector({layoutDirection: 'TB'}),
}));

vi.mock('../stores/useWorkflowNodeDetailsPanelStore', () => ({
    default: (selector: (state: Record<string, unknown>) => unknown) =>
        selector({currentNode: undefined, setCurrentNode: vi.fn()}),
}));

vi.mock('../stores/useWorkflowDataStore', () => ({
    default: (selector: (state: Record<string, unknown>) => unknown) =>
        selector({
            incrementLayoutResetCounter: vi.fn(),
            workflow: {
                definition: workflowDataStoreState.definition,
                id: 'workflow-1',
                tasks: [],
                triggers: workflowDataStoreState.triggers,
            },
        }),
}));

vi.mock('../stores/useWorkflowEditorStore', () => ({
    default: (selector: (state: Record<string, unknown>) => unknown) =>
        selector({
            clusterRootComponentDefinitions: workflowEditorStoreState.clusterRootComponentDefinitions,
            copiedNode: undefined,
            copiedWorkflowId: undefined,
            renamingNodeName: undefined,
            setClusterElementsCanvasOpen: vi.fn(),
            setClusterFrameLocked: vi.fn(),
            setCopiedNode: vi.fn(),
            setCopiedWorkflowId: vi.fn(),
            setRenamingNodeName: vi.fn(),
            setRootClusterElementNodeData: vi.fn(),
        }),
}));

const DISABLED_BADGE_TITLE = 'Disabled — skipped during execution';

const AI_AGENT_DATA = {
    componentName: 'aiAgent',
    label: 'AI Agent',
    name: 'aiAgent_1',
    version: 1,
    workflowNodeName: 'aiAgent_1',
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

function renderNode(data: NodeDataType = AI_AGENT_DATA) {
    const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

    return render(
        <QueryClientProvider client={queryClient}>
            <TooltipProvider>
                <ReactFlowProvider>
                    <AiAgentNode data={data} id="aiAgent_1" />
                </ReactFlowProvider>
            </TooltipProvider>
        </QueryClientProvider>
    );
}

function nodeClassName(container: HTMLElement) {
    return container.querySelector('[data-nodetype="clusterRoot"]')?.className ?? '';
}

describe('AiAgentNode', () => {
    beforeEach(() => {
        workflowDataStoreState.definition = '{"tasks": []}';
        workflowDataStoreState.triggers = [];
        workflowEditorStoreState.clusterRootComponentDefinitions = {};
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'box'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
    });

    it('renders muted when the agent task carries its own disabled flag', () => {
        const {container} = renderNode({...AI_AGENT_DATA, disabled: true} as NodeDataType);

        expect(nodeClassName(container)).toContain('opacity-50');
        expect(nodeClassName(container)).toContain('grayscale');
    });

    it('does not render muted when the agent task is enabled', () => {
        const {container} = renderNode();

        expect(nodeClassName(container)).not.toContain('opacity-50');
        expect(nodeClassName(container)).not.toContain('grayscale');
    });

    it('shows the disabled badge for the agent task own disabled flag', () => {
        renderNode({...AI_AGENT_DATA, disabled: true} as NodeDataType);

        expect(screen.getByTitle(DISABLED_BADGE_TITLE)).toBeInTheDocument();
    });

    it('renders muted without a badge when only an ancestor dispatcher is disabled', () => {
        workflowDataStoreState.definition = definitionWithDisabledLoop();

        const {container} = renderNode();

        expect(nodeClassName(container)).toContain('opacity-50');
        expect(nodeClassName(container)).toContain('grayscale');
        expect(screen.queryByTitle(DISABLED_BADGE_TITLE)).not.toBeInTheDocument();
    });
});

describe('AiAgentNode expand control', () => {
    beforeEach(() => {
        workflowDataStoreState.definition = '{"tasks": []}';
        workflowDataStoreState.triggers = [];
        workflowEditorStoreState.clusterRootComponentDefinitions = {};
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'box'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
    });

    it('offers no expand control on a root that is not collapsed', () => {
        renderNode();

        expect(screen.queryByRole('button', {name: 'Expand cluster elements'})).not.toBeInTheDocument();
    });

    it('offers no expand control in dialog mode, where no root has a box to expand', () => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterFrameCollapsedStore.getState().setClusterFrameCollapsed('workflow-1', 'aiAgent_1', true);

        renderNode();

        expect(screen.queryByRole('button', {name: 'Expand cluster elements'})).not.toBeInTheDocument();
    });

    it('expands the root it is clicked on, clearing the stored collapse', async () => {
        useClusterFrameCollapsedStore.getState().setClusterFrameCollapsed('workflow-1', 'aiAgent_1', true);

        renderNode();

        await userEvent.click(screen.getByRole('button', {name: 'Expand cluster elements'}));

        expect(useClusterFrameCollapsedStore.getState().collapsedByWorkflowId).toEqual({});
    });
});

describe('AiAgentNode issue badge', () => {
    beforeEach(() => {
        workflowDataStoreState.definition = '{"tasks": []}';
        workflowDataStoreState.triggers = [];
        workflowEditorStoreState.clusterRootComponentDefinitions = {};
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterFrameCollapsedStore.setState({collapsedByWorkflowId: {}});
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
    });

    it('anchors the badge to the card rather than to the whole node element', () => {
        renderNode();

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

    beforeEach(() => {
        handleDeleteTaskMock.mockReset();
        handleDeleteTriggerMock.mockReset();
        recordedMenuProps.contextMenu = undefined;
        recordedMenuProps.dropdownMenu = undefined;
        workflowDataStoreState.definition = '{"tasks": []}';
        workflowDataStoreState.triggers = [{name: 'trigger_1', type: 'browser/v1/voiceSession'}];
        workflowEditorStoreState.clusterRootComponentDefinitions = {};
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
        workflowDataStoreState.triggers = [
            {name: 'trigger_1', type: 'browser/v1/voiceSession'},
            {name: 'trigger_2', type: 'webhook/v1/onReceive'},
        ];

        renderNode(VOICE_SESSION_TRIGGER_DATA);

        expect(recordedMenuProps.contextMenu?.showDeleteAction).toBe(true);

        (recordedMenuProps.contextMenu?.onDelete as () => void)();

        expect(handleDeleteTriggerMock).toHaveBeenCalledWith(expect.objectContaining({triggerName: 'trigger_1'}));
        expect(handleDeleteTaskMock).not.toHaveBeenCalled();
    });

    it('hides the incoming handle of a trigger card', () => {
        const {container} = renderNode(VOICE_SESSION_TRIGGER_DATA);

        expect(container.querySelector('.react-flow__handle.target')).toHaveClass('hidden');
    });

    it('keeps every task action and the incoming handle on an agent task', () => {
        const {container} = renderNode();

        expect(recordedMenuProps.contextMenu).toMatchObject({
            showCopyAction: true,
            showCutAction: true,
            showDeleteAction: true,
            showDisableAction: true,
        });
        expect(container.querySelector('.react-flow__handle.target')).not.toHaveClass('hidden');
    });
});

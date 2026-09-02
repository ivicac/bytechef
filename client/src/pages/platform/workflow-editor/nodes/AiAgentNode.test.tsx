import {TooltipProvider} from '@/components/ui/tooltip';
import {NodeDataType} from '@/shared/types';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {ReactFlowProvider} from '@xyflow/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import AiAgentNode from './AiAgentNode';

// Mutable slice of the workflow data store so each test can supply its own definition.
const {workflowDataStoreState, workflowEditorStoreState} = vi.hoisted(() => ({
    workflowDataStoreState: {definition: '{"tasks": []}'},
    workflowEditorStoreState: {
        clusterRootComponentDefinitions: {} as Record<string, unknown>,
    },
}));

// Render the context menu as a passthrough so the node content is asserted directly.
vi.mock('@/pages/platform/workflow-editor/components/WorkflowNodeContextMenu', () => ({
    default: ({children}: {children: ReactNode}) => <div>{children}</div>,
}));

vi.mock('@/pages/platform/workflow-editor/components/WorkflowNodeDropdownMenu', () => ({
    default: () => null,
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
                triggers: [],
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
        workflowEditorStoreState.clusterRootComponentDefinitions = {};
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

// Every cluster edge anchors to `<elementType>-handle` on the root, and React Flow drops an edge
// whose named handle does not exist. On the main canvas a cluster root is an AiAgentNode, not a
// WorkflowNode, so without these the box painted its elements and placeholders with nothing joining
// them to the root.
describe('AiAgentNode cluster element handles in box mode', () => {
    beforeEach(() => {
        workflowDataStoreState.definition = '{"tasks": []}';
        workflowEditorStoreState.clusterRootComponentDefinitions = {
            aiAgent_1: {
                clusterElementTypes: [
                    {label: 'Model', multipleElements: false, name: 'MODEL'},
                    {label: 'Tools', multipleElements: true, name: 'TOOLS'},
                ],
                name: 'aiAgent',
                version: 1,
            },
        };
    });

    function handleIds(container: HTMLElement) {
        return Array.from(container.querySelectorAll('[data-handleid]')).map((handle) =>
            handle.getAttribute('data-handleid')
        );
    }

    it('renders one source handle per declared cluster element type when the node is a box', () => {
        const {container} = renderNode({
            ...AI_AGENT_DATA,
            clusterFrame: {
                clusterRootId: 'aiAgent_1',
                contentOrigin: {x: 0, y: 40},
                height: 320,
                width: 640,
            },
        } as unknown as NodeDataType);

        expect(handleIds(container)).toEqual(expect.arrayContaining(['model-handle', 'tools-handle']));
    });

    it('renders no cluster element handles when the node is not a box', () => {
        const {container} = renderNode();

        expect(handleIds(container)).not.toEqual(expect.arrayContaining(['model-handle']));
    });
});

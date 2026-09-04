import {render, screen} from '@/shared/util/test-utils';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import WorkflowEditorLayout from './WorkflowEditorLayout';
import {useClusterElementsCanvasDialogStore} from './components/stores/useClusterElementsCanvasDialogStore';
import {useWorkflowEditor} from './providers/workflowEditorProvider';
import useClusterElementsViewModeStore from './stores/useClusterElementsViewModeStore';
import useWorkflowEditorStore from './stores/useWorkflowEditorStore';
import useWorkflowNodeDetailsPanelStore from './stores/useWorkflowNodeDetailsPanelStore';
import {isWorkflowMutating, setWorkflowMutating} from './utils/workflowMutationGuard';

// WorkflowEditorLayout branches between the React Flow canvas and the Monaco source editor based on
// the codeWorkflow/codeWorkflowLanguage flags threaded onto the shared WorkflowEditorStateI context
// (Task 6). This test only exercises that branch, so every other dependency (data fetching hooks,
// heavy child components) is stubbed to keep the render hermetic and fast.

const {useParamsMock} = vi.hoisted(() => ({
    useParamsMock: vi.fn<() => Record<string, string | undefined>>(() => ({
        projectId: '123',
        projectWorkflowId: '456',
    })),
}));

vi.mock('react-router-dom', async (importOriginal) => ({
    ...(await importOriginal<typeof import('react-router-dom')>()),
    useParams: useParamsMock,
}));

vi.mock('./providers/workflowEditorProvider', () => ({
    useWorkflowEditor: vi.fn(),
}));

vi.mock('@/pages/platform/workflow-editor/hooks/useWorkflowEditorLayout', () => ({
    default: () => ({
        handleClusterElementsCanvasOpenChange: vi.fn(),
        isMainRootClusterElement: false,
    }),
}));

vi.mock('@/pages/platform/workflow-editor/hooks/useWorkflowLayout', () => ({
    useWorkflowLayout: () => ({
        componentDefinitions: [],
        filteredWorkflowNodeOutputs: [],
        handleComponentsAndFlowControlsClick: vi.fn(),
        handleCopilotClick: vi.fn(),
        handleWorkflowCodeEditorClick: vi.fn(),
        handleWorkflowInputsClick: vi.fn(),
        handleWorkflowOutputsClick: vi.fn(),
        isWorkflowNodeOutputsPending: false,
        previousComponentDefinitions: [],
        taskDispatcherDefinitions: [],
        testConfigurationDisabled: true,
        workflowTestConfiguration: undefined,
    }),
}));

vi.mock('@/pages/platform/code-workflow/ProjectCodeWorkflowDetail', () => ({
    default: ({language, projectId}: {language: string; projectId: string}) => (
        <div data-testid="code-workflow-detail">{`${projectId}:${language}`}</div>
    ),
}));

vi.mock('@/pages/platform/code-workflow/IntegrationCodeWorkflowDetail', () => ({
    default: ({integrationId, language}: {integrationId: string; language: string}) => (
        <div data-testid="integration-code-workflow-detail">{`${integrationId}:${language}`}</div>
    ),
}));

vi.mock('./components/WorkflowEditor', () => ({
    default: () => <div data-testid="workflow-editor" />,
}));

vi.mock('./components/WorkflowRightSidebar', () => ({
    default: () => null,
}));

vi.mock('./components/WorkflowNodesSidebar', () => ({
    default: () => null,
}));

vi.mock('./components/datapills/DataPillPanel', () => ({
    default: () => null,
}));

vi.mock('@/pages/platform/workflow-editor/components/ClusterElementsCanvasDialog', () => ({
    default: () => null,
}));

vi.mock('@/pages/platform/workflow-editor/components/WorkflowNodeDetailsPanel', () => ({
    default: () => null,
}));

vi.mock('@/pages/platform/workflow-editor/components/workflow-test-chat/WorkflowTestChatPanel', () => ({
    default: () => null,
}));

vi.mock(
    '@/pages/platform/cluster-element-editor/ai-agent-editor/components/ai-agent-testing-panel/AiAgentTestingPanel',
    () => ({
        default: () => <div data-testid="playground-panel" />,
    })
);

vi.mock('./components/ErrorsBanner', () => ({default: () => null}));
vi.mock('./components/SubflowBanner', () => ({default: () => null}));
vi.mock('./components/WorkflowCodeEditorSheet', () => ({default: () => null}));
vi.mock('./components/WorkflowOutputsSheet', () => ({default: () => null}));
vi.mock('./components/workflow-inputs/WorkflowInputsSheet', () => ({default: () => null}));
vi.mock('./components/WorkflowEditorSkeletons', () => ({
    DataPillPanelSkeleton: () => null,
    WorkflowNodesSidebarSkeleton: () => null,
    WorkflowRightSidebarSkeleton: () => null,
}));

const mockUseWorkflowEditor = (codeWorkflow?: boolean, codeWorkflowLanguage?: string) => {
    vi.mocked(useWorkflowEditor).mockReturnValue({
        codeWorkflow,
        codeWorkflowLanguage,
        invalidateWorkflowQueries: vi.fn(),
        updateWorkflowMutation: {},
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);
};

const renderLayout = () => render(<WorkflowEditorLayout runDisabled={false} showWorkflowInputs={false} />);

describe('WorkflowEditorLayout - code-backed project branching', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        useParamsMock.mockReturnValue({projectId: '123', projectWorkflowId: '456'});

        // Reset the playground-guard state the later describe block below sets, so this block's
        // outcome does not depend on running before that one -- each describe block owns its own
        // starting state rather than relying on file declaration order.
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterElementsCanvasDialogStore.setState({testingPanelOpen: false});
        useWorkflowEditorStore.setState({clusterElementsCanvasOpen: false});
    });

    it('renders ProjectCodeWorkflowDetail when codeWorkflow is true and the language is polyglot', async () => {
        mockUseWorkflowEditor(true, 'JAVASCRIPT');

        renderLayout();

        expect(await screen.findByTestId('code-workflow-detail')).toHaveTextContent('123:JAVASCRIPT');
        expect(screen.queryByTestId('workflow-editor')).not.toBeInTheDocument();
        expect(screen.queryByTestId('integration-code-workflow-detail')).not.toBeInTheDocument();
    });

    it('renders IntegrationCodeWorkflowDetail when codeWorkflow is true, the language is polyglot, and the route has an integrationId', async () => {
        useParamsMock.mockReturnValue({integrationId: '789', integrationWorkflowId: '456'});
        mockUseWorkflowEditor(true, 'JAVASCRIPT');

        renderLayout();

        expect(await screen.findByTestId('integration-code-workflow-detail')).toHaveTextContent('789:JAVASCRIPT');
        expect(screen.queryByTestId('workflow-editor')).not.toBeInTheDocument();
        expect(screen.queryByTestId('code-workflow-detail')).not.toBeInTheDocument();
    });

    it('renders the visual editor when codeWorkflow is undefined', async () => {
        mockUseWorkflowEditor(undefined, undefined);

        renderLayout();

        expect(await screen.findByTestId('workflow-editor')).toBeInTheDocument();
        expect(screen.queryByTestId('code-workflow-detail')).not.toBeInTheDocument();
        expect(screen.queryByTestId('integration-code-workflow-detail')).not.toBeInTheDocument();
    });

    it('renders neither editor for a Java-backed code workflow', async () => {
        mockUseWorkflowEditor(true, 'JAVA');

        renderLayout();

        // A JAR has no source to edit, and the canvas would open on a definition the next deploy regenerates.
        expect(await screen.findByText(/compiled Java artifact/i)).toBeInTheDocument();
        expect(screen.queryByTestId('workflow-editor')).not.toBeInTheDocument();
        expect(screen.queryByTestId('code-workflow-detail')).not.toBeInTheDocument();
        expect(screen.queryByTestId('integration-code-workflow-detail')).not.toBeInTheDocument();
    });
});

// Regression coverage: a box-mode destination button (an editor toggle, or Evals) can open
// ClusterElementsCanvasDialog while the playground is already open beside the canvas. The dialog
// mounts its OWN AiAgentTestingPanel instance, sharing useAiAgentTestingChatStore with this one — two
// instances answering the same conversation state. The fix adds `!clusterElementsCanvasOpen` to this
// panel's mount condition so the two are never mounted at the same time.
describe('WorkflowEditorLayout - playground panel single-instance guard', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        useParamsMock.mockReturnValue({projectId: '123', projectWorkflowId: '456'});
        mockUseWorkflowEditor(undefined, undefined);

        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'box'});
        useClusterElementsCanvasDialogStore.setState({testingPanelOpen: true});
        useWorkflowEditorStore.setState({clusterElementsCanvasOpen: false});
    });

    it('mounts the playground panel in box mode when the dialog is closed', async () => {
        renderLayout();

        expect(await screen.findByTestId('playground-panel')).toBeInTheDocument();
    });

    it('does not mount the playground panel while the dialog is also open', () => {
        useWorkflowEditorStore.setState({clusterElementsCanvasOpen: true});

        renderLayout();

        expect(screen.queryByTestId('playground-panel')).not.toBeInTheDocument();
    });
});

describe('WorkflowEditorLayout - unmount cleanup', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        useParamsMock.mockReturnValue({projectId: '123', projectWorkflowId: '456'});
        mockUseWorkflowEditor(undefined, undefined);

        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});
        useClusterElementsCanvasDialogStore.setState({testingPanelOpen: false});
        useWorkflowEditorStore.setState({clusterElementsCanvasOpen: false});
    });

    it('drops the pending-first-save markers together with the mutation guard when it unmounts', () => {
        useWorkflowNodeDetailsPanelStore.getState().addPendingSaveNodeName('logger_1');
        setWorkflowMutating('workflow-1', true);

        const {unmount} = renderLayout();

        expect(useWorkflowNodeDetailsPanelStore.getState().pendingSaveNodeNames.has('logger_1')).toBe(true);

        unmount();

        expect(useWorkflowNodeDetailsPanelStore.getState().pendingSaveNodeNames.size).toBe(0);
        expect(isWorkflowMutating('workflow-1')).toBe(false);
    });
});

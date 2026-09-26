import {TooltipProvider} from '@/components/ui/tooltip';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen, waitFor, within} from '@testing-library/react';
import React from 'react';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import ProjectsLeftSidebar from './ProjectsLeftSidebar';

// React Query test client setup
const createTestQueryClient = () =>
    new QueryClient({
        defaultOptions: {
            queries: {
                retry: false,
            },
        },
    });

let queryClient: QueryClient;

// Mocks for UI components used inside dropdown/scroll
vi.mock('@/components/Button/Button', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({children, icon, label, ...props}: any) => (
        <button data-testid="btn" {...props}>
            {icon}

            {label ?? children}
        </button>
    ),
}));

vi.mock('@/components/ui/dropdown-menu', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    DropdownMenu: ({children}: any) => <div>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    DropdownMenuContent: ({children}: any) => <div>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    DropdownMenuItem: ({children, disabled, onClick}: any) => (
        <div aria-disabled={disabled ? 'true' : undefined} onClick={disabled ? undefined : onClick} role="menuitem">
            {children}
        </div>
    ),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    DropdownMenuTrigger: ({children}: any) => <div>{children}</div>,
}));

vi.mock('@/components/ui/scroll-area', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ScrollArea: ({children, ...props}: any) => <div {...props}>{children}</div>,
}));

vi.mock('@/components/ui/tabs', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Tabs: ({children, value}: any) => <div data-active-tab={value}>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    TabsContent: ({children, value}: any) => <div data-testid={`tabs-content-${value}`}>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    TabsList: ({children}: any) => <div>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    TabsTrigger: ({children, value}: any) => <button data-testid={`tabs-trigger-${value}`}>{children}</button>,
}));

// Child components mocked to minimal renderers
vi.mock('@/pages/automation/project/components/projects-sidebar/components/ProjectSelect', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({selectedProjectId, setSelectedProjectId}: any) => (
        <div data-testid="project-select">
            <span>ProjectSelect:{selectedProjectId}</span>

            <button onClick={() => setSelectedProjectId(3)} type="button">
                switch-to-project-3
            </button>

            <button onClick={() => setSelectedProjectId(0)} type="button">
                switch-to-all-projects
            </button>
        </div>
    ),
}));

vi.mock('@/pages/automation/project/components/projects-sidebar/components/ProjectWorkflowsList', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({project}: any) => <div data-testid="project-workflows-list">Project:{project.id}</div>,
}));

vi.mock('@/pages/automation/project/components/projects-sidebar/components/WorkflowsListFilter', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({sortBy}: any) => <div data-testid="workflows-list-filter">Sort:{sortBy}</div>,
}));

vi.mock('@/pages/automation/project/components/projects-sidebar/components/WorkflowsListItem', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({workflow}: any) => <li data-testid="workflow-item">Workflow:{workflow.id}</li>,
}));

vi.mock('@/pages/automation/project/components/projects-sidebar/components/WorkflowsListSkeleton', () => ({
    default: () => <div data-testid="skeleton">Loading...</div>,
}));

vi.mock('@/pages/automation/agents/components/AgentsLeftSidebarDropdownMenu', () => ({
    default: () => null,
}));

vi.mock('@/pages/automation/data-syncs/components/DataSyncsLeftSidebarDropdownMenu', () => ({
    default: () => null,
}));

// Hooks and stores
const mockGetProjectWorkflowsQuery = vi.fn();
const mockGetWorkflowsQuery = vi.fn();
vi.mock('@/shared/queries/automation/projectWorkflows.queries', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useGetProjectWorkflowsQuery: (...args: any[]) => mockGetProjectWorkflowsQuery(...args),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useGetWorkflowsQuery: (...args: any[]) => mockGetWorkflowsQuery(...args),
}));

const mockGetWorkspaceProjectsQuery = vi.fn();
vi.mock('@/shared/queries/automation/projects.queries', async () => ({
    ProjectKeys: {project: (id: number) => ['project', id], projects: ['projects']},
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useGetWorkspaceProjectsQuery: (args: any) => mockGetWorkspaceProjectsQuery(args),
}));

vi.mock('@/shared/queries/automation/workflows.queries', () => ({
    useGetWorkflowQuery: vi.fn(),
}));

vi.mock('@/pages/automation/project/components/projects-sidebar/hooks/useProjectsLeftSidebar', () => ({
    useProjectsLeftSidebar: () => ({
        calculateTimeDifference: vi.fn(),
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        getFilteredWorkflows: (workflows: any[]) => workflows || [],
        getWorkflowsProjectId: () => vi.fn(),
    }),
}));

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useWorkspaceStore: (selector: any) => selector({currentWorkspaceId: 10}),
}));

vi.mock('@/shared/mutations/automation/workflows.mutations', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useCreateProjectWorkflowMutation: (opts: any) => ({
        mutate: vi.fn().mockImplementation(() => {
            opts?.onSuccess?.();
        }),
    }),
}));

vi.mock('@/pages/automation/agents/hooks/useAgents', () => ({
    default: () => ({
        agents: [{id: '1', projectId: '7', title: 'Support Bot'}],
        agentsIsLoading: false,
    }),
}));

vi.mock('@/pages/automation/data-syncs/hooks/useDataSyncs', () => ({
    default: () => ({
        dataSyncs: [{id: '1', lastModifiedDate: null, projectId: '7', title: 'Contacts Sync'}],
        dataSyncsError: undefined,
        dataSyncsIsLoading: false,
    }),
}));

vi.mock('sonner', () => ({toast: vi.fn()}));

vi.mock('@tanstack/react-query', async () => {
    const actual = await vi.importActual<typeof import('@tanstack/react-query')>('@tanstack/react-query');
    return {
        ...actual,
        useQueryClient: () => ({invalidateQueries: vi.fn()}),
    };
});

const mockNavigate = vi.hoisted(() => vi.fn());

vi.mock('react-router-dom', async () => {
    const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');

    return {
        ...actual,
        useNavigate: () => mockNavigate,
        useSearchParams: () => [new URLSearchParams(), vi.fn()],
    };
});

// Helper to set default mocks per test scenario
const setupQueries = ({
    loading = false,
    projects = [{id: 1}, {id: 2}],
    selectedProjectId,
    workflows = [{id: 'w1'}, {id: 'w2'}],
}: {
    selectedProjectId: number;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    projects?: any[];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    workflows?: any[];
    loading?: boolean;
}) => {
    mockGetWorkspaceProjectsQuery.mockReturnValue({data: projects, refetch: vi.fn()});

    if (selectedProjectId !== 0) {
        mockGetProjectWorkflowsQuery.mockReturnValue({data: workflows, isLoading: loading});
        mockGetWorkflowsQuery.mockReturnValue({data: undefined, isLoading: false});
    } else {
        mockGetProjectWorkflowsQuery.mockReturnValue({data: undefined, isLoading: false});
        mockGetWorkflowsQuery.mockReturnValue({data: workflows, isLoading: loading});
    }
};

const baseProps = {
    currentWorkflowId: 'w1',
    onProjectClick: vi.fn(),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    updateWorkflowMutation: {} as any,
};

// Helper render wrapper to provide required Providers (React Query + Tooltip)
const renderWithProviders = (ui: React.ReactElement) =>
    render(
        <QueryClientProvider client={queryClient}>
            <TooltipProvider>
                <MemoryRouter>{ui}</MemoryRouter>
            </TooltipProvider>
        </QueryClientProvider>
    );

describe('ProjectsLeftSidebar', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        queryClient = createTestQueryClient();
    });

    afterEach(() => {
        queryClient.clear();
    });

    it('shows loading skeleton when queries are loading', async () => {
        setupQueries({loading: true, selectedProjectId: 5});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={5} />);

        // isLoading becomes true after effect runs
        await waitFor(() => expect(screen.getByTestId('skeleton')).toBeInTheDocument());
    });

    it('renders ProjectWorkflowsList for each project when selectedProjectId is 0', async () => {
        const projects = [{id: 11}, {id: 22}, {id: 33}];
        setupQueries({projects, selectedProjectId: 0, workflows: [{id: 'wa'}]});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={0} />);

        // Lists per project
        const items = await screen.findAllByTestId('project-workflows-list');
        expect(items).toHaveLength(projects.length);
    });

    it('renders WorkflowsListItem for each workflow when a specific project is selected', async () => {
        const workflows = [{id: 'wa'}, {id: 'wb'}, {id: 'wc'}];
        setupQueries({selectedProjectId: 7, workflows});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        const items = await screen.findAllByTestId('workflow-item');
        expect(items).toHaveLength(workflows.length);
    });

    it('updates selectedProjectId when projectId prop changes', async () => {
        const workflows1 = [{id: 'wa'}, {id: 'wb'}];
        const workflows2 = [{id: 'wc'}, {id: 'wd'}];

        // Initially render with projectId = 5
        setupQueries({selectedProjectId: 5, workflows: workflows1});

        const {rerender} = renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={5} />);

        // Verify initial workflows are displayed
        await waitFor(() => {
            const items = screen.getAllByTestId('workflow-item');
            expect(items).toHaveLength(2);
            expect(screen.getByText('Workflow:wa')).toBeInTheDocument();
        });

        // Verify useGetProjectWorkflowsQuery was called with projectId = 5
        expect(mockGetProjectWorkflowsQuery).toHaveBeenCalledWith(5, true);

        // Now change projectId to 7
        setupQueries({selectedProjectId: 7, workflows: workflows2});

        rerender(
            <QueryClientProvider client={queryClient}>
                <TooltipProvider>
                    <MemoryRouter>
                        <ProjectsLeftSidebar {...baseProps} projectId={7} />
                    </MemoryRouter>
                </TooltipProvider>
            </QueryClientProvider>
        );

        // Verify the query was called with the new projectId
        await waitFor(() => {
            expect(mockGetProjectWorkflowsQuery).toHaveBeenCalledWith(7, true);
        });

        // Verify new workflows are displayed
        await waitFor(() => {
            const items = screen.getAllByTestId('workflow-item');
            expect(items).toHaveLength(2);
            expect(screen.getByText('Workflow:wc')).toBeInTheDocument();
        });
    });

    it('handles NaN projectId by defaulting to 0 (All projects)', async () => {
        const projects = [{id: 11}, {id: 22}];
        setupQueries({projects, selectedProjectId: 0, workflows: [{id: 'wa'}]});

        // Pass NaN as projectId (simulating parseInt(undefined))
        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={NaN} />);

        // Should show ProjectWorkflowsList for all projects (selectedProjectId = 0)
        await waitFor(() => {
            expect(screen.getByTestId('project-select')).toHaveTextContent('ProjectSelect:0');
        });

        const items = await screen.findAllByTestId('project-workflows-list');
        expect(items).toHaveLength(projects.length);

        // Verify the "all workflows" query was called with enabled=true
        expect(mockGetWorkflowsQuery).toHaveBeenCalledWith(true);
        // And the project-specific query was disabled (NaN is converted to 0)
        expect(mockGetProjectWorkflowsQuery).toHaveBeenCalledWith(0, false);
    });

    it('updates from NaN to valid projectId when prop changes after initial load', async () => {
        const projects = [{id: 11}, {id: 22}];
        const workflows = [{id: 'wa'}, {id: 'wb'}];

        // Start with NaN projectId (initial state after login before URL params are parsed)
        setupQueries({projects, selectedProjectId: 0, workflows: [{id: 'initial'}]});

        const {rerender} = renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={NaN} />);

        // Initially should show all projects
        await waitFor(() => {
            expect(screen.getByTestId('project-select')).toHaveTextContent('ProjectSelect:0');
        });

        // Now update to a valid projectId (simulating URL params being parsed)
        setupQueries({selectedProjectId: 5, workflows});

        rerender(
            <QueryClientProvider client={queryClient}>
                <TooltipProvider>
                    <MemoryRouter>
                        <ProjectsLeftSidebar {...baseProps} projectId={5} />
                    </MemoryRouter>
                </TooltipProvider>
            </QueryClientProvider>
        );

        // Should now show the specific project
        await waitFor(() => {
            expect(screen.getByTestId('project-select')).toHaveTextContent('ProjectSelect:5');
        });

        // Verify the project-specific query was called with the valid projectId
        await waitFor(() => {
            expect(mockGetProjectWorkflowsQuery).toHaveBeenCalledWith(5, true);
        });

        // Verify workflows for the specific project are displayed
        const items = await screen.findAllByTestId('workflow-item');
        expect(items).toHaveLength(workflows.length);
    });

    it('uses a flex-bounded scroll container so the last workflow stays reachable (regression for #5111)', async () => {
        setupQueries({selectedProjectId: 7, workflows: [{id: 'wa'}, {id: 'wb'}, {id: 'wc'}]});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        const items = await screen.findAllByTestId('workflow-item');
        // ul (workflow list) -> tabs-content-workflows -> Tabs -> ScrollArea, the actual scroll container.
        const scrollContainer = items[0].closest('ul')?.parentElement?.parentElement?.parentElement as HTMLElement;

        // The scroll container must fill the remaining flex space and be allowed to shrink
        // (flex-1 + min-h-0) rather than be pinned to the viewport height (h-screen), which
        // pushed its bottom below the fold and clipped the last workflow.
        expect(scrollContainer.className).toContain('flex-1');
        expect(scrollContainer.className).toContain('min-h-0');
        expect(scrollContainer.className).not.toContain('h-screen');
    });

    it('handles zero projectId correctly (should show all projects)', async () => {
        const projects = [{id: 11}, {id: 22}];
        setupQueries({projects, selectedProjectId: 0, workflows: [{id: 'wa'}]});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={0} />);

        // Should show all projects
        await waitFor(() => {
            expect(screen.getByTestId('project-select')).toHaveTextContent('ProjectSelect:0');
        });

        const items = await screen.findAllByTestId('project-workflows-list');
        expect(items).toHaveLength(projects.length);
    });

    it('shows an Agents tab with a count and lists a project agent when useAgents returns one for it', async () => {
        setupQueries({selectedProjectId: 7});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        const agentsTab = await screen.findByTestId('tabs-trigger-agents');
        expect(agentsTab).toHaveTextContent('Agents');
        expect(within(agentsTab).getByText('1')).toBeInTheDocument();
        expect(screen.getByText('Support Bot')).toBeInTheDocument();
    });

    it('shows a Data Syncs tab with a count and lists a project data sync when useDataSyncs returns one for it', async () => {
        setupQueries({selectedProjectId: 7});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        const dataSyncsTab = await screen.findByTestId('tabs-trigger-dataSyncs');
        expect(dataSyncsTab).toHaveTextContent('Data Syncs');
        expect(within(dataSyncsTab).getByText('1')).toBeInTheDocument();
        expect(screen.getByText('Contacts Sync')).toBeInTheDocument();
    });

    it('defaults to the Workflows tab when no agent or data sync is open', async () => {
        setupQueries({selectedProjectId: 7});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        const workflowsTab = await screen.findByTestId('tabs-trigger-workflows');
        expect(workflowsTab).toHaveTextContent('Workflows');
        expect(within(workflowsTab).getByText('2')).toBeInTheDocument();
        expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'workflows');
    });

    it('selects the Agents tab by default when currentAgentId is passed', async () => {
        setupQueries({selectedProjectId: 7});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} currentAgentId="1" projectId={7} />);

        await screen.findByTestId('tabs-trigger-agents');

        expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'agents');
    });

    it('switches to the Agents tab when currentAgentId is set after the initial render', async () => {
        setupQueries({selectedProjectId: 7});

        const {rerender} = renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        await waitFor(() =>
            expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'workflows')
        );

        rerender(
            <QueryClientProvider client={queryClient}>
                <TooltipProvider>
                    <MemoryRouter>
                        <ProjectsLeftSidebar {...baseProps} currentAgentId="1" projectId={7} />
                    </MemoryRouter>
                </TooltipProvider>
            </QueryClientProvider>
        );

        await waitFor(() =>
            expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'agents')
        );
    });

    it('selects the Data Syncs tab by default when currentDataSyncId is passed', async () => {
        setupQueries({selectedProjectId: 7});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} currentDataSyncId="1" projectId={7} />);

        await screen.findByTestId('tabs-trigger-dataSyncs');

        expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'dataSyncs');
    });

    it('switches to the Data Syncs tab when currentDataSyncId is set after the initial render', async () => {
        setupQueries({selectedProjectId: 7});

        const {rerender} = renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={7} />);

        await waitFor(() =>
            expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'workflows')
        );

        rerender(
            <QueryClientProvider client={queryClient}>
                <TooltipProvider>
                    <MemoryRouter>
                        <ProjectsLeftSidebar {...baseProps} currentDataSyncId="1" projectId={7} />
                    </MemoryRouter>
                </TooltipProvider>
            </QueryClientProvider>
        );

        await waitFor(() =>
            expect(document.querySelector('[data-active-tab]')).toHaveAttribute('data-active-tab', 'dataSyncs')
        );
    });

    it('offers no creation actions in the sidebar', () => {
        setupQueries({selectedProjectId: 5});

        renderWithProviders(<ProjectsLeftSidebar {...baseProps} projectId={5} />);

        expect(screen.queryByLabelText('New project')).not.toBeInTheDocument();
        expect(screen.queryByText('From Template')).not.toBeInTheDocument();
        expect(screen.queryByText('Import Workflow')).not.toBeInTheDocument();
        expect(screen.queryByText('Import n8n Workflow')).not.toBeInTheDocument();
        expect(screen.queryByText('New Workflow')).not.toBeInTheDocument();
        expect(screen.queryByText('New Agent')).not.toBeInTheDocument();
        expect(screen.queryByText('Import Agent')).not.toBeInTheDocument();
        expect(screen.queryByText('New Data Sync')).not.toBeInTheDocument();
    });
});

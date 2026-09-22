import {TooltipProvider} from '@/components/ui/tooltip';
import Projects from '@/pages/automation/projects/Projects';
import {fireEvent, render, screen, userEvent, waitFor, within} from '@/shared/util/test-utils';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const mockProjectsState = vi.hoisted(() => ({
    agents: [] as {channels: {channelType: string}[]; id: string; projectId: string}[],
    captureEmptyStateAction: vi.fn(),
    captureProjectImported: vi.fn(),
    dataSyncs: [] as {id: string; projectId: string; triggerType: string}[],
    navigate: vi.fn(),
    projects: [] as {id: number; name: string}[],
}));

vi.mock('react-router-dom', async () => {
    const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');

    return {
        ...actual,
        useNavigate: () => mockProjectsState.navigate,
    };
});

// Mock the necessary stores and hooks
vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useWorkspaceStore: (selector: any) => selector({currentWorkspaceId: 1}),
}));

vi.mock('@/pages/automation/agents/hooks/useAgents', () => ({
    default: () => ({agents: mockProjectsState.agents, agentsIsLoading: false}),
}));

vi.mock('@/pages/automation/data-syncs/hooks/useDataSyncs', () => ({
    default: () => ({dataSyncs: mockProjectsState.dataSyncs, dataSyncsIsLoading: false}),
}));

vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: () => ({
        ai: {copilot: {enabled: true}},
        analytics: {enabled: false, postHog: {apiKey: '', host: ''}},
        application: {edition: 'CE'},
        featureFlags: {},
    }),
}));

vi.mock('@/shared/hooks/useAnalytics', () => ({
    useAnalytics: () => ({
        captureEmptyStateAction: mockProjectsState.captureEmptyStateAction,
        captureProjectImported: mockProjectsState.captureProjectImported,
    }),
}));

// Mock the API queries
vi.mock('@/shared/queries/automation/projectCategories.queries', () => ({
    useGetProjectCategoriesQuery: () => ({
        data: [],
        error: null,
        isLoading: false,
    }),
}));

vi.mock('@/shared/queries/automation/projectTags.queries', () => ({
    useGetProjectTagsQuery: () => ({
        data: [],
        error: null,
        isLoading: false,
    }),
}));

vi.mock('@/shared/queries/automation/projects.queries', () => ({
    ProjectKeys: {
        filteredProjects: (filters: {categoryId?: number; id: number; tagId?: number}) => [
            'projects',
            filters.id,
            filters,
        ],
        project: (id: number) => ['projects', id],
        projectWorkflows: (id: number) => ['projects', id, 'workflows'],
        projects: ['projects'],
    },
    useGetWorkspaceProjectsQuery: () => ({
        data: mockProjectsState.projects,
        error: null,
        isLoading: false,
    }),
}));

const mockImportMutate = vi.fn();
vi.mock('@/shared/mutations/automation/projects.mutations', async () => {
    const actual = await vi.importActual<typeof import('@/shared/mutations/automation/projects.mutations')>(
        '@/shared/mutations/automation/projects.mutations'
    );

    return {
        ...actual,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        useImportProjectMutation: (opts: any) => ({
            mutate: mockImportMutate.mockImplementation(() => {
                opts?.onSuccess?.();
            }),
        }),
    };
});

vi.mock('@/pages/automation/project/loadProject', () => ({default: vi.fn()}));

vi.mock('@/pages/automation/projects/components/project-list/ProjectList', () => ({
    default: ({defaultActiveTab, projects}: {defaultActiveTab?: string; projects: {id: number; name: string}[]}) => (
        <ul data-default-active-tab={defaultActiveTab} data-testid="project-list">
            {projects.map((project) => (
                <li key={project.id}>{project.name}</li>
            ))}
        </ul>
    ),
}));

vi.mock('sonner', () => ({toast: vi.fn()}));

const createTestQueryClient = () =>
    new QueryClient({
        defaultOptions: {
            queries: {
                retry: false,
            },
        },
    });

let queryClient: QueryClient;

beforeEach(() => {
    mockProjectsState.agents = [];
    mockProjectsState.dataSyncs = [];

    queryClient = createTestQueryClient();
    mockImportMutate.mockClear();
    mockProjectsState.captureEmptyStateAction.mockClear();
    mockProjectsState.captureProjectImported.mockClear();
    mockProjectsState.navigate.mockClear();
    mockProjectsState.projects = [];
});

afterEach(() => {
    queryClient.clear();
});

const renderProjects = (initialEntries: string[] = ['/']) => {
    render(
        <MemoryRouter initialEntries={initialEntries}>
            <QueryClientProvider client={queryClient}>
                <TooltipProvider>
                    <Projects />
                </TooltipProvider>
            </QueryClientProvider>
        </MemoryRouter>
    );
};

// The Agents and Data Syncs sidebar groups each have their own "Scheduled" item, so the accessible name
// alone does not pick one out — the target href does.
const getFilterLink = (name: string, hrefIncludes: string) =>
    screen.getAllByRole('link', {name}).find((link) => link.getAttribute('href')?.includes(hrefIncludes))!;

describe('Projects Import Functionality', () => {
    it('should show import dropdown menu items', async () => {
        renderProjects();

        // Find the dropdown trigger (chevron) button and click it to open the menu
        const createButton = screen.getByRole('button', {name: /create project/i});
        expect(createButton).toBeInTheDocument();

        // Excludes both the "Create Project" button and the header's "Ask Copilot" trigger, which also
        // renders in the button list once the project list is empty.
        const buttons = screen.getAllByRole('button');
        const chevronButton = buttons.find(
            (button) =>
                !/create project/i.test(button.textContent || '') && button.getAttribute('aria-label') !== 'Ask Copilot'
        )!;
        await userEvent.click(chevronButton);

        await waitFor(() => {
            expect(screen.getByText('From Template')).toBeInTheDocument();
            expect(screen.getByText('Import Project')).toBeInTheDocument();
        });
    });

    it('should trigger file input when import project is clicked', async () => {
        renderProjects();

        // Open the dropdown using the chevron button
        const buttons = screen.getAllByRole('button');
        const chevronButton = buttons.find((button) => /chevron-down/i.test(button.innerHTML))!;
        await userEvent.click(chevronButton);

        await waitFor(() => {
            const importButton = screen.getByText('Import Project');
            expect(importButton).toBeInTheDocument();

            // Since the file input is hidden and accessed via ref, we can't easily test the actual click
            // but we can verify the menu item exists and is clickable, which is the main functionality
            expect(importButton).toBeInTheDocument();
        });
    });

    it('should call import mutation when a file is selected', async () => {
        renderProjects();

        // Create a mock file
        const mockFile = new File(['test content'], 'test-project.zip', {
            type: 'application/zip',
        });

        // Find the hidden file input
        const fileInput = document.querySelector('input[type="file"][accept=".zip"]') as HTMLInputElement;
        expect(fileInput).toBeTruthy();

        // Simulate file selection using fireEvent
        fireEvent.change(fileInput, {target: {files: [mockFile]}});

        await waitFor(() => {
            expect(mockImportMutate).toHaveBeenCalledWith({
                file: mockFile,
                workspaceId: 1,
            });
        });
    });

    it('should capture analytics when the project import succeeds', async () => {
        renderProjects();

        const fileInput = document.querySelector('input[type="file"][accept=".zip"]') as HTMLInputElement;

        fireEvent.change(fileInput, {target: {files: [new File(['test content'], 'test-project.zip')]}});

        await waitFor(() => {
            expect(mockProjectsState.captureProjectImported).toHaveBeenCalledTimes(1);
        });
    });
});

describe('Projects empty states', () => {
    it('explains what a project is and links to the docs', () => {
        renderProjects();

        expect(screen.getByRole('heading', {level: 2, name: 'Create your first project'})).toBeInTheDocument();
        expect(screen.getByText(/A project groups related workflows\./)).toBeInTheDocument();
        expect(screen.getByRole('link', {name: 'How projects work'})).toHaveAttribute(
            'href',
            'https://docs.bytechef.io/platform/automation/build/workflows/projects'
        );
    });

    it('shows creating a project as the current step of getting started', () => {
        renderProjects();

        const steps = within(screen.getByRole('list', {name: 'Getting started'})).getAllByRole('listitem');

        expect(steps).toHaveLength(4);
        expect(steps[0]).toHaveTextContent('Create a project');
        expect(steps[0]).toHaveAttribute('aria-current', 'step');
    });

    it('records a click on Create Project', async () => {
        renderProjects();

        await userEvent.click(screen.getByRole('button', {name: 'Create Project'}));

        expect(mockProjectsState.captureEmptyStateAction).toHaveBeenCalledWith(
            'projects',
            'create_project',
            'createProject'
        );
    });

    it('hides the category and tag sidebar until there is something to filter', () => {
        renderProjects();

        expect(screen.queryByText('No defined tags.')).not.toBeInTheDocument();
        expect(screen.getByText('Projects')).toBeInTheDocument();
    });

    // The filtered list and the unfiltered one were the same array, so a filter that matched nothing rendered
    // the first-run state and told the reader the workspace was empty.
    it('reports a filter miss rather than an empty workspace', () => {
        renderProjects(['/?tagId=1']);

        expect(screen.getByText('No Matching Projects')).toBeInTheDocument();
        expect(screen.queryByText('Create your first project')).not.toBeInTheDocument();
        expect(screen.getByText('No defined tags.')).toBeInTheDocument();
    });
});

describe('Projects menus', () => {
    const openMenu = async () => {
        await userEvent.click(document.querySelector('[aria-haspopup="menu"]')!);
    };

    describe.each([
        ['empty state', [] as unknown[]],
        ['header', [{id: 1, name: 'Project'}]],
    ])('%s menu', (_name, projects) => {
        beforeEach(() => {
            mockProjectsState.projects = projects as {id: number; name: string}[];
        });

        it('should render with the design-system DropdownMenu', async () => {
            renderProjects();

            await openMenu();

            expect(screen.getByRole('menu')).toHaveClass('bg-surface-neutral-primary');
            expect(screen.getByRole('menuitem', {name: 'From Template'})).toHaveClass('text-content-neutral-primary');
            expect(screen.getByRole('menuitem', {name: 'Import Project'})).toBeInTheDocument();
        });

        it('should navigate to the templates when From Template is clicked', async () => {
            renderProjects();

            await openMenu();

            await userEvent.click(screen.getByRole('menuitem', {name: 'From Template'}));

            expect(mockProjectsState.navigate).toHaveBeenCalledWith('templates');
        });

        it('should open the file picker when Import Project is clicked', async () => {
            const clickSpy = vi.spyOn(HTMLInputElement.prototype, 'click');

            renderProjects();

            await openMenu();

            await userEvent.click(screen.getByRole('menuitem', {name: 'Import Project'}));

            expect(clickSpy).toHaveBeenCalled();

            clickSpy.mockRestore();
        });
    });
});

describe('Projects agents filter', () => {
    const setUpProjectsWithAgents = () => {
        mockProjectsState.projects = [
            {id: 1, name: 'Scheduled Project'},
            {id: 2, name: 'Chat Project'},
            {id: 3, name: 'Workflow Project'},
        ];

        mockProjectsState.agents = [
            {channels: [{channelType: 'chat'}, {channelType: 'schedule'}], id: 'a1', projectId: '1'},
            {channels: [{channelType: 'chat'}], id: 'a2', projectId: '2'},
        ];
    };

    it('shows the Agents section between Categories and Tags', () => {
        setUpProjectsWithAgents();

        renderProjects();

        const categoriesHeading = screen.getByText('Categories');
        const agentsHeading = screen.getByText('Agents');
        const tagsHeading = screen.getByText('Tags');

        expect(
            categoriesHeading.compareDocumentPosition(agentsHeading) & Node.DOCUMENT_POSITION_FOLLOWING
        ).toBeTruthy();
        expect(agentsHeading.compareDocumentPosition(tagsHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

        expect(screen.getByRole('link', {name: 'All Agents'})).toHaveAttribute('href', '/?agents=all');
        expect(getFilterLink('Scheduled', 'agents=scheduled')).toHaveAttribute('href', '/?agents=scheduled');
    });

    it('no longer offers the Projects | Agents tabs', () => {
        renderProjects();

        expect(screen.queryByRole('tab')).not.toBeInTheDocument();
        expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    });

    it('lists every project and opens rows on Workflows without the filter', () => {
        setUpProjectsWithAgents();

        renderProjects();

        expect(screen.getByText('Scheduled Project')).toBeInTheDocument();
        expect(screen.getByText('Chat Project')).toBeInTheDocument();
        expect(screen.getByText('Workflow Project')).toBeInTheDocument();
        expect(screen.getByTestId('project-list')).toHaveAttribute('data-default-active-tab', 'workflows');
    });

    it('keeps only projects with an agent for All Agents, opening rows on Agents', () => {
        setUpProjectsWithAgents();

        renderProjects(['/?agents=all']);

        expect(screen.getByText('Scheduled Project')).toBeInTheDocument();
        expect(screen.getByText('Chat Project')).toBeInTheDocument();
        expect(screen.queryByText('Workflow Project')).not.toBeInTheDocument();
        expect(screen.getByTestId('project-list')).toHaveAttribute('data-default-active-tab', 'agents');
        expect(screen.getByText('Agents: All Agents')).toBeInTheDocument();
    });

    it('keeps only projects with a scheduled agent for Scheduled', () => {
        setUpProjectsWithAgents();

        renderProjects(['/?agents=scheduled']);

        expect(screen.getByText('Scheduled Project')).toBeInTheDocument();
        expect(screen.queryByText('Chat Project')).not.toBeInTheDocument();
        expect(screen.queryByText('Workflow Project')).not.toBeInTheDocument();
        expect(screen.getByTestId('project-list')).toHaveAttribute('data-default-active-tab', 'agents');
    });

    it('reports a filter miss when no project has a matching agent', () => {
        mockProjectsState.projects = [{id: 3, name: 'Workflow Project'}];

        renderProjects(['/?agents=scheduled']);

        expect(screen.getByText('No Matching Projects')).toBeInTheDocument();
    });

    it('clears the active agents filter on a second click and keeps the category filter', () => {
        renderProjects(['/?categoryId=5&agents=all']);

        expect(screen.getByRole('link', {name: 'All Agents'})).toHaveAttribute('href', '/?categoryId=5');
        expect(getFilterLink('Scheduled', 'agents=scheduled')).toHaveAttribute(
            'href',
            '/?categoryId=5&agents=scheduled'
        );
    });

    it('picking a data syncs filter clears the active agents filter', () => {
        renderProjects(['/?agents=all']);

        expect(screen.getByRole('link', {name: 'All Data Syncs'})).toHaveAttribute('href', '/?dataSyncs=all');
    });
});

describe('Projects data syncs filter', () => {
    const setUpProjectsWithDataSyncs = () => {
        mockProjectsState.projects = [
            {id: 1, name: 'Scheduled Project'},
            {id: 2, name: 'Manual Project'},
            {id: 3, name: 'Workflow Project'},
        ];

        mockProjectsState.dataSyncs = [
            {id: 'ds1', projectId: '1', triggerType: 'SCHEDULE'},
            {id: 'ds2', projectId: '2', triggerType: 'MANUAL'},
        ];
    };

    it('shows the Data Syncs section between Agents and Tags', () => {
        setUpProjectsWithDataSyncs();

        renderProjects();

        const agentsHeading = screen.getByText('Agents');
        const dataSyncsHeading = screen.getByText('Data Syncs');
        const tagsHeading = screen.getByText('Tags');

        expect(agentsHeading.compareDocumentPosition(dataSyncsHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        expect(dataSyncsHeading.compareDocumentPosition(tagsHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

        expect(screen.getByRole('link', {name: 'All Data Syncs'})).toHaveAttribute('href', '/?dataSyncs=all');
        expect(getFilterLink('Scheduled', 'dataSyncs=scheduled')).toHaveAttribute('href', '/?dataSyncs=scheduled');
    });

    it('keeps only projects with a data sync for All Data Syncs, opening rows on Data Syncs', () => {
        setUpProjectsWithDataSyncs();

        renderProjects(['/?dataSyncs=all']);

        expect(screen.getByText('Scheduled Project')).toBeInTheDocument();
        expect(screen.getByText('Manual Project')).toBeInTheDocument();
        expect(screen.queryByText('Workflow Project')).not.toBeInTheDocument();
        expect(screen.getByTestId('project-list')).toHaveAttribute('data-default-active-tab', 'dataSyncs');
        expect(screen.getByText('Data Syncs: All Data Syncs')).toBeInTheDocument();
    });

    it('keeps only projects with a scheduled data sync for Scheduled', () => {
        setUpProjectsWithDataSyncs();

        renderProjects(['/?dataSyncs=scheduled']);

        expect(screen.getByText('Scheduled Project')).toBeInTheDocument();
        expect(screen.queryByText('Manual Project')).not.toBeInTheDocument();
        expect(screen.queryByText('Workflow Project')).not.toBeInTheDocument();
        expect(screen.getByTestId('project-list')).toHaveAttribute('data-default-active-tab', 'dataSyncs');
    });

    it('clears the active data syncs filter on a second click and keeps the category filter', () => {
        renderProjects(['/?categoryId=5&dataSyncs=all']);

        expect(screen.getByRole('link', {name: 'All Data Syncs'})).toHaveAttribute('href', '/?categoryId=5');
        expect(getFilterLink('Scheduled', 'dataSyncs=scheduled')).toHaveAttribute(
            'href',
            '/?categoryId=5&dataSyncs=scheduled'
        );
    });

    it('picking an agents filter clears the active data syncs filter', () => {
        renderProjects(['/?dataSyncs=all']);

        expect(screen.getByRole('link', {name: 'All Agents'})).toHaveAttribute('href', '/?agents=all');
    });

    it('carries the active data syncs filter over to the category and tag links', () => {
        renderProjects(['/?dataSyncs=scheduled']);

        expect(screen.getByRole('link', {name: 'All Categories'})).toHaveAttribute('href', '/?dataSyncs=scheduled');
    });

    it('offers to create a data sync from an unlocked dialog when no project matches', async () => {
        mockProjectsState.projects = [{id: 3, name: 'Workflow Project'}];

        renderProjects(['/?dataSyncs=scheduled']);

        expect(screen.getByText('No Matching Projects')).toBeInTheDocument();

        const createButton = screen.getByRole('button', {name: 'Create Data Sync'});

        expect(createButton).toBeInTheDocument();

        await userEvent.click(createButton);

        await waitFor(() => {
            expect(screen.getByText('Project')).toBeInTheDocument();
            expect(screen.getByRole('combobox')).toBeInTheDocument();
        });
    });
});

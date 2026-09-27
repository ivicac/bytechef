import {ProjectDeployment} from '@/shared/middleware/automation/configuration';
import {render, screen} from '@/shared/util/test-utils';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import ProjectDeploymentListItem from './ProjectDeploymentListItem';

// ---------------------------------------------------------------------------
// Hoisted mocks (must not reference outer-scope constants - vi.hoisted runs
// before module initialisation)
// ---------------------------------------------------------------------------

const hoisted = vi.hoisted(() => ({
    canOpenInProject: true,
    deleteProjectDeploymentMock: vi.fn(),
    edition: 'EE',
    environmentsResult: {
        data: {
            environments: [
                {id: '0', name: 'Development'},
                {id: '1', name: 'Staging'},
            ],
        },
    } as {
        data: {environments: {id: string; name: string}[]} | undefined;
    },
    invalidateQueriesMock: vi.fn(),
    openProjectMock: vi.fn(),
    promotionDialogProps: [] as unknown[],
}));

vi.mock('@/pages/automation/project-deployments/hooks/useOpenInProject', () => ({
    default: () => ({
        canOpenInProject: hoisted.canOpenInProject,
        openProject: hoisted.openProjectMock,
        openProjectWorkflow: vi.fn(),
    }),
}));

vi.mock('@tanstack/react-query', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@tanstack/react-query')>();

    return {
        ...actual,
        useQueryClient: () => ({invalidateQueries: hoisted.invalidateQueriesMock}),
    };
});

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useEnvironmentsQuery: () => hoisted.environmentsResult,
    };
});

vi.mock('@/shared/mutations/automation/projectDeployments.mutations', () => ({
    useDeleteProjectDeploymentMutation: () => ({isPending: false, mutate: hoisted.deleteProjectDeploymentMock}),
    useEnableProjectDeploymentMutation: () => ({isPending: false, mutate: vi.fn()}),
}));

vi.mock('@/shared/mutations/automation/projectDeploymentTags.mutations', () => ({
    useUpdateProjectDeploymentTagsMutation: () => ({mutate: vi.fn()}),
}));

vi.mock('@/shared/hooks/useAnalytics', () => ({
    useAnalytics: () => ({captureProjectDeploymentEnabled: vi.fn()}),
}));

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: (selector: (state: Record<string, unknown>) => unknown) => selector({currentWorkspaceId: 1}),
}));

vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: (selector: (state: Record<string, unknown>) => unknown) =>
        selector({application: {edition: hoisted.edition}}),
}));

vi.mock('@/components/ui/collapsible', () => ({
    CollapsibleTrigger: ({children}: {children: React.ReactNode}) => <button type="button">{children}</button>,
}));

vi.mock('@/components/ui/tooltip', () => ({
    Tooltip: ({children}: {children: React.ReactNode}) => <>{children}</>,
    TooltipContent: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
    TooltipTrigger: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
}));

vi.mock('@/components/ui/dropdown-menu', () => ({
    DropdownMenu: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
    DropdownMenuContent: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
    DropdownMenuItem: ({children, onClick}: {children: React.ReactNode; onClick?: () => void}) => (
        <button onClick={onClick} type="button">
            {children}
        </button>
    ),
    DropdownMenuSeparator: () => <hr />,
    DropdownMenuTrigger: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
}));

vi.mock('@/ee/shared/components/environment-promotion/EnvironmentPromotionDialog', () => ({
    default: (props: Record<string, unknown>) => {
        hoisted.promotionDialogProps.push(props);

        return (
            <div data-testid="environment-promotion-dialog">
                <button onClick={props.onClose as () => void} type="button">
                    close
                </button>

                <button onClick={() => (props.onPromoted as (result: unknown) => void)?.({})} type="button">
                    promote
                </button>
            </div>
        );
    },
}));

const projectDeployment: ProjectDeployment = {
    enabled: true,
    environmentId: 1,
    id: 42,
    name: 'My Project Deployment',
    projectDeploymentWorkflows: [],
    projectId: 7,
    projectVersion: 1,
    tags: [],
};

describe('ProjectDeploymentListItem', () => {
    beforeEach(() => {
        hoisted.canOpenInProject = true;
        hoisted.deleteProjectDeploymentMock.mockReset();
        hoisted.edition = 'EE';
        hoisted.openProjectMock.mockReset();
        hoisted.invalidateQueriesMock.mockReset();
        hoisted.promotionDialogProps.length = 0;
        hoisted.environmentsResult.data = {
            environments: [
                {id: '0', name: 'Development'},
                {id: '1', name: 'Staging'},
            ],
        };
    });

    it('closes the delete dialog without deleting when cancelled', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByText('Delete'));

        expect(screen.getByRole('alertdialog')).toBeInTheDocument();

        await user.click(screen.getByRole('button', {name: 'Cancel'}));

        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
        expect(hoisted.deleteProjectDeploymentMock).not.toHaveBeenCalled();
    });

    it('deletes the deployment when the delete dialog is confirmed', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByText('Delete'));

        await user.click(screen.getByRole('button', {name: 'Delete'}));

        expect(hoisted.deleteProjectDeploymentMock).toHaveBeenCalledWith(42);
    });

    it('always shows the workflow, agent and data sync counts, zeros included', () => {
        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.getByText('0 workflows · 0 agents · 0 data syncs')).toBeInTheDocument();
    });

    it('uses the singular for one workflow, one agent and one data sync', () => {
        render(
            <ProjectDeploymentListItem
                agentCount={1}
                dataSyncCount={1}
                projectDeployment={projectDeployment}
                workflowCount={1}
            />
        );

        expect(screen.getByText('1 workflow · 1 agent · 1 data sync')).toBeInTheDocument();
    });

    it('uses the plural for several workflows, agents and data syncs', () => {
        render(
            <ProjectDeploymentListItem
                agentCount={2}
                dataSyncCount={4}
                projectDeployment={projectDeployment}
                workflowCount={3}
            />
        );

        expect(screen.getByText('3 workflows · 2 agents · 4 data syncs')).toBeInTheDocument();
    });

    it('hides the Promote to environment menu item when fewer than two environments exist', () => {
        hoisted.environmentsResult.data = {environments: [{id: '0', name: 'Development'}]};

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.queryByText('Promote to environment…')).not.toBeInTheDocument();
    });

    it('hides the Promote to environment menu item on CE even with multiple environments', () => {
        hoisted.edition = 'CE';

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.queryByText('Promote to environment…')).not.toBeInTheDocument();
    });

    it('mounts the dialog on click and unmounts it on close, passing the correct resourceType and ids', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.queryByTestId('environment-promotion-dialog')).not.toBeInTheDocument();

        await user.click(screen.getByText('Promote to environment…'));

        expect(await screen.findByTestId('environment-promotion-dialog')).toBeInTheDocument();
        expect(hoisted.promotionDialogProps.at(-1)).toMatchObject({
            resourceType: 'PROJECT_DEPLOYMENT',
            sourceEnvironmentId: 1,
            sourceId: '42',
            sourceName: 'My Project Deployment',
            workspaceId: 1,
        });

        await user.click(screen.getByText('close'));

        expect(screen.queryByTestId('environment-promotion-dialog')).not.toBeInTheDocument();
    });

    it('opens the project from the icon beside the name and from the menu', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByRole('button', {name: 'Open project'}));

        expect(hoisted.openProjectMock).toHaveBeenLastCalledWith(7);

        await user.click(screen.getByText('Open Project'));

        expect(hoisted.openProjectMock).toHaveBeenCalledTimes(2);
        expect(hoisted.openProjectMock).toHaveBeenLastCalledWith(7);
    });

    it('hides the open project controls where the project editor is unreachable', () => {
        hoisted.canOpenInProject = false;

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.queryByRole('button', {name: 'Open project'})).not.toBeInTheDocument();
        expect(screen.queryByText('Open Project')).not.toBeInTheDocument();
    });

    it('invalidates the projectDeployments query when onPromoted fires', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByText('Promote to environment…'));

        await user.click(await screen.findByText('promote'));

        expect(hoisted.invalidateQueriesMock).toHaveBeenCalledWith({queryKey: ['projectDeployments']});
    });
});

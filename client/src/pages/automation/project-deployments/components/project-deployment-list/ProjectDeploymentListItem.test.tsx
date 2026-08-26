import {ProjectDeployment} from '@/shared/middleware/automation/configuration';
import {render, screen} from '@/shared/util/test-utils';
import {QueryClient} from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import ProjectDeploymentListItem from './ProjectDeploymentListItem';

// ---------------------------------------------------------------------------
// Hoisted mocks (must not reference outer-scope constants - vi.hoisted runs
// before module initialisation)
// ---------------------------------------------------------------------------

const hoisted = vi.hoisted(() => ({
    captureProjectDeploymentEnabledMock: vi.fn(),
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
    promotionDialogProps: [] as unknown[],
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
    useDeleteProjectDeploymentMutation: ({onSuccess}: {onSuccess?: () => void}) => ({
        isPending: false,
        mutate: (id: number) => {
            hoisted.deleteProjectDeploymentMock(id);

            onSuccess?.();
        },
    }),
    useEnableProjectDeploymentMutation: ({
        onSuccess,
    }: {
        onSuccess?: (result: void, variables: {enable: boolean; id: number}) => void;
    }) => ({
        isPending: false,
        mutate: (variables: {enable: boolean; id: number}) => onSuccess?.(undefined, variables),
    }),
}));

vi.mock('@/shared/mutations/automation/projectDeploymentTags.mutations', () => ({
    useUpdateProjectDeploymentTagsMutation: () => ({mutate: vi.fn()}),
}));

vi.mock('@/shared/hooks/useAnalytics', () => ({
    useAnalytics: () => ({captureProjectDeploymentEnabled: hoisted.captureProjectDeploymentEnabledMock}),
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
    projectVersion: 1,
    tags: [],
};

describe('ProjectDeploymentListItem', () => {
    beforeEach(() => {
        hoisted.captureProjectDeploymentEnabledMock.mockReset();
        hoisted.deleteProjectDeploymentMock.mockReset();
        hoisted.edition = 'EE';
        hoisted.invalidateQueriesMock.mockReset();
        hoisted.promotionDialogProps.length = 0;
        hoisted.environmentsResult.data = {
            environments: [
                {id: '0', name: 'Development'},
                {id: '1', name: 'Staging'},
            ],
        };
    });

    it('shows the deployment icon before the name', () => {
        const {container} = render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(container.querySelector('svg.lucide-layers-3')).toBeInTheDocument();
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

    it('refreshes the project list after deleting so the deployment count updates', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByText('Delete'));

        await user.click(screen.getByRole('button', {name: 'Delete'}));

        expect(hoisted.invalidateQueriesMock).toHaveBeenCalledWith({queryKey: ['projects']});
    });

    it('opens the project from the icon beside the name and from the menu', async () => {
    it('hides the Promote to environment menu item when fewer than two environments exist', () => {
        hoisted.environmentsResult.data = {environments: [{id: '0', name: 'Development'}]};


        expect(screen.queryByText('Promote to environment…')).not.toBeInTheDocument();

    it('hides the Promote to environment menu item on CE even with multiple environments', () => {
        hoisted.edition = 'CE';



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

    it('invalidates the projectDeployments query when onPromoted fires', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByText('Promote to environment…'));

        await user.click(await screen.findByText('promote'));

        expect(hoisted.invalidateQueriesMock).toHaveBeenCalledWith({queryKey: ['projectDeployments']});
    });

    it('does not show an environment badge for a development deployment', () => {
        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.queryByText('DEV')).not.toBeInTheDocument();
    });

    it('shows the tags by default', () => {
        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        expect(screen.getByText('Tags:')).toBeInTheDocument();
    });

    it('hides the tags when showTags is false', () => {
        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} showTags={false} />);

        expect(screen.queryByText('Tags:')).not.toBeInTheDocument();
    });

    it('captures the deployment enabled event when the deployment is turned on', async () => {
        const user = userEvent.setup();

        render(
            <ProjectDeploymentListItem
                projectDeployment={{
                    ...projectDeployment,
                    enabled: false,
                    projectDeploymentWorkflows: [{enabled: true}],
                }}
            />
        );

        await user.click(screen.getByRole('switch'));

        expect(hoisted.captureProjectDeploymentEnabledMock).toHaveBeenCalledTimes(1);
    });

    it('does not capture the deployment enabled event when the deployment is turned off', async () => {
        const user = userEvent.setup();

        render(<ProjectDeploymentListItem projectDeployment={projectDeployment} />);

        await user.click(screen.getByRole('switch'));

        expect(hoisted.captureProjectDeploymentEnabledMock).not.toHaveBeenCalled();
    });
});

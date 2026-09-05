import {TooltipProvider} from '@/components/ui/tooltip';
import {DataSyncTriggerType} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import DataSyncDeployments from './DataSyncDeployments';

const {deploymentsState, searchParams, syncsState} = vi.hoisted(() => ({
    deploymentsState: {value: {data: undefined as unknown, error: null, isLoading: false}},
    searchParams: {value: new URLSearchParams()},
    syncsState: {value: {data: undefined as unknown, error: null, isLoading: false}},
}));

vi.mock('react-router-dom', () => ({
    useNavigate: () => vi.fn(),
    useSearchParams: () => [searchParams.value, vi.fn()],
}));

vi.mock('@/pages/automation/data-sync-deployments/components/DataSyncDeploymentsLeftSidebarNav', () => ({
    default: () => null,
}));

vi.mock('@/pages/automation/data-sync-deployments/components/DataSyncDeploymentListItem', () => ({
    default: ({deployment}: {deployment: {dataSyncTitle: string}}) => <div>{deployment.dataSyncTitle}</div>,
}));

vi.mock('@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog', () => ({
    default: () => null,
}));

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: (selector: (state: {currentWorkspaceId: number}) => unknown) =>
        selector({currentWorkspaceId: 1}),
}));

vi.mock('@/shared/middleware/graphql', async () => {
    const actual = await vi.importActual<typeof import('@/shared/middleware/graphql')>('@/shared/middleware/graphql');

    return {
        ...actual,
        useDataSyncDeploymentTagsQuery: () => ({data: {dataSyncDeploymentTags: []}}),
        useDataSyncDeploymentsQuery: () => deploymentsState.value,
        useDataSyncsQuery: () => syncsState.value,
    };
});

const deployment = (id: string, title: string, tagId?: string) => ({
    dataSyncId: '10',
    dataSyncTitle: title,
    enabled: true,
    environmentId: 2,
    id,
    name: 'deploy',
    projectId: '100',
    projectVersion: 1,
    tags: tagId ? [{id: tagId, name: 'crm'}] : [],
    triggerType: DataSyncTriggerType.Manual,
    workflowId: 'wf',
});

const renderPage = () =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <TooltipProvider>
                <DataSyncDeployments />
            </TooltipProvider>
        </QueryClientProvider>
    );

describe('DataSyncDeployments', () => {
    beforeEach(() => {
        searchParams.value = new URLSearchParams();
        deploymentsState.value = {data: {dataSyncDeployments: []}, error: null, isLoading: false};
        syncsState.value = {data: {dataSyncs: []}, error: null, isLoading: false};
    });

    it('renders one row per deployment', () => {
        deploymentsState.value = {
            data: {dataSyncDeployments: [deployment('20', 'CRM to DB')]},
            error: null,
            isLoading: false,
        };

        renderPage();

        expect(screen.getByText('CRM to DB')).toBeInTheDocument();
    });

    it('tells the user to publish first when nothing is deployable', () => {
        renderPage();

        expect(screen.getByText('Publish a data sync first, then deploy it here.')).toBeInTheDocument();
    });

    it('narrows the list to the selected tag', () => {
        searchParams.value = new URLSearchParams('tagId=7');
        deploymentsState.value = {
            data: {dataSyncDeployments: [deployment('20', 'Tagged', '7'), deployment('21', 'Untagged')]},
            error: null,
            isLoading: false,
        };

        renderPage();

        expect(screen.getByText('Tagged')).toBeInTheDocument();
        expect(screen.queryByText('Untagged')).not.toBeInTheDocument();
    });
});

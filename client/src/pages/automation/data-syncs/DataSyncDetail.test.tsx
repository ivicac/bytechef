import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import DataSyncDetail from './DataSyncDetail';

const {queryState} = vi.hoisted(() => ({
    queryState: {value: {data: undefined as unknown, error: null, isLoading: false}},
}));

vi.mock('react-router-dom', () => ({
    useNavigate: () => vi.fn(),
    useParams: () => ({dataSyncId: '10'}),
}));

vi.mock('@/pages/automation/data-syncs/components/DataSyncDialog', () => ({default: () => null}));
vi.mock('@/pages/automation/data-syncs/components/DataSyncsLeftSidebarNav', () => ({default: () => null}));

vi.mock('@/pages/automation/data-syncs/components/detail/DataSyncDetailHeader', () => ({
    default: ({title}: {title: string}) => <h1>{title}</h1>,
}));

vi.mock('@/pages/automation/data-syncs/components/wizard/DataSyncWizard', () => ({
    default: ({dataSync}: {dataSync: {id: string}}) => <div>wizard for {dataSync.id}</div>,
}));

vi.mock('@/shared/middleware/graphql', async () => {
    const actual = await vi.importActual<typeof import('@/shared/middleware/graphql')>('@/shared/middleware/graphql');

    return {...actual, useDataSyncQuery: () => queryState.value};
});

describe('DataSyncDetail', () => {
    it('renders the header and the wizard for the routed sync', () => {
        queryState.value = {
            data: {
                dataSync: {
                    draftWorkflowId: 'wf',
                    elements: [],
                    id: '10',
                    lastPublishedVersion: 0,
                    projectId: '100',
                    title: 'CRM to DB',
                },
            },
            error: null,
            isLoading: false,
        };

        render(
            <QueryClientProvider client={new QueryClient()}>
                <DataSyncDetail />
            </QueryClientProvider>
        );

        expect(screen.getByRole('heading', {name: 'CRM to DB'})).toBeInTheDocument();
        expect(screen.getByText('wizard for 10')).toBeInTheDocument();
    });

    it('renders nothing but the loader while the sync loads', () => {
        queryState.value = {data: undefined, error: null, isLoading: true};

        render(
            <QueryClientProvider client={new QueryClient()}>
                <DataSyncDetail />
            </QueryClientProvider>
        );

        expect(screen.queryByText(/wizard for/)).not.toBeInTheDocument();
    });
});

import {TooltipProvider} from '@/components/ui/tooltip';
import {DataSyncTriggerType} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import DataSyncs from './DataSyncs';

const {queryState, searchParams} = vi.hoisted(() => ({
    queryState: {value: {data: undefined as unknown, error: null, isLoading: false}},
    searchParams: {value: new URLSearchParams()},
}));

vi.mock('react-router-dom', () => ({
    useNavigate: () => vi.fn(),
    useSearchParams: () => [searchParams.value, vi.fn()],
}));

vi.mock('@/pages/automation/data-syncs/components/DataSyncsLeftSidebarNav', () => ({default: () => null}));

vi.mock('@/pages/automation/data-syncs/components/DataSyncDialog', () => ({
    default: ({triggerNode}: {triggerNode: React.ReactNode}) => <>{triggerNode}</>,
}));

vi.mock('@/pages/automation/data-syncs/components/data-sync-list/DataSyncListItem', () => ({
    default: ({dataSync}: {dataSync: {title: string}}) => <div>{dataSync.title}</div>,
}));

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: (selector: (state: {currentWorkspaceId: number}) => unknown) =>
        selector({currentWorkspaceId: 1}),
}));

vi.mock('@/shared/middleware/graphql', async () => {
    const actual = await vi.importActual<typeof import('@/shared/middleware/graphql')>('@/shared/middleware/graphql');

    return {...actual, useDataSyncsQuery: () => queryState.value};
});

const dataSync = (id: string, title: string, tagId?: string) => ({
    elements: [],
    id,
    lastPublishedVersion: 0,
    name: title.toLowerCase(),
    projectId: '100',
    tags: tagId ? [{id: tagId, name: 'crm'}] : [],
    title,
    triggerParameters: null,
    triggerType: DataSyncTriggerType.Manual,
    unpublishedChanges: true,
    visibility: 'WORKSPACE',
});

const renderPage = () =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <TooltipProvider>
                <DataSyncs />
            </TooltipProvider>
        </QueryClientProvider>
    );

describe('DataSyncs', () => {
    beforeEach(() => {
        searchParams.value = new URLSearchParams();
        queryState.value = {data: undefined, error: null, isLoading: false};
    });

    it('shows the loading state without the list or the empty state', () => {
        queryState.value = {data: undefined, error: null, isLoading: true};

        const {container} = renderPage();

        // PageLoader renders LoadingDots (four pulsing dots, no text or role) while loading, so the positive
        // assertion is the real markup it produces rather than a stand-in test id — this fails if the page
        // ever regresses to rendering nothing at all while loading.
        expect(container.querySelector('.animate-pulse')).toBeInTheDocument();
        expect(screen.queryByText('No Data Syncs')).not.toBeInTheDocument();
    });

    it('maps queried syncs into rows', () => {
        queryState.value = {
            data: {dataSyncs: [dataSync('1', 'Alpha'), dataSync('2', 'Beta')]},
            error: null,
            isLoading: false,
        };

        renderPage();

        expect(screen.getByText('Alpha')).toBeInTheDocument();
        expect(screen.getByText('Beta')).toBeInTheDocument();
    });

    it('narrows the list to the selected tag', () => {
        searchParams.value = new URLSearchParams('tagId=7');
        queryState.value = {
            data: {dataSyncs: [dataSync('1', 'Alpha', '7'), dataSync('2', 'Beta')]},
            error: null,
            isLoading: false,
        };

        renderPage();

        expect(screen.getByText('Alpha')).toBeInTheDocument();
        expect(screen.queryByText('Beta')).not.toBeInTheDocument();
    });

    it('offers creation only when the workspace is empty', () => {
        queryState.value = {data: {dataSyncs: []}, error: null, isLoading: false};

        renderPage();

        expect(screen.getByText('No Data Syncs')).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Create Data Sync'})).toBeInTheDocument();
    });
});

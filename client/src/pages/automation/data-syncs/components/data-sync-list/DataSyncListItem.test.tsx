import {TooltipProvider} from '@/components/ui/tooltip';
import {DataSyncElementKind, DataSyncTriggerType} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import DataSyncListItem from './DataSyncListItem';

const {navigateMock} = vi.hoisted(() => ({navigateMock: vi.fn()}));

vi.mock('react-router-dom', () => ({useNavigate: () => navigateMock}));

vi.mock('@/shared/queries/automation/componentDefinitions.queries', () => ({
    useGetComponentDefinitionsQuery: () => ({
        data: [
            {icon: 'csv.svg', name: 'csvFile', title: 'CSV File'},
            {icon: 'pg.svg', name: 'postgresql', title: 'PostgreSQL'},
        ],
    }),
}));

vi.mock('@/shared/middleware/graphql', async () => {
    const actual = await vi.importActual<typeof import('@/shared/middleware/graphql')>('@/shared/middleware/graphql');

    return {
        ...actual,
        useDataSyncTagsQuery: () => ({data: {dataSyncTags: []}}),
        useDeleteDataSyncMutation: () => ({isPending: false, mutate: vi.fn()}),
        usePublishDataSyncMutation: () => ({isPending: false, mutate: vi.fn()}),
        useUpdateDataSyncTagsMutation: () => ({isPending: false, mutate: vi.fn()}),
    };
});

const dataSync = {
    description: 'nightly',
    elements: [
        {
            componentName: 'csvFile',
            componentVersion: 1,
            id: '1',
            kind: DataSyncElementKind.Source,
            operationName: 'read',
        },
        {
            componentName: 'postgresql',
            componentVersion: 1,
            id: '2',
            kind: DataSyncElementKind.Destination,
            operationName: 'insert',
        },
    ],
    id: '10',
    lastPublishedVersion: 0,
    name: 'crm-to-db',
    projectId: '100',
    tags: [],
    title: 'CRM to DB',
    triggerParameters: {frequencyKind: 'DAILY', timeOfDay: '09:00'},
    triggerType: DataSyncTriggerType.Schedule,
    unpublishedChanges: true,
    visibility: 'WORKSPACE',
};

const renderItem = () =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <TooltipProvider>
                <DataSyncListItem dataSync={dataSync as never} />
            </TooltipProvider>
        </QueryClientProvider>
    );

describe('DataSyncListItem', () => {
    it('names both sides and the cadence', () => {
        renderItem();

        expect(screen.getByText('CRM to DB')).toBeInTheDocument();
        expect(screen.getByText('CSV File')).toBeInTheDocument();
        expect(screen.getByText('PostgreSQL')).toBeInTheDocument();
        expect(screen.getByText('Daily at 09:00')).toBeInTheDocument();
        expect(screen.getByText('DRAFT')).toBeInTheDocument();
    });

    it('disables Deploy until published', () => {
        renderItem();

        expect(screen.getByRole('button', {name: /deploy/i})).toBeDisabled();
    });

    it('falls back to placeholder text for a freshly created sync with no elements', () => {
        const freshDataSync = {...dataSync, elements: []};

        render(
            <QueryClientProvider client={new QueryClient()}>
                <TooltipProvider>
                    <DataSyncListItem dataSync={freshDataSync as never} />
                </TooltipProvider>
            </QueryClientProvider>
        );

        expect(screen.getByText('No source')).toBeInTheDocument();
        expect(screen.getByText('No destination')).toBeInTheDocument();
    });
});

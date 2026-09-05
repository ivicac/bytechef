import {TooltipProvider} from '@/components/ui/tooltip';
import {DataSyncTriggerType} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {describe, expect, it, vi} from 'vitest';

import DataSyncDeploymentListItem from './DataSyncDeploymentListItem';

const {runMock} = vi.hoisted(() => ({runMock: vi.fn()}));

vi.mock('@/shared/middleware/graphql', async () => {
    const actual = await vi.importActual<typeof import('@/shared/middleware/graphql')>('@/shared/middleware/graphql');

    return {
        ...actual,
        useRunDataSyncDeploymentMutation: () => ({isPending: false, mutate: runMock}),
        useUpdateDataSyncDeploymentTagsMutation: () => ({isPending: false, mutate: vi.fn()}),
    };
});

vi.mock('@/shared/mutations/automation/projectDeployments.mutations', () => ({
    useDeleteProjectDeploymentMutation: () => ({isPending: false, mutate: vi.fn()}),
    useEnableProjectDeploymentMutation: () => ({isPending: false, mutate: vi.fn()}),
}));

vi.mock('@/shared/queries/automation/projectDeployments.queries', () => ({
    ProjectDeploymentKeys: {projectDeployments: ['projectDeployments']},
    useGetProjectDeploymentQuery: () => ({data: undefined}),
}));

const deployment = {
    dataSyncId: '10',
    dataSyncTitle: 'CRM to DB',
    enabled: true,
    environmentId: 2,
    id: '20',
    name: 'deploy',
    projectId: '100',
    projectVersion: 1,
    tags: [],
    triggerType: DataSyncTriggerType.Schedule,
    workflowId: 'wf',
};

const renderItem = (enabled = true) =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <TooltipProvider>
                <DataSyncDeploymentListItem deployment={{...deployment, enabled} as never} />
            </TooltipProvider>
        </QueryClientProvider>
    );

describe('DataSyncDeploymentListItem', () => {
    it('runs the deployment through the Data Sync mutation', async () => {
        renderItem();

        await userEvent.click(screen.getByRole('button', {name: /run now/i}));

        expect(runMock).toHaveBeenCalledWith({id: '10', projectDeploymentId: '20'});
    });

    it('disables Run now while the deployment is disabled', () => {
        renderItem(false);

        expect(screen.getByRole('button', {name: /run now/i})).toBeDisabled();
    });
});

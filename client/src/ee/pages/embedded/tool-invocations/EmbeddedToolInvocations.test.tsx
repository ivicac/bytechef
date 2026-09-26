import {useToolInvocations} from '@/pages/automation/tool-invocations/hooks/useToolInvocations';
import {environmentStore} from '@/shared/stores/useEnvironmentStore';
import {render, screen, userEvent} from '@/shared/util/test-utils';
import {act} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {EmbeddedToolInvocations} from './EmbeddedToolInvocations';

const hoisted = vi.hoisted(() => ({
    useToolInvocationLogsQueryMock: vi.fn(),
    useWorkspaceToolInvocationLogsQueryMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useToolInvocationLogsQuery: hoisted.useToolInvocationLogsQueryMock,
    useWorkspaceToolInvocationLogsQuery: hoisted.useWorkspaceToolInvocationLogsQueryMock,
}));

vi.mock('@/pages/automation/tool-invocations/ToolInvocations', () => ({
    ToolInvocations: ({
        environmentId,
        workspaceScope,
    }: {
        environmentId?: number;
        workspaceScope?: Parameters<typeof useToolInvocations>[0];
    }) => {
        const {filterOutcome, filterPageNumber, handleOutcomeChange, handlePaginationClick} = useToolInvocations(
            workspaceScope,
            environmentId
        );

        return (
            <div>
                <span data-testid="filters">{`outcome=${filterOutcome ?? ''};page=${filterPageNumber}`}</span>

                <span data-testid="workspace-scope">{workspaceScope === undefined ? 'none' : 'scoped'}</span>

                <button onClick={() => handleOutcomeChange({label: 'Error', value: 'ERROR'})}>pick outcome</button>

                <button onClick={() => handlePaginationClick(3)}>next pages</button>
            </div>
        );
    },
}));

const emptyQueryResult = () => ({
    data: undefined,
    error: null,
    isFetching: false,
    isLoading: false,
    refetch: vi.fn(),
});

const renderPage = () =>
    render(
        <MemoryRouter>
            <EmbeddedToolInvocations />
        </MemoryRouter>
    );

describe('EmbeddedToolInvocations', () => {
    beforeEach(() => {
        hoisted.useToolInvocationLogsQueryMock.mockReset();
        hoisted.useToolInvocationLogsQueryMock.mockReturnValue(emptyQueryResult());
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReset();
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReturnValue(emptyQueryResult());

        environmentStore.setState({currentEnvironmentId: 2});
    });

    it('reads the tenant-wide log of the current environment without a workspace scope', () => {
        renderPage();

        expect(screen.getByTestId('workspace-scope')).toHaveTextContent('none');
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenLastCalledWith(
            expect.objectContaining({environmentId: '2', page: 0}),
            {enabled: true}
        );
    });

    it('resets the filters and the page and queries the new environment when the environment changes', async () => {
        renderPage();

        await userEvent.click(screen.getByRole('button', {name: 'pick outcome'}));
        await userEvent.click(screen.getByRole('button', {name: 'next pages'}));

        expect(screen.getByTestId('filters')).toHaveTextContent('outcome=ERROR;page=3');
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenLastCalledWith(
            expect.objectContaining({environmentId: '2', outcome: 'ERROR', page: 3}),
            {enabled: true}
        );

        act(() => {
            environmentStore.setState({currentEnvironmentId: 1});
        });

        expect(screen.getByTestId('filters')).toHaveTextContent('outcome=;page=0');
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenLastCalledWith(
            expect.objectContaining({environmentId: '1', outcome: undefined, page: 0}),
            {enabled: true}
        );
    });
});

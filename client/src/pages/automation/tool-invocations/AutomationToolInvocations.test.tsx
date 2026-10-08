import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useToolInvocations} from '@/pages/automation/tool-invocations/hooks/useToolInvocations';
import {environmentStore} from '@/shared/stores/useEnvironmentStore';
import {render, screen, userEvent} from '@/shared/util/test-utils';
import {act} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {AutomationToolInvocations} from './AutomationToolInvocations';

const hoisted = vi.hoisted(() => ({
    useAppsQueryMock: vi.fn(),
    useToolInvocationLogsQueryMock: vi.fn(),
    useWorkspaceMcpServersQueryMock: vi.fn(),
    useWorkspaceToolInvocationLogsQueryMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useAppsQuery: hoisted.useAppsQueryMock,
    useToolInvocationLogsQuery: hoisted.useToolInvocationLogsQueryMock,
    useWorkspaceMcpServersQuery: hoisted.useWorkspaceMcpServersQueryMock,
    useWorkspaceToolInvocationLogsQuery: hoisted.useWorkspaceToolInvocationLogsQueryMock,
}));

vi.mock('@/pages/automation/tool-invocations/ToolInvocations', () => ({
    ToolInvocations: ({workspaceScope}: {workspaceScope: Parameters<typeof useToolInvocations>[0]}) => {
        const {
            filterAppId,
            filterMcpServerId,
            filterPageNumber,
            handleAppChange,
            handleMcpServerChange,
            handlePaginationClick,
        } = useToolInvocations(workspaceScope);

        return (
            <div>
                <span data-testid="filters">
                    {`app=${filterAppId ?? ''};mcp=${filterMcpServerId ?? ''};page=${filterPageNumber}`}
                </span>

                <button onClick={() => handleAppChange({label: 'Support', value: '5'})}>pick app</button>

                <button onClick={() => handleMcpServerChange({label: 'Server', value: '9'})}>pick server</button>

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
            <AutomationToolInvocations />
        </MemoryRouter>
    );

describe('AutomationToolInvocations', () => {
    beforeEach(() => {
        hoisted.useAppsQueryMock.mockReset();
        hoisted.useAppsQueryMock.mockReturnValue({data: undefined});
        hoisted.useToolInvocationLogsQueryMock.mockReset();
        hoisted.useToolInvocationLogsQueryMock.mockReturnValue(emptyQueryResult());
        hoisted.useWorkspaceMcpServersQueryMock.mockReset();
        hoisted.useWorkspaceMcpServersQueryMock.mockReturnValue({data: undefined});
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReset();
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReturnValue(emptyQueryResult());

        environmentStore.setState({currentEnvironmentId: 2});
        useWorkspaceStore.setState({currentWorkspaceId: 7});
    });

    it.each([
        ['environment', () => environmentStore.setState({currentEnvironmentId: 3}), '3', '7'],
        ['workspace', () => useWorkspaceStore.setState({currentWorkspaceId: 8}), '2', '8'],
    ])(
        'resets the filters and the page and queries the new scope when the %s changes',
        async (_scope, switchScope, environmentId, workspaceId) => {
            renderPage();

            await userEvent.click(screen.getByRole('button', {name: 'pick app'}));
            await userEvent.click(screen.getByRole('button', {name: 'pick server'}));
            await userEvent.click(screen.getByRole('button', {name: 'next pages'}));

            expect(screen.getByTestId('filters')).toHaveTextContent('app=5;mcp=9;page=3');
            expect(hoisted.useWorkspaceToolInvocationLogsQueryMock).toHaveBeenLastCalledWith(
                expect.objectContaining({appId: 5, environmentId: '2', mcpServerId: 9, page: 3, workspaceId: '7'}),
                {enabled: true}
            );

            act(() => {
                switchScope();
            });

            expect(screen.getByTestId('filters')).toHaveTextContent('app=;mcp=;page=0');
            expect(hoisted.useWorkspaceToolInvocationLogsQueryMock).toHaveBeenLastCalledWith(
                expect.objectContaining({
                    appId: undefined,
                    environmentId,
                    mcpServerId: undefined,
                    page: 0,
                    workspaceId,
                }),
                {enabled: true}
            );
        }
    );
});

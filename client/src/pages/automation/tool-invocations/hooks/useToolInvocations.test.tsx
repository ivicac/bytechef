import {act, renderHook} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {ToolInvocationsWorkspaceScopeI, useToolInvocations} from './useToolInvocations';

const hoisted = vi.hoisted(() => ({
    useToolInvocationLogsQueryMock: vi.fn(),
    useWorkspaceToolInvocationLogsQueryMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useToolInvocationLogsQuery: hoisted.useToolInvocationLogsQueryMock,
    useWorkspaceToolInvocationLogsQuery: hoisted.useWorkspaceToolInvocationLogsQueryMock,
}));

const emptyQueryResult = () => ({
    data: undefined,
    error: null,
    isFetching: false,
    isLoading: false,
    refetch: vi.fn(),
});

const renderUseToolInvocations = (
    initialEntry: string,
    workspaceScope?: ToolInvocationsWorkspaceScopeI,
    environmentId?: number
) =>
    renderHook(() => useToolInvocations(workspaceScope, environmentId), {
        wrapper: ({children}) => <MemoryRouter initialEntries={[initialEntry]}>{children}</MemoryRouter>,
    });

describe('useToolInvocations', () => {
    beforeEach(() => {
        hoisted.useToolInvocationLogsQueryMock.mockReset();
        hoisted.useToolInvocationLogsQueryMock.mockReturnValue(emptyQueryResult());
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReset();
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReturnValue(emptyQueryResult());
    });

    it('reads the appId search parameter into the initial filter', () => {
        const {result} = renderUseToolInvocations('/automation/executions/tool-invocations?appId=7');

        expect(result.current.filterAppId).toBe('7');
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({appId: 7, page: 0}),
            {enabled: true}
        );
    });

    it('ignores a malformed appId search parameter', () => {
        const {result} = renderUseToolInvocations('/automation/executions/tool-invocations?appId=abc');

        expect(result.current.filterAppId).toBeUndefined();
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({appId: undefined}),
            {enabled: true}
        );
    });

    it('does not filter by app without the search parameter', () => {
        const {result} = renderUseToolInvocations('/automation/executions/tool-invocations');

        expect(result.current.filterAppId).toBeUndefined();
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({appId: undefined}),
            {enabled: true}
        );
    });

    it('changes the app filter and resets the page', () => {
        const {result} = renderUseToolInvocations('/automation/executions/tool-invocations');

        act(() => result.current.handlePaginationClick(3));

        expect(result.current.filterPageNumber).toBe(3);

        act(() => result.current.handleAppChange({label: 'CRM', value: '9'}));

        expect(result.current.filterAppId).toBe('9');
        expect(result.current.filterPageNumber).toBe(0);

        act(() => result.current.handleAppChange({label: 'Any', value: ''}));

        expect(result.current.filterAppId).toBeUndefined();
    });

    it('queries the tenant-wide log and leaves the workspace query off without a workspace scope', () => {
        renderUseToolInvocations('/embedded/executions/tool-invocations');

        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({environmentId: undefined}),
            {enabled: true}
        );
        expect(hoisted.useWorkspaceToolInvocationLogsQueryMock).toHaveBeenCalledWith(expect.anything(), {
            enabled: false,
        });
    });

    it('narrows the tenant-wide log to the given environment', () => {
        renderUseToolInvocations('/embedded/executions/tool-invocations', undefined, 2);

        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({environmentId: '2', page: 0}),
            {enabled: true}
        );
        expect(hoisted.useWorkspaceToolInvocationLogsQueryMock).toHaveBeenCalledWith(expect.anything(), {
            enabled: false,
        });
    });

    it('sends environment 0 (development) as a filter instead of dropping it', () => {
        renderUseToolInvocations('/embedded/executions/tool-invocations', undefined, 0);

        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({environmentId: '0', page: 0}),
            {enabled: true}
        );
    });

    it('queries the workspace log with the workspace and environment of the scope', () => {
        hoisted.useWorkspaceToolInvocationLogsQueryMock.mockReturnValue({
            ...emptyQueryResult(),
            data: {
                workspaceToolInvocationLogs: {
                    content: [{id: '1', toolName: 'CRM__SEARCH_RECORDS'}],
                    number: 0,
                    size: 50,
                    totalElements: 1,
                    totalPages: 1,
                },
            },
        });

        const {result} = renderUseToolInvocations('/automation/executions/tool-invocations?appId=7', {
            environmentId: 2,
            workspaceId: 1049,
        });

        expect(hoisted.useWorkspaceToolInvocationLogsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({appId: 7, environmentId: '2', page: 0, workspaceId: '1049'}),
            {enabled: true}
        );
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(expect.anything(), {enabled: false});
        expect(result.current.toolInvocationLogs).toEqual([{id: '1', toolName: 'CRM__SEARCH_RECORDS'}]);
        expect(result.current.toolInvocationLogPage?.totalElements).toBe(1);
    });

    it('waits for the workspace instead of falling back to the tenant-wide log', () => {
        renderUseToolInvocations('/automation/executions/tool-invocations', {
            environmentId: 2,
            workspaceId: undefined,
        });

        expect(hoisted.useWorkspaceToolInvocationLogsQueryMock).toHaveBeenCalledWith(expect.anything(), {
            enabled: false,
        });
        expect(hoisted.useToolInvocationLogsQueryMock).toHaveBeenCalledWith(expect.anything(), {enabled: false});
    });
});

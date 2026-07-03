import {aiHubTabsStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {act, renderHook} from '@testing-library/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

/**
 * Regression coverage for the cross-chat artifact bleed that motivated gating recording on
 * `tabsActiveChatId === chatId`. The scenario:
 *
 *   1. Tabs store starts mirrored to chat A with an open file tab.
 *   2. The hook is re-rendered with `chatId = B` — simulating the moment of a chat switch where the
 *      consumer (AiHub.tsx) has updated `currentChatId` but the separate `setActiveChatId` mirror
 *      effect on the tabs store has NOT yet run, so `tabsStore.activeChatId` is still A.
 *   3. Without the gate the hook would fire `recordReferencedAiHubChatArtifact` with `chatId = B` for
 *      every open tab, registering A's content under B in the persistent artifact log.
 *
 * The fix gates the recording loop on the tabs store's `activeChatId` matching the prop; this test
 * pins that gate against a recurrence.
 */

const {fetcherSpy, useRecordReferencedAiHubChatArtifactMutation: useGeneratedRecordMutation} = vi.hoisted(() => ({
    fetcherSpy: vi.fn(),
    useRecordReferencedAiHubChatArtifactMutation: vi.fn(),
}));

// recordTabLessReferences bypasses react-query on purpose (it runs from the send path, not a hook), so it
// is exercised through the raw fetcher.
vi.mock('@/shared/middleware/graphqlFetcher', () => ({
    fetcher:
        (...fetcherArgs: unknown[]) =>
        () =>
            fetcherSpy(...fetcherArgs),
}));

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useRecordReferencedAiHubChatArtifactMutation: useGeneratedRecordMutation,
    };
});

const recordedModule = await import('../useRecordReferencedArtifacts');
const useRecordReferencedArtifacts = recordedModule.default;
const {recordTabLessReferences} = recordedModule;

const mutateSpy = vi.fn();

beforeEach(() => {
    mutateSpy.mockReset();
    fetcherSpy.mockReset();
    fetcherSpy.mockResolvedValue({});
    useGeneratedRecordMutation.mockReset();

    // The mock returns the same surface for every test. Tests below assert on `mutate` invocations
    // rather than firing onSuccess, so the options arg passes through unread.
    useGeneratedRecordMutation.mockImplementation(() => {
        return {
            isError: false,
            isIdle: true,
            isPending: false,
            isSuccess: false,
            mutate: mutateSpy,
            mutateAsync: vi.fn(),
            reset: vi.fn(),
            status: 'idle',
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any;
    });

    aiHubTabsStore.setState({
        activeChatId: undefined,
        activeTabId: undefined,
        openTabs: [],
        rightPanelOpen: false,
        snapshotsByChatId: {},
    });
});

const wrap = (queryClient: QueryClient) => {
    const Wrapper = ({children}: {children: ReactNode}) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    return Wrapper;
};

const fileTab = {
    fileId: 'asset-7',
    id: 'tab-1',
    kind: 'file' as const,
    name: 'notes.md',
    viewMode: 'editor' as const,
};

const codeWorkflowTab = {
    id: 'codeWorkflow-proj-1',
    kind: 'codeWorkflow' as const,
    language: 'java',
    name: 'My Code Workflow',
    projectId: 'proj-1',
};

const aiAgentTab = {
    aiAgentId: 'agent-1',
    id: 'tab-agent-1',
    kind: 'aiAgent' as const,
    name: 'Support Agent',
};

describe('useRecordReferencedArtifacts', () => {
    it('records the open tabs when the tabs store is mirrored to the prop chatId', () => {
        // Baseline: tabs store has activeChatId = 10 and openTabs = [fileTab]. The hook is invoked with
        // chatId = 10. Both halves of the (chatId, tabsActiveChatId) gate match, so recording runs.
        aiHubTabsStore.setState({activeChatId: 10, openTabs: [fileTab]});

        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        renderHook(() => useRecordReferencedArtifacts(10, 1), {wrapper: wrap(queryClient)});

        expect(mutateSpy).toHaveBeenCalledTimes(1);
        expect(mutateSpy).toHaveBeenCalledWith({
            input: expect.objectContaining({
                artifactId: 'asset-7',
                artifactName: 'notes.md',
                chatId: '10',
                workspaceId: '1',
            }),
        });
    });

    /*
     * workflowExecution is a tab kind whose WORKFLOW_EXECUTION_REFERENCED enum value has existed on the
     * server and in the schema for a long time, but it was never added to the client's kind map — so a
     * referenced execution opened its tab and was silently never recorded.
     */
    it('records a workflowExecution tab, stringifying its numeric id', () => {
        aiHubTabsStore.setState({
            activeChatId: 10,
            openTabs: [{id: 'tab-exec-1', kind: 'workflowExecution' as const, name: 'run 42', workflowExecutionId: 42}],
        });

        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        renderHook(() => useRecordReferencedArtifacts(10, 1), {wrapper: wrap(queryClient)});

        expect(mutateSpy).toHaveBeenCalledTimes(1);
        expect(mutateSpy).toHaveBeenCalledWith({
            input: expect.objectContaining({
                artifactId: '42',
                artifactName: 'run 42',
                kind: 'WORKFLOW_EXECUTION_REFERENCED',
            }),
        });
    });

    it('records a codeWorkflow tab using projectId as the artifact id', () => {
        aiHubTabsStore.setState({activeChatId: 10, openTabs: [codeWorkflowTab]});

        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        renderHook(() => useRecordReferencedArtifacts(10, 1), {wrapper: wrap(queryClient)});

        expect(mutateSpy).toHaveBeenCalledTimes(1);
        expect(mutateSpy).toHaveBeenCalledWith({
            input: expect.objectContaining({
                artifactId: 'proj-1',
                artifactName: 'My Code Workflow',
                chatId: '10',
                workspaceId: '1',
            }),
        });
    });

    it('records an aiAgent tab using aiAgentId as the artifact id', () => {
        aiHubTabsStore.setState({activeChatId: 10, openTabs: [aiAgentTab]});

        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        renderHook(() => useRecordReferencedArtifacts(10, 1), {wrapper: wrap(queryClient)});

        expect(mutateSpy).toHaveBeenCalledTimes(1);
        expect(mutateSpy).toHaveBeenCalledWith({
            input: expect.objectContaining({
                artifactId: 'agent-1',
                artifactName: 'Support Agent',
                chatId: '10',
                kind: 'AI_AGENT_REFERENCED',
                workspaceId: '1',
            }),
        });
    });

    it('does NOT record when the tabs store is still mirrored to a different chat', () => {
        // The cross-chat-bleed scenario. Tabs store still says activeChatId=10 with file7 in tabs; the
        // consumer has updated to chat 20 but the mirror effect hasn't run yet. Recording must skip,
        // otherwise file7 ends up under chat 20 in the artifact log even though the user attached it to 10.
        aiHubTabsStore.setState({activeChatId: 10, openTabs: [fileTab]});

        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        renderHook(() => useRecordReferencedArtifacts(20, 1), {wrapper: wrap(queryClient)});

        expect(mutateSpy).not.toHaveBeenCalled();
    });

    it('records once the tabs store mirror catches up to the new chat', () => {
        // Continuation of the previous scenario: after the mirror effect finally runs, activeChatId
        // flips to match the new prop chatId. The hook re-fires (tabsActiveChatId is in the dep array)
        // and now records for the correct chat.
        aiHubTabsStore.setState({activeChatId: 10, openTabs: [fileTab]});

        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        renderHook(() => useRecordReferencedArtifacts(20, 1), {wrapper: wrap(queryClient)});

        expect(mutateSpy).not.toHaveBeenCalled();

        // Simulate the mirror effect running: tabs store catches up to chat 20 and the openTabs reset
        // to whatever 20's snapshot had — empty in this test.
        act(() => {
            aiHubTabsStore.setState({activeChatId: 20, openTabs: []});
        });

        // No tabs to record now, but the gate would allow the loop to run if there were.
        expect(mutateSpy).not.toHaveBeenCalled();

        // Now chat 20 legitimately opens a tab — recording should fire under 20.
        act(() => {
            aiHubTabsStore.setState({
                activeChatId: 20,
                openTabs: [{...fileTab, fileId: 'asset-9', name: 'plan.md'}],
            });
        });

        expect(mutateSpy).toHaveBeenCalledTimes(1);
        expect(mutateSpy).toHaveBeenCalledWith({
            input: expect.objectContaining({
                artifactId: 'asset-9',
                chatId: '20',
            }),
        });
    });
});

/*
 * The three tab-less kinds could never be recorded by the tab-watching effect above — attaching one adds a
 * chip and opens nothing — so they are recorded from the send path instead, while the chips still exist.
 * The server enum and GraphQL schema have carried all three for a long time; only the client half was
 * missing, which is why this looked like a server gap.
 */
describe('recordTabLessReferences', () => {
    it('records the attachments that have no viewer tab', async () => {
        await recordTabLessReferences({
            chatId: 7,
            references: [
                {id: 'api-1', kind: 'apiCollection', name: 'Billing API'},
                {id: 'mcp-2', kind: 'mcpServer', name: 'Local MCP'},
                {id: 'chat-3', kind: 'chat', name: 'Earlier chat'},
            ],
            workspaceId: 1,
        });

        expect(fetcherSpy).toHaveBeenCalledTimes(3);

        const recordedKinds = fetcherSpy.mock.calls.map(([, variables]) => variables.input.kind);

        expect(recordedKinds).toEqual(['API_COLLECTION_REFERENCED', 'MCP_SERVER_REFERENCED', 'CHAT_REFERENCED']);
    });

    // Everything else already reaches the artifact log through its tab, so recording it here too would
    // duplicate the write on every single turn.
    it('leaves the kinds that open a tab to the tab-watching effect', async () => {
        await recordTabLessReferences({
            chatId: 7,
            references: [
                {id: 'file-1', kind: 'file', name: 'notes.md'},
                {id: '42', kind: 'workflowExecution', name: 'run 42'},
            ],
            workspaceId: 1,
        });

        expect(fetcherSpy).not.toHaveBeenCalled();
    });

    /*
     * Bookkeeping must never break sending. The user has already hit Enter by the time this runs, and the
     * turn itself is unaffected by whether the artifact row lands.
     */
    it('swallows a failed record rather than rejecting into the send path', async () => {
        fetcherSpy.mockRejectedValue(new Error('boom'));

        await expect(
            recordTabLessReferences({
                chatId: 7,
                references: [{id: 'api-1', kind: 'apiCollection', name: 'Billing API'}],
                workspaceId: 1,
            })
        ).resolves.toBeUndefined();
    });
});

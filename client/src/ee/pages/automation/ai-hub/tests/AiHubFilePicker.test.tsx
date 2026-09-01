import AiHubFilePicker from '@/ee/pages/automation/ai-hub/AiHubFilePicker';
import {aiHubChatsStore} from '@/ee/pages/automation/ai-hub/chats/stores/useAiHubChatsStore';
import {aiHubComposerStore} from '@/ee/pages/automation/ai-hub/composer/stores/useAiHubComposerStore';
import {aiHubTabsStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {fireEvent, render, screen} from '@testing-library/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {mockUseAiHubChatArtifactsQuery, mockUseGetAssetFilesQuery} = vi.hoisted(() => ({
    mockUseAiHubChatArtifactsQuery: vi.fn(),
    mockUseGetAssetFilesQuery: vi.fn(),
}));

vi.mock('@/ee/pages/automation/ai-hub/chats/hooks/useChats', () => ({
    useAiHubChatArtifactsQuery: (...args: unknown[]) => mockUseAiHubChatArtifactsQuery(...args),
}));

// The picker's other branches fan out into project/workflow/file/data-table/knowledge-base queries that
// are irrelevant to the artifacts branch; stub them flat so the test exercises one branch only.
vi.mock('@/shared/middleware/graphql', () => ({
    useDataTablesQuery: () => ({data: undefined}),
    useGetAssetFilesQuery: (...args: unknown[]) => mockUseGetAssetFilesQuery(...args),
    useKnowledgeBasesQuery: () => ({data: undefined}),
    useWorkspaceProjectWorkflowsQuery: () => ({data: undefined}),
}));

vi.mock('@/shared/queries/automation/workflowExecutions.queries', () => ({
    useInfiniteWorkspaceProjectWorkflowExecutionsQuery: () => ({
        data: undefined,
        fetchNextPage: vi.fn(),
        hasNextPage: false,
        isFetchingNextPage: false,
    }),
}));

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: (selector: (state: {currentWorkspaceId: number}) => unknown) =>
        selector({currentWorkspaceId: 1}),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: (selector: (state: {currentEnvironmentId: number}) => unknown) =>
        selector({currentEnvironmentId: 1}),
}));

const wrap = (ui: ReactNode) => {
    const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

    return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
};

describe('AiHubFilePicker artifacts branch', () => {
    beforeEach(() => {
        aiHubChatsStore.setState({currentChatId: 7});

        aiHubTabsStore.setState({
            activeChatId: undefined,
            activeTabId: undefined,
            attachedTabIds: [],
            chatsSidebarCollapsed: true,
            openTabs: [],
            rightPanelOpen: false,
            snapshotsByChatId: {},
        });

        aiHubComposerStore.setState({referencedResources: []});

        mockUseGetAssetFilesQuery.mockReturnValue({data: undefined});

        mockUseAiHubChatArtifactsQuery.mockReturnValue({
            data: [
                {
                    artifactId: 'file-1',
                    artifactName: 'report.csv',
                    chatId: 7,
                    createdAt: new Date().toISOString(),
                    id: 1,
                    kind: 'FILE_CREATED',
                    metadataJson: null,
                    status: 'APPLIED',
                },
            ],
        });
    });

    it('drills into Artifacts and opens the picked artifact as a tab', () => {
        wrap(<AiHubFilePicker />);

        fireEvent.click(screen.getByRole('button', {name: 'Add resource'}));
        fireEvent.click(screen.getByText('Artifacts'));
        fireEvent.click(screen.getByText('report.csv'));

        const state = aiHubTabsStore.getState();

        expect(state.openTabs).toHaveLength(1);
        expect(state.openTabs[0]!.kind).toBe('file');
    });

    it('shows an empty state when the chat has no artifacts', () => {
        mockUseAiHubChatArtifactsQuery.mockReturnValue({data: []});

        wrap(<AiHubFilePicker />);

        fireEvent.click(screen.getByRole('button', {name: 'Add resource'}));
        fireEvent.click(screen.getByText('Artifacts'));

        expect(screen.getByText('No artifacts yet.')).toBeInTheDocument();
    });
});

/*
 * Picking here ATTACHES, exactly as the composer's "+" menu does. It used to only open a tab, which left
 * the resource un-detachable (no chip to remove) and — because the home -> chat hand-off adopts attached
 * tabs into the chat the first prompt creates — filed anything merely browsed on the home view as an
 * artifact of a chat that never referenced it.
 */
describe('AiHubFilePicker attaching', () => {
    beforeEach(() => {
        aiHubChatsStore.setState({currentChatId: undefined});

        aiHubTabsStore.setState({
            activeChatId: undefined,
            activeTabId: undefined,
            attachedTabIds: [],
            openTabs: [],
            rightPanelOpen: false,
            snapshotsByChatId: {},
        });

        aiHubComposerStore.setState({referencedResources: []});

        mockUseAiHubChatArtifactsQuery.mockReturnValue({data: []});
        mockUseGetAssetFilesQuery.mockReturnValue({
            data: {assetFiles: [{id: 'file-9', name: 'notes.md'}]},
        });
    });

    it('adds a composer chip for the picked file, not just a tab', async () => {
        wrap(<AiHubFilePicker />);

        fireEvent.click(screen.getByRole('button', {name: 'Add resource'}));

        // The resource groups only render against a search term, and the term is debounced by 300ms.
        fireEvent.change(screen.getByPlaceholderText('Search resources…'), {target: {value: 'notes'}});

        const fileItem = await screen.findByText('notes.md');

        fireEvent.click(fileItem);

        const [reference] = aiHubComposerStore.getState().referencedResources;
        const {attachedTabIds, openTabs} = aiHubTabsStore.getState();

        expect(reference).toMatchObject({id: 'file-9', kind: 'file', name: 'notes.md', ownsTab: true});
        expect(openTabs).toHaveLength(1);
        // Marked as an attachment, so the home -> chat hand-off keeps it and the chip's X can close it.
        expect(attachedTabIds).toEqual([reference!.tabId]);
    });
});

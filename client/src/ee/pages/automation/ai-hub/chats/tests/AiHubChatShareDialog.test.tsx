import {AiHubChatI} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {fireEvent, render, screen, userEvent} from '@/shared/util/test-utils';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import AiHubChatShareDialog from '../AiHubChatShareDialog';

// grantsDataRef holds the QUERY RESULT OBJECT itself, not just the array inside it, and each test
// reassigns it at most once (in beforeEach or at the top of the "revoke" test) rather than on every
// render. This mirrors how react-query actually behaves — `data` keeps the same reference across
// renders until a real refetch replaces it. Rebuilding `{aiHubChatGrants: ...}` fresh on every mock
// call (the first version of this test did that) makes `useAiHubChatGrantsQuery(...).data` a new
// object on every render; AiHubChatShareDialog seeds local state from that value in a `useEffect`
// keyed on it, so a non-memoized mock turned that effect into an infinite render loop that hung the
// test run. See the `areSameIds` guard in AiHubChatShareDialog.tsx for the production-side hardening
// against the same class of instability.
const {grantAccessMutateMock, grantsDataRef, revokeAccessMutateMock, setVisibilityMutateMock} = vi.hoisted(() => ({
    grantAccessMutateMock: vi.fn(),
    grantsDataRef: {current: {aiHubChatGrants: [] as string[]}},
    revokeAccessMutateMock: vi.fn(),
    setVisibilityMutateMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useAiHubChatGrantsQuery: () => ({data: grantsDataRef.current}),
    useGrantAiHubChatAccessMutation: () => ({mutate: grantAccessMutateMock}),
    useRevokeAiHubChatAccessMutation: () => ({mutate: revokeAccessMutateMock}),
    useSetAiHubChatVisibilityMutation: () => ({mutate: setVisibilityMutateMock}),
    useWorkspaceUsersQuery: () => ({
        data: {
            workspaceUsers: [
                {user: {email: 'alice@example.com'}, userId: '1'},
                {user: {email: 'bob@example.com'}, userId: '2'},
            ],
        },
    }),
}));

let queryClient: QueryClient;

const Wrapper = ({children}: {children: ReactNode}) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

function buildChat(overrides: Partial<AiHubChatI> = {}): AiHubChatI {
    return {
        aiAgentId: null,
        autoTitled: true,
        createdAt: new Date().toISOString(),
        id: 5,
        isOwner: true,
        kind: 'STANDARD',
        lastPreview: null,
        messageCount: 0,
        ownerName: null,
        ownerUserId: 1,
        participation: 'VIEW',
        status: 'ACTIVE',
        threadId: 'thread-5',
        title: 'My chat',
        updatedAt: new Date().toISOString(),
        userId: 1,
        visibility: 'PRIVATE',
        workflowExecutionId: null,
        workspaceId: 7,
        ...overrides,
    };
}

beforeEach(() => {
    vi.clearAllMocks();

    grantsDataRef.current = {aiHubChatGrants: []};

    queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});
});

describe('AiHubChatShareDialog', () => {
    it('shows the picker set to Private for a chat with PRIVATE visibility and no grants', () => {
        render(
            <AiHubChatShareDialog
                chat={buildChat({visibility: 'PRIVATE'})}
                onClose={vi.fn()}
                open={true}
                workspaceId={7}
            />,
            {
                wrapper: Wrapper,
            }
        );

        expect(screen.getByRole('radio', {name: /private/i})).toBeChecked();
    });

    it('sends WORKSPACE visibility and the toggled participation on Save', async () => {
        const user = userEvent.setup();

        render(
            <AiHubChatShareDialog
                chat={buildChat({visibility: 'PRIVATE'})}
                onClose={vi.fn()}
                open={true}
                workspaceId={7}
            />,
            {
                wrapper: Wrapper,
            }
        );

        await user.click(screen.getByRole('radio', {name: /shared with workspace/i}));
        await user.click(screen.getByLabelText('People with access can send messages'));
        await user.click(screen.getByRole('button', {name: 'Save'}));

        expect(setVisibilityMutateMock).toHaveBeenCalledWith({
            chatId: '5',
            participation: 'PARTICIPATE',
            visibility: 'WORKSPACE',
            workspaceId: '7',
        });
    });

    it('grants access to a newly ticked member and sets PRIVATE visibility on Save', async () => {
        const user = userEvent.setup();

        render(
            <AiHubChatShareDialog
                chat={buildChat({visibility: 'PRIVATE'})}
                onClose={vi.fn()}
                open={true}
                workspaceId={7}
            />,
            {
                wrapper: Wrapper,
            }
        );

        await user.click(screen.getByRole('radio', {name: /specific people/i}));

        // The "Add person" combobox is a Radix Select; opening it and picking an option is only reliable
        // via fireEvent in this codebase's jsdom setup (see SelectGeneric.test.tsx), not userEvent.click.
        fireEvent.click(screen.getByLabelText(/add person/i));
        fireEvent.click(screen.getByText('alice@example.com'));

        await user.click(screen.getByRole('button', {name: 'Save'}));

        expect(grantAccessMutateMock).toHaveBeenCalledTimes(1);
        expect(grantAccessMutateMock).toHaveBeenCalledWith({chatId: '5', userId: '1', workspaceId: '7'});
        expect(setVisibilityMutateMock).toHaveBeenCalledWith({
            chatId: '5',
            participation: 'VIEW',
            visibility: 'PRIVATE',
            workspaceId: '7',
        });
    });

    it('revokes access from an unticked existing grant on Save', async () => {
        const user = userEvent.setup();

        grantsDataRef.current = {aiHubChatGrants: ['2']};

        render(
            <AiHubChatShareDialog
                chat={buildChat({visibility: 'PRIVATE'})}
                onClose={vi.fn()}
                open={true}
                workspaceId={7}
            />,
            {
                wrapper: Wrapper,
            }
        );

        expect(await screen.findByText('bob@example.com')).toBeInTheDocument();

        await user.click(screen.getByRole('button', {name: /remove bob@example.com/i}));
        await user.click(screen.getByRole('button', {name: 'Save'}));

        expect(revokeAccessMutateMock).toHaveBeenCalledWith({chatId: '5', userId: '2', workspaceId: '7'});
    });
});

import {AiHubChatI} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {fireEvent, render, screen, userEvent} from '@/shared/util/test-utils';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import AiHubChatShareDialog from '../AiHubChatShareDialog';

// Two deliberately different mock behaviours, because the difference between them is what the last
// test in this file measures:
//
//   - grantsDataRef holds the QUERY RESULT OBJECT itself, not just the array inside it, and each test
//     reassigns it at most once rather than on every render. This mirrors how react-query actually
//     behaves — `data` keeps the same reference across renders until a real refetch replaces it — so
//     the behavioural tests below exercise the dialog under production-shaped conditions.
//   - unstableGrantsRef makes the query hand back a FRESH object carrying the SAME ids on every read.
//     `grantsQuery.data` then changes identity on every render while its content never changes, which
//     is precisely the instability the `areSameIds` guard in AiHubChatShareDialog.tsx defends against.
//     A same-identity mock cannot exercise that guard at all: the effect fires once either way, so
//     such a test passes against the unguarded code and guards nothing.
const {
    grantAccessMutateMock,
    grantsCallCountRef,
    grantsDataRef,
    queriesPendingRef,
    revokeAccessMutateMock,
    setVisibilityMutateMock,
    unstableGrantsRef,
} = vi.hoisted(() => ({
    grantAccessMutateMock: vi.fn(),
    // How many times the mocked grants query has been read — i.e. how many times the dialog rendered.
    grantsCallCountRef: {current: 0},
    grantsDataRef: {current: {aiHubChatGrants: [] as string[]}},
    // Both mocked queries report this as their `isPending`, so a test can put the dialog in the
    // still-loading state the real hooks are in for the first frame after it opens.
    queriesPendingRef: {current: false},
    revokeAccessMutateMock: vi.fn(),
    setVisibilityMutateMock: vi.fn(),
    // When set, the grants query returns a FRESH object with these same ids on every read, so
    // `grantsQuery.data` changes identity on every render while its content never changes. That is the
    // instability the `areSameIds` guard exists for — see the render-loop test at the bottom of this file.
    unstableGrantsRef: {current: null as string[] | null},
}));

// A hard ceiling so an unguarded regression fails loudly instead of hanging the whole run: without the
// guard the effect re-runs on every render and re-renders on every run, so the render count is unbounded.
const MAX_RENDERS_BEFORE_LOOP_DECLARED = 30;

vi.mock('@/shared/middleware/graphql', () => ({
    useAiHubChatGrantsQuery: () => {
        grantsCallCountRef.current += 1;

        if (grantsCallCountRef.current > MAX_RENDERS_BEFORE_LOOP_DECLARED) {
            throw new Error(
                `Render loop: AiHubChatShareDialog rendered more than ${MAX_RENDERS_BEFORE_LOOP_DECLARED} times for one unchanged grant list`
            );
        }

        if (unstableGrantsRef.current) {
            return {data: {aiHubChatGrants: [...unstableGrantsRef.current]}, isPending: queriesPendingRef.current};
        }

        return {data: grantsDataRef.current, isPending: queriesPendingRef.current};
    },
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
        isPending: queriesPendingRef.current,
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

    grantsCallCountRef.current = 0;
    grantsDataRef.current = {aiHubChatGrants: []};
    queriesPendingRef.current = false;
    unstableGrantsRef.current = null;

    queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});
});

describe('AiHubChatShareDialog', () => {
    it('shows a loading row instead of the picker, and withholds Save, while the audience queries are in flight', () => {
        queriesPendingRef.current = true;

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

        expect(screen.getByTestId('share-dialog-loading')).toBeInTheDocument();
        expect(screen.queryByRole('radio', {name: /private/i})).not.toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled();
    });

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

describe('AiHubChatShareDialog grant-seeding under an unstable query result', () => {
    it('seeds once and stops when the grants query returns a new object with identical ids on every read', async () => {
        unstableGrantsRef.current = ['2'];

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

        // The seeded grant reaching the screen proves the effect ran and the ids landed in state.
        expect(await screen.findByText('bob@example.com')).toBeInTheDocument();

        // And then stopped. Without the `areSameIds` guard, the effect re-runs on every render (its
        // `grantsQuery.data` dep is a new object each time) and `.map(Number)` hands setState a brand-new
        // array on every run, so state "changes" on every render and the renders never stop — the mock's
        // own ceiling turns that into a thrown error rather than a hung run, and it is thrown from inside
        // React's effect flush, so `render` above never returns. With the guard, the functional updater
        // returns the previous array once the ids match, React bails out of the re-render, and the count
        // settles in single digits.
        expect(grantsCallCountRef.current).toBeLessThan(MAX_RENDERS_BEFORE_LOOP_DECLARED);
    });
});

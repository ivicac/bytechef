import {AiHubMessageComponents} from '@/ee/pages/automation/ai-hub/messages/AiHubMessage';
import {render, screen} from '@testing-library/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

interface FakeMessageStateI {
    content: unknown[];
    metadata?: {custom?: {authorName?: string; authorUserId?: number}};
}

interface FakeStoreMessageI {
    metadata?: {custom?: {authorUserId?: number}};
}

// useAuiState is called with a fresh selector on every render (once for `message.content`, once for
// `message.metadata?.custom?.authorName`); rather than a blanket mockReturnValue (which can't tell the two
// calls apart), the mock applies whatever selector the component passes against this per-test fake message
// state, exactly as the real hook applies it against the real assistant-ui store.
const {aiHubStoreMessagesRef, messageStateRef, sharingEnabledRef} = vi.hoisted(() => ({
    aiHubStoreMessagesRef: {current: [] as FakeStoreMessageI[]},
    messageStateRef: {current: {content: []} as FakeMessageStateI},
    sharingEnabledRef: {current: true},
}));

vi.mock('@/ee/pages/automation/ai-hub/chats/hooks/useAiHubSharingEnabled', () => ({
    useAiHubSharingEnabled: () => sharingEnabledRef.current,
}));

vi.mock('@/ee/pages/automation/ai-hub/messages/AiHubMessageContent', () => ({
    default: () => <div data-testid="message-content" />,
}));

vi.mock('@/ee/pages/automation/ai-hub/stores/useAiHubStore', () => ({
    useAiHubStore: (selector: (state: {messages: FakeStoreMessageI[]}) => unknown) =>
        selector({messages: aiHubStoreMessagesRef.current}),
}));

vi.mock('@assistant-ui/react', () => ({
    ActionBarPrimitive: {
        Edit: ({children}: {children: ReactNode}) => <>{children}</>,
        Root: ({children}: {children: ReactNode}) => <div>{children}</div>,
    },
    MessagePrimitive: {
        Root: ({children}: {children: ReactNode}) => <>{children}</>,
    },
    useAuiState: (selector: (state: {message: FakeMessageStateI}) => unknown) =>
        selector({message: messageStateRef.current}),
}));

const {UserMessage} = AiHubMessageComponents;

beforeEach(() => {
    aiHubStoreMessagesRef.current = [];
    messageStateRef.current = {content: []};
    sharingEnabledRef.current = true;
});

describe('AiHubUserMessage', () => {
    it('renders the synthetic tool-approval message as a status line, not a bubble', () => {
        messageStateRef.current = {content: [{text: '[tool-approval #1234 approved and executed]', type: 'text'}]};

        render(<UserMessage />);

        expect(screen.getByTestId('tool-approval-status-line')).toHaveTextContent(
            'tool-approval #1234 approved and executed'
        );
        expect(screen.queryByTestId('message-content')).not.toBeInTheDocument();
        expect(screen.queryByText('[tool-approval')).not.toBeInTheDocument();
    });

    it('renders the normal bubble for an ordinary user message', () => {
        messageStateRef.current = {content: [{text: 'hello there', type: 'text'}]};

        render(<UserMessage />);

        expect(screen.getByTestId('message-content')).toBeInTheDocument();
        expect(screen.queryByTestId('tool-approval-status-line')).not.toBeInTheDocument();
    });

    describe('author label', () => {
        it('omits the author label for a solo chat, even when the message carries an authorName', () => {
            messageStateRef.current = {
                content: [{text: 'hello there', type: 'text'}],
                metadata: {custom: {authorName: 'Ana', authorUserId: 2}},
            };
            aiHubStoreMessagesRef.current = [{metadata: {custom: {authorUserId: 2}}}];

            render(<UserMessage />);

            expect(screen.queryByTestId('message-author')).not.toBeInTheDocument();
        });

        it('renders the author label once the thread has messages from more than one distinct author', () => {
            messageStateRef.current = {
                content: [{text: 'hello there', type: 'text'}],
                metadata: {custom: {authorName: 'Ana', authorUserId: 2}},
            };
            aiHubStoreMessagesRef.current = [
                {metadata: {custom: {authorUserId: 2}}},
                {metadata: {custom: {authorUserId: 3}}},
            ];

            render(<UserMessage />);

            expect(screen.getByTestId('message-author')).toHaveTextContent('Ana');
        });

        it('omits the label when the thread has multiple authors but this particular message carries no authorName', () => {
            // A message the current turn just sent locally has no server round-trip yet, so the provider
            // hasn't stamped metadata onto it — the label must not render "undefined" in that gap.
            messageStateRef.current = {content: [{text: 'hello there', type: 'text'}]};
            aiHubStoreMessagesRef.current = [
                {metadata: {custom: {authorUserId: 2}}},
                {metadata: {custom: {authorUserId: 3}}},
            ];

            render(<UserMessage />);

            expect(screen.queryByTestId('message-author')).not.toBeInTheDocument();
        });

        it('omits the label when every message in the thread shares the same authorUserId', () => {
            messageStateRef.current = {
                content: [{text: 'hello there', type: 'text'}],
                metadata: {custom: {authorName: 'Ana', authorUserId: 2}},
            };
            aiHubStoreMessagesRef.current = [
                {metadata: {custom: {authorUserId: 2}}},
                {metadata: {custom: {authorUserId: 2}}},
            ];

            render(<UserMessage />);

            expect(screen.queryByTestId('message-author')).not.toBeInTheDocument();
        });

        it('omits the label when useAiHubSharingEnabled returns false, even with more than one distinct author', () => {
            sharingEnabledRef.current = false;
            messageStateRef.current = {
                content: [{text: 'hello there', type: 'text'}],
                metadata: {custom: {authorName: 'Ana', authorUserId: 2}},
            };
            aiHubStoreMessagesRef.current = [
                {metadata: {custom: {authorUserId: 2}}},
                {metadata: {custom: {authorUserId: 3}}},
            ];

            render(<UserMessage />);

            expect(screen.queryByTestId('message-author')).not.toBeInTheDocument();
        });
    });
});

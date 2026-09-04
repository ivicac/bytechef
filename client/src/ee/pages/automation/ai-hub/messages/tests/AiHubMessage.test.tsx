import {AiHubMessageComponents} from '@/ee/pages/automation/ai-hub/messages/AiHubMessage';
import {render, screen} from '@testing-library/react';
import {ReactNode} from 'react';
import {describe, expect, it, vi} from 'vitest';

const {useAuiStateMock} = vi.hoisted(() => ({
    useAuiStateMock: vi.fn(),
}));

vi.mock('@/ee/pages/automation/ai-hub/messages/AiHubMessageContent', () => ({
    default: () => <div data-testid="message-content" />,
}));

vi.mock('@assistant-ui/react', () => ({
    ActionBarPrimitive: {
        Edit: ({children}: {children: ReactNode}) => <>{children}</>,
        Root: ({children}: {children: ReactNode}) => <div>{children}</div>,
    },
    MessagePrimitive: {
        Root: ({children}: {children: ReactNode}) => <>{children}</>,
    },
    useAuiState: (selector: (state: unknown) => unknown) => useAuiStateMock(selector),
}));

const {UserMessage} = AiHubMessageComponents;

describe('AiHubUserMessage', () => {
    it('renders the synthetic tool-approval message as a status line, not a bubble', () => {
        useAuiStateMock.mockReturnValue([{text: '[tool-approval #1234 approved and executed]', type: 'text'}]);

        render(<UserMessage />);

        expect(screen.getByTestId('tool-approval-status-line')).toHaveTextContent(
            'tool-approval #1234 approved and executed'
        );
        expect(screen.queryByTestId('message-content')).not.toBeInTheDocument();
        expect(screen.queryByText('[tool-approval')).not.toBeInTheDocument();
    });

    it('renders the normal bubble for an ordinary user message', () => {
        useAuiStateMock.mockReturnValue([{text: 'hello there', type: 'text'}]);

        render(<UserMessage />);

        expect(screen.getByTestId('message-content')).toBeInTheDocument();
        expect(screen.queryByTestId('tool-approval-status-line')).not.toBeInTheDocument();
    });
});

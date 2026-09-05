import {render, screen} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {describe, expect, it, vi} from 'vitest';

import CallAiAgentDetailDialog from './CallAiAgentDetailDialog';

const {invalidateAgentQueries, invalidateQueries} = vi.hoisted(() => ({
    invalidateAgentQueries: vi.fn(),
    invalidateQueries: vi.fn(),
}));

vi.mock('@tanstack/react-query', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@tanstack/react-query')>()),
    useQueryClient: () => ({invalidateQueries}),
}));

vi.mock('@/pages/automation/agents/utils/invalidateAgentQueries', () => ({
    default: invalidateAgentQueries,
}));

vi.mock('@/pages/automation/agents/AgentDetailContent', () => ({
    default: ({agentId}: {agentId: string}) => <div data-testid="agent-detail-content">{agentId}</div>,
}));

const renderDialog = (onOpenChange = vi.fn()) =>
    render(
        <MemoryRouter>
            <CallAiAgentDetailDialog agentId="22" agentTitle="Billing Agent" onOpenChange={onOpenChange} open />
        </MemoryRouter>
    );

describe('CallAiAgentDetailDialog', () => {
    it('should mount the editable agent builder on the given agent id', () => {
        renderDialog();

        expect(screen.getByTestId('agent-detail-content')).toHaveTextContent('22');
        expect(screen.getByText('Billing Agent')).toBeInTheDocument();
    });

    it('should link to the routed agent page', () => {
        renderDialog();

        expect(screen.getByRole('link', {name: /open in full view/i})).toHaveAttribute('href', '/automation/agents/22');
    });

    it('should invalidate both the node options and the agent queries on close', async () => {
        invalidateQueries.mockClear();
        invalidateAgentQueries.mockClear();

        const onOpenChange = vi.fn();

        renderDialog(onOpenChange);

        // The picker's label comes from the node-options cache while the link's uuid-to-id lookup comes from
        // the agent queries; refreshing only one leaves them disagreeing after a rename.
        await userEventEscape();

        expect(invalidateQueries).toHaveBeenCalledWith({queryKey: ['workflowNodeOptions']});
        expect(invalidateAgentQueries).toHaveBeenCalled();
        expect(onOpenChange).toHaveBeenCalledWith(false);
    });
});

async function userEventEscape() {
    const {default: userEvent} = await import('@testing-library/user-event');

    await userEvent.keyboard('{Escape}');
}

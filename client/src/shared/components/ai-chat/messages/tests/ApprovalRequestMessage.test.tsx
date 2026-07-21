import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

vi.mock('@/shared/components/approval-form/ApprovalForm', () => ({
    default: ({id}: {id: string | undefined}) => <div data-testid="approval-form">{id}</div>,
}));

import ApprovalRequestMessage from '../ApprovalRequestMessage';

describe('ApprovalRequestMessage', () => {
    it('renders the card frame and embeds the approval form keyed by the resume id', () => {
        render(
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            <ApprovalRequestMessage {...({data: {kind: 'approval-request', resumeId: 'abc123'}} as any)} />
        );

        expect(screen.getByText('Approval required')).toBeInTheDocument();
        expect(screen.getByTestId('approval-form')).toHaveTextContent('abc123');
    });

    it('renders nothing without a resume id', () => {
        const {container} = render(
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            <ApprovalRequestMessage {...({data: {kind: 'approval-request', resumeId: ''}} as any)} />
        );

        expect(container).toBeEmptyDOMElement();
    });
});

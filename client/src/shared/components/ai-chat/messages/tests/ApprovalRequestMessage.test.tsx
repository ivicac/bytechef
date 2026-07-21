import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

vi.mock('@/shared/components/approval-form/ApprovalForm', () => ({
    default: ({id}: {id: string | undefined}) => <div data-testid="approval-form">{id}</div>,
}));

vi.mock('@/shared/mutations/platform/resumeJobs.mutations', () => ({
    useResumeJobMutation: () => ({mutateAsync: vi.fn()}),
}));

import ApprovalRequestMessage from '../ApprovalRequestMessage';

describe('ApprovalRequestMessage', () => {
    it('renders a self-contained approve/discard card for field-less approvals', () => {
        render(
            <ApprovalRequestMessage
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                {...({data: {formTitle: 'Approve tool call', kind: 'approval-request', resumeId: 'abc123'}} as any)}
            />
        );

        // Field-less approvals must not depend on the approval-form endpoint — the card renders its own
        // buttons from the event data (this is what makes it work for editor test runs too).
        expect(screen.getByText('Approval required')).toBeInTheDocument();
        expect(screen.getByText('Approve tool call')).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Approve'})).toBeEnabled();
        expect(screen.getByRole('button', {name: 'Discard'})).toBeEnabled();
        expect(screen.queryByTestId('approval-form')).not.toBeInTheDocument();
    });

    it('embeds the approval form when the approval carries form fields', () => {
        render(
            <ApprovalRequestMessage
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                {...({data: {hasInputs: true, kind: 'approval-request', resumeId: 'abc123'}} as any)}
            />
        );

        expect(screen.getByTestId('approval-form')).toHaveTextContent('abc123');
        expect(screen.queryByRole('button', {name: 'Approve'})).not.toBeInTheDocument();
    });

    it('renders nothing without a resume id', () => {
        const {container} = render(
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            <ApprovalRequestMessage {...({data: {kind: 'approval-request', resumeId: ''}} as any)} />
        );

        expect(container).toBeEmptyDOMElement();
    });
});

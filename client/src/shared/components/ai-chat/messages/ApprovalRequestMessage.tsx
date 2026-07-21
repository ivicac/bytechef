import ApprovalForm from '@/shared/components/approval-form/ApprovalForm';
import {DataMessagePartProps} from '@assistant-ui/react';
import {ShieldCheckIcon} from 'lucide-react';

export interface ApprovalRequestDataI {
    formDescription?: string;
    formTitle?: string;
    formUrl?: string;
    kind: 'approval-request';
    resumeId: string;
}

/**
 * Renders an approval request raised by a running workflow as an inline card in the chat conversation. The card
 * embeds the standard {@link ApprovalForm}, which fetches the form definition by the tokenized resume id and owns
 * the whole resolution lifecycle: field rendering, the optional comment box, Approve/Discard submission through the
 * job-resume endpoint, and the submitted / no-longer-available terminal states.
 *
 * The chat input stays the conversation — typing never resolves the approval in either direction; only the card's
 * own buttons (or the hosted form behind {@code formUrl}) resolve it. Because the form definition is fetched live,
 * a card re-rendered after the approval was resolved elsewhere degrades to the form's "no longer available" state
 * instead of offering a stale decision.
 */
const ApprovalRequestMessage = ({data}: DataMessagePartProps<ApprovalRequestDataI>) => {
    if (!data.resumeId) {
        return null;
    }

    return (
        <div className="mt-2 flex flex-col gap-3 rounded-md border border-border bg-muted/30 p-3">
            <div className="flex items-center gap-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
                <ShieldCheckIcon className="size-3.5" />
                Approval required
            </div>

            <ApprovalForm id={data.resumeId} showHeader />
        </div>
    );
};

export default ApprovalRequestMessage;

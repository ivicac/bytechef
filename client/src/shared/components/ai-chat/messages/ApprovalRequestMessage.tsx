import Button from '@/components/Button/Button';
import {Label} from '@/components/ui/label';
import {Textarea} from '@/components/ui/textarea';
import {useApprovalResolution} from '@/shared/components/ai-chat/approvalResolutionContext';
import ApprovalForm from '@/shared/components/approval-form/ApprovalForm';
import {useResumeJobMutation} from '@/shared/mutations/platform/resumeJobs.mutations';
import {DataMessagePartProps} from '@assistant-ui/react';
import {ShieldCheckIcon} from 'lucide-react';
import {useMemo, useState} from 'react';

export interface ApprovalRequestDataI {
    formDescription?: string;
    formTitle?: string;
    formUrl?: string;
    hasInputs?: boolean;
    kind: 'approval-request';
    resumeId: string;
}

/**
 * Renders an approval request raised by a running workflow as an inline card in the chat conversation.
 *
 * Field-less approvals (the common tool-gate and plain approve/reject case) render self-contained from the event
 * data — title, description, optional comment box, Approve/Discard — with no dependency on the approval-form
 * endpoint, so the card also works for editor test runs whose suspend state is not served by that endpoint.
 * Approvals with form fields embed the standard {@link ApprovalForm}, which fetches the field definitions by the
 * tokenized resume id and owns the full form lifecycle.
 *
 * Resolution goes through the surface's {@code ApprovalResolutionContext} when provided — streaming the resumed
 * run's output back into the conversation — and falls back to the plain job-resume mutation otherwise. The chat
 * input stays the conversation: typing never resolves the approval in either direction.
 */
const ApprovalRequestMessage = ({data}: DataMessagePartProps<ApprovalRequestDataI>) => {
    const [comment, setComment] = useState('');
    const [resolved, setResolved] = useState<boolean | null>(null);
    const [submitError, setSubmitError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const approvalResolution = useApprovalResolution();

    const resumeJobMutation = useResumeJobMutation();

    const resumeId = data.resumeId;

    // Streaming transport for the fields variant (ApprovalForm owns the buttons there).
    const submitHandler = useMemo(() => {
        if (!approvalResolution || !resumeId) {
            return undefined;
        }

        return async (formData: Record<string, unknown>, approved: boolean) => {
            approvalResolution.resolveApproval(resumeId, {...formData, approved});
        };
    }, [approvalResolution, resumeId]);

    if (!resumeId) {
        return null;
    }

    const resolve = async (approved: boolean) => {
        setSubmitting(true);
        setSubmitError(null);

        const payload: Record<string, unknown> = {approved};
        const trimmedComment = comment.trim();

        if (trimmedComment) {
            payload.comment = trimmedComment;
        }

        try {
            if (approvalResolution) {
                approvalResolution.resolveApproval(resumeId, payload);
            } else {
                const {approved: approvedValue, ...restPayload} = payload;

                await resumeJobMutation.mutateAsync({
                    approved: approvedValue as boolean,
                    data: restPayload,
                    id: resumeId,
                });
            }

            setResolved(approved);
        } catch {
            setSubmitError('Failed to submit your decision. Please try again.');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="mt-2 flex flex-col gap-3 rounded-md border border-border bg-muted/30 p-3">
            <div className="flex items-center gap-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
                <ShieldCheckIcon className="size-3.5" />
                Approval required
            </div>

            {data.hasInputs ? (
                <ApprovalForm id={resumeId} showHeader submitHandler={submitHandler} />
            ) : resolved !== null ? (
                <div className="text-sm text-muted-foreground">
                    {resolved ? 'Approved — the workflow is continuing.' : 'Discarded.'}
                </div>
            ) : (
                <>
                    <div className="text-sm font-semibold">{data.formTitle || 'Approval requested'}</div>

                    {data.formDescription && (
                        <div className="text-sm whitespace-pre-line text-muted-foreground">{data.formDescription}</div>
                    )}

                    <div className="space-y-2">
                        <Label htmlFor={`approval-comment-${resumeId}`}>Comment (optional)</Label>

                        <Textarea
                            disabled={submitting}
                            id={`approval-comment-${resumeId}`}
                            onChange={(event) => setComment(event.target.value)}
                            placeholder="Add a note for the requester — included whether you approve or discard."
                            value={comment}
                        />
                    </div>

                    {submitError && <div className="text-sm text-destructive">{submitError}</div>}

                    <div className="flex gap-3">
                        <Button disabled={submitting} onClick={() => resolve(true)} size="sm" type="button">
                            {submitting ? 'Submitting…' : 'Approve'}
                        </Button>

                        <Button
                            disabled={submitting}
                            onClick={() => resolve(false)}
                            size="sm"
                            type="button"
                            variant="outline"
                        >
                            {submitting ? 'Submitting…' : 'Discard'}
                        </Button>
                    </div>
                </>
            )}
        </div>
    );
};

export default ApprovalRequestMessage;

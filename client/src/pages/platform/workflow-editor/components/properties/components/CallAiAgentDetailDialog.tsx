import {Dialog, DialogContent, DialogTitle} from '@/components/ui/dialog';
import AgentDetailContent from '@/pages/automation/agents/AgentDetailContent';
import getAgentPath from '@/pages/automation/agents/utils/getAgentPath';
import invalidateAgentQueries from '@/pages/automation/agents/utils/invalidateAgentQueries';
import {useAiAgentQuery} from '@/shared/middleware/graphql';
import {WorkflowNodeOptionKeys} from '@/shared/queries/platform/workflowNodeOptions.queries';
import {useQueryClient} from '@tanstack/react-query';
import {ExternalLinkIcon} from 'lucide-react';
import {Link} from 'react-router-dom';

export interface CallAiAgentDetailDialogPropsI {
    agentId: string;
    agentTitle: string;
    onOpenChange: (open: boolean) => void;
    open: boolean;
}

/**
 * Shows the editable Agent builder inside a dialog, so the agent picked on a Call AI Agent node can be
 * inspected and changed without leaving the workflow editor. Reuses the same {@link AgentDetailContent}
 * card composition the routed Agent page and the AI Hub resource panel use.
 *
 * <p>
 * Closing the dialog invalidates two caches, because the picker's label and this dialog's own lookup come
 * from different places: the label is served by the platform's generic node-options endpoint, while the
 * uuid-to-id resolution comes from the aiAgents GraphQL query. Renaming an agent here and invalidating only
 * one of them leaves the dropdown and the link disagreeing about the agent's name.
 * </p>
 */
const CallAiAgentDetailDialog = ({agentId, agentTitle, onOpenChange, open}: CallAiAgentDetailDialogPropsI) => {
    const queryClient = useQueryClient();

    // The routed agent page now lives under its project, so the link needs the agent's projectId — which
    // this dialog otherwise has no reason to fetch. TanStack Query dedupes this against AgentDetailContent's
    // own useAiAgentQuery call for the same id.
    const {data} = useAiAgentQuery({id: agentId}, {enabled: !!agentId});

    const agent = data?.aiAgent;

    const handleOpenChange = (nextOpen: boolean) => {
        if (!nextOpen) {
            queryClient.invalidateQueries({queryKey: WorkflowNodeOptionKeys.workflowNodeOptions});

            invalidateAgentQueries(queryClient);
        }

        onOpenChange(nextOpen);
    };

    return (
        <Dialog onOpenChange={handleOpenChange} open={open}>
            <DialogContent className="flex h-[85vh] w-[92vw] flex-col gap-0 p-0 sm:max-w-5xl">
                <header className="flex shrink-0 items-center justify-between gap-2 border-b border-stroke-neutral-secondary px-4 py-3">
                    <DialogTitle className="truncate text-sm font-semibold text-content-neutral-primary">
                        {agentTitle}
                    </DialogTitle>

                    {agent && (
                        <Link
                            className="mr-8 flex shrink-0 items-center gap-1 text-xs text-content-brand-primary hover:underline"
                            rel="noreferrer"
                            target="_blank"
                            to={getAgentPath(agent)}
                        >
                            Open in full view
                            <ExternalLinkIcon className="size-3" />
                        </Link>
                    )}
                </header>

                <div className="min-h-0 flex-1 overflow-y-auto p-4">
                    <AgentDetailContent agentId={agentId} className="max-w-none p-0" />
                </div>
            </DialogContent>
        </Dialog>
    );
};

export default CallAiAgentDetailDialog;

import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import CallAiAgentDetailDialog from '@/pages/platform/workflow-editor/components/properties/components/CallAiAgentDetailDialog';
import {useAiAgentsQuery} from '@/shared/middleware/graphql';
import {ExternalLinkIcon} from 'lucide-react';
import {useState} from 'react';

export interface CallAiAgentDetailLinkPropsI {
    agentUuid: string | undefined;
}

/**
 * The "View agent details" link under the Call AI Agent node's Agent picker, opening the picked agent's
 * editable builder in a dialog.
 *
 * <p>
 * The picker stores an agent uuid while the builder is keyed by id, so this resolves one to the other
 * through the workspace's agent list. Nothing renders until that resolution succeeds: with no agent picked
 * there is nothing to link to, and an agent deleted after being picked would otherwise open a dialog onto
 * an id that no longer exists.
 * </p>
 */
const CallAiAgentDetailLink = ({agentUuid}: CallAiAgentDetailLinkPropsI) => {
    const [dialogOpen, setDialogOpen] = useState(false);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const {data} = useAiAgentsQuery(
        {workspaceId: String(currentWorkspaceId)},
        {enabled: !!currentWorkspaceId && !!agentUuid}
    );

    const agent = data?.aiAgents?.find((aiAgent) => aiAgent?.uuid === agentUuid);

    if (!agent) {
        return null;
    }

    return (
        <>
            <button
                className="flex items-center gap-1 self-start text-xs text-content-brand-primary hover:underline"
                onClick={() => setDialogOpen(true)}
                type="button"
            >
                View agent details
                <ExternalLinkIcon className="size-3" />
            </button>

            {dialogOpen && (
                <CallAiAgentDetailDialog
                    agentId={agent.id}
                    agentTitle={agent.title}
                    onOpenChange={setDialogOpen}
                    open={dialogOpen}
                />
            )}
        </>
    );
};

export default CallAiAgentDetailLink;

import invalidateAgentQueries from '@/pages/automation/agents/utils/invalidateAgentQueries';
import {useDeleteAiAgentMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface UseAgentActionsProps {
    agentId: string;
    /** True when deleting this agent should navigate away — either it is the agent's own page, or it is the
     *  row currently open in the sidebar. Deleting a different sidebar row leaves the user where they are. */
    navigateOnDelete: boolean;
}

/** Shared Edit/Delete wiring for an agent, used by both the sidebar row menu and the agent page's settings
 *  menu tab, so the mutation and its toast/invalidation/navigation side effects are defined once. Delete goes
 *  through a confirmation dialog rather than mutating straight away: handleDeleteClick only opens it, and the
 *  caller renders DeleteAgentAlertDialog wired to handleConfirmDelete/setShowDeleteConfirmDialog so every place
 *  an agent can be deleted from shares the same guard. */
const useAgentActions = ({agentId, navigateOnDelete}: UseAgentActionsProps) => {
    const [showDeleteConfirmDialog, setShowDeleteConfirmDialog] = useState(false);
    const [showEditDialog, setShowEditDialog] = useState(false);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    const deleteAgentMutation = useDeleteAiAgentMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to delete the agent.');
        },
        onSuccess: () => {
            invalidateAgentQueries(queryClient);

            if (navigateOnDelete) {
                navigate('/automation/projects');
            }
        },
    });

    const handleDeleteClick = () => {
        setShowDeleteConfirmDialog(true);
    };

    const handleConfirmDelete = () => {
        setShowDeleteConfirmDialog(false);

        deleteAgentMutation.mutate({id: agentId});
    };

    return {
        deleteAgentMutation,
        handleConfirmDelete,
        handleDeleteClick,
        setShowDeleteConfirmDialog,
        setShowEditDialog,
        showDeleteConfirmDialog,
        showEditDialog,
    };
};

export default useAgentActions;

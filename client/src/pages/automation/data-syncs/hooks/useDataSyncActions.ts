import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {useDeleteDataSyncMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {useState} from 'react';
import {toast} from 'sonner';

interface UseDataSyncActionsProps {
    dataSync: {id: string};
    /**
     * Called after a successful delete. The caller decides what that means — navigating away when this is
     * the data sync's own page or the row currently open in a sidebar, or nothing at all when it is a
     * different row in a list that will simply refetch.
     */
    onDeleted?: () => void;
}

/**
 * Shared Edit/Delete wiring for a data sync, the DataSync twin of useAgentActions: the delete mutation and
 * its toast/invalidation side effects are defined once. Delete goes through a confirmation dialog rather
 * than mutating straight away: handleDeleteClick only opens it, and the caller renders
 * DeleteDataSyncAlertDialog wired to deleteDataSync/showDeleteConfirmDialog/setShowDeleteConfirmDialog so
 * every place a data sync can be deleted from shares the same guard.
 */
const useDataSyncActions = ({dataSync, onDeleted}: UseDataSyncActionsProps) => {
    const [showDeleteConfirmDialog, setShowDeleteConfirmDialog] = useState(false);
    const [showEditDialog, setShowEditDialog] = useState(false);

    const queryClient = useQueryClient();

    const deleteDataSyncMutation = useDeleteDataSyncMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to delete the data sync.');
        },
        onSuccess: () => {
            invalidateDataSyncQueries(queryClient, {projects: true});

            onDeleted?.();
        },
    });

    const handleDeleteClick = () => {
        setShowDeleteConfirmDialog(true);
    };

    const deleteDataSync = () => {
        setShowDeleteConfirmDialog(false);

        deleteDataSyncMutation.mutate({id: dataSync.id});
    };

    const openEditDialog = () => {
        setShowEditDialog(true);
    };

    return {
        deleteDataSync,
        handleDeleteClick,
        isDeleting: deleteDataSyncMutation.isPending,
        openEditDialog,
        setShowDeleteConfirmDialog,
        setShowEditDialog,
        showDeleteConfirmDialog,
        showEditDialog,
    };
};

export default useDataSyncActions;

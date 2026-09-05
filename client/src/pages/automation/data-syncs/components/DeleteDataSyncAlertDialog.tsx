import AlertDialog from '@/components/AlertDialog';

interface DeleteDataSyncAlertDialogProps {
    dataSyncTitle: string;
    onClose: () => void;
    onDelete: () => void;
}

/**
 * Confirms a data sync delete before the mutation runs, the DataSync twin of DeleteAgentAlertDialog. The
 * project-scoped entry points gate on it: the sidebar row menu (shared with the Projects page row) and the
 * Data Sync tab of the sync page's settings menu.
 */
const DeleteDataSyncAlertDialog = ({dataSyncTitle, onClose, onDelete}: DeleteDataSyncAlertDialogProps) => (
    <AlertDialog
        ariaLabel="Confirm Data Sync Deletion"
        description={`This action cannot be undone. This will permanently delete the data sync ${dataSyncTitle}.`}
        onCancel={onClose}
        onConfirm={onDelete}
        open
    />
);

export default DeleteDataSyncAlertDialog;

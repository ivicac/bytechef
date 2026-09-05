import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {useRef} from 'react';

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
const DeleteDataSyncAlertDialog = ({dataSyncTitle, onClose, onDelete}: DeleteDataSyncAlertDialogProps) => {
    const deleteButtonRef = useRef<HTMLButtonElement>(null);

    return (
        <AlertDialog open={true}>
            <AlertDialogContent
                onOpenAutoFocus={(event) => {
                    event.preventDefault();
                    deleteButtonRef.current?.focus();
                }}
            >
                <AlertDialogHeader>
                    <AlertDialogTitle>Are you absolutely sure?</AlertDialogTitle>

                    <AlertDialogDescription>
                        This action cannot be undone. This will permanently delete the data sync {dataSyncTitle}.
                    </AlertDialogDescription>
                </AlertDialogHeader>

                <AlertDialogFooter>
                    <AlertDialogCancel onClick={onClose}>Cancel</AlertDialogCancel>

                    <AlertDialogAction
                        aria-label="Confirm Data Sync Deletion"
                        className="bg-surface-destructive-primary text-content-onsurface-primary shadow-none hover:bg-surface-destructive-primary-hover active:bg-surface-destructive-primary-active"
                        onClick={onDelete}
                        ref={deleteButtonRef}
                    >
                        Delete
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
};

export default DeleteDataSyncAlertDialog;

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

interface DeleteAgentAlertDialogProps {
    agentTitle: string;
    onClose: () => void;
    onDelete: () => void;
}

/**
 * Confirms an agent delete before the mutation runs, mirroring DeleteWorkflowAlertDialog. Every place an agent can
 * be deleted from gates on it: the sidebar row menu, the Projects-page agent row menu (which shares the sidebar row
 * menu component), the agent page's Agent settings tab, and the AI Hub Scheduled list row.
 */
const DeleteAgentAlertDialog = ({agentTitle, onClose, onDelete}: DeleteAgentAlertDialogProps) => {
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
                        This action cannot be undone. This will permanently delete the agent {agentTitle}.
                    </AlertDialogDescription>
                </AlertDialogHeader>

                <AlertDialogFooter>
                    <AlertDialogCancel onClick={onClose}>Cancel</AlertDialogCancel>

                    <AlertDialogAction
                        aria-label="Confirm Agent Deletion"
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

export default DeleteAgentAlertDialog;

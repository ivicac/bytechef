import AlertDialog from '@/components/AlertDialog';

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
const DeleteAgentAlertDialog = ({agentTitle, onClose, onDelete}: DeleteAgentAlertDialogProps) => (
    <AlertDialog
        ariaLabel="Confirm Agent Deletion"
        description={`This action cannot be undone. This will permanently delete the agent ${agentTitle}.`}
        onCancel={onClose}
        onConfirm={onDelete}
        open
    />
);

export default DeleteAgentAlertDialog;

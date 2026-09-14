import Button from '@/components/Button/Button';
import LoadingIcon from '@/components/LoadingIcon';
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
import {Trash2Icon, XIcon} from 'lucide-react';

interface DeleteAlertDialogProps {
    open: boolean;
    confirmLabel?: string;
    description?: string;
    isPending?: boolean;
    nodeName?: string;
    onCancel: () => void;
    onDelete: () => void;
    title?: string;
}

const DeleteAlertDialog = ({
    confirmLabel,
    description,
    isPending,
    nodeName,
    onCancel,
    onDelete,
    open,
    title,
}: DeleteAlertDialogProps) => {
    const isNodeDeleteDialog = !!nodeName;

    return (
        <AlertDialog open={open}>
            <AlertDialogContent onEscapeKeyDown={onCancel}>
                <AlertDialogHeader>
                    <AlertDialogTitle>
                        {title ?? (isNodeDeleteDialog ? `Delete node ${nodeName}?` : 'Are you absolutely sure?')}
                    </AlertDialogTitle>

                    <AlertDialogDescription>
                        {description ??
                            (isNodeDeleteDialog
                                ? 'This action cannot be undone. This will permanently delete the node and properties it contains.'
                                : 'This action cannot be undone. This will permanently delete data.')}
                    </AlertDialogDescription>

                    <Button
                        aria-label="Close"
                        className="absolute top-4 right-4"
                        icon={<XIcon />}
                        onClick={onCancel}
                        size="icon"
                        variant="ghost"
                    />
                </AlertDialogHeader>

                <AlertDialogFooter>
                    <AlertDialogCancel disabled={isPending} onClick={onCancel}>
                        {isNodeDeleteDialog ? 'Keep node' : 'Cancel'}
                    </AlertDialogCancel>

                    <AlertDialogAction
                        className="bg-surface-destructive-primary text-content-onsurface-primary shadow-none hover:bg-surface-destructive-primary-hover active:bg-surface-destructive-primary-active"
                        disabled={isPending}
                        onClick={onDelete}
                    >
                        {isPending ? <LoadingIcon /> : isNodeDeleteDialog && <Trash2Icon />}

                        {confirmLabel ?? (isNodeDeleteDialog ? 'Delete node' : 'Delete')}
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
};

export default DeleteAlertDialog;

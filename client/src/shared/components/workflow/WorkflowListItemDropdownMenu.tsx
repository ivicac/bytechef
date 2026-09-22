import Button from '@/components/Button/Button';
import DeleteAlertDialog from '@/components/DeleteAlertDialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

import '@/shared/styles/dropdownMenu.css';
import {EditIcon, EllipsisVerticalIcon, Trash2Icon} from 'lucide-react';
import {ReactNode, useState} from 'react';
import {twMerge} from 'tailwind-merge';

interface WorkflowListItemDropdownMenuProps {
    children?: ReactNode;
    dialogs?: ReactNode;
    onDelete: () => void;
    onEditClick: () => void;
    /**
     * Extra classes for the trigger button. Callers whose row has a `group` hover state (the project editor
     * sidebar) use this to keep the trigger hidden until the row is hovered or the menu is open, the way the
     * data tables and agents sidebars do. Defaults to always visible, matching the other callers of this menu.
     */
    triggerClassName?: string;
    workflowLabel?: string | null;
}

const WorkflowListItemDropdownMenu = ({
    children,
    dialogs,
    onDelete,
    onEditClick,
    triggerClassName,
    workflowLabel,
}: WorkflowListItemDropdownMenuProps) => {
    const [showDeleteWorkflowAlertDialog, setShowDeleteWorkflowAlertDialog] = useState(false);

    const ariaLabel = workflowLabel ? `Workflow actions for ${workflowLabel}` : 'Workflow actions';

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button
                        aria-label={ariaLabel}
                        className={twMerge('-mr-px w-6 px-0', triggerClassName)}
                        icon={<EllipsisVerticalIcon />}
                        onClick={(event) => event.stopPropagation()}
                        size="icon"
                        variant="ghost"
                    />
                </DropdownMenuTrigger>

                <DropdownMenuContent align="end" className="p-0">
                    <DropdownMenuItem className="dropdown-menu-item" onClick={onEditClick}>
                        <EditIcon /> Edit
                    </DropdownMenuItem>

                    {children}

                    <DropdownMenuSeparator className="m-0" />

                    <DropdownMenuItem
                        className="dropdown-menu-item-destructive"
                        onClick={() => setShowDeleteWorkflowAlertDialog(true)}
                        variant="destructive"
                    >
                        <Trash2Icon /> Delete
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            {showDeleteWorkflowAlertDialog && (
                <DeleteAlertDialog
                    onCancel={() => setShowDeleteWorkflowAlertDialog(false)}
                    onDelete={() => {
                        onDelete();

                        setShowDeleteWorkflowAlertDialog(false);
                    }}
                    open={showDeleteWorkflowAlertDialog}
                />
            )}

            {dialogs}
        </>
    );
};

export default WorkflowListItemDropdownMenu;

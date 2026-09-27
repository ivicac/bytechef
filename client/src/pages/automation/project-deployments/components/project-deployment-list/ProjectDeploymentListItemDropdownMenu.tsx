import '@/shared/styles/dropdownMenu.css';
import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import EEVersion from '@/shared/edition/EEVersion';
import {
    ArrowUpRightIcon,
    EditIcon,
    EllipsisVerticalIcon,
    RefreshCcwIcon,
    SquareArrowOutUpRightIcon,
    Trash2Icon,
} from 'lucide-react';

interface ProjectDeploymentListItemDropdownMenuProps {
    /**
     * Label for the change-version item. Overridden by the agent deployments list, where the thing being
     * versioned is the agent — its backing project is an implementation detail the user never sees named.
     */
    changeVersionLabel?: string;
    onChangeProjectVersionClick: () => void;
    onDeleteClick: () => void;
    onEditClick: () => void;
    /**
     * Omitted where the project editor is unreachable — outside Development, and in the agent deployments list,
     * whose backing project the user never sees named — which hides the item.
     */
    onOpenProjectClick?: () => void;
    /**
     * Not passed by the agent deployments list — an agent deployment's underlying ProjectDeployment is
     * promoted through its owning agent, not directly, so the item stays hidden there.
     */
    onPromoteClick?: () => void;
    showPromoteToEnvironment?: boolean;
}

const ProjectDeploymentListItemDropdownMenu = ({
    changeVersionLabel = 'Change Project Version',
    onChangeProjectVersionClick,
    onDeleteClick,
    onEditClick,
    onOpenProjectClick,
    onPromoteClick,
    showPromoteToEnvironment = false,
}: ProjectDeploymentListItemDropdownMenuProps) => {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button icon={<EllipsisVerticalIcon />} size="icon" variant="ghost" />
            </DropdownMenuTrigger>

            <DropdownMenuContent align="end" className="p-0">
                <DropdownMenuItem className="dropdown-menu-item" onClick={onEditClick}>
                    <EditIcon /> Edit
                </DropdownMenuItem>

                {onOpenProjectClick && (
                    <DropdownMenuItem className="dropdown-menu-item" onClick={onOpenProjectClick}>
                        <SquareArrowOutUpRightIcon /> Open Project
                    </DropdownMenuItem>
                )}

                <DropdownMenuItem className="dropdown-menu-item" onClick={onChangeProjectVersionClick}>
                    <RefreshCcwIcon /> {changeVersionLabel}
                </DropdownMenuItem>

                {showPromoteToEnvironment && onPromoteClick && (
                    <EEVersion hidden={true}>
                        <DropdownMenuItem className="dropdown-menu-item" onClick={onPromoteClick}>
                            <ArrowUpRightIcon /> Promote to environment…
                        </DropdownMenuItem>
                    </EEVersion>
                )}

                <DropdownMenuSeparator className="m-0" />

                <DropdownMenuItem
                    className="dropdown-menu-item-destructive"
                    onClick={onDeleteClick}
                    variant="destructive"
                >
                    <Trash2Icon /> Delete
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

export default ProjectDeploymentListItemDropdownMenu;

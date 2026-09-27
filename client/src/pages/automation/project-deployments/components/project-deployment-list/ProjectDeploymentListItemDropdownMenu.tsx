import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/DropdownMenu/DropdownMenu';
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

            <DropdownMenuContent align="end">
                <DropdownMenuItem icon={<EditIcon />} label="Edit" onClick={onEditClick} />

                {onOpenProjectClick && (
                    <DropdownMenuItem
                        icon={<SquareArrowOutUpRightIcon />}
                        label="Open Project"
                        onClick={onOpenProjectClick}
                    />
                )}

                <DropdownMenuItem
                    icon={<RefreshCcwIcon />}
                    label={changeVersionLabel}
                    onClick={onChangeProjectVersionClick}
                />

                {showPromoteToEnvironment && onPromoteClick && (
                    <EEVersion hidden={true}>
                        <DropdownMenuItem
                            icon={<ArrowUpRightIcon />}
                            label="Promote to environment…"
                            onClick={onPromoteClick}
                        />
                    </EEVersion>
                )}

                <DropdownMenuSeparator />

                <DropdownMenuItem icon={<Trash2Icon />} label="Delete" onClick={onDeleteClick} variant="destructive" />
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

export default ProjectDeploymentListItemDropdownMenu;

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
import {McpServer} from '@/shared/middleware/graphql';
import {EditIcon, EllipsisVerticalIcon, Trash2Icon} from 'lucide-react';

interface McpServerListItemDropdownMenuProps {
    mcpServer: McpServer;
    onDeleteClick: () => void;
    onEditClick: () => void;
    onPromoteClick?: () => void;
    showPromoteToEnvironment?: boolean;
}

const McpServerListItemDropdownMenu = ({
    onDeleteClick,
    onEditClick,
    onPromoteClick,
    showPromoteToEnvironment,
}: McpServerListItemDropdownMenuProps) => {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button icon={<EllipsisVerticalIcon />} size="icon" variant="ghost" />
            </DropdownMenuTrigger>

            <DropdownMenuContent align="end">
                <DropdownMenuItem className="dropdown-menu-item" onClick={onEditClick}>
                    <EditIcon /> Edit
                </DropdownMenuItem>

                {showPromoteToEnvironment && (
                    <EEVersion hidden={true}>
                        <DropdownMenuItem onClick={onPromoteClick}>Promote to environment…</DropdownMenuItem>
                    </EEVersion>
                )}

                <DropdownMenuSeparator />

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

export default McpServerListItemDropdownMenu;

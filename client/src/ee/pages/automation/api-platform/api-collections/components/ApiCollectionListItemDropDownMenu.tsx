import '@/shared/styles/dropdownMenu.css';
import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {DownloadIcon, EditIcon, EllipsisVerticalIcon, PlusIcon, RefreshCcwIcon, Trash2Icon} from 'lucide-react';

interface ApiCollectionListItemDropdownMenuProps {
    apiCollectionId: number;
    onDeleteClick: () => void;
    onEditClick: () => void;
    onNewEndpoint: () => void;
    onPromoteClick: () => void;
    onUpdateProjectVersionClick: () => void;
    showPromoteToEnvironment: boolean;
}

const ApiCollectionListItemDropDownMenu = ({
    apiCollectionId,
    onDeleteClick,
    onEditClick,
    onNewEndpoint,
    onPromoteClick,
    onUpdateProjectVersionClick,
    showPromoteToEnvironment,
}: ApiCollectionListItemDropdownMenuProps) => {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button icon={<EllipsisVerticalIcon />} size="icon" variant="ghost" />
            </DropdownMenuTrigger>

            <DropdownMenuContent align="end" className="p-0">
                <DropdownMenuItem className="dropdown-menu-item" onClick={onEditClick}>
                    <EditIcon /> Edit
                </DropdownMenuItem>

                <DropdownMenuItem className="dropdown-menu-item" onClick={onUpdateProjectVersionClick}>
                    <RefreshCcwIcon /> Update Project Version
                </DropdownMenuItem>

                {showPromoteToEnvironment && (
                    <DropdownMenuItem className="dropdown-menu-item" onClick={onPromoteClick}>
                        Promote to environment…
                    </DropdownMenuItem>
                )}

                <DropdownMenuItem className="dropdown-menu-item" onClick={onNewEndpoint}>
                    <PlusIcon /> New Endpoint
                </DropdownMenuItem>

                <DropdownMenuItem
                    className="dropdown-menu-item"
                    onClick={() =>
                        (window.location.href = `/api/automation/api-platform/internal/api-collections/${apiCollectionId}/openapi.json`)
                    }
                >
                    <DownloadIcon /> Download OpenAPI Spec
                </DropdownMenuItem>

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

export default ApiCollectionListItemDropDownMenu;

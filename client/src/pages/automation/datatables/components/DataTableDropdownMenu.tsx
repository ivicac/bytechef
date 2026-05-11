import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import useDataTableDropdownMenu from '@/pages/automation/datatables/components/hooks/useDataTableDropdownMenu';
import {CopyIcon, DownloadIcon, EditIcon, EllipsisVerticalIcon, Trash2Icon, UploadIcon} from 'lucide-react';

interface DataTableDropdownMenuProps {
    baseName: string;
    dataTableId: string;
    onImportCsv?: () => void;
    triggerClassName?: string;
    triggerSize?: 'icon' | 'iconSm';
}

const DataTableDropdownMenu = ({
    baseName,
    dataTableId,
    onImportCsv,
    triggerClassName,
    triggerSize = 'icon',
}: DataTableDropdownMenuProps) => {
    const {handleDeleteClick, handleDuplicateClick, handleExportCsvClick, handleRenameClick} = useDataTableDropdownMenu(
        {
            baseName,
            dataTableId,
        }
    );

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button
                    aria-label="Table menu"
                    className={triggerClassName}
                    icon={<EllipsisVerticalIcon />}
                    size={triggerSize}
                    variant="ghost"
                />
            </DropdownMenuTrigger>

            <DropdownMenuContent align="end">
                <DropdownMenuItem className="dropdown-menu-item" onClick={handleRenameClick}>
                    <EditIcon /> Rename
                </DropdownMenuItem>

                <DropdownMenuItem className="dropdown-menu-item" onClick={handleDuplicateClick}>
                    <CopyIcon /> Duplicate
                </DropdownMenuItem>

                {onImportCsv && (
                    <DropdownMenuItem className="dropdown-menu-item" onClick={onImportCsv}>
                        <UploadIcon /> Import CSV
                    </DropdownMenuItem>
                )}

                <DropdownMenuItem className="dropdown-menu-item" onClick={handleExportCsvClick}>
                    <DownloadIcon /> Export CSV
                </DropdownMenuItem>

                <DropdownMenuSeparator className="m-0" />

                <DropdownMenuItem
                    className="dropdown-menu-item-destructive"
                    onClick={handleDeleteClick}
                    variant="destructive"
                >
                    <Trash2Icon /> Delete
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

export default DataTableDropdownMenu;

import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/DropdownMenu/DropdownMenu';
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
                <DropdownMenuItem icon={<EditIcon />} label="Rename" onClick={handleRenameClick} />

                <DropdownMenuItem icon={<CopyIcon />} label="Duplicate" onClick={handleDuplicateClick} />

                {onImportCsv && <DropdownMenuItem icon={<UploadIcon />} label="Import CSV" onClick={onImportCsv} />}

                <DropdownMenuItem icon={<DownloadIcon />} label="Export CSV" onClick={handleExportCsvClick} />

                <DropdownMenuSeparator />

                <DropdownMenuItem
                    icon={<Trash2Icon />}
                    label="Delete"
                    onClick={handleDeleteClick}
                    variant="destructive"
                />
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

export default DataTableDropdownMenu;

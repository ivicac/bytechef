import Button from '@/components/Button/Button';
import DataTableDropdownMenu from '@/pages/automation/datatables/components/DataTableDropdownMenu';
import Header from '@/shared/layout/Header';
import {SparklesIcon, Trash2Icon} from 'lucide-react';

import useDataTableHeader from '../hooks/useDataTableHeader';
import useImportDataTableCsvDialog from '../hooks/useImportDataTableCsvDialog';
import {useCurrentDataTableStore} from '../stores/useCurrentDataTableStore';

interface DataTableHeaderPropsI {
    onAskCopilot?: () => void;
}

const DataTableHeader = ({onAskCopilot}: DataTableHeaderPropsI) => {
    const {dataTable} = useCurrentDataTableStore();

    const {handleOpenDeleteRowsDialog, selectedRowsCount} = useDataTableHeader();

    const {handleOpen: handleOpenImportCsvDialog} = useImportDataTableCsvDialog();

    return (
        <Header
            centerTitle
            position="main"
            right={
                <div className="flex items-center gap-1">
                    {selectedRowsCount > 0 && (
                        <Button onClick={handleOpenDeleteRowsDialog} variant="destructive">
                            <Trash2Icon className="size-4" /> Delete ({selectedRowsCount})
                        </Button>
                    )}

                    {onAskCopilot && (
                        <Button
                            aria-label="Ask Copilot"
                            icon={<SparklesIcon />}
                            onClick={onAskCopilot}
                            size="icon"
                            variant="ghost"
                        />
                    )}

                    {dataTable && (
                        <DataTableDropdownMenu
                            baseName={dataTable.baseName}
                            dataTableId={dataTable.id}
                            onImportCsv={handleOpenImportCsvDialog}
                        />
                    )}
                </div>
            }
            title={
                <div className="flex items-center gap-2">
                    <span className="font-semibold">{dataTable?.baseName}</span>
                </div>
            }
        />
    );
};

export default DataTableHeader;

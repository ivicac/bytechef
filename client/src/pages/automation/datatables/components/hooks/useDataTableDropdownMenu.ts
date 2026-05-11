import useDeleteDataTableAlertDialog from '@/pages/automation/datatable/hooks/useDeleteDataTableAlertDialog';
import useRenameDataTableDialog from '@/pages/automation/datatable/hooks/useRenameDataTableDialog';
import useDuplicateDataTableDialog from '@/pages/automation/datatables/components/hooks/useDuplicateDataTableDialog';
import {useExportDataTableCsvQuery} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {MouseEvent, useCallback} from 'react';
import {toast} from 'sonner';

interface UseDataTableDropdownMenuProps {
    baseName: string;
    dataTableId: string;
}

interface UseDataTableDropdownMenuI {
    handleDeleteClick: (event: MouseEvent) => void;
    handleDuplicateClick: (event: MouseEvent) => void;
    handleExportCsvClick: (event: MouseEvent) => void;
    handleRenameClick: (event: MouseEvent) => void;
}

export default function useDataTableDropdownMenu({
    baseName,
    dataTableId,
}: UseDataTableDropdownMenuProps): UseDataTableDropdownMenuI {
    const environmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const {handleOpen: handleDeleteDialogOpen} = useDeleteDataTableAlertDialog();
    const {handleOpen: handleDuplicateDialogOpen} = useDuplicateDataTableDialog();
    const {handleOpen: handleRenameDialogOpen} = useRenameDataTableDialog();

    const {refetch: refetchExportCsv} = useExportDataTableCsvQuery(
        {environmentId: String(environmentId), tableId: dataTableId},
        {enabled: false}
    );

    const handleRenameClick = useCallback(
        (event: MouseEvent) => {
            event.preventDefault();
            event.stopPropagation();

            handleRenameDialogOpen(dataTableId, baseName);
        },
        [baseName, dataTableId, handleRenameDialogOpen]
    );

    const handleDuplicateClick = useCallback(
        (event: MouseEvent) => {
            event.preventDefault();
            event.stopPropagation();

            handleDuplicateDialogOpen(dataTableId, baseName);
        },
        [baseName, dataTableId, handleDuplicateDialogOpen]
    );

    const handleExportCsvClick = useCallback(
        async (event: MouseEvent) => {
            event.preventDefault();
            event.stopPropagation();

            try {
                const {data} = await refetchExportCsv();

                if (!data?.exportDataTableCsv) {
                    return;
                }

                const blob = new Blob([data.exportDataTableCsv], {type: 'text/csv;charset=utf-8;'});

                const url = URL.createObjectURL(blob);

                const anchor = document.createElement('a');

                anchor.href = url;
                anchor.download = `${baseName}.csv`;

                document.body.appendChild(anchor);

                anchor.click();
                anchor.remove();

                URL.revokeObjectURL(url);
            } catch (error) {
                console.error('Failed to export CSV:', error);

                toast.error('Failed to export CSV');
            }
        },
        [baseName, refetchExportCsv]
    );

    const handleDeleteClick = useCallback(
        (event: MouseEvent) => {
            event.preventDefault();
            event.stopPropagation();

            handleDeleteDialogOpen(dataTableId, baseName);
        },
        [baseName, dataTableId, handleDeleteDialogOpen]
    );

    return {
        handleDeleteClick,
        handleDuplicateClick,
        handleExportCsvClick,
        handleRenameClick,
    };
}

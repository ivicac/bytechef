import {DataTableScopeType} from '@/shared/components/data-tables/types';
import {ColumnType, useCreateDataTableMutation, useCreateEmbeddedDataTableMutation} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useQueryClient} from '@tanstack/react-query';
import {useState} from 'react';

type ColumnDefinitionType = {name: string; type: ColumnType};

interface UseCreateDataTableDialogI {
    baseName: string;
    canSubmit: boolean;
    columns: ColumnDefinitionType[];
    description: string;
    handleAddColumn: () => void;
    handleBaseNameChange: (value: string) => void;
    handleClose: () => void;
    handleColumnNameChange: (index: number, name: string) => void;
    handleColumnTypeChange: (index: number, type: ColumnType) => void;
    handleCreate: () => void;
    handleDescriptionChange: (value: string) => void;
    handleOpen: () => void;
    handleOpenChange: (open: boolean) => void;
    handleOwnerIdChange: (ownerId: number | undefined) => void;
    handleRemoveColumn: (index: number) => void;
    isPending: boolean;
    open: boolean;
    ownerId: number | undefined;
}

/**
 * The create behind both surfaces, taking the same scope `useDataTables` takes and for the same reason: reading the
 * workspace store here would couple the dialog to a surface that has no workspaces. Both mutations are called on every
 * render because hooks cannot be conditional; the scope picks which one `handleCreate` fires.
 *
 * The owner is dialog state rather than part of the scope. The scope's `ownerId` is the console's list filter, while
 * this one is the account the vendor picks while creating -- the two are independent, and a vendor filtering the list
 * to one account is not thereby creating for it.
 */
export default function useCreateDataTableDialog(scope: DataTableScopeType): UseCreateDataTableDialogI {
    const [open, setOpen] = useState(false);
    const [baseName, setBaseName] = useState('');
    const [columns, setColumns] = useState<ColumnDefinitionType[]>([{name: '', type: ColumnType.String}]);
    const [description, setDescription] = useState('');
    const [ownerId, setOwnerId] = useState<number | undefined>(undefined);

    const environmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const queryClient = useQueryClient();

    const isWorkspaceScope = scope.type === 'WORKSPACE';

    const resetForm = () => {
        setOpen(false);
        setBaseName('');
        setColumns([{name: '', type: ColumnType.String}]);
        setDescription('');
        setOwnerId(undefined);
    };

    const createMutation = useCreateDataTableMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ['dataTables']});
            resetForm();
        },
    });

    const createEmbeddedMutation = useCreateEmbeddedDataTableMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ['EmbeddedDataTables']});
            resetForm();
        },
    });

    const canSubmit = baseName.trim().length > 0 && columns.every((column) => column.name.trim().length > 0);

    const handleOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
    };

    const handleOpenChange = (open: boolean) => {
        if (!open) {
            handleClose();
        }
    };

    const handleAddColumn = () => {
        setColumns((previous) => [...previous, {name: '', type: ColumnType.String}]);
    };

    const handleRemoveColumn = (index: number) => {
        setColumns((previous) => previous.filter((_, columnIndex) => columnIndex !== index));
    };

    const handleColumnNameChange = (index: number, name: string) => {
        setColumns((previous) => {
            const previousColumns = [...previous];
            previousColumns[index] = {...previousColumns[index], name};

            return previousColumns;
        });
    };

    const handleColumnTypeChange = (index: number, type: ColumnType) => {
        setColumns((previous) => {
            const previousColumns = [...previous];
            previousColumns[index] = {...previousColumns[index], type};

            return previousColumns;
        });
    };

    const handleBaseNameChange = (value: string) => {
        setBaseName(value);
    };

    const handleDescriptionChange = (value: string) => {
        setDescription(value);
    };

    const handleOwnerIdChange = (value: number | undefined) => {
        setOwnerId(value);
    };

    const handleCreate = () => {
        const trimmedColumns = columns.map((column) => ({name: column.name.trim(), type: column.type}));

        if (scope.type === 'WORKSPACE') {
            createMutation.mutate({
                input: {
                    baseName: baseName.trim(),
                    columns: trimmedColumns,
                    description: description.trim() || undefined,
                    environmentId: String(environmentId),
                    workspaceId: String(scope.workspaceId),
                },
            });

            return;
        }

        createEmbeddedMutation.mutate({
            input: {
                columns: trimmedColumns,
                description: description.trim() || undefined,
                environmentId: String(environmentId),
                name: baseName.trim(),
                ownerId: ownerId === undefined ? undefined : String(ownerId),
            },
        });
    };

    return {
        baseName,
        canSubmit,
        columns,
        description,
        handleAddColumn,
        handleBaseNameChange,
        handleClose,
        handleColumnNameChange,
        handleColumnTypeChange,
        handleCreate,
        handleDescriptionChange,
        handleOpen,
        handleOpenChange,
        handleOwnerIdChange,
        handleRemoveColumn,
        isPending: isWorkspaceScope ? createMutation.isPending : createEmbeddedMutation.isPending,
        open,
        ownerId,
    };
}

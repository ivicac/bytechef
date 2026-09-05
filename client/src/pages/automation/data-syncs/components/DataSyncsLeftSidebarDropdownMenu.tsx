import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import DataSyncDialog from '@/pages/automation/data-syncs/components/DataSyncDialog';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {useDeleteDataSyncMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {MoreVerticalIcon, PencilIcon, Trash2Icon} from 'lucide-react';
import {useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface DataSyncsLeftSidebarDropdownMenuProps {
    current: boolean;
    dataSync: {description?: string | null; id: string; title: string};
}

/**
 * Per-row Edit/Delete menu for the data syncs sidebar, mirroring the agents sidebar: hidden until the row is
 * hovered (or the menu is open), so a list of data syncs stays a list of names. On the detail page this
 * sidebar is the only way to move between syncs, so it needs its own way to rename or remove one without
 * first opening it.
 */
const DataSyncsLeftSidebarDropdownMenu = ({current, dataSync}: DataSyncsLeftSidebarDropdownMenuProps) => {
    const [showEditDialog, setShowEditDialog] = useState(false);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    const deleteDataSyncMutation = useDeleteDataSyncMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to delete the data sync.');
        },
        onSuccess: () => {
            invalidateDataSyncQueries(queryClient);

            // Only when the deleted data sync is the one being viewed — deleting another row from the
            // sidebar should leave the user where they are.
            if (current) {
                navigate('/automation/data-syncs');
            }
        },
    });

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button
                        aria-label={`${dataSync.title} menu`}
                        className="w-6 opacity-0 transition-opacity group-hover:opacity-100 data-[state=open]:opacity-100"
                        icon={<MoreVerticalIcon className="h-4" />}
                        size="iconSm"
                        variant="ghost"
                    />
                </DropdownMenuTrigger>

                <DropdownMenuContent align="end">
                    <DropdownMenuItem onSelect={() => setShowEditDialog(true)}>
                        <PencilIcon className="mr-2 size-4" /> Edit
                    </DropdownMenuItem>

                    <DropdownMenuSeparator />

                    {/* variant rather than a colour class: the item's own muted-svg rule wins over one, leaving
                        the icon grey. */}

                    <DropdownMenuItem
                        disabled={deleteDataSyncMutation.isPending}
                        onSelect={() => deleteDataSyncMutation.mutate({id: dataSync.id})}
                        variant="destructive"
                    >
                        <Trash2Icon className="mr-2 size-4" /> Delete
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            {/* Controlled: the menu item that opens it unmounts on select, so the dialog cannot hang off a
                trigger inside the menu. */}

            <DataSyncDialog dataSync={dataSync} onOpenChange={setShowEditDialog} open={showEditDialog} />
        </>
    );
};

export default DataSyncsLeftSidebarDropdownMenu;

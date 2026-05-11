import {Input} from '@/components/Input/Input';
import PageLoader from '@/components/PageLoader';
import DataTableDropdownMenu from '@/pages/automation/datatables/components/DataTableDropdownMenu';
import DuplicateDataTableDialog from '@/pages/automation/datatables/components/DuplicateDataTableDialog';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';

import useDataTableLeftSidebar from '../hooks/useDataTableLeftSidebar';
import DeleteDataTableAlertDialog from './DeleteDataTableAlertDialog';
import RenameDataTableDialog from './RenameDataTableDialog';

interface Props {
    currentId?: string;
}

const DataTableLeftSidebar = ({currentId}: Props) => {
    const {error, filteredTables, handleSearchChange, isLoading, search} = useDataTableLeftSidebar();

    return (
        <div className="flex h-full flex-col">
            <div className="space-y-2 px-2 pt-0.5 pb-3">
                <Input
                    onChange={(event) => handleSearchChange(event.target.value)}
                    placeholder="Search tables..."
                    value={search}
                />
            </div>

            <div className="flex-1 overflow-y-auto">
                <PageLoader errors={[error]} loading={false}>
                    <LeftSidebarNav
                        body={
                            <>
                                {filteredTables.map((table) => {
                                    const active = currentId != null ? table.id === currentId : false;

                                    return (
                                        <div className="group relative flex items-center" key={table.id}>
                                            <LeftSidebarNavItem
                                                item={{current: active, name: table.baseName}}
                                                toLink={`/automation/datatables/${table.id}`}
                                            />

                                            <div className="absolute top-1/2 right-2 z-10 -translate-y-1/2">
                                                <DataTableDropdownMenu
                                                    baseName={table.baseName}
                                                    dataTableId={table.id}
                                                    triggerClassName="w-6 opacity-0 transition-opacity group-hover:opacity-100 data-[state=open]:opacity-100"
                                                    triggerSize="iconSm"
                                                />
                                            </div>
                                        </div>
                                    );
                                })}

                                {filteredTables.length === 0 && (
                                    <div className="px-2 py-2 text-sm text-muted-foreground">No tables found</div>
                                )}
                            </>
                        }
                        loading={isLoading}
                    />
                </PageLoader>
            </div>

            <DeleteDataTableAlertDialog />

            <DuplicateDataTableDialog />

            <RenameDataTableDialog />
        </div>
    );
};

export default DataTableLeftSidebar;

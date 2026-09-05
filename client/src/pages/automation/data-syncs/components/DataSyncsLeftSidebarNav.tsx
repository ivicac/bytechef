import {Input} from '@/components/Input/Input';
import useDataSyncs from '@/pages/automation/data-syncs/hooks/useDataSyncs';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {useDataSyncTagsQuery} from '@/shared/middleware/graphql';
import {TagIcon} from 'lucide-react';
import {useMemo, useState} from 'react';

interface DataSyncsLeftSidebarNavProps {
    currentDataSyncId?: string;
    currentTagId?: string | null;
    /**
     * List-page mode. The data syncs become tag-style filters rather than links to each detail page, and a
     * Tags group is added. The detail page keeps the plain navigation behaviour, where a tag filter would
     * have nothing to filter.
     */
    filterMode?: boolean;
}

/**
 * The workspace's data syncs, as the Data Syncs pages' left sidebar — the same shape the Agents pages'
 * sidebar uses, so switching between data syncs never needs a trip back to the list.
 */
const DataSyncsLeftSidebarNav = ({
    currentDataSyncId,
    currentTagId,
    filterMode = false,
}: DataSyncsLeftSidebarNavProps) => {
    const [search, setSearch] = useState('');

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const {dataSyncs, dataSyncsIsLoading} = useDataSyncs();

    const {data: dataSyncTagsData, isLoading: tagsIsLoading} = useDataSyncTagsQuery(
        {workspaceId: String(currentWorkspaceId)},
        {enabled: filterMode && currentWorkspaceId != null}
    );

    const tags = dataSyncTagsData?.dataSyncTags ?? [];

    // Only the detail page's navigation list is searchable, matching the agents sidebar: on the list page
    // this sidebar is a filter, and the data syncs themselves are already on screen to search through.
    const filteredDataSyncs = useMemo(() => {
        const query = search.trim().toLowerCase();

        if (filterMode || !query) {
            return dataSyncs;
        }

        return dataSyncs.filter((dataSync) => dataSync.title.toLowerCase().includes(query));
    }, [dataSyncs, filterMode, search]);

    return (
        <>
            {!filterMode && (
                <div className="space-y-2 px-3 pt-0.5 pb-3">
                    <Input
                        onChange={(event) => setSearch(event.target.value)}
                        placeholder="Search data syncs..."
                        value={search}
                    />
                </div>
            )}

            <LeftSidebarNav
                body={
                    <>
                        {filterMode && (
                            <LeftSidebarNavItem
                                item={{
                                    current: !currentDataSyncId && !currentTagId,
                                    id: 'all-data-syncs',
                                    name: 'All Data Syncs',
                                }}
                                toLink=""
                            />
                        )}

                        {filteredDataSyncs.length ? (
                            filteredDataSyncs.map((dataSync) => (
                                <LeftSidebarNavItem
                                    item={{
                                        current: dataSync.id === currentDataSyncId,
                                        id: dataSync.id,
                                        name: dataSync.title,
                                    }}
                                    key={dataSync.id}
                                    toLink={
                                        filterMode
                                            ? `?dataSyncId=${dataSync.id}`
                                            : `/automation/data-syncs/${dataSync.id}`
                                    }
                                />
                            ))
                        ) : (
                            <span className="px-3 text-xs">
                                {search ? 'No data syncs found.' : 'No data syncs yet.'}
                            </span>
                        )}
                    </>
                }
                loading={dataSyncsIsLoading}
                title={filterMode ? 'Data Syncs' : undefined}
            />

            {filterMode && (
                <LeftSidebarNav
                    body={
                        tags.length ? (
                            tags.map((tag) => (
                                <LeftSidebarNavItem
                                    icon={<TagIcon className="mr-1 size-4" />}
                                    item={{
                                        current: tag.id === currentTagId,
                                        id: tag.id,
                                        name: tag.name,
                                    }}
                                    key={tag.id}
                                    toLink={`?tagId=${tag.id}`}
                                />
                            ))
                        ) : (
                            <span className="px-3 text-xs">No defined tags.</span>
                        )
                    }
                    className="mb-0"
                    loading={tagsIsLoading}
                    title="Tags"
                />
            )}
        </>
    );
};

export default DataSyncsLeftSidebarNav;

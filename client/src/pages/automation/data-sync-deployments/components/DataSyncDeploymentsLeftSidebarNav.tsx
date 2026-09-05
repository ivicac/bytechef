import useDataSyncs from '@/pages/automation/data-syncs/hooks/useDataSyncs';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {useDataSyncDeploymentTagsQuery} from '@/shared/middleware/graphql';
import {TagIcon} from 'lucide-react';

interface DataSyncDeploymentsLeftSidebarNavProps {
    currentDataSyncId?: string | null;
    currentTagId?: string | null;
}

/**
 * Filters the deployment list by data sync or by tag, the way the Projects sidebar does. Both selections
 * ride in search params (`dataSyncId` / `tagId`). The two filters are mutually exclusive: picking one
 * clears the other, since a tag selects a SET of data syncs and an explicit data sync selection would be a
 * narrower, confusing overlay.
 */
const DataSyncDeploymentsLeftSidebarNav = ({
    currentDataSyncId,
    currentTagId,
}: DataSyncDeploymentsLeftSidebarNavProps) => {
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const {dataSyncs, dataSyncsIsLoading} = useDataSyncs();

    // The DEPLOYMENTS' own tags, not the owning data syncs'. This list drives the tag filter below, and a
    // deployment is filtered by the tags it carries — listing data sync tags here offered filters that
    // matched nothing and hid deployment tags that do.
    const {data: dataSyncDeploymentTagsData, isLoading: tagsIsLoading} = useDataSyncDeploymentTagsQuery(
        {workspaceId: String(currentWorkspaceId)},
        {enabled: currentWorkspaceId != null}
    );

    const tags = dataSyncDeploymentTagsData?.dataSyncDeploymentTags ?? [];

    return (
        <>
            <LeftSidebarNav
                body={
                    <>
                        <LeftSidebarNavItem
                            item={{
                                current: !currentDataSyncId && !currentTagId,
                                id: 'all-data-syncs',
                                name: 'All Data Syncs',
                            }}
                            toLink=""
                        />

                        {dataSyncs.map((dataSync) => (
                            <LeftSidebarNavItem
                                item={{
                                    current: dataSync.id === currentDataSyncId,
                                    id: dataSync.id,
                                    name: dataSync.title,
                                }}
                                key={dataSync.id}
                                toLink={`?dataSyncId=${dataSync.id}`}
                            />
                        ))}
                    </>
                }
                loading={dataSyncsIsLoading}
                title="Data Syncs"
            />

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
        </>
    );
};

export default DataSyncDeploymentsLeftSidebarNav;

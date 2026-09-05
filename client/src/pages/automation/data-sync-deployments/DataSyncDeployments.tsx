import Button from '@/components/Button/Button';
import EmptyFilterResult from '@/components/EmptyFilterResult';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import DataSyncDeploymentListItem from '@/pages/automation/data-sync-deployments/components/DataSyncDeploymentListItem';
import DataSyncDeploymentsLeftSidebarNav from '@/pages/automation/data-sync-deployments/components/DataSyncDeploymentsLeftSidebarNav';
import useDataSyncDeployments from '@/pages/automation/data-sync-deployments/hooks/useDataSyncDeployments';
import DataSyncsFilterTitle from '@/pages/automation/data-syncs/components/DataSyncsFilterTitle';
import useDataSyncs from '@/pages/automation/data-syncs/hooks/useDataSyncs';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {ProjectDeployment} from '@/shared/middleware/automation/configuration';
import {DataSync, useDataSyncDeploymentTagsQuery} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useQueryClient} from '@tanstack/react-query';
import {RefreshCwIcon} from 'lucide-react';
import {useMemo, useState} from 'react';
import {useSearchParams} from 'react-router-dom';

const DataSyncDeployments = () => {
    const [showCreateDialog, setShowCreateDialog] = useState(false);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const queryClient = useQueryClient();

    const [searchParams] = useSearchParams();

    const dataSyncIdFilter = searchParams.get('dataSyncId');
    const tagIdFilter = searchParams.get('tagId');

    const {dataSyncDeployments, dataSyncDeploymentsError, dataSyncDeploymentsIsLoading} = useDataSyncDeployments();
    const {dataSyncs, dataSyncsError, dataSyncsIsLoading} = useDataSyncs();

    // Only published syncs (lastPublishedVersion > 0) have a workflow a ProjectDeployment can reference.
    const publishedDataSyncs = useMemo(
        () => dataSyncs.filter((dataSync) => dataSync.lastPublishedVersion > 0) as DataSync[],
        [dataSyncs]
    );

    // A deployment is filtered by its OWN tags. The two filters (data sync / tag) are mutually exclusive —
    // picking one clears the other, exactly as the Data Syncs list itself does.
    const filteredDeployments = useMemo(() => {
        if (dataSyncIdFilter) {
            return dataSyncDeployments.filter((deployment) => deployment.dataSyncId === dataSyncIdFilter);
        }

        if (tagIdFilter) {
            return dataSyncDeployments.filter((deployment) =>
                (deployment.tags ?? []).some((tag) => tag?.id === tagIdFilter)
            );
        }

        return dataSyncDeployments;
    }, [dataSyncDeployments, dataSyncIdFilter, tagIdFilter]);

    // Fetched once for the whole list rather than per row: each row filters out what it already carries.
    const {data: deploymentTagsData} = useDataSyncDeploymentTagsQuery(
        {workspaceId: String(currentWorkspaceId)},
        {enabled: currentWorkspaceId != null}
    );

    const remainingTags = useMemo(
        () => (deploymentTagsData?.dataSyncDeploymentTags ?? []).map((tag) => ({id: Number(tag.id), name: tag.name})),
        [deploymentTagsData?.dataSyncDeploymentTags]
    );

    const filterDataSyncTitle = dataSyncIdFilter
        ? dataSyncs.find((dataSync) => dataSync.id === dataSyncIdFilter)?.title
        : undefined;

    const filterTagName = tagIdFilter
        ? dataSyncDeployments.flatMap((deployment) => deployment.tags ?? []).find((tag) => tag?.id === tagIdFilter)
              ?.name
        : undefined;

    // The dialog picks the data sync itself, so the page only needs the deployable set. A data sync
    // deployment is a ProjectDeployment of the sync's hidden backing project, hence projectId +
    // lastPublishedVersion.
    const dataSyncOptions = useMemo(
        () =>
            publishedDataSyncs.map((dataSync) => ({
                id: dataSync.id,
                lastPublishedVersion: dataSync.lastPublishedVersion,
                projectId: dataSync.projectId,
                title: dataSync.title,
            })),
        [publishedDataSyncs]
    );

    const handleCreateDialogClose = () => {
        setShowCreateDialog(false);
    };

    // The dialog invalidates the REST projectDeployments keys it owns; this list is the dataSyncDeployments
    // GraphQL query, which those keys do not reach, so a new deployment would not appear until a reload.
    const handleCreateDialogSuccess = () => {
        queryClient.invalidateQueries({queryKey: ['dataSyncDeployments']});
    };

    return (
        <LayoutContainer
            header={
                <Header
                    centerTitle={true}
                    position="main"
                    right={
                        filteredDeployments.length > 0 &&
                        publishedDataSyncs.length > 0 && (
                            <Button label="New Deployment" onClick={() => setShowCreateDialog(true)} />
                        )
                    }
                    title={
                        filteredDeployments.length > 0 ? (
                            <DataSyncsFilterTitle dataSyncName={filterDataSyncTitle} tagName={filterTagName} />
                        ) : (
                            ''
                        )
                    }
                />
            }
            leftSidebarBody={
                <DataSyncDeploymentsLeftSidebarNav currentDataSyncId={dataSyncIdFilter} currentTagId={tagIdFilter} />
            }
            leftSidebarHeader={<Header position="sidebar" title="Data Sync Deployments" />}
            leftSidebarWidth="64"
        >
            <PageLoader
                errors={[dataSyncDeploymentsError, dataSyncsError]}
                loading={dataSyncDeploymentsIsLoading || dataSyncsIsLoading}
            >
                {filteredDeployments.length > 0 ? (
                    <div className="w-full px-4 3xl:mx-auto 3xl:w-4/5">
                        {filteredDeployments.map((deployment) => (
                            <DataSyncDeploymentListItem
                                deployment={deployment}
                                key={deployment.id}
                                remainingTags={remainingTags}
                            />
                        ))}
                    </div>
                ) : dataSyncDeployments.length > 0 ? (
                    <EmptyFilterResult entityName="data sync deployments" entityTitle="Data Sync Deployments" />
                ) : (
                    <EmptyList
                        button={
                            publishedDataSyncs.length > 0 ? (
                                <Button label="New Deployment" onClick={() => setShowCreateDialog(true)} />
                            ) : undefined
                        }
                        icon={<RefreshCwIcon className="size-24 text-stroke-neutral-tertiary" />}
                        message={
                            publishedDataSyncs.length > 0
                                ? 'Get started by deploying a published data sync.'
                                : 'Publish a data sync first, then deploy it here.'
                        }
                        title="No Data Sync Deployments"
                    />
                )}
            </PageLoader>

            {showCreateDialog && (
                <ProjectDeploymentDialog
                    agentOptions={dataSyncOptions}
                    agentOptionsLabel="Data Sync"
                    onClose={handleCreateDialogClose}
                    onSuccess={handleCreateDialogSuccess}
                    projectDeployment={{environmentId: currentEnvironmentId} as ProjectDeployment}
                    redirectOnSubmit={false}
                />
            )}
        </LayoutContainer>
    );
};

export default DataSyncDeployments;

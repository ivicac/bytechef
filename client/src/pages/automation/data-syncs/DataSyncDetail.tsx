import Button from '@/components/Button/Button';
import PageLoader from '@/components/PageLoader';
import DataSyncDialog from '@/pages/automation/data-syncs/components/DataSyncDialog';
import DataSyncsLeftSidebarNav from '@/pages/automation/data-syncs/components/DataSyncsLeftSidebarNav';
import DataSyncDetailHeader from '@/pages/automation/data-syncs/components/detail/DataSyncDetailHeader';
import DataSyncWizard from '@/pages/automation/data-syncs/components/wizard/DataSyncWizard';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {DataSync, useDataSyncQuery} from '@/shared/middleware/graphql';
import {PlusIcon} from 'lucide-react';
import {useParams} from 'react-router-dom';

const DataSyncDetail = () => {
    const {dataSyncId} = useParams<{dataSyncId: string}>();

    const {data, error, isLoading} = useDataSyncQuery({id: dataSyncId ?? ''}, {enabled: !!dataSyncId});

    const dataSync = data?.dataSync;

    return (
        <LayoutContainer
            header={
                dataSync && (
                    <DataSyncDetailHeader
                        description={dataSync.description}
                        id={dataSync.id}
                        lastPublishedVersion={dataSync.lastPublishedVersion}
                        projectId={dataSync.projectId}
                        title={dataSync.title}
                    />
                )
            }
            leftSidebarBody={<DataSyncsLeftSidebarNav currentDataSyncId={dataSyncId} />}
            leftSidebarHeader={
                <Header
                    position="sidebar"
                    right={<DataSyncDialog triggerNode={<Button icon={<PlusIcon />} size="icon" variant="ghost" />} />}
                    title="Data Syncs"
                />
            }
            leftSidebarWidth="64"
        >
            <PageLoader errors={[error]} loading={isLoading}>
                {/* Keyed by id: the wizard keeps its own current-step state, which must not survive a
                    route-param-only navigation to a different sync. */}

                {dataSync && <DataSyncWizard dataSync={dataSync as DataSync} key={dataSync.id} />}
            </PageLoader>
        </LayoutContainer>
    );
};

export default DataSyncDetail;

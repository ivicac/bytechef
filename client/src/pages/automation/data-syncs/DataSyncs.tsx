import Button from '@/components/Button/Button';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import DataSyncDialog from '@/pages/automation/data-syncs/components/DataSyncDialog';
import DataSyncsFilterTitle from '@/pages/automation/data-syncs/components/DataSyncsFilterTitle';
import DataSyncsLeftSidebarNav from '@/pages/automation/data-syncs/components/DataSyncsLeftSidebarNav';
import DataSyncList from '@/pages/automation/data-syncs/components/data-sync-list/DataSyncList';
import useDataSyncs from '@/pages/automation/data-syncs/hooks/useDataSyncs';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {DataSync} from '@/shared/middleware/graphql';
import {ArrowLeftRightIcon} from 'lucide-react';
import {useMemo} from 'react';
import {useSearchParams} from 'react-router-dom';

const DataSyncs = () => {
    const [searchParams] = useSearchParams();

    const dataSyncIdFilter = searchParams.get('dataSyncId');
    const tagIdFilter = searchParams.get('tagId');

    const {dataSyncs, dataSyncsError, dataSyncsIsLoading} = useDataSyncs();

    // A tag selects a SET of data syncs; an explicit sync selection is narrower and wins outright rather
    // than intersecting, so picking one clears the other. Each sidebar item links to a single search param,
    // so only a hand-written URL can set two at once — and then the first branch that matches wins rather
    // than the filters combining.
    const filteredDataSyncs = useMemo(() => {
        if (dataSyncIdFilter) {
            return dataSyncs.filter((dataSync) => dataSync.id === dataSyncIdFilter);
        }

        if (tagIdFilter) {
            return dataSyncs.filter((dataSync) => (dataSync.tags ?? []).some((tag) => tag?.id === tagIdFilter));
        }

        return dataSyncs;
    }, [dataSyncs, dataSyncIdFilter, tagIdFilter]);

    const filterDataSyncName = dataSyncIdFilter
        ? dataSyncs.find((dataSync) => dataSync.id === dataSyncIdFilter)?.title
        : undefined;

    // Resolved from the loaded data syncs rather than a separate tag query: a tag is only selectable in the
    // sidebar because some data sync carries it, so it is always present here.
    const filterTagName = tagIdFilter
        ? dataSyncs.flatMap((dataSync) => dataSync.tags ?? []).find((tag) => tag?.id === tagIdFilter)?.name
        : undefined;

    return (
        <LayoutContainer
            header={
                <Header
                    centerTitle={true}
                    position="main"
                    right={dataSyncs.length > 0 && <DataSyncDialog triggerNode={<Button label="New Data Sync" />} />}
                    title={
                        dataSyncs.length > 0 ? (
                            <DataSyncsFilterTitle dataSyncName={filterDataSyncName} tagName={filterTagName} />
                        ) : (
                            ''
                        )
                    }
                />
            }
            leftSidebarBody={
                <DataSyncsLeftSidebarNav
                    currentDataSyncId={dataSyncIdFilter ?? undefined}
                    currentTagId={tagIdFilter}
                    filterMode
                />
            }
            leftSidebarHeader={<Header position="sidebar" title="Data Syncs" />}
            leftSidebarWidth="64"
        >
            <PageLoader errors={[dataSyncsError]} loading={dataSyncsIsLoading}>
                {filteredDataSyncs.length > 0 ? (
                    <DataSyncList dataSyncs={filteredDataSyncs as DataSync[]} />
                ) : (
                    // A filter that matches nothing is not the same as an empty workspace — offering
                    // "create a data sync" there would be answering a question the user did not ask.
                    <EmptyList
                        button={
                            dataSyncs.length > 0 ? undefined : (
                                <DataSyncDialog triggerNode={<Button label="Create Data Sync" />} />
                            )
                        }
                        icon={<ArrowLeftRightIcon className="size-24 text-stroke-neutral-tertiary" />}
                        message={
                            dataSyncs.length > 0
                                ? 'No data syncs match the current filter.'
                                : 'Get started by creating a new data sync.'
                        }
                        title="No Data Syncs"
                    />
                )}
            </PageLoader>
        </LayoutContainer>
    );
};

export default DataSyncs;

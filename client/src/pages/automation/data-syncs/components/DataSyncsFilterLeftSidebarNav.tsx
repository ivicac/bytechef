import LeftSidebarFilterNav from '@/shared/layout/LeftSidebarFilterNav';
import {useSearchParams} from 'react-router-dom';

export type DataSyncsFilterType = 'all' | 'scheduled';

const DATA_SYNCS_FILTER_ITEMS: {id: DataSyncsFilterType; name: string}[] = [
    {id: 'all', name: 'All Data Syncs'},
    {id: 'scheduled', name: 'Scheduled'},
];

/** Reads the `?dataSyncs=all|scheduled` search param, ignoring any other value. */
export const getDataSyncsFilter = (searchParams: URLSearchParams): DataSyncsFilterType | undefined => {
    const dataSyncsSearchParam = searchParams.get('dataSyncs');

    return dataSyncsSearchParam === 'all' || dataSyncsSearchParam === 'scheduled' ? dataSyncsSearchParam : undefined;
};

interface DataSyncsFilterLeftSidebarNavProps {
    currentDataSyncsFilter?: DataSyncsFilterType;
}

/**
 * The Data Syncs group of the Projects page sidebar: narrows the list to projects that hold a data sync, or a
 * scheduled one. It combines with the page's other filters, so each link keeps the other search params, and
 * clicking the active item again clears it. It cancels the Agents filter — the two decide which tab every
 * project row opens on, so only one can be active at a time.
 */
const DataSyncsFilterLeftSidebarNav = ({currentDataSyncsFilter}: DataSyncsFilterLeftSidebarNavProps) => {
    const [searchParams] = useSearchParams();

    const getToLink = (dataSyncsFilter: DataSyncsFilterType) => {
        const nextSearchParams = new URLSearchParams(searchParams);

        nextSearchParams.delete('agents');

        if (dataSyncsFilter === currentDataSyncsFilter) {
            nextSearchParams.delete('dataSyncs');
        } else {
            nextSearchParams.set('dataSyncs', dataSyncsFilter);
        }

        const search = nextSearchParams.toString();

        return search ? `?${search}` : '';
    };

    return (
        <LeftSidebarFilterNav
            items={DATA_SYNCS_FILTER_ITEMS.map((item) => ({
                current: item.id === currentDataSyncsFilter,
                id: item.id,
                name: item.name,
                toLink: getToLink(item.id),
            }))}
            title="Data Syncs"
        />
    );
};

export default DataSyncsFilterLeftSidebarNav;

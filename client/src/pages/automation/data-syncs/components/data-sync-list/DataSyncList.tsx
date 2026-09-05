import {DataSync} from '@/shared/middleware/graphql';

import DataSyncListItem from './DataSyncListItem';

interface DataSyncListProps {
    dataSyncs: DataSync[];
}

const DataSyncList = ({dataSyncs}: DataSyncListProps) => (
    <div className="w-full space-y-2 px-4 3xl:mx-auto 3xl:w-4/5">
        {dataSyncs.map((dataSync) => (
            <DataSyncListItem dataSync={dataSync} key={dataSync.id} />
        ))}
    </div>
);

export default DataSyncList;

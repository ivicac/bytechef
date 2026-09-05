import {DataSync, DataSyncElementKind} from '@/shared/middleware/graphql';

interface DataSyncElementStepProps {
    dataSync: DataSync;
    kind: DataSyncElementKind;
}

/**
 * Placeholder for the Source/Destination step. Task 10 replaces this body; the wizard already passes the
 * real props, so this file will not need to change again when that lands.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export default function DataSyncElementStep({dataSync, kind}: DataSyncElementStepProps) {
    return null;
}

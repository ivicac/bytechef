import {DataSyncElementKind} from '@/shared/middleware/graphql';

interface ElementLikeI {
    kind: DataSyncElementKind;
}

interface DataSyncLikeI<T extends ElementLikeI> {
    elements?: Array<T | null> | null;
}

export function findElement<T extends ElementLikeI>(
    dataSync: DataSyncLikeI<T>,
    kind: DataSyncElementKind
): T | undefined {
    return (dataSync.elements ?? []).find((element): element is T => element != null && element.kind === kind);
}

/**
 * The lowercase key the generated workflow uses for this slot — e.g. the node name `source_1` (see
 * {@link elementNodeName}). This is NOT the `clusterElementsCount` key: that map is keyed by the cluster
 * element type's UPPERCASE name (`SOURCE` / `DESTINATION`), reachable via
 * `convertNameToSnakeCase(elementKindKey(kind))`. A filter written against this function's own lowercase
 * output would silently match nothing and yield an empty picker with no error.
 */
export function elementKindKey(kind: DataSyncElementKind): 'destination' | 'processor' | 'source' {
    if (kind === DataSyncElementKind.Source) {
        return 'source';
    }

    if (kind === DataSyncElementKind.Destination) {
        return 'destination';
    }

    return 'processor';
}

export const PROCESSOR_COMPONENT_NAME = 'dataStreamProcessor';
export const PROCESSOR_COMPONENT_VERSION = 1;
export const PROCESSOR_OPERATION_NAME = 'fieldMapper';

export const DATA_SYNC_TASK_NODE_NAME = 'dataStream_1';

export function elementNodeName(kind: DataSyncElementKind): string {
    return `${elementKindKey(kind)}_1`;
}

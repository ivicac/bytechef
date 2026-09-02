import useClusterElementsViewModeStore, {ClusterElementsViewModeType} from '../stores/useClusterElementsViewModeStore';

/**
 * The cluster elements view mode in force.
 *
 * Every consumer reads the mode through here rather than reaching into the store directly, so the
 * question "which mode applies?" is answered in exactly one place.
 */
export default function useClusterElementsViewMode(): ClusterElementsViewModeType {
    return useClusterElementsViewModeStore((state) => state.clusterElementsViewMode);
}

/** The non-reactive twin of `useClusterElementsViewMode`, for event handlers. */
export function getClusterElementsViewMode(): ClusterElementsViewModeType {
    return useClusterElementsViewModeStore.getState().clusterElementsViewMode;
}

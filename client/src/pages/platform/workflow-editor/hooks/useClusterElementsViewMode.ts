import {applicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {featureFlagsStore, useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';

import useClusterElementsViewModeStore, {ClusterElementsViewModeType} from '../stores/useClusterElementsViewModeStore';

export const CLUSTER_ELEMENTS_BOX_MODE_FEATURE_FLAG = 'ff-5470';

/**
 * Reads the flag the way `useFeatureFlagsStore` does, but outside React.
 *
 * Deliberately does NOT kick off the PostHog fetch the hook does: this is only ever called from a
 * click handler on a canvas whose toolbar has already rendered `useClusterElementsViewMode`, so the
 * flag is either resolved or still resolving — and while it is still resolving, dialog mode (the
 * pre-flag behaviour) is the right answer anyway.
 */
function isBoxModeFeatureFlagEnabled(): boolean {
    const localFeatureFlags = applicationInfoStore.getState().featureFlags;

    if (localFeatureFlags[CLUSTER_ELEMENTS_BOX_MODE_FEATURE_FLAG] !== undefined) {
        return localFeatureFlags[CLUSTER_ELEMENTS_BOX_MODE_FEATURE_FLAG];
    }

    return featureFlagsStore.getState().featureFlags[CLUSTER_ELEMENTS_BOX_MODE_FEATURE_FLAG] ?? false;
}

/**
 * The cluster elements view mode ACTUALLY in force, as opposed to the one the user last picked.
 *
 * The stored mode is persisted to `localStorage`, so a user who tried box mode while the flag was on
 * would keep it forever once the flag was turned off — with the toolbar toggle gone, and no way back.
 * That is not a kill switch. Every consumer reads the mode through here instead of through the store
 * directly, so turning `ff-5470` off returns everyone to dialog mode regardless of what they last
 * chose, and turning it back on restores their choice.
 *
 * `WorkflowEditorToolbar` is the one place that still reads the flag on its own — it decides whether
 * to SHOW the toggle, which is a different question from which mode is in force.
 */
export default function useClusterElementsViewMode(): ClusterElementsViewModeType {
    const clusterElementsViewMode = useClusterElementsViewModeStore((state) => state.clusterElementsViewMode);

    const ff_5470 = useFeatureFlagsStore()(CLUSTER_ELEMENTS_BOX_MODE_FEATURE_FLAG);

    return ff_5470 ? clusterElementsViewMode : 'dialog';
}

/** The non-reactive twin of `useClusterElementsViewMode`, for event handlers. */
export function getClusterElementsViewMode(): ClusterElementsViewModeType {
    return isBoxModeFeatureFlagEnabled()
        ? useClusterElementsViewModeStore.getState().clusterElementsViewMode
        : 'dialog';
}

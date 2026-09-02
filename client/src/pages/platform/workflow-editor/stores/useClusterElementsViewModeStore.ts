import {create} from 'zustand';
import {devtools, persist} from 'zustand/middleware';

export type ClusterElementsViewModeType = 'box' | 'dialog';

interface ClusterElementsViewModeStateI {
    clusterElementsViewMode: ClusterElementsViewModeType;
    setClusterElementsViewMode: (clusterElementsViewMode: ClusterElementsViewModeType) => void;
}

/**
 * How cluster roots render on the main canvas. 'box', the default, renders the root as an
 * auto-sizing container with its elements inline, and opens the ordinary node details panel. 'dialog'
 * draws the root as one node, and opening it raises the full-screen cluster editor.
 *
 * Per user rather than per workflow, and deliberately not written to the workflow definition — two
 * people may view the same workflow differently, and toggling must never mark it dirty.
 */
const useClusterElementsViewModeStore = create<ClusterElementsViewModeStateI>()(
    devtools(
        persist(
            (set) => ({
                clusterElementsViewMode: 'box' as ClusterElementsViewModeType,

                setClusterElementsViewMode: (clusterElementsViewMode: ClusterElementsViewModeType) =>
                    set({clusterElementsViewMode}),
            }),
            {
                name: 'bytechef.cluster-elements-view-mode',
            }
        )
    )
);

export default useClusterElementsViewModeStore;

import {create} from 'zustand';
import {devtools, persist} from 'zustand/middleware';

interface ClusterFrameCollapsedStateI {
    collapsedByWorkflowId: Record<string, Record<string, true>>;
    setClusterFrameCollapsed: (workflowId: string, clusterRootId: string, collapsed: boolean) => void;
}

/**
 * Which cluster roots are drawn folded shut, per workflow.
 *
 * A collapsed root skips box mode and falls back to the compact card the dialog mode always drew, so
 * one agent can be put away without flipping the whole canvas out of box mode. Inert in dialog mode,
 * where every root is compact already.
 *
 * Keyed by workflow id and NOT by node name alone: names like `aiAgent_1` repeat across workflows, so
 * a flat map would open every other workflow with this one's boxes shut — and because this store
 * persists, that would outlive the session.
 *
 * Only collapsed roots are stored, and a workflow with none is dropped: a `false` per root per
 * workflow would grow this key without bound for a preference almost nobody sets.
 *
 * Per user rather than per workflow definition, for the reason the view mode is
 * (`useClusterElementsViewModeStore`): two people may view one workflow differently, and folding a box
 * must never mark it dirty.
 */
const useClusterFrameCollapsedStore = create<ClusterFrameCollapsedStateI>()(
    devtools(
        persist(
            (set) => ({
                collapsedByWorkflowId: {},

                setClusterFrameCollapsed: (workflowId: string, clusterRootId: string, collapsed: boolean) =>
                    set((state) => {
                        const collapsedByWorkflowId = {...state.collapsedByWorkflowId};

                        if (collapsed) {
                            collapsedByWorkflowId[workflowId] = {
                                ...collapsedByWorkflowId[workflowId],
                                [clusterRootId]: true,
                            };

                            return {collapsedByWorkflowId};
                        }

                        const collapsedRootIds = {...collapsedByWorkflowId[workflowId]};

                        delete collapsedRootIds[clusterRootId];

                        if (Object.keys(collapsedRootIds).length) {
                            collapsedByWorkflowId[workflowId] = collapsedRootIds;
                        } else {
                            delete collapsedByWorkflowId[workflowId];
                        }

                        return {collapsedByWorkflowId};
                    }),
            }),
            {
                name: 'bytechef.cluster-frame-collapsed',
            }
        )
    )
);

export default useClusterFrameCollapsedStore;

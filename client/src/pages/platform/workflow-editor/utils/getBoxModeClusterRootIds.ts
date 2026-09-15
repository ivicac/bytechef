import {WorkflowTask, WorkflowTrigger} from '@/shared/middleware/platform/configuration';

interface GetBoxModeClusterRootIdsProps {
    clusterElementsViewMode: string;
    collapsedClusterRootIds?: Record<string, boolean>;
    tasks?: Array<WorkflowTask>;
    triggers?: Array<WorkflowTrigger>;
}

/**
 * The cluster roots the main canvas draws as boxes: every trigger and task the server marks
 * `clusterRoot`, minus the ones folded back to their compact card.
 *
 * Keyed on the server-computed `clusterRoot` flag — the same one that types the node — and NOT on
 * `clusterElements` being present: a task dispatcher's DTO carries that field too (an empty object is
 * truthy), so a Condition added after a box was treated as a root and had its component definition
 * fetched, which no task dispatcher has. Triggers come first, matching their place on the canvas.
 */
export default function getBoxModeClusterRootIds({
    clusterElementsViewMode,
    collapsedClusterRootIds,
    tasks,
    triggers,
}: GetBoxModeClusterRootIdsProps): Array<string> {
    if (clusterElementsViewMode !== 'box') {
        return [];
    }

    return [...(triggers ?? []), ...(tasks ?? [])]
        .filter((workflowNode) => workflowNode.clusterRoot && !collapsedClusterRootIds?.[workflowNode.name])
        .map((workflowNode) => workflowNode.name);
}

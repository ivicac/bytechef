import {
    CLUSTER_ELEMENT_NODE_WIDTH,
    FINAL_PLACEHOLDER_NODE_ID,
    LayoutDirectionType,
    NODE_WIDTH,
    PLACEHOLDER_NODE_HEIGHT,
} from '@/shared/constants';
import {WorkflowTask} from '@/shared/middleware/platform/configuration';
import {Node} from '@xyflow/react';

interface PlaceAppendedTaskAfterPinnedPredecessorProps {
    canvasNodes: Array<Node>;
    crossAxisShift: number;
    direction: LayoutDirectionType;
    tasks: Array<WorkflowTask>;
}

/**
 * The rendered width of the trailing "+" chip: the 28px button plus the 22px margin on each side
 * that PlaceholderNode gives it.
 */
const PLACEHOLDER_RENDERED_WIDTH = 72;

/**
 * Pins the task appended at the end of `tasks` where the end-of-chain "+" chip was drawn, when the
 * task before it carries a saved position; returns the list unchanged otherwise.
 *
 * The chip rides with a pinned last node (applySavedPositions carries it by that node's delta), so
 * it is drawn where the user expects the chain to continue — but the appended task itself has no
 * successor to take a midpoint against, and the layout engine would put it back on the automatic
 * chain line. Taking the chip's own canvas position, main-axis start and cross-axis chain line
 * alike, lands the task exactly where the chip was, in either direction and whatever the
 * predecessor's size.
 */
export default function placeAppendedTaskAfterPinnedPredecessor({
    canvasNodes,
    crossAxisShift,
    direction,
    tasks,
}: PlaceAppendedTaskAfterPinnedPredecessorProps): Array<WorkflowTask> {
    if (tasks.length < 2) {
        return tasks;
    }

    const predecessorPosition = tasks[tasks.length - 2].metadata?.ui?.nodePosition;

    if (
        !predecessorPosition ||
        typeof predecessorPosition.x !== 'number' ||
        typeof predecessorPosition.y !== 'number'
    ) {
        return tasks;
    }

    const trailingPlaceholderNode = canvasNodes.find((node) => node.id === FINAL_PLACEHOLDER_NODE_ID && !node.parentId);

    if (!trailingPlaceholderNode) {
        return tasks;
    }

    const placeholderPosition = trailingPlaceholderNode.position;

    let nodePosition: {x: number; y: number};

    if (direction === 'LR') {
        const placeholderHeight = trailingPlaceholderNode.measured?.height ?? PLACEHOLDER_NODE_HEIGHT;
        const chainLineY = placeholderPosition.y + placeholderHeight / 2;

        nodePosition = {
            x: placeholderPosition.x,
            y: chainLineY - CLUSTER_ELEMENT_NODE_WIDTH / 2 - crossAxisShift,
        };
    } else {
        const placeholderWidth = trailingPlaceholderNode.measured?.width ?? PLACEHOLDER_RENDERED_WIDTH;
        const chainLineX = placeholderPosition.x + placeholderWidth / 2;

        nodePosition = {
            x: chainLineX - NODE_WIDTH / 2 - crossAxisShift,
            y: placeholderPosition.y,
        };
    }

    const appendedTask = tasks[tasks.length - 1];

    const placedTask: WorkflowTask = {
        ...appendedTask,
        metadata: {
            ...appendedTask.metadata,
            ui: {
                ...appendedTask.metadata?.ui,
                nodePosition,
            },
        },
    };

    return [...tasks.slice(0, -1), placedTask];
}

import {WorkflowTask} from '@/shared/middleware/platform/configuration';

type NodePositionType = {x: number; y: number};

function getPinnedPosition(task: WorkflowTask | undefined): NodePositionType | undefined {
    const nodePosition = task?.metadata?.ui?.nodePosition;

    if (!nodePosition || typeof nodePosition.x !== 'number' || typeof nodePosition.y !== 'number') {
        return undefined;
    }

    return nodePosition;
}

/**
 * Pins the task at `insertionIndex` at the midpoint of its two neighbours when both of them carry a
 * saved position, and returns the list unchanged otherwise.
 *
 * Neither layout engine places an unpinned node relative to hand-placed neighbours: ELK runs no
 * chain alignment at all, and dagre's deliberately stops at a manually placed predecessor. A node
 * dropped between two pinned ones would therefore jump to the engine's slot on the original chain
 * line. With only one neighbour pinned the automatic chain still runs through the other, so the
 * engine's slot beside it is the right place and the task stays unpinned.
 */
export default function placeInsertedTaskBetweenPinnedNeighbours(
    tasks: Array<WorkflowTask>,
    insertionIndex: number
): Array<WorkflowTask> {
    const predecessorPosition = getPinnedPosition(tasks[insertionIndex - 1]);
    const successorPosition = getPinnedPosition(tasks[insertionIndex + 1]);

    if (!predecessorPosition || !successorPosition) {
        return tasks;
    }

    const insertedTask = tasks[insertionIndex];

    const placedTask: WorkflowTask = {
        ...insertedTask,
        metadata: {
            ...insertedTask.metadata,
            ui: {
                ...insertedTask.metadata?.ui,
                nodePosition: {
                    x: (predecessorPosition.x + successorPosition.x) / 2,
                    y: (predecessorPosition.y + successorPosition.y) / 2,
                },
            },
        },
    };

    return [...tasks.slice(0, insertionIndex), placedTask, ...tasks.slice(insertionIndex + 1)];
}

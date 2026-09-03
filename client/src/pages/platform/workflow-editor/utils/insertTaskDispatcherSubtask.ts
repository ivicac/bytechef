import {WorkflowTask} from '@/shared/middleware/platform/configuration';
import {TaskDispatcherContextType} from '@/shared/types';

import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import getRecursivelyUpdatedTasks from './getRecursivelyUpdatedTasks';
import {applyGraphMemberInsertion} from './graph/graphMemberInsertion';
import placeInsertedTaskBetweenPinnedNeighbours from './placeInsertedTaskBetweenPinnedNeighbours';
import {TASK_DISPATCHER_CONFIG} from './taskDispatcherConfig';

interface InsertTaskDispatcherSubtaskProps {
    newTask: WorkflowTask;
    placeholderId?: string;
    taskDispatcherContext: TaskDispatcherContextType;
    tasks: Array<WorkflowTask>;
}

/**
 * Insert a new task into the task dispatcher subtask list.
 */
export default function insertTaskDispatcherSubtask({
    newTask,
    placeholderId,
    taskDispatcherContext,
    tasks,
}: InsertTaskDispatcherSubtaskProps): Array<WorkflowTask> {
    const taskDispatcherId = taskDispatcherContext.taskDispatcherId;

    const componentName = taskDispatcherId?.split('_')[0] as keyof typeof TASK_DISPATCHER_CONFIG;

    const config = TASK_DISPATCHER_CONFIG[componentName];

    if (!config) {
        console.error(`Unknown task dispatcher type: ${componentName}`);

        return tasks;
    }

    const {extractContextFromPlaceholder, getSubtasks, getTask, initializeParameters, updateTaskParameters} = config;

    let targetTaskDispatcher = tasks.find((task) => task.name === taskDispatcherId);

    if (!targetTaskDispatcher) {
        targetTaskDispatcher = getTask({taskDispatcherId, tasks});
    }

    if (!targetTaskDispatcher) {
        return tasks;
    }

    if (!targetTaskDispatcher.parameters) {
        targetTaskDispatcher.parameters = initializeParameters();
    }

    let context: TaskDispatcherContextType = {...taskDispatcherContext};

    if (placeholderId && context.index === 0) {
        if (componentName === 'parallel') {
            context = {
                ...context,
                index: targetTaskDispatcher.parameters?.tasks?.length,
            };
        } else if (componentName === 'each') {
            context = {
                ...context,
                index: 0,
            };
        } else {
            const placeholderContext = extractContextFromPlaceholder(placeholderId);

            context = {...context, ...placeholderContext};
        }
    }

    if (componentName === 'each') {
        const updatedTaskDispatcherTask = {
            ...targetTaskDispatcher,
            parameters: {
                ...targetTaskDispatcher.parameters,
                iteratee: newTask,
            },
        };

        return getRecursivelyUpdatedTasks(tasks, updatedTaskDispatcherTask);
    }

    const subtasks = getSubtasks({context, task: targetTaskDispatcher});

    let updatedSubtasks: Array<WorkflowTask>;

    // `graph` reaches this with no index by design — its add-node placeholder resolves none
    // (getTaskDispatcherContext.ts), so a member added from the frame header appends here on the
    // strength of the context alone, whether or not the caller forwarded a `placeholderId`. A
    // numeric index still means what it says: pasting onto a member inserts at that declaration
    // position.
    if (context.index === undefined || context.index === -1 || typeof context.index !== 'number') {
        updatedSubtasks = [...subtasks, newTask];
    } else {
        updatedSubtasks = [...subtasks];

        updatedSubtasks.splice(context.index, 0, newTask);

        // A graph member's position is the model rather than a pin, and graphMemberInsertion below
        // already places a new member; only chain dispatchers lay their subtasks out along a line.
        if (componentName !== 'graph') {
            updatedSubtasks = placeInsertedTaskBetweenPinnedNeighbours(updatedSubtasks, context.index);
        }
    }

    let updatedTaskDispatcherTask = updateTaskParameters({context, task: targetTaskDispatcher, updatedSubtasks});

    if (componentName === 'graph') {
        updatedTaskDispatcherTask = applyGraphMemberInsertion({
            nodes: useWorkflowDataStore.getState().nodes,
            pendingConnection: consumeGraphPendingConnection(targetTaskDispatcher.name),
            previousGraphTask: targetTaskDispatcher,
            updatedGraphTask: updatedTaskDispatcherTask,
        });
    }

    return getRecursivelyUpdatedTasks(tasks, updatedTaskDispatcherTask);
}

/**
 * Takes the pending connection raised for `graphId`, clearing it so the next add starts clean.
 *
 * A pending connection belongs to exactly one graph — a release over another graph's frame raised
 * its own — so one raised elsewhere is left in place rather than consumed here.
 */
function consumeGraphPendingConnection(graphId: string) {
    const {graphPendingConnection, setGraphPendingConnection} = useWorkflowEditorStore.getState();

    if (graphPendingConnection?.graphId !== graphId) {
        return undefined;
    }

    setGraphPendingConnection(undefined);

    return graphPendingConnection;
}

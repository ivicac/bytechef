import {WorkflowTask, WorkflowTrigger} from '@/shared/middleware/platform/configuration';

import {getTask} from './getTask';

interface GetClusterRootTaskProps {
    tasks?: Array<WorkflowTask>;
    triggers?: Array<WorkflowTrigger>;
    workflowNodeName: string;
}

/**
 * The workflow entry a cluster root name addresses: a task (top level or nested in a task dispatcher)
 * or a trigger. A trigger can be a cluster root too — `browser/v1/voiceSession` carries its Voice Agent
 * and Tools slots in `triggers[i].clusterElements` — so every place that locates a root by name has to
 * look past `tasks`, or a trigger's slots silently resolve to nothing.
 *
 * Tasks are searched first. Node names are unique across a workflow, so the order only matters for
 * speed. A trigger has the same shape as a task where cluster roots are concerned (`name`, `type`,
 * `parameters`, `clusterElements`, `connections`, `metadata`), which is why it is returned as one.
 * `saveWorkflowDefinition` recognises a root saved back under a trigger's name and writes it to `triggers`.
 */
export function getClusterRootTask({
    tasks,
    triggers,
    workflowNodeName,
}: GetClusterRootTaskProps): WorkflowTask | undefined {
    const task = getTask({tasks: tasks ?? [], workflowNodeName});

    if (task) {
        return task;
    }

    return (triggers ?? []).find((trigger) => trigger?.name === workflowNodeName) as WorkflowTask | undefined;
}

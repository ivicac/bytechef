import {WorkflowTask} from '@/shared/middleware/platform/configuration';
import {GraphTransitionType} from '@/shared/types';

import {findGraphMembersPrecedingMember} from './graph/graphReachability';
import {forEachNestedTaskGroup} from './taskTraversalUtils';

function visitTasks(
    tasks: Array<WorkflowTask>,
    inheritedTaskNames: Set<string>,
    graphTransitions: Array<GraphTransitionType> | undefined,
    availableTaskNamesByTaskName: Map<string, Set<string>>
): void {
    tasks.forEach((currentTask, index) => {
        const precedingGraphMemberNames = graphTransitions
            ? findGraphMembersPrecedingMember(currentTask.name, graphTransitions)
            : undefined;

        const availableTaskNames = new Set(inheritedTaskNames);

        for (const precedingTask of tasks.slice(0, index)) {
            if (!precedingGraphMemberNames || precedingGraphMemberNames.has(precedingTask.name)) {
                availableTaskNames.add(precedingTask.name);
            }
        }

        if (!availableTaskNamesByTaskName.has(currentTask.name)) {
            availableTaskNamesByTaskName.set(currentTask.name, availableTaskNames);
        }

        if (!currentTask.parameters) {
            return;
        }

        const parameters = currentTask.parameters as Record<string, unknown>;
        const nestedInheritedTaskNames = new Set([...availableTaskNames, currentTask.name]);

        forEachNestedTaskGroup(parameters, (nestedTasks, key) => {
            if (key !== 'nodes') {
                visitTasks(nestedTasks, nestedInheritedTaskNames, undefined, availableTaskNamesByTaskName);

                return;
            }

            for (const memberTask of nestedTasks) {
                availableTaskNames.add(memberTask.name);
            }

            visitTasks(
                nestedTasks,
                nestedInheritedTaskNames,
                Array.isArray(parameters.transitions) ? (parameters.transitions as Array<GraphTransitionType>) : [],
                availableTaskNamesByTaskName
            );
        });
    });
}

export default function getAvailableTaskNamesByTaskName(tasks: Array<WorkflowTask>): Map<string, Set<string>> {
    const nestedTaskNames = new Set<string>();

    for (const currentTask of tasks) {
        if (currentTask.parameters) {
            forEachNestedTaskGroup(currentTask.parameters as Record<string, unknown>, (nestedTasks) => {
                for (const nestedTask of nestedTasks) {
                    nestedTaskNames.add(nestedTask.name);
                }
            });
        }
    }

    const availableTaskNamesByTaskName = new Map<string, Set<string>>();

    visitTasks(
        tasks.filter((currentTask) => !nestedTaskNames.has(currentTask.name)),
        new Set(),
        undefined,
        availableTaskNamesByTaskName
    );

    return availableTaskNamesByTaskName;
}

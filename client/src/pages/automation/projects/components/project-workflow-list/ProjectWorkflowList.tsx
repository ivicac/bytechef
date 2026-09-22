import EmptyList from '@/components/EmptyList';
import {Skeleton} from '@/components/ui/skeleton';
import ProjectWorkflowCreationActions from '@/pages/automation/projects/components/project-workflow-list/ProjectWorkflowCreationActions';
import ProjectWorkflowListItem from '@/pages/automation/projects/components/project-workflow-list/ProjectWorkflowListItem';
import {Project} from '@/shared/middleware/automation/configuration';
import {ComponentDefinitionBasic, TaskDispatcherDefinition} from '@/shared/middleware/platform/configuration';
import {useGetProjectWorkflowsQuery} from '@/shared/queries/automation/projectWorkflows.queries';
import {WorkflowIcon} from 'lucide-react';

const ProjectWorkflowList = ({
    componentDefinitions,
    project,
    queryEnabled,
    taskDispatcherDefinitions,
}: {
    componentDefinitions?: ComponentDefinitionBasic[];
    project: Project;
    queryEnabled?: boolean;
    taskDispatcherDefinitions?: TaskDispatcherDefinition[];
}) => {
    const workflowComponentDefinitions: {
        [key: string]: ComponentDefinitionBasic | undefined;
    } = {};

    const {data: workflows, isLoading: isProjectWorkflowsLoading} = useGetProjectWorkflowsQuery(
        project.id!,
        queryEnabled && !!project.id
    );

    const workflowTaskDispatcherDefinitions: {
        [key: string]: ComponentDefinitionBasic | undefined;
    } = {};

    return !componentDefinitions || !taskDispatcherDefinitions || isProjectWorkflowsLoading ? (
        <div className="space-y-3 p-3">
            <Skeleton className="h-5 w-40" />

            {[1, 2].map((value) => (
                <div className="flex items-center space-x-4" key={value}>
                    <Skeleton className="h-4 w-80" />

                    <div className="flex w-60 items-center space-x-1">
                        <Skeleton className="h-6 w-7 rounded-full" />

                        <Skeleton className="size-7 rounded-full" />

                        <Skeleton className="size-7 rounded-full" />
                    </div>

                    <Skeleton className="h-4 flex-1" />
                </div>
            ))}
        </div>
    ) : (
        <div className="pt-3">
            {workflows && workflows.length > 0 ? (
                <>
                    <ul className="divide-y divide-stroke-neutral-primary">
                        {workflows
                            .sort((a, b) => a.label!.localeCompare(b.label!))
                            .map((workflow) => {
                                const componentNames = [
                                    ...(workflow.workflowTriggerComponentNames ?? []),
                                    ...(workflow.workflowTaskComponentNames ?? []),
                                ];

                                componentNames?.map((componentName) => {
                                    if (!workflowComponentDefinitions[componentName]) {
                                        workflowComponentDefinitions[componentName] = componentDefinitions?.find(
                                            (componentDefinition) => componentDefinition.name === componentName
                                        );
                                    }

                                    if (!workflowTaskDispatcherDefinitions[componentName]) {
                                        workflowTaskDispatcherDefinitions[componentName] =
                                            taskDispatcherDefinitions?.find(
                                                (taskDispatcherDefinition) =>
                                                    taskDispatcherDefinition.name === componentName
                                            );
                                    }
                                });

                                const filteredComponentNames = componentNames?.filter(
                                    (item, index) => componentNames?.indexOf(item) === index
                                );

                                return (
                                    <ProjectWorkflowListItem
                                        filteredComponentNames={filteredComponentNames}
                                        key={workflow.id}
                                        project={project}
                                        workflow={workflow}
                                        workflowComponentDefinitions={workflowComponentDefinitions}
                                        workflowTaskDispatcherDefinitions={workflowTaskDispatcherDefinitions}
                                    />
                                );
                            })}
                    </ul>
                </>
            ) : (
                <div className="flex justify-center py-8">
                    <EmptyList
                        button={
                            !project.codeWorkflow && (
                                <ProjectWorkflowCreationActions placement="emptyState" project={project} />
                            )
                        }
                        icon={<WorkflowIcon className="size-24 text-stroke-neutral-tertiary" />}
                        message="Get started by creating a new workflow."
                        title="No Workflows"
                    />
                </div>
            )}
        </div>
    );
};

export default ProjectWorkflowList;

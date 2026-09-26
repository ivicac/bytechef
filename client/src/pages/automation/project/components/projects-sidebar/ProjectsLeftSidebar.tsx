import {ScrollArea} from '@/components/ui/scroll-area';
import {Skeleton} from '@/components/ui/skeleton';
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs';
import useAgents from '@/pages/automation/agents/hooks/useAgents';
import useDataSyncs from '@/pages/automation/data-syncs/hooks/useDataSyncs';
import ProjectAgentsList from '@/pages/automation/project/components/projects-sidebar/components/ProjectAgentsList';
import ProjectDataSyncsList from '@/pages/automation/project/components/projects-sidebar/components/ProjectDataSyncsList';
import ProjectSelect from '@/pages/automation/project/components/projects-sidebar/components/ProjectSelect';
import ProjectWorkflowsList from '@/pages/automation/project/components/projects-sidebar/components/ProjectWorkflowsList';
import WorkflowsListFilter from '@/pages/automation/project/components/projects-sidebar/components/WorkflowsListFilter';
import WorkflowsListItem from '@/pages/automation/project/components/projects-sidebar/components/WorkflowsListItem';
import WorkflowsListSkeleton from '@/pages/automation/project/components/projects-sidebar/components/WorkflowsListSkeleton';
import {useProjectsLeftSidebar} from '@/pages/automation/project/components/projects-sidebar/hooks/useProjectsLeftSidebar';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useGetProjectWorkflowsQuery, useGetWorkflowsQuery} from '@/shared/queries/automation/projectWorkflows.queries';
import {useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import {useEffect, useMemo, useRef, useState} from 'react';

interface ProjectsLeftSidebarProps {
    currentAgentId?: string;
    currentDataSyncId?: string;
    currentWorkflowId: string;
    onProjectClick: (projectId: number, projectWorkflowId: number) => void;
    projectId: number;
}

// A plain label with the count as a small badge, rather than "Label (N)" as a single string: measured against
// .superpowers/sidebar-tabs.html (built app CSS, three tabs at exactly 355px), the literal "Workflows (12) |
// Agents (3) | Data Syncs (4)" example fits with under 3px to spare and a slightly larger, entirely
// realistic count (e.g. any tab reaching double digits together with another) already overflows the row. The
// fallback applies to all three tabs, not a shorter label for one, per the spec.
const TabCountBadge = ({count}: {count: number}) => (
    <span className="inline-flex min-w-4 shrink-0 items-center justify-center rounded-full bg-surface-neutral-primary px-1 text-xs font-normal text-content-neutral-secondary">
        {count}
    </span>
);

const ProjectsLeftSidebar = ({
    currentAgentId,
    currentDataSyncId,
    currentWorkflowId,
    onProjectClick,
    projectId,
}: ProjectsLeftSidebarProps) => {
    const [selectedProjectId, setSelectedProjectId] = useState(!isNaN(projectId) ? projectId : 0);
    const [sortBy, setSortBy] = useState('last-edited');
    const [searchValue, setSearchValue] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [activeTab, setActiveTab] = useState(
        currentDataSyncId ? 'dataSyncs' : currentAgentId ? 'agents' : 'workflows'
    );

    const searchInputRef = useRef<HTMLInputElement>(null);

    const {dataSyncs} = useDataSyncs();

    const {data: eachProjectWorkflows, isLoading: projectWorkflowsLoading} = useGetProjectWorkflowsQuery(
        selectedProjectId,
        selectedProjectId !== 0
    );
    const {data: allProjectsWorkflows, isLoading: allProjectsWorkflowsLoading} = useGetWorkflowsQuery(
        selectedProjectId === 0
    );
    const workflows = eachProjectWorkflows || allProjectsWorkflows;

    const {calculateTimeDifference, getFilteredWorkflows, getWorkflowsProjectId} = useProjectsLeftSidebar();

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const {
        data: projects,
        isLoading: projectsLoading,
        refetch: refetchProjects,
    } = useGetWorkspaceProjectsQuery({
        id: currentWorkspaceId!,
    });

    const findProjectIdByWorkflow = getWorkflowsProjectId(projects || []);

    const selectedProject = projects?.find((project) => project.id === selectedProjectId);

    const filteredWorkflowsList = useMemo(
        () => getFilteredWorkflows(workflows, sortBy, searchValue),
        [workflows, sortBy, searchValue, getFilteredWorkflows]
    );

    const {agents} = useAgents();

    const projectAgents = useMemo(
        () => agents.filter((agent) => selectedProjectId === 0 || +agent.projectId === selectedProjectId),
        [agents, selectedProjectId]
    );

    const projectDataSyncs = useMemo(
        () => dataSyncs.filter((dataSync) => selectedProjectId === 0 || +dataSync.projectId === selectedProjectId),
        [dataSyncs, selectedProjectId]
    );

    useEffect(() => {
        setIsLoading(projectWorkflowsLoading || allProjectsWorkflowsLoading || projectsLoading);
    }, [projectWorkflowsLoading, allProjectsWorkflowsLoading, projectsLoading]);

    useEffect(() => {
        if (selectedProjectId === 0) {
            refetchProjects();
        }
    }, [selectedProjectId, refetchProjects]);

    useEffect(() => {
        setSelectedProjectId(!isNaN(projectId) ? projectId : 0);
    }, [projectId]);

    useEffect(() => {
        setActiveTab(currentDataSyncId ? 'dataSyncs' : currentAgentId ? 'agents' : 'workflows');
    }, [currentAgentId, currentDataSyncId]);

    useEffect(() => {
        if (isLoading) {
            return;
        }

        const timeoutId = setTimeout(() => {
            searchInputRef.current?.focus();
        }, 50);

        return () => clearTimeout(timeoutId);
    }, [isLoading, selectedProjectId]);

    return (
        <aside className="flex h-full min-w-[355px] flex-col items-center gap-2 bg-surface-main px-4 pt-3">
            <div className="flex w-full flex-col gap-2">
                {projectsLoading ? (
                    <Skeleton className="h-9 w-full rounded-md" />
                ) : (
                    projects && (
                        <div className="flex items-center gap-2">
                            <ProjectSelect
                                projectId={projectId}
                                projects={projects}
                                selectedProjectId={selectedProjectId}
                                setSelectedProjectId={setSelectedProjectId}
                            />
                        </div>
                    )
                )}

                <WorkflowsListFilter
                    ref={searchInputRef}
                    searchValue={searchValue}
                    setSearchValue={setSearchValue}
                    setSortBy={setSortBy}
                    sortBy={sortBy}
                />
            </div>

            <ScrollArea className="mb-3 min-h-0 w-full flex-1 [&_[data-radix-scroll-area-viewport]>div]:block!">
                {isLoading && <WorkflowsListSkeleton />}

                {!isLoading && (
                    <Tabs onValueChange={setActiveTab} value={activeTab}>
                        <TabsList className="mb-2 w-full">
                            <TabsTrigger className="flex-1" value="workflows">
                                Workflows <TabCountBadge count={filteredWorkflowsList.length} />
                            </TabsTrigger>

                            <TabsTrigger className="flex-1" value="agents">
                                Agents <TabCountBadge count={projectAgents.length} />
                            </TabsTrigger>

                            <TabsTrigger className="flex-1" value="dataSyncs">
                                Data Syncs <TabCountBadge count={projectDataSyncs.length} />
                            </TabsTrigger>
                        </TabsList>

                        <TabsContent value="workflows">
                            <ul className="flex flex-col gap-4">
                                {selectedProjectId === 0 &&
                                    (projects ? (
                                        projects.map((project) => (
                                            <ProjectWorkflowsList
                                                calculateTimeDifference={calculateTimeDifference}
                                                currentWorkflowId={currentWorkflowId}
                                                filteredWorkflowsList={filteredWorkflowsList}
                                                findProjectIdByWorkflow={findProjectIdByWorkflow}
                                                key={project.id}
                                                onProjectClick={onProjectClick}
                                                project={project}
                                                setSelectedProjectId={setSelectedProjectId}
                                            />
                                        ))
                                    ) : (
                                        <span className="w-full py-2 text-sm text-muted-foreground">
                                            No workflows found
                                        </span>
                                    ))}

                                {selectedProjectId !== 0 && filteredWorkflowsList.length > 0 ? (
                                    filteredWorkflowsList.map((workflow) => (
                                        <WorkflowsListItem
                                            calculateTimeDifference={calculateTimeDifference}
                                            currentWorkflowId={currentWorkflowId}
                                            findProjectIdByWorkflow={findProjectIdByWorkflow}
                                            key={workflow.id}
                                            onProjectClick={onProjectClick}
                                            project={selectedProject}
                                            setSelectedProjectId={setSelectedProjectId}
                                            workflow={workflow}
                                        />
                                    ))
                                ) : (
                                    <span className="w-full py-2 text-sm text-muted-foreground">
                                        No workflows found
                                    </span>
                                )}
                            </ul>
                        </TabsContent>

                        <TabsContent value="agents">
                            <ul className="flex flex-col gap-4">
                                <ProjectAgentsList
                                    calculateTimeDifference={calculateTimeDifference}
                                    currentAgentId={currentAgentId}
                                    emptyMessage="No agents yet."
                                    projectId={selectedProjectId}
                                />
                            </ul>
                        </TabsContent>

                        <TabsContent value="dataSyncs">
                            <ul className="flex flex-col gap-4">
                                <ProjectDataSyncsList
                                    calculateTimeDifference={calculateTimeDifference}
                                    currentDataSyncId={currentDataSyncId}
                                    emptyMessage="No data syncs yet."
                                    projectId={selectedProjectId}
                                />
                            </ul>
                        </TabsContent>
                    </Tabs>
                )}
            </ScrollArea>
        </aside>
    );
};

export default ProjectsLeftSidebar;

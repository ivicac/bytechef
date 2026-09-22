import Button from '@/components/Button/Button';
import {ButtonGroup} from '@/components/ui/button-group';
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import {ScrollArea} from '@/components/ui/scroll-area';
import {Skeleton} from '@/components/ui/skeleton';
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs';
import AgentDialog from '@/pages/automation/agents/components/AgentDialog';
import useAgents from '@/pages/automation/agents/hooks/useAgents';
import useImportAiAgent from '@/pages/automation/agents/hooks/useImportAiAgent';
import ProjectAgentsList from '@/pages/automation/project/components/projects-sidebar/components/ProjectAgentsList';
import ProjectSelect from '@/pages/automation/project/components/projects-sidebar/components/ProjectSelect';
import ProjectWorkflowsList from '@/pages/automation/project/components/projects-sidebar/components/ProjectWorkflowsList';
import WorkflowsListFilter from '@/pages/automation/project/components/projects-sidebar/components/WorkflowsListFilter';
import WorkflowsListItem from '@/pages/automation/project/components/projects-sidebar/components/WorkflowsListItem';
import WorkflowsListSkeleton from '@/pages/automation/project/components/projects-sidebar/components/WorkflowsListSkeleton';
import {useProjectsLeftSidebar} from '@/pages/automation/project/components/projects-sidebar/hooks/useProjectsLeftSidebar';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useGetProjectWorkflowsQuery, useGetWorkflowsQuery} from '@/shared/queries/automation/projectWorkflows.queries';
import {useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import {ChevronDownIcon, LoaderCircleIcon, PlusIcon, UploadIcon} from 'lucide-react';
import {useEffect, useMemo, useRef, useState} from 'react';

interface ProjectsLeftSidebarProps {
    currentAgentId?: string;
    currentWorkflowId: string;
    onProjectClick: (projectId: number, projectWorkflowId: number) => void;
    projectId: number;
}

const ProjectsLeftSidebar = ({currentAgentId, currentWorkflowId, onProjectClick, projectId}: ProjectsLeftSidebarProps) => {
    const [selectedProjectId, setSelectedProjectId] = useState(!isNaN(projectId) ? projectId : 0);
    const [sortBy, setSortBy] = useState('last-edited');
    const [searchValue, setSearchValue] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [showAgentDialog, setShowAgentDialog] = useState(false);
    const [activeTab, setActiveTab] = useState(currentAgentId ? 'agents' : 'workflows');

    const searchInputRef = useRef<HTMLInputElement>(null);

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

    // New/imported agents target the project browsed in the sidebar, falling back to the page's own project
    // when the sidebar is browsing all projects (selectedProjectId is then 0).
    const agentTargetProjectId = selectedProjectId || projectId;

    const filteredWorkflowsList = useMemo(
        () => getFilteredWorkflows(workflows, sortBy, searchValue),
        [workflows, sortBy, searchValue, getFilteredWorkflows]
    );

    const {agents} = useAgents();

    const projectAgents = useMemo(
        () => agents.filter((agent) => selectedProjectId === 0 || +agent.projectId === selectedProjectId),
        [agents, selectedProjectId]
    );

    const {
        fileInputRef: agentHiddenFileInputRef,
        handleImportFileChange: handleImportAgentFileChange,
        isImporting: isImportingAgent,
        triggerImport: triggerAgentImport,
    } = useImportAiAgent({
        projectId: agentTargetProjectId,
        workspaceId: currentWorkspaceId,
    });

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
        setActiveTab(currentAgentId ? 'agents' : 'workflows');
    }, [currentAgentId]);

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
                                Workflows ({filteredWorkflowsList.length})
                            </TabsTrigger>

                            <TabsTrigger className="flex-1" value="agents">
                                Agents ({projectAgents.length})
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
                            <ButtonGroup className="mb-3 w-full">
                                <Button
                                    className="flex-1 [&_svg]:size-5"
                                    icon={<PlusIcon />}
                                    label="New Agent"
                                    onClick={() => setShowAgentDialog(true)}
                                    variant="secondary"
                                />

                                <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                        <Button
                                            className="data-[state=open]:border-stroke-brand-secondary data-[state=open]:bg-surface-brand-secondary data-[state=open]:text-content-brand-primary [&_svg]:size-5"
                                            icon={
                                                isImportingAgent ? (
                                                    <LoaderCircleIcon className="animate-spin text-primary" />
                                                ) : (
                                                    <ChevronDownIcon />
                                                )
                                            }
                                            size="icon"
                                            variant="secondary"
                                        />
                                    </DropdownMenuTrigger>

                                    <DropdownMenuContent align="end">
                                        <DropdownMenuItem
                                            className="cursor-pointer"
                                            disabled={isImportingAgent}
                                            onClick={() => triggerAgentImport()}
                                        >
                                            <UploadIcon /> Import Agent
                                        </DropdownMenuItem>
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            </ButtonGroup>

                            <ul className="flex flex-col gap-4">
                                <ProjectAgentsList
                                    calculateTimeDifference={calculateTimeDifference}
                                    currentAgentId={currentAgentId}
                                    emptyMessage="No agents yet."
                                    projectId={selectedProjectId}
                                />
                            </ul>
                        </TabsContent>
                    </Tabs>
                )}
            </ScrollArea>

            {showAgentDialog && (
                <AgentDialog
                    onOpenChange={setShowAgentDialog}
                    open={showAgentDialog}
                    projectId={agentTargetProjectId}
                />
            )}

            <input
                accept=".json"
                className="hidden"
                onChange={handleImportAgentFileChange}
                ref={agentHiddenFileInputRef}
                type="file"
            />
        </aside>
    );
};

export default ProjectsLeftSidebar;

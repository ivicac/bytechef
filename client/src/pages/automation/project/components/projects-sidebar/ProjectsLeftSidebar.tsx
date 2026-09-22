import Button from '@/components/Button/Button';
import {ButtonGroup} from '@/components/ui/button-group';
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import {ScrollArea} from '@/components/ui/scroll-area';
import {Skeleton} from '@/components/ui/skeleton';
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
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
import {useConvertN8nToWorkflow} from '@/pages/automation/project/hooks/useConverterN8nToWorkflow';
import handleImportN8nWorkflow from '@/pages/automation/project/utils/handleImportN8nWorkflow';
import handleImportProject from '@/pages/automation/project/utils/handleImportProject';
import handleImportWorkflow from '@/pages/automation/project/utils/handleImportWorkflow';
import ProjectDialog from '@/pages/automation/projects/components/ProjectDialog';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import WorkflowDialog from '@/shared/components/workflow/WorkflowDialog';
import {useAnalytics} from '@/shared/hooks/useAnalytics';
import {useHasEnabledAiProvider} from '@/shared/hooks/useHasEnabledAiProvider';
import {useImportProjectMutation} from '@/shared/mutations/automation/projects.mutations';
import {useCreateProjectWorkflowMutation} from '@/shared/mutations/automation/workflows.mutations';
import {useGetProjectWorkflowsQuery, useGetWorkflowsQuery} from '@/shared/queries/automation/projectWorkflows.queries';
import {ProjectKeys, useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import {useGetWorkflowQuery} from '@/shared/queries/automation/workflows.queries';
import {useQueryClient} from '@tanstack/react-query';
import {ChevronDownIcon, LayoutTemplateIcon, LoaderCircleIcon, PlusIcon, UploadIcon} from 'lucide-react';
import {RefObject, useEffect, useMemo, useRef, useState} from 'react';
import {PanelImperativeHandle} from 'react-resizable-panels';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface ProjectsLeftSidebarProps {
    bottomResizablePanelRef: RefObject<PanelImperativeHandle | null>;
    currentAgentId?: string;
    currentWorkflowId: string;
    onProjectClick: (projectId: number, projectWorkflowId: number) => void;
    projectId: number;
}

const ProjectsLeftSidebar = ({
    bottomResizablePanelRef,
    currentAgentId,
    currentWorkflowId,
    onProjectClick,
    projectId,
}: ProjectsLeftSidebarProps) => {
    const [selectedProjectId, setSelectedProjectId] = useState(!isNaN(projectId) ? projectId : 0);
    const [sortBy, setSortBy] = useState('last-edited');
    const [searchValue, setSearchValue] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [showAgentDialog, setShowAgentDialog] = useState(false);
    const [showProjectDialog, setShowProjectDialog] = useState(false);
    const [showWorkflowDialog, setShowWorkflowDialog] = useState(false);
    const [activeTab, setActiveTab] = useState(currentAgentId ? 'agents' : 'workflows');

    const projectHiddenFileInputRef = useRef<HTMLInputElement>(null);
    const searchInputRef = useRef<HTMLInputElement>(null);
    const workflowHiddenFileInputRef = useRef<HTMLInputElement>(null);
    const converterHiddenFileInputRef = useRef<HTMLInputElement>(null);
    const navigate = useNavigate();

    const {captureProjectWorkflowImported} = useAnalytics();

    const {data: eachProjectWorkflows, isLoading: projectWorkflowsLoading} = useGetProjectWorkflowsQuery(
        selectedProjectId,
        selectedProjectId !== 0
    );
    const {data: allProjectsWorkflows, isLoading: allProjectsWorkflowsLoading} = useGetWorkflowsQuery(
        selectedProjectId === 0
    );
    const workflows = eachProjectWorkflows || allProjectsWorkflows;

    const {calculateTimeDifference, createProjectWorkflowMutation, getFilteredWorkflows, getWorkflowsProjectId} =
        useProjectsLeftSidebar({
            bottomResizablePanelRef,
            projectId: selectedProjectId === 0 ? projectId : selectedProjectId,
        });

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
    // A code project's workflows come from its source file, so there is nothing to create here.
    const selectedProjectIsCodeWorkflow = (projects ?? []).some(
        (project) => project.id === (selectedProjectId === 0 ? projectId : selectedProjectId) && project.codeWorkflow
    );

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

    const queryClient = useQueryClient();

    const {
        fileInputRef: agentHiddenFileInputRef,
        handleImportFileChange: handleImportAgentFileChange,
        isImporting: isImportingAgent,
        triggerImport: triggerAgentImport,
    } = useImportAiAgent({
        projectId: agentTargetProjectId,
        workspaceId: currentWorkspaceId,
    });

    const {convertN8nWorkflow} = useConvertN8nToWorkflow();
    const {hasEnabledAiProvider, isPending: isAiProviderCheckPending} = useHasEnabledAiProvider();

    const importN8nWorkflowDisabled = !isAiProviderCheckPending && !hasEnabledAiProvider;
    const [isImportingN8nWorkflow, setIsImportingN8nWorkflow] = useState(false);

    const importProjectMutation = useImportProjectMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ProjectKeys.projects});

            toast('Project is imported.');
        },
    });

    const importProjectWorkflowMutation = useCreateProjectWorkflowMutation({
        onSuccess: () => {
            captureProjectWorkflowImported();

            queryClient.invalidateQueries({queryKey: ProjectKeys.project(selectedProjectId)});
            queryClient.invalidateQueries({queryKey: ProjectKeys.projects});

            if (workflowHiddenFileInputRef.current) {
                workflowHiddenFileInputRef.current.value = '';
            }

            toast('Workflow is imported.');
        },
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
                    <div className="flex items-center gap-2">
                        <Skeleton className="h-9 flex-1 rounded-md" />

                        <Skeleton className="size-9 rounded-md" />
                    </div>
                ) : (
                    projects && (
                        <div className="flex items-center gap-2">
                            <ProjectSelect
                                projectId={projectId}
                                projects={projects}
                                selectedProjectId={selectedProjectId}
                                setSelectedProjectId={setSelectedProjectId}
                            />

                            <DropdownMenu>
                                <Tooltip>
                                    <DropdownMenuTrigger asChild>
                                        <TooltipTrigger asChild>
                                            <Button
                                                aria-label="New project"
                                                className="data-[state=open]:border-stroke-brand-secondary data-[state=open]:bg-surface-brand-secondary data-[state=open]:text-content-brand-primary"
                                                icon={<PlusIcon />}
                                                size="icon"
                                                variant="outline"
                                            />
                                        </TooltipTrigger>
                                    </DropdownMenuTrigger>

                                    <TooltipContent>New project</TooltipContent>
                                </Tooltip>

                                <DropdownMenuContent align="end">
                                    <DropdownMenuItem
                                        className="cursor-pointer"
                                        onClick={() => setShowProjectDialog(true)}
                                    >
                                        <PlusIcon className="mr-2 size-4" />
                                        From Scratch
                                    </DropdownMenuItem>

                                    <DropdownMenuItem
                                        className="cursor-pointer"
                                        onClick={() => navigate('/automation/projects/templates')}
                                    >
                                        <LayoutTemplateIcon className="mr-2 size-4" />
                                        From Template
                                    </DropdownMenuItem>

                                    <DropdownMenuItem
                                        className="cursor-pointer"
                                        onClick={() => projectHiddenFileInputRef.current?.click()}
                                    >
                                        <UploadIcon className="mr-2 size-4" />
                                        Import Project
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
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
                            {!selectedProjectIsCodeWorkflow && (
                                <ButtonGroup className="mb-3 w-full">
                                    <Button
                                        className="flex-1 [&_svg]:size-5"
                                        icon={<PlusIcon />}
                                        label="New Workflow"
                                        onClick={() => setShowWorkflowDialog(true)}
                                        variant="secondary"
                                    />

                                    <DropdownMenu>
                                        <DropdownMenuTrigger asChild>
                                            <Button
                                                className="data-[state=open]:border-stroke-brand-secondary data-[state=open]:bg-surface-brand-secondary data-[state=open]:text-content-brand-primary [&_svg]:size-5"
                                                icon={
                                                    isImportingN8nWorkflow ? (
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
                                                onClick={() =>
                                                    navigate(`/automation/projects/${selectedProjectId}/templates`)
                                                }
                                            >
                                                <LayoutTemplateIcon /> From Template
                                            </DropdownMenuItem>

                                            <DropdownMenuItem
                                                className="cursor-pointer"
                                                onClick={() => {
                                                    if (workflowHiddenFileInputRef.current) {
                                                        workflowHiddenFileInputRef.current.click();
                                                    }
                                                }}
                                            >
                                                <UploadIcon /> Import Workflow
                                            </DropdownMenuItem>

                                            <Tooltip>
                                                <TooltipTrigger asChild>
                                                    <span className="block">
                                                        <DropdownMenuItem
                                                            className="cursor-pointer"
                                                            disabled={importN8nWorkflowDisabled}
                                                            onClick={() => {
                                                                if (converterHiddenFileInputRef.current) {
                                                                    converterHiddenFileInputRef.current.click();
                                                                }
                                                            }}
                                                        >
                                                            <UploadIcon /> Import n8n Workflow
                                                        </DropdownMenuItem>
                                                    </span>
                                                </TooltipTrigger>

                                                {importN8nWorkflowDisabled && (
                                                    <TooltipContent>
                                                        Enable an AI provider to import n8n workflows.
                                                    </TooltipContent>
                                                )}
                                            </Tooltip>
                                        </DropdownMenuContent>
                                    </DropdownMenu>
                                </ButtonGroup>
                            )}

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

            {showProjectDialog && <ProjectDialog onClose={() => setShowProjectDialog(false)} project={undefined} />}

            {showWorkflowDialog && (
                <WorkflowDialog
                    createWorkflowMutation={createProjectWorkflowMutation}
                    onClose={() => setShowWorkflowDialog(false)}
                    parentId={selectedProjectId}
                    useGetWorkflowQuery={useGetWorkflowQuery}
                />
            )}

            <input
                accept=".json,.yaml,.yml"
                alt="file"
                className="hidden"
                onChange={(event) => handleImportWorkflow(event, selectedProjectId, importProjectWorkflowMutation)}
                ref={workflowHiddenFileInputRef}
                type="file"
            />

            <input
                accept=".json"
                className="hidden"
                onChange={async (event) => {
                    if (!event.target.files?.length) {
                        return;
                    }

                    try {
                        setIsImportingN8nWorkflow(true);
                        await handleImportN8nWorkflow(
                            event,
                            selectedProjectId,
                            importProjectWorkflowMutation,
                            convertN8nWorkflow
                        );
                    } finally {
                        setIsImportingN8nWorkflow(false);

                        if (converterHiddenFileInputRef.current) {
                            converterHiddenFileInputRef.current.value = '';
                        }
                    }
                }}
                ref={converterHiddenFileInputRef}
                type="file"
            />

            <input
                accept=".json"
                className="hidden"
                onChange={handleImportAgentFileChange}
                ref={agentHiddenFileInputRef}
                type="file"
            />

            <input
                accept=".zip"
                className="hidden"
                onChange={(event) => handleImportProject(event, currentWorkspaceId!, importProjectMutation)}
                ref={projectHiddenFileInputRef}
                type="file"
            />
        </aside>
    );
};

export default ProjectsLeftSidebar;

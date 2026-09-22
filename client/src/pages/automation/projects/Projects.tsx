import Button from '@/components/Button/Button';
import EmptyFilterResult from '@/components/EmptyFilterResult';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import {ButtonGroup} from '@/components/ui/button-group';
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import AgentsFilterLeftSidebarNav, {
    getAgentsFilter,
} from '@/pages/automation/agents/components/AgentsFilterLeftSidebarNav';
import useAgents from '@/pages/automation/agents/hooks/useAgents';
import isScheduledAgent from '@/pages/automation/agents/utils/isScheduledAgent';
import loadProject from '@/pages/automation/project/loadProject';
import handleImportProject from '@/pages/automation/project/utils/handleImportProject';
import ProjectsFilterTitle from '@/pages/automation/projects/components/ProjectsFilterTitle';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import CopilotButton from '@/shared/components/copilot/CopilotButton';
import useCopilotPostTurnRegistry from '@/shared/components/copilot/stores/useCopilotPostTurnRegistry';
import {Source} from '@/shared/components/copilot/stores/useCopilotStore';
import {getProjectGitApi} from '@/shared/edition/project-git/projectGitApi';
import useButtonGroupDropdownAlign from '@/shared/hooks/useButtonGroupDropdownAlign';
import CategoryTagLeftSidebarNav from '@/shared/layout/CategoryTagLeftSidebarNav';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {useImportProjectMutation} from '@/shared/mutations/automation/projects.mutations';
import {useGetComponentDefinitionsQuery} from '@/shared/queries/automation/componentDefinitions.queries';
import {useGetProjectCategoriesQuery} from '@/shared/queries/automation/projectCategories.queries';
import {useGetProjectTagsQuery} from '@/shared/queries/automation/projectTags.queries';
import {ProjectWorkflowKeys} from '@/shared/queries/automation/projectWorkflows.queries';
import {ProjectKeys, useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import {useGetTaskDispatcherDefinitionsQuery} from '@/shared/queries/platform/taskDispatcherDefinitions.queries';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {useQueryClient} from '@tanstack/react-query';
import {ChevronDownIcon, CodeIcon, FolderIcon, LayoutTemplateIcon, UploadIcon} from 'lucide-react';
import {useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';
import {toast} from 'sonner';

import NewCodeWorkflowDialog from './components/NewCodeWorkflowDialog';
import ProjectDialog from './components/ProjectDialog';
import ProjectList from './components/project-list/ProjectList';

export enum Type {
    Category,
    Tag,
}

const Projects = () => {
    const [newlyCreatedProjectId, setNewlyCreatedProjectId] = useState<number | undefined>();
    const [showNewCodeWorkflowDialog, setShowNewCodeWorkflowDialog] = useState(false);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const [searchParams] = useSearchParams();
    const fileInputRef = useRef<HTMLInputElement>(null);
    const navigate = useNavigate();

    const {alignOffset, buttonGroupRef, dropdownMenuTriggerRef, handleOpenChange} = useButtonGroupDropdownAlign();

    const queryClient = useQueryClient();
    const registerPostTurn = useCopilotPostTurnRegistry((state) => state.register);

    const importProjectMutation = useImportProjectMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ProjectKeys.projects});

            toast('Project is imported.');
        },
    });

    const ff_1039 = useFeatureFlagsStore()('ff-1039');

    const categoryId = searchParams.get('categoryId');
    const tagId = searchParams.get('tagId');

    const agentsFilter = getAgentsFilter(searchParams);

    const filterData = {
        id: categoryId ? parseInt(categoryId) : tagId ? parseInt(tagId) : undefined,
        type: tagId ? Type.Tag : Type.Category,
    };

    const isFiltered = filterData.id !== undefined || agentsFilter !== undefined;

    const {data: componentDefinitions} = useGetComponentDefinitionsQuery({
        actionDefinitions: true,
        triggerDefinitions: true,
    });

    const {
        data: categories,
        error: categoriesError,
        isLoading: categoriesIsLoading,
    } = useGetProjectCategoriesQuery(currentWorkspaceId!);

    const {
        data: projectGitConfigurations,
        error: projectGitConfigurationsError,
        isLoading: projectGitConfigurationsIsLoading,
    } = getProjectGitApi().useWorkspaceProjectGitConfigurationsQuery(currentWorkspaceId!, ff_1039);

    const {
        data: projects,
        error: projectsError,
        isLoading: projectsIsLoading,
    } = useGetWorkspaceProjectsQuery({
        categoryId: searchParams.get('categoryId') ? parseInt(searchParams.get('categoryId')!) : undefined,
        id: currentWorkspaceId!,
        tagId: searchParams.get('tagId') ? parseInt(searchParams.get('tagId')!) : undefined,
    });

    const isRefetchingProjects =
        queryClient.isFetching({
            exact: true,
            queryKey: ProjectKeys.filteredProjects({
                categoryId: searchParams.get('categoryId') ? parseInt(searchParams.get('categoryId')!) : undefined,
                id: currentWorkspaceId!,
                tagId: searchParams.get('tagId') ? parseInt(searchParams.get('tagId')!) : undefined,
            }),
        }) > 0;

    const {data: tags, error: tagsError, isLoading: tagsIsLoading} = useGetProjectTagsQuery(currentWorkspaceId!);

    const {data: taskDispatcherDefinitions} = useGetTaskDispatcherDefinitionsQuery();

    const {agents, agentsError, agentsIsLoading} = useAgents();

    // Client-side on top of the server's category/tag filter, so the two combine. Agents carry their project id
    // as a GraphQL string, hence the numeric comparison.
    const filteredProjects = useMemo(() => {
        if (!projects || !agentsFilter) {
            return projects;
        }

        const matchingAgents =
            agentsFilter === 'scheduled' ? agents.filter((agent) => isScheduledAgent(agent)) : agents;

        const agentProjectIds = new Set(matchingAgents.map((agent) => +agent.projectId));

        return projects.filter((project) => agentProjectIds.has(project.id!));
    }, [agents, agentsFilter, projects]);

    useEffect(() => {
        loadProject();
    }, []);

    // Refresh the project list and the workflows nested under it after a BUILD-mode copilot turn creates or
    // updates either, so the page reflects the change without a manual reload.
    useEffect(() => {
        return registerPostTurn(Source.PROJECT, () => {
            queryClient.invalidateQueries({queryKey: ProjectKeys.projects});
            queryClient.invalidateQueries({queryKey: ProjectWorkflowKeys.workflows});
        });
    }, [queryClient, registerPostTurn]);

    return (
        <LayoutContainer
            header={
                <Header
                    centerTitle={true}
                    position="main"
                    right={
                        ((projects && projects.length > 0) || !projectsIsLoading) && (
                            <div className="flex items-center gap-1">
                                <CopilotButton source={Source.PROJECT} />

                                {projects && projects.length > 0 && (
                                    <ButtonGroup>
                                        <ProjectDialog
                                            // This is the "Create project" command's target.
                                            claimsCreateIntent={true}
                                            onSuccess={(projectId) => projectId && setNewlyCreatedProjectId(projectId)}
                                            project={undefined}
                                            triggerNode={
                                                <Button
                                                    aria-label="Create Project"
                                                    onSelect={(event) => event.preventDefault()}
                                                >
                                                    New Project
                                                </Button>
                                            }
                                        />

                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <Button aria-label="More create options">
                                                    <ChevronDownIcon />
                                                </Button>
                                            </DropdownMenuTrigger>

                                            <DropdownMenuContent align="end">
                                                <DropdownMenuItem onClick={() => navigate(`templates`)}>
                                                    <LayoutTemplateIcon className="mr-2 size-4" />
                                                    From Template
                                                </DropdownMenuItem>

                                                <DropdownMenuItem onClick={() => fileInputRef.current?.click()}>
                                                    <UploadIcon className="mr-2 size-4" />
                                                    Import Project
                                                </DropdownMenuItem>

                                                <DropdownMenuItem onClick={() => setShowNewCodeWorkflowDialog(true)}>
                                                    <CodeIcon className="mr-2 size-4" />
                                                    New Code Project
                                                </DropdownMenuItem>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    </ButtonGroup>
                                )}
                            </div>
                        )
                    }
                    title={
                        (projects && projects.length > 0) || isFiltered ? (
                            <ProjectsFilterTitle
                                agentsFilter={agentsFilter}
                                categories={categories}
                                filterData={filterData}
                                tags={tags}
                            />
                        ) : (
                            ''
                        )
                    }
                />
            }
            leftSidebarBody={
                <CategoryTagLeftSidebarNav
                    categories={categories}
                    categoriesIsLoading={categoriesIsLoading}
                    currentCategoryId={categoryId ? parseInt(categoryId) : undefined}
                    currentTagId={tagId ? parseInt(tagId) : undefined}
                    middleGroups={<AgentsFilterLeftSidebarNav currentAgentsFilter={agentsFilter} />}
                    preservedSearchParams={agentsFilter ? `agents=${agentsFilter}` : undefined}
                    tags={tags}
                    tagsClassName="mb-0"
                    tagsEmptyMessage="No defined tags."
                    tagsIsLoading={tagsIsLoading}
                />
            }
            leftSidebarHeader={<Header position="sidebar" title="Projects" />}
            leftSidebarWidth="64"
        >
            <PageLoader
                errors={[
                    categoriesError,
                    projectGitConfigurationsError,
                    projectsError,
                    tagsError,
                    agentsFilter ? agentsError : null,
                ]}
                loading={
                    categoriesIsLoading ||
                    projectGitConfigurationsIsLoading ||
                    projectsIsLoading ||
                    tagsIsLoading ||
                    (!!agentsFilter && agentsIsLoading)
                }
            >
                {filteredProjects && filteredProjects.length > 0 && tags ? (
                    <ProjectList
                        componentDefinitions={componentDefinitions}
                        defaultActiveTab={agentsFilter ? 'agents' : 'workflows'}
                        isRefetchingProjects={isRefetchingProjects}
                        newlyCreatedProjectId={newlyCreatedProjectId}
                        projectGitConfigurations={projectGitConfigurations ?? []}
                        projects={filteredProjects}
                        tags={tags}
                        taskDispatcherDefinitions={taskDispatcherDefinitions}
                    />
                ) : isFiltered ? (
                    <EmptyFilterResult entityName="projects" entityTitle="Projects" />
                ) : (
                    <EmptyList
                        button={
                            <ButtonGroup className="mx-auto" ref={buttonGroupRef}>
                                <ProjectDialog
                                    // This is the "Create project" command's target.
                                    claimsCreateIntent={true}
                                    onSuccess={(projectId) => projectId && setNewlyCreatedProjectId(projectId)}
                                    project={undefined}
                                    triggerNode={<Button aria-label="Create Project" label="Create Project" />}
                                />

                                <DropdownMenu onOpenChange={handleOpenChange}>
                                    <DropdownMenuTrigger asChild>
                                        <Button aria-label="More create options" ref={dropdownMenuTriggerRef}>
                                            <ChevronDownIcon />
                                        </Button>
                                    </DropdownMenuTrigger>

                                    <DropdownMenuContent align="start" alignOffset={alignOffset}>
                                        <DropdownMenuItem onClick={() => navigate(`templates`)}>
                                            <LayoutTemplateIcon className="mr-2 size-4" />
                                            From Template
                                        </DropdownMenuItem>

                                        <DropdownMenuItem onClick={() => fileInputRef.current?.click()}>
                                            <UploadIcon className="mr-2 size-4" /> Import Project
                                        </DropdownMenuItem>

                                        <DropdownMenuItem onClick={() => setShowNewCodeWorkflowDialog(true)}>
                                            <CodeIcon className="mr-2 size-4" />
                                            New Code Project
                                        </DropdownMenuItem>
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            </ButtonGroup>
                        }
                        icon={<FolderIcon className="size-24 text-stroke-neutral-tertiary" />}
                        message="Get started by creating a new project."
                        title="No Projects"
                    />
                )}
            </PageLoader>

            <input
                accept=".zip"
                onChange={(event) => handleImportProject(event, currentWorkspaceId!, importProjectMutation)}
                ref={fileInputRef}
                style={{display: 'none'}}
                type="file"
            />

            {showNewCodeWorkflowDialog && <NewCodeWorkflowDialog onClose={() => setShowNewCodeWorkflowDialog(false)} />}
        </LayoutContainer>
    );
};

export default Projects;

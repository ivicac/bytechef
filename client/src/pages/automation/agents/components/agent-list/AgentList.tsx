import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from '@/components/ui/collapsible';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {Project} from '@/shared/middleware/automation/configuration';
import {AiAgent} from '@/shared/middleware/graphql';
import {useGetWorkspaceProjectDeploymentsQuery} from '@/shared/queries/automation/projectDeployments.queries';
import {useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import isInteractiveElementClick from '@/shared/util/interactive-element-utils';
import {ChevronDownIcon, RocketIcon} from 'lucide-react';
import {MouseEvent, useMemo, useRef, useState} from 'react';
import {Link} from 'react-router-dom';

import AgentListItem from './AgentListItem';

interface AgentListProps {
    agents: AiAgent[];
}

interface AgentProjectGroupI {
    agents: AiAgent[];
    project?: Project;
    projectId: string;
    projectName: string;
}

// One row per project, styled after ProjectListItem's own row on the Projects page: the project name up top,
// a chevron-and-count line beneath it (ProjectListItem's own "N workflows · N agents" line) that expands and
// collapses the group, and the group's agents nested inside like ProjectAgentListItem's rows — rather than
// free-floating cards under a bare heading. Defaults open, matching a newly created project's row on the
// Projects page; there is no bare project route, so the name links to the group's first agent, same as the
// row badge's own link shape did before this row existed.
//
// The version+status badge, the published-date line and the Deploy button also live here rather than on each
// agent row: publishing is a project-level action, so those three facts describe the PROJECT the group
// heading names, not any one agent inside it. Read off the same `Project` the Projects page reads — not off
// any agent's own `lastPublishedVersion` — so a project with several scheduled agents shows one badge, not
// one repeated per agent.
const AgentProjectGroup = ({projectGroup}: {projectGroup: AgentProjectGroupI}) => {
    const [isProjectDeploymentDialogOpen, setIsProjectDeploymentDialogOpen] = useState(false);

    const triggerRef = useRef<HTMLButtonElement | null>(null);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const project = projectGroup.project;

    // Mirrors ProjectListItem: a project reads PUBLISHED only once it has both a published date and a
    // version to show, even with unpublished edits pending in its draft.
    const isPublished = !!(project?.lastPublishedDate && project?.lastProjectVersion);

    // Fetched lazily (enabled: false below) and refetched only when Deploy is clicked — same as
    // ProjectListItem, which needs the workspace's existing deployments to offer "Change Version" instead of
    // always starting a new one.
    const projectDeploymentsQuery = useGetWorkspaceProjectDeploymentsQuery(
        {
            id: currentWorkspaceId ?? 0,
            projectId: project?.id ?? 0,
        },
        false
    );

    // The whole row toggles the group, mirroring ProjectListItem's own row — except where the click lands on
    // the project name link, the badge/Deploy controls, or the deployment dialog itself: the dialog portals
    // outside this row in the DOM but still bubbles through it in React's tree, so a click on plain dialog
    // content (not caught by isInteractiveElementClick) would otherwise toggle the collapsible underneath it.
    const handleRowClick = (event: React.MouseEvent) => {
        if (isProjectDeploymentDialogOpen) {
            return;
        }

        if (isInteractiveElementClick(event.target)) {
            return;
        }

        triggerRef.current?.click();
    };

    const handleProjectDeploymentDialogOpen = async (event: MouseEvent<HTMLButtonElement>) => {
        event.stopPropagation();

        if (!currentWorkspaceId || !project?.id) {
            return;
        }

        await projectDeploymentsQuery.refetch();
    };

    return (
        <Collapsible className="group mb-2 rounded border border-border/50" defaultOpen>
            <div
                className="flex w-full cursor-pointer items-center justify-between rounded-md px-3 py-3 hover:bg-surface-neutral-primary-hover"
                onClick={handleRowClick}
            >
                <div className="flex-1">
                    <div className="flex min-h-8 items-center gap-1.5">
                        <Link
                            className="text-base font-semibold hover:underline"
                            to={
                                '/automation/projects/' +
                                projectGroup.projectId +
                                '/agents/' +
                                projectGroup.agents[0].id
                            }
                        >
                            {projectGroup.projectName || 'Untitled Project'}
                        </Link>
                    </div>

                    <div className="mt-2 flex min-h-6 items-center">
                        <CollapsibleTrigger
                            className="group/trigger flex items-center text-xs font-semibold text-muted-foreground"
                            ref={triggerRef}
                        >
                            <div className="mr-1">
                                {projectGroup.agents.length} {projectGroup.agents.length === 1 ? 'agent' : 'agents'}
                            </div>

                            <ChevronDownIcon className="size-4 duration-300 group-data-[state=open]:rotate-180" />
                        </CollapsibleTrigger>
                    </div>
                </div>

                {project && (
                    <div className="flex flex-col items-end gap-y-2">
                        <div className="flex min-h-8 items-center gap-2">
                            {isPublished ? (
                                <>
                                    <Badge className="flex space-x-1" styleType="success-outline" weight="semibold">
                                        <span>V{project.lastProjectVersion! - 1}</span>

                                        <span>PUBLISHED</span>
                                    </Badge>

                                    <ProjectDeploymentDialog
                                        environmentEditable={true}
                                        onOpenChange={setIsProjectDeploymentDialogOpen}
                                        projectDeployment={{
                                            name: project.name,
                                            projectId: project.id,
                                        }}
                                        projectDeployments={projectDeploymentsQuery.data}
                                        projectDeploymentsLoading={projectDeploymentsQuery.isFetching}
                                        showTabs
                                        triggerNode={
                                            <Button
                                                className="hover:bg-surface-neutral-primary-hover"
                                                onClick={handleProjectDeploymentDialogOpen}
                                                size="sm"
                                                variant="outline"
                                            >
                                                <RocketIcon /> Deploy
                                            </Button>
                                        }
                                    />
                                </>
                            ) : (
                                <Badge className="flex space-x-1" styleType="secondary-filled" weight="semibold">
                                    <span>V{project.lastProjectVersion}</span>

                                    <span>{project.lastStatus}</span>
                                </Badge>
                            )}
                        </div>

                        <Tooltip>
                            <TooltipTrigger>
                                <div className="flex min-h-7 items-center text-xs text-muted-foreground">
                                    {project.lastPublishedDate
                                        ? `Published at ${new Date(project.lastPublishedDate).toLocaleDateString()} ${new Date(project.lastPublishedDate).toLocaleTimeString()}`
                                        : 'Not yet published'}
                                </div>
                            </TooltipTrigger>

                            <TooltipContent>Last Modified Date</TooltipContent>
                        </Tooltip>
                    </div>
                )}
            </div>

            <CollapsibleContent>
                <ul className="divide-y divide-stroke-neutral-primary px-2 pb-2">
                    {projectGroup.agents.map((agent) => (
                        <AgentListItem agent={agent} key={agent.id} />
                    ))}
                </ul>
            </CollapsibleContent>
        </Collapsible>
    );
};

const AgentList = ({agents}: AgentListProps) => {
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const {data: projects} = useGetWorkspaceProjectsQuery({id: currentWorkspaceId}, !!currentWorkspaceId);

    // Projects sort alphabetically by name; agents keep the order the caller gave them within their project,
    // so a page that already sorted (or didn't) is not silently re-sorted here.
    const projectGroups = useMemo(() => {
        const projectsById = new Map((projects ?? []).map((project) => [String(project.id), project]));

        const groupsByProjectId = new Map<string, AgentProjectGroupI>();

        for (const agent of agents) {
            let projectGroup = groupsByProjectId.get(agent.projectId);

            if (!projectGroup) {
                const project = projectsById.get(agent.projectId);

                projectGroup = {
                    agents: [],
                    project,
                    projectId: agent.projectId,
                    projectName: project?.name ?? '',
                };

                groupsByProjectId.set(agent.projectId, projectGroup);
            }

            projectGroup.agents.push(agent);
        }

        return [...groupsByProjectId.values()].sort((a, b) => a.projectName.localeCompare(b.projectName));
    }, [agents, projects]);

    return (
        <div className="w-full px-4 3xl:mx-auto 3xl:w-4/5">
            {projectGroups.map((projectGroup) => (
                <AgentProjectGroup key={projectGroup.projectId} projectGroup={projectGroup} />
            ))}
        </div>
    );
};

export default AgentList;

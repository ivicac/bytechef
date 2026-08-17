import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import AgentChannelChips from '@/pages/automation/agents/components/AgentChannelChips';
import DeleteAgentAlertDialog from '@/pages/automation/agents/components/DeleteAgentAlertDialog';
import exportAgent from '@/pages/automation/agents/utils/agentImportExport';
import {describeCadence, fromCadenceParameters} from '@/pages/automation/agents/utils/agentScheduleCron';
import getAgentPath from '@/pages/automation/agents/utils/getAgentPath';
import invalidateAgentQueries from '@/pages/automation/agents/utils/invalidateAgentQueries';
import isScheduledAgent from '@/pages/automation/agents/utils/isScheduledAgent';
import {AiAgent, useDeleteAiAgentMutation} from '@/shared/middleware/graphql';
import {usePublishProjectMutation} from '@/shared/mutations/automation/projects.mutations';
import {ProjectKeys} from '@/shared/queries/automation/projects.queries';
import isInteractiveElementClick from '@/shared/util/interactive-element-utils';
import {useQueryClient} from '@tanstack/react-query';
import {
    BotIcon,
    CalendarClockIcon,
    DownloadIcon,
    EllipsisVerticalIcon,
    PencilIcon,
    SendIcon,
    Trash2Icon,
} from 'lucide-react';
import {useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface AgentListItemProps {
    agent: AiAgent;
}

// Rendered as a project group's nested row (AgentList groups agents under their project, matching the
// Projects page's own agent list), so the row carries no project badge of its own — the group heading above
// it already says which project this is. Publish state and Deploy also live on that group heading now:
// publishing is a project-level action, so a row per agent showing it would either repeat the same badge
// for every agent in the project or, worse, show a version that belongs to the project, not the agent.
const AgentListItem = ({agent}: AgentListItemProps) => {
    const [showDeleteConfirmDialog, setShowDeleteConfirmDialog] = useState(false);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    const deleteAgentMutation = useDeleteAiAgentMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to delete the agent.');
        },
        onSuccess: () => queryClient.invalidateQueries({queryKey: ['aiAgents']}),
    });

    const publishProjectMutation = usePublishProjectMutation({
        onSuccess: () => {
            invalidateAgentQueries(queryClient);

            queryClient.invalidateQueries({queryKey: ProjectKeys.project(+agent.projectId)});

            toast.success('The project has been published.');
        },
    });

    // When the agent runs, in words ("Daily at 17:38") rather than as the cron the trigger registers — the
    // reader wants to know when, not to parse five fields. Shown outright rather than as a marker's tooltip:
    // a schedule is one of the few things worth knowing about a row at a glance, and hover-to-reveal hides it
    // from anyone scanning the list. Schedule NAMES are left out — they are almost always the agent's own
    // title, which the row already carries. describeCadence falls back to the expression for a row written
    // without the picker, which is the only truthful reading of such a row.
    const scheduleSummary = useMemo(
        () =>
            (agent.channels ?? [])
                .filter((channel) => channel?.channelType === 'schedule')
                .map((channel) => {
                    const parameters = (channel?.parameters ?? {}) as Record<string, unknown>;

                    return describeCadence(fromCadenceParameters(parameters)) || String(parameters.expression ?? '');
                })
                .filter(Boolean)
                .join(', '),
        [agent.channels]
    );

    const handleConfirmDelete = () => {
        setShowDeleteConfirmDialog(false);

        deleteAgentMutation.mutate({id: agent.id});
    };

    const handleExportClick = async () => {
        try {
            await exportAgent(agent.id, agent.title);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : 'Failed to export the agent.');
        }
    };

    // The row opens the agent unless the click reached a control that means something else. Guarding the
    // controls instead — stopPropagation on the column that holds them — also swallowed every click on the
    // empty space around them, so most of the row's right-hand side did nothing.
    const handleClick = (event: React.MouseEvent) => {
        if (isInteractiveElementClick(event.target)) {
            return;
        }

        navigate(getAgentPath(agent));
    };

    return (
        <li
            className="flex cursor-pointer items-center justify-between rounded-md px-3 py-1 hover:bg-surface-neutral-primary-hover"
            onClick={handleClick}
        >
            <Link
                aria-label={`Link to agent ${agent.title}`}
                className="flex min-w-0 flex-1 items-center gap-2"
                to={getAgentPath(agent)}
            >
                <div className="flex w-80 min-w-0 shrink-0 items-center gap-2 pr-1 text-sm font-semibold">
                    <BotIcon className="size-4 shrink-0 text-content-neutral-secondary" />

                    <Tooltip>
                        <TooltipTrigger className="line-clamp-1 min-w-0 flex-1 truncate text-start">
                            {agent.title}
                        </TooltipTrigger>

                        <TooltipContent align="start" className="max-w-md break-all">
                            {agent.title}
                        </TooltipContent>
                    </Tooltip>
                </div>

                <AgentChannelChips channels={agent.channels} className="ml-6 hidden sm:flex" />
            </Link>

            <div className="flex justify-end gap-x-6">
                {isScheduledAgent(agent) && (
                    <span className="mr-6 hidden min-w-0 items-center gap-1 truncate text-sm font-normal text-content-neutral-secondary sm:flex">
                        <CalendarClockIcon className="size-4 shrink-0" />

                        <span className="truncate">{scheduleSummary || 'Scheduled'}</span>
                    </span>
                )}

                <Tooltip>
                    <TooltipTrigger className="hidden items-center text-sm text-content-neutral-secondary xl:block">
                        <span className="text-xs">
                            {agent.lastModifiedDate &&
                                `Modified at ${new Date(agent.lastModifiedDate).toLocaleDateString()} ${new Date(agent.lastModifiedDate).toLocaleTimeString()}`}
                        </span>
                    </TooltipTrigger>

                    <TooltipContent>Last Modified Date</TooltipContent>
                </Tooltip>

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button icon={<EllipsisVerticalIcon />} size="icon" variant="ghost" />
                    </DropdownMenuTrigger>

                    <DropdownMenuContent align="end">
                        <DropdownMenuItem
                            disabled={publishProjectMutation.isPending}
                            onClick={() =>
                                publishProjectMutation.mutate({id: +agent.projectId, publishProjectRequest: {}})
                            }
                        >
                            <SendIcon /> Publish Project
                        </DropdownMenuItem>

                        <DropdownMenuItem onClick={() => navigate(getAgentPath(agent))}>
                            <PencilIcon /> Edit
                        </DropdownMenuItem>

                        <DropdownMenuItem onClick={handleExportClick}>
                            <DownloadIcon /> Export
                        </DropdownMenuItem>

                        <DropdownMenuSeparator />

                        <DropdownMenuItem
                            disabled={deleteAgentMutation.isPending}
                            onClick={() => setShowDeleteConfirmDialog(true)}
                            variant="destructive"
                        >
                            <Trash2Icon /> Delete
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>

            {showDeleteConfirmDialog && (
                <DeleteAgentAlertDialog
                    agentTitle={agent.title}
                    onClose={() => setShowDeleteConfirmDialog(false)}
                    onDelete={handleConfirmDelete}
                />
            )}
        </li>
    );
};

export default AgentListItem;

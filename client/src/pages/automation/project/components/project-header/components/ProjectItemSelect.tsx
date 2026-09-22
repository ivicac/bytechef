import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuLabel,
    DropdownMenuRadioGroup,
    DropdownMenuRadioItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {Skeleton} from '@/components/ui/skeleton';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import useAgents from '@/pages/automation/agents/hooks/useAgents';
import getAgentPath from '@/pages/automation/agents/utils/getAgentPath';
import {Workflow} from '@/shared/middleware/automation/configuration';
import {BotIcon, ChevronDownIcon, WorkflowIcon} from 'lucide-react';
import {useMemo} from 'react';
import {useNavigate} from 'react-router-dom';

interface ProjectItemSelectProps {
    currentAgentId?: string;
    currentLabel?: string;
    currentProjectWorkflowId?: number;
    onWorkflowValueChange: (projectWorkflowId: number) => void;
    projectId: number;
    projectWorkflows: Workflow[];
}

const ProjectItemSelect = ({
    currentAgentId,
    currentLabel,
    currentProjectWorkflowId,
    onWorkflowValueChange,
    projectId,
    projectWorkflows,
}: ProjectItemSelectProps) => {
    const {agents} = useAgents();
    const navigate = useNavigate();

    const projectAgents = useMemo(() => agents.filter((agent) => +agent.projectId === projectId), [agents, projectId]);

    const currentValue = currentAgentId ?? currentProjectWorkflowId?.toString();

    const handleValueChange = (value: string) => {
        const selectedWorkflow = projectWorkflows.find((workflow) => workflow.projectWorkflowId!.toString() === value);

        if (selectedWorkflow) {
            onWorkflowValueChange(selectedWorkflow.projectWorkflowId!);

            return;
        }

        const selectedAgent = projectAgents.find((agent) => agent.id === value);

        if (selectedAgent) {
            navigate(getAgentPath(selectedAgent));
        }
    };

    return (
        <DropdownMenu>
            <Tooltip>
                <TooltipTrigger asChild>
                    <DropdownMenuTrigger
                        aria-label="Project item select"
                        className="flex max-w-64 items-center gap-1 rounded-md px-1.5 py-1 font-semibold text-content-neutral-primary outline-hidden hover:bg-surface-neutral-primary-hover data-[state=open]:bg-surface-neutral-primary-hover"
                    >
                        {currentLabel ? (
                            <span className="truncate">{currentLabel}</span>
                        ) : (
                            <Skeleton className="h-3 w-44" />
                        )}

                        <ChevronDownIcon className="size-4 shrink-0 text-content-neutral-secondary" />
                    </DropdownMenuTrigger>
                </TooltipTrigger>

                {currentLabel && currentLabel.length > 30 && <TooltipContent>{currentLabel}</TooltipContent>}
            </Tooltip>

            <DropdownMenuContent align="start" className="max-w-80 min-w-64">
                <DropdownMenuRadioGroup onValueChange={handleValueChange} value={currentValue ?? ''}>
                    {projectWorkflows.length > 0 && (
                        <>
                            <DropdownMenuLabel>Workflows</DropdownMenuLabel>

                            {projectWorkflows.map((workflow) => (
                                <DropdownMenuRadioItem
                                    className="cursor-pointer"
                                    key={workflow.projectWorkflowId!}
                                    title={workflow.label!.length > 32 ? workflow.label! : undefined}
                                    value={workflow.projectWorkflowId!.toString()}
                                >
                                    <WorkflowIcon className="size-4 shrink-0" />

                                    <span className="truncate">{workflow.label!}</span>
                                </DropdownMenuRadioItem>
                            ))}
                        </>
                    )}

                    {projectAgents.length > 0 && (
                        <>
                            <DropdownMenuLabel>Agents</DropdownMenuLabel>

                            {projectAgents.map((agent) => (
                                <DropdownMenuRadioItem
                                    className="cursor-pointer"
                                    key={agent.id}
                                    title={agent.title.length > 32 ? agent.title : undefined}
                                    value={agent.id}
                                >
                                    <BotIcon className="size-4 shrink-0" />

                                    <span className="truncate">{agent.title}</span>
                                </DropdownMenuRadioItem>
                            ))}
                        </>
                    )}
                </DropdownMenuRadioGroup>
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

export default ProjectItemSelect;

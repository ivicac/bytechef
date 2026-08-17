import Button from '@/components/Button/Button';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import AgentChannelIcon from '@/pages/automation/agents/components/AgentChannelIcon';
import {
    type AiAgentChannelDefinitionType,
    useAiAgentChannelDefinitions,
} from '@/pages/automation/agents/hooks/useAiAgentChannelDefinitions';
import {describeCronExpression} from '@/pages/automation/agents/utils/agentScheduleCron';
import isScheduleTrigger from '@/pages/automation/project-deployments/components/agent-deployment-channel-list/isScheduleTrigger';
import {getPageUrl} from '@/pages/automation/project-deployments/components/project-deployment-workflow-list/util/pageUrl-utils';
import {getAgentChatApi} from '@/shared/edition/agent-chat/agentChatApi';
import {AiAgentDeploymentTrigger, AiAgentDeploymentWorkflow} from '@/shared/middleware/graphql';
import {useCopyToClipboard} from '@uidotdev/usehooks';
import {CalendarClockIcon, ClipboardIcon, MessageCircleIcon} from 'lucide-react';
import {useMemo} from 'react';
import {Link} from 'react-router-dom';

// The one reserved channel key this list names, matching AiAgentChannelType.CHAT server-side.
const CHAT_CHANNEL_TYPE = 'chat';

export const WORKFLOW_CALL_TRIGGER_TYPE = 'workflow/v1/newWorkflowCall';

/**
 * A deployment carries a trigger TYPE (`<component>/v<version>/<trigger>`) where the registry is keyed by channel
 * type, so the channel is recovered from the two the definition also carries.
 *
 * The fallback to the component alone is what keeps an already-published deployment readable: its trigger type is
 * frozen at publish time, so a workflow published before Slack's channel moved from `anyEvent` to `newMessage`
 * still has to name its channel rather than print a bare lowercase component name.
 */
const findChannelDefinition = (definitions: AiAgentChannelDefinitionType[], triggerType: string) => {
    const [componentName, , triggerName] = triggerType.split('/');

    return (
        definitions.find(
            (definition) => definition.componentName === componentName && definition.triggerName === triggerName
        ) ?? definitions.find((definition) => definition.componentName === componentName)
    );
};

interface AgentDeploymentChannelListItemProps {
    /** The channel this trigger belongs to, or undefined when no deployed component declares it. */
    definition?: AiAgentChannelDefinitionType;
    /** The ProjectDeployment id and agent title, needed to open the chat as a conversation on EE. */
    projectDeploymentId: string;
    title: string;
    trigger: AiAgentDeploymentTrigger;
}

// One chip per channel, sitting inline in the agent's single row. Each trigger carries its OWN staticWebhookUrl
// (per-trigger, resolved server-side) — a workflow with both a slack and a telegram channel must never show one
// channel's URL under the other's name. Telegram is DYNAMIC_WEBHOOK (it registers its webhook with the provider
// itself), so its staticWebhookUrl is always null and it correctly gets no copy button, same as workflowCall.
const AgentDeploymentChannelListItem = ({
    definition,
    projectDeploymentId,
    title,
    trigger,
}: AgentDeploymentChannelListItemProps) => {
    /* eslint-disable @typescript-eslint/no-unused-vars */
    const [_, copyToClipboard] = useCopyToClipboard();

    const label = definition?.title || trigger.type.split('/')[0];

    const isChatTrigger = definition?.channelType === CHAT_CHANNEL_TYPE;

    // Every channel that is neither pinned nor the schedule is a messaging channel reached over a STATIC_WEBHOOK,
    // so it gets the copy-url affordance -- derived rather than listed, which is what brings twilio and infobip in
    // (a hand-written prefix list had simply never been extended to them).
    const isMessagingTrigger = definition != null && !definition.pinned && !definition.schedule;

    const staticWebhookUrl = trigger.staticWebhookUrl;

    // Null on CE, where there is no AI Hub to open a conversation in — the hosted chat page is the destination
    // there, and remains the one a shared link points at.
    const openAgentChat = getAgentChatApi().useOpenAgentChat();

    return (
        <span className="flex shrink-0 items-center gap-1 rounded-full border border-stroke-neutral-primary bg-surface-neutral-primary py-0.5 pr-0.5 pl-2 text-xs">
            <AgentChannelIcon channelType={definition?.channelType} className="mr-0.5" icon={definition?.icon} />

            <span className="truncate">{label}</span>

            <span className="flex size-6 flex-none items-center justify-center">
                {isChatTrigger && staticWebhookUrl && openAgentChat && (
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Button
                                aria-label="Open chat"
                                icon={<MessageCircleIcon />}
                                onClick={() =>
                                    openAgentChat({
                                        projectDeploymentId,
                                        title,
                                        // The same segment getPageUrl reads to build the hosted chat URL: a
                                        // workflow chat is keyed on the trigger's webhook execution id.
                                        workflowExecutionId: staticWebhookUrl.substring(
                                            staticWebhookUrl.lastIndexOf('/webhooks/') + '/webhooks/'.length
                                        ),
                                    })
                                }
                                size="iconXs"
                                variant="ghost"
                            />
                        </TooltipTrigger>

                        <TooltipContent>Open chat</TooltipContent>
                    </Tooltip>
                )}

                {isChatTrigger && staticWebhookUrl && !openAgentChat && (
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Link
                                aria-label="Open hosted chat"
                                // The iconXs Button footprint its sibling copy button uses, so a chip with a
                                // link is the same height as one with a button.
                                className="flex size-6 items-center justify-center rounded-md p-1 text-content-neutral-secondary hover:bg-surface-neutral-primary-hover hover:text-content-brand-primary"
                                to={getPageUrl('chats', undefined, staticWebhookUrl)}
                            >
                                <MessageCircleIcon className="size-3.5" />
                            </Link>
                        </TooltipTrigger>

                        <TooltipContent>Open hosted chat</TooltipContent>
                    </Tooltip>
                )}

                {isMessagingTrigger && staticWebhookUrl && (
                    <Button
                        aria-label={`Copy ${label} webhook URL`}
                        icon={<ClipboardIcon />}
                        onClick={() => copyToClipboard(staticWebhookUrl)}
                        size="iconXs"
                        variant="ghost"
                    />
                )}
            </span>
        </span>
    );
};

interface AgentDeploymentChannelListProps {
    projectDeploymentId: string;
    title: string;
    workflows: AiAgentDeploymentWorkflow[];
}

const AgentDeploymentChannelList = ({projectDeploymentId, title, workflows}: AgentDeploymentChannelListProps) => {
    const {definitions} = useAiAgentChannelDefinitions();

    // workflowCall is hidden for the same reason it is hidden on the agent detail page: the generator always
    // emits it, it carries no configuration and nothing here can act on it, so the row was permanently inert.
    const rows = useMemo(
        () =>
            workflows.flatMap((workflow) =>
                workflow.triggers
                    .filter((trigger) => trigger.type !== WORKFLOW_CALL_TRIGGER_TYPE)
                    .map((trigger) => ({
                        definition: findChannelDefinition(definitions, trigger.type),
                        key: `${workflow.workflowId}-${trigger.name}`,
                        trigger,
                    }))
            ),
        [definitions, workflows]
    );

    if (!rows.length) {
        return <span className="text-xs text-muted-foreground">No triggers configured for this deployment.</span>;
    }

    const channelRows = rows.filter(({trigger}) => !isScheduleTrigger(trigger));

    if (!channelRows.length) {
        return null;
    }

    return (
        <div className="flex min-w-0 flex-wrap items-center gap-2">
            {channelRows.map(({definition, key, trigger}) => (
                <AgentDeploymentChannelListItem
                    definition={definition}
                    key={key}
                    projectDeploymentId={projectDeploymentId}
                    title={title}
                    trigger={trigger}
                />
            ))}
        </div>
    );
};

interface AgentDeploymentScheduleProps {
    workflows: AiAgentDeploymentWorkflow[];
}

export const AgentDeploymentSchedule = ({workflows}: AgentDeploymentScheduleProps) => {
    const scheduleSummary = useMemo(
        () =>
            workflows
                .flatMap((workflow) => workflow.triggers)
                .filter((trigger) => isScheduleTrigger(trigger))
                .map(
                    (trigger) =>
                        describeCronExpression((trigger.parameters as {expression?: string} | undefined)?.expression) ||
                        'Runs on schedule'
                )
                .join(', '),
        [workflows]
    );

    if (!scheduleSummary) {
        return null;
    }

    return (
        <span className="mr-6 hidden min-w-0 items-center gap-1 truncate text-sm font-normal text-content-neutral-secondary sm:flex">
            <CalendarClockIcon className="size-4 shrink-0" />

            <span className="truncate">{scheduleSummary}</span>
        </span>
    );
};

export default AgentDeploymentChannelList;

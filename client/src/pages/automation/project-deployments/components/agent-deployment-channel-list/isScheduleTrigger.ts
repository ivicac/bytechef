import {type AiAgentDeploymentTrigger} from '@/shared/middleware/graphql';

/**
 * A deployed trigger belongs to the `schedule` channel when its type names the `schedule` component
 * (`schedule/v1/cron`) — the trigger AiAgentWorkflowGenerator emits for that channel, same as isScheduledAgent
 * checks via `channelType === 'schedule'` on the agent's current, undeployed channels. This is the deployed-version
 * equivalent: it reads the trigger type a published workflow was frozen with, not the agent's current channels.
 */
const isScheduleTrigger = (trigger: Pick<AiAgentDeploymentTrigger, 'type'>): boolean =>
    trigger.type.split('/')[0] === 'schedule';

export default isScheduleTrigger;

import AgentDetailContent from '@/pages/automation/agents/AgentDetailContent';
import getAgentPath from '@/pages/automation/agents/utils/getAgentPath';
import {useAiAgentQuery} from '@/shared/middleware/graphql';
import {ExternalLinkIcon} from 'lucide-react';
import {Link} from 'react-router-dom';

interface AiHubAiAgentViewerPropsI {
    aiAgentId: string;
    name: string;
}

/**
 * Reuses the CE {@link AgentDetailContent} card composition (instructions, model, channels, tools, skills,
 * sub-agents, knowledge base, memory, settings) inside the AI Hub resource panel — the same EE→CE import
 * direction already used elsewhere (e.g. {@code ApiCollectionListItem} importing {@code
 * ProjectDeploymentDialog}). The routed {@code AgentDetail} page's own test-chat sidebar is intentionally
 * NOT reused here: the AI Hub tab already sits beside the hub's own chat, so a second chat surface would be
 * redundant chrome rather than a useful feature.
 */
const AiHubAiAgentViewer = ({aiAgentId, name}: AiHubAiAgentViewerPropsI) => {
    // The routed agent page now lives under its project, so the link needs the agent's projectId — which
    // this component otherwise has no reason to fetch. TanStack Query dedupes this against
    // AgentDetailContent's own useAiAgentQuery call for the same id.
    const {data} = useAiAgentQuery({id: aiAgentId}, {enabled: !!aiAgentId});

    const agent = data?.aiAgent;

    return (
        <div className="flex size-full flex-col">
            <header className="flex shrink-0 items-center justify-between gap-2 border-b border-stroke-neutral-secondary px-4 py-3">
                <span className="truncate text-sm font-semibold text-content-neutral-primary">{name}</span>

                {agent && (
                    <Link
                        className="flex shrink-0 items-center gap-1 text-xs text-content-brand-primary hover:underline"
                        rel="noreferrer"
                        target="_blank"
                        to={getAgentPath(agent)}
                    >
                        Open in full view
                        <ExternalLinkIcon className="size-3" />
                    </Link>
                )}
            </header>

            <div className="min-h-0 flex-1 overflow-y-auto">
                <AgentDetailContent agentId={aiAgentId} />
            </div>
        </div>
    );
};

export default AiHubAiAgentViewer;

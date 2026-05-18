import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuSub,
    DropdownMenuSubContent,
    DropdownMenuSubTrigger,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {useWorkspaceAiGatewayModelsQuery, useWorkspaceAiGatewayProvidersQuery} from '@/shared/middleware/graphql';
import {BotIcon, BrainCircuitIcon, ChevronDownIcon, WorkflowIcon} from 'lucide-react';
import {useMemo, useState} from 'react';
import {twMerge} from 'tailwind-merge';

/**
 * Minimal shape the {@link ModelPicker} needs to render a personal-agent row. Decoupled from the full
 * {@code AiHubPersonalAgentI} so callers can pass either the codegen type or a hand-shaped projection
 * without dragging the GraphQL types into the picker module.
 */
export interface ModelPickerPersonalAgentI {
    id: number;
    name: string;
    title: string | null;
}

/**
 * Minimal shape for a chat-enabled workflow row. Two ids — {@code workflowExecutionId} is the composite
 * identifier the {@code createWorkflowChatAiHubTask} mutation expects; {@code projectDeploymentId}
 * groups by deployment and is also required by the mutation. {@code label} is the user-visible row label
 * (callers format this however they want; AiHub passes {@code "ProjectName — WorkflowLabel"} to match the
 * sidebar's grouping).
 */
export interface ModelPickerWorkflowChatI {
    label: string;
    projectDeploymentId: string;
    workflowExecutionId: string;
}

/**
 * Shared LLM provider/model picker. Used in three places:
 *
 *   - Copilot panel input toolbar (per-conversation override, resets on new conversation).
 *   - AI Hub chat panel input toolbar (per-conversation override; hidden for WORKFLOW_CHAT kinds).
 *   - Personal Agent edit form (replaces the previous two-`Select` block with a single picker).
 *
 * Two data sources, both already shipped: {@link useWorkspaceAiGatewayProvidersQuery} for the
 * workspace's enabled providers and {@link useWorkspaceAiGatewayModelsQuery} for the model rows
 * (joined to providers via `providerId`). The merge of application.yml + AI Providers admin page
 * happens server-side inside those GraphQL resolvers — the picker just consumes the resolved list.
 *
 * The trigger button is intentionally minimal (provider/model name + chevron). It compresses to fit
 * compact toolbars; full layouts can wrap with their own container for additional context.
 *
 * UX shape mirrors the n8n-style cascade: outer menu lists alphabetized providers, each cascading
 * right into that provider's models. The chevron-right on each provider row is auto-rendered by
 * `DropdownMenuSubTrigger`. The search input at the top filters provider names; typing stops
 * `onKeyDown` propagation so Radix's menu-nav keyboard handler doesn't swallow letter keys.
 */
export interface ModelPickerPropsI {
    /**
     * Personal-agent's configured default model name. When provided alongside `agentDefaultProvider`
     * AND `selectedProvider`/`selectedModel` are both null, the trigger reads "Agent default" and
     * the sentinel item in the menu reverts to that agent's pinned model rather than the workspace
     * default. Only meaningful for AI Hub PERSONAL_AGENT conversations.
     */
    agentDefaultModel?: string | null;

    /**
     * Personal-agent's configured default provider type (lowercase, matching `provider.type.toLowerCase()`).
     * Pair with {@link agentDefaultModel}. Both must be set for the agent-default sentinel to render.
     */
    agentDefaultProvider?: string | null;

    /**
     * When true, the trigger collapses to an icon-only square button — no label, no chevron — with the
     * current selection surfaced via the native `title` tooltip on hover. Used by the AI Hub chat panel
     * when the resource panel is open and horizontal space is tight. Independent of {@link layout}.
     */
    iconOnly?: boolean;

    /**
     * Visual layout hint. `compact` (default) shows the trigger as a small inline button suited for a
     * chat input toolbar; `full` adds extra padding for form contexts (the Personal Agent edit form).
     */
    layout?: 'compact' | 'full';

    /**
     * Fires when the user picks a model, picks the default sentinel, or otherwise changes selection.
     * Both args are `null` when reverting to the highest-precedence default available (agent default
     * if `agentDefault*` props are passed, else workspace default).
     */
    onChange: (provider: string | null, model: string | null) => void;

    /**
     * Fires when the user picks a personal agent from the "Personal agents" cascade. Only invoked when
     * {@link personalAgents} is non-empty; absence of this callback hides the entire section regardless
     * of {@link personalAgents} contents (so callers that pass agents for display without an action
     * don't render an orphan cascade). Picking an agent is a navigation action, NOT a model override —
     * the caller is expected to start a fresh PERSONAL_AGENT conversation bound to the picked agent.
     */
    onSelectPersonalAgent?: (agentId: number) => void;

    /**
     * Fires when the user picks a chat-enabled workflow from the "Workflow chats" cascade. Same opt-in
     * shape as {@link onSelectPersonalAgent} — absence hides the section. The mutation that backs this
     * (createWorkflowChatAiHubTask) is server-side idempotent so re-picking the same workflow returns
     * the existing task instead of duplicating; the caller can therefore treat every pick as "navigate
     * to this workflow's chat" without managing find-or-create logic itself.
     */
    onSelectWorkflowChat?: (workflowExecutionId: string, projectDeploymentId: string, label: string) => void;

    /**
     * The user's personal agents. When non-empty AND {@link onSelectPersonalAgent} is set, a "Personal
     * agents" cascade renders above the providers list. Each entry's {@code title} (or {@code name}
     * fallback) is the visible label. Pass an empty array (or omit) to hide the section.
     */
    personalAgents?: ModelPickerPersonalAgentI[];

    /**
     * Chat-enabled workflows in the workspace. When non-empty AND {@link onSelectWorkflowChat} is set, a
     * "Workflow chats" cascade renders between the personal-agents cascade (if any) and the providers
     * list. Pass an empty array (or omit) to hide the section.
     */
    workflowChats?: ModelPickerWorkflowChatI[];

    /**
     * Currently-selected model `name` (NOT `alias`). Pair with {@link selectedProvider}. Both null =
     * "use default" (workspace or agent depending on props).
     */
    selectedModel: string | null;

    /**
     * Currently-selected provider type (lowercase). Pair with {@link selectedModel}.
     */
    selectedProvider: string | null;

    /**
     * Label rendered in the trigger and the sentinel menu item when no override is selected and no
     * agent-default is provided. Defaults to "Workspace default" — callers in the Personal Agent
     * form pass "Use workspace default" to read more naturally in a form context.
     */
    workspaceDefaultLabel?: string;

    /** The workspace whose enabled providers/models drive the picker. */
    workspaceId: number;
}

/**
 * Filters a `T | null` array down to non-null entries with the type narrowed.
 */
const isPresent = <T,>(value: T | null): value is T => value != null;

const ModelPicker = ({
    agentDefaultModel,
    agentDefaultProvider,
    iconOnly = false,
    layout = 'compact',
    onChange,
    onSelectPersonalAgent,
    onSelectWorkflowChat,
    personalAgents,
    selectedModel,
    selectedProvider,
    workflowChats,
    workspaceDefaultLabel = 'Workspace default',
    workspaceId,
}: ModelPickerPropsI) => {
    const [open, setOpen] = useState(false);
    const [searchQuery, setSearchQuery] = useState('');

    const queryEnabled = workspaceId > 0;
    const workspaceIdString = workspaceId > 0 ? String(workspaceId) : '';

    const {data: providersData} = useWorkspaceAiGatewayProvidersQuery(
        {workspaceId: workspaceIdString},
        {enabled: queryEnabled}
    );
    const {data: modelsData} = useWorkspaceAiGatewayModelsQuery(
        {workspaceId: workspaceIdString},
        {enabled: queryEnabled}
    );

    // Drop null entries (the codegen marks list items as nullable) and disabled rows. We never offer
    // disabled providers/models in the picker — the server-side resolver would reject them anyway and
    // showing them would let the user pick something that silently falls back to workspace default.
    const enabledProviders = useMemo(
        () =>
            (providersData?.workspaceAiGatewayProviders ?? []).filter(isPresent).filter((provider) => provider.enabled),
        [providersData]
    );

    const enabledModels = useMemo(
        () => (modelsData?.workspaceAiGatewayModels ?? []).filter(isPresent).filter((model) => model.enabled),
        [modelsData]
    );

    // Alphabetize by display name AFTER the enabled-filter so the order is stable across renders even
    // when the admin reorders providers in the AI Providers settings page (which sorts by created date).
    const sortedProviders = useMemo(() => {
        const query = searchQuery.trim().toLowerCase();
        const filtered = query
            ? enabledProviders.filter(
                  (provider) =>
                      provider.name.toLowerCase().includes(query) || provider.type.toLowerCase().includes(query)
              )
            : enabledProviders;

        return [...filtered].sort((firstProvider, secondProvider) =>
            firstProvider.name.localeCompare(secondProvider.name)
        );
    }, [enabledProviders, searchQuery]);

    // The Personal agents section renders only when BOTH the data and the action callback are present.
    // Passing personalAgents without onSelectPersonalAgent would create rows the user could focus but not
    // act on — confusing. Same search filter as providers (case-insensitive substring on the visible
    // label) so typing 'research' narrows both sections in lockstep.
    const showPersonalAgentsSection = onSelectPersonalAgent != null && (personalAgents?.length ?? 0) > 0;

    const sortedPersonalAgents = useMemo(() => {
        if (!showPersonalAgentsSection || personalAgents == null) {
            return [];
        }

        const query = searchQuery.trim().toLowerCase();
        const filtered = query
            ? personalAgents.filter(
                  (agent) =>
                      (agent.title ?? '').toLowerCase().includes(query) || agent.name.toLowerCase().includes(query)
              )
            : personalAgents;

        return [...filtered].sort((firstAgent, secondAgent) =>
            (firstAgent.title ?? firstAgent.name).localeCompare(secondAgent.title ?? secondAgent.name)
        );
    }, [personalAgents, searchQuery, showPersonalAgentsSection]);

    // Workflow chats cascade — same opt-in shape as personal agents. Hidden when either the data or the
    // callback is absent so callers that surface workflow chats in some contexts and not others (e.g. AI
    // Hub vs. Copilot) don't have to drop the prop entirely; omitting the callback is enough.
    const showWorkflowChatsSection = onSelectWorkflowChat != null && (workflowChats?.length ?? 0) > 0;

    const sortedWorkflowChats = useMemo(() => {
        if (!showWorkflowChatsSection || workflowChats == null) {
            return [];
        }

        const query = searchQuery.trim().toLowerCase();
        const filtered = query
            ? workflowChats.filter((chat) => chat.label.toLowerCase().includes(query))
            : workflowChats;

        return [...filtered].sort((firstChat, secondChat) => firstChat.label.localeCompare(secondChat.label));
    }, [searchQuery, showWorkflowChatsSection, workflowChats]);

    // Trigger label: prefer the model's alias when set (e.g., "GPT-4o" instead of "gpt-4o-2024-08-06"),
    // falling back to the bare model name. The alias is admin-configurable in AI Gateway Models settings.
    const triggerLabel = useMemo(() => {
        if (selectedProvider && selectedModel) {
            const model = enabledModels.find((candidate) => candidate.name === selectedModel);

            return model?.alias || model?.name || selectedModel;
        }

        if (agentDefaultProvider && agentDefaultModel) {
            const model = enabledModels.find((candidate) => candidate.name === agentDefaultModel);

            return model?.alias || model?.name || agentDefaultModel;
        }

        return workspaceDefaultLabel;
    }, [
        agentDefaultModel,
        agentDefaultProvider,
        enabledModels,
        selectedModel,
        selectedProvider,
        workspaceDefaultLabel,
    ]);

    const defaultSentinelLabel =
        agentDefaultProvider && agentDefaultModel ? 'Use agent default' : `Use ${workspaceDefaultLabel.toLowerCase()}`;

    const handleSelectDefault = () => {
        onChange(null, null);
        setOpen(false);
        setSearchQuery('');
    };

    const handleSelectModel = (providerType: string, modelName: string) => {
        // Provider type goes over the wire lowercased to match the existing personal-agent form convention
        // (see AiHubPersonalAgentForm.tsx:122). The server's resolver uses equalsIgnoreCase so the case
        // doesn't matter for correctness, but lowercasing keeps everything consistent.
        onChange(providerType.toLowerCase(), modelName);
        setOpen(false);
        setSearchQuery('');
    };

    const handleSelectPersonalAgent = (agentId: number) => {
        // Fire the navigation callback then close the menu. The caller (AiHubPanel) handles the actual
        // task creation + route navigation — the picker stays pure UI and doesn't touch mutations or
        // router state directly.
        onSelectPersonalAgent?.(agentId);
        setOpen(false);
        setSearchQuery('');
    };

    const handleSelectWorkflowChat = (chat: ModelPickerWorkflowChatI) => {
        // Same callback-then-close pattern as personal agents. Server-side mutation is idempotent so
        // re-picking the same workflow returns the existing chat task — caller can treat every pick as a
        // pure navigation.
        onSelectWorkflowChat?.(chat.workflowExecutionId, chat.projectDeploymentId, chat.label);
        setOpen(false);
        setSearchQuery('');
    };

    const triggerClassName = twMerge(
        // Compact layout (composer toolbar) drops the always-on border + opaque background so the
        // trigger sits flush with its ghost-button neighbors (paperclip, mic). The border is reserved
        // for hover/focus to give a clear affordance without competing with the composer's own border.
        // Full layout (form context) keeps the visible border so it reads as an input control.
        'inline-flex items-center gap-1.5 rounded-md text-sm font-medium text-foreground transition-colors hover:text-accent-foreground focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50',
        layout === 'compact'
            ? 'h-7 border border-transparent bg-transparent px-2 hover:border-input hover:bg-accent'
            : 'h-9 w-full justify-between border border-input bg-background px-3 hover:bg-accent',
        // Icon-only collapses the trigger to a 28px square so it matches the composer's other ghost
        // icon buttons (paperclip is size-7). Border is dropped — the hover background alone reads
        // as a ghost icon button, same visual weight as the controls it sits next to.
        iconOnly && 'size-7 justify-center border-0 bg-transparent px-0'
    );

    return (
        <DropdownMenu onOpenChange={setOpen} open={open}>
            <DropdownMenuTrigger asChild>
                <button
                    aria-label="Select LLM provider and model"
                    className={triggerClassName}
                    title={iconOnly ? triggerLabel : undefined}
                    type="button"
                >
                    <BrainCircuitIcon className="size-4 shrink-0 text-muted-foreground" />

                    {!iconOnly && <span className="truncate">{triggerLabel}</span>}

                    {/*
                     * Chevron kept only for the full layout (Personal Agent form) where the trigger
                     * reads as a select control next to other form fields. In compact toolbars
                     * (AI Hub home, AI Hub task, Copilot) the chevron is visual noise — the trigger
                     * is short and clearly clickable from context, and dropping it tightens the
                     * trigger so it sits flush with neighboring ghost buttons.
                     */}

                    {!iconOnly && layout === 'full' && (
                        <ChevronDownIcon className="size-4 shrink-0 text-muted-foreground" />
                    )}
                </button>
            </DropdownMenuTrigger>

            <DropdownMenuContent align="start" className="w-72">
                {/*
                 * Controlled search input. Lives inside the menu content so it shares the popover region
                 * with the items. Stops `onKeyDown` propagation so Radix's menu keyboard navigation
                 * (arrow keys, letter type-ahead) doesn't swallow the user's typing.
                 */}

                <div className="px-2 py-1.5">
                    <input
                        aria-label="Search providers"
                        className="w-full rounded-sm border border-input bg-background px-2 py-1 text-sm placeholder:text-muted-foreground focus:ring-1 focus:ring-ring focus:outline-none"
                        onChange={(event) => setSearchQuery(event.target.value)}
                        onKeyDown={(event) => event.stopPropagation()}
                        placeholder="Search providers..."
                        type="text"
                        value={searchQuery}
                    />
                </div>

                <DropdownMenuSeparator />

                <DropdownMenuItem onSelect={handleSelectDefault}>
                    <BrainCircuitIcon className="text-muted-foreground" />

                    <span>{defaultSentinelLabel}</span>
                </DropdownMenuItem>

                <DropdownMenuSeparator />

                {/*
                 * Personal agents cascade. Rendered ABOVE the providers list because it's the higher-level
                 * concept ("pick a personality") — picking a model is a fallback for users who just want a
                 * raw chat with no agent personality applied. Hidden entirely (no header, no separator)
                 * when there are no agents or no onSelectPersonalAgent callback, so contexts that don't
                 * support agent switching (Copilot, Personal Agent edit form) don't see a dead section.
                 */}

                {showPersonalAgentsSection && (
                    <DropdownMenuSub>
                        <DropdownMenuSubTrigger>
                            <BotIcon className="text-muted-foreground" />

                            <span>Personal agents</span>
                        </DropdownMenuSubTrigger>

                        <DropdownMenuSubContent className="max-h-80 overflow-y-auto">
                            {sortedPersonalAgents.length === 0 ? (
                                <div className="px-2 py-1.5 text-sm text-muted-foreground">No matching agents.</div>
                            ) : (
                                sortedPersonalAgents.map((agent) => (
                                    <DropdownMenuItem
                                        key={agent.id}
                                        onSelect={() => handleSelectPersonalAgent(agent.id)}
                                    >
                                        <span className="truncate">{agent.title || agent.name}</span>
                                    </DropdownMenuItem>
                                ))
                            )}
                        </DropdownMenuSubContent>
                    </DropdownMenuSub>
                )}

                {/*
                 * Workflow chats cascade. Same shape as personal agents — opt-in via callback + data
                 * presence. Sits between agents and providers because workflow chats are the rarer
                 * conceptual fit ("talk to a workflow's chat endpoint") and providers are the universal
                 * fallback ("just pick a raw model").
                 */}

                {showWorkflowChatsSection && (
                    <DropdownMenuSub>
                        <DropdownMenuSubTrigger>
                            <WorkflowIcon className="text-muted-foreground" />

                            <span>Workflow chats</span>
                        </DropdownMenuSubTrigger>

                        <DropdownMenuSubContent className="max-h-80 overflow-y-auto">
                            {sortedWorkflowChats.length === 0 ? (
                                <div className="px-2 py-1.5 text-sm text-muted-foreground">No matching workflows.</div>
                            ) : (
                                sortedWorkflowChats.map((chat) => (
                                    <DropdownMenuItem
                                        key={chat.workflowExecutionId}
                                        onSelect={() => handleSelectWorkflowChat(chat)}
                                    >
                                        <span className="truncate">{chat.label}</span>
                                    </DropdownMenuItem>
                                ))
                            )}
                        </DropdownMenuSubContent>
                    </DropdownMenuSub>
                )}

                {(showPersonalAgentsSection || showWorkflowChatsSection) && <DropdownMenuSeparator />}

                {sortedProviders.length === 0 ? (
                    <div className="px-2 py-1.5 text-sm text-muted-foreground">
                        {searchQuery.trim() ? 'No matching providers.' : 'No providers configured.'}
                    </div>
                ) : (
                    sortedProviders.map((provider) => {
                        const providerModels = enabledModels.filter((model) => model.providerId === provider.id);

                        if (providerModels.length === 0) {
                            // Hide providers with no enabled models — selecting one would have no models
                            // to drill into, which is a dead end.
                            return null;
                        }

                        const providerTypeLowercase = provider.type.toLowerCase();

                        return (
                            <DropdownMenuSub key={provider.id}>
                                <DropdownMenuSubTrigger>
                                    <BrainCircuitIcon className="text-muted-foreground" />

                                    <span className="truncate">{provider.name}</span>
                                </DropdownMenuSubTrigger>

                                <DropdownMenuSubContent className="max-h-80 overflow-y-auto">
                                    {providerModels.map((model) => (
                                        <DropdownMenuItem
                                            key={model.id}
                                            onSelect={() => handleSelectModel(providerTypeLowercase, model.name)}
                                        >
                                            <span className="truncate">{model.alias || model.name}</span>
                                        </DropdownMenuItem>
                                    ))}
                                </DropdownMenuSubContent>
                            </DropdownMenuSub>
                        );
                    })
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

export default ModelPicker;

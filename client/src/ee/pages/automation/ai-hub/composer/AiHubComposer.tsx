import {CommandItem} from '@/components/ui/command';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import AiHubConnectorsBranch from '@/ee/pages/automation/ai-hub/composer/AiHubConnectorsBranch';
import AiHubSkillsBranch from '@/ee/pages/automation/ai-hub/composer/AiHubSkillsBranch';
import {
    ReferencedResourceKindType,
    aiHubComposerStore,
    useAiHubComposerStore,
} from '@/ee/pages/automation/ai-hub/composer/stores/useAiHubComposerStore';
import ResourcePickerMenu, {
    ResourcePickerCustomBranchI,
    ResourcePickerSelectionI,
} from '@/ee/pages/automation/ai-hub/resource-picker/ResourcePickerMenu';
import {aiHubTabsStore, attachTab} from '@/ee/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {DEVELOPMENT_ENVIRONMENT} from '@/shared/constants';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {ChevronRightIcon, HexagonIcon, PlugIcon, PlusIcon} from 'lucide-react';

// The "+" menu is the composer's single picker: the 9 reference kinds (files, workflows, data tables,
// knowledge bases, executions, AI agents, …) plus two caller-supplied branches, Connectors and Skills. Those
// two were standalone toolbar buttons — a plug icon and a '/' icon — until the toolbar's four coordinate
// affordances were folded into one; only the paperclip stayed out, because uploading a file is not
// referencing one.
//
// AI Agents were once deliberately excluded here, on the grounds that the model picker's Agent Chats cascade
// already reached them. That cascade STARTS A CHAT with the agent; it never lets you look at how the agent is
// configured. Referencing one instead opens its detail in the resource panel (AiHubAiAgentViewer) and hands
// the LLM the agent as context — a different job, so the branch belongs here too.
const AiHubComposer = () => {
    const referencedResources = useAiHubComposerStore((state) => state.referencedResources);
    // The picker's open state is store-held so the textarea's '@' key can raise it — see the field's doc in
    // useAiHubComposerStore. The "+" button still opens it the ordinary way, through the same flag.
    const resourcePickerOpen = useAiHubComposerStore((state) => state.resourcePickerOpen);
    const setResourcePickerOpen = useAiHubComposerStore((state) => state.setResourcePickerOpen);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const environmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    /*
     * Selecting a resource in the picker (opened by the "+" button or by typing '@') does TWO things:
     *   1. Adds the resource to `aiHubComposerStore.referencedResources` so the LLM sees it as
     *      part of the next prompt's state.
     *   2. Opens it as a tab in the right panel via `aiHubTabsStore` — "if I @-mention a file, I should be
     *      able to see it".
     *
     * Both halves are undone together by AiHubChatComposer's handleRemoveReference — keep them in step.
     *
     * Workflows require `projectId` and `projectWorkflowId` to open as tabs (the workflow viewer needs the
     * parent project for routing), so the workflow shape is passed separately via {@link handleSelectWorkflow}.
     */
    const handleSelect = (id: string, kind: ReferencedResourceKindType, name: string) => {
        const tabsStore = aiHubTabsStore.getState();

        let attachment: {ownsTab: boolean; tabId: string} | undefined;

        if (kind === 'file') {
            attachment = attachTab(() => tabsStore.openFileTab(id, name));
        } else if (kind === 'dataTable') {
            attachment = attachTab(() => tabsStore.openDataTableTab(id, name));
        } else if (kind === 'knowledgeBase') {
            attachment = attachTab(() => tabsStore.openKnowledgeBaseTab(id, name));
        } else if (kind === 'aiAgent') {
            attachment = attachTab(() => tabsStore.openAiAgentTab(id, name));
        } else if (kind === 'workflowExecution') {
            // ResourcePickerMenu stringifies execution.id at the call site (handleSelect(String(execution.id), ...));
            // openWorkflowExecutionTab expects a numeric workflowExecutionId because the tab type and the
            // downstream viewer/query both key on number — see AiHubWorkflowExecutionViewer.
            attachment = attachTab(() => tabsStore.openWorkflowExecutionTab(Number(id), name));
        }

        // apiCollection / mcpServer / chat have no viewer, so they attach as a chip with no tab at all.
        aiHubComposerStore
            .getState()
            .addReference({id, kind, name, ownsTab: attachment?.ownsTab, tabId: attachment?.tabId});
    };

    const handleSelectWorkflow = (id: string, name: string, projectId: string, projectWorkflowId: number) => {
        const {ownsTab, tabId} = attachTab(() =>
            aiHubTabsStore.getState().openWorkflowTab(id, projectId, projectWorkflowId, name)
        );

        aiHubComposerStore.getState().addReference({id, kind: 'workflow', name, ownsTab, tabId});
    };

    const handleResourceSelect = (selection: ResourcePickerSelectionI) => {
        if (selection.projectId != null && selection.projectWorkflowId != null) {
            handleSelectWorkflow(selection.id, selection.name, selection.projectId, selection.projectWorkflowId);
        } else {
            handleSelect(selection.id, selection.kind, selection.name);
        }
    };

    const customBranches: ResourcePickerCustomBranchI[] = [
        {
            key: 'connectors',
            renderBranch: (onBack, onClose) => <AiHubConnectorsBranch onBack={onBack} onClose={onClose} />,
            renderRootItem: (onEnter) => (
                <CommandItem onSelect={onEnter} value="root-connectors">
                    <PlugIcon className="mr-2 size-3.5" />

                    <span className="flex-1">Connectors</span>

                    <ChevronRightIcon className="size-3.5 text-muted-foreground" />
                </CommandItem>
            ),
        },
        {
            key: 'skills',
            renderBranch: (onBack, onClose) => <AiHubSkillsBranch onBack={onBack} onClose={onClose} />,
            renderRootItem: (onEnter) => (
                <CommandItem onSelect={onEnter} value="root-skills">
                    <HexagonIcon className="mr-2 size-3.5" />

                    <span className="flex-1">Skills</span>

                    <ChevronRightIcon className="size-3.5 text-muted-foreground" />
                </CommandItem>
            ),
        },
    ];

    return (
        // Tooltip wraps the menu (not just the button) so the Radix Tooltip.Root is an ancestor of both the
        // TooltipTrigger — which `ResourcePickerMenu` nests inside its PopoverTrigger asChild — and the
        // TooltipContent sibling below. Both triggers compose onto the same "+" button via asChild.
        <Tooltip>
            <ResourcePickerMenu
                customBranches={customBranches}
                environmentId={environmentId ?? DEVELOPMENT_ENVIRONMENT}
                onOpenChange={setResourcePickerOpen}
                onSelect={handleResourceSelect}
                open={resourcePickerOpen}
                trigger={
                    <TooltipTrigger asChild>
                        <button
                            aria-label="Add resources"
                            className="flex size-7 items-center justify-center rounded-full text-muted-foreground hover:bg-accent hover:text-foreground"
                            type="button"
                        >
                            <PlusIcon className="size-4" />

                            {referencedResources.length > 0 && (
                                <span className="ml-1 text-xs">{referencedResources.length}</span>
                            )}
                        </button>
                    </TooltipTrigger>
                }
                workspaceId={currentWorkspaceId ?? 0}
            />

            {/* Below, not the default above: the "+" sits in the toolbar UNDER the textarea, so a top-side
                tooltip covers the message input and its placeholder. */}

            <TooltipContent side="bottom">Add resources</TooltipContent>
        </Tooltip>
    );
};

export default AiHubComposer;

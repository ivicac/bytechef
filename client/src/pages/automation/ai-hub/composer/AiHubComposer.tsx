import {CommandEmpty, CommandGroup, CommandItem} from '@/components/ui/command';
import {
    ReferencedResourceKindType,
    aiHubComposerStore,
    useAiHubComposerStore,
} from '@/pages/automation/ai-hub/composer/stores/useAiHubComposerStore';
import ResourcePickerMenu, {
    ResourcePickerSelectionI,
    ResourcePickerToolsBranchI,
} from '@/pages/automation/ai-hub/resource-picker/ResourcePickerMenu';
import {useAiHubStore} from '@/pages/automation/ai-hub/stores/useAiHubStore';
import {aiHubTabsStore} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import TaskToolDialog, {TaskToolDialogTargetI} from '@/pages/automation/ai-hub/tools/dialogs/TaskToolDialog';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {DEVELOPMENT_ENVIRONMENT} from '@/shared/constants';
import {useAiHubTaskToolableComponentsQuery} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {ChevronLeftIcon, ChevronRightIcon, PlusIcon, WrenchIcon} from 'lucide-react';
import {useMemo, useState} from 'react';
import InlineSVG from 'react-inlinesvg';

const AiHubComposer = () => {
    const [dialogTarget, setDialogTarget] = useState<TaskToolDialogTargetI | null>(null);
    // Tools-branch drilldown state. The Tools branch is composer-owned (ResourcePickerMenu only tracks the
    // ['tools'] root entry), so the second-level "pick a component" → "pick a tool" navigation lives here.
    // `null` shows the component list; a componentName shows that component's tool list.
    const [selectedToolComponentName, setSelectedToolComponentName] = useState<string | null>(null);
    // Mirrors whether the user is currently inside the Tools branch — drives the lazy `enabled` flag on the
    // toolable-components query so the catalog scan only runs when the branch is open.
    const [toolsBranchActive, setToolsBranchActive] = useState(false);

    const referencedResources = useAiHubComposerStore((state) => state.referencedResources);
    const taskId = useAiHubStore((state) => state.taskId);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const environmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    // Tool catalog walk — only fetched when the user enters the Tools branch so first-render of the
    // composer doesn't pay for the per-component cluster element scan.
    const {data: toolableComponentsData} = useAiHubTaskToolableComponentsQuery(
        {workspaceId: String(currentWorkspaceId ?? '')},
        {enabled: currentWorkspaceId != null && toolsBranchActive}
    );

    const toolableComponents = useMemo(() => {
        // Sort alphabetically by display label so the picker is scannable. The server returns components
        // in catalog/registration order which has no meaning to the user — without this sort the list
        // looks random (PostHog, Pushover, Mixpanel, Attio, ...). Falls back to componentName when title
        // isn't set so unlabelled components still sort consistently.
        const components = toolableComponentsData?.aiHubTaskToolableComponents ?? [];

        return [...components].sort((a, b) => {
            const labelA = (a.title || a.componentName).toLowerCase();
            const labelB = (b.title || b.componentName).toLowerCase();

            return labelA.localeCompare(labelB);
        });
    }, [toolableComponentsData]);

    // When a component is selected inside the Tools branch, find the matching component so we can render
    // its tool list. Treats `selectedToolComponentName` as the source of truth — if the user picks a
    // different component the next render swaps the visible tool list.
    const selectedToolableComponent = useMemo(() => {
        if (selectedToolComponentName == null) {
            return null;
        }

        return toolableComponents.find((component) => component.componentName === selectedToolComponentName) ?? null;
    }, [selectedToolComponentName, toolableComponents]);

    /*
     * Selecting a resource in the composer's @-mention popover does TWO things now:
     *   1. Adds the resource to `aiHubComposerStore.referencedResources` so the LLM sees it as
     *      part of the next prompt's state (the existing behavior).
     *   2. Opens it as a tab in the right panel via `aiHubTabsStore`. This matches the user's
     *      mental model: "if I @-mention a file, I should be able to see it" — without (2), referenced
     *      resources had no visible affordance and the user had to separately click the right-panel +
     *      menu to view them.
     *
     * Workflows require `projectId` and `projectWorkflowId` to open as tabs (the workflow viewer needs
     * the parent project for routing). The existing reference-store schema only carries `id`/`kind`/`name`,
     * so the workflow shape is passed separately via {@link handleSelectWorkflow}. The other kinds use
     * {@link handleSelect} because their `id` alone is enough.
     */
    const handleSelect = (id: string, kind: ReferencedResourceKindType, name: string) => {
        aiHubComposerStore.getState().addReference({id, kind, name});

        const tabsStore = aiHubTabsStore.getState();

        if (kind === 'file') {
            tabsStore.openFileTab(id, name);
        } else if (kind === 'dataTable') {
            tabsStore.openDataTableTab(id, name);
        } else if (kind === 'knowledgeBase') {
            tabsStore.openKnowledgeBaseTab(id, name);
        } else if (kind === 'workflowExecution') {
            // ResourcePickerMenu stringifies execution.id at the call site (handleSelect(String(execution.id), ...));
            // openWorkflowExecutionTab expects a numeric workflowExecutionId because the tab type and the
            // downstream viewer/query both key on number — see AiHubWorkflowExecutionViewer.
            tabsStore.openWorkflowExecutionTab(Number(id), name);
        }
    };

    const handleSelectWorkflow = (id: string, name: string, projectId: string, projectWorkflowId: number) => {
        aiHubComposerStore.getState().addReference({id, kind: 'workflow', name});

        aiHubTabsStore.getState().openWorkflowTab(id, projectId, projectWorkflowId, name);
    };

    const handleResourceSelect = (selection: ResourcePickerSelectionI) => {
        if (selection.projectId != null && selection.projectWorkflowId != null) {
            handleSelectWorkflow(selection.id, selection.name, selection.projectId, selection.projectWorkflowId);
        } else {
            handleSelect(selection.id, selection.kind, selection.name);
        }
    };

    const toolsBranch: ResourcePickerToolsBranchI = useMemo(
        () => ({
            renderBranch: (onBack, onClose) => (
                <>
                    <CommandGroup>
                        <CommandItem
                            // Two-step back inside the Tools branch: from a component's tool list pop to the
                            // component list; from the component list pop to the picker root via `onBack`.
                            onSelect={() => {
                                if (selectedToolComponentName != null) {
                                    setSelectedToolComponentName(null);
                                } else {
                                    setToolsBranchActive(false);
                                    onBack();
                                }
                            }}
                            value="back-to-root"
                        >
                            <ChevronLeftIcon className="mr-2 size-3.5" />

                            <span className="flex-1 text-muted-foreground">Back</span>
                        </CommandItem>
                    </CommandGroup>

                    {selectedToolComponentName == null ? (
                        <CommandGroup heading="Tools — pick a component">
                            {toolableComponents.length === 0 && <CommandEmpty>No components with tools.</CommandEmpty>}

                            {toolableComponents.map((component) => (
                                <CommandItem
                                    key={`tool-component-${component.componentName}`}
                                    // The drilldown is gated on a selected component so the user
                                    // sees a manageable per-component tool list rather than every
                                    // toolable cluster element across the catalog at once.
                                    onSelect={() => setSelectedToolComponentName(component.componentName)}
                                    value={`tool-component-${component.componentName}-${component.title ?? ''}`}
                                >
                                    {component.icon ? (
                                        // Component icons are returned by the server as raw SVG strings
                                        // (resolved via IconUtils.readIcon at definition load), NOT URLs.
                                        // `<img src={...}>` would render a broken-image placeholder; the
                                        // rest of the codebase uses `react-inlinesvg`'s `InlineSVG` to
                                        // inject the SVG markup directly into the DOM.
                                        <InlineSVG className="mr-2 size-3.5" src={component.icon} />
                                    ) : (
                                        <WrenchIcon className="mr-2 size-3.5" />
                                    )}

                                    <span className="flex-1">{component.title || component.componentName}</span>

                                    <ChevronRightIcon className="size-3.5 text-muted-foreground" />
                                </CommandItem>
                            ))}
                        </CommandGroup>
                    ) : (
                        <CommandGroup
                            heading={`Tools — ${selectedToolableComponent?.title || selectedToolComponentName}`}
                        >
                            {!selectedToolableComponent || selectedToolableComponent.tools.length === 0 ? (
                                <CommandEmpty>No tools.</CommandEmpty>
                            ) : (
                                selectedToolableComponent.tools.map((tool) => (
                                    <CommandItem
                                        key={`tool-${selectedToolableComponent.componentName}-${tool.name}`}
                                        onSelect={() => {
                                            // Close the popover BEFORE opening the dialog. The popover
                                            // must be fully closed first so the dialog doesn't open on
                                            // top of an open popover — a nested-portal arrangement that
                                            // triggers Radix focus-trap conflicts (popover-in-dialog).
                                            setToolsBranchActive(false);
                                            setSelectedToolComponentName(null);
                                            onClose();
                                            setDialogTarget({
                                                clusterElementName: tool.name,
                                                componentName: selectedToolableComponent.componentName,
                                                componentVersion: selectedToolableComponent.componentVersion,
                                                description: tool.description,
                                                title: tool.title,
                                            });
                                        }}
                                        value={`tool-${selectedToolableComponent.componentName}-${tool.name}-${tool.title ?? ''}`}
                                    >
                                        <WrenchIcon className="mr-2 size-3.5" />

                                        <span className="flex-1">{tool.title || tool.name}</span>
                                    </CommandItem>
                                ))
                            )}
                        </CommandGroup>
                    )}
                </>
            ),
            renderRootItem: (onEnter) => (
                <CommandItem
                    onSelect={() => {
                        setToolsBranchActive(true);
                        setSelectedToolComponentName(null);
                        onEnter();
                    }}
                    value="root-tools"
                >
                    <WrenchIcon className="mr-2 size-3.5" />

                    <span className="flex-1">Tools</span>

                    <ChevronRightIcon className="size-3.5 text-muted-foreground" />
                </CommandItem>
            ),
        }),
        // The closures capture `selectedToolComponentName`, the derived `toolableComponents` /
        // `selectedToolableComponent` lists, and otherwise only stable `useState` setters.
        [selectedToolComponentName, selectedToolableComponent, toolableComponents]
    );

    return (
        <>
            <ResourcePickerMenu
                environmentId={environmentId ?? DEVELOPMENT_ENVIRONMENT}
                // The Tools branch is composer-owned, so its drilldown state lives here. Reset it whenever
                // the picker closes by any means (Back button or an outside-click dismiss) so a stale
                // `selectedToolComponentName` and a lingering `toolsBranchActive` enabled-flag don't
                // survive into the next picker open.
                onOpenChange={(open) => {
                    if (!open) {
                        setToolsBranchActive(false);
                        setSelectedToolComponentName(null);
                    }
                }}
                onSelect={handleResourceSelect}
                toolsBranch={toolsBranch}
                trigger={
                    <button
                        aria-label="Add reference"
                        className="flex size-7 items-center justify-center rounded-full text-muted-foreground hover:bg-accent hover:text-foreground"
                        type="button"
                    >
                        <PlusIcon className="size-4" />

                        {referencedResources.length > 0 && (
                            <span className="ml-1 text-xs">{referencedResources.length}</span>
                        )}
                    </button>
                }
                workspaceId={currentWorkspaceId ?? 0}
            />

            {/*
             * The dialog lives as a sibling of the ResourcePickerMenu (NOT a child) so its lifecycle is
             * independent — closing the popover doesn't unmount the dialog mid-form-edit, and the dialog's
             * overlay doesn't get nested inside the popover's portal (which causes Radix focus-trap conflicts).
             */}
            {dialogTarget && taskId && currentWorkspaceId != null && (
                <TaskToolDialog
                    onClose={() => setDialogTarget(null)}
                    open={dialogTarget != null}
                    target={dialogTarget}
                    taskId={taskId}
                    workspaceId={currentWorkspaceId}
                />
            )}
        </>
    );
};

export default AiHubComposer;

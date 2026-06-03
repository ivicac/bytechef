import {ResizableHandle, ResizablePanel, ResizablePanelGroup} from '@/components/ui/resizable';
import AiHubErrorBoundary from '@/pages/automation/ai-hub/AiHubErrorBoundary';
import AiHubHomePanel from '@/pages/automation/ai-hub/AiHubHomePanel';
import AiHubPanel from '@/pages/automation/ai-hub/AiHubPanel';
import AiHubResourcePanel from '@/pages/automation/ai-hub/AiHubResourcePanel';
import AiHubTasksSidebarCollapseButton from '@/pages/automation/ai-hub/AiHubTasksSidebarCollapseButton';
import AiHubTasksSidebarRail from '@/pages/automation/ai-hub/AiHubTasksSidebarRail';
import {useResetAiHubStoresOnWorkspaceChange} from '@/pages/automation/ai-hub/hooks/useResetAiHubStoresOnWorkspaceChange';
import {aiHubAskedQuestionsStore} from '@/pages/automation/ai-hub/messages/stores/useAiHubAskedQuestionsStore';
import {AiHubRuntimeProvider} from '@/pages/automation/ai-hub/runtime-providers/AiHubRuntimeProvider';
import {MODE, useAiHubStore} from '@/pages/automation/ai-hub/stores/useAiHubStore';
import {aiHubTabsStore, useAiHubTabsStore} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import AiHubTasksSidebar from '@/pages/automation/ai-hub/tasks/AiHubTasksSidebar';
import useRecordReferencedArtifacts from '@/pages/automation/ai-hub/tasks/hooks/useRecordReferencedArtifacts';
import {useSwitchTask} from '@/pages/automation/ai-hub/tasks/hooks/useSwitchTask';
import {useAiHubTasksQuery} from '@/pages/automation/ai-hub/tasks/hooks/useTasks';
import {aiHubTasksStore, useAiHubTasksStore} from '@/pages/automation/ai-hub/tasks/stores/useAiHubTasksStore';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {useWorkspaceChatWorkflowsQuery} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';

const AiHubContent = () => {
    const setMode = useAiHubStore((state) => state.setMode);
    const generateTaskId = useAiHubStore((state) => state.generateTaskId);
    const resetMessages = useAiHubStore((state) => state.resetMessages);

    const rightPanelOpen = useAiHubTabsStore((state) => state.rightPanelOpen);
    const tasksSidebarCollapsed = useAiHubTabsStore((state) => state.tasksSidebarCollapsed);
    const setActiveTabsTaskId = useAiHubTabsStore((state) => state.setActiveTaskId);

    const currentTaskId = useAiHubTasksStore((state) => state.currentTaskId);
    const hasActiveTask = currentTaskId != null;

    // The resource (right) panel only shows in the split view — an active task plus an open right panel.
    const showResourcePanel = hasActiveTask && rightPanelOpen;

    // While the resource panel is open the left Tasks sidebar collapses to a thin rail by default
    // (`tasksSidebarCollapsed`) so the chat + resource panels reclaim most of the width. The user can
    // expand it back from the rail — `showSidebarRail` is false in that expanded state, which brings
    // the full `LayoutContainer` sidebar back. Three layout states result:
    //   - no resource panel            -> full sidebar (showSidebarRail false)
    //   - resource panel + collapsed   -> thin rail    (showSidebarRail true)
    //   - resource panel + expanded    -> full sidebar pushed back in (showSidebarRail false)
    const showSidebarRail = showResourcePanel && tasksSidebarCollapsed;

    // Keep the resource panel mounted for 300ms after it closes so it can play a slide+fade-out before
    // unmounting (mirrors the slide+fade-in on open). Without this the split layout swaps to the full-width
    // chat instantly, with no exit effect.
    const [resourcePanelMounted, setResourcePanelMounted] = useState(showResourcePanel);

    useEffect(() => {
        if (showResourcePanel) {
            setResourcePanelMounted(true);

            return;
        }

        const timerId = setTimeout(() => setResourcePanelMounted(false), 300);

        return () => clearTimeout(timerId);
    }, [showResourcePanel]);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const {data: tasks} = useAiHubTasksQuery(currentWorkspaceId, currentEnvironmentId, 'ACTIVE');

    // Workspace chat workflows are needed to translate a WORKFLOW_CHAT task's workflowExecutionId
    // (the only workflow handle the task row carries) into the (projectId, projectWorkflowId,
    // workflowId) triple openWorkflowTab requires. Query is cached workspace-wide and shared with
    // useLiveWorkflowLabel / WorkflowChatsList so this doesn't add a round-trip.
    const {data: chatWorkflowsData} = useWorkspaceChatWorkflowsQuery(
        {environmentId: String(currentEnvironmentId), workspaceId: String(currentWorkspaceId ?? 0)},
        {enabled: currentWorkspaceId != null}
    );

    const navigate = useNavigate();
    const {taskId: urlTaskIdParam} = useParams<{taskId?: string}>();
    const switchTask = useSwitchTask();

    // Active task lookup happens inside AiHubPanel now — title + workflow-chat badge
    // both live there alongside the Ask/Build toggle. The CC root no longer needs the title because the
    // top header strip is gone.

    // Hoisted to this level so the workspace-store reset effect runs once for the whole AI Hub
    // surface — children (HomePanel, Panel, ResourcePanel) used to register it independently, which fired
    // the reset multiple times on workspace change.
    useResetAiHubStoresOnWorkspaceChange();

    // Records each open right-panel tab as a `ai_hub_task_artifact` so it shows up in the sidebar
    // artifact list. Bridges the gap between the UI-only tab state and the persistent artifact log —
    // covers BOTH user-driven attachment (composer plus-button → opens tab) and agent-driven attachment
    // (chat tool calls that open files/workflows). The hook is a no-op when there's no active
    // task (home view); it resumes recording once a task is created.
    useRecordReferencedArtifacts(currentTaskId, currentWorkspaceId ?? 0);

    useEffect(() => {
        setMode(MODE.BUILD);
    }, [setMode]);

    /*
     * Mirror the active task into the tabs store so per-task tab snapshots can be
     * saved/restored across switches. Without this wiring the resource-panel tabs would persist
     * VISUALLY across task changes (the store is global) but they wouldn't be associated with
     * any task — switching from /tasks/A back to /tasks/B would still show
     * task A's tabs because the store doesn't know which task it's mirroring.
     */
    useEffect(() => {
        setActiveTabsTaskId(currentTaskId);
    }, [currentTaskId, setActiveTabsTaskId]);

    /*
     * Reset the askUserQuestion answered-state store on task switch. The store is keyed by question
     * content fingerprint (question text + option labels), which is content-stable per question instance
     * but NOT scoped to a task. Two different tasks producing the same question shape (e.g. "Which Slack
     * channel?" with the same channel list) would otherwise share the answered state — task B would see
     * task A's answer as already-submitted, hide the buttons, and confuse the user. The workspace-change
     * reset hook already calls reset() for cross-workspace switches; this effect covers in-workspace
     * task switches.
     */
    useEffect(() => {
        aiHubAskedQuestionsStore.getState().reset();
    }, [currentTaskId]);

    /*
     * Auto-open the bound workflow in the right resource panel whenever the active task is a
     * WORKFLOW_CHAT. Fires on the task-id transition only (not on every render), so the user can still
     * close the workflow tab manually inside a single conversation without it springing back open on
     * the next message. The transition gate covers the four landing paths uniformly:
     *
     *   1. Sidebar row click       → AiHubTasksSidebar.handleSelectTask → switchTask → setCurrentTaskId
     *   2. WorkflowChatsList pick  → handleSelect sets state, navigates
     *   3. ModelPicker cascade pick → AiHubPanel.handleSelectWorkflowChat sets state, navigates
     *   4. Deep-link / URL change  → AiHub.tsx (b) URL-DRIVEN STANDING branch calls switchTask
     *
     * `openWorkflowTab` already flips `rightPanelOpen` to true (see useAiHubTabsStore) and is
     * idempotent when the same workflow is already open as a tab — it just focuses the existing tab
     * instead of duplicating.
     *
     * Resolving the workflow tuple needs the workspaceChatWorkflows list to be loaded; the effect is
     * keyed on `chatWorkflowsData` so the auto-open still fires on a cold mount where the task arrives
     * before the workflow lookup query resolves.
     */
    const previousAutoOpenTaskIdRef = useRef(currentTaskId);

    useEffect(() => {
        const previousTaskId = previousAutoOpenTaskIdRef.current;

        if (currentTaskId == null || currentTaskId === previousTaskId) {
            return;
        }

        const task = tasks?.find((candidate) => candidate.id === currentTaskId);

        if (!task || task.kind !== 'WORKFLOW_CHAT' || task.workflowExecutionId == null) {
            // Standard / personal-agent tasks shouldn't auto-open a workflow tab. Still record the task
            // id as "seen" so a later switch back to a workflow-chat task re-fires the open path.
            previousAutoOpenTaskIdRef.current = currentTaskId;

            return;
        }

        const chatWorkflow = chatWorkflowsData?.workspaceChatWorkflows.find(
            (candidate) => candidate.workflowExecutionId === task.workflowExecutionId
        );

        if (!chatWorkflow) {
            // Workflow not loaded yet (cold mount race) or no longer chat-enabled / deleted. Leave the
            // previousTaskId ref unset so a later render — once the query resolves or the workflow becomes
            // discoverable — still gets a chance to open the tab.
            return;
        }

        aiHubTabsStore
            .getState()
            .openWorkflowTab(
                chatWorkflow.workflowId,
                chatWorkflow.projectId,
                Number(chatWorkflow.projectWorkflowId),
                `${chatWorkflow.projectName} — ${chatWorkflow.workflowLabel}`
            );

        previousAutoOpenTaskIdRef.current = currentTaskId;
    }, [chatWorkflowsData, currentTaskId, tasks]);

    /*
     * URL <-> store sync — single effect with explicit priority ordering.
     *
     * Three flow shapes need to coexist without cross-firing:
     *
     *   (a) STORE-DRIVEN — `storeChanged` is true. Caused by a sidebar task row click
     *       (`switchTask` writes the store), the auto-create path inside `onNew`
     *       (`createAiHubTask` then `setCurrentTaskId`), or an explicit reset (delete /
     *       archive of the active task). The URL must catch up. ALWAYS HANDLED FIRST so the
     *       URL→store invariant below cannot revert the store back to whatever the URL still says.
     *
     *   (b) URL-DRIVEN STANDING — URL has a task id and the store doesn't yet match. Triggered
     *       on cold-mount deep links and on every render until the tasks query resolves. This
     *       has to be a STANDING condition (not gated on cross-render diff) because the tasks
     *       list is async and may arrive after the first render — gating on `urlChanged` made the
     *       deep-link case silently no-op whenever the data landed on render 2+.
     *
     *   (c) URL → home — URL has no task id but the store still does. Reset the store.
     *       Fires under TWO conditions:
     *         1. URL just transitioned to home (`urlChanged`) — sidebar AI Hub icon click.
     *         2. Initial effect run on mount (`isFirstEffectRef`) — catches the remount case where
     *            the user clicks a task, navigates to another page, then returns to
     *            /ai-hub with `urlTaskIdParam = undefined` and a stale
     *            `currentTaskId` left in the global store from before navigation.
     *       NOT a fully-standing condition: between branch (a) calling `navigate()` and React Router's
     *       URL context catching up, there's a render where store has the new id but `useParams`
     *       still returns `undefined`. A standing branch (c) would erroneously reset the store there,
     *       triggering an A → C → A bounce loop (URL flips home → task → home → task
     *       on every send-from-home flow).
     *
     * The previous shape ran (b) before (a). When the user clicked a sibling task row from
     * /tasks/17 to /tasks/18, the store changed to 18 but the URL was still "17"; (b)
     * saw the mismatch, found task 17 in the list, and called `switchTask(17)`,
     * which clobbered the user's intent. Symptom: clicking a different task appeared to do
     * nothing (the panel stayed on 17). The order swap below fixes that without breaking deep links.
     */
    const previousUrlParamRef = useRef(urlTaskIdParam);
    const previousStoreIdRef = useRef(currentTaskId);
    const isFirstEffectRef = useRef(true);

    useEffect(() => {
        const urlChanged = urlTaskIdParam !== previousUrlParamRef.current;
        const storeChanged = currentTaskId !== previousStoreIdRef.current;
        const isFirstEffect = isFirstEffectRef.current;

        previousUrlParamRef.current = urlTaskIdParam;
        previousStoreIdRef.current = currentTaskId;
        isFirstEffectRef.current = false;

        // (a) STORE-DRIVEN: highest priority. Push URL to match the store.
        if (storeChanged) {
            if (currentTaskId != null && String(currentTaskId) !== urlTaskIdParam) {
                navigate(`/automation/ai-hub/tasks/${currentTaskId}`);
            } else if (currentTaskId == null && urlTaskIdParam) {
                navigate('/automation/ai-hub');
            }

            return;
        }

        // (b) URL-DRIVEN STANDING: URL has an id, keep store aligned. Self-heals across renders.
        if (urlTaskIdParam) {
            const idNum = Number(urlTaskIdParam);

            if (!Number.isNaN(idNum) && idNum !== currentTaskId) {
                const task = tasks?.find((candidate) => candidate.id === idNum);

                if (task) {
                    void switchTask(task);
                }
                // No task found: fall through silently. Next tasks refetch retries.
            }

            return;
        }

        // (c) URL → home. Reset store on (1) URL transition to home or (2) first effect run after mount
        // with a stale store value. NOT on intermediate renders during branch (a)'s navigate() flush.
        if ((urlChanged || isFirstEffect) && currentTaskId != null) {
            aiHubTasksStore.getState().setCurrentTaskId(undefined);
            resetMessages();
            generateTaskId();
        }
    }, [tasks, currentTaskId, generateTaskId, navigate, resetMessages, switchTask, urlTaskIdParam]);

    // Home view: no active task yet — show only the centered composer. The first message sent here
    // auto-creates a task (see AiHubRuntimeProvider.onNew), which flips this branch to the
    // panel view containing the thread. Personal Agents and Workflow Chats are full-page routes — they live
    // outside this component (see /automation/ai-hub/personal-agents and /workflow-chats).
    const mainBody = !hasActiveTask ? (
        <AiHubHomePanel />
    ) : !resourcePanelMounted ? (
        <AiHubPanel />
    ) : (
        <div className="flex size-full">
            {/* Collapsed Tasks sidebar. Rendered here (inside the LayoutContainer body) rather than via
             * the LayoutContainer `aside` because the full sidebar is hidden in this state — the rail is
             * its stand-in. Expanding it flips `tasksSidebarCollapsed`, which unhides the real sidebar. */}

            {showSidebarRail && <AiHubTasksSidebarRail />}

            <ResizablePanelGroup className="min-w-0 flex-1" orientation="horizontal">
                <ResizablePanel defaultSize={35} minSize={25}>
                    <AiHubPanel />
                </ResizablePanel>

                {/* Invisible draggable gap (no grip line) so the resource panel reads as a floating
                 * "island". This transparent 4px separator is the left gutter between the chat and the
                 * card — deliberately tighter than the `py-2 pr-2` (8px) gutter the panel paints on its
                 * other three sides: the card already reads as detached via its border + shadow, so the
                 * inter-panel gap can be smaller than the window-edge gutters without losing the island look. */}

                <ResizableHandle className="w-1 bg-transparent" />

                <ResizablePanel defaultSize={65} minSize={30}>
                    <div
                        className={twMerge(
                            'size-full',
                            showResourcePanel
                                ? 'animate-in duration-300 fade-in slide-in-from-right-4'
                                : 'animate-out duration-300 fade-out slide-out-to-right-4'
                        )}
                    >
                        <AiHubResourcePanel />
                    </div>
                </ResizablePanel>
            </ResizablePanelGroup>
        </div>
    );

    return (
        <LayoutContainer
            className="bg-surface-main"
            // No top `header` slot any more — both the task title (which lived on the left of the
            // header) and the EnvironmentSelect (which lived on the right) have moved into their natural
            // homes: the title sits inside AiHubPanel's own header alongside the Ask/Build toggle,
            // and EnvironmentSelect lives in AiHubTasksSidebar's Tasks row. This
            // lets both sidebars stretch top-to-bottom without a strip across the top of the layout.
            leftSidebarBody={<AiHubTasksSidebar />}
            // The collapse-to-rail control lives in the header's `right` slot so it sits in line with
            // the "AI Hub" title. Shown only while the resource panel is open — that's the only state
            // where collapsing to the rail is meaningful.
            leftSidebarHeader={
                <Header
                    position="sidebar"
                    right={showResourcePanel ? <AiHubTasksSidebarCollapseButton /> : undefined}
                    title="AI Hub"
                />
            }
            // Hide the LayoutContainer left sidebar only while the collapsed rail stands in for it
            // (resource panel open + not expanded). When there's no resource panel, or the user has
            // expanded the sidebar back from the rail, the full sidebar renders here as usual.
            leftSidebarOpen={!showSidebarRail}
            leftSidebarWidth="64"
        >
            {/*
             * Runtime provider is hoisted up here (was previously instantiated inside HomePanel and Panel).
             * The home -> task transition flips `mainBody` from `AiHubHomePanel` to the
             * `AiHubPanel` view; if each side owned its own provider, that flip would unmount the
             * provider mid-`onNew`, fire its cleanup useEffect, and abort the AG-UI agent run via
             * `cleanupForTaskChange` — the user would land on the task page with their
             * message but no streaming reply. Mounting the provider once at this level keeps the runtime
             * (and its turn AbortController) alive across the view swap.
             *
             * AiHubErrorBoundary stays here too, for the same reason — it catches the
             * `@assistant-ui/react` "unmount a fiber that is already unmounted" error in React 19, and
             * needs to wrap the runtime provider to fence those errors at a single point.
             */}

            <AiHubErrorBoundary open={true}>
                <AiHubRuntimeProvider>{mainBody}</AiHubRuntimeProvider>
            </AiHubErrorBoundary>
        </LayoutContainer>
    );
};

const AiHub = () => <AiHubContent />;

export default AiHub;

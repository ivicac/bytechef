import {useAiAgentTestingChatStore} from '@/pages/platform/cluster-element-editor/ai-agent-editor/stores';
import {useTestingModeStore} from '@/pages/platform/cluster-element-editor/ai-agent-editor/stores/useTestingModeStore';
import {useAiAgentEvalsStore} from '@/pages/platform/cluster-element-editor/ai-agent-evals/stores/useAiAgentEvalsStore';
import useClusterElementsDataStore from '@/pages/platform/cluster-element-editor/stores/useClusterElementsDataStore';
import {useClusterElementsCanvasDialogStore} from '@/pages/platform/workflow-editor/components/stores/useClusterElementsCanvasDialogStore';
import useClusterElementsViewMode from '@/pages/platform/workflow-editor/hooks/useClusterElementsViewMode';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import {isDataStreamSimpleModeAvailable as computeIsDataStreamSimpleModeAvailable} from '@/pages/platform/workflow-editor/utils/isDataStreamSimpleModeAvailable';
import {MODE, Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {useCallback, useEffect, useMemo, useRef} from 'react';

interface UseClusterElementsCanvasDialogProps {
    onOpenChange: (open: boolean) => void;
    workflowReferenceId?: number | string;
}

export default function useClusterElementsCanvasDialog({
    onOpenChange,
    workflowReferenceId,
}: UseClusterElementsCanvasDialogProps) {
    const conversationTokenRef = useRef<string | null>(null);

    const setCopilotPanelOpen = useClusterElementsCanvasDialogStore((state) => state.setCopilotPanelOpen);
    const setShowAiAgentEditor = useClusterElementsCanvasDialogStore((state) => state.setShowAiAgentEditor);
    const setShowDataStreamEditor = useClusterElementsCanvasDialogStore((state) => state.setShowDataStreamEditor);
    const setEditorPreference = useClusterElementsCanvasDialogStore((state) => state.setEditorPreference);
    const setTestingPanelOpen = useClusterElementsCanvasDialogStore((state) => state.setTestingPanelOpen);
    const clusterElementsViewMode = useClusterElementsViewMode();

    const rootClusterElementNodeData = useWorkflowEditorStore((state) => state.rootClusterElementNodeData);
    const setClusterElementsCanvasOpen = useWorkflowEditorStore((state) => state.setClusterElementsCanvasOpen);
    const resetNodeDetailsPanel = useWorkflowNodeDetailsPanelStore((state) => state.reset);

    const isAiAgentClusterRoot = rootClusterElementNodeData?.componentName === 'aiAgent';
    const isDataStreamClusterRoot = rootClusterElementNodeData?.componentName === 'dataStream';
    const workflowNodeName = rootClusterElementNodeData?.workflowNodeName;

    const ai = useApplicationInfoStore((state) => state.ai);
    const setContext = useCopilotStore((state) => state.setContext);

    const ff_1570 = useFeatureFlagsStore()('ff-1570');

    const copilotEnabled = ai.copilot.enabled && ff_1570;

    const workflow = useWorkflowDataStore((state) => state.workflow);

    const isDataStreamSimpleModeAvailable = useMemo(() => {
        if (!isDataStreamClusterRoot) {
            return true;
        }

        return computeIsDataStreamSimpleModeAvailable(workflow.definition, workflowNodeName);
    }, [isDataStreamClusterRoot, workflowNodeName, workflow.definition]);

    const preferenceWorkflowReferenceId = workflowReferenceId ?? workflow.id;
    const preferenceKey =
        preferenceWorkflowReferenceId && workflowNodeName
            ? `${preferenceWorkflowReferenceId}:${workflowNodeName}`
            : undefined;

    // Restores this root's remembered editor-vs-canvas preference. Box mode never reaches this: its
    // box header buttons explicitly decide which surface to show on each open (see ClusterFrameShell),
    // and this effect firing there would silently override that explicit choice with whatever was last
    // remembered from a DIALOG-mode session -- including a stale `false`, which would answer "Switch to
    // AI Agent editor" with the dialog's canvas view instead.
    useEffect(() => {
        if (clusterElementsViewMode === 'dialog' && isAiAgentClusterRoot && preferenceKey) {
            const preference = useClusterElementsCanvasDialogStore.getState().editorPreferences[preferenceKey];

            const showAiAgent = preference ?? true;

            setShowAiAgentEditor(showAiAgent);
        }
    }, [clusterElementsViewMode, isAiAgentClusterRoot, preferenceKey, setShowAiAgentEditor]);

    // Same reasoning as the AI Agent effect above, for the DataStream editor.
    useEffect(() => {
        if (clusterElementsViewMode === 'dialog' && isDataStreamClusterRoot && preferenceKey) {
            if (!isDataStreamSimpleModeAvailable) {
                setShowDataStreamEditor(false);

                return;
            }

            const preference = useClusterElementsCanvasDialogStore.getState().editorPreferences[preferenceKey];

            const showDataStream = preference ?? true;

            setShowDataStreamEditor(showDataStream);
        }
    }, [
        clusterElementsViewMode,
        isDataStreamClusterRoot,
        isDataStreamSimpleModeAvailable,
        preferenceKey,
        setShowDataStreamEditor,
    ]);

    const handleToggleEditor = useCallback(
        (showSimpleEditor: boolean) => {
            // In box mode there is no dialog canvas view to fall back to — that surface is the
            // dialog's own ClusterElementsWorkflowEditor, which box mode never asked to see. Closing
            // the whole destination returns the user to their box on the main canvas instead.
            //
            // The cleanups below still run: resetTestingMode/setAiAgentNodeDetailsPanelOpen(false)
            // clear state that stays visible on the MAIN canvas after the dialog unmounts (the testing
            // mode flag and the AI-agent node-details panel are not dialog-local), and the preference
            // write keeps a later DIALOG-mode open of this same root consistent with what box mode just
            // showed. setTestingPanelOpen(false) is deliberately NOT called here, unlike the dialog-mode
            // branch below — the playground is an independent side panel in box mode (see
            // ClusterFrameShell's handleOpenPlayground), not owned by this toggle.
            if (!showSimpleEditor && clusterElementsViewMode === 'box') {
                setClusterElementsCanvasOpen(false);

                useTestingModeStore.getState().resetTestingMode();

                useWorkflowNodeDetailsPanelStore.getState().setAiAgentNodeDetailsPanelOpen(false);

                if (preferenceKey) {
                    setEditorPreference(preferenceKey, showSimpleEditor);
                }

                return;
            }

            if (isAiAgentClusterRoot) {
                setShowAiAgentEditor(showSimpleEditor);

                useTestingModeStore.getState().resetTestingMode();

                setTestingPanelOpen(false);

                useWorkflowNodeDetailsPanelStore.getState().setAiAgentNodeDetailsPanelOpen(false);
            } else if (isDataStreamClusterRoot) {
                setShowDataStreamEditor(showSimpleEditor);

                if (showSimpleEditor) {
                    useWorkflowNodeDetailsPanelStore.getState().reset();
                } else {
                    const panelStore = useWorkflowNodeDetailsPanelStore.getState();

                    if (rootClusterElementNodeData) {
                        panelStore.setCurrentNode((previousCurrentNode) => ({
                            ...rootClusterElementNodeData,
                            description: '',
                            displayConditions: previousCurrentNode?.displayConditions,
                        }));

                        panelStore.setWorkflowNodeDetailsPanelOpen(true);
                    }
                }
            }

            if (preferenceKey) {
                setEditorPreference(preferenceKey, showSimpleEditor);
            }
        },
        [
            clusterElementsViewMode,
            preferenceKey,
            isAiAgentClusterRoot,
            isDataStreamClusterRoot,
            rootClusterElementNodeData,
            setClusterElementsCanvasOpen,
            setEditorPreference,
            setShowAiAgentEditor,
            setShowDataStreamEditor,
            setTestingPanelOpen,
        ]
    );

    const handleCopilotClick = useCallback(() => {
        const {
            context: currentContext,
            generateConversationId,
            resetMessages,
            saveConversationState,
        } = useCopilotStore.getState();

        conversationTokenRef.current = saveConversationState();
        resetMessages();
        generateConversationId();

        setContext({
            ...currentContext,
            mode: MODE.ASK,
            parameters: {taskName: rootClusterElementNodeData?.name},
            source: Source.CLUSTER_ELEMENT,
        });

        setCopilotPanelOpen(true);
    }, [rootClusterElementNodeData?.name, setCopilotPanelOpen, setContext]);

    const handleCopilotClose = useCallback(() => {
        useCopilotStore.getState().restoreConversationState(conversationTokenRef.current);
        setCopilotPanelOpen(false);
    }, [setCopilotPanelOpen]);

    const handleTestClick = useCallback(() => {
        const {generateConversationId, resetMessages} = useAiAgentTestingChatStore.getState();

        resetMessages();
        generateConversationId();
        useTestingModeStore.getState().setIsTestingAgent(true);
        setTestingPanelOpen(true);
    }, [setTestingPanelOpen]);

    const handleCloseTestingPanel = useCallback(() => {
        useTestingModeStore.getState().resetTestingMode();
        setTestingPanelOpen(false);
    }, [setTestingPanelOpen]);

    const handleOpenChange = useCallback(
        (isOpen: boolean) => {
            onOpenChange(isOpen);

            if (!isOpen) {
                useCopilotStore.getState().restoreConversationState(conversationTokenRef.current);
                useClusterElementsCanvasDialogStore.getState().reset();
                useClusterElementsDataStore.getState().reset();
                useTestingModeStore.getState().resetTestingMode();
                // Pre-existing gap: nothing else in this close path cleared evalsPanelOpen, so a
                // subsequently-opened root with no evals button of its own (a DataStream root, or an
                // AI Agent root with ff-4553 off) could still render the Evals surface left over from
                // the previous root's session.
                useAiAgentEvalsStore.getState().setEvalsPanelOpen(false);
                resetNodeDetailsPanel();
            }
        },
        [onOpenChange, resetNodeDetailsPanel]
    );

    const handleClose = useCallback(() => {
        handleOpenChange(false);
    }, [handleOpenChange]);

    // Duplicate of the mount-time restore effect above, guarding the same box-mode case for the same
    // reason -- see that effect's comment.
    useEffect(() => {
        if (clusterElementsViewMode !== 'dialog') {
            return;
        }

        if (isAiAgentClusterRoot && preferenceKey) {
            const showAiAgent = useClusterElementsCanvasDialogStore.getState().editorPreferences[preferenceKey] ?? true;

            setShowAiAgentEditor(showAiAgent);
        } else {
            setShowAiAgentEditor(false);
        }
    }, [clusterElementsViewMode, preferenceKey, isAiAgentClusterRoot, setShowAiAgentEditor]);

    // Duplicate of the mount-time restore effect above, guarding the same box-mode case for the same
    // reason -- see that effect's comment.
    useEffect(() => {
        if (clusterElementsViewMode !== 'dialog') {
            return;
        }

        if (isDataStreamClusterRoot && preferenceKey) {
            if (!isDataStreamSimpleModeAvailable) {
                setShowDataStreamEditor(false);

                return;
            }

            const showDataStream =
                useClusterElementsCanvasDialogStore.getState().editorPreferences[preferenceKey] ?? true;

            setShowDataStreamEditor(showDataStream);
        } else {
            setShowDataStreamEditor(false);
        }
    }, [
        clusterElementsViewMode,
        preferenceKey,
        isDataStreamClusterRoot,
        isDataStreamSimpleModeAvailable,
        setShowDataStreamEditor,
    ]);

    const handlePointerDownOutside = useCallback((event: CustomEvent<{originalEvent: PointerEvent}>) => {
        const target = event.detail.originalEvent.target;

        if (
            target instanceof Element &&
            (target.closest('[data-sonner-toast]') || target.closest('[data-sonner-toaster]'))
        ) {
            event.preventDefault();
        }
    }, []);

    return {
        copilotEnabled,
        handleClose,
        handleCloseTestingPanel,
        handleCopilotClick,
        handleCopilotClose,
        handleOpenChange,
        handlePointerDownOutside,
        handleTestClick,
        handleToggleEditor,
        isAiAgentClusterRoot,
        isDataStreamClusterRoot,
        isDataStreamSimpleModeAvailable,
    };
}

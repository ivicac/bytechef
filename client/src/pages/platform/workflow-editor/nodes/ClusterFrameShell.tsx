import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {useAiAgentEvalsStore} from '@/pages/platform/cluster-element-editor/ai-agent-evals/stores/useAiAgentEvalsStore';
import useOpenCopilot from '@/shared/components/copilot/hooks/useOpenCopilot';
import {Source} from '@/shared/components/copilot/stores/useCopilotStore';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {NodeDataType} from '@/shared/types';
import {Handle, Position} from '@xyflow/react';
import {
    BrushCleaningIcon,
    ExternalLinkIcon,
    FlaskConicalIcon,
    LockIcon,
    LockOpenIcon,
    PlayIcon,
    SparklesIcon,
    TextInitialIcon,
    ZapIcon,
} from 'lucide-react';
import {ReactNode, memo, useCallback, useMemo} from 'react';
import {twMerge} from 'tailwind-merge';

import {useClusterElementsCanvasDialogStore} from '../components/stores/useClusterElementsCanvasDialogStore';
import {useWorkflowEditor} from '../providers/workflowEditorProvider';
import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import {clearClusterElementPositions} from '../utils/clearAllClusterElementPositions';
import {
    CLUSTER_FRAME_BORDER_WIDTH,
    CLUSTER_FRAME_HEADER_HEIGHT,
    DEFAULT_CLUSTER_FRAME_CONTENT_ORIGIN,
} from '../utils/clusterFrame/clusterFrameGeometry';
import {mapHandlePosition} from '../utils/directionUtils';
import {getTask} from '../utils/getTask';
import {isDataStreamSimpleModeAvailable as computeIsDataStreamSimpleModeAvailable} from '../utils/isDataStreamSimpleModeAvailable';
import saveWorkflowDefinition from '../utils/saveWorkflowDefinition';
import styles from './NodeTypes.module.css';

const HEADER_BUTTON_CLASSNAME =
    'nodrag flex items-center gap-1 rounded px-2 py-1 text-xs text-content-neutral-secondary hover:bg-surface-neutral-secondary';

interface ClusterFrameShellProps {
    children: ReactNode;
    data: NodeDataType;
    nodeId: string;
}

/**
 * The box a cluster root's elements are drawn inside. Unlike a graph frame this is not a node of its
 * own: the root IS its elements' React Flow parent, so the shell wraps the root's own card and takes
 * the size the pre-pass wrote onto `data.clusterFrame`.
 *
 * Renders its children bare when there is no box — dialog mode, and the first paint before the
 * pre-pass has run — so the same node component serves both modes without branching at the call site.
 *
 * The chain handles live here rather than on the card, so the surrounding flow connects to the box.
 *
 * Lock/reset persist through `getTask` + `saveWorkflowDefinition` directly rather than through the
 * dialog's `clearAllClusterElementPositions` — that helper reads and rewrites the singular
 * `rootClusterElementNodeData` store slot, which `useClusterElementNodes` also uses to decide whether
 * to draw a root's OWN card as one of its elements (only the dialog's single open root gets one; every
 * box-mode root already has its card on the outer canvas). Setting that slot for a box root would make
 * `useClusterElementNodes` start drawing a duplicate card inside its own box on the very next render.
 *
 * The header also carries the destinations and side panels the dialog used to be the only entry point
 * to: the AI Agent editor, the DataStream editor, Skills, Evals and the agent playground/Copilot. Each
 * destination handler seeds `rootClusterElementNodeData` with THIS box's own root before opening its
 * surface — that single field is all `AiAgentEditor`, `DataStreamEditor`, `useAiAgentTools` and Evals
 * read to know which root they were opened on, so seeding it here is what lets those surfaces stay
 * unchanged. The playground and Copilot are side panels beside the main canvas, not destinations over
 * it, so their handlers never set `clusterElementsCanvasOpen` — see WorkflowEditorLayout for where they
 * actually mount.
 */
const ClusterFrameShell = ({children, data, nodeId}: ClusterFrameShellProps) => {
    const layoutDirection = useLayoutDirectionStore((state) => state.layoutDirection);
    // Selects the derived boolean rather than the whole map: this shell only ever needs its OWN
    // root's entry, so subscribing to the map itself would re-render every box's shell whenever any
    // one of them toggles. The `?.` also covers WorkflowNode's pre-existing tests, which stub
    // useWorkflowEditorStore with a hand-picked partial object lacking this field entirely — every
    // WorkflowNode render path reaches this shell regardless of whether that node is a cluster root.
    const locked = useWorkflowEditorStore((state) => state.clusterFrameLockedByRootId?.[nodeId] !== false);
    const setClusterFrameLocked = useWorkflowEditorStore((state) => state.setClusterFrameLocked);
    const setClusterElementsCanvasOpen = useWorkflowEditorStore((state) => state.setClusterElementsCanvasOpen);
    const setRootClusterElementNodeData = useWorkflowEditorStore((state) => state.setRootClusterElementNodeData);
    const workflowDefinition = useWorkflowDataStore((state) => state.workflow.definition);
    const setShowAiAgentEditor = useClusterElementsCanvasDialogStore((state) => state.setShowAiAgentEditor);
    const setShowDataStreamEditor = useClusterElementsCanvasDialogStore((state) => state.setShowDataStreamEditor);
    const setTestingPanelOpen = useClusterElementsCanvasDialogStore((state) => state.setTestingPanelOpen);
    const setEvalsPanelOpen = useAiAgentEvalsStore((state) => state.setEvalsPanelOpen);
    const openCopilot = useOpenCopilot();
    const ai = useApplicationInfoStore((state) => state.ai);
    const ff_1570 = useFeatureFlagsStore()('ff-1570');
    const ff_4070 = useFeatureFlagsStore()('ff-4070');
    const ff_4553 = useFeatureFlagsStore()('ff-4553');

    const {updateWorkflowMutation} = useWorkflowEditor();

    const clusterFrame = data.clusterFrame;
    const isAiAgentClusterRoot = data.componentName === 'aiAgent';
    const isDataStreamClusterRoot = data.componentName === 'dataStream';
    const copilotEnabled = ff_4070 && ai.copilot.enabled && ff_1570;

    // Shared with useClusterElementsCanvasDialog's own toggle-editor button -- see
    // isDataStreamSimpleModeAvailable's own doc comment for why this must not be a second copy.
    const isDataStreamSimpleModeAvailable = useMemo(() => {
        if (!isDataStreamClusterRoot) {
            return true;
        }

        return computeIsDataStreamSimpleModeAvailable(workflowDefinition, nodeId);
    }, [isDataStreamClusterRoot, nodeId, workflowDefinition]);

    const handleToggleLock = useCallback(() => {
        setClusterFrameLocked(nodeId, !locked);
    }, [locked, nodeId, setClusterFrameLocked]);

    const handleResetLayout = useCallback(() => {
        if (!updateWorkflowMutation) {
            return;
        }

        const {workflow} = useWorkflowDataStore.getState();

        if (!workflow.definition) {
            return;
        }

        const workflowDefinitionTasks = JSON.parse(workflow.definition).tasks ?? [];

        const clusterRootTask = getTask({tasks: workflowDefinitionTasks, workflowNodeName: nodeId});

        if (!clusterRootTask?.clusterElements) {
            return;
        }

        const clearedClusterElements = clearClusterElementPositions(clusterRootTask.clusterElements);

        saveWorkflowDefinition({
            nodeData: {
                ...clusterRootTask,
                clusterElements: clearedClusterElements,
                componentName: clusterRootTask.type.split('/')[0],
                workflowNodeName: nodeId,
            },
            updateWorkflowMutation,
        });
    }, [nodeId, updateWorkflowMutation]);

    const handleOpenAiAgentEditor = useCallback(() => {
        setRootClusterElementNodeData(data);
        setShowAiAgentEditor(true);
        setClusterElementsCanvasOpen(true);
    }, [data, setClusterElementsCanvasOpen, setRootClusterElementNodeData, setShowAiAgentEditor]);

    const handleOpenDataStreamEditor = useCallback(() => {
        setRootClusterElementNodeData(data);
        setShowDataStreamEditor(true);
        setClusterElementsCanvasOpen(true);
    }, [data, setClusterElementsCanvasOpen, setRootClusterElementNodeData, setShowDataStreamEditor]);

    const handleOpenEvals = useCallback(() => {
        setRootClusterElementNodeData(data);
        setEvalsPanelOpen(true);
        setClusterElementsCanvasOpen(true);
    }, [data, setClusterElementsCanvasOpen, setEvalsPanelOpen, setRootClusterElementNodeData]);

    // Note what this does NOT do: it never sets `clusterElementsCanvasOpen`. The playground is a
    // panel beside the main canvas (mounted by WorkflowEditorLayout), not a destination over it.
    const handleOpenPlayground = useCallback(() => {
        setRootClusterElementNodeData(data);
        setTestingPanelOpen(true);
    }, [data, setRootClusterElementNodeData, setTestingPanelOpen]);

    // Goes through useOpenCopilot -- the same fresh-context contract every other Copilot trigger
    // uses (the canvas's own CopilotButton, and the dialog's handleCopilotClick) -- rather than calling
    // useCopilotPanelStore's setCopilotPanelOpen directly. Skipping that contract would leave the
    // PREVIOUS surface's conversation and context installed, so the agent this button was clicked on
    // would answer using the wrong agent id and the wrong /ai/chat/{source} URL. Seeding
    // rootClusterElementNodeData still matters here too: it is what a reopened AI Agent editor or Evals
    // panel for this same root reads, independent of Copilot's own context.
    const handleOpenCopilot = useCallback(() => {
        setRootClusterElementNodeData(data);
        openCopilot({parameters: {taskName: data.name}, source: Source.CLUSTER_ELEMENT});
    }, [data, openCopilot, setRootClusterElementNodeData]);

    if (!clusterFrame) {
        return <>{children}</>;
    }

    const contentOrigin = clusterFrame.contentOrigin ?? DEFAULT_CLUSTER_FRAME_CONTENT_ORIGIN;

    return (
        <div
            className="rounded-lg border-2 border-dashed border-stroke-neutral-secondary bg-surface-neutral-secondary/40"
            data-nodetype="clusterFrame"
            data-testid="cluster-frame-shell"
            style={{height: clusterFrame.height, width: clusterFrame.width}}
        >
            <div className="flex items-center justify-between px-3" style={{height: CLUSTER_FRAME_HEADER_HEIGHT}}>
                <span className="text-sm font-semibold text-content-neutral-secondary">{data.label}</span>

                {updateWorkflowMutation && (
                    <div className="flex items-center gap-1">
                        {isAiAgentClusterRoot && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        aria-label="Switch to AI Agent editor"
                                        className={HEADER_BUTTON_CLASSNAME}
                                        onClick={handleOpenAiAgentEditor}
                                        type="button"
                                    >
                                        <TextInitialIcon className="size-3.5" />
                                    </button>
                                </TooltipTrigger>

                                <TooltipContent>Switch to AI Agent editor</TooltipContent>
                            </Tooltip>
                        )}

                        {isDataStreamClusterRoot && isDataStreamSimpleModeAvailable && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        aria-label="Switch to DataStream editor"
                                        className={HEADER_BUTTON_CLASSNAME}
                                        onClick={handleOpenDataStreamEditor}
                                        type="button"
                                    >
                                        <TextInitialIcon className="size-3.5" />
                                    </button>
                                </TooltipTrigger>

                                <TooltipContent>Switch to DataStream editor</TooltipContent>
                            </Tooltip>
                        )}

                        {isAiAgentClusterRoot && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <a
                                        aria-label="Manage AI Skills"
                                        className={twMerge(HEADER_BUTTON_CLASSNAME, 'relative')}
                                        href="/automation/settings/ai/skills"
                                        rel="noreferrer"
                                        target="_blank"
                                    >
                                        <ZapIcon className="size-3.5" />

                                        <ExternalLinkIcon
                                            aria-hidden
                                            className="absolute top-0 right-0 size-2 text-content-neutral-secondary/70"
                                        />
                                    </a>
                                </TooltipTrigger>

                                <TooltipContent>Manage AI Skills</TooltipContent>
                            </Tooltip>
                        )}

                        {ff_4553 && isAiAgentClusterRoot && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        aria-label="Agent Evals"
                                        className={HEADER_BUTTON_CLASSNAME}
                                        onClick={handleOpenEvals}
                                        type="button"
                                    >
                                        <FlaskConicalIcon className="size-3.5" />
                                    </button>
                                </TooltipTrigger>

                                <TooltipContent>Agent Evals</TooltipContent>
                            </Tooltip>
                        )}

                        {isAiAgentClusterRoot && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        aria-label="Test agent"
                                        className={HEADER_BUTTON_CLASSNAME}
                                        onClick={handleOpenPlayground}
                                        type="button"
                                    >
                                        <PlayIcon className="size-3.5" />
                                    </button>
                                </TooltipTrigger>

                                <TooltipContent>Test agent</TooltipContent>
                            </Tooltip>
                        )}

                        {copilotEnabled && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        aria-label="Open Copilot panel"
                                        className={HEADER_BUTTON_CLASSNAME}
                                        onClick={handleOpenCopilot}
                                        type="button"
                                    >
                                        <SparklesIcon className="size-3.5" />
                                    </button>
                                </TooltipTrigger>

                                <TooltipContent>Open Copilot panel</TooltipContent>
                            </Tooltip>
                        )}

                        <button
                            aria-label={locked ? 'Unlock node movement' : 'Lock node movement'}
                            className={HEADER_BUTTON_CLASSNAME}
                            onClick={handleToggleLock}
                            type="button"
                        >
                            {locked ? <LockIcon className="size-3.5" /> : <LockOpenIcon className="size-3.5" />}
                        </button>

                        <button
                            aria-label="Reset layout"
                            className={HEADER_BUTTON_CLASSNAME}
                            onClick={handleResetLayout}
                            type="button"
                        >
                            <BrushCleaningIcon className="size-3.5" />
                        </button>
                    </div>
                )}
            </div>

            {/* Rendered BEFORE the card, because React Flow binds an edge that names no handle to the
            FIRST handle of that type in DOM order. The card inside carries a source handle per cluster
            element type, so with these last the chain's own edge left the box from `model-handle`
            instead of from the box's bottom edge. */}

            <Handle
                className={styles.handle}
                id={`${nodeId}-top`}
                position={mapHandlePosition(Position.Top, layoutDirection)}
                type="target"
            />

            <Handle
                className={styles.handle}
                id={`${nodeId}-bottom`}
                position={mapHandlePosition(Position.Bottom, layoutDirection)}
                type="source"
            />

            {/* The card sits at the content origin, which is the origin every member position is
            measured from. It is pushed in from the box's top-left corner only when a member reaches
            left of, or above, the card — see computeClusterFrameContentOrigin. */}

            <div
                style={{
                    // Both offsets discount the frame's own border: the placeholders this card's
                    // handles connect to are positioned from the frame node's origin, which sits
                    // outside that border. See CLUSTER_FRAME_BORDER_WIDTH.
                    marginLeft: contentOrigin.x - CLUSTER_FRAME_BORDER_WIDTH,
                    marginTop: contentOrigin.y - CLUSTER_FRAME_HEADER_HEIGHT - CLUSTER_FRAME_BORDER_WIDTH,
                }}
            >
                {children}
            </div>
        </div>
    );
};

export default memo(ClusterFrameShell);

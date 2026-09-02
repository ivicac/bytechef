import {NodeDataType} from '@/shared/types';
import {Handle, Position} from '@xyflow/react';
import {BrushCleaningIcon, LockIcon, LockOpenIcon} from 'lucide-react';
import {ReactNode, memo, useCallback} from 'react';

import {useWorkflowEditor} from '../providers/workflowEditorProvider';
import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import {clearClusterElementPositions} from '../utils/clearAllClusterElementPositions';
import {CLUSTER_FRAME_HEADER_HEIGHT} from '../utils/clusterFrame/clusterFrameGeometry';
import {mapHandlePosition} from '../utils/directionUtils';
import {getTask} from '../utils/getTask';
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

    const {updateWorkflowMutation} = useWorkflowEditor();

    const clusterFrame = data.clusterFrame;

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

    if (!clusterFrame) {
        return <>{children}</>;
    }

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

            {children}

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
        </div>
    );
};

export default memo(ClusterFrameShell);

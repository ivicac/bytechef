import {BaseEdge, EdgeProps, getSmoothStepPath} from '@xyflow/react';
import {twMerge} from 'tailwind-merge';

import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import styles from './WorkflowEdge.module.css';
import computeExitEdgeJogCenter from './computeExitEdgeJogCenter';
import {getTriggerFanInBusCenter} from './computeTriggerFanIn';
import useExecutedEdgeStatus from './useExecutedEdgeStatus';

// Every "+" placeholder node id ends this way: `<dispatcher>-parallel-placeholder-0`,
// `<dispatcher>-forkJoin-placeholder-<n>`, the case placeholders of conditions, branches and the rest.
const PLACEHOLDER_NODE_ID_PATTERN = /-placeholder-\d+$/;

export default function RoundedSmoothStepEdge({
    data,
    id,
    source,
    sourcePosition,
    sourceX,
    sourceY,
    style,
    target,
    targetPosition,
    targetX,
    targetY,
}: EdgeProps) {
    const layoutDirection = useLayoutDirectionStore((state) => state.layoutDirection);
    const workflowIsRunning = useWorkflowEditorStore((state) => state.workflowIsRunning);

    const executedEdgeStatus = useExecutedEdgeStatus(id);

    const isTriggerFanIn = !!(data as Record<string, unknown>)?.triggerFanIn;

    const busCenter = getTriggerFanInBusCenter({
        isTriggerFanIn,
        sourcePosition,
        sourceX,
        sourceY,
    });

    // Smoothstep edges into a bottom bar (empty-case placeholder trails in the
    // editor, EVERY trailing edge in the read-only conversion where all edges
    // become 'smoothstep') bend beside the bar instead of at the path midpoint
    // — the same no-crossing rule WorkflowEdge applies to its exit edges. This
    // component has no node lookup, so the bar is detected by its id suffix.
    const exitJogCenter = computeExitEdgeJogCenter({
        correctedSourceX: sourceX,
        correctedSourceY: sourceY,
        correctedTargetX: targetX,
        correctedTargetY: targetY,
        isHorizontal: layoutDirection === 'LR',
        isTriggerFanIn,
        targetNodeType: target.endsWith('-bottom-ghost') ? 'taskDispatcherBottomGhostNode' : undefined,
    });

    const [edgePath] = getSmoothStepPath({
        borderRadius: 10,
        ...busCenter,
        ...exitJogCenter,
        sourcePosition,
        sourceX,
        sourceY,
        targetPosition,
        targetX,
        targetY,
    });

    // A "+" placeholder is an insertion point, not a step: nothing flows through it, so its edges never
    // take the running dash.
    const touchesPlaceholder = PLACEHOLDER_NODE_ID_PATTERN.test(source) || PLACEHOLDER_NODE_ID_PATTERN.test(target);
    const isRunningPath = workflowIsRunning && !touchesPlaceholder;

    return (
        <>
            {isRunningPath && <path className="fill-none stroke-background stroke-2" d={edgePath} />}

            <BaseEdge
                className={twMerge(
                    'fill-none stroke-stroke-neutral-tertiary stroke-2',
                    isRunningPath && styles.runningPath,
                    executedEdgeStatus === 'COMPLETED' && 'stroke-green-500',
                    executedEdgeStatus === 'FAILED' && 'stroke-red-500'
                )}
                id={id}
                path={edgePath}
                style={style}
            />
        </>
    );
}

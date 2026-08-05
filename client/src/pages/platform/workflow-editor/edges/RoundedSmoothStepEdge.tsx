import {BaseEdge, EdgeProps, getSmoothStepPath} from '@xyflow/react';
import {twMerge} from 'tailwind-merge';

import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import computeExitEdgeJogCenter from './computeExitEdgeJogCenter';
import {getTriggerFanInBusCenter} from './computeTriggerFanIn';
import useExecutedEdgeStatus from './useExecutedEdgeStatus';

export default function RoundedSmoothStepEdge({
    data,
    id,
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

    return (
        <BaseEdge
            className={twMerge(
                'fill-none stroke-stroke-neutral-tertiary stroke-2',
                executedEdgeStatus === 'COMPLETED' && 'stroke-green-500',
                executedEdgeStatus === 'FAILED' && 'stroke-red-500'
            )}
            id={id}
            path={edgePath}
            style={style}
        />
    );
}

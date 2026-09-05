import {LayoutDirectionType} from '@/shared/constants';
import {Rect, XYPosition} from '@xyflow/react';

interface GetInitialViewportPositionProps {
    canvasHeight: number;
    layoutDirection: LayoutDirectionType;
    leftInset: number;
    nodesBounds: Rect;
    offsetX: number;
    topInset: number;
}

/**
 * The pan that first shows a workflow at zoom 1.
 *
 * A top-to-bottom graph grows downward: its topmost node lands `topInset` below the canvas's top edge, and it keeps the
 * horizontal place the layout gave it (plus `offsetX`, the pan the overlay panels apply).
 *
 * A left-to-right graph grows sideways, so it is placed by its left edge instead: its leftmost node lands `leftInset`
 * from the canvas's left edge -- far enough for the trigger's label, which is wider than the node and centred under
 * it, to clear the edge -- and it is centred vertically, unless it is taller than the room below `topInset`, in which
 * case it falls back to the top placement so the trigger stays in view.
 */
export default function getInitialViewportPosition({
    canvasHeight,
    layoutDirection,
    leftInset,
    nodesBounds,
    offsetX,
    topInset,
}: GetInitialViewportPositionProps): XYPosition {
    const topAlignedY = topInset - nodesBounds.y;

    if (layoutDirection !== 'LR') {
        return {x: offsetX, y: topAlignedY};
    }

    const x = offsetX + leftInset - nodesBounds.x;

    if (canvasHeight <= 0) {
        return {x, y: topAlignedY};
    }

    const centeredY = canvasHeight / 2 - (nodesBounds.y + nodesBounds.height / 2);

    return {x, y: Math.max(centeredY, topAlignedY)};
}

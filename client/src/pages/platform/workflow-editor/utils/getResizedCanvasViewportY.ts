import {Rect} from '@xyflow/react';

interface GetResizedCanvasViewportYProps {
    canvasHeight: number;
    nodesBounds: Rect;
    previousCanvasHeight: number;
    topInset: number;
    viewportY: number;
    zoom: number;
}

/**
 * The vertical pan that keeps a vertically centred graph where it was when the canvas changes height -- the bottom
 * test output panel opening or closing, or its divider being dragged. The canvas grows and shrinks from its bottom
 * edge, so the pan moves by half the change: whatever sat in the middle of the canvas stays in the middle.
 *
 * Moving up is stopped at `topInset` below the top edge, so a canvas squeezed shorter than the graph never pushes its
 * top node under the toolbar; a graph whose top was already above that line is left no higher than it was.
 */
export default function getResizedCanvasViewportY({
    canvasHeight,
    nodesBounds,
    previousCanvasHeight,
    topInset,
    viewportY,
    zoom,
}: GetResizedCanvasViewportYProps): number {
    const centeredY = viewportY + (canvasHeight - previousCanvasHeight) / 2;

    if (centeredY >= viewportY) {
        return centeredY;
    }

    const topAlignedY = topInset - nodesBounds.y * zoom;

    return Math.max(centeredY, Math.min(topAlignedY, viewportY));
}

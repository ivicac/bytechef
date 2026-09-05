import {Node, useNodesInitialized, useReactFlow, useStore, useStoreApi} from '@xyflow/react';
import {useEffect, useRef} from 'react';

import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import getInitialViewportPosition from '../utils/getInitialViewportPosition';
import getResizedCanvasViewportY from '../utils/getResizedCanvasViewportY';

/**
 * On-screen gap between the canvas's top edge and the topmost node, so the graph does not start
 * flush against the border.
 *
 * Applied against that node's own coordinate rather than as a bare viewport offset, because the
 * graph's origin is not its top edge: the layout engine places the first node at a different y per
 * workflow, so a fixed offset yields a different visible gap each time and cannot be corrected by
 * tuning the number.
 *
 * `NodeActionsHint` floats over the top of the canvas and relies on this to clear it, so shrinking
 * this value means checking the hint no longer overlaps the first node.
 */
export const CANVAS_TOP_INSET = 120;

/**
 * On-screen gap between the canvas's left edge and the leftmost node of a left-to-right graph. The trigger's label is
 * wider than its node and centred under it, so anything much smaller lets the edge cut the label off.
 */
export const CANVAS_LEFT_INSET = 80;

/**
 * The node types the placement measures against: the ones representing a step the viewer actually
 * sees at the top of the graph. The rest of the canvas is excluded on purpose — the add-step
 * placeholders and dispatcher ghosts sit BELOW the graph and can be on the canvas a frame before
 * the real nodes are, and a sticky note can be dragged anywhere at all, including far above the graph.
 */
const PLACEMENT_NODE_TYPES = ['clusterRoot', 'graphFrame', 'readonly', 'triggerPlaceholder', 'workflow'];

// Top-level nodes only — a child node's `position` is relative to its parent frame.
const getPlacementNodes = (nodes: Node[]) =>
    nodes.filter((node) => !node.parentId && PLACEMENT_NODE_TYPES.includes(node.type ?? ''));

interface UseInitialViewportProps {
    enabled: boolean;
    getViewportOffsetX: () => number;
    /**
     * Keyed on the workflow id rather than its uuid: the embedded builder's workflow carries no uuid, so gating the
     * placement on one skipped it there entirely and left the canvas at React Flow's default viewport.
     */
    workflowId?: string;
}

export default function useInitialViewport({enabled, getViewportOffsetX, workflowId}: UseInitialViewportProps) {
    // False until the canvas has been positioned once, which is what keeps the first placement from animating.
    const viewportPlacedRef = useRef(false);

    // The workflow whose graph has already been placed. `nodesInitialized` flips back to false whenever a node is
    // added, so without this the placement would re-run and slide the canvas out from under the node just added.
    const placedWorkflowIdRef = useRef<string | undefined>(undefined);

    // The canvas height the viewport was last kept centred against -- see the canvas height effect below.
    const previousCanvasHeightRef = useRef(0);

    const flowHeight = useStore((state) => state.height);
    const nodesInitialized = useNodesInitialized();
    const storeApi = useStoreApi();
    const {getNodes, getNodesBounds, getViewport, setViewport} = useReactFlow();

    useEffect(() => {
        if (!enabled || !workflowId || !nodesInitialized || placedWorkflowIdRef.current === workflowId) {
            return;
        }

        const placementNodes = getPlacementNodes(getNodes());

        // `nodesInitialized` can go true on a frame holding only placeholders, before the graph itself is on the
        // canvas. Leaving the per-workflow slot unclaimed lets this run again when the real nodes arrive.
        if (placementNodes.length === 0) {
            return;
        }

        placedWorkflowIdRef.current = workflowId;

        const {x, y} = getInitialViewportPosition({
            canvasHeight: storeApi.getState().height,
            layoutDirection: useLayoutDirectionStore.getState().layoutDirection,
            leftInset: CANVAS_LEFT_INSET,
            nodesBounds: getNodesBounds(placementNodes),
            // The overlay panels pan the canvas horizontally to keep the graph clear of themselves, so the first
            // placement starts from that pan rather than from a bare 0.
            offsetX: getViewportOffsetX(),
            topInset: CANVAS_TOP_INSET,
        });

        // Instant the first time, animated when moving between workflows.
        void setViewport({x, y, zoom: 1}, {duration: viewportPlacedRef.current ? 500 : 0});

        viewportPlacedRef.current = true;
    }, [enabled, getNodes, getNodesBounds, getViewportOffsetX, nodesInitialized, setViewport, storeApi, workflowId]);

    // A left-to-right graph opens centred vertically, and the canvas loses and regains height from its bottom edge
    // whenever the test output panel opens, closes or is dragged. Keep the graph where it was relative to the canvas's
    // middle. A top-to-bottom graph is anchored to the top edge, which does not move, so it is left alone.
    useEffect(() => {
        const previousCanvasHeight = previousCanvasHeightRef.current;

        previousCanvasHeightRef.current = flowHeight;

        if (
            !enabled ||
            !previousCanvasHeight ||
            !flowHeight ||
            previousCanvasHeight === flowHeight ||
            !viewportPlacedRef.current ||
            useLayoutDirectionStore.getState().layoutDirection !== 'LR'
        ) {
            return;
        }

        const placementNodes = getPlacementNodes(getNodes());

        if (placementNodes.length === 0) {
            return;
        }

        const {x, y, zoom} = getViewport();

        void setViewport({
            x,
            y: getResizedCanvasViewportY({
                canvasHeight: flowHeight,
                nodesBounds: getNodesBounds(placementNodes),
                previousCanvasHeight,
                topInset: CANVAS_TOP_INSET,
                viewportY: y,
                zoom,
            }),
            zoom,
        });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [flowHeight]);
}

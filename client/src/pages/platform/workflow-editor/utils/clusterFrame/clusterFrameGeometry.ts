import {calculateNodeWidth} from '@/pages/platform/cluster-element-editor/utils/clusterElementsUtils';
import {
    CLUSTER_ELEMENT_NODE_WIDTH,
    CLUSTER_ELEMENT_PLACEHOLDER_WIDTH,
    NODE_HEIGHT,
    NODE_WIDTH,
    PLACEHOLDER_NODE_HEIGHT,
    ROOT_CLUSTER_WIDTH,
} from '@/shared/constants';
import {Node} from '@xyflow/react';

/** Height of the box's header row, which members are never placed inside. */
export const CLUSTER_FRAME_HEADER_HEIGHT = 40;

/** Breathing room between the outermost member and the box edge. */
export const CLUSTER_FRAME_PADDING = 32;

/**
 * The dashed border ClusterFrameShell paints (`border-2`).
 *
 * React Flow positions a frame's CHILD nodes from the frame node's own origin, but anything inside
 * the frame element is laid out after its border. Left uncompensated that puts the root card -- and
 * every cluster element handle on it -- two pixels right of the placeholders those handles connect
 * to, so every edge in the box leans by a constant amount.
 */
export const CLUSTER_FRAME_BORDER_WIDTH = 2;

export const CLUSTER_FRAME_MIN_HEIGHT = 220;
export const CLUSTER_FRAME_MIN_WIDTH = 420;

/**
 * A circle element's label renders below its 72px circle and extends past `NODE_HEIGHT`. The cluster
 * placer reserves the same overhang when it computes subtree extents (`layoutUtils.tsx`), so the box
 * reserves it too — otherwise the bottom row's labels sit outside the border.
 */
export const CLUSTER_FRAME_LABEL_OVERHANG = 40;

/**
 * The two fields a member's size is derived from. Both are stamped on cluster element node data by
 * `clusterElementsNodesUtils`, but neither is declared on `NodeDataType` -- the same reason
 * `layoutUtils` reads them through a cast.
 */
interface ClusterMemberNodeDataI {
    clusterElementType?: string;
    clusterElementTypesCount?: number;
}

export interface ClusterMemberBoxI {
    height: number;
    width: number;
    x: number;
    y: number;
}

export interface ClusterFrameContentOriginI {
    x: number;
    y: number;
}

/**
 * The origin a box uses when no member reaches left of, or above, the root card — the overwhelmingly
 * common case, and the identity the geometry degrades to.
 */
export const DEFAULT_CLUSTER_FRAME_CONTENT_ORIGIN: ClusterFrameContentOriginI = {
    x: CLUSTER_FRAME_PADDING,
    y: CLUSTER_FRAME_HEADER_HEIGHT,
};

/**
 * Content-origin coordinates → the parent-relative coordinates React Flow positions a child by.
 * The two differ by the content origin, and this pair is the ONLY sanctioned crossing between them:
 * open-coding the offset drifts the whole box against its contents.
 *
 * Content coordinates are what `metadata.ui.nodePosition` stores and what the cluster placer emits,
 * both measured from the root CARD's top-left corner. Frame coordinates are measured from the box's
 * own top-left corner, which sits `contentOrigin` above and to the left of the card.
 */
export function toClusterFrameChildPosition(
    contentPosition: {x: number; y: number},
    contentOrigin: ClusterFrameContentOriginI = DEFAULT_CLUSTER_FRAME_CONTENT_ORIGIN
): {x: number; y: number} {
    return {x: contentPosition.x + contentOrigin.x, y: contentPosition.y + contentOrigin.y};
}

export function fromClusterFrameChildPosition(
    childPosition: {x: number; y: number},
    contentOrigin: ClusterFrameContentOriginI = DEFAULT_CLUSTER_FRAME_CONTENT_ORIGIN
): {x: number; y: number} {
    return {x: childPosition.x - contentOrigin.x, y: childPosition.y - contentOrigin.y};
}

/**
 * The footprint a member occupies, derived from what kind of node it is rather than from
 * `node.measured`.
 *
 * Member nodes are rebuilt from the workflow definition by `createClusterElementsNodes` on every
 * layout, and that builder sets neither `measured` nor `width`/`height` — React Flow's measurements
 * land on the STORE copies, which the next pre-pass discards. Sizing from `measured` therefore made
 * every member 0×0 in production and only ever worked in fixtures that hand-set it. The kind-based
 * sizes below are the same ones the cluster placer lays members out with, so the box and the
 * placement agree by construction. `measured` remains the fallback for a shape neither the placer
 * nor this function recognises.
 */
export function getClusterMemberSize(memberNode: Node): {height: number; width: number} {
    if (memberNode.type === 'placeholder') {
        return {height: PLACEHOLDER_NODE_HEIGHT, width: CLUSTER_ELEMENT_PLACEHOLDER_WIDTH};
    }

    const memberNodeData = memberNode.data as ClusterMemberNodeDataI | undefined;

    // A nested cluster root renders as a wide card whose width follows its own handle count, exactly
    // as `getClusterElementsLayoutElements` sizes it while resolving overlaps.
    if (memberNodeData?.clusterElementTypesCount) {
        return {
            height: NODE_HEIGHT,
            width: calculateNodeWidth(memberNodeData.clusterElementTypesCount) || ROOT_CLUSTER_WIDTH,
        };
    }

    if (memberNodeData?.clusterElementType) {
        return {height: NODE_HEIGHT + CLUSTER_FRAME_LABEL_OVERHANG, width: CLUSTER_ELEMENT_NODE_WIDTH};
    }

    return {
        height: memberNode.measured?.height ?? memberNode.height ?? NODE_HEIGHT,
        width: memberNode.measured?.width ?? memberNode.width ?? NODE_WIDTH,
    };
}

/**
 * How far the root card has to be pushed in from the box's top-left corner so that every member
 * lands inside the border.
 *
 * Negative content coordinates are routine, not exotic: the placer computes a first child's x as
 * `handleX - CLUSTER_ELEMENT_NODE_WIDTH / 2`, which is negative for the leftmost handle, and its
 * even-expansion pass shifts whole sibling rows further left. Those members would otherwise render
 * outside the box, and the member drag extent (which clamps at the box's own edge) would refuse to
 * let them back in.
 */
/**
 * The rectangle the card and every member together occupy, in content coordinates. The card is
 * always one of the boxes at (0, 0), so `left` and `top` are never positive: they are the push-in
 * a member reaching left of, or above, the card requires.
 */
function computeClusterContentExtent(childBoxes: ClusterMemberBoxI[]): {
    height: number;
    left: number;
    top: number;
    width: number;
} {
    const left = Math.min(0, ...childBoxes.map((childBox) => childBox.x));
    const top = Math.min(0, ...childBoxes.map((childBox) => childBox.y));
    const right = Math.max(...childBoxes.map((childBox) => childBox.x + childBox.width));
    const bottom = Math.max(...childBoxes.map((childBox) => childBox.y + childBox.height));

    return {height: bottom - top, left, top, width: right - left};
}

export function computeClusterFrameSize(childBoxes: ClusterMemberBoxI[]): {height: number; width: number} {
    if (childBoxes.length === 0) {
        return {height: CLUSTER_FRAME_MIN_HEIGHT, width: CLUSTER_FRAME_MIN_WIDTH};
    }

    const extent = computeClusterContentExtent(childBoxes);

    return {
        height: Math.max(CLUSTER_FRAME_MIN_HEIGHT, CLUSTER_FRAME_HEADER_HEIGHT + extent.height + CLUSTER_FRAME_PADDING),
        width: Math.max(CLUSTER_FRAME_MIN_WIDTH, extent.width + 2 * CLUSTER_FRAME_PADDING),
    };
}

/**
 * Where content coordinate (0, 0) -- the card's top-left corner -- sits inside a frame of the given
 * width.
 *
 * Horizontally the contents are centred: whatever the frame is wider than the contents by is split
 * evenly, which is exactly CLUSTER_FRAME_PADDING a side whenever the width was set by the contents,
 * and more when CLUSTER_FRAME_MIN_WIDTH floored it -- a narrow card used to sit against the left
 * border of a floored box with all the slack on its right. Vertically the contents hang from the
 * header. A member reaching left of, or above, the card pushes the origin in by that much on top.
 */
export function computeClusterFrameContentOrigin(
    childBoxes: ClusterMemberBoxI[],
    frameWidth: number
): ClusterFrameContentOriginI {
    if (childBoxes.length === 0) {
        return DEFAULT_CLUSTER_FRAME_CONTENT_ORIGIN;
    }

    const extent = computeClusterContentExtent(childBoxes);

    // Math.max rather than plain negation for `top`: `-0` is not `0` to Object.is, and this value is
    // compared and serialised.
    return {
        x: (frameWidth - extent.width) / 2 - extent.left,
        y: CLUSTER_FRAME_HEADER_HEIGHT + Math.max(0, -extent.top),
    };
}

/** Height of the box's header row, which members are never placed inside. */
export const CLUSTER_FRAME_HEADER_HEIGHT = 40;

/** Breathing room between the outermost member and the box edge. */
export const CLUSTER_FRAME_PADDING = 32;

export const CLUSTER_FRAME_MIN_HEIGHT = 220;
export const CLUSTER_FRAME_MIN_WIDTH = 420;

export interface ClusterMemberBoxI {
    height: number;
    width: number;
    x: number;
    y: number;
}

/**
 * Content-origin coordinates → the parent-relative coordinates React Flow positions a child by.
 * The two differ by the header band, and this pair is the ONLY sanctioned crossing between them:
 * open-coding the offset drifts the whole box against its contents.
 */
export function toClusterFrameChildPosition(contentPosition: {x: number; y: number}): {x: number; y: number} {
    return {x: contentPosition.x, y: contentPosition.y + CLUSTER_FRAME_HEADER_HEIGHT};
}

export function fromClusterFrameChildPosition(childPosition: {x: number; y: number}): {x: number; y: number} {
    return {x: childPosition.x, y: childPosition.y - CLUSTER_FRAME_HEADER_HEIGHT};
}

/**
 * The box a set of members needs. Measured from the content origin outward, so a member at x=0
 * still gets padding on its right and the header is added once on top.
 */
export function computeClusterFrameSize(childBoxes: ClusterMemberBoxI[]): {height: number; width: number} {
    if (childBoxes.length === 0) {
        return {height: CLUSTER_FRAME_MIN_HEIGHT, width: CLUSTER_FRAME_MIN_WIDTH};
    }

    const right = Math.max(...childBoxes.map((childBox) => childBox.x + childBox.width));
    const bottom = Math.max(...childBoxes.map((childBox) => childBox.y + childBox.height));

    return {
        height: Math.max(CLUSTER_FRAME_MIN_HEIGHT, bottom + CLUSTER_FRAME_PADDING + CLUSTER_FRAME_HEADER_HEIGHT),
        width: Math.max(CLUSTER_FRAME_MIN_WIDTH, right + CLUSTER_FRAME_PADDING),
    };
}

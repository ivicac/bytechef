import {NodeDataType} from '@/shared/types';
import {Edge, Node} from '@xyflow/react';

import {getClusterElementsLayoutElements} from '../layoutUtils';

interface PlaceClusterMembersProps {
    clusterRootId: string;
    edges: Edge[];
    memberNodes: Node[];
    rootClusterElements: NodeDataType['clusterElements'];
}

/**
 * Runs the cluster placer over one root's members and returns them positioned.
 *
 * This is the step box mode was missing: `createClusterElementsNodes` gives every element the
 * position stored on it, and there is none until somebody drags it —
 * `metadata?.ui?.nodePosition || DEFAULT_NODE_POSITION` is `{x: 0, y: 0}` for a freshly built agent
 * and for every element the box header's Reset layout button has just cleared. The dialog never saw
 * this because `useClusterElementsLayout` runs `getClusterElementsLayoutElements` afterwards; box
 * mode has to run the same placer, which is what this does.
 *
 * The placer requires a main root node in its input — the node every child hangs off — and centres
 * that root on the canvas it is given. Box mode has no such node in the member array (the root's
 * card lives on the outer canvas and IS the box), so a stand-in is synthesised here purely to give
 * the placer its anchor, and dropped again on the way out. Its own placement is discarded: the
 * placer positions children RELATIVE to it, so where it lands cannot move them. That is the
 * "fixed content origin" replacing `canvasCenterX` root-centering — the card renders at the content
 * origin, and every returned position is measured from there.
 *
 * A member the placer does not return (a shape it cannot classify) keeps the position it arrived
 * with rather than being dropped, so the box can never lose an element to a placer edge case.
 */
export function placeClusterMembers({
    clusterRootId,
    edges,
    memberNodes,
    rootClusterElements,
}: PlaceClusterMembersProps): Node[] {
    if (memberNodes.length === 0) {
        return memberNodes;
    }

    const firstDirectMember = memberNodes.find((memberNode) => memberNode.parentId === clusterRootId);

    const syntheticRootNode: Node = {
        data: {
            clusterElementTypesCount:
                (firstDirectMember?.data as {parentClusterRootElementsTypeCount?: number} | undefined)
                    ?.parentClusterRootElementsTypeCount ?? 1,
            // Truthy `clusterElements` is how the placer recognises its main root; the value itself is
            // only ever tested for presence there.
            clusterElements: rootClusterElements ?? {},
        },
        id: clusterRootId,
        position: {x: 0, y: 0},
        type: 'workflow',
    };

    const placedElements = getClusterElementsLayoutElements({
        // The root's own centering is discarded with the stand-in, so the canvas it would be centred
        // on is irrelevant; `currentRootPosition` additionally pins it to the content origin.
        canvasHeight: 0,
        canvasWidth: 0,
        currentRootPosition: {x: 0, y: 0},
        edges,
        nodes: [syntheticRootNode, ...memberNodes],
    });

    const placedPositionsById = new Map(
        placedElements.nodes.map((placedNode) => [placedNode.id, placedNode.position] as const)
    );

    return memberNodes.map((memberNode) => {
        const placedPosition = placedPositionsById.get(memberNode.id);

        return placedPosition ? {...memberNode, position: placedPosition} : memberNode;
    });
}

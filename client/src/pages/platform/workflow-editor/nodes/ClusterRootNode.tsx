import {NodeDataType} from '@/shared/types';
import {memo} from 'react';

import AiAgentNode from './AiAgentNode';
import WorkflowNode from './WorkflowNode';

/**
 * Chooses which component draws a cluster root, by view mode.
 *
 * Dialog mode keeps the compact `AiAgentNode` card, which is what the main canvas has always shown.
 * Box mode wants the wide card with its handles spread along the bottom edge -- and `WorkflowNode`
 * already draws exactly that, because it treats any node whose data says `clusterRoot` as a main
 * root cluster element. That is the same component, and the same card, the cluster element editor
 * dialog renders its root with, so the two modes cannot drift apart.
 *
 * This has to be a component boundary rather than a branch inside `AiAgentNode`: toggling the view
 * mode flips `data.clusterFrame` on a node React Flow keeps mounted, and a branch would change the
 * hook count of a live component. Switching between two child components makes React unmount one and
 * mount the other instead.
 */
const ClusterRootNode = ({data, id}: {data: NodeDataType; id: string}) =>
    data.clusterFrame ? <WorkflowNode data={data} id={id} /> : <AiAgentNode data={data} id={id} />;

export default memo(ClusterRootNode);

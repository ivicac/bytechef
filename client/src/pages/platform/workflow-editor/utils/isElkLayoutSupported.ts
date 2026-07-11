import {CHILDLESS_TASK_DISPATCHER_NAMES} from '@/shared/constants';
import {NodeDataType} from '@/shared/types';
import {Node} from '@xyflow/react';

// Dispatchers the ELK engine lays out as compound frames (phase 3c: condition,
// loop, branch, parallel, fork-join, each, map). Childless dispatchers
// (loopBreak, subflow, terminate) own no children and lay out as plain chain
// nodes, so they are supported without frames.
export const ELK_FRAME_DISPATCHER_COMPONENT_NAMES = [
    'branch',
    'condition',
    'each',
    'fork-join',
    'loop',
    'map',
    'parallel',
];

const SUPPORTED_DISPATCHER_COMPONENT_NAMES = new Set([
    ...CHILDLESS_TASK_DISPATCHER_NAMES,
    ...ELK_FRAME_DISPATCHER_COMPONENT_NAMES,
]);

/**
 * The experimental ELK layout engine supports plain task nodes, the frame
 * dispatchers listed above (arbitrarily nested in each other), and childless
 * dispatchers. Any other dispatcher (on-error) or a cluster root
 * (AI agent, data stream, approval — any task the server flags `clusterRoot`)
 * makes the workflow unsupported: layout falls back to dagre and the toolbar
 * switch is disabled.
 *
 * Operates on ReactFlow nodes rather than workflow tasks because dispatcher
 * children are flattened into the node array, so a single scan covers nesting.
 */
export default function isElkLayoutSupported(nodes: Node[]): boolean {
    return nodes.every((node) => {
        if (node.type === 'clusterRoot') {
            return false;
        }

        const nodeData = node.data as NodeDataType;

        if (nodeData.taskDispatcher && !SUPPORTED_DISPATCHER_COMPONENT_NAMES.has(nodeData.componentName)) {
            return false;
        }

        return true;
    });
}

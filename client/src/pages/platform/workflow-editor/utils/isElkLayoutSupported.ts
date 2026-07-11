import {CHILDLESS_TASK_DISPATCHER_NAMES} from '@/shared/constants';
import {NodeDataType} from '@/shared/types';
import {Node} from '@xyflow/react';

// Dispatchers the ELK engine lays out as compound frames (phase 2: condition +
// loop). Childless dispatchers (loopBreak, subflow, terminate) own no children
// and lay out as plain chain nodes, so they are supported without frames.
export const ELK_FRAME_DISPATCHER_COMPONENT_NAMES = ['condition', 'loop'];

const SUPPORTED_DISPATCHER_COMPONENT_NAMES = new Set([
    ...CHILDLESS_TASK_DISPATCHER_NAMES,
    ...ELK_FRAME_DISPATCHER_COMPONENT_NAMES,
]);

/**
 * The experimental ELK layout engine supports plain task nodes, the condition
 * and loop task dispatchers (arbitrarily nested in each other), and childless
 * dispatchers. Any other dispatcher (branch, each, fork-join, map, parallel,
 * on-error) or an AI-agent cluster root makes the workflow unsupported: layout
 * falls back to dagre and the toolbar switch is disabled.
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

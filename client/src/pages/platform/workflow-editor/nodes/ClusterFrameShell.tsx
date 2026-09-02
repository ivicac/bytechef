import {NodeDataType} from '@/shared/types';
import {Handle, Position} from '@xyflow/react';
import {ReactNode, memo} from 'react';

import useLayoutDirectionStore from '../stores/useLayoutDirectionStore';
import {CLUSTER_FRAME_HEADER_HEIGHT} from '../utils/clusterFrame/clusterFrameGeometry';
import {mapHandlePosition} from '../utils/directionUtils';
import styles from './NodeTypes.module.css';

interface ClusterFrameShellProps {
    children: ReactNode;
    data: NodeDataType;
    nodeId: string;
}

/**
 * The box a cluster root's elements are drawn inside. Unlike a graph frame this is not a node of its
 * own: the root IS its elements' React Flow parent, so the shell wraps the root's own card and takes
 * the size the pre-pass wrote onto `data.clusterFrame`.
 *
 * Renders its children bare when there is no box — dialog mode, and the first paint before the
 * pre-pass has run — so the same node component serves both modes without branching at the call site.
 *
 * The chain handles live here rather than on the card, so the surrounding flow connects to the box.
 */
const ClusterFrameShell = ({children, data, nodeId}: ClusterFrameShellProps) => {
    const layoutDirection = useLayoutDirectionStore((state) => state.layoutDirection);

    const clusterFrame = data.clusterFrame;

    if (!clusterFrame) {
        return <>{children}</>;
    }

    return (
        <div
            className="rounded-lg border-2 border-dashed border-stroke-neutral-secondary bg-surface-neutral-secondary/40"
            data-nodetype="clusterFrame"
            data-testid="cluster-frame-shell"
            style={{height: clusterFrame.height, width: clusterFrame.width}}
        >
            <div className="flex items-center justify-between px-3" style={{height: CLUSTER_FRAME_HEADER_HEIGHT}}>
                <span className="text-sm font-semibold text-content-neutral-secondary">{data.label}</span>
            </div>

            {children}

            <Handle
                className={styles.handle}
                id={`${nodeId}-top`}
                position={mapHandlePosition(Position.Top, layoutDirection)}
                type="target"
            />

            <Handle
                className={styles.handle}
                id={`${nodeId}-bottom`}
                position={mapHandlePosition(Position.Bottom, layoutDirection)}
                type="source"
            />
        </div>
    );
};

export default memo(ClusterFrameShell);

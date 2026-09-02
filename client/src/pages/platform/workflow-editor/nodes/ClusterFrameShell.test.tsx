import {NodeDataType} from '@/shared/types';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import ClusterFrameShell from './ClusterFrameShell';

vi.mock('@xyflow/react', () => ({
    Handle: () => null,
    Position: {Bottom: 'bottom', Left: 'left', Right: 'right', Top: 'top'},
}));

const CLUSTER_ROOT_DATA = {
    clusterFrame: {clusterRootId: 'aiAgent_1', height: 320, width: 640},
    label: 'AI Agent',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

describe('ClusterFrameShell', () => {
    it('paints the box at the size the pre-pass computed', () => {
        render(
            <ClusterFrameShell data={CLUSTER_ROOT_DATA} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        const shell = screen.getByTestId('cluster-frame-shell');

        expect(shell).toHaveStyle({height: '320px', width: '640px'});
        expect(screen.getByText('root card')).toBeInTheDocument();
    });

    it('renders the children bare when no box has been computed', () => {
        render(
            <ClusterFrameShell data={{label: 'AI Agent'} as NodeDataType} nodeId="aiAgent_1">
                <div>root card</div>
            </ClusterFrameShell>
        );

        expect(screen.queryByTestId('cluster-frame-shell')).not.toBeInTheDocument();
        expect(screen.getByText('root card')).toBeInTheDocument();
    });
});

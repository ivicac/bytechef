import {NodeDataType} from '@/shared/types';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import ClusterRootNode from './ClusterRootNode';

vi.mock('./AiAgentNode', () => ({default: () => <div data-testid="ai-agent-node" />}));
vi.mock('./WorkflowNode', () => ({default: () => <div data-testid="workflow-node" />}));

// The dispatcher's whole job is this choice, and getting it wrong is invisible in a unit test of
// either child: box mode needs the wide card `WorkflowNode` draws, because that is the card the
// cluster element editor dialog renders its own root with and the one the cluster placer positions
// handles and placeholders against.
describe('ClusterRootNode', () => {
    const data = {clusterRoot: true, componentName: 'aiAgent', workflowNodeName: 'aiAgent_1'} as NodeDataType;

    it('draws a box-mode root with the card the dialog uses', () => {
        render(
            <ClusterRootNode
                data={{
                    ...data,
                    clusterFrame: {clusterRootId: 'aiAgent_1', contentOrigin: {x: 0, y: 40}, height: 320, width: 640},
                }}
                id="aiAgent_1"
            />
        );

        expect(screen.getByTestId('workflow-node')).toBeInTheDocument();
        expect(screen.queryByTestId('ai-agent-node')).not.toBeInTheDocument();
    });

    it('keeps the compact agent card outside box mode', () => {
        render(<ClusterRootNode data={data} id="aiAgent_1" />);

        expect(screen.getByTestId('ai-agent-node')).toBeInTheDocument();
        expect(screen.queryByTestId('workflow-node')).not.toBeInTheDocument();
    });
});

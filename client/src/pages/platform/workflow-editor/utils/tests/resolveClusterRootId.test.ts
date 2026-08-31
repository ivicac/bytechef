import {NodeDataType} from '@/shared/types';
import {describe, expect, it} from 'vitest';

import {resolveClusterRootId} from '../resolveClusterRootId';

describe('resolveClusterRootId', () => {
    it('returns the parent root for an element', () => {
        expect(
            resolveClusterRootId({parentClusterRootId: 'aiAgent_1', workflowNodeName: 'model_1'} as NodeDataType)
        ).toBe('aiAgent_1');
    });

    it('returns itself for a root', () => {
        expect(resolveClusterRootId({clusterRoot: true, workflowNodeName: 'aiAgent_1'} as NodeDataType)).toBe(
            'aiAgent_1'
        );
    });

    it('returns undefined for a plain task', () => {
        expect(resolveClusterRootId({workflowNodeName: 'task_1'} as NodeDataType)).toBeUndefined();
    });

    it('prefers topLevelClusterRootId over the immediate parentClusterRootId', () => {
        // A depth-2 element: parentClusterRootId is its immediate (non-top-level) parent, which is
        // not addressable via getTask/workflowTasks -- topLevelClusterRootId is the one that is.
        expect(
            resolveClusterRootId({
                parentClusterRootId: 'agentTool_1',
                topLevelClusterRootId: 'aiAgent_1',
                workflowNodeName: 'tool_2',
            } as NodeDataType)
        ).toBe('aiAgent_1');
    });

    it('falls back to parentClusterRootId when topLevelClusterRootId is absent', () => {
        // A caller building a minimal, synthetic NodeDataType for a first-level element by hand --
        // parentClusterRootId already IS the outermost root at that depth.
        expect(
            resolveClusterRootId({parentClusterRootId: 'aiAgent_1', workflowNodeName: 'tool_1'} as NodeDataType)
        ).toBe('aiAgent_1');
    });
});

import {describe, expect, it, vi} from 'vitest';

vi.mock('@/pages/platform/workflow-editor/EmbeddableWorkflowEditor', () => ({
    default: () => null,
}));

vi.mock('@/shared/queries/automation/projectWorkflows.queries', () => ({
    useGetProjectWorkflowsQuery: vi.fn().mockReturnValue({data: undefined}),
}));

import {resolveWorkflowTabSelection} from '../AiHubWorkflowViewer';

describe('resolveWorkflowTabSelection', () => {
    const workflows = [
        {id: 'wf-a', label: 'Workflow A', projectWorkflowId: 11},
        {id: 'wf-b', label: 'Workflow B', projectWorkflowId: 12},
    ];

    it('resolves the openWorkflowTab args for the chosen projectWorkflowId', () => {
        expect(resolveWorkflowTabSelection(workflows, 'project-1', 12)).toEqual({
            name: 'Workflow B',
            projectId: 'project-1',
            projectWorkflowId: 12,
            workflowId: 'wf-b',
        });
    });

    it('returns null when no workflow matches', () => {
        expect(resolveWorkflowTabSelection(workflows, 'project-1', 99)).toBeNull();
    });

    it('returns null when the workflow list is undefined', () => {
        expect(resolveWorkflowTabSelection(undefined, 'project-1', 12)).toBeNull();
    });
});

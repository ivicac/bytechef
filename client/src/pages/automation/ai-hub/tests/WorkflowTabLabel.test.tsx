import {render, screen} from '@testing-library/react';
import {afterEach, describe, expect, it, vi} from 'vitest';

import WorkflowTabLabel from '../WorkflowTabLabel';

const useGetProjectQueryMock = vi.fn();

vi.mock('@/shared/queries/automation/projects.queries', () => ({
    useGetProjectQuery: (...args: unknown[]) => useGetProjectQueryMock(...args),
}));

describe('WorkflowTabLabel', () => {
    afterEach(() => {
        vi.clearAllMocks();
    });

    it('renders the project name and a V<version> <STATUS> badge once the project query resolves', () => {
        useGetProjectQueryMock.mockReturnValue({
            data: {lastProjectVersion: 2, lastStatus: 'DRAFT', name: 'AI Agent 2'},
        });

        render(<WorkflowTabLabel fallbackName="agent1" projectId="7" />);

        expect(screen.getByText('AI Agent 2')).toBeInTheDocument();
        expect(screen.getByText('V2')).toBeInTheDocument();
        expect(screen.getByText('DRAFT')).toBeInTheDocument();
    });

    it('falls back to the workflow name while the project is loading', () => {
        useGetProjectQueryMock.mockReturnValue({data: undefined});

        render(<WorkflowTabLabel fallbackName="agent1" projectId="7" />);

        expect(screen.getByText('agent1')).toBeInTheDocument();
    });
});

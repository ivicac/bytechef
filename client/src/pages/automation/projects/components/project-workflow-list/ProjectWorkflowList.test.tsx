import {TooltipProvider} from '@/components/ui/tooltip';
import {Project} from '@/shared/middleware/automation/configuration';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {ReactElement} from 'react';
import {MemoryRouter} from 'react-router-dom';
import {describe, expect, it, vi} from 'vitest';

import ProjectWorkflowList from './ProjectWorkflowList';

const mockGetProjectWorkflowsQuery = vi.fn();

vi.mock('@/shared/queries/automation/projectWorkflows.queries', () => ({
    useGetProjectWorkflowsQuery: (...args: unknown[]) => mockGetProjectWorkflowsQuery(...args),
}));

vi.mock('@/pages/automation/projects/components/project-workflow-list/ProjectWorkflowListItem', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({workflow}: any) => <li>{workflow.label}</li>,
}));

vi.mock('@/pages/automation/projects/components/project-workflow-list/ProjectWorkflowCreationActions', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({placement}: any) => <button aria-label="Create Workflow">Create Workflow ({placement})</button>,
}));

const renderWithProviders = (ui: ReactElement) => {
    const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

    return render(
        <QueryClientProvider client={queryClient}>
            <TooltipProvider>
                <MemoryRouter>{ui}</MemoryRouter>
            </TooltipProvider>
        </QueryClientProvider>
    );
};

const project = {codeWorkflow: false, id: 1} as Project;
const codeWorkflowProject = {codeWorkflow: true, id: 2} as Project;

describe('ProjectWorkflowList', () => {
    it('lists the workflows without its own create button when the project has workflows (the tab row has one)', () => {
        mockGetProjectWorkflowsQuery.mockReturnValue({data: [{id: 'w1', label: 'Workflow 1'}], isLoading: false});

        renderWithProviders(
            <ProjectWorkflowList componentDefinitions={[]} project={project} taskDispatcherDefinitions={[]} />
        );

        expect(screen.getByText('Workflow 1')).toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Create Workflow'})).not.toBeInTheDocument();
    });

    it('shows the create-workflow actions inside the empty state when the project has no workflows', () => {
        mockGetProjectWorkflowsQuery.mockReturnValue({data: [], isLoading: false});

        renderWithProviders(
            <ProjectWorkflowList componentDefinitions={[]} project={project} taskDispatcherDefinitions={[]} />
        );

        expect(screen.getByText('No Workflows')).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Create Workflow'})).toHaveTextContent('emptyState');
    });

    it('hides the create-workflow actions for a code-workflow project, whose workflows come from its source file', () => {
        mockGetProjectWorkflowsQuery.mockReturnValue({data: [], isLoading: false});

        renderWithProviders(
            <ProjectWorkflowList
                componentDefinitions={[]}
                project={codeWorkflowProject}
                taskDispatcherDefinitions={[]}
            />
        );

        expect(screen.getByText('No Workflows')).toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Create Workflow'})).not.toBeInTheDocument();
    });
});

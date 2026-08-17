import {TooltipProvider} from '@/components/ui/tooltip';
import {AiAgent} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen} from '@testing-library/react';
import {ReactNode} from 'react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import AgentList from './AgentList';

const {agentListItemMock, projectDeploymentDialogMock, projectsQueryDataMock} = vi.hoisted(() => ({
    agentListItemMock: vi.fn(),
    projectDeploymentDialogMock: vi.fn(),
    projectsQueryDataMock: [
        {id: 2, lastProjectVersion: 3, lastPublishedDate: new Date('2026-01-05T10:00:00Z'), name: 'Zeta Project'},
        {id: 1, lastProjectVersion: 1, lastStatus: 'DRAFT', name: 'Alpha Project'},
    ] as unknown[],
}));

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: vi.fn((selector) => selector({currentWorkspaceId: 7})),
}));

vi.mock('@/shared/queries/automation/projects.queries', () => ({
    useGetWorkspaceProjectsQuery: () => ({
        data: projectsQueryDataMock,
    }),
}));

vi.mock('@/shared/queries/automation/projectDeployments.queries', () => ({
    useGetWorkspaceProjectDeploymentsQuery: () => ({data: [], isFetching: false, refetch: vi.fn()}),
}));

// Rendered with the props AgentList passes, so a test can assert this is invoked exactly like the Projects
// page invokes it — same wizard, same prefilled name, same editable environment — rather than mocking away
// the very thing this component is responsible for wiring correctly.
vi.mock('@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog', () => ({
    default: (props: {triggerNode?: ReactNode}) => {
        projectDeploymentDialogMock(props);

        return <div data-testid="project-deployment-dialog">{props.triggerNode}</div>;
    },
}));

// AgentListItem's own row content is covered by AgentListItem.test.tsx. What belongs to AgentList is which
// project each agent lands under and the group heading above it.
vi.mock('./AgentListItem', () => ({
    default: (props: {agent: {id: string}}) => {
        agentListItemMock(props);

        return <li data-testid={`agent-item-${props.agent.id}`} />;
    },
}));

const renderList = (agents: Partial<AiAgent>[]) =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <MemoryRouter>
                <TooltipProvider>
                    <AgentList agents={agents as unknown as AiAgent[]} />
                </TooltipProvider>
            </MemoryRouter>
        </QueryClientProvider>
    );

describe('AgentList', () => {
    beforeEach(() => {
        agentListItemMock.mockClear();
    });

    it('groups agents under a heading for their project, sorted alphabetically by project name', () => {
        renderList([
            {id: 'agent-zeta', projectId: '2', title: 'Zeta Agent'},
            {id: 'agent-alpha', projectId: '1', title: 'Alpha Agent'},
        ]);

        const headings = screen.getAllByRole('link');

        expect(headings.map((heading) => heading.textContent)).toEqual(['Alpha Project', 'Zeta Project']);
    });

    // Agents keep the order the caller gave them within their own project — grouping must not re-sort them.
    it('keeps agents in their given order within a project', () => {
        renderList([
            {id: 'agent-second', projectId: '1', title: 'Second'},
            {id: 'agent-first', projectId: '1', title: 'First'},
        ]);

        expect(agentListItemMock.mock.calls.map((call) => call[0].agent.id)).toEqual(['agent-second', 'agent-first']);
    });

    // The group heading shows how many agents it holds, next to the chevron that expands and collapses it.
    it('shows the agent count next to the group’s expand/collapse control', () => {
        renderList([
            {id: 'agent-1', projectId: '1', title: 'First'},
            {id: 'agent-2', projectId: '1', title: 'Second'},
        ]);

        expect(screen.getByText('2 agents')).toBeInTheDocument();
    });

    it('links the project heading to the group’s first agent, matching the row badge’s own link shape', () => {
        renderList([
            {id: 'agent-1', projectId: '1', title: 'First'},
            {id: 'agent-2', projectId: '1', title: 'Second'},
        ]);

        expect(screen.getByRole('link')).toHaveAttribute('href', '/automation/projects/1/agents/agent-1');
    });

    // Defaults expanded, so a page with a handful of scheduled tasks does not open collapsed by default.
    it('lists the group’s agents expanded by default', () => {
        renderList([{id: 'agent-1', projectId: '1', title: 'Solo'}]);

        expect(screen.getByTestId('agent-item-agent-1')).toBeInTheDocument();
    });

    // Publishing is a project-level action, so the version badge and Deploy button read the group's PROJECT,
    // not any one agent inside it — a project with several scheduled agents shows one badge, not one per row.
    it('shows the published version and a Deploy button on the project row', () => {
        renderList([{id: 'agent-zeta', projectId: '2', title: 'Zeta Agent'}]);

        expect(screen.getByText('V2')).toBeInTheDocument();
        expect(screen.getByText('PUBLISHED')).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Deploy'})).toBeInTheDocument();
        expect(screen.getByText(/Published at/)).toBeInTheDocument();
    });

    it('shows a draft badge and no Deploy button for a project with no published version', () => {
        renderList([{id: 'agent-alpha', projectId: '1', title: 'Alpha Agent'}]);

        expect(screen.getByText('V1')).toBeInTheDocument();
        expect(screen.getByText('DRAFT')).toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Deploy'})).not.toBeInTheDocument();
        expect(screen.getByText('Not yet published')).toBeInTheDocument();
    });

    // The Scheduled page used to open this dialog locked to a fixed environment and version, with no name
    // prefilled and no "Change Version" tab. It must now behave exactly as it does from the Projects page.
    it('invokes the project deployment dialog with the same props the Projects page uses', () => {
        renderList([{id: 'agent-zeta', projectId: '2', title: 'Zeta Agent'}]);

        expect(projectDeploymentDialogMock).toHaveBeenCalledWith(
            expect.objectContaining({
                environmentEditable: true,
                showTabs: true,
            })
        );

        const [props] = projectDeploymentDialogMock.mock.calls[0];

        expect(props.projectDeployment).toEqual({name: 'Zeta Project', projectId: 2});
        expect(typeof props.onOpenChange).toBe('function');
    });

    it('shows no folder icon beside the project row heading', () => {
        const {container} = renderList([{id: 'agent-1', projectId: '1', title: 'Solo'}]);

        expect(container.querySelector('.lucide-folder')).not.toBeInTheDocument();
    });
});

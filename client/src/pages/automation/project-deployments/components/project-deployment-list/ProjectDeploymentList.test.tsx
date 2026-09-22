import {Project, ProjectDeployment} from '@/shared/middleware/automation/configuration';
import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {createContext, useContext} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import ProjectDeploymentList from './ProjectDeploymentList';

interface TabsContextValueI {
    onValueChange?: (value: string) => void;
    value?: string;
}

const TabsContext = createContext<TabsContextValueI>({});

const hoisted = vi.hoisted(() => ({
    agentDeployments: [] as {agentId: string; agentTitle: string; id: string; workflows: {workflowId: string}[]}[],
    agentListPropsMock: vi.fn(),
    agents: [] as {id: string; projectId: string; projectWorkflowUuid: string}[],
    dataSyncDeploymentListPropsMock: vi.fn(),
    dataSyncDeployments: [] as {dataSyncId: string; dataSyncTitle: string; id: string; workflowId: string}[],
    dataSyncs: [] as {id: string; projectId: string; projectWorkflowUuid: string}[],
    workflowListPropsMock: vi.fn(),
}));

vi.mock('@/components/ui/collapsible', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Collapsible: ({children}: any) => <div>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    CollapsibleContent: ({children}: any) => <div>{children}</div>,
}));

vi.mock('@/components/ui/tabs', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Tabs: ({children, onValueChange, value}: any) => (
        <TabsContext.Provider value={{onValueChange, value}}>
            <div>{children}</div>
        </TabsContext.Provider>
    ),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    TabsContent: ({children, value}: any) => {
        const {value: activeValue} = useContext(TabsContext);

        return activeValue === value ? <div data-testid={`tabs-content-${value}`}>{children}</div> : null;
    },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    TabsList: ({children}: any) => <div>{children}</div>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    TabsTrigger: ({children, value}: any) => {
        const {onValueChange} = useContext(TabsContext);

        return (
            <button data-testid={`tabs-trigger-${value}`} onClick={() => onValueChange?.(value)}>
                {children}
            </button>
        );
    },
}));

vi.mock('@/pages/automation/agents/hooks/useAgents', () => ({
    default: () => ({agents: hoisted.agents}),
}));

vi.mock('@/pages/automation/data-syncs/hooks/useDataSyncs', () => ({
    default: () => ({dataSyncs: hoisted.dataSyncs}),
}));

vi.mock('@/pages/automation/project-deployments/hooks/useAgentDeployments', () => ({
    default: () => ({agentDeployments: hoisted.agentDeployments}),
}));

vi.mock('@/pages/automation/project-deployments/hooks/useDataSyncDeployments', () => ({
    default: () => ({dataSyncDeployments: hoisted.dataSyncDeployments}),
}));

vi.mock('./ProjectDeploymentListItem', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({agentCount, dataSyncCount, projectDeployment, workflowCount}: any) => (
        <div data-testid="list-item">
            {projectDeployment.name}: {workflowCount} workflows, {agentCount} agents, {dataSyncCount} data syncs
        </div>
    ),
}));

vi.mock('../project-deployment-workflow-list/ProjectDeploymentWorkflowList', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: (props: any) => {
        hoisted.workflowListPropsMock(props);

        return <div data-testid="workflow-list" />;
    },
}));

vi.mock('../project-deployment-agent-list/ProjectDeploymentAgentList', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: (props: any) => {
        hoisted.agentListPropsMock(props);

        return <div data-testid="agent-list" />;
    },
}));

vi.mock('../project-deployment-data-sync-list/ProjectDeploymentDataSyncList', () => ({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: (props: any) => {
        hoisted.dataSyncDeploymentListPropsMock(props);

        return <div data-testid="data-sync-list" />;
    },
}));

const project = {id: 7, name: 'Sales'} as Project;

const projectDeployment = {
    enabled: true,
    environmentId: 1,
    id: 50,
    name: 'Sales Deployment',
    projectDeploymentWorkflows: [
        {enabled: true, workflowId: 'workflow1', workflowUuid: 'uuid-1'},
        {enabled: true, workflowId: 'workflow2', workflowUuid: 'uuid-2'},
        {enabled: true, workflowId: 'agent-workflow', workflowUuid: 'uuid-agent'},
        {enabled: true, workflowId: 'data-sync-workflow', workflowUuid: 'uuid-data-sync'},
    ],
    projectVersion: 2,
    tags: [],
} as ProjectDeployment;

const renderList = (projectDeployments: ProjectDeployment[] = [projectDeployment]) =>
    render(
        <ProjectDeploymentList
            componentDefinitions={[]}
            project={project}
            projectDeployments={projectDeployments}
            tags={[]}
            taskDispatcherDefinitions={[]}
        />
    );

describe('ProjectDeploymentList', () => {
    beforeEach(() => {
        hoisted.agentDeployments = [
            {agentId: '1', agentTitle: 'Support Bot', id: '50', workflows: [{workflowId: 'agent-workflow'}]},
            {agentId: '2', agentTitle: 'Elsewhere Bot', id: '51', workflows: [{workflowId: 'other-workflow'}]},
        ];
        hoisted.agents = [];
        hoisted.dataSyncDeployments = [
            {dataSyncId: '1', dataSyncTitle: 'CRM Sync', id: '50', workflowId: 'data-sync-workflow'},
        ];
        hoisted.dataSyncs = [];
        hoisted.agentListPropsMock.mockReset();
        hoisted.dataSyncDeploymentListPropsMock.mockReset();
        hoisted.workflowListPropsMock.mockReset();
    });

    it('shows Workflows, Agents and Data Syncs tabs with their counts, opening on Workflows', () => {
        renderList();

        expect(screen.getByTestId('tabs-trigger-workflows')).toHaveTextContent('Workflows (2)');
        expect(screen.getByTestId('tabs-trigger-agents')).toHaveTextContent('Agents (1)');
        expect(screen.getByTestId('tabs-trigger-dataSyncs')).toHaveTextContent('Data Syncs (1)');
        expect(screen.getByTestId('tabs-content-workflows')).toBeInTheDocument();
        expect(screen.queryByTestId('tabs-content-agents')).not.toBeInTheDocument();
        expect(screen.queryByTestId('tabs-content-dataSyncs')).not.toBeInTheDocument();
    });

    it('opens rows on the Agents tab when that is the default', () => {
        render(
            <ProjectDeploymentList
                componentDefinitions={[]}
                defaultActiveTab="agents"
                project={project}
                projectDeployments={[projectDeployment]}
                tags={[]}
                taskDispatcherDefinitions={[]}
            />
        );

        expect(screen.getByTestId('tabs-content-agents')).toBeInTheDocument();
        expect(screen.queryByTestId('tabs-content-workflows')).not.toBeInTheDocument();
    });

    it('opens rows on the Data Syncs tab when that is the default', () => {
        render(
            <ProjectDeploymentList
                componentDefinitions={[]}
                defaultActiveTab="dataSyncs"
                project={project}
                projectDeployments={[projectDeployment]}
                tags={[]}
                taskDispatcherDefinitions={[]}
            />
        );

        expect(screen.getByTestId('tabs-content-dataSyncs')).toBeInTheDocument();
        expect(screen.queryByTestId('tabs-content-workflows')).not.toBeInTheDocument();
    });

    it('hands the row header the ordinary workflow, agent and data sync counts', () => {
        renderList();

        expect(screen.getByTestId('list-item')).toHaveTextContent(
            'Sales Deployment: 2 workflows, 1 agents, 1 data syncs'
        );
    });

    it("leaves an agent's and a sync's generated workflow out of the Workflows tab", () => {
        renderList();

        expect(hoisted.workflowListPropsMock).toHaveBeenLastCalledWith(
            expect.objectContaining({
                projectDeploymentWorkflows: [
                    expect.objectContaining({workflowId: 'workflow1'}),
                    expect.objectContaining({workflowId: 'workflow2'}),
                ],
            })
        );
    });

    it('also recognises an agent workflow by the agent workflow uuid', () => {
        hoisted.agentDeployments = [];
        hoisted.agents = [{id: '1', projectId: '7', projectWorkflowUuid: 'uuid-2'}];

        renderList();

        expect(screen.getByTestId('tabs-trigger-workflows')).toHaveTextContent('Workflows (2)');
        expect(screen.getByTestId('tabs-trigger-agents')).toHaveTextContent('Agents (0)');
    });

    it('also recognises a data sync workflow by the sync workflow uuid', () => {
        hoisted.dataSyncDeployments = [];
        hoisted.dataSyncs = [{id: '1', projectId: '7', projectWorkflowUuid: 'uuid-2'}];

        renderList();

        expect(screen.getByTestId('tabs-trigger-workflows')).toHaveTextContent('Workflows (2)');
        expect(screen.getByTestId('tabs-trigger-dataSyncs')).toHaveTextContent('Data Syncs (0)');
    });

    it("passes only this deployment's agents to the Agents tab", async () => {
        const user = userEvent.setup();

        renderList();

        await user.click(screen.getByTestId('tabs-trigger-agents'));

        expect(screen.getByTestId('agent-list')).toBeInTheDocument();
        expect(hoisted.agentListPropsMock).toHaveBeenLastCalledWith(
            expect.objectContaining({
                agentDeployments: [expect.objectContaining({agentTitle: 'Support Bot'})],
                projectDeploymentWorkflows: projectDeployment.projectDeploymentWorkflows,
            })
        );
    });

    it("passes only this deployment's data syncs to the Data Syncs tab", async () => {
        const user = userEvent.setup();

        renderList();

        await user.click(screen.getByTestId('tabs-trigger-dataSyncs'));

        expect(screen.getByTestId('data-sync-list')).toBeInTheDocument();
        expect(hoisted.dataSyncDeploymentListPropsMock).toHaveBeenLastCalledWith(
            expect.objectContaining({
                dataSyncDeployments: [expect.objectContaining({dataSyncTitle: 'CRM Sync'})],
                projectDeploymentWorkflows: projectDeployment.projectDeploymentWorkflows,
            })
        );
    });

    it('shows No Workflows when the deployment only carries agents and data syncs', () => {
        renderList([
            {
                ...projectDeployment,
                projectDeploymentWorkflows: [
                    {enabled: true, workflowId: 'agent-workflow'},
                    {enabled: true, workflowId: 'data-sync-workflow'},
                ],
            } as ProjectDeployment,
        ]);

        expect(screen.getByTestId('tabs-trigger-workflows')).toHaveTextContent('Workflows (0)');
        expect(screen.getByText('No Workflows')).toBeInTheDocument();
        expect(screen.queryByTestId('workflow-list')).not.toBeInTheDocument();
    });
});

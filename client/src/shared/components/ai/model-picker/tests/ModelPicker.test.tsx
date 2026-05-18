import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

// vi.hoisted runs BEFORE the vi.mock factory hoist, so refs declared here are safely
// usable inside the mock factory. Plain module-scope `const` refs are not — Vitest hoists
// vi.mock calls above them and accessing them throws "Cannot access X before initialization."
const {modelsQueryMock, providersQueryMock} = vi.hoisted(() => ({
    modelsQueryMock: vi.fn(),
    providersQueryMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useWorkspaceAiGatewayModelsQuery: modelsQueryMock,
    useWorkspaceAiGatewayProvidersQuery: providersQueryMock,
}));

const ModelPicker = (await import('../ModelPicker')).default;

const openAiProvider = {
    baseUrl: null,
    config: null,
    createdBy: 'admin',
    createdDate: '2026-05-01T00:00:00Z',
    enabled: true,
    id: '100',
    lastModifiedBy: 'admin',
    lastModifiedDate: '2026-05-01T00:00:00Z',
    name: 'OpenAI',
    type: 'OPENAI',
    version: 1,
};

const anthropicProvider = {
    baseUrl: null,
    config: null,
    createdBy: 'admin',
    createdDate: '2026-05-01T00:00:00Z',
    enabled: true,
    id: '200',
    lastModifiedBy: 'admin',
    lastModifiedDate: '2026-05-01T00:00:00Z',
    name: 'Anthropic',
    type: 'ANTHROPIC',
    version: 1,
};

const gpt4oModel = {
    alias: 'GPT-4o',
    capabilities: null,
    contextWindow: 128000,
    createdDate: '2026-05-01T00:00:00Z',
    defaultRoutingPolicyId: null,
    enabled: true,
    id: 'm1',
    inputCostPerMTokens: null,
    lastModifiedDate: '2026-05-01T00:00:00Z',
    name: 'gpt-4o',
    outputCostPerMTokens: null,
    providerId: '100',
    version: 1,
};

const opusModel = {
    alias: 'Opus 4.7',
    capabilities: null,
    contextWindow: 200000,
    createdDate: '2026-05-01T00:00:00Z',
    defaultRoutingPolicyId: null,
    enabled: true,
    id: 'm2',
    inputCostPerMTokens: null,
    lastModifiedDate: '2026-05-01T00:00:00Z',
    name: 'claude-opus-4-7',
    outputCostPerMTokens: null,
    providerId: '200',
    version: 1,
};

const setupQueries = (
    providers: Array<typeof openAiProvider | null> = [openAiProvider, anthropicProvider],
    models: Array<typeof gpt4oModel | null> = [gpt4oModel, opusModel]
) => {
    providersQueryMock.mockReturnValue({data: {workspaceAiGatewayProviders: providers}});
    modelsQueryMock.mockReturnValue({data: {workspaceAiGatewayModels: models}});
};

describe('ModelPicker', () => {
    beforeEach(() => {
        modelsQueryMock.mockReset();
        providersQueryMock.mockReset();
    });

    it('renders trigger with workspace default label when nothing is selected', () => {
        setupQueries();

        render(
            <ModelPicker
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workspaceDefaultLabel="Workspace default"
                workspaceId={7}
            />
        );

        expect(screen.getByRole('button', {name: /select llm provider and model/i})).toHaveTextContent(
            'Workspace default'
        );
    });

    it('renders trigger with the selected model alias when both provider and model are set', () => {
        setupQueries();

        render(<ModelPicker onChange={vi.fn()} selectedModel="gpt-4o" selectedProvider="openai" workspaceId={7} />);

        // Alias is preferred over raw model name to match what's shown in AI Gateway Models settings.
        expect(screen.getByRole('button', {name: /select llm provider and model/i})).toHaveTextContent('GPT-4o');
    });

    it('renders trigger with the agent default model when agent-default props are provided and nothing else is selected', () => {
        setupQueries();

        render(
            <ModelPicker
                agentDefaultModel="claude-opus-4-7"
                agentDefaultProvider="anthropic"
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={7}
            />
        );

        expect(screen.getByRole('button', {name: /select llm provider and model/i})).toHaveTextContent('Opus 4.7');
    });

    it('opens the menu and renders the workspace-default sentinel + provider rows when the trigger is clicked', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workspaceDefaultLabel="Workspace default"
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.getByText('Use workspace default')).toBeInTheDocument();
        expect(screen.getByText('OpenAI')).toBeInTheDocument();
        expect(screen.getByText('Anthropic')).toBeInTheDocument();
    });

    it('renders the agent-default sentinel label when agent-default props are passed', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                agentDefaultModel="claude-opus-4-7"
                agentDefaultProvider="anthropic"
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        // Sentinel label flips to "Use agent default" when the picker has an agent-default to revert to.
        expect(screen.getByText('Use agent default')).toBeInTheDocument();
        expect(screen.queryByText('Use workspace default')).not.toBeInTheDocument();
    });

    it('fires onChange(null, null) when the default sentinel item is selected', async () => {
        setupQueries();

        const onChange = vi.fn();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={onChange}
                selectedModel="gpt-4o"
                selectedProvider="openai"
                workspaceDefaultLabel="Workspace default"
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        await user.click(screen.getByText('Use workspace default'));

        expect(onChange).toHaveBeenCalledWith(null, null);
    });

    it('sorts provider rows alphabetically by name', async () => {
        setupQueries([openAiProvider, anthropicProvider]);

        const user = userEvent.setup();

        render(<ModelPicker onChange={vi.fn()} selectedModel={null} selectedProvider={null} workspaceId={7} />);

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        const providerNames = screen.getAllByText(/^(Anthropic|OpenAI)$/).map((node) => node.textContent);

        // "Anthropic" comes before "OpenAI" alphabetically regardless of API-returned order.
        expect(providerNames[0]).toBe('Anthropic');
        expect(providerNames[1]).toBe('OpenAI');
    });

    it('filters provider rows when the user types in the search input', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(<ModelPicker onChange={vi.fn()} selectedModel={null} selectedProvider={null} workspaceId={7} />);

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        const search = screen.getByRole('textbox', {name: /search providers/i});

        await user.type(search, 'open');

        expect(screen.getByText('OpenAI')).toBeInTheDocument();
        expect(screen.queryByText('Anthropic')).not.toBeInTheDocument();
    });

    it('renders "no providers configured" when the workspace has no enabled providers', async () => {
        setupQueries([], []);

        const user = userEvent.setup();

        render(<ModelPicker onChange={vi.fn()} selectedModel={null} selectedProvider={null} workspaceId={7} />);

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.getByText('No providers configured.')).toBeInTheDocument();
    });

    it('hides providers whose models are all disabled or absent', async () => {
        // Anthropic has no models in this workspace → its row should not render even though the
        // provider itself is enabled (clicking it would have nothing to drill into).
        setupQueries([openAiProvider, anthropicProvider], [gpt4oModel]);

        const user = userEvent.setup();

        render(<ModelPicker onChange={vi.fn()} selectedModel={null} selectedProvider={null} workspaceId={7} />);

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.getByText('OpenAI')).toBeInTheDocument();
        expect(screen.queryByText('Anthropic')).not.toBeInTheDocument();
    });

    it('renders the Personal agents section when personalAgents and onSelectPersonalAgent are both passed', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                onSelectPersonalAgent={vi.fn()}
                personalAgents={[
                    {id: 1, name: 'research-bot', title: 'Research Assistant'},
                    {id: 2, name: 'code-reviewer', title: 'Code Reviewer'},
                ]}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        // Section header (sub-trigger) appears above the providers.
        expect(screen.getByText('Personal agents')).toBeInTheDocument();
    });

    it('hides the Personal agents section when onSelectPersonalAgent is omitted, even if personalAgents is non-empty', async () => {
        // Defensive: a caller that passes agents without an action would create dead rows the user
        // could focus but not click. The picker hides the section entirely in that case.
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                personalAgents={[{id: 1, name: 'research-bot', title: 'Research Assistant'}]}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.queryByText('Personal agents')).not.toBeInTheDocument();
    });

    it('hides the Personal agents section when personalAgents is empty', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                onSelectPersonalAgent={vi.fn()}
                personalAgents={[]}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.queryByText('Personal agents')).not.toBeInTheDocument();
    });

    it('renders the Workflow chats section when workflowChats and onSelectWorkflowChat are both passed', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                onSelectWorkflowChat={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workflowChats={[
                    {
                        label: 'Marketing — Lead Qualifier',
                        projectDeploymentId: 'pd-1',
                        workflowExecutionId: 'we-1',
                    },
                ]}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.getByText('Workflow chats')).toBeInTheDocument();
    });

    it('hides the Workflow chats section when onSelectWorkflowChat is omitted', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workflowChats={[
                    {
                        label: 'Marketing — Lead Qualifier',
                        projectDeploymentId: 'pd-1',
                        workflowExecutionId: 'we-1',
                    },
                ]}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.queryByText('Workflow chats')).not.toBeInTheDocument();
    });

    it('hides the Workflow chats section when workflowChats is empty', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker
                onChange={vi.fn()}
                onSelectWorkflowChat={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workflowChats={[]}
                workspaceId={7}
            />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.queryByText('Workflow chats')).not.toBeInTheDocument();
    });

    it('does not call the GraphQL queries when workspaceId is 0 (uninitialised workspace)', () => {
        setupQueries();

        render(<ModelPicker onChange={vi.fn()} selectedModel={null} selectedProvider={null} workspaceId={0} />);

        // The hook is called (React calls the hook unconditionally) but with `enabled: false`. We
        // assert the `enabled` flag flowed through — TanStack Query won't fire the network call.
        expect(providersQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({workspaceId: ''}),
            expect.objectContaining({enabled: false})
        );
        expect(modelsQueryMock).toHaveBeenCalledWith(
            expect.objectContaining({workspaceId: ''}),
            expect.objectContaining({enabled: false})
        );
    });

    it('icon-only mode hides the trigger label and surfaces it via the title tooltip', () => {
        setupQueries();

        render(
            <ModelPicker iconOnly onChange={vi.fn()} selectedModel="gpt-4o" selectedProvider="openai" workspaceId={7} />
        );

        const trigger = screen.getByRole('button', {name: /select llm provider and model/i});

        // Label text is not rendered inline when collapsed to an icon...
        expect(trigger).not.toHaveTextContent('GPT-4o');
        // ...but stays discoverable on hover via the native title attribute.
        expect(trigger).toHaveAttribute('title', 'GPT-4o');
    });

    it('icon-only mode still opens the provider menu when clicked', async () => {
        setupQueries();

        const user = userEvent.setup();

        render(
            <ModelPicker iconOnly onChange={vi.fn()} selectedModel={null} selectedProvider={null} workspaceId={7} />
        );

        await user.click(screen.getByRole('button', {name: /select llm provider and model/i}));

        expect(screen.getByText('OpenAI')).toBeInTheDocument();
    });
});

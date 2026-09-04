import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {render, screen, userEvent, within} from '@/shared/util/test-utils';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import ToolApprovals from '../ToolApprovals';

const hoisted = vi.hoisted(() => ({
    createRuleMutate: vi.fn(),
    defaultToolNames: ['deleteRecord', 'sendEmail'] as string[],
    deleteRuleMutate: vi.fn(),
    isFeatureFlagEnabled: vi.fn(() => true),
    rules: [
        {componentName: null, id: 'rule-1', mode: 'EXEMPT', toolKind: 'CATALOG', toolName: 'sendEmail'},
        {componentName: 'gmail', id: 'rule-2', mode: 'REQUIRE', toolKind: 'COMPONENT', toolName: 'sendEmail'},
    ] as {componentName: string | null; id: string; mode: string; toolKind: string; toolName: string}[],
    scopes: ['WORKSPACE_MANAGE'] as string[],
}));

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useAiHubToolApprovalDefaultToolNamesQuery: vi.fn(() => ({
            data: {aiHubToolApprovalDefaultToolNames: hoisted.defaultToolNames},
            isLoading: false,
        })),
        useAiHubToolApprovalRulesQuery: vi.fn(() => ({
            data: {aiHubToolApprovalRules: hoisted.rules},
            isLoading: false,
        })),
        useCreateAiHubToolApprovalRuleMutation: vi.fn(() => ({mutate: hoisted.createRuleMutate})),
        useDeleteAiHubToolApprovalRuleMutation: vi.fn(() => ({mutate: hoisted.deleteRuleMutate})),
        useMyWorkspaceScopesQuery: vi.fn(() => ({data: {myWorkspaceScopes: hoisted.scopes}})),
    };
});

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => hoisted.isFeatureFlagEnabled,
}));

vi.mock(import('@tanstack/react-query'), async (importOriginal) => ({
    ...(await importOriginal()),
    useQueryClient: vi.fn(() => ({invalidateQueries: vi.fn()}) as never),
}));

const renderPage = () =>
    render(
        <MemoryRouter>
            <ToolApprovals />
        </MemoryRouter>
    );

describe('ToolApprovals', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        hoisted.defaultToolNames = ['deleteRecord', 'sendEmail'];
        hoisted.isFeatureFlagEnabled = vi.fn(() => true);
        hoisted.rules = [
            {componentName: null, id: 'rule-1', mode: 'EXEMPT', toolKind: 'CATALOG', toolName: 'sendEmail'},
            {componentName: 'gmail', id: 'rule-2', mode: 'REQUIRE', toolKind: 'COMPONENT', toolName: 'sendEmail'},
        ];
        hoisted.scopes = ['WORKSPACE_MANAGE'];

        useWorkspaceStore.setState({currentWorkspaceId: 7});
    });

    it('renders nothing when the feature flag is off', () => {
        hoisted.isFeatureFlagEnabled = vi.fn(() => false);

        const {container} = renderPage();

        expect(container).toBeEmptyDOMElement();
    });

    it('lists every default tool name with an Exempt switch and creates a rule when toggled on', async () => {
        renderPage();

        const deleteRecordRow = screen.getByText('deleteRecord').closest('tr');

        expect(deleteRecordRow).not.toBeNull();

        const exemptSwitch = within(deleteRecordRow as HTMLElement).getByRole('switch', {name: 'Exempt'});

        expect(exemptSwitch).not.toBeChecked();

        await userEvent.click(exemptSwitch);

        expect(hoisted.createRuleMutate).toHaveBeenCalledWith({
            mode: 'EXEMPT',
            toolKind: 'CATALOG',
            toolName: 'deleteRecord',
            workspaceId: '7',
        });
    });

    it('renders an existing EXEMPT rule as checked and deletes it when toggled off', async () => {
        renderPage();

        const sendEmailRow = screen.getByText('sendEmail').closest('tr');

        expect(sendEmailRow).not.toBeNull();

        const exemptSwitch = within(sendEmailRow as HTMLElement).getByRole('switch', {name: 'Exempt'});

        expect(exemptSwitch).toBeChecked();

        await userEvent.click(exemptSwitch);

        expect(hoisted.deleteRuleMutate).toHaveBeenCalledWith({ruleId: 'rule-1', workspaceId: '7'});
    });

    it('renders a REQUIRE component rule in Workspace rules with a Delete button', () => {
        renderPage();

        expect(screen.getByText('gmail / sendEmail')).toBeInTheDocument();

        const ruleRow = screen.getByText('gmail / sendEmail').closest('tr');

        expect(ruleRow).not.toBeNull();
        expect(within(ruleRow as HTMLElement).getByRole('button', {name: /delete/i})).toBeInTheDocument();
    });

    it('hides mutation controls for a read-only user but still shows the data', () => {
        hoisted.scopes = [];

        renderPage();

        expect(screen.getByText('deleteRecord')).toBeInTheDocument();
        expect(screen.getByText('gmail / sendEmail')).toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Add rule'})).not.toBeInTheDocument();

        const sendEmailRow = screen.getByText('sendEmail').closest('tr');
        const exemptSwitch = within(sendEmailRow as HTMLElement).getByRole('switch', {name: 'Exempt'});

        expect(exemptSwitch).toBeDisabled();
    });

    it('submits a new rule from the Add rule dialog', async () => {
        renderPage();

        await userEvent.click(screen.getByRole('button', {name: 'Add rule'}));
        await userEvent.type(screen.getByLabelText('Component'), 'gmail');
        await userEvent.type(screen.getByLabelText('Tool name'), 'sendEmail');
        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(hoisted.createRuleMutate).toHaveBeenCalledWith({
            componentName: 'gmail',
            mode: 'REQUIRE',
            toolKind: 'COMPONENT',
            toolName: 'sendEmail',
            workspaceId: '7',
        });
    });
});

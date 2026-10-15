import {render, resetAll, screen, userEvent, waitFor, windowResizeObserver} from '@/shared/util/test-utils';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import IntegrationInstanceConfigurationDialog from '../IntegrationInstanceConfigurationDialog';

const hoisted = vi.hoisted(() => ({
    connectionDefinition: undefined as {authorizations: {title: string; type: string}[]} | undefined,
    createMutate: vi.fn(),
    integration: undefined as {componentName: string} | undefined,
    updateMutate: vi.fn(),
}));

vi.mock('@/ee/shared/mutations/embedded/integrationInstanceConfigurations.mutations', () => ({
    useCreateIntegrationInstanceConfigurationMutation: () => ({mutate: hoisted.createMutate, reset: vi.fn()}),
    useUpdateIntegrationInstanceConfigurationMutation: () => ({mutate: hoisted.updateMutate, reset: vi.fn()}),
}));

vi.mock('@/ee/shared/queries/embedded/integrationInstanceConfigurations.queries', () => ({
    IntegrationInstanceConfigurationKeys: {integrationInstanceConfigurations: ['integrationInstanceConfigurations']},
}));

vi.mock('@/ee/shared/queries/embedded/integrationInstanceConfigurationTags.queries', () => ({
    IntegrationInstanceConfigurationTagKeys: {integrationInstanceConfigurationTags: ['tags']},
}));

vi.mock('@/ee/shared/queries/embedded/integrationWorkflows.queries', () => ({
    useGetIntegrationVersionWorkflowsQuery: () => ({data: [], isPending: false}),
}));

vi.mock('@/ee/shared/queries/embedded/integrations.queries', () => ({
    IntegrationKeys: {integrations: ['integrations']},
    useGetIntegrationQuery: () => ({data: hoisted.integration}),
}));

vi.mock('@/shared/queries/platform/connectionDefinitions.queries', () => ({
    useGetConnectionDefinitionQuery: () => ({data: hoisted.connectionDefinition}),
}));

vi.mock('@/ee/pages/embedded/integration-instance-configurations/stores/useWorkflowsEnabledStore', () => ({
    useWorkflowsEnabledStore: (selector: (state: Record<string, unknown>) => unknown) =>
        selector({reset: vi.fn(), setWorkflowEnabled: vi.fn(), workflowEnabledMap: new Map()}),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: (selector: (state: Record<string, unknown>) => unknown) => selector({currentEnvironmentId: 1}),
}));

vi.mock('@/shared/hooks/useAnalytics', () => ({
    useAnalytics: () => ({captureIntegrationInstanceConfigurationCreated: vi.fn()}),
}));

vi.mock('@/pages/platform/workflow-editor/providers/workflowEditorProvider', () => ({
    WorkflowMockProvider: ({children}: {children: React.ReactNode}) => <>{children}</>,
}));

vi.mock('../IntegrationInstanceConfigurationDialogBasicStep', () => ({
    default: ({
        authorizations,
        setValue,
    }: {
        authorizations?: {type: string}[];
        setValue: (name: string, value: unknown) => void;
    }) => (
        <div data-testid="basic-step">
            <span data-testid="basic-step-authorizations">
                {authorizations?.map((authorization) => authorization.type).join(',')}
            </span>

            <button onClick={() => setValue('authorizationType', 'API_KEY')} type="button">
                Use API Key
            </button>
        </div>
    ),
}));

vi.mock('../IntegrationInstanceConfigurationDialogOauth2Step', () => ({
    default: () => <div data-testid="oauth2-step" />,
}));

vi.mock('../IntegrationInstanceConfigurationDialogWorkflowsStep', () => ({
    default: () => <div data-testid="workflows-step" />,
}));

vi.mock('@/shared/components/connection/ConnectionParameters', () => ({
    default: () => <div data-testid="connection-parameters" />,
}));

const onClose = vi.fn();

beforeEach(() => {
    windowResizeObserver();
});

afterEach(() => {
    resetAll();
    vi.clearAllMocks();

    hoisted.connectionDefinition = undefined;
    hoisted.integration = undefined;
});

describe('IntegrationInstanceConfigurationDialog', () => {
    describe('create mode', () => {
        it('should title the dialog for a new configuration and name the current step', () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            expect(screen.getByRole('heading', {name: /New Instance Configuration/})).toBeInTheDocument();
            expect(screen.getByRole('heading', {name: /Basic/})).toBeInTheDocument();
        });

        it('should render the basic step', () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            expect(screen.getByTestId('basic-step')).toBeInTheDocument();
        });
    });

    describe('authorization', () => {
        beforeEach(() => {
            hoisted.connectionDefinition = {
                authorizations: [
                    {title: 'API Key', type: 'API_KEY'},
                    {title: 'OAuth2', type: 'OAUTH2_AUTHORIZATION_CODE'},
                ],
            };
            hoisted.integration = {componentName: 'httpClient'};
        });

        it('should pass the connection authorizations to the basic step', () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            expect(screen.getByTestId('basic-step-authorizations')).toHaveTextContent(
                'API_KEY,OAUTH2_AUTHORIZATION_CODE'
            );
        });

        it('should default to the OAuth2 authorization and offer the OAuth2 step', async () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            await waitFor(() => expect(screen.getByRole('button', {name: 'Next'})).toBeInTheDocument());

            expect(screen.queryByRole('button', {name: 'Save'})).not.toBeInTheDocument();
        });

        it('should save the authorization selected in the basic step', async () => {
            const user = userEvent.setup();

            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            await user.click(screen.getByRole('button', {name: 'Use API Key'}));

            await user.click(await screen.findByRole('button', {name: 'Save'}));

            await waitFor(() =>
                expect(hoisted.createMutate).toHaveBeenCalledWith(
                    expect.objectContaining({authorizationType: 'API_KEY'})
                )
            );
        });
    });

    describe('edit mode', () => {
        it('should title the dialog for an existing configuration', () => {
            render(
                <IntegrationInstanceConfigurationDialog
                    integrationInstanceConfiguration={{
                        id: 1,
                        integrationId: 2,
                        integrationVersion: 1,
                        name: 'Existing',
                    }}
                    onClose={onClose}
                />
            );

            expect(screen.getByRole('heading', {name: /Edit Instance Configuration/})).toBeInTheDocument();
        });
    });

    describe('version upgrade', () => {
        it('should title the dialog for a version upgrade', () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} updateIntegrationVersion />);

            expect(screen.getByRole('heading', {name: 'Upgrade Integration Version'})).toBeInTheDocument();
        });
    });

    describe('dialog chrome', () => {
        it('should render the close and cancel controls', () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            expect(screen.getByRole('button', {name: 'Close'})).toBeInTheDocument();
            expect(screen.getByRole('button', {name: 'Cancel'})).toBeInTheDocument();
        });

        it('should keep the cancel button out of any form submission', () => {
            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            expect(screen.getByRole('button', {name: 'Cancel'})).toHaveAttribute('type', 'button');
        });
    });

    describe('interactions', () => {
        it('should call onClose when cancel is clicked', async () => {
            const user = userEvent.setup();

            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            await user.click(screen.getByRole('button', {name: 'Cancel'}));

            // closeDialog defers onClose through a setTimeout so the form can reset first.
            await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
        });

        it('should call onClose when the close button is clicked', async () => {
            const user = userEvent.setup();

            render(<IntegrationInstanceConfigurationDialog onClose={onClose} />);

            await user.click(screen.getByRole('button', {name: 'Close'}));

            await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
        });
    });
});

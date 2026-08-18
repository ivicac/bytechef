import {HubBuilderContext} from '@/ee/pages/embedded/automation-hub/hubBuilderContext';
import {AutomationHubKeys} from '@/ee/pages/embedded/automation-hub/queries/automationHub.queries';
import {useAutomationHubStore} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {useContext} from 'react';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import HubBuilderView from '../HubBuilderView';

// vi.mock factories hoist above module-scope `const`s, so refs they close over must come from
// vi.hoisted — see CLAUDE.md's Vitest mock factory hoisting note.
const {navigateMock, useGetWorkflowQueryMock} = vi.hoisted(() => ({
    navigateMock: vi.fn(),
    useGetWorkflowQueryMock: vi.fn(),
}));

vi.mock('react-router-dom', async () => {
    const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');

    return {...actual, useNavigate: () => navigateMock};
});

vi.mock('@/ee/pages/embedded/automation-hub/queries/automationHub.queries', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/ee/pages/embedded/automation-hub/queries/automationHub.queries')>()),
    useGetWorkflowQuery: useGetWorkflowQueryMock,
}));

// The builder itself is out of scope here — this test only verifies that HubBuilderView provides
// the hub's settings to it via context, so the real (heavy) WorkflowBuilder is replaced with a
// probe that renders one of the three context values back out. A `function` declaration (rather
// than a `vi.hoisted` const) is used so it is fully hoisted before the `vi.mock` factory below
// (which itself hoists above module-scope `const`s — see CLAUDE.md's Vitest mock factory hoisting
// note) can reference it.
function WorkflowBuilderProbe() {
    const hubContext = useContext(HubBuilderContext);

    return (
        <div data-testid="workflow-builder-probe">
            <span data-testid="shared-connection-ids">{hubContext?.sharedConnectionIds.join(',')}</span>

            <button onClick={hubContext?.onBack} type="button">
                Back to automations
            </button>
        </div>
    );
}

vi.mock('@/ee/pages/embedded/workflow-builder/WorkflowBuilder', () => ({
    default: WorkflowBuilderProbe,
}));

const DEFAULT_TABS = {automations: true, connections: true, newWorkflow: true};

describe('HubBuilderView', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        useAutomationHubStore.setState({
            connectionDialogAllowed: true,
            includeComponents: undefined,
            initialized: true,
            sharedConnectionIds: [3, 4],
            tabs: DEFAULT_TABS,
            theme: {},
        });

        useGetWorkflowQueryMock.mockReturnValue({data: {label: 'Sync leads'}});
    });

    it('forwards the hub store settings to the builder through context', () => {
        render(
            <QueryClientProvider client={new QueryClient()}>
                <MemoryRouter initialEntries={['/embedded/hub/builder/wf-1']}>
                    <Routes>
                        <Route element={<HubBuilderView />} path="/embedded/hub/builder/:workflowUuid" />
                    </Routes>
                </MemoryRouter>
            </QueryClientProvider>
        );

        expect(screen.getByTestId('shared-connection-ids')).toHaveTextContent('3,4');
    });

    // The back control is rendered by the BUILDER's own header, from `onBack` on the context, so the
    // hub contributes no header row of its own — the workflow's name is on screen once, not twice.
    it('navigates back to the hub and invalidates the automations query on back click', async () => {
        const user = userEvent.setup();
        const queryClient = new QueryClient();
        const invalidateQueriesSpy = vi.spyOn(queryClient, 'invalidateQueries');

        render(
            <QueryClientProvider client={queryClient}>
                <MemoryRouter initialEntries={['/embedded/hub/builder/wf-1']}>
                    <Routes>
                        <Route element={<HubBuilderView />} path="/embedded/hub/builder/:workflowUuid" />
                    </Routes>
                </MemoryRouter>
            </QueryClientProvider>
        );

        await user.click(screen.getByRole('button', {name: 'Back to automations'}));

        expect(invalidateQueriesSpy).toHaveBeenCalledWith({queryKey: AutomationHubKeys.automations});
        expect(navigateMock).toHaveBeenCalledWith('/embedded/hub');
    });

    it('returns to the catalog when the workflow will not load, so a stale route is escapable', async () => {
        // The hub restores the route it was on across a host refresh, and the automation may have
        // been deleted since. There is no tab strip in the builder, so it has to leave by itself.
        useGetWorkflowQueryMock.mockReturnValue({data: undefined, error: new Error('not found')});

        render(
            <QueryClientProvider client={new QueryClient()}>
                <MemoryRouter initialEntries={['/embedded/hub/builder/wf-1']}>
                    <Routes>
                        <Route element={<HubBuilderView />} path="/embedded/hub/builder/:workflowUuid" />
                    </Routes>
                </MemoryRouter>
            </QueryClientProvider>
        );

        await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/embedded/hub', {replace: true}));
    });

    it('stays put while the workflow loads cleanly', () => {
        render(
            <QueryClientProvider client={new QueryClient()}>
                <MemoryRouter initialEntries={['/embedded/hub/builder/wf-1']}>
                    <Routes>
                        <Route element={<HubBuilderView />} path="/embedded/hub/builder/:workflowUuid" />
                    </Routes>
                </MemoryRouter>
            </QueryClientProvider>
        );

        expect(navigateMock).not.toHaveBeenCalled();
    });
});

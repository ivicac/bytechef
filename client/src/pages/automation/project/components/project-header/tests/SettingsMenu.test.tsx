import {TooltipProvider} from '@/components/ui/tooltip';
import SettingsMenu, {
    SettingsMenuFirstTabProps,
} from '@/pages/automation/project/components/project-header/components/settings-menu/SettingsMenu';
import {UpdateWorkflowMutationType} from '@/shared/types';
import {render, screen, userEvent, waitFor} from '@/shared/util/test-utils';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, expect, it, vi} from 'vitest';

screen.debug();

const mockProject = {
    id: 1,
    name: 'Test Project',
    workspaceId: 1,
};

const mockWorkflow = {
    id: '1',
    label: 'Test Workflow',
};

const mockUpdateWorkflowMutation = vi.fn().mockResolvedValue(undefined);

const createTestQueryClient = () =>
    new QueryClient({
        defaultOptions: {
            queries: {
                retry: false,
            },
        },
    });

const queryClient = createTestQueryClient();

afterEach(() => {
    queryClient.clear();
});

const renderSettingsMenu = ({firstTab}: {firstTab?: SettingsMenuFirstTabProps} = {}) => {
    render(
        <MemoryRouter>
            <QueryClientProvider client={queryClient}>
                <TooltipProvider>
                    <Routes>
                        <Route
                            element={
                                <SettingsMenu
                                    firstTab={firstTab}
                                    project={mockProject}
                                    updateWorkflowMutation={
                                        firstTab
                                            ? undefined
                                            : (mockUpdateWorkflowMutation as unknown as UpdateWorkflowMutationType)
                                    }
                                    workflow={firstTab ? undefined : mockWorkflow}
                                />
                            }
                            path="/"
                        />
                    </Routes>
                </TooltipProvider>
            </QueryClientProvider>
        </MemoryRouter>
    );
};

it('should open the settings dropdown menu on click of a settings button', async () => {
    renderSettingsMenu();

    expect(screen.getByLabelText('Settings')).toBeInTheDocument();

    expect(screen.queryByLabelText('Workflow tab')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Settings'));

    await waitFor(() => {
        expect(screen.getByText('Edit')).toBeInTheDocument();

        expect(screen.getByLabelText('Workflow tab')).toBeInTheDocument();

        expect(screen.getByLabelText('Project tab')).toBeInTheDocument();
    });
});

it('should open project tab on click', async () => {
    renderSettingsMenu();

    expect(screen.queryByLabelText('Workflow tab')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Settings'));

    expect(screen.queryByText('Project History')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Project tab'));

    await waitFor(() => {
        expect(screen.getByText('Project History')).toBeInTheDocument();
    });
});

it('should close the dropdown on click of a button inside the Workflow tab', async () => {
    renderSettingsMenu();

    expect(screen.queryByLabelText('Workflow tab')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Settings'));

    expect(screen.getByLabelText('Workflow tab')).toBeInTheDocument();

    await userEvent.click(screen.getByText('Export'));

    await waitFor(() => {
        expect(screen.queryByLabelText('Workflow tab')).not.toBeInTheDocument();
    });
});

it('should close the dropdown on click of a button inside the Project tab', async () => {
    renderSettingsMenu();

    expect(screen.queryByLabelText('Project tab')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Settings'));

    expect(screen.getByLabelText('Project tab')).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Project tab'));

    await userEvent.click(screen.getByText('Project History'));

    await waitFor(() => {
        expect(screen.queryByLabelText('Project tab')).not.toBeInTheDocument();
    });
});

// The agent page has no workflow of its own — it plugs in an Agent tab through `firstTab` instead of the
// default Workflow tab, with the Project tab unchanged.
it('should show the caller-supplied firstTab in place of the Workflow tab, alongside the Project tab', async () => {
    const mockCloseDropdownMenu = vi.fn();

    renderSettingsMenu({
        firstTab: {
            ariaLabel: 'Agent tab',
            content: (onCloseDropdownMenu) => (
                <button
                    onClick={() => {
                        mockCloseDropdownMenu();
                        onCloseDropdownMenu();
                    }}
                >
                    stub agent tab content
                </button>
            ),
            label: 'Agent',
            value: 'agent',
        },
    });

    await userEvent.click(screen.getByLabelText('Settings'));

    expect(screen.queryByLabelText('Workflow tab')).not.toBeInTheDocument();

    await waitFor(() => {
        expect(screen.getByLabelText('Agent tab')).toBeInTheDocument();
        expect(screen.getByLabelText('Project tab')).toBeInTheDocument();
        expect(screen.getByText('stub agent tab content')).toBeInTheDocument();
    });

    await userEvent.click(screen.getByLabelText('Project tab'));

    await waitFor(() => {
        expect(screen.getByText('Project History')).toBeInTheDocument();
    });

    await userEvent.click(screen.getByLabelText('Agent tab'));

    await userEvent.click(screen.getByText('stub agent tab content'));

    expect(mockCloseDropdownMenu).toHaveBeenCalled();

    await waitFor(() => {
        expect(screen.queryByLabelText('Agent tab')).not.toBeInTheDocument();
    });
});

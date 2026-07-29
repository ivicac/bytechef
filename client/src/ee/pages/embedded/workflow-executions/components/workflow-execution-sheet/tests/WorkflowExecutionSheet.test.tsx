import {TooltipProvider} from '@/components/ui/tooltip';
import {render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {featureFlagMock, handleCopilotClickMock, useWorkflowExecutionSheetMock} = vi.hoisted(() => ({
    featureFlagMock: vi.fn(),
    handleCopilotClickMock: vi.fn(),
    useWorkflowExecutionSheetMock: vi.fn(),
}));

vi.mock('../hooks/useWorkflowExecutionSheet', () => ({default: useWorkflowExecutionSheetMock}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({useFeatureFlagsStore: () => featureFlagMock}));

vi.mock('@/shared/components/copilot/CopilotPanel', () => ({
    default: ({open}: {open: boolean}) => (open ? <div data-testid="copilot-panel" /> : null),
}));

vi.mock('@/components/ui/resizable', () => ({
    ResizableHandle: () => <div />,
    ResizablePanel: ({children}: {children: ReactNode}) => <div>{children}</div>,
    ResizablePanelGroup: ({children}: {children: ReactNode}) => <div>{children}</div>,
}));

vi.mock('@/ee/shared/queries/embedded/componentDefinitions.queries', () => ({
    useGetComponentDefinitionsQuery: vi.fn(),
}));

vi.mock('@/pages/platform/workflow-editor/providers/workflowEditorProvider', () => ({
    WorkflowReadOnlyProvider: ({children}: {children: ReactNode}) => <>{children}</>,
}));

vi.mock('../WorkflowExecutionSheetContent', () => ({default: () => <div data-testid="sheet-content" />}));

vi.mock(
    '@/ee/pages/embedded/workflow-executions/components/workflow-execution-sheet/WorkflowExecutionSheetWorkflowPanel',
    () => ({default: () => <div data-testid="sheet-workflow-panel" />})
);

const WorkflowExecutionSheet = (await import('../WorkflowExecutionSheet')).default;

const renderSheet = () => render(<WorkflowExecutionSheet />, {wrapper: TooltipProvider});

const baseHookReturn = {
    copilotEnabled: true,
    copilotPanelOpen: false,
    handleCopilotClick: handleCopilotClickMock,
    handleCopilotClose: vi.fn(),
    handleOpenChange: vi.fn(),
    workflowExecution: undefined,
    workflowExecutionId: 501,
    workflowExecutionLoading: false,
    workflowExecutionSheetOpen: true,
};

describe('embedded WorkflowExecutionSheet', () => {
    beforeEach(() => {
        featureFlagMock.mockReset().mockReturnValue(true);
        handleCopilotClickMock.mockReset();
        useWorkflowExecutionSheetMock.mockReset().mockReturnValue(baseHookReturn);
    });

    describe('header', () => {
        it('shows the integration version after the workflow in the header', () => {
            useWorkflowExecutionSheetMock.mockReturnValue({
                ...baseHookReturn,
                workflowExecution: {
                    id: 1,
                    integration: {name: 'HubSpot'},
                    integrationInstanceConfiguration: {integrationVersion: 2},
                    job: {id: '1', status: 'COMPLETED'},
                    workflow: {label: 'Sync contacts'},
                },
            });

            renderSheet();

            expect(document.querySelector('header')).toHaveTextContent('HubSpot /Sync contacts/ V2');
        });

        it('shows no version when the instance configuration has none', () => {
            useWorkflowExecutionSheetMock.mockReturnValue({
                ...baseHookReturn,
                workflowExecution: {
                    id: 1,
                    integration: {name: 'HubSpot'},
                    integrationInstanceConfiguration: {},
                    job: {id: '1', status: 'COMPLETED'},
                    workflow: {label: 'Sync contacts'},
                },
            });

            renderSheet();

            expect(document.querySelector('header')).toHaveTextContent('HubSpot /Sync contacts');
            expect(document.querySelector('header')).not.toHaveTextContent('/ V');
        });
    });

    describe('copilot toggle', () => {
        it('renders the copilot toggle when the feature flag and copilot are both enabled', () => {
            renderSheet();

            expect(screen.getByRole('button', {name: 'Open Copilot panel'})).toBeInTheDocument();
        });

        it('hides the copilot toggle when the feature flag is disabled', () => {
            featureFlagMock.mockReturnValue(false);

            renderSheet();

            expect(screen.queryByRole('button', {name: 'Open Copilot panel'})).not.toBeInTheDocument();
        });

        it('hides the copilot toggle when copilot is disabled', () => {
            useWorkflowExecutionSheetMock.mockReturnValue({...baseHookReturn, copilotEnabled: false});

            renderSheet();

            expect(screen.queryByRole('button', {name: 'Open Copilot panel'})).not.toBeInTheDocument();
        });

        it('calls handleCopilotClick when the toggle is clicked', async () => {
            const user = userEvent.setup();

            renderSheet();

            await user.click(screen.getByRole('button', {name: 'Open Copilot panel'}));

            expect(handleCopilotClickMock).toHaveBeenCalled();
        });

        it('renders the copilot panel open when copilotPanelOpen is true', () => {
            useWorkflowExecutionSheetMock.mockReturnValue({...baseHookReturn, copilotPanelOpen: true});

            renderSheet();

            expect(screen.getByTestId('copilot-panel')).toBeInTheDocument();
        });
    });
});

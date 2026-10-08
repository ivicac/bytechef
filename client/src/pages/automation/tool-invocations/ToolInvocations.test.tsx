import {ComboBoxItemType} from '@/components/ComboBox/ComboBox';
import {TooltipProvider} from '@/components/ui/tooltip';
import {render, screen} from '@/shared/util/test-utils';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {ToolInvocations} from './ToolInvocations';

const hoisted = vi.hoisted(() => ({
    useToolInvocationsMock: vi.fn(),
}));

vi.mock('@/pages/automation/tool-invocations/hooks/useToolInvocations', () => ({
    useToolInvocations: hoisted.useToolInvocationsMock,
}));

vi.mock('@/components/ComboBox/ComboBox', () => ({
    default: ({items}: {items: Array<ComboBoxItemType>}) => (
        <ul>
            {items.map((item) => (
                <li data-testid="combo-box-item" key={item.value}>
                    {item.value}
                </li>
            ))}
        </ul>
    ),
}));

const toolInvocationsState = () => ({
    filterPageNumber: 0,
    handleAppChange: vi.fn(),
    handleEndDateChange: vi.fn(),
    handleMcpServerChange: vi.fn(),
    handleOutcomeChange: vi.fn(),
    handlePaginationClick: vi.fn(),
    handleStartDateChange: vi.fn(),
    handleSurfaceChange: vi.fn(),
    refetchToolInvocations: vi.fn(),
    toolInvocationLogPage: undefined,
    toolInvocationLogs: [],
    toolInvocationLogsError: null,
    toolInvocationLogsIsFetching: false,
    toolInvocationLogsIsLoading: false,
});

const renderedItemValues = () => screen.getAllByTestId('combo-box-item').map((item) => item.textContent);

describe('ToolInvocations', () => {
    beforeEach(() => {
        hoisted.useToolInvocationsMock.mockReset();
        hoisted.useToolInvocationsMock.mockReturnValue(toolInvocationsState());
    });

    it('offers every surface on the tenant-wide page', () => {
        render(
            <MemoryRouter>
                <TooltipProvider>
                    <ToolInvocations />
                </TooltipProvider>
            </MemoryRouter>
        );

        expect(renderedItemValues()).toEqual(
            expect.arrayContaining([
                'AI_AGENT',
                'AUTOMATION_APP_ACTION',
                'AUTOMATION_APP_API',
                'AUTOMATION_APP_PROXY',
                'AUTOMATION_APP_TOOL',
                'AUTOMATION_APP_WORKFLOW',
                'EMBEDDED_API_ACTION',
                'EMBEDDED_API_TOOL',
                'MCP_AUTOMATION',
                'MCP_EMBEDDED',
                'MCP_MANAGEMENT',
            ])
        );
    });

    it('offers only the surfaces that can occur in a workspace on the workspace-scoped page', () => {
        render(
            <MemoryRouter>
                <TooltipProvider>
                    <ToolInvocations workspaceScope={{environmentId: 2, workspaceId: 7}} />
                </TooltipProvider>
            </MemoryRouter>
        );

        const itemValues = renderedItemValues();

        expect(itemValues).toEqual(
            expect.arrayContaining([
                'AI_AGENT',
                'AUTOMATION_APP_ACTION',
                'AUTOMATION_APP_API',
                'AUTOMATION_APP_PROXY',
                'AUTOMATION_APP_TOOL',
                'AUTOMATION_APP_WORKFLOW',
                'MCP_AUTOMATION',
            ])
        );
        expect(itemValues).not.toContain('EMBEDDED_API_ACTION');
        expect(itemValues).not.toContain('EMBEDDED_API_TOOL');
        expect(itemValues).not.toContain('MCP_EMBEDDED');
        expect(itemValues).not.toContain('MCP_MANAGEMENT');
    });
});

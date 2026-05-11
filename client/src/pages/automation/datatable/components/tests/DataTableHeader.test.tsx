import {TooltipProvider} from '@/components/ui/tooltip';
import {render, resetAll, screen, userEvent, windowResizeObserver} from '@/shared/util/test-utils';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import DataTableHeader from '../DataTableHeader';

// The sidebar toggle in the title renders a Tooltip, which throws outside a provider.
const renderHeader = () =>
    render(
        <TooltipProvider>
            <DataTableHeader />
        </TooltipProvider>
    );

const hoisted = vi.hoisted(() => {
    return {
        mockHandleOpenDeleteRowsDialog: vi.fn(),
        mockHandleOpenImportCsvDialog: vi.fn(),
        storeState: {
            dataTable: {baseName: 'TestTable', id: 'table-123'} as {baseName: string; id: string} | null,
            selectedRowsCount: 0,
        },
    };
});

vi.mock('../../hooks/useDataTableHeader', () => ({
    default: () => ({
        handleOpenDeleteRowsDialog: hoisted.mockHandleOpenDeleteRowsDialog,
        selectedRowsCount: hoisted.storeState.selectedRowsCount,
    }),
}));

vi.mock('../../hooks/useImportDataTableCsvDialog', () => ({
    default: () => ({
        handleOpen: hoisted.mockHandleOpenImportCsvDialog,
    }),
}));

vi.mock('../../stores/useCurrentDataTableStore', () => ({
    useCurrentDataTableStore: () => ({
        dataTable: hoisted.storeState.dataTable,
    }),
}));

vi.mock('@/pages/automation/datatables/components/DataTableDropdownMenu', () => ({
    default: ({
        baseName,
        dataTableId,
        onImportCsv,
    }: {
        baseName: string;
        dataTableId: string;
        onImportCsv?: () => void;
    }) => (
        <div data-testid="actions-menu">
            <button data-testid="import-btn" onClick={onImportCsv}>
                Import CSV
            </button>

            <span data-testid="table-id">{dataTableId}</span>

            <span data-testid="table-base-name">{baseName}</span>
        </div>
    ),
}));

beforeEach(() => {
    windowResizeObserver();
    hoisted.storeState.selectedRowsCount = 0;
    hoisted.storeState.dataTable = {baseName: 'TestTable', id: 'table-123'};
});

afterEach(() => {
    resetAll();
    vi.clearAllMocks();
});

describe('DataTableHeader', () => {
    describe('rendering', () => {
        it('should render the table name', () => {
            renderHeader();

            expect(screen.getAllByText('TestTable')[0]).toBeInTheDocument();
        });

        it('should render the actions menu', () => {
            renderHeader();

            expect(screen.getByTestId('actions-menu')).toBeInTheDocument();
        });

        it('should pass the current table to the actions menu', () => {
            renderHeader();

            expect(screen.getByTestId('table-id')).toHaveTextContent('table-123');
            expect(screen.getByTestId('table-base-name')).toHaveTextContent('TestTable');
        });

        it('should not render the actions menu when no table is loaded', () => {
            hoisted.storeState.dataTable = null;

            renderHeader();

            expect(screen.queryByTestId('actions-menu')).not.toBeInTheDocument();
        });
    });

    describe('delete rows button', () => {
        it('should not render delete rows button when no rows are selected', () => {
            hoisted.storeState.selectedRowsCount = 0;

            renderHeader();

            expect(screen.queryByRole('button', {name: /Delete \(/i})).not.toBeInTheDocument();
        });

        it('should render delete rows button when rows are selected', () => {
            hoisted.storeState.selectedRowsCount = 5;

            renderHeader();

            expect(screen.getByRole('button', {name: /Delete \(5\)/i})).toBeInTheDocument();
        });

        it('should show correct count in delete button', () => {
            hoisted.storeState.selectedRowsCount = 10;

            renderHeader();

            expect(screen.getByText(/Delete \(10\)/i)).toBeInTheDocument();
        });

        it('should call handleOpenDeleteRowsDialog when delete button is clicked', async () => {
            const user = userEvent.setup();
            hoisted.storeState.selectedRowsCount = 3;

            renderHeader();

            const deleteButton = screen.getByRole('button', {name: /Delete \(3\)/i});

            await user.click(deleteButton);

            expect(hoisted.mockHandleOpenDeleteRowsDialog).toHaveBeenCalledTimes(1);
        });
    });

    describe('actions menu handlers', () => {
        it('should open the import CSV dialog when import is clicked', async () => {
            const user = userEvent.setup();

            renderHeader();

            await user.click(screen.getByTestId('import-btn'));

            expect(hoisted.mockHandleOpenImportCsvDialog).toHaveBeenCalledTimes(1);
        });
    });
});

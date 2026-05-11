import {render, resetAll, screen, userEvent, windowResizeObserver} from '@/shared/util/test-utils';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import DataTableDropdownMenu from '../DataTableDropdownMenu';

const hoisted = vi.hoisted(() => {
    return {
        handleDeleteClick: vi.fn(),
        handleDuplicateClick: vi.fn(),
        handleExportCsvClick: vi.fn(),
        handleRenameClick: vi.fn(),
        mockUseDataTableDropdownMenu: vi.fn(),
    };
});

vi.mock('../hooks/useDataTableDropdownMenu', () => ({
    default: hoisted.mockUseDataTableDropdownMenu,
}));

beforeEach(() => {
    windowResizeObserver();
    hoisted.mockUseDataTableDropdownMenu.mockReturnValue({
        handleDeleteClick: hoisted.handleDeleteClick,
        handleDuplicateClick: hoisted.handleDuplicateClick,
        handleExportCsvClick: hoisted.handleExportCsvClick,
        handleRenameClick: hoisted.handleRenameClick,
    });
});

afterEach(() => {
    resetAll();
    vi.clearAllMocks();
});

const openMenu = async () => {
    await userEvent.click(screen.getByRole('button', {name: 'Table menu'}));
};

describe('DataTableDropdownMenu', () => {
    it('should render menu trigger button', () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        expect(screen.getByRole('button', {name: 'Table menu'})).toBeInTheDocument();
    });

    it('should show the table actions without Import CSV by default', async () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        await openMenu();

        expect(screen.getByText('Rename')).toBeInTheDocument();
        expect(screen.getByText('Duplicate')).toBeInTheDocument();
        expect(screen.getByText('Export CSV')).toBeInTheDocument();
        expect(screen.getByText('Delete')).toBeInTheDocument();
        expect(screen.queryByText('Import CSV')).not.toBeInTheDocument();
    });

    it('should separate Delete from the other actions', async () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        await openMenu();

        expect(screen.getByRole('separator')).toBeInTheDocument();
        expect(screen.getByText('Delete').closest('[role="menuitem"]')).toHaveAttribute('data-variant', 'destructive');
    });

    it('should show Import CSV and call onImportCsv when provided', async () => {
        const onImportCsv = vi.fn();

        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" onImportCsv={onImportCsv} />);

        await openMenu();

        await userEvent.click(screen.getByText('Import CSV'));

        expect(onImportCsv).toHaveBeenCalledTimes(1);
    });

    it('should call handleRenameClick when clicking Rename', async () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        await openMenu();

        await userEvent.click(screen.getByText('Rename'));

        expect(hoisted.handleRenameClick).toHaveBeenCalledTimes(1);
    });

    it('should call handleDuplicateClick when clicking Duplicate', async () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        await openMenu();

        await userEvent.click(screen.getByText('Duplicate'));

        expect(hoisted.handleDuplicateClick).toHaveBeenCalledTimes(1);
    });

    it('should call handleExportCsvClick when clicking Export CSV', async () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        await openMenu();

        await userEvent.click(screen.getByText('Export CSV'));

        expect(hoisted.handleExportCsvClick).toHaveBeenCalledTimes(1);
    });

    it('should call handleDeleteClick when clicking Delete', async () => {
        render(<DataTableDropdownMenu baseName="orders" dataTableId="123" />);

        await openMenu();

        await userEvent.click(screen.getByText('Delete'));

        expect(hoisted.handleDeleteClick).toHaveBeenCalledTimes(1);
    });
});

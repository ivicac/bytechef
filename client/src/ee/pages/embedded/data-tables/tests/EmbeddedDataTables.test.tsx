import EmbeddedDataTables from '@/ee/pages/embedded/data-tables/EmbeddedDataTables';
import {render, screen, waitFor} from '@/shared/util/test-utils';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {useDataTablesMock} = vi.hoisted(() => ({
    useDataTablesMock: vi.fn(),
}));

vi.mock('@/shared/components/data-tables/components/hooks/useDataTables', () => ({
    default: useDataTablesMock,
}));

describe('EmbeddedDataTables', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        useDataTablesMock.mockReturnValue({
            allTags: [],
            error: undefined,
            filteredTables: [],
            isLoading: false,
            tables: [{baseName: 'conversations', columns: [], description: 'Chat history', id: '7'}],
            tagId: undefined,
            tagsByTableData: [],
        });
    });

    it('lists the tables in the environment', async () => {
        render(<EmbeddedDataTables />);

        expect(await screen.findByText('conversations')).toBeInTheDocument();
    });

    it('asks the shared hook for an embedded scope', async () => {
        render(<EmbeddedDataTables />);

        await waitFor(() => {
            expect(useDataTablesMock).toHaveBeenCalledWith({type: 'EMBEDDED'});
        });
    });

    it('shows an empty state rather than a blank page when there are no tables', async () => {
        useDataTablesMock.mockReturnValue({
            allTags: [],
            error: undefined,
            filteredTables: [],
            isLoading: false,
            tables: [],
            tagId: undefined,
            tagsByTableData: [],
        });

        render(<EmbeddedDataTables />);

        expect(await screen.findByText('No Data Tables')).toBeInTheDocument();
    });

    it('offers no owner control, because every account sees every table', async () => {
        render(<EmbeddedDataTables />);

        await waitFor(() => {
            expect(screen.getByText('Data Tables')).toBeInTheDocument();
        });

        expect(screen.queryByLabelText('Owner')).not.toBeInTheDocument();
    });
});

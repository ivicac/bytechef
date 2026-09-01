import {DataTableScopeType} from '@/shared/components/data-tables/types';
import {ColumnType} from '@/shared/middleware/graphql';
import {act, renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useCreateDataTableDialog from '../useCreateDataTableDialog';

const hoisted = vi.hoisted(() => {
    return {
        createEmbeddedMutate: vi.fn(),
        createMutate: vi.fn(),
        invalidateQueries: vi.fn(),
    };
});

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useCreateDataTableMutation: vi.fn((options: {onSuccess: () => void}) => ({
            isPending: false,
            mutate: (vars: unknown) => {
                hoisted.createMutate(vars);
                options.onSuccess();
            },
        })),
        useCreateEmbeddedDataTableMutation: vi.fn((options: {onSuccess: () => void}) => ({
            isPending: false,
            mutate: (vars: unknown) => {
                hoisted.createEmbeddedMutate(vars);
                options.onSuccess();
            },
        })),
    };
});

vi.mock('@/shared/queries/automation/datatables.queries', () => ({}));

vi.mock('@tanstack/react-query', () => ({
    useQueryClient: vi.fn(() => ({
        invalidateQueries: hoisted.invalidateQueries,
    })),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: vi.fn(() => 2),
}));

const WORKSPACE_SCOPE: DataTableScopeType = {type: 'WORKSPACE', workspaceId: 1049};
const EMBEDDED_SCOPE: DataTableScopeType = {type: 'EMBEDDED'};

describe('useCreateDataTableDialog', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe('initial state', () => {
        it('returns initial state as closed', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            expect(result.current.open).toBe(false);
            expect(result.current.baseName).toBe('');
            expect(result.current.description).toBe('');
            expect(result.current.columns).toHaveLength(1);
        });

        it('returns canSubmit as false initially', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            expect(result.current.canSubmit).toBe(false);
        });
    });

    describe('open dialog', () => {
        it('opens dialog', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleOpen();
            });

            expect(result.current.open).toBe(true);
        });
    });

    describe('close dialog', () => {
        it('closes dialog', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleOpen();
            });

            act(() => {
                result.current.handleClose();
            });

            expect(result.current.open).toBe(false);
        });
    });

    describe('form state', () => {
        it('updates baseName via handleBaseNameChange', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
            });

            expect(result.current.baseName).toBe('orders');
        });

        it('updates description via handleDescriptionChange', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleDescriptionChange('Order data');
            });

            expect(result.current.description).toBe('Order data');
        });

        it('adds column', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleAddColumn();
            });

            expect(result.current.columns).toHaveLength(2);
        });

        it('removes column', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleAddColumn();
            });

            act(() => {
                result.current.handleRemoveColumn(0);
            });

            expect(result.current.columns).toHaveLength(1);
        });

        it('updates column name', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleColumnNameChange(0, 'product_id');
            });

            expect(result.current.columns[0].name).toBe('product_id');
        });

        it('updates column type', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleColumnTypeChange(0, ColumnType.Number);
            });

            expect(result.current.columns[0].type).toBe(ColumnType.Number);
        });
    });

    describe('canSubmit', () => {
        it('returns true when baseName and column names are provided', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
                result.current.handleColumnNameChange(0, 'order_id');
            });

            expect(result.current.canSubmit).toBe(true);
        });

        it('returns false when baseName is empty', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleColumnNameChange(0, 'order_id');
            });

            expect(result.current.canSubmit).toBe(false);
        });

        it('returns false when column name is empty', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
            });

            expect(result.current.canSubmit).toBe(false);
        });
    });

    describe('handle create', () => {
        it('calls the embedded mutation with the chosen owner, and not the workspace one', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
                result.current.handleColumnNameChange(0, 'order_id');
                result.current.handleOwnerIdChange(42);
            });

            act(() => {
                result.current.handleCreate();
            });

            expect(hoisted.createEmbeddedMutate).toHaveBeenCalledWith({
                input: {
                    columns: [{name: 'order_id', type: ColumnType.String}],
                    description: undefined,
                    environmentId: '2',
                    name: 'orders',
                    ownerId: '42',
                },
            });
            expect(hoisted.createMutate).not.toHaveBeenCalled();
        });

        // `undefined` is what omits the field from the request. A `null` or the string 'undefined' -- which
        // `String(ownerId)` would produce -- both reach the server as a value, and only one of them is rejected there.
        it('sends no owner at all when none was picked', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
                result.current.handleColumnNameChange(0, 'order_id');
            });

            act(() => {
                result.current.handleCreate();
            });

            expect(hoisted.createEmbeddedMutate).toHaveBeenCalledTimes(1);

            const [variables] = hoisted.createEmbeddedMutate.mock.calls[0];

            expect(variables.input.ownerId).toBeUndefined();
        });

        it('clears the owner once the create succeeds', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(EMBEDDED_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
                result.current.handleColumnNameChange(0, 'order_id');
                result.current.handleOwnerIdChange(42);
            });

            expect(result.current.ownerId).toBe(42);

            act(() => {
                result.current.handleCreate();
            });

            expect(result.current.ownerId).toBeUndefined();
        });

        it('calls create mutation with correct data', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleBaseNameChange('orders');
                result.current.handleDescriptionChange('Order data');
                result.current.handleColumnNameChange(0, 'order_id');
                result.current.handleColumnTypeChange(0, ColumnType.Integer);
            });

            act(() => {
                result.current.handleCreate();
            });

            expect(hoisted.createMutate).toHaveBeenCalledWith({
                input: {
                    baseName: 'orders',
                    columns: [{name: 'order_id', type: ColumnType.Integer}],
                    description: 'Order data',
                    environmentId: '2',
                    workspaceId: '1049',
                },
            });
            expect(hoisted.createEmbeddedMutate).not.toHaveBeenCalled();
        });

        it('resets form after successful create', () => {
            const {result} = renderHook(() => useCreateDataTableDialog(WORKSPACE_SCOPE));

            act(() => {
                result.current.handleOpen();
                result.current.handleBaseNameChange('orders');
                result.current.handleColumnNameChange(0, 'order_id');
            });

            act(() => {
                result.current.handleCreate();
            });

            expect(result.current.open).toBe(false);
            expect(result.current.baseName).toBe('');
            expect(result.current.columns).toHaveLength(1);
            expect(result.current.columns[0].name).toBe('');
        });
    });
});

import {WorkflowInput} from '@/shared/middleware/platform/configuration';
import {render, resetAll, screen} from '@/shared/util/test-utils';
import {afterEach, describe, expect, it, vi} from 'vitest';

import WorkflowInputsTable from './WorkflowInputsTable';

const longTestValue =
    'a-very-long-test-value-that-would-otherwise-stretch-the-workflow-inputs-panel-past-its-visible-width';

const workflowInputs = [
    {
        label: 'Long Input',
        name: 'longInput',
        required: false,
        type: 'string',
    },
];

const codeWorkflowInputs = [{label: 'Order ID', name: 'orderId', required: true, type: 'STRING'}] as WorkflowInput[];

const renderTable = () =>
    render(
        <WorkflowInputsTable
            internalOnlyVisible={false}
            openDeleteDialog={vi.fn()}
            openEditDialog={vi.fn()}
            workflowInputs={workflowInputs}
            workflowTestConfigurationInputs={{longInput: longTestValue}}
        />
    );

const renderActionsTable = (codeWorkflow: boolean) =>
    render(
        <WorkflowInputsTable
            codeWorkflow={codeWorkflow}
            internalOnlyVisible={false}
            openDeleteDialog={vi.fn()}
            openEditDialog={vi.fn()}
            workflowInputs={codeWorkflowInputs}
            workflowTestConfigurationInputs={{orderId: 'ORD-1'}}
        />
    );

afterEach(() => {
    resetAll();
});

describe('WorkflowInputsTable', () => {
    it('should truncate a long test value instead of widening the table', () => {
        renderTable();

        const testValueCell = screen.getByText(longTestValue);

        expect(testValueCell).toHaveClass('truncate');

        expect(testValueCell.closest('table')).toHaveClass('table-fixed');
    });

    it('should expose the full test value through the cell title', () => {
        renderTable();

        expect(screen.getByTitle(longTestValue)).toBeInTheDocument();
    });

    it('offers edit and delete for a visually built workflow', () => {
        renderActionsTable(false);

        expect(screen.getByRole('button', {name: 'Edit input'})).toBeInTheDocument();
        expect(screen.getByRole('button', {name: 'Delete input'})).toBeInTheDocument();
    });

    it('drops delete for a code workflow, whose source owns the declaration', () => {
        renderActionsTable(true);

        // Edit stays: it is how a test value is set.
        expect(screen.getByRole('button', {name: 'Edit input'})).toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'Delete input'})).not.toBeInTheDocument();
    });

    it('still shows the test value for a code workflow', () => {
        renderActionsTable(true);

        expect(screen.getByText('ORD-1')).toBeInTheDocument();
    });
});

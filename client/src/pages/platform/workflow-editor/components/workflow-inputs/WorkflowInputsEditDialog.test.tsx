import {WorkflowInputType} from '@/shared/types';
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {useRef} from 'react';
import {useForm} from 'react-hook-form';
import {beforeAll, beforeEach, describe, expect, it, vi} from 'vitest';

import WorkflowInputsEditDialog from './WorkflowInputsEditDialog';

const {useGetComponentDefinitionQueryMock, workflowEditorState} = vi.hoisted(() => ({
    useGetComponentDefinitionQueryMock: vi.fn(),
    workflowEditorState: {codeWorkflow: false},
}));

vi.mock('@/shared/queries/platform/componentDefinitions.queries', () => ({
    useGetComponentDefinitionQuery: () => useGetComponentDefinitionQueryMock(),
}));

vi.mock('@/pages/platform/workflow-editor/providers/workflowEditorProvider', () => ({
    useWorkflowEditor: () => ({
        codeWorkflow: workflowEditorState.codeWorkflow,
        useGetComponentDefinitionsQuery: () => ({
            // googleSheets declares inputs but is NOT used in the workflow, so it must be excluded.
            data: [
                {inputsCount: 1, name: 'googleSheets', version: 1},
                {inputsCount: 1, name: 'slack', version: 2},
            ],
        }),
    }),
}));

const workflow = {
    definition: JSON.stringify({tasks: [{type: 'slack/v2/sendMessage'}], triggers: []}),
    label: 'Test',
    workflowTaskComponentNames: ['slack'],
} as never;

const saveWorkflowInputMock = vi.fn();

const setupUser = () => userEvent.setup({pointerEventsCheck: 0});

const newInputDefaultValues = (name: string): WorkflowInputType => ({
    label: 'Label',
    name,
    required: false,
    testValue: '',
    type: 'string',
});

const Harness = ({defaultValues = {label: '', name: '', required: false}}: {defaultValues?: WorkflowInputType}) => {
    const form = useForm<WorkflowInputType>({defaultValues});
    const nameInputRef = useRef<HTMLInputElement>(null);

    return (
        <WorkflowInputsEditDialog
            closeDialog={() => {}}
            currentInputIndex={-1}
            form={form}
            internalOnlyVisible={true}
            isEditDialogOpen={true}
            nameInputRef={nameInputRef}
            openEditDialog={() => {}}
            saveWorkflowInput={saveWorkflowInputMock}
            workflow={workflow}
        />
    );
};

const EditHarness = ({defaultValues}: {defaultValues: WorkflowInputType}) => {
    const form = useForm<WorkflowInputType>({defaultValues});
    const nameInputRef = useRef<HTMLInputElement>(null);

    return (
        <WorkflowInputsEditDialog
            closeDialog={() => {}}
            currentInputIndex={0}
            form={form}
            internalOnlyVisible={true}
            isEditDialogOpen={true}
            nameInputRef={nameInputRef}
            openEditDialog={() => {}}
            saveWorkflowInput={saveWorkflowInputMock}
            workflow={workflow}
        />
    );
};

const selectOption = async (user: ReturnType<typeof userEvent.setup>, triggerText: string, optionName: string) => {
    await user.click(await screen.findByText(triggerText));

    const listbox = await screen.findByRole('listbox');

    await user.click(within(listbox).getByRole('option', {name: optionName}));
};

describe('WorkflowInputsEditDialog', () => {
    beforeAll(() => {
        // Radix Select relies on pointer-capture APIs that jsdom does not implement.
        Element.prototype.hasPointerCapture = vi.fn(() => false);
        Element.prototype.setPointerCapture = vi.fn();
        Element.prototype.releasePointerCapture = vi.fn();
    });

    beforeEach(() => {
        workflowEditorState.codeWorkflow = false;

        saveWorkflowInputMock.mockClear();
        useGetComponentDefinitionQueryMock.mockReturnValue({
            data: {
                inputs: [
                    // Lone-property group (the `.inputs(property)` form): no group label, label lives on the property.
                    {
                        name: 'channel',
                        properties: [{controlType: 'SELECT', label: 'Channel', name: 'channelId', type: 'STRING'}],
                    },
                    {label: 'Date Range', name: 'dateRange', properties: []},
                ],
            },
        });
    });

    it('renders the create dialog title', () => {
        render(<Harness />);

        expect(screen.getByText('Create a new Input')).toBeInTheDocument();
    });

    it('reveals the Component select and the deployment note when Component property type is chosen', async () => {
        const user = setupUser();

        render(<Harness />);

        await selectOption(user, 'Select input type', 'Component property');

        expect(await screen.findByText('Select component')).toBeInTheDocument();
        expect(screen.getByText('Configured at deployment time.')).toBeInTheDocument();
        expect(screen.getByText('Test Value')).toBeInTheDocument();
    });

    it('offers only components used in the workflow that declare inputs', async () => {
        const user = setupUser();

        render(<Harness />);

        await selectOption(user, 'Select input type', 'Component property');
        await user.click(await screen.findByText('Select component'));

        const listbox = await screen.findByRole('listbox');

        expect(within(listbox).getByRole('option', {name: 'slack'})).toBeInTheDocument();
        expect(within(listbox).queryByRole('option', {name: 'googleSheets'})).not.toBeInTheDocument();
    });

    it('auto-fills name and label and stores the group reference when picking a single-property input', async () => {
        const user = setupUser();

        render(<Harness />);

        await selectOption(user, 'Select input type', 'Component property');
        await selectOption(user, 'Select component', 'slack');
        await selectOption(user, 'Select input', 'Channel');

        expect(screen.getByPlaceholderText('Input name (will be used as a dynamic value key)')).toHaveValue('channel');
        expect(screen.getByPlaceholderText('Input label')).toHaveValue('Channel');

        await user.click(screen.getByRole('button', {name: 'Save'}));

        const savedInput = saveWorkflowInputMock.mock.calls[0][0];

        expect(savedInput.componentReference.componentName).toBe('slack');
        expect(savedInput.componentReference.componentVersion).toBe(2);
        expect(savedInput.componentReference.groupName).toBe('channel');
    });

    it('stores the group reference when picking a compound group', async () => {
        const user = setupUser();

        render(<Harness />);

        await selectOption(user, 'Select input type', 'Component property');
        await selectOption(user, 'Select component', 'slack');
        await selectOption(user, 'Select input', 'Date Range');

        await user.click(screen.getByRole('button', {name: 'Save'}));

        const savedInput = saveWorkflowInputMock.mock.calls[0][0];

        expect(savedInput.componentReference.componentName).toBe('slack');
        expect(savedInput.componentReference.groupName).toBe('dateRange');
    });

    it('offers a Field Mapping input type', async () => {
        const user = setupUser();

        render(<Harness />);

        await user.click(screen.getByText('Select input type'));

        const listbox = await screen.findByRole('listbox');

        expect(within(listbox).getByRole('option', {name: 'Field Mapping'})).toBeInTheDocument();
    });

    it('renders a JSON editor for the field_mapping test value', async () => {
        const user = setupUser();

        render(<Harness />);

        await user.click(screen.getByText('Select input type'));

        const listbox = await screen.findByRole('listbox');

        await user.click(within(listbox).getByRole('option', {name: 'Field Mapping'}));

        expect(screen.getByText('Test Value')).toBeInTheDocument();
        expect(screen.getByTestId('field-mapping-json-editor')).toBeInTheDocument();
    });

    it('renders an Internal only checkbox', () => {
        render(<Harness />);

        expect(screen.getByText('Internal only')).toBeInTheDocument();
        expect(screen.getByRole('checkbox', {name: /internal only/i})).toBeInTheDocument();
    });

    it('shows the Internal only checkbox checked when editing an input with internalOnly: true', () => {
        render(
            <EditHarness
                defaultValues={
                    {
                        internalOnly: true,
                        label: 'API Key',
                        name: 'apiKey',
                        required: false,
                        type: 'string',
                    } as WorkflowInputType
                }
            />
        );

        expect(screen.getByRole('checkbox', {name: /internal only/i})).toBeChecked();
    });

    it('reopens a saved component-referenced input in edit mode with its component selectors visible', () => {
        render(
            <EditHarness
                defaultValues={
                    {
                        componentReference: {componentName: 'slack', componentVersion: 2, groupName: 'channel'},
                        label: 'Channel',
                        name: 'channelId',
                        required: false,
                        type: 'component',
                    } as WorkflowInputType
                }
            />
        );

        expect(screen.getByText('Edit Input')).toBeInTheDocument();
        expect(screen.getByText('Configured at deployment time.')).toBeInTheDocument();
        expect(screen.getByText('Test Value')).toBeInTheDocument();

        const nameInput = screen.getByPlaceholderText('Input name (will be used as a dynamic value key)');

        expect(nameInput).toHaveValue('channelId');
        expect(nameInput).toHaveAttribute('readonly');
    });
    it('should keep the existing test value when an input is opened for editing', async () => {
        render(
            <EditHarness
                defaultValues={{
                    label: 'Long Input',
                    name: 'longInput',
                    required: false,
                    testValue: 'a-very-long-test-value-that-overflows-the-workflow-inputs-panel',
                    type: 'string',
                }}
            />
        );

        expect(await screen.findByLabelText('Test Value')).toHaveValue(
            'a-very-long-test-value-that-overflows-the-workflow-inputs-panel'
        );
    });

    it('should clear the test value when the input type changes', async () => {
        render(
            <EditHarness
                defaultValues={{
                    label: 'Long Input',
                    name: 'longInput',
                    required: false,
                    testValue: '1234',
                    type: 'string',
                }}
            />
        );

        const [typeSelectTrigger] = await screen.findAllByRole('combobox');

        fireEvent.click(typeSelectTrigger);

        await waitFor(() => expect(screen.getByRole('option', {name: 'Number'})).toBeInTheDocument());

        fireEvent.click(screen.getByRole('option', {name: 'Number'}));

        await waitFor(() => expect(screen.getByLabelText('Test Value')).toHaveValue(null));
    });

    it.each(['my input', 'my-input', '1input', 'my.input', 'my@input'])(
        'should reject the name %s, which the expression evaluator cannot resolve',
        async (name) => {
            render(<Harness defaultValues={newInputDefaultValues(name)} />);

            fireEvent.click(await screen.findByRole('button', {name: 'Save'}));

            expect(
                await screen.findByText(
                    'Name must start with a letter or underscore and contain only letters, digits and underscores'
                )
            ).toBeInTheDocument();

            expect(saveWorkflowInputMock).not.toHaveBeenCalled();
        }
    );

    it.each(['myInput', 'my_input', '_leading', 'input1', 'INPUT_2'])('should accept the name %s', async (name) => {
        render(<Harness defaultValues={newInputDefaultValues(name)} />);

        fireEvent.click(await screen.findByRole('button', {name: 'Save'}));

        await waitFor(() => expect(saveWorkflowInputMock).toHaveBeenCalled());
    });

    it('locks a code workflow input declaration but leaves its test value editable', () => {
        workflowEditorState.codeWorkflow = true;

        render(
            <EditHarness
                defaultValues={
                    {label: 'Order ID', name: 'orderId', required: true, type: 'string'} as WorkflowInputType
                }
            />
        );

        // The source owns the declaration, so editing it here would only be undone by the next save.
        expect(screen.getByPlaceholderText('Input name (will be used as a dynamic value key)')).toHaveAttribute(
            'readonly'
        );
        expect(screen.getByPlaceholderText('Input label')).toHaveAttribute('readonly');
        expect(screen.getByRole('checkbox', {name: 'Required'})).toBeDisabled();

        // The test value is NOT source-owned — it is the whole reason to open this dialog for a code workflow.
        expect(screen.getByPlaceholderText('Enter value')).not.toHaveAttribute('readonly');
    });

    it('says where a code workflow input comes from', () => {
        workflowEditorState.codeWorkflow = true;

        render(
            <EditHarness
                defaultValues={
                    {label: 'Order ID', name: 'orderId', required: false, type: 'string'} as WorkflowInputType
                }
            />
        );

        expect(screen.getByText(/declared in the workflow's source/i)).toBeInTheDocument();
    });

    it('leaves the declaration editable for a visually built workflow', () => {
        render(
            <EditHarness
                defaultValues={
                    {label: 'Order ID', name: 'orderId', required: false, type: 'string'} as WorkflowInputType
                }
            />
        );

        expect(screen.getByPlaceholderText('Input label')).not.toHaveAttribute('readonly');
        expect(screen.getByRole('checkbox', {name: 'Required'})).not.toBeDisabled();
    });
});

import {fireEvent, render, resetAll, screen, userEvent, windowResizeObserver} from '@/shared/util/test-utils';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const {generateSchemaFromSampleMock} = vi.hoisted(() => ({
    generateSchemaFromSampleMock: vi.fn(),
}));

vi.mock('./utils/generateSchemaFromSample', () => ({
    generateSchemaFromSample: generateSchemaFromSampleMock,
}));

vi.mock('@/shared/components/MonacoEditorWrapper', () => ({
    default: ({onChange, value}: {onChange: (value: string | undefined) => void; value: string}) => (
        <textarea data-testid="mock-monaco-editor" onChange={(event) => onChange(event.target.value)} value={value} />
    ),
}));

import PropertyJsonSchemaBuilderSampleDataTab from './PropertyJsonSchemaBuilderSampleDataTab';

const generatedSchema = {
    properties: {name: {type: 'string'}},
    type: 'object',
};

const mockOnGenerate = vi.fn();

beforeEach(() => {
    windowResizeObserver();
});

afterEach(() => {
    resetAll();
    vi.clearAllMocks();
});

const renderTab = (schema?: Record<string, unknown>) =>
    render(<PropertyJsonSchemaBuilderSampleDataTab onGenerate={mockOnGenerate} schema={schema} />);

const typeSample = async (sample: string) => {
    const editor = await screen.findByTestId('mock-monaco-editor');

    fireEvent.change(editor, {target: {value: sample}});
};

describe('PropertyJsonSchemaBuilderSampleDataTab', () => {
    it('disables Generate until the sample parses as JSON', async () => {
        renderTab();

        expect(screen.getByRole('button', {name: /generate/i})).toBeDisabled();

        await typeSample('{not json');

        expect(screen.getByRole('button', {name: /generate/i})).toBeDisabled();

        await typeSample('{"name": "Ana"}');

        expect(screen.getByRole('button', {name: /generate/i})).toBeEnabled();
    });

    it('hands the generated schema to onGenerate', async () => {
        const user = userEvent.setup();

        generateSchemaFromSampleMock.mockResolvedValue(generatedSchema);

        renderTab();

        await typeSample('{"name": "Ana"}');

        await user.click(screen.getByRole('button', {name: /generate/i}));

        expect(generateSchemaFromSampleMock).toHaveBeenCalledWith('{"name": "Ana"}');

        expect(mockOnGenerate).toHaveBeenCalledWith(generatedSchema);
    });

    it('shows an error and keeps the schema when generation fails', async () => {
        const user = userEvent.setup();

        generateSchemaFromSampleMock.mockRejectedValue(new Error('boom'));

        renderTab();

        await typeSample('{"name": "Ana"}');

        await user.click(screen.getByRole('button', {name: /generate/i}));

        expect(await screen.findByRole('alert')).toHaveTextContent(/could not generate/i);

        expect(mockOnGenerate).not.toHaveBeenCalled();
    });

    it('warns that generating replaces a schema that already has properties', () => {
        renderTab({properties: {existing: {type: 'string'}}, type: 'object'});

        expect(screen.getByText(/replace your current schema/i)).toBeInTheDocument();
    });

    it('does not warn when the current schema has no properties', () => {
        renderTab({properties: {}, type: 'object'});

        expect(screen.queryByText(/replace your current schema/i)).not.toBeInTheDocument();
    });
});

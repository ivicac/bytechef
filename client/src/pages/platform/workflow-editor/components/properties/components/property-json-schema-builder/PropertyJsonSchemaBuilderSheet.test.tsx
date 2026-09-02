import {i18n} from '@lingui/core';
import {I18nProvider} from '@lingui/react';
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {ReactNode} from 'react';
import {describe, expect, it, vi} from 'vitest';

const {generateSchemaFromSampleMock, handleCopilotOpen} = vi.hoisted(() => ({
    generateSchemaFromSampleMock: vi.fn(),
    handleCopilotOpen: vi.fn(),
}));

vi.mock('./hooks/usePropertyJsonSchemaBuilderCopilot', () => ({
    usePropertyJsonSchemaBuilderCopilot: () => ({
        copilotPanelOpen: false,
        handleCopilotClose: vi.fn(),
        handleCopilotOpen,
    }),
}));
vi.mock('./utils/generateSchemaFromSample', () => ({
    generateSchemaFromSample: generateSchemaFromSampleMock,
}));
vi.mock('@/shared/components/copilot/CopilotPanel', () => ({default: () => <div data-testid="copilot-panel" />}));
vi.mock('@/shared/components/MonacoEditorWrapper', () => ({
    default: ({onChange, value}: {onChange: (value: string | undefined) => void; value: string}) => (
        <textarea data-testid="mock-monaco-editor" onChange={(event) => onChange(event.target.value)} value={value} />
    ),
}));
vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: (selector: (s: unknown) => unknown) => selector({ai: {copilot: {enabled: true}}}),
}));
vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({useFeatureFlagsStore: () => () => true}));
vi.mock('../property-copilot/useGeneratePropertyValue', () => ({
    useGeneratePropertyValue: () => ({generate: vi.fn(), isPending: false}),
}));
vi.mock('@/components/JsonSchemaBuilder/JsonSchemaBuilder', () => ({
    default: () => <div data-testid="json-schema-builder" />,
}));

import PropertyJsonSchemaBuilderSheet from './PropertyJsonSchemaBuilderSheet';

i18n.load('en', {});
i18n.activate('en');

const wrapper = ({children}: {children: ReactNode}) => <I18nProvider i18n={i18n}>{children}</I18nProvider>;

const generatedSchema = {
    properties: {name: {type: 'string'}},
    type: 'object',
};

describe('PropertyJsonSchemaBuilderSheet copilot toggle', () => {
    it('opens the copilot when the toggle is clicked', async () => {
        const user = userEvent.setup();

        render(
            <PropertyJsonSchemaBuilderSheet
                environmentId={1}
                propertyPath="output"
                title="Response Schema"
                workflowId="w1"
                workflowNodeName="node1"
            />,
            {wrapper}
        );

        await user.click(screen.getByRole('button', {name: /copilot/i}));

        expect(handleCopilotOpen).toHaveBeenCalled();
    });
});

describe('PropertyJsonSchemaBuilderSheet sample generation', () => {
    it('offers a From Sample button', () => {
        render(<PropertyJsonSchemaBuilderSheet title="Response Schema" />, {wrapper});

        expect(screen.getByRole('button', {name: /from sample/i})).toBeInTheDocument();
    });

    it('applies the generated schema and closes the dialog', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();

        generateSchemaFromSampleMock.mockResolvedValue(generatedSchema);

        render(<PropertyJsonSchemaBuilderSheet onChange={onChange} title="Response Schema" />, {wrapper});

        await user.click(screen.getByRole('button', {name: /from sample/i}));

        const dialog = await screen.findByRole('dialog', {name: /generate schema from sample/i});

        fireEvent.change(await within(dialog).findByTestId('mock-monaco-editor'), {
            target: {value: '{"name": "Ana"}'},
        });

        await user.click(within(dialog).getByRole('button', {name: /generate/i}));

        await waitFor(() => expect(onChange).toHaveBeenCalledWith(generatedSchema));

        await waitFor(() =>
            expect(screen.queryByRole('dialog', {name: /generate schema from sample/i})).not.toBeInTheDocument()
        );
    });
});

describe('PropertyJsonSchemaBuilderSheet note', () => {
    it('hides the response schema note once it is dismissed', async () => {
        const user = userEvent.setup();

        render(<PropertyJsonSchemaBuilderSheet title="Response Schema" />, {wrapper});

        const note = screen.getByText(/essentially a template for its output/i);

        await user.click(screen.getByRole('button', {name: /dismiss/i}));

        expect(note).not.toBeInTheDocument();
    });
});

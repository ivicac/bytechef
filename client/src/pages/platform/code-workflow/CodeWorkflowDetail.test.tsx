import {CodeWorkflowLanguage} from '@/shared/middleware/graphql';
import {fireEvent, render, resetAll, screen} from '@/shared/util/test-utils';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import CodeWorkflowDetail from './CodeWorkflowDetail';

const hoisted = vi.hoisted(() => ({
    mockUseCodeWorkflowSourceQuery: vi.fn(),
    mockUseUpdateCodeWorkflowSourceMutation: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', async () => {
    const actual = await vi.importActual<Record<string, unknown>>('@/shared/middleware/graphql');

    return {
        ...actual,
        useCodeWorkflowSourceQuery: hoisted.mockUseCodeWorkflowSourceQuery,
        useUpdateCodeWorkflowSourceMutation: hoisted.mockUseUpdateCodeWorkflowSourceMutation,
    };
});

vi.mock('@/shared/components/MonacoEditorWrapper', () => ({
    default: ({
        defaultLanguage,
        onChange,
        value,
    }: {
        defaultLanguage: string;
        onChange: (value: string | undefined) => void;
        value: string;
    }) => (
        <textarea
            data-language={defaultLanguage}
            data-testid="monaco-editor-mock"
            onChange={(event) => onChange(event.target.value)}
            value={value}
        />
    ),
}));

beforeEach(() => {
    hoisted.mockUseCodeWorkflowSourceQuery.mockReturnValue({
        data: {codeWorkflowSource: 'console.log("hi");'},
        error: null,
        isLoading: false,
    });
    hoisted.mockUseUpdateCodeWorkflowSourceMutation.mockReturnValue({isPending: false, mutate: vi.fn()});
});

afterEach(() => {
    resetAll();
    vi.clearAllMocks();
});

describe('CodeWorkflowDetail', () => {
    it('renders the Monaco editor with the fetched source for the given language', async () => {
        render(<CodeWorkflowDetail language={CodeWorkflowLanguage.Javascript} projectId="1" />);

        const editor = await screen.findByTestId('monaco-editor-mock');

        expect(editor).toHaveAttribute('data-language', 'javascript');
        expect(editor).toHaveValue('console.log("hi");');
        expect(hoisted.mockUseCodeWorkflowSourceQuery).toHaveBeenCalledWith({projectId: '1'}, {enabled: true});
    });

    it('maps Python and Ruby languages to their Monaco equivalents', async () => {
        const {rerender} = render(<CodeWorkflowDetail language={CodeWorkflowLanguage.Python} projectId="1" />);

        expect(await screen.findByTestId('monaco-editor-mock')).toHaveAttribute('data-language', 'python');

        rerender(<CodeWorkflowDetail language={CodeWorkflowLanguage.Ruby} projectId="1" />);

        expect(await screen.findByTestId('monaco-editor-mock')).toHaveAttribute('data-language', 'ruby');
    });

    it('disables Save until the source is edited, and tracks dirty state', async () => {
        render(<CodeWorkflowDetail language={CodeWorkflowLanguage.Javascript} projectId="1" />);

        const saveButton = screen.getByRole('button', {name: 'Save'});

        expect(saveButton).toBeDisabled();

        const editor = await screen.findByTestId('monaco-editor-mock');

        fireEvent.change(editor, {target: {value: 'console.log("changed");'}});

        expect(saveButton).not.toBeDisabled();
    });

    it('calls the update mutation with the project id and edited content on Save', async () => {
        const mutateMock = vi.fn();

        hoisted.mockUseUpdateCodeWorkflowSourceMutation.mockReturnValue({isPending: false, mutate: mutateMock});

        render(<CodeWorkflowDetail language={CodeWorkflowLanguage.Javascript} projectId="1" />);

        const editor = await screen.findByTestId('monaco-editor-mock');

        fireEvent.change(editor, {target: {value: 'console.log("changed");'}});

        const saveButton = screen.getByRole('button', {name: 'Save'});

        fireEvent.click(saveButton);

        expect(mutateMock).toHaveBeenCalledWith({content: 'console.log("changed");', projectId: '1'});
    });
});

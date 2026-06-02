import {PropertyCopilotMode} from '@/shared/middleware/graphql-types';
import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import PropertyCopilotButton from './PropertyCopilotButton';

const generateMock = vi.fn();
const copilotEnabledMock = vi.fn();
const featureFlagMock = vi.fn();

vi.mock('./useGeneratePropertyValue', () => ({
    useGeneratePropertyValue: () => ({generate: generateMock, isPending: false}),
}));

vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: (selector: (state: unknown) => unknown) =>
        selector({ai: {copilot: {enabled: copilotEnabledMock()}}}),
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => featureFlagMock,
}));

describe('PropertyCopilotButton', () => {
    beforeEach(() => {
        generateMock.mockReset();
        copilotEnabledMock.mockReset().mockReturnValue(true);
        featureFlagMock.mockReset().mockReturnValue(true);
    });

    const baseProps = {
        environmentId: 0,
        getHasValue: () => false,
        mode: PropertyCopilotMode.Text,
        onApply: vi.fn(),
        propertyPath: 'p',
        propertyType: 'STRING',
        workflowId: 'wf1',
        workflowNodeName: 'n1',
    };

    it('is hidden when copilot is disabled', () => {
        copilotEnabledMock.mockReturnValue(false);

        render(<PropertyCopilotButton {...baseProps} />);

        expect(screen.queryByLabelText(/copilot/i)).not.toBeInTheDocument();
    });

    it('is hidden when feature flag is disabled', () => {
        featureFlagMock.mockReturnValue(false);

        render(<PropertyCopilotButton {...baseProps} />);

        expect(screen.queryByLabelText(/copilot/i)).not.toBeInTheDocument();
    });

    it('previews the generated value and applies it on Insert', async () => {
        generateMock.mockResolvedValue({message: null, valid: true, value: 'Hi ${n1.name}'});
        const onApply = vi.fn();

        render(<PropertyCopilotButton {...baseProps} onApply={onApply} />);

        fireEvent.click(screen.getByLabelText(/copilot/i));
        fireEvent.change(screen.getByPlaceholderText(/describe/i), {target: {value: 'greet'}});
        fireEvent.click(screen.getByRole('button', {name: /generate/i}));

        await waitFor(() => expect(screen.getByText('Hi ${n1.name}')).toBeInTheDocument());
        expect(generateMock).toHaveBeenCalledWith(
            expect.objectContaining({mode: PropertyCopilotMode.Text, prompt: 'greet'})
        );
        expect(onApply).not.toHaveBeenCalled();

        fireEvent.click(screen.getByRole('button', {name: /insert/i}));

        expect(onApply).toHaveBeenCalledWith('Hi ${n1.name}');
    });

    it('offers Replace when the field already has a value', async () => {
        generateMock.mockResolvedValue({message: null, valid: true, value: 'new value'});

        render(<PropertyCopilotButton {...baseProps} getHasValue={() => true} />);

        fireEvent.click(screen.getByLabelText(/copilot/i));
        fireEvent.change(screen.getByPlaceholderText(/describe/i), {target: {value: 'greet'}});
        fireEvent.click(screen.getByRole('button', {name: /generate/i}));

        await waitFor(() => expect(screen.getByRole('button', {name: /replace/i})).toBeInTheDocument());
    });
});

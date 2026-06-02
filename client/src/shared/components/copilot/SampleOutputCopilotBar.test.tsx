import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import SampleOutputCopilotBar from './SampleOutputCopilotBar';

const {generateMock} = vi.hoisted(() => ({generateMock: vi.fn()}));

vi.mock('./useGenerateSampleOutput', () => ({
    useGenerateSampleOutput: () => ({generate: generateMock, isPending: false}),
}));

vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: (selector: (state: unknown) => unknown) => selector({ai: {copilot: {enabled: true}}}),
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => () => true,
}));

describe('SampleOutputCopilotBar', () => {
    beforeEach(() => {
        generateMock.mockReset();
    });

    it('generates a sample and applies the raw JSON string', async () => {
        const onApply = vi.fn();

        generateMock.mockResolvedValue({message: null, valid: true, value: '{"id":1}'});

        render(
            <SampleOutputCopilotBar currentEditorIsEmpty={true} environmentId={1} onApply={onApply} workflowId="wf1" />
        );

        fireEvent.change(screen.getByPlaceholderText(/describe/i), {target: {value: 'an order'}});
        fireEvent.click(screen.getByRole('button', {name: /generate/i}));

        await vi.waitFor(() => expect(onApply).toHaveBeenCalledWith('{"id":1}'));
    });

    it('renders nothing when workflowId is undefined', () => {
        const {container} = render(
            <SampleOutputCopilotBar
                currentEditorIsEmpty={true}
                environmentId={1}
                onApply={vi.fn()}
                workflowId={undefined}
            />
        );

        expect(container).toBeEmptyDOMElement();
    });
});

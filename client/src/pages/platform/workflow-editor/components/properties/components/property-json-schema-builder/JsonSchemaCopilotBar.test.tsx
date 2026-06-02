import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import JsonSchemaCopilotBar from './JsonSchemaCopilotBar';

const {generateMock} = vi.hoisted(() => ({generateMock: vi.fn()}));

vi.mock('../property-copilot/useGeneratePropertyValue', () => ({
    useGeneratePropertyValue: () => ({generate: generateMock, isPending: false}),
}));

vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: (selector: (state: unknown) => unknown) => selector({ai: {copilot: {enabled: true}}}),
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => () => true,
}));

describe('JsonSchemaCopilotBar', () => {
    beforeEach(() => {
        generateMock.mockReset();
    });

    it('generates a schema and applies the parsed object', async () => {
        const onApply = vi.fn();

        generateMock.mockResolvedValue({message: null, valid: true, value: '{"type":"object"}'});

        render(
            <JsonSchemaCopilotBar
                currentSchemaIsEmpty={true}
                environmentId={1}
                onApply={onApply}
                propertyPath="responseSchema"
                workflowId="wf1"
                workflowNodeName="node1"
            />
        );

        fireEvent.change(screen.getByPlaceholderText(/describe/i), {target: {value: 'an order'}});
        fireEvent.click(screen.getByRole('button', {name: /generate/i}));

        await vi.waitFor(() => expect(onApply).toHaveBeenCalledWith({type: 'object'}));
    });

    it('renders nothing when copilot context is incomplete', () => {
        const {container} = render(
            <JsonSchemaCopilotBar
                currentSchemaIsEmpty={true}
                environmentId={1}
                onApply={vi.fn()}
                propertyPath="responseSchema"
                workflowId={undefined}
                workflowNodeName="node1"
            />
        );

        expect(container).toBeEmptyDOMElement();
    });
});

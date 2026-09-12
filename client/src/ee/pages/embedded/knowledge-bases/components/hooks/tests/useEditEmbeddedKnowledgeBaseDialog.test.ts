import {act, renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useEditEmbeddedKnowledgeBaseDialog from '../useEditEmbeddedKnowledgeBaseDialog';

const hoisted = vi.hoisted(() => {
    return {
        invalidateQueries: vi.fn(),
        mutate: vi.fn(),
        toast: vi.fn(),
    };
});

vi.mock('sonner', () => ({
    toast: hoisted.toast,
}));

vi.mock('@tanstack/react-query', () => ({
    useQueryClient: vi.fn(() => ({
        invalidateQueries: hoisted.invalidateQueries,
    })),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useUpdateEmbeddedKnowledgeBaseMutation: vi.fn((options: {onSuccess: () => void}) => ({
        isPending: false,
        mutate: (variables: unknown) => {
            hoisted.mutate(variables);

            options.onSuccess();
        },
    })),
}));

const mockKnowledgeBase = {
    description: 'Test description',
    id: 'kb-1',
    maxChunkSize: 1024,
    minChunkSizeChars: 100,
    name: 'Test KB',
    overlap: 200,
};

describe('useEditEmbeddedKnowledgeBaseDialog', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('seeds the chunking settings from the knowledge base', () => {
        const {result} = renderHook(() => useEditEmbeddedKnowledgeBaseDialog({knowledgeBase: mockKnowledgeBase}));

        expect(result.current.maxChunkSize).toBe('1024');
        expect(result.current.minChunkSizeChars).toBe('100');
        expect(result.current.overlapSize).toBe('200');
    });

    it('sends the edited chunking settings', () => {
        const {result} = renderHook(() => useEditEmbeddedKnowledgeBaseDialog({knowledgeBase: mockKnowledgeBase}));

        act(() => {
            result.current.setMaxChunkSize('512');
            result.current.setMinChunkSizeChars('40');
            result.current.setOverlapSize('64');
        });

        act(() => {
            result.current.handleSave();
        });

        expect(hoisted.mutate).toHaveBeenCalledWith({
            input: {
                description: 'Test description',
                knowledgeBaseId: 'kb-1',
                maxChunkSize: 512,
                minChunkSizeChars: 40,
                name: 'Test KB',
                overlap: 64,
            },
        });
    });

    // The list reads from the `EmbeddedKnowledgeBases` query, not the automation `knowledgeBases` one, so invalidating
    // the wrong key leaves the row showing the chunking it had before the save.
    it('invalidates the embedded listing once the save succeeds', () => {
        const {result} = renderHook(() => useEditEmbeddedKnowledgeBaseDialog({knowledgeBase: mockKnowledgeBase}));

        act(() => {
            result.current.handleSave();
        });

        expect(hoisted.invalidateQueries).toHaveBeenCalledWith({queryKey: ['EmbeddedKnowledgeBases']});
    });

    // `parseInt('')` is NaN, which serializes onto the wire as a value rather than as an omission -- and clearing a
    // number box to retype it is a normal thing to do mid-edit.
    it('omits a cleared chunking box rather than sending NaN', () => {
        const {result} = renderHook(() => useEditEmbeddedKnowledgeBaseDialog({knowledgeBase: mockKnowledgeBase}));

        act(() => {
            result.current.setMaxChunkSize('');
        });

        act(() => {
            result.current.handleSave();
        });

        const [variables] = hoisted.mutate.mock.calls[0];

        expect(variables.input.maxChunkSize).toBeUndefined();
        expect(variables.input.overlap).toBe(200);
    });

    it('refuses to submit an empty name', () => {
        const {result} = renderHook(() => useEditEmbeddedKnowledgeBaseDialog({knowledgeBase: mockKnowledgeBase}));

        act(() => {
            result.current.setName('   ');
        });

        expect(result.current.canSubmit).toBe(false);
    });
});

import {renderHook} from '@testing-library/react';
import {afterEach, describe, expect, it, vi} from 'vitest';

import useKnowledgeBaseListItemRechunkDialog from '../useKnowledgeBaseListItemRechunkDialog';

const {invalidateQueriesMock, mutateMock, toastMock, useRechunkKnowledgeBaseMutationMock} = vi.hoisted(() => ({
    invalidateQueriesMock: vi.fn(),
    mutateMock: vi.fn(),
    toastMock: vi.fn(),
    useRechunkKnowledgeBaseMutationMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useRechunkKnowledgeBaseMutation: useRechunkKnowledgeBaseMutationMock,
}));

vi.mock('@tanstack/react-query', () => ({
    useQueryClient: () => ({invalidateQueries: invalidateQueriesMock}),
}));

vi.mock('sonner', () => ({
    toast: toastMock,
}));

useRechunkKnowledgeBaseMutationMock.mockImplementation(() => ({isPending: false, mutate: mutateMock}));

afterEach(() => {
    vi.clearAllMocks();
});

const onCloseMock = vi.fn();

describe('useKnowledgeBaseListItemRechunkDialog', () => {
    it('re-chunks the knowledge base it was given', () => {
        const {result} = renderHook(() =>
            useKnowledgeBaseListItemRechunkDialog({knowledgeBaseId: 'kb-1', onClose: onCloseMock})
        );

        result.current.handleRechunkClick();

        expect(mutateMock).toHaveBeenCalledWith({id: 'kb-1'});
        expect(onCloseMock).toHaveBeenCalled();
    });

    it('does not re-chunk anything when the confirmation is cancelled', () => {
        const {result} = renderHook(() =>
            useKnowledgeBaseListItemRechunkDialog({knowledgeBaseId: 'kb-1', onClose: onCloseMock})
        );

        result.current.handleCancelClick();

        expect(mutateMock).not.toHaveBeenCalled();
        expect(onCloseMock).toHaveBeenCalled();
    });

    it('reports how many documents were queued and refreshes the list', () => {
        renderHook(() => useKnowledgeBaseListItemRechunkDialog({knowledgeBaseId: 'kb-1', onClose: onCloseMock}));

        const [{onSuccess}] = useRechunkKnowledgeBaseMutationMock.mock.calls.at(-1) as [
            {onSuccess: (data: {rechunkKnowledgeBase: number}) => void},
        ];

        onSuccess({rechunkKnowledgeBase: 3});

        expect(invalidateQueriesMock).toHaveBeenCalledWith({queryKey: ['knowledgeBases']});
        expect(toastMock).toHaveBeenCalledWith('Re-chunking 3 document(s). Search results update as they finish.');
    });
});

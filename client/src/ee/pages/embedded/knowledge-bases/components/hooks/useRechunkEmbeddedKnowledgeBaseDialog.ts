import {useRechunkEmbeddedKnowledgeBaseMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {useState} from 'react';
import {toast} from 'sonner';

interface UseRechunkEmbeddedKnowledgeBaseDialogProps {
    knowledgeBaseId: string;
}

export default function useRechunkEmbeddedKnowledgeBaseDialog({
    knowledgeBaseId,
}: UseRechunkEmbeddedKnowledgeBaseDialogProps) {
    const [open, setOpen] = useState(false);

    const queryClient = useQueryClient();

    const rechunkEmbeddedKnowledgeBaseMutation = useRechunkEmbeddedKnowledgeBaseMutation({
        onSuccess: (data) => {
            queryClient.invalidateQueries({queryKey: ['embeddedKnowledgeBases']});

            toast(
                `Re-chunking ${data.rechunkEmbeddedKnowledgeBase} document(s). Search results update as they finish.`
            );
        },
    });

    const handleCancel = () => {
        setOpen(false);
    };

    const handleOpenChange = (nextOpen: boolean) => {
        setOpen(nextOpen);
    };

    const handleRechunk = () => {
        rechunkEmbeddedKnowledgeBaseMutation.mutate({knowledgeBaseId});

        setOpen(false);
    };

    return {
        handleCancel,
        handleOpenChange,
        handleRechunk,
        isPending: rechunkEmbeddedKnowledgeBaseMutation.isPending,
        open,
    };
}

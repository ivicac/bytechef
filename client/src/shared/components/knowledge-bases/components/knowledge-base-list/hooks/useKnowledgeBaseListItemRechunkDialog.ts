import {useRechunkKnowledgeBaseMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {toast} from 'sonner';

interface UseKnowledgeBaseListItemRechunkDialogProps {
    knowledgeBaseId: string;
    onClose: () => void;
}

export default function useKnowledgeBaseListItemRechunkDialog({
    knowledgeBaseId,
    onClose,
}: UseKnowledgeBaseListItemRechunkDialogProps) {
    const queryClient = useQueryClient();

    const rechunkKnowledgeBaseMutation = useRechunkKnowledgeBaseMutation({
        onSuccess: (data) => {
            queryClient.invalidateQueries({queryKey: ['knowledgeBases']});

            toast(`Re-chunking ${data.rechunkKnowledgeBase} document(s). Search results update as they finish.`);
        },
    });

    const handleCancelClick = () => {
        onClose();
    };

    const handleRechunkClick = () => {
        rechunkKnowledgeBaseMutation.mutate({id: knowledgeBaseId});

        onClose();
    };

    return {
        handleCancelClick,
        handleRechunkClick,
        isRechunking: rechunkKnowledgeBaseMutation.isPending,
    };
}

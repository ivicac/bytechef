import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import useKnowledgeBaseListItemRechunkDialog from '@/shared/components/knowledge-bases/components/knowledge-base-list/hooks/useKnowledgeBaseListItemRechunkDialog';

interface KnowledgeBaseListItemRechunkDialogProps {
    knowledgeBaseId: string;
    onClose: () => void;
    open: boolean;
}

const KnowledgeBaseListItemRechunkDialog = ({
    knowledgeBaseId,
    onClose,
    open,
}: KnowledgeBaseListItemRechunkDialogProps) => {
    const {handleCancelClick, handleRechunkClick} = useKnowledgeBaseListItemRechunkDialog({
        knowledgeBaseId,
        onClose,
    });

    return (
        <AlertDialog open={open}>
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>Re-chunk every document?</AlertDialogTitle>

                    <AlertDialogDescription>
                        Every document in this knowledge base is split again under its current chunking settings and
                        re-embedded, which takes time and costs embedding calls. A document returns no search results
                        between losing its old chunks and finishing its new ones.
                    </AlertDialogDescription>
                </AlertDialogHeader>

                <AlertDialogFooter>
                    <AlertDialogCancel className="shadow-none" onClick={handleCancelClick}>
                        Cancel
                    </AlertDialogCancel>

                    <AlertDialogAction className="shadow-none" onClick={handleRechunkClick}>
                        Re-chunk
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
};

export default KnowledgeBaseListItemRechunkDialog;

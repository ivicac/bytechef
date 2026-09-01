import Button from '@/components/Button/Button';
import {
    Dialog,
    DialogCloseButton,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog';
import useRechunkEmbeddedKnowledgeBaseDialog from '@/ee/pages/embedded/knowledge-bases/components/hooks/useRechunkEmbeddedKnowledgeBaseDialog';
import {RefreshCwIcon} from 'lucide-react';

interface RechunkEmbeddedKnowledgeBaseDialogProps {
    knowledgeBaseId: string;
}

const RechunkEmbeddedKnowledgeBaseDialog = ({knowledgeBaseId}: RechunkEmbeddedKnowledgeBaseDialogProps) => {
    const {handleCancel, handleOpenChange, handleRechunk, isPending, open} = useRechunkEmbeddedKnowledgeBaseDialog({
        knowledgeBaseId,
    });

    return (
        <Dialog onOpenChange={handleOpenChange} open={open}>
            <DialogTrigger asChild>
                <Button aria-label="Re-chunk documents" icon={<RefreshCwIcon />} size="icon" variant="ghost" />
            </DialogTrigger>

            <DialogContent className="sm:max-w-[500px]">
                <DialogHeader className="flex flex-row items-center justify-between space-y-0">
                    <div className="flex flex-col space-y-1">
                        <DialogTitle>Re-chunk every document?</DialogTitle>

                        <DialogDescription>
                            Every document in this knowledge base is split again under its current chunking settings and
                            re-embedded, which takes time and costs embedding calls. A document returns no search
                            results between losing its old chunks and finishing its new ones.
                        </DialogDescription>
                    </div>

                    <DialogCloseButton />
                </DialogHeader>

                <DialogFooter>
                    <Button onClick={handleCancel} variant="ghost">
                        Cancel
                    </Button>

                    <Button disabled={isPending} onClick={handleRechunk}>
                        {isPending ? 'Starting...' : 'Re-chunk'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default RechunkEmbeddedKnowledgeBaseDialog;

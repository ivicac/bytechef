import Button from '@/components/Button/Button';
import {Input} from '@/components/Input/Input';
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
import {Label} from '@/components/ui/label';
import {Textarea} from '@/components/ui/textarea';
import useEditEmbeddedKnowledgeBaseDialog, {
    EditableEmbeddedKnowledgeBaseI,
} from '@/ee/pages/embedded/knowledge-bases/components/hooks/useEditEmbeddedKnowledgeBaseDialog';
import {EditIcon} from 'lucide-react';

interface EditEmbeddedKnowledgeBaseDialogProps {
    knowledgeBase: EditableEmbeddedKnowledgeBaseI;
}

const EditEmbeddedKnowledgeBaseDialog = ({knowledgeBase}: EditEmbeddedKnowledgeBaseDialogProps) => {
    const {
        canSubmit,
        description,
        handleCancel,
        handleOpenChange,
        handleSave,
        isPending,
        maxChunkSize,
        minChunkSizeChars,
        name,
        open,
        overlapSize,
        setDescription,
        setMaxChunkSize,
        setMinChunkSizeChars,
        setName,
        setOverlapSize,
    } = useEditEmbeddedKnowledgeBaseDialog({knowledgeBase});

    return (
        <Dialog onOpenChange={handleOpenChange} open={open}>
            <DialogTrigger asChild>
                <Button aria-label="Edit knowledge base" icon={<EditIcon />} size="icon" variant="ghost" />
            </DialogTrigger>

            <DialogContent className="sm:max-w-[500px]">
                <DialogHeader className="flex flex-row items-center justify-between space-y-0">
                    <div className="flex flex-col space-y-1">
                        <DialogTitle>Edit Knowledge Base</DialogTitle>

                        <DialogDescription>
                            Chunking governs documents ingested from now on. Documents already embedded keep the chunks
                            they were split into until you re-chunk the knowledge base.
                        </DialogDescription>
                    </div>

                    <DialogCloseButton />
                </DialogHeader>

                <fieldset className="space-y-4 border-0 py-4">
                    <div className="space-y-2">
                        <Label htmlFor="embedded-kb-name">Name</Label>

                        <Input
                            id="embedded-kb-name"
                            onChange={(event) => setName(event.target.value)}
                            placeholder="Knowledge base name"
                            value={name}
                        />
                    </div>

                    <div className="space-y-2">
                        <Label htmlFor="embedded-kb-description">Description</Label>

                        <Textarea
                            id="embedded-kb-description"
                            onChange={(event) => setDescription(event.target.value)}
                            placeholder="Describe this knowledge base (optional)"
                            rows={3}
                            value={description}
                        />
                    </div>

                    <div className="grid grid-cols-2 gap-4">
                        <div className="space-y-2">
                            <Label htmlFor="embedded-kb-min-chunk-size">Min Chunk Size (characters)</Label>

                            <Input
                                id="embedded-kb-min-chunk-size"
                                onChange={(event) => setMinChunkSizeChars(event.target.value)}
                                type="number"
                                value={minChunkSizeChars}
                            />
                        </div>

                        <div className="space-y-2">
                            <Label htmlFor="embedded-kb-max-chunk-size">Max Chunk Size (tokens)</Label>

                            <Input
                                id="embedded-kb-max-chunk-size"
                                onChange={(event) => setMaxChunkSize(event.target.value)}
                                type="number"
                                value={maxChunkSize}
                            />
                        </div>
                    </div>

                    <div className="space-y-2">
                        <Label htmlFor="embedded-kb-overlap">Overlap Size (tokens)</Label>

                        <Input
                            id="embedded-kb-overlap"
                            onChange={(event) => setOverlapSize(event.target.value)}
                            type="number"
                            value={overlapSize}
                        />
                    </div>
                </fieldset>

                <DialogFooter>
                    <Button onClick={handleCancel} variant="ghost">
                        Cancel
                    </Button>

                    <Button disabled={!canSubmit || isPending} onClick={handleSave}>
                        {isPending ? 'Saving...' : 'Save'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default EditEmbeddedKnowledgeBaseDialog;

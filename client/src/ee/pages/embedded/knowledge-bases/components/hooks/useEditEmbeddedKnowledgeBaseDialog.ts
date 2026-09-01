import {toOptionalChunkingInt} from '@/shared/components/knowledge-bases/chunking-utils';
import {useUpdateEmbeddedKnowledgeBaseMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {useEffect, useState} from 'react';
import {toast} from 'sonner';

export interface EditableEmbeddedKnowledgeBaseI {
    description?: string | null;
    id: string;
    maxChunkSize?: number | null;
    minChunkSizeChars?: number | null;
    name: string;
    overlap?: number | null;
}

const toFieldValue = (value?: number | null) => (value == null ? '' : String(value));

interface UseEditEmbeddedKnowledgeBaseDialogProps {
    knowledgeBase: EditableEmbeddedKnowledgeBaseI;
    onOpenChange?: (open: boolean) => void;
    open?: boolean;
}

/**
 * The embedded twin of `useEditKnowledgeBaseDialog`, differing in the one way that matters: chunking is editable here
 * rather than carried through untouched. A knowledge base created from the console had no other way to change it --
 * chunking decides how a document is split before embedding, so the alternative was deleting the knowledge base and
 * re-uploading and re-embedding every document in it.
 *
 * Changing chunking does not re-chunk the documents already embedded; it governs the ones ingested after the change.
 */
export default function useEditEmbeddedKnowledgeBaseDialog({
    knowledgeBase,
    onOpenChange,
    open: controlledOpen,
}: UseEditEmbeddedKnowledgeBaseDialogProps) {
    const [internalOpen, setInternalOpen] = useState(false);
    const [name, setName] = useState(knowledgeBase.name);
    const [description, setDescription] = useState(knowledgeBase.description || '');
    const [maxChunkSize, setMaxChunkSize] = useState(toFieldValue(knowledgeBase.maxChunkSize));
    const [minChunkSizeChars, setMinChunkSizeChars] = useState(toFieldValue(knowledgeBase.minChunkSizeChars));
    const [overlapSize, setOverlapSize] = useState(toFieldValue(knowledgeBase.overlap));

    const queryClient = useQueryClient();

    const isControlled = controlledOpen !== undefined;
    const open = isControlled ? controlledOpen : internalOpen;

    const handleOpenChange = (newOpen: boolean) => {
        if (isControlled) {
            onOpenChange?.(newOpen);
        } else {
            setInternalOpen(newOpen);
        }
    };

    const updateMutation = useUpdateEmbeddedKnowledgeBaseMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ['EmbeddedKnowledgeBases']});

            toast('Settings saved successfully.');

            handleOpenChange(false);
        },
    });

    const handleCancel = () => {
        handleOpenChange(false);
    };

    const handleSave = () => {
        updateMutation.mutate({
            input: {
                description: description.trim() || undefined,
                knowledgeBaseId: knowledgeBase.id,
                maxChunkSize: toOptionalChunkingInt(maxChunkSize),
                minChunkSizeChars: toOptionalChunkingInt(minChunkSizeChars),
                name: name.trim(),
                overlap: toOptionalChunkingInt(overlapSize),
            },
        });
    };

    const canSubmit = name.trim().length > 0;

    useEffect(() => {
        if (open) {
            setName(knowledgeBase.name);
            setDescription(knowledgeBase.description || '');
            setMaxChunkSize(toFieldValue(knowledgeBase.maxChunkSize));
            setMinChunkSizeChars(toFieldValue(knowledgeBase.minChunkSizeChars));
            setOverlapSize(toFieldValue(knowledgeBase.overlap));
        }
    }, [
        open,
        knowledgeBase.description,
        knowledgeBase.maxChunkSize,
        knowledgeBase.minChunkSizeChars,
        knowledgeBase.name,
        knowledgeBase.overlap,
    ]);

    return {
        canSubmit,
        description,
        handleCancel,
        handleOpenChange,
        handleSave,
        isPending: updateMutation.isPending,
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
    };
}

import {toOptionalChunkingInt} from '@/shared/components/knowledge-bases/chunking-utils';
import {KnowledgeBaseScopeType} from '@/shared/components/knowledge-bases/types';
import {useCreateEmbeddedKnowledgeBaseMutation, useCreateKnowledgeBaseMutation} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {getCookie} from '@/shared/util/cookie-utils';
import {useQueryClient} from '@tanstack/react-query';
import {ChangeEvent, useState} from 'react';

interface SelectedFileI {
    documentId?: string;
    file: File;
    status: 'completed' | 'error' | 'pending' | 'processing' | 'uploading';
    statusMessage?: string;
}

/**
 * The create behind both surfaces, taking the same scope `useKnowledgeBases` takes and for the same reason: reading
 * the workspace store here would couple the dialog to a surface that has no workspaces. Both mutations are called on
 * every render because hooks cannot be conditional; the scope picks which one `handleSubmit` fires.
 *
 * The three chunking boxes start empty and an empty one is omitted from the mutation, so an untouched field takes
 * whatever `KnowledgeBase` defaults to rather than a number restated here. See `toOptionalChunkingInt`.
 */
export default function useCreateKnowledgeBaseDialog(scope: KnowledgeBaseScopeType) {
    const [open, setOpen] = useState(false);
    const [name, setName] = useState('');
    const [description, setDescription] = useState('');
    const [minChunkSizeChars, setMinChunkSizeChars] = useState('');
    const [maxChunkSize, setMaxChunkSize] = useState('');
    const [overlapSize, setOverlapSize] = useState('');
    const [selectedFiles, setSelectedFiles] = useState<SelectedFileI[]>([]);
    const [uploading, setUploading] = useState(false);

    const environmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const queryClient = useQueryClient();

    const isWorkspaceScope = scope.type === 'WORKSPACE';

    const resetForm = () => {
        setName('');
        setDescription('');
        setMinChunkSizeChars('');
        setMaxChunkSize('');
        setOverlapSize('');
        setSelectedFiles([]);
        setUploading(false);
    };

    const uploadFile = async (knowledgeBaseId: string, file: File, index: number) => {
        setSelectedFiles((prev) => {
            const copy = [...prev];
            copy[index] = {...copy[index], status: 'uploading'};

            return copy;
        });

        try {
            const formData = new FormData();

            formData.append('file', file);

            const response = await fetch(`/api/automation/internal/knowledge-bases/${knowledgeBaseId}/documents`, {
                body: formData,
                headers: {
                    'X-XSRF-TOKEN': getCookie('XSRF-TOKEN') || '',
                },
                method: 'POST',
            });

            if (!response.ok) {
                throw new Error(`Upload failed: ${response.statusText}`);
            }

            setSelectedFiles((prev) => {
                const copy = [...prev];
                copy[index] = {
                    ...copy[index],
                    status: 'completed',
                    statusMessage: 'Uploaded successfully',
                };

                return copy;
            });
        } catch (error) {
            setSelectedFiles((prev) => {
                const copy = [...prev];
                copy[index] = {
                    ...copy[index],
                    status: 'error',
                    statusMessage: error instanceof Error ? error.message : 'Upload failed',
                };

                return copy;
            });
        }
    };

    const uploadFiles = async (knowledgeBaseId: string, files: SelectedFileI[]) => {
        setUploading(true);

        await Promise.all(files.map((selectedFile, index) => uploadFile(knowledgeBaseId, selectedFile.file, index)));

        queryClient.invalidateQueries({queryKey: ['knowledgeBases']});

        setTimeout(() => {
            setOpen(false);
            resetForm();
        }, 500);
    };

    const createMutation = useCreateKnowledgeBaseMutation({
        onSuccess: (data) => {
            const knowledgeBaseId = data.createKnowledgeBase?.id;

            if (selectedFiles.length > 0 && knowledgeBaseId) {
                uploadFiles(knowledgeBaseId, selectedFiles);
            } else {
                queryClient.invalidateQueries({queryKey: ['knowledgeBases']});
                setOpen(false);
                resetForm();
            }
        },
    });

    const createEmbeddedMutation = useCreateEmbeddedKnowledgeBaseMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ['EmbeddedKnowledgeBases']});
            setOpen(false);
            resetForm();
        },
    });

    const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
        if (event.target.files) {
            const newFiles = Array.from(event.target.files).map((file) => ({
                file,
                status: 'pending' as const,
            }));

            setSelectedFiles((prev) => [...prev, ...newFiles]);
        }
    };

    const removeFile = (index: number) => {
        setSelectedFiles((prev) => prev.filter((_, fileIndex) => fileIndex !== index));
    };

    const canSubmit = name.trim().length > 0;

    const handleSubmit = () => {
        if (scope.type === 'WORKSPACE') {
            createMutation.mutate({
                environmentId: String(environmentId),
                knowledgeBase: {
                    description: description.trim() || undefined,
                    maxChunkSize: toOptionalChunkingInt(maxChunkSize),
                    minChunkSizeChars: toOptionalChunkingInt(minChunkSizeChars),
                    name: name.trim(),
                    overlap: toOptionalChunkingInt(overlapSize),
                },
                workspaceId: String(scope.workspaceId),
            });

            return;
        }

        createEmbeddedMutation.mutate({
            input: {
                description: description.trim() || undefined,
                environmentId: String(environmentId),
                maxChunkSize: toOptionalChunkingInt(maxChunkSize),
                minChunkSizeChars: toOptionalChunkingInt(minChunkSizeChars),
                name: name.trim(),
                overlap: toOptionalChunkingInt(overlapSize),
            },
        });
    };

    const formatFileSize = (bytes: number) => {
        if (bytes === 0) {
            return '0 Bytes';
        }

        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));

        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    };

    const handleOpenChange = (newOpen: boolean) => {
        setOpen(newOpen);

        if (!newOpen) {
            resetForm();
        }
    };

    return {
        canSubmit,
        description,
        formatFileSize,
        handleFileChange,
        handleOpenChange,
        handleSubmit,
        isPending: isWorkspaceScope ? createMutation.isPending : createEmbeddedMutation.isPending,
        maxChunkSize,
        minChunkSizeChars,
        name,
        open,
        overlapSize,
        removeFile,
        selectedFiles,
        setDescription,
        setMaxChunkSize,
        setMinChunkSizeChars,
        setName,
        setOpen,
        setOverlapSize,
        uploading,
    };
}

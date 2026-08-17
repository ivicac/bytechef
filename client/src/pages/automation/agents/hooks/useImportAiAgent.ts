import invalidateAgentQueries from '@/pages/automation/agents/utils/invalidateAgentQueries';
import {useImportAiAgentMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {ChangeEvent, RefObject, useRef} from 'react';
import {toast} from 'sonner';

interface UseImportAiAgentParamsI {
    projectId: number;
    workspaceId?: number;
}

interface UseImportAiAgentResultI {
    fileInputRef: RefObject<HTMLInputElement | null>;
    handleImportFileChange: (event: ChangeEvent<HTMLInputElement>) => Promise<void>;
    isImporting: boolean;
    triggerImport: () => void;
}

/**
 * The "Import Agent" file-picker flow, shared by every place that offers it — the tab-row creation actions
 * (ProjectAgentCreationActions) and the project editor sidebar's Agents-tab creation button — so the mutation,
 * toasts and hidden-input wiring are written once rather than duplicated per caller.
 */
const useImportAiAgent = ({projectId, workspaceId}: UseImportAiAgentParamsI): UseImportAiAgentResultI => {
    const fileInputRef = useRef<HTMLInputElement>(null);

    const queryClient = useQueryClient();

    const importAgentMutation = useImportAiAgentMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to import the agent.');
        },
        onSuccess: () => {
            invalidateAgentQueries(queryClient);

            toast.success('Agent imported. Re-attach its connections, skills, knowledge bases and sub-agents.');
        },
    });

    const handleImportFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];

        // Cleared before the await so picking the same file twice in a row still fires a change event.
        event.target.value = '';

        if (!file) {
            return;
        }

        importAgentMutation.mutate({
            json: await file.text(),
            projectId: String(projectId),
            workspaceId: String(workspaceId),
        });
    };

    return {
        fileInputRef,
        handleImportFileChange,
        isImporting: importAgentMutation.isPending,
        triggerImport: () => fileInputRef.current?.click(),
    };
};

export default useImportAiAgent;

import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {toast} from 'sonner';

import {useReportQueryError} from '../../hooks/useReportQueryError';
import {
    AiHubArtifactKindType,
    AiHubTaskArtifactI,
    AiHubTaskI,
    AiHubTaskMessageI,
    AiHubTaskPatchI,
    ArtifactPageResponseI,
    createAiHubTask,
    deleteAiHubTask,
    generateAiHubTaskTitle,
    getTaskArtifacts,
    getTaskMessages,
    listArtifacts,
    listTasks,
    patchTask,
} from '../api/tasks.api';

/**
 * Default mutation error handler for ai-hub task mutations. The api/* helpers use raw fetch (not
 * the global fetch interceptor) so the global error toast layer never sees their failures. Without this hook each
 * failed mutation would silently revert: e.g. a failed delete would reappear after the next list refetch with no
 * user feedback. Routing through a single helper keeps the toast wording consistent across mutations.
 */
export function reportMutationError(action: string, error: Error) {
    const message = error?.message ? error.message : `${action} failed`;

    console.error(`[AiHubTasks] ${action} failed:`, error);

    toast.error(message);
}

export const AiHubTasksKeys = {
    all: ['aiHubTasks'] as const,
    artifactAudit: (workspaceId: number, filters: object) =>
        [...AiHubTasksKeys.all, 'artifactAudit', workspaceId, filters] as const,
    artifacts: (taskId: number, workspaceId: number) =>
        [...AiHubTasksKeys.all, 'artifacts', taskId, workspaceId] as const,
    list: (workspaceId: number, environment: number, status: 'ACTIVE' | 'ARCHIVED') =>
        [...AiHubTasksKeys.all, 'list', workspaceId, environment, status] as const,
    messages: (taskId: number, workspaceId: number) =>
        [...AiHubTasksKeys.all, 'messages', taskId, workspaceId] as const,
};

export function useAiHubTasksQuery(workspaceId: number, environment: number, status: 'ACTIVE' | 'ARCHIVED') {
    const query = useQuery<AiHubTaskI[], Error>({
        queryFn: () => listTasks({environment, status, workspaceId}),
        queryKey: AiHubTasksKeys.list(workspaceId, environment, status),
        staleTime: 30_000,
    });

    useReportQueryError('List tasks', query.error);

    return query;
}

export function useAiHubTaskMessagesQuery(taskId: number, workspaceId: number, enabled: boolean = true) {
    const query = useQuery<AiHubTaskMessageI[], Error>({
        enabled,
        queryFn: () => getTaskMessages({taskId, workspaceId}),
        queryKey: AiHubTasksKeys.messages(taskId, workspaceId),
    });

    useReportQueryError('Load task messages', query.error);

    return query;
}

export function useCreateAiHubTaskMutation() {
    const queryClient = useQueryClient();

    return useMutation<AiHubTaskI, Error, {environment: number; threadId: string; workspaceId: number}>({
        mutationFn: createAiHubTask,
        onError: (error) => reportMutationError('Create task', error),
        onSuccess: (_data, variables) => {
            queryClient.invalidateQueries({
                queryKey: AiHubTasksKeys.list(variables.workspaceId, variables.environment, 'ACTIVE'),
            });
        },
    });
}

/**
 * The patch/delete/title mutations operate on a single task by id; the task's environment isn't part
 * of the mutation payload (it lives on the persisted row), so the cache invalidations use a prefix that catches every
 * environment + status combination for the workspace. This is correct (we don't know which env-bucket the task
 * lives in client-side, and the worst case is one extra refetch) and matches React Query's default prefix-matching
 * semantics for queryKey arrays.
 */
function listPrefixKeyForWorkspace(workspaceId: number) {
    return [...AiHubTasksKeys.all, 'list', workspaceId] as const;
}

export function usePatchAiHubTaskMutation() {
    const queryClient = useQueryClient();

    return useMutation<AiHubTaskI, Error, {taskId: number; patch: AiHubTaskPatchI; workspaceId: number}>({
        mutationFn: patchTask,
        onError: (error) => reportMutationError('Update task', error),
        onSuccess: (_data, variables) => {
            queryClient.invalidateQueries({queryKey: listPrefixKeyForWorkspace(variables.workspaceId)});

            queryClient.invalidateQueries({
                queryKey: AiHubTasksKeys.messages(variables.taskId, variables.workspaceId),
            });
        },
    });
}

export function useDeleteAiHubTaskMutation() {
    const queryClient = useQueryClient();

    return useMutation<void, Error, {taskId: number; workspaceId: number}>({
        mutationFn: deleteAiHubTask,
        onError: (error) => reportMutationError('Delete task', error),
        onSuccess: (_data, variables) => {
            queryClient.invalidateQueries({queryKey: listPrefixKeyForWorkspace(variables.workspaceId)});
        },
    });
}

export function useGenerateAiHubTaskTitleMutation() {
    const queryClient = useQueryClient();

    return useMutation<AiHubTaskI, Error, {taskId: number; workspaceId: number}>({
        mutationFn: generateAiHubTaskTitle,
        onError: (error) => reportMutationError('Generate task title', error),
        onSuccess: (_data, variables) => {
            queryClient.invalidateQueries({queryKey: listPrefixKeyForWorkspace(variables.workspaceId)});
        },
    });
}

/**
 * Key identifying a single data-table row across a task's artifact log. DATA_TABLE_ROW_* artifacts store
 * the row id in artifactId and the parent table id in metadataJson.dataTableId; both are needed because
 * row ids are only unique per table.
 */
function dataTableRowKey(artifact: AiHubTaskArtifactI): string {
    let dataTableId = '';

    if (artifact.metadataJson) {
        try {
            const metadata = JSON.parse(artifact.metadataJson) as Record<string, string>;

            dataTableId = metadata['dataTableId'] ?? '';
        } catch {
            // Unparseable metadata falls back to the bare row id; worst case a cross-table row-id collision
            // keeps a pair visible, which is the safe direction.
        }
    }

    return `${dataTableId}:${artifact.artifactId}`;
}

/**
 * Hides data-table row artifacts that net out to nothing within the same task: a row that was ADDED and later
 * DELETED (plus any UPDATED entries in between) ends the conversation not existing, so showing it as an
 * "attachment" misleads — the canonical case is the agent seeding a sample row to create a table schema and
 * deleting it right after. The server-side artifact log stays complete (the workspace audit viewer shows every
 * entry); this is purely a sidebar presentation rule. A DELETED artifact for a row the task did NOT add stays
 * visible, since destroying pre-existing data is exactly what an audit trail must surface.
 */
export function collapseNetZeroDataTableRowArtifacts(artifacts: AiHubTaskArtifactI[]): AiHubTaskArtifactI[] {
    const addedRowKeys = new Set<string>();
    const deletedRowKeys = new Set<string>();

    for (const artifact of artifacts) {
        if (artifact.kind === 'DATA_TABLE_ROW_ADDED') {
            addedRowKeys.add(dataTableRowKey(artifact));
        } else if (artifact.kind === 'DATA_TABLE_ROW_DELETED') {
            deletedRowKeys.add(dataTableRowKey(artifact));
        }
    }

    if (addedRowKeys.size === 0 || deletedRowKeys.size === 0) {
        return artifacts;
    }

    return artifacts.filter((artifact) => {
        if (
            artifact.kind !== 'DATA_TABLE_ROW_ADDED' &&
            artifact.kind !== 'DATA_TABLE_ROW_UPDATED' &&
            artifact.kind !== 'DATA_TABLE_ROW_DELETED'
        ) {
            return true;
        }

        const rowKey = dataTableRowKey(artifact);

        return !(addedRowKeys.has(rowKey) && deletedRowKeys.has(rowKey));
    });
}

export function useAiHubTaskArtifactsQuery(taskId: number | undefined, workspaceId: number, enabled = true) {
    const query = useQuery<AiHubTaskArtifactI[], Error>({
        enabled: enabled && taskId !== undefined,
        queryFn: () => getTaskArtifacts({taskId: taskId!, workspaceId}),
        queryKey: AiHubTasksKeys.artifacts(taskId ?? -1, workspaceId),
        // Applies to every consumer of this hook (artifact list + count badge) so the two can't disagree.
        select: collapseNetZeroDataTableRowArtifacts,
        staleTime: 60_000,
    });

    useReportQueryError('Load task artifacts', query.error);

    return query;
}

export function useArtifactAuditQuery(
    workspaceId: number,
    filters: {
        environment?: number;
        from?: string;
        kind?: AiHubArtifactKindType;
        page: number;
        size: number;
        to?: string;
        userId?: number;
    }
) {
    const query = useQuery<ArtifactPageResponseI, Error>({
        queryFn: () => listArtifacts({...filters, workspaceId}),
        queryKey: AiHubTasksKeys.artifactAudit(workspaceId, filters),
        staleTime: 30_000,
    });

    useReportQueryError('Load artifact audit', query.error);

    return query;
}

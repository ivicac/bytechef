import {type AiHubChatI, isWebhookBridgedChat} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {useDeleteAiHubChatMutation, usePatchAiHubChatMutation} from '@/ee/pages/automation/ai-hub/chats/hooks/useChats';
import {aiHubChatsStore} from '@/ee/pages/automation/ai-hub/chats/stores/useAiHubChatsStore';
import {
    aiHubRunStateStore,
    isChatRunning,
} from '@/ee/pages/automation/ai-hub/runtime-providers/stores/useAiHubRunStateStore';
import {aiHubStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubStore';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useCancelAiHubRunMutation, useCancelWorkflowChatTurnMutation} from '@/shared/middleware/graphql';
import {useState} from 'react';
import {useNavigate} from 'react-router-dom';

/**
 * Cancel a deleted chat's in-flight run so the server stops streaming a conversation that no longer exists,
 * instead of leaving the run in InFlightAiHubRunRegistry until its TTL (where it keeps consuming model
 * tokens for a chat the user just removed). Mirrors {@link AiHubChatComposer}'s handleCancelTurn but is
 * keyed off the deleted chat's thread id, because a delete can target a background (non-focused) chat.
 *
 * "Streaming" spans two signals: the focused chat sets runningByChat via RUN_STARTED, while a background
 * chat's visible pulse comes from the probe-driven 'running' activity state. Both are covered. The server
 * cancel is idempotent, so a false positive is harmless. No-op when the chat isn't streaming.
 */
export function cancelChatRunIfStreaming(
    chat: Pick<AiHubChatI, 'id' | 'kind' | 'threadId'>,
    workspaceId: number | undefined,
    cancel: {
        cancelAiHubRun: (variables: {id: string; runId: string | undefined; workspaceId: string}) => void;
        cancelWorkflowChatTurn: (variables: {id: string; workspaceId: string}) => void;
    }
): void {
    const runState = aiHubRunStateStore.getState();

    const isStreaming =
        isChatRunning(runState, chat.threadId) || aiHubChatsStore.getState().chatActivity[chat.threadId] === 'running';

    if (!isStreaming || workspaceId == null) {
        return;
    }

    if (isWebhookBridgedChat(chat.kind)) {
        cancel.cancelWorkflowChatTurn({id: String(chat.id), workspaceId: String(workspaceId)});
    } else {
        cancel.cancelAiHubRun({
            id: String(chat.id),
            runId: runState.runIdByChat[chat.threadId],
            workspaceId: String(workspaceId),
        });
    }

    runState.setChatRunning(chat.threadId, false);

    aiHubChatsStore.getState().clearActivityState(chat.threadId);
}

export interface AiHubChatActionsI {
    archiveChat: (chat: AiHubChatI) => void;
    cancelDelete: () => void;
    cancelRename: () => void;
    confirmDelete: () => void;
    deleteTarget: AiHubChatI | null;
    renameTarget: AiHubChatI | null;
    requestDelete: (chat: AiHubChatI) => void;
    requestRename: (chat: AiHubChatI) => void;
    submitRename: (title: string) => void;
    unarchiveChat: (chat: AiHubChatI) => void;
}

/**
 * The rename / archive / delete actions a chat can be put through, and the pending state of the two that
 * need confirming. Both the sidebar row's menu and the chat header's menu act on the same chats, so they
 * share one implementation rather than each carrying its own copy of the delete path — that path cancels
 * an in-flight run and redirects away from a route that is about to 404, and a second copy would be a
 * second chance to get either wrong.
 *
 * Pending state is exposed rather than rendered here: the caller pairs it with AiHubChatActionDialogs.
 */
export function useAiHubChatActions(): AiHubChatActionsI {
    const [renameTarget, setRenameTarget] = useState<AiHubChatI | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<AiHubChatI | null>(null);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const navigate = useNavigate();

    const patchChatMutation = usePatchAiHubChatMutation();
    const deleteChatMutation = useDeleteAiHubChatMutation();
    const cancelAiHubRunMutation = useCancelAiHubRunMutation();
    const cancelWorkflowChatTurnMutation = useCancelWorkflowChatTurnMutation();

    const archiveChat = (chat: AiHubChatI) => {
        patchChatMutation.mutate({
            chatId: chat.id,
            patch: {status: 'ARCHIVED'},
            workspaceId: currentWorkspaceId,
        });

        if (aiHubChatsStore.getState().currentChatId === chat.id) {
            aiHubChatsStore.getState().setCurrentChatId(undefined);
        }
    };

    const unarchiveChat = (chat: AiHubChatI) => {
        patchChatMutation.mutate({
            chatId: chat.id,
            patch: {status: 'ACTIVE'},
            workspaceId: currentWorkspaceId,
        });
    };

    const submitRename = (title: string) => {
        if (!renameTarget) {
            return;
        }

        const trimmedTitle = title.trim();

        if (trimmedTitle && trimmedTitle !== (renameTarget.title ?? '')) {
            patchChatMutation.mutate({
                chatId: renameTarget.id,
                patch: {title: trimmedTitle},
                workspaceId: currentWorkspaceId,
            });
        }

        setRenameTarget(null);
    };

    const confirmDelete = () => {
        if (!deleteTarget) {
            return;
        }

        const chat = deleteTarget;

        // Stop the stream before removing the chat: cancel its in-flight run (server-side) so it doesn't
        // keep running for a chat the user just deleted. Clearing currentChatId below tears down the
        // focused runtime's SSE for the active chat; this handles the background chats too.
        cancelChatRunIfStreaming(chat, currentWorkspaceId, {
            cancelAiHubRun: (variables) => cancelAiHubRunMutation.mutate(variables),
            cancelWorkflowChatTurn: (variables) => cancelWorkflowChatTurnMutation.mutate(variables),
        });

        deleteChatMutation.mutate({
            chatId: chat.id,
            workspaceId: currentWorkspaceId,
        });

        if (aiHubChatsStore.getState().currentChatId === chat.id) {
            // Deleting the chat the user is currently viewing: reset to the home view and redirect so they
            // don't sit on a stale /chats/<id> route for a chat that no longer exists. Mirrors AiHub.tsx's
            // home-reset (clear messages + fresh thread id); the explicit navigate guarantees the redirect.
            aiHubChatsStore.getState().setCurrentChatId(undefined);
            aiHubStore.getState().resetMessages();
            aiHubStore.getState().generateChatId();

            navigate('/automation/ai-hub');
        }

        setDeleteTarget(null);
    };

    return {
        archiveChat,
        cancelDelete: () => setDeleteTarget(null),
        cancelRename: () => setRenameTarget(null),
        confirmDelete,
        deleteTarget,
        renameTarget,
        requestDelete: setDeleteTarget,
        requestRename: setRenameTarget,
        submitRename,
        unarchiveChat,
    };
}

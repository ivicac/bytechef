import {type AiHubChatI} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {type ThreadMessageLike} from '@assistant-ui/react';

/**
 * Whether this chat's transcript currently holds a gated tool call nobody has decided yet.
 *
 * <p>There is no store field for "an approval is pending on this chat": the approval IS a message, a
 * {@code data-tool-approval-request} content part on an assistant row, put there either live by the
 * runtime provider's {@code onToolCallResultEvent} or on chat load by {@code useSwitchChat}, which also
 * stitches {@code resolvedStatus} onto every part whose approval has since been decided. So a part that
 * still says {@code awaitingApproval} and carries no {@code resolvedStatus} is exactly the pending one,
 * and reading the transcript is reading the real state rather than a second copy of it.</p>
 *
 * <p>The server allows at most one pending approval per chat, so this is a boolean rather than a count.</p>
 *
 * <p>One staleness to know about: {@code ToolApprovalRequestMessage} holds this session's own
 * Approve/Reject outcome in local component state and does not write it back into the message, so for the
 * person who just resolved it this keeps reading true until the transcript is reloaded. That does not reach
 * the callers below — both only ask about a viewer who CANNOT resolve the approval, and for them the
 * continuation turn grows the message count, which the focused-chat poll refetches.</p>
 */
export function hasPendingToolApproval(messages: ThreadMessageLike[]): boolean {
    return messages.some((message) => {
        if (message.role !== 'assistant' || !Array.isArray(message.content)) {
            return false;
        }

        return message.content.some((part) => {
            if (
                typeof part !== 'object' ||
                part === null ||
                (part as {type?: unknown}).type !== 'data-tool-approval-request'
            ) {
                return false;
            }

            const data = (part as {data?: Record<string, unknown>}).data;

            return data?.awaitingApproval === true && data.resolvedStatus == null;
        });
    });
}

/**
 * Whether the caller could decide a pending approval on {@code chat} themselves. Mirrors the server's
 * {@code AiHubToolApprovalFacade.canResolve}, which routes through {@code AiHubChatAccessPolicy#canManage} —
 * owner or instance admin, NOT any participant, however the chat's participation level is set. Kept in one
 * place because the composer and the presence strip each need the same answer, and a viewer wrongly told
 * they can resolve gets no controls and no explanation of why the chat is stuck.
 */
export function canResolveToolApproval(chat: AiHubChatI | undefined, isAdmin: boolean): boolean {
    return chat != null && (chat.isOwner || isAdmin);
}

/**
 * The name to put in front of "…'s approval". Falls back to a role rather than an empty string when the
 * owner's name has not resolved: "Waiting for the owner's approval" still tells the viewer who to chase.
 */
export function getToolApprovalOwnerLabel(chat: AiHubChatI | undefined): string {
    return chat?.ownerName ?? 'the owner';
}

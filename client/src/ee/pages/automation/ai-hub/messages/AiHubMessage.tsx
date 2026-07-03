import {TooltipIconButton} from '@/components/assistant-ui/tooltip-icon-button';
import {useAiHubSharingEnabled} from '@/ee/pages/automation/ai-hub/chats/hooks/useAiHubSharingEnabled';
import AiHubMessageContent from '@/ee/pages/automation/ai-hub/messages/AiHubMessageContent';
import {useAiHubStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubStore';
import {ActionBarPrimitive, ComposerPrimitive, MessagePrimitive, useAuiState} from '@assistant-ui/react';
import {CheckIcon, CopyIcon, PencilIcon, RefreshCwIcon} from 'lucide-react';
import {FC} from 'react';

// The platform-generated prompt that restarts an agent turn after a tool approval is resolved — see
// AiHubRuntimeProvider's resolveToolApproval / attachToContinuation. It is never typed by a person, so it
// renders as a slim status line rather than a user bubble; TOOL_APPROVAL_STATUS_PREFIX is the marker that
// tells the two apart.
const TOOL_APPROVAL_STATUS_PREFIX = '[tool-approval #';

/**
 * Edit composer for user messages — replaces the previous AiHubUserMessageEditor wrapper. The runtime
 * provider's onEdit handler does the heavy lifting (server-side chat-memory truncate, local store + agent
 * transcript rewind, re-run); this component is just the inline textarea + send/cancel chrome assistant-ui
 * renders inside `MessagePrimitive.If editing`.
 *
 * <p>border-0 + ring-0 are required to defeat the global `* { @apply border-border }` rule in
 * styles/components.css that would otherwise paint a visible UA-default border around the textarea on focus.</p>
 */
const AiHubEditComposer: FC = () => (
    <div className="aui-cc-edit-composer-wrapper mx-auto flex w-full max-w-[var(--thread-max-width)] flex-col gap-4 px-2 first:mt-4">
        <ComposerPrimitive.Root className="aui-cc-edit-composer-root ml-auto flex w-full max-w-[87.5%] flex-col rounded-xl bg-muted">
            <ComposerPrimitive.Input
                autoFocus
                className="aui-cc-edit-composer-input flex min-h-[60px] w-full resize-none border-0 bg-transparent p-4 text-foreground ring-0 outline-none"
            />

            <div className="flex justify-end gap-2 p-2">
                <ComposerPrimitive.Cancel asChild>
                    <button className="rounded-md border border-input px-3 py-1 text-xs hover:bg-accent" type="button">
                        Cancel
                    </button>
                </ComposerPrimitive.Cancel>

                <ComposerPrimitive.Send asChild>
                    <button
                        className="rounded-md bg-primary px-3 py-1 text-xs text-primary-foreground hover:opacity-90"
                        type="button"
                    >
                        Save and resend
                    </button>
                </ComposerPrimitive.Send>
            </div>
        </ComposerPrimitive.Root>
    </div>
);

/**
 * Hover-revealed pencil button on user messages. Clicking it triggers assistant-ui's beginEdit which swaps the
 * bubble for the EditComposer above. Wired through the runtime's onEdit adapter in AiHubRuntimeProvider.
 */
const AiHubUserActionBar: FC = () => (
    <ActionBarPrimitive.Root
        autohide="not-last"
        className="aui-cc-user-action-bar flex flex-col items-end"
        hideWhenRunning
    >
        <ActionBarPrimitive.Edit asChild>
            <TooltipIconButton className="aui-cc-user-action-edit p-2" tooltip="Edit">
                <PencilIcon />
            </TooltipIconButton>
        </ActionBarPrimitive.Edit>
    </ActionBarPrimitive.Root>
);

/**
 * Action bar floating below assistant messages with Copy + Refresh. Copy reads the message text directly via
 * the assistant-ui primitive (no runtime adapter needed). Refresh fires the runtime's onReload, which truncates
 * the chat-memory + transcript and re-runs the agent against the preceding user message.
 */
const AiHubAssistantActionBar: FC = () => (
    <ActionBarPrimitive.Root
        autohide="not-last"
        className="aui-cc-assistant-action-bar flex gap-1 text-muted-foreground"
        hideWhenRunning
    >
        <ActionBarPrimitive.Copy asChild>
            <TooltipIconButton tooltip="Copy">
                <MessagePrimitive.If copied>
                    <CheckIcon />
                </MessagePrimitive.If>

                <MessagePrimitive.If copied={false}>
                    <CopyIcon />
                </MessagePrimitive.If>
            </TooltipIconButton>
        </ActionBarPrimitive.Copy>

        <ActionBarPrimitive.Reload asChild>
            <TooltipIconButton tooltip="Refresh">
                <RefreshCwIcon />
            </TooltipIconButton>
        </ActionBarPrimitive.Reload>
    </ActionBarPrimitive.Root>
);

const AiHubUserMessage: FC = () => {
    const content = useAuiState((state) => state.message.content);
    // The provider stamps metadata.custom.{authorName, authorUserId} on each loaded user message from the
    // aiHubChatMessages query's authorName/authorUserId fields (see useSwitchChat's mapServerMessages) —
    // null for a message the current turn just sent locally, since that one has no server round-trip yet.
    const authorName = useAuiState((state) => state.message.metadata?.custom?.authorName as string | undefined);
    // Gate for the author label below: the EE visibility edition. This component derives the label
    // purely from message history and reads no other gate, so without this a CE caller would show
    // author labels on any chat whose transcript happens to carry more than one author, while every
    // other sharing surface (the dialog, "Shared with me", the presence strip) renders nothing.
    const sharingEnabled = useAiHubSharingEnabled();
    // A boolean selector, not the raw messages array: subscribing to the whole array here would re-render
    // every user bubble on every streamed token. Zustand only notifies subscribers when the selected value
    // actually changes, so this only re-renders once the thread crosses the one-author line.
    const hasMultipleAuthors = useAiHubStore((state) => {
        const authorUserIds = new Set(
            state.messages
                .map((message) => (message.metadata?.custom?.authorUserId as number | undefined) ?? null)
                .filter((authorUserId): authorUserId is number => authorUserId != null)
        );

        return authorUserIds.size > 1;
    });

    const firstTextPart = content.find((part) => part.type === 'text');
    const firstTextValue = firstTextPart && 'text' in firstTextPart ? firstTextPart.text : undefined;

    if (firstTextValue?.startsWith(TOOL_APPROVAL_STATUS_PREFIX)) {
        const closingBracketIndex = firstTextValue.indexOf(']');
        const statusLine =
            closingBracketIndex >= 0 ? firstTextValue.slice(1, closingBracketIndex) : firstTextValue.slice(1);

        return (
            <div
                className="mx-auto w-full max-w-[var(--thread-max-width)] px-3 py-1 text-xs text-muted-foreground"
                data-testid="tool-approval-status-line"
            >
                {statusLine}
            </div>
        );
    }

    return (
        <MessagePrimitive.Root asChild>
            <div
                className="aui-cc-user-message mx-auto grid w-full max-w-[var(--thread-max-width)] auto-rows-auto grid-cols-[minmax(72px,1fr)_auto] gap-y-2 px-3 py-3 first:mt-3 last:mb-2 [&:where(>*)]:col-start-2"
                data-role="user"
            >
                <div className="relative col-start-2 min-w-0">
                    {/*
                     * Solo chats (the overwhelming majority) show no label at all — this only appears once
                     * the thread has messages from more than one distinct author, so a chat that was never
                     * shared looks exactly as it always has.
                     */}

                    {sharingEnabled && hasMultipleAuthors && authorName && (
                        <div className="mb-1 text-right text-xs text-muted-foreground" data-testid="message-author">
                            {authorName}
                        </div>
                    )}

                    <div className="rounded-2xl bg-muted px-4 py-2 text-sm break-words text-foreground">
                        <AiHubMessageContent />
                    </div>

                    {/*
                     * Hover-revealed pencil button. Clicking it triggers assistant-ui's beginEdit which swaps
                     * the message in the thread for the EditComposer below. The save handler is wired through
                     * the runtime provider's onEdit adapter which truncates chat-memory + re-runs the agent.
                     */}

                    <div className="absolute top-1/2 left-0 -translate-x-full -translate-y-1/2 pr-2">
                        <AiHubUserActionBar />
                    </div>
                </div>
            </div>
        </MessagePrimitive.Root>
    );
};

const AiHubAssistantMessage: FC = () => (
    <MessagePrimitive.Root asChild>
        <div
            className="aui-cc-assistant-message relative mx-auto w-full max-w-[var(--thread-max-width)] py-3 last:mb-12"
            data-role="assistant"
        >
            <div className="mx-3 text-sm leading-7 break-words text-foreground">
                <AiHubMessageContent />
            </div>

            {/*
             * Inline action-bar footer. Reserves exactly the bar's own height (`min-h-6` = the 24px
             * `size-6` icon buttons) and collapses that reservation back with a matching negative margin
             * (`-mb-6`) so revealing the bar on hover doesn't shift surrounding messages. NO top margin: the
             * gap is owned by the preceding content, and EVERY terminal content type (tool card, picker,
             * connect button, …) uses top-margin-only spacing (no bottom margin), so the bar sits uniformly
             * flush beneath all of them. A footer `-mt` was tried but couldn't serve both card and picker
             * (different trailing margins) — normalizing the content is what makes the spacing consistent.
             * The bar must stay IN FLOW (no `autohideFloat`/absolute): a floated bar detaches from the
             * message and opens a hover dead-zone, so the buttons vanish as the cursor moves toward them.
             */}

            <div className="mx-2 -mb-6 min-h-6">
                <AiHubAssistantActionBar />
            </div>
        </div>
    </MessagePrimitive.Root>
);

export const AiHubMessageComponents = {
    AssistantMessage: AiHubAssistantMessage,
    EditComposer: AiHubEditComposer,
    UserMessage: AiHubUserMessage,
};

export default AiHubAssistantMessage;

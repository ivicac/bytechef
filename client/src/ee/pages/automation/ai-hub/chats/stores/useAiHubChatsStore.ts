import {ThreadStatusI} from '@/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient';
import {create} from 'zustand';
import {devtools} from 'zustand/middleware';

export type AiHubChatStatusType = 'ACTIVE' | 'ARCHIVED' | 'DELETED';

/**
 * In-flight signal for a chat, set by AiHubRuntimeProvider on AG-UI lifecycle events. Drives
 * the per-row pulse / pause icon in the sidebar — without it, a workflow chat in mid-execution looked
 * indistinguishable from a finished chat, and a chat paused at `ask_user_question` looked the same as a
 * chat the user had moved on from. Two signals rather than an enum so a turn that's both running and
 * paused-from-the-previous-question (rare, but possible during the streaming-resume hand-off) shows the more
 * actionable "needs your answer" indicator.
 */
export type ChatActivityStateType = 'running' | 'paused';

/** Value equality for one thread's presence roster. Order matters — the server returns a stable order. */
function isSamePresence(left: ThreadStatusI['presence'], right: ThreadStatusI['presence']): boolean {
    if (left.length !== right.length) {
        return false;
    }

    return left.every((entry, index) => {
        const other = right[index];

        return (
            entry.lastSeen === other.lastSeen &&
            entry.state === other.state &&
            entry.userId === other.userId &&
            entry.userName === other.userName
        );
    });
}

function isSameThreadStatus(left: ThreadStatusI, right: ThreadStatusI): boolean {
    return (
        left.inFlight === right.inFlight &&
        left.messageCount === right.messageCount &&
        left.runningUserId === right.runningUserId &&
        left.runningUserName === right.runningUserName &&
        left.updatedAt === right.updatedAt &&
        isSamePresence(left.presence, right.presence)
    );
}

/**
 * Merges a `/status` poll's per-thread answers into the stored map, keeping the PREVIOUS object for any
 * thread whose answer is value-identical — and returning the previous map itself when no thread moved.
 *
 * Two polls run against this map (the focused chat's every 5 s, the sidebar's every 20 s) and each parses
 * its response afresh, so every answer is a new object even for a thread nothing has happened to. Consumers
 * select {@code state.threadStatus[threadId]}, which zustand compares by reference, so an unconditional
 * spread woke the presence strip, the composer's disabled state and the panel header on every tick for a
 * status that had not changed. Exported for direct testing: the saving is invisible from the components.
 *
 * <p>A presence heartbeat legitimately changes {@code lastSeen} every 20 s per present viewer, so a chat
 * somebody else is watching still churns at that cadence. The case this makes free is the common one — an
 * idle chat nobody else has open, where every poll answer is identical to the last.</p>
 */
export function mergeThreadStatus(
    previousStatusByThreadId: Record<string, ThreadStatusI>,
    incomingStatusByThreadId: Record<string, ThreadStatusI>
): Record<string, ThreadStatusI> {
    const merged: Record<string, ThreadStatusI> = {...previousStatusByThreadId};

    let changed = false;

    Object.entries(incomingStatusByThreadId).forEach(([threadId, status]) => {
        const previousStatus = previousStatusByThreadId[threadId];

        if (previousStatus != null && isSameThreadStatus(previousStatus, status)) {
            return;
        }

        merged[threadId] = status;
        changed = true;
    });

    return changed ? merged : previousStatusByThreadId;
}

interface AiHubChatsStateI {
    activeFilter: AiHubChatStatusType;
    chatActivity: Record<string, ChatActivityStateType>;
    // Per-chat LLM picker selection (user-chosen provider + model from the chat-toolbar picker). Keyed by
    // numeric chatId so the selection survives panel close/open and chat switches without leaking across
    // chats. Both null = no override (server uses workspace default). Cleared
    // implicitly when the store is reset; not cleared on chat delete since deleted chats can't be reopened
    // anyway — the entry leaks a few bytes per defunct chat id, which is negligible.
    chatLlmSelections: Record<number, {model: string | null; provider: string | null}>;
    // The activity-state store is keyed by AG-UI thread id (string), not the numeric chat row id,
    // because the runtime provider's per-turn subscriber holds the threadId — not the row id (which it would
    // have to look up). titleGenerationFailures stays keyed by numeric id because it's set/read inside the
    // sidebar component which has the chat row in scope. Two different keys for two different
    // call-site contexts; bridging them here would require threading the row id into the runtime provider
    // which adds coupling for no functional gain.
    clearActivityState: (threadId: string) => void;
    clearTitleGenerationFailure: (chatId: number) => void;
    // Picker selection chosen on the home view BEFORE a chat row exists. AiHubRuntimeProvider.onNew
    // migrates this into chatLlmSelections[newChatId] right after auto-creating the chat, so the
    // override survives the home -> chat transition with no extra branch in withCurrentChatLlmSelection.
    // Cleared by consumeDraftLlmSelection or by reset.
    consumeDraftLlmSelection: (chatId: number) => void;
    currentChatId: number | undefined;
    draftLlmSelection: {model: string | null; provider: string | null} | null;
    markTitleGenerationFailed: (chatId: number, errorMessage: string) => void;
    reset: () => void;
    searchTerm: string;

    setActiveFilter: (filter: AiHubChatStatusType) => void;
    setActivityState: (threadId: string, state: ChatActivityStateType) => void;
    setChatLlmSelection: (chatId: number, provider: string | null, model: string | null) => void;
    setCurrentChatId: (id: number | undefined) => void;
    setDraftLlmSelection: (provider: string | null, model: string | null) => void;
    setSearchTerm: (term: string) => void;
    // Merges by thread id — a call with one thread's status leaves every other thread's entry (and
    // chatActivity) untouched. The sidebar's poll and the runtime provider's TURN_IN_FLIGHT handling are the
    // two writers; both pass a full ThreadStatusI per key, never a partial patch. A value-identical answer
    // preserves the previous object (and the previous map) rather than replacing it — see
    // mergeThreadStatus for why that matters to every selector reading this field.
    setThreadStatus: (statusByThreadId: Record<string, ThreadStatusI>) => void;
    // Keyed by AG-UI thread id, like chatActivity — see that field's doc for why. A thread absent from this
    // map means "not polled yet, or the last poll omitted it" (no view access, or the chat is gone) — NOT
    // "idle with nobody present". Callers must branch on `threadId in threadStatus`, not on a falsy
    // default, to tell the two apart.
    threadStatus: Record<string, ThreadStatusI>;
    // Map of chatId -> error message. When non-empty for a given id, the sidebar shows a retry
    // affordance instead of leaving the chat labelled "Untitled" with no recovery path. The toast
    // shown at failure time is dismissable; this state is the durable signal that lets a user re-invoke
    // title generation later from the sidebar row.
    titleGenerationFailures: Record<number, string>;
}

export const aiHubChatsStore = create<AiHubChatsStateI>()(
    devtools((set) => ({
        activeFilter: 'ACTIVE',
        chatActivity: {},
        chatLlmSelections: {},
        clearActivityState: (threadId) =>
            set((state) => {
                if (!(threadId in state.chatActivity)) {
                    return state;
                }

                const next = {...state.chatActivity};

                delete next[threadId];

                return {chatActivity: next};
            }),
        clearTitleGenerationFailure: (chatId) =>
            set((state) => {
                if (!(chatId in state.titleGenerationFailures)) {
                    return state;
                }

                const next = {...state.titleGenerationFailures};

                delete next[chatId];

                return {titleGenerationFailures: next};
            }),
        // Move the draft selection into the per-chat slot for the freshly-created chat, then clear the
        // draft. No-op when no draft is set (user sent a message without picking anything), so it's safe
        // to call unconditionally from onNew's auto-create path. The migration vs. a fallback-read in
        // withCurrentChatLlmSelection matters because the chat panel's own ModelPicker reads from
        // chatLlmSelections[currentChatId] — without the migration, the picker would render empty after
        // the home -> chat transition even though the override is still active on the wire.
        consumeDraftLlmSelection: (chatId) =>
            set((state) => {
                if (state.draftLlmSelection == null) {
                    return state;
                }

                return {
                    chatLlmSelections: {
                        ...state.chatLlmSelections,
                        [chatId]: state.draftLlmSelection,
                    },
                    draftLlmSelection: null,
                };
            }),
        currentChatId: undefined,
        draftLlmSelection: null,
        markTitleGenerationFailed: (chatId, errorMessage) =>
            set((state) => ({
                titleGenerationFailures: {...state.titleGenerationFailures, [chatId]: errorMessage},
            })),
        reset: () =>
            set({
                activeFilter: 'ACTIVE',
                chatActivity: {},
                chatLlmSelections: {},
                currentChatId: undefined,
                draftLlmSelection: null,
                searchTerm: '',
                threadStatus: {},
                titleGenerationFailures: {},
            }),
        searchTerm: '',

        setActiveFilter: (filter) => set({activeFilter: filter}),
        // setActivityState writes paused over running but NOT running over paused — a paused chat that
        // restarts via the user's next message goes through clearActivityState first (called from the
        // RUN_STARTED handler), so the running state is set against an empty slot. Without this guard,
        // a duplicate RUN_STARTED firing during a paused turn would clobber the user-actionable "needs
        // answer" indicator with a less-actionable "running" pulse.
        setActivityState: (threadId, activityState) =>
            set((state) => {
                const current = state.chatActivity[threadId];

                if (current === 'paused' && activityState === 'running') {
                    return state;
                }

                return {
                    chatActivity: {...state.chatActivity, [threadId]: activityState},
                };
            }),
        setChatLlmSelection: (chatId, provider, model) =>
            set((state) => ({
                chatLlmSelections: {
                    ...state.chatLlmSelections,
                    [chatId]: {model, provider},
                },
            })),
        setCurrentChatId: (id) => set({currentChatId: id}),
        setDraftLlmSelection: (provider, model) =>
            set({draftLlmSelection: provider == null && model == null ? null : {model, provider}}),
        setSearchTerm: (term) => set({searchTerm: term}),
        setThreadStatus: (statusByThreadId) =>
            set((state) => ({
                threadStatus: mergeThreadStatus(state.threadStatus, statusByThreadId),
            })),
        threadStatus: {},
        titleGenerationFailures: {},
    }))
);

export const useAiHubChatsStore = aiHubChatsStore;

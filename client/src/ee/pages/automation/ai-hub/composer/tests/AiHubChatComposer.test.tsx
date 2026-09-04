import {TooltipProvider} from '@/components/ui/tooltip';
import {
    type ReferencedResourceI,
    aiHubComposerStore,
} from '@/ee/pages/automation/ai-hub/composer/stores/useAiHubComposerStore';
import {aiHubStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubStore';
import {aiHubTabsStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {ReactNode} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

/**
 * Focused coverage for AiHubChatComposer's presentational layer — the referenced-resource chip row,
 * each chip's remove (X) control, and the empty-state behaviour. The component previously had no test
 * at all: a test-suite revival deleted the old (skipped) AiHubComposer chip/remove/empty-state cases
 * on the basis the behaviour "moved to AiHubChatComposer" — but AiHubChatComposer was never given a
 * test. This file closes that gap.
 *
 * The upload pipeline is NOT exercised here (it is already covered by useAiHubAttachmentUpload.test.ts);
 * the upload hook is mocked to return no uploads so only the chip layer is under test. The assistant-ui
 * runtime primitives, voice input, GraphQL mutations, and sibling composer subtrees are stubbed so the
 * component renders in isolation.
 */

// useAiHubAttachmentUpload owns the upload pipeline (covered by its own test). Stub it so the composer
// renders with no uploads and the chip row reflects only referencedResources.
vi.mock('@/ee/pages/automation/ai-hub/composer/hooks/useAiHubAttachmentUpload', () => ({
    ALLOWED_MIME_TYPES: ['image/png'],
    useAiHubAttachmentUpload: () => ({
        dismiss: vi.fn(),
        retry: vi.fn(),
        upload: vi.fn(),
        uploads: [],
    }),
}));

// AiHubComposerDropZone is a sibling (covered by its own test) — render its children directly so the
// composer tree mounts without the drag-and-drop machinery.
vi.mock('@/ee/pages/automation/ai-hub/composer/AiHubComposerDropZone', () => ({
    default: ({children}: {children: ReactNode}) => <div data-testid="drop-zone">{children}</div>,
}));

// AiHubComposer (the ResourcePickerMenu trigger) and ChatToolChips are sibling subtrees, not under
// test here — stub them to keep the render lightweight.
vi.mock('@/ee/pages/automation/ai-hub/composer/AiHubComposer', () => ({
    default: () => <div data-testid="ai-hub-composer" />,
}));

vi.mock('@/ee/pages/automation/ai-hub/tools/ChatToolChips', () => ({
    default: () => <div data-testid="chat-tool-chips" />,
}));

// The assistant-ui runtime primitives require an AssistantRuntimeProvider context. Stub the pieces the
// composer uses so it mounts standalone: Root/Input render plain elements, Send/If/Dictate render their
// children, AuiIf renders nothing (the dictation buttons are not under test), and the runtime hooks
// return inert objects.
vi.mock('@assistant-ui/react', () => ({
    AuiIf: () => null,
    ComposerPrimitive: {
        Dictate: ({children}: {children: ReactNode}) => <>{children}</>,
        Input: (props: Record<string, unknown>) => <textarea {...props} />,
        Root: ({children}: {children: ReactNode}) => <div data-testid="composer-root">{children}</div>,
        Send: ({children}: {children: ReactNode}) => <>{children}</>,
        StopDictation: ({children}: {children: ReactNode}) => <>{children}</>,
    },
    ThreadPrimitive: {
        If: ({children, running}: {children: ReactNode; running: boolean}) => (running ? null : <>{children}</>),
    },
    useAui: () => ({
        composer: {
            getState: () => ({text: ''}),
            send: vi.fn(),
            setText: vi.fn(),
        },
        subscribe: () => () => {},
        thread: {cancelRun: vi.fn()},
    }),
}));

// GraphQL cancel mutations are only invoked on a Stop click — stub them so the component mounts.
vi.mock('@/shared/middleware/graphql', () => ({
    useAiSkillsQuery: () => ({data: undefined}),
    useCancelAiHubRunMutation: () => ({mutate: vi.fn()}),
    useCancelWorkflowChatTurnMutation: () => ({mutate: vi.fn()}),
}));

// The active-chat lookup (currentChatId from the chats store ∩ the chats query) drives `isWorkflowChat`
// (gates the resource-attachment controls) and `isChannelBornChat` (gates the message input itself).
// Hoisted mutable refs let each test set the active chat's kind/aiAgentId/workflowExecutionId; all default
// to "no active chat" so existing chip tests see a standard (non-workflow) composer.
interface MockChatI {
    aiAgentId?: number | null;
    id: number;
    isOwner?: boolean;
    kind: string;
    participation?: 'PARTICIPATE' | 'VIEW';
    threadId?: string;
    workflowExecutionId?: string | null;
}

interface MockThreadStatusI {
    inFlight: boolean;
    runningUserId: number | null;
    runningUserName: string | null;
}

const {
    chatsQueryRef,
    currentChatIdRef,
    currentUserIdRef,
    isAdminRef,
    sendPresenceMock,
    sharingEnabledRef,
    threadStatusRef,
} = vi.hoisted(() => ({
    chatsQueryRef: {current: undefined as MockChatI[] | undefined},
    currentChatIdRef: {current: undefined as number | undefined},
    currentUserIdRef: {current: undefined as number | undefined},
    isAdminRef: {current: false},
    sendPresenceMock: vi.fn(),
    sharingEnabledRef: {current: true},
    threadStatusRef: {current: {} as Record<string, MockThreadStatusI>},
}));

vi.mock('@/ee/pages/automation/ai-hub/chats/hooks/useAiHubSharingEnabled', () => ({
    useAiHubSharingEnabled: () => sharingEnabledRef.current,
}));

vi.mock('@/ee/pages/automation/ai-hub/chats/hooks/useChats', () => ({
    useAiHubChatsQuery: () => ({data: chatsQueryRef.current}),
}));

vi.mock('@/ee/pages/automation/ai-hub/chats/stores/useAiHubChatsStore', () => ({
    useAiHubChatsStore: (
        selector: (state: {
            currentChatId: number | undefined;
            threadStatus: Record<string, MockThreadStatusI>;
        }) => unknown
    ) => selector({currentChatId: currentChatIdRef.current, threadStatus: threadStatusRef.current}),
}));

vi.mock('@/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient', () => ({
    sendPresence: sendPresenceMock,
}));

// Workspace / environment stores are read via selectors; constant returns are enough here.
vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: vi.fn(() => 1),
}));

// Owner-or-admin bypass for the view-only composer gate — isAdminRef lets a test flip this without
// pulling in the real edition/authentication stores the hook composes.
vi.mock('@/shared/hooks/useVisibilityFeatureEnabled', () => ({
    useVisibilityFeatureEnabled: () => ({enabled: true, isAdmin: isAdminRef.current, workspaceId: 1}),
}));

vi.mock('@/shared/stores/useAuthenticationStore', () => ({
    useAuthenticationStore: (selector: (state: {account: {id: number | undefined}}) => unknown) =>
        selector({account: {id: currentUserIdRef.current}}),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: vi.fn(() => 0),
}));

const dataTableResource: ReferencedResourceI = {id: 'dt-1', kind: 'dataTable', name: 'Customers'};
const knowledgeBaseResource: ReferencedResourceI = {id: 'kb-2', kind: 'knowledgeBase', name: 'Product Docs'};

const renderComposer = async () => {
    const {default: AiHubChatComposer} = await import('../AiHubChatComposer');

    return render(
        <TooltipProvider>
            <AiHubChatComposer />
        </TooltipProvider>
    );
};

beforeEach(() => {
    aiHubComposerStore.setState({referencedResources: [], resourcePickerOpen: false, selectedSkills: []});

    aiHubTabsStore.setState({
        activeChatId: undefined,
        activeTabId: undefined,
        attachedTabIds: [],
        openTabs: [],
        rightPanelOpen: false,
        snapshotsByChatId: {},
    });

    currentChatIdRef.current = undefined;
    currentUserIdRef.current = undefined;
    isAdminRef.current = false;
    sharingEnabledRef.current = true;
    threadStatusRef.current = {};
    chatsQueryRef.current = undefined;
    sendPresenceMock.mockClear();
});

describe('AiHubChatComposer skill chips', () => {
    it('renders a chip for each skill armed through the / menu and removes it on X', async () => {
        aiHubComposerStore.setState({selectedSkills: [{id: 'skill-1', name: 'email-digest'}]});

        await renderComposer();

        expect(screen.getByTestId('skill-chip')).toBeInTheDocument();
        expect(screen.getByText('email-digest')).toBeInTheDocument();

        fireEvent.click(screen.getByLabelText('Remove email-digest'));

        expect(aiHubComposerStore.getState().selectedSkills).toEqual([]);
        expect(screen.queryByText('email-digest')).not.toBeInTheDocument();
    });
});

describe('AiHubChatComposer referenced-resource chips', () => {
    it('renders a chip for each referenced resource in the composer store', async () => {
        aiHubComposerStore.setState({referencedResources: [dataTableResource, knowledgeBaseResource]});

        await renderComposer();

        expect(screen.getByTestId('reference-chips')).toBeInTheDocument();
        expect(screen.getByText('Customers')).toBeInTheDocument();
        expect(screen.getByText('Product Docs')).toBeInTheDocument();
    });

    it('removes a referenced resource from the store when its X control is clicked', async () => {
        aiHubComposerStore.setState({referencedResources: [dataTableResource, knowledgeBaseResource]});

        await renderComposer();

        fireEvent.click(screen.getByLabelText('Remove Customers'));

        const {referencedResources} = aiHubComposerStore.getState();

        expect(referencedResources).toEqual([knowledgeBaseResource]);
        expect(screen.queryByText('Customers')).not.toBeInTheDocument();
        expect(screen.getByText('Product Docs')).toBeInTheDocument();
    });

    /*
     * Attaching a resource writes to TWO stores: a chip in aiHubComposerStore and a viewer tab in
     * aiHubTabsStore (see AiHubComposer.handleSelect). Removal has to undo both. It didn't: the chip
     * went, the tab stayed, and because the home -> chat hand-off inherits every open home-view tab
     * (useAiHubTabsStore.switchChat) while useRecordReferencedArtifacts records every open tab as a
     * chat artifact, a resource the user had explicitly removed came back as an attachment on the
     * chat their next prompt created.
     */
    it('closes the viewer tab the attach opened when the chip is removed', async () => {
        const tabId = aiHubTabsStore.getState().openDataTableTab('dt-1', 'Customers');

        aiHubComposerStore.setState({referencedResources: [{...dataTableResource, ownsTab: true, tabId}]});

        await renderComposer();

        fireEvent.click(screen.getByLabelText('Remove Customers'));

        expect(aiHubComposerStore.getState().referencedResources).toEqual([]);
        expect(aiHubTabsStore.getState().openTabs).toEqual([]);
    });

    /*
     * The converse guard: a tab the user already had open before attaching is not owned by the chip, so
     * removing the chip detaches it but must not close it. `ownsTab` is set only when the attach itself
     * created the tab, which is what makes the two cases distinguishable here.
     */
    it('detaches but does not close a tab it did not open', async () => {
        const tabId = aiHubTabsStore.getState().openDataTableTab('dt-1', 'Customers');

        aiHubTabsStore.getState().markTabAttached(tabId);

        aiHubComposerStore.setState({referencedResources: [{...dataTableResource, ownsTab: false, tabId}]});

        await renderComposer();

        fireEvent.click(screen.getByLabelText('Remove Customers'));

        expect(aiHubComposerStore.getState().referencedResources).toEqual([]);
        expect(aiHubTabsStore.getState().openTabs).toHaveLength(1);
        // Still on screen, but no longer an attachment - so the hand-off will not adopt it into a new chat.
        expect(aiHubTabsStore.getState().attachedTabIds).toEqual([]);
    });

    it('omits the chip row entirely when there are no referenced resources or uploads', async () => {
        await renderComposer();

        expect(screen.queryByTestId('reference-chips')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', {name: /^Remove /})).not.toBeInTheDocument();
    });
});

describe('AiHubChatComposer workflow-chat attachment gating', () => {
    it('renders the resource picker and attach-file button for a standard chat', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        expect(screen.getByTestId('ai-hub-composer')).toBeInTheDocument();
        expect(screen.getByLabelText('Attach file')).toBeInTheDocument();
    });

    it('hides the resource picker and attach-file button for a workflow chat', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'WORKFLOW_CHAT'}];

        await renderComposer();

        expect(screen.queryByTestId('ai-hub-composer')).not.toBeInTheDocument();
        expect(screen.queryByLabelText('Attach file')).not.toBeInTheDocument();
    });
});

describe('AiHubChatComposer channel-born agent chat input gating', () => {
    // Channel-born AGENT_CHAT rows (aiAgentId stamped, workflowExecutionId null — see isChannelAgentChat)
    // must not offer a typeable input at all: the conversation is driven by its channel (Slack, a
    // schedule, …), not by AI Hub, so a message typed here has nowhere real to go. This is the fix for
    // the gap where isWorkflowChat alone hid only the attachment controls above, leaving the message
    // input itself enabled — this test fails if that gate is ever removed or narrowed back to
    // isWorkflowChat.
    it('replaces the message input with a read-only notice for a channel-born agent chat', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{aiAgentId: 9, id: 7, kind: 'AGENT_CHAT', workflowExecutionId: null}];

        await renderComposer();

        expect(screen.queryByLabelText('Message input')).not.toBeInTheDocument();
        expect(screen.getByTestId('channel-born-readonly-notice')).toBeInTheDocument();
        expect(screen.getByText(/happens on its channel/)).toBeInTheDocument();
    });

    it('still renders a typeable message input for a composer-created agent chat (aiAgentId null)', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{aiAgentId: null, id: 7, kind: 'AGENT_CHAT', workflowExecutionId: 'exec-1'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('channel-born-readonly-notice')).not.toBeInTheDocument();
    });

    it('still renders a typeable message input for a WORKFLOW_CHAT — typing is the entire point of it', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{aiAgentId: null, id: 7, kind: 'WORKFLOW_CHAT', workflowExecutionId: 'exec-1'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('channel-born-readonly-notice')).not.toBeInTheDocument();
    });

    it('still renders a typeable message input for a STANDARD chat', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('channel-born-readonly-notice')).not.toBeInTheDocument();
    });
});

describe("AiHubChatComposer '@' resource-picker trigger", () => {
    // The '@' key is the composer's second way into the resource picker (the "+" button being the first).
    // It raises the picker by flipping a store flag AiHubComposer carries down — a keystroke in this
    // textarea cannot otherwise reach a popover owned by a sibling component.
    const pressAt = (caret: number, value = '') => {
        const textarea = screen.getByLabelText('Message input') as HTMLTextAreaElement;

        textarea.value = value;
        textarea.selectionStart = caret;
        textarea.selectionEnd = caret;

        return fireEvent.keyDown(textarea, {key: '@'});
    };

    it('opens the picker when @ is typed at the start of an empty composer', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        pressAt(0);

        expect(aiHubComposerStore.getState().resourcePickerOpen).toBe(true);
    });

    it('opens the picker when @ follows a space mid-sentence', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        pressAt(5, 'look ');

        expect(aiHubComposerStore.getState().resourcePickerOpen).toBe(true);
    });

    // Without the word-boundary rule, typing an email address would pop the picker open mid-word and
    // swallow the '@' — the address would come out as "supportbytechef.io".
    it('leaves @ as ordinary text mid-word, so an email address still types through', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        const defaultNotPrevented = pressAt(7, 'support');

        expect(aiHubComposerStore.getState().resourcePickerOpen).toBe(false);
        // fireEvent returns false when preventDefault was called; the '@' must reach the textarea here.
        expect(defaultNotPrevented).toBe(true);
    });

    it('consumes the @ keystroke when it opens the picker', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        expect(pressAt(0)).toBe(false);
    });

    // Radix hands focus back to the popover trigger — the "+" button — on close. After a '@' the user was
    // mid-sentence, so the caret has to come back to the textarea or their next keystroke is swallowed by a
    // button. This is the half of the flow that makes '@' usable rather than merely functional.
    it('returns focus to the textarea when a picker raised by @ closes', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input') as HTMLTextAreaElement;

        pressAt(0);
        textarea.blur();

        act(() => {
            aiHubComposerStore.getState().setResourcePickerOpen(false);
        });

        await waitFor(() => {
            expect(textarea).toHaveFocus();
        });
    });

    // The "+" button must keep Radix's ordinary trigger-restore behaviour: nobody was typing, so stealing
    // the caret into the textarea would be the surprising move.
    it('leaves focus alone when the picker was opened without @', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input') as HTMLTextAreaElement;

        act(() => {
            aiHubComposerStore.getState().setResourcePickerOpen(true);
        });

        textarea.blur();

        act(() => {
            aiHubComposerStore.getState().setResourcePickerOpen(false);
        });

        await waitFor(() => {
            expect(textarea).not.toHaveFocus();
        });
    });

    // A workflow chat forwards messages to a webhook rather than an agent, so it renders no picker at all
    // (see the attachment-gating suite above). '@' must not open one that isn't there.
    it('stays inert for a workflow chat, which has no resource picker', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{aiAgentId: null, id: 7, kind: 'WORKFLOW_CHAT', workflowExecutionId: 'exec-1'}];

        await renderComposer();

        pressAt(0);

        expect(aiHubComposerStore.getState().resourcePickerOpen).toBe(false);
    });
});

describe('AiHubChatComposer view-only gating', () => {
    it('replaces the message input with a view-only notice when participation is VIEW and the caller is not the owner', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, isOwner: false, kind: 'STANDARD', participation: 'VIEW'}];

        await renderComposer();

        expect(screen.queryByLabelText('Message input')).not.toBeInTheDocument();
        expect(screen.getByTestId('view-only-notice')).toBeInTheDocument();
        expect(screen.getByText(/view-only access/)).toBeInTheDocument();
    });

    it('still renders a typeable input when participation is VIEW but the caller owns the chat', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, isOwner: true, kind: 'STANDARD', participation: 'VIEW'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('view-only-notice')).not.toBeInTheDocument();
    });

    it('still renders a typeable input for a VIEW, non-owned chat when the caller is a workspace admin', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, isOwner: false, kind: 'STANDARD', participation: 'VIEW'}];
        isAdminRef.current = true;

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('view-only-notice')).not.toBeInTheDocument();
    });

    it('still renders a typeable input when participation is PARTICIPATE, even for a non-owner', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, isOwner: false, kind: 'STANDARD', participation: 'PARTICIPATE'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('view-only-notice')).not.toBeInTheDocument();
    });

    it("still renders a typeable input for a STANDARD chat with no participation set (the caller's own, never-shared chat)", async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('view-only-notice')).not.toBeInTheDocument();
    });
});

describe("AiHubChatComposer another participant's turn running", () => {
    it("disables the input and shows the running user's name in the placeholder when someone else's turn is in flight", async () => {
        currentChatIdRef.current = 7;
        currentUserIdRef.current = 1;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];
        threadStatusRef.current = {
            'thread-7': {inFlight: true, runningUserId: 2, runningUserName: 'Ana'},
        };

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        expect(textarea).toBeDisabled();
        expect(textarea).toHaveAttribute('placeholder', expect.stringContaining("Ana's turn is running"));
    });

    it('leaves the input enabled when the in-flight turn belongs to the caller themselves', async () => {
        currentChatIdRef.current = 7;
        currentUserIdRef.current = 2;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];
        threadStatusRef.current = {
            'thread-7': {inFlight: true, runningUserId: 2, runningUserName: 'Ana'},
        };

        await renderComposer();

        expect(screen.getByLabelText('Message input')).not.toBeDisabled();
    });

    it('leaves the input enabled once the thread status reports no run in flight', async () => {
        currentChatIdRef.current = 7;
        currentUserIdRef.current = 1;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];
        threadStatusRef.current = {
            'thread-7': {inFlight: false, runningUserId: null, runningUserName: null},
        };

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        expect(textarea).not.toBeDisabled();
        expect(textarea).toHaveAttribute('placeholder', 'Send a message...');
    });

    it('leaves the input enabled when the thread has no polled status at all', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).not.toBeDisabled();
    });
});

describe('AiHubChatComposer typing presence', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    // A typing indicator exists to say "words are coming" — it has to announce on the leading edge of a
    // burst, not only once the person stops. These tests pin that directly: no timer advance is needed to
    // observe the first TYPING send, because it must fire synchronously with the keystroke that starts it.
    it('sends TYPING synchronously on the very first keystroke of a burst — no timer advance needed', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        expect(sendPresenceMock).toHaveBeenCalledExactlyOnceWith(aiHubStore.getState().chatId, 'TYPING');
    });

    it('throttles further TYPING sends during a continuous burst — no resend within the throttle window', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        act(() => {
            vi.advanceTimersByTime(900);
        });

        fireEvent.change(textarea, {target: {value: 'he'}});

        act(() => {
            vi.advanceTimersByTime(900);
        });

        fireEvent.change(textarea, {target: {value: 'hel'}});

        // Three keystrokes inside the 3s throttle window — still only the one leading-edge send.
        expect(sendPresenceMock).toHaveBeenCalledExactlyOnceWith(aiHubStore.getState().chatId, 'TYPING');
    });

    it('sends a fresh TYPING once the throttle window elapses while the burst is still going', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        // Past the 3s throttle window, but keystrokes keep coming under the 3s idle gap so the burst never
        // reverted to VIEWING in between.
        act(() => {
            vi.advanceTimersByTime(1_500);
        });

        fireEvent.change(textarea, {target: {value: 'he'}});

        act(() => {
            vi.advanceTimersByTime(1_500);
        });

        fireEvent.change(textarea, {target: {value: 'hel'}});

        const typingCalls = sendPresenceMock.mock.calls.filter(([, state]) => state === 'TYPING');

        expect(typingCalls).toHaveLength(2);
    });

    it('falls back to VIEWING once the user has genuinely paused (idle gap elapses)', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        sendPresenceMock.mockClear();

        act(() => {
            vi.advanceTimersByTime(3_000);
        });

        expect(sendPresenceMock).toHaveBeenCalledExactlyOnceWith(aiHubStore.getState().chatId, 'VIEWING');
    });

    it('does not fall back to VIEWING while keystrokes keep landing within the idle gap', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        sendPresenceMock.mockClear();

        // Each keystroke lands well inside the 3s idle gap and resets it — total elapsed time exceeds 3s,
        // but the gap SINCE THE LAST keystroke never does. This is the guard that stops "one dropped
        // request" reasoning from applying here too: a steady typist must never flicker to VIEWING mid-word.
        for (let tick = 0; tick < 4; tick += 1) {
            act(() => {
                vi.advanceTimersByTime(2_000);
            });

            fireEvent.change(textarea, {target: {value: `h${'e'.repeat(tick + 1)}`}});
        }

        expect(sendPresenceMock).not.toHaveBeenCalledWith(aiHubStore.getState().chatId, 'VIEWING');
    });

    it('announces a fresh leading-edge TYPING for a new burst that starts after an idle VIEWING', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        act(() => {
            vi.advanceTimersByTime(3_000);
        });

        expect(sendPresenceMock).toHaveBeenLastCalledWith(aiHubStore.getState().chatId, 'VIEWING');

        sendPresenceMock.mockClear();

        fireEvent.change(textarea, {target: {value: 'hi'}});

        expect(sendPresenceMock).toHaveBeenCalledExactlyOnceWith(aiHubStore.getState().chatId, 'TYPING');
    });

    it('clears pending timers on unmount so no further heartbeat fires afterward', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        const {unmount} = await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'h'}});

        expect(sendPresenceMock).toHaveBeenCalledTimes(1);

        unmount();

        act(() => {
            vi.advanceTimersByTime(5_000);
        });

        // The leading-edge TYPING already fired before unmount; nothing further (no VIEWING fallback)
        // should follow it once the component — and its timers — are gone.
        expect(sendPresenceMock).toHaveBeenCalledTimes(1);
    });
});

describe('AiHubChatComposer with chat sharing disabled', () => {
    beforeEach(() => {
        sharingEnabledRef.current = false;
    });

    it('renders a plain typeable input — no view-only notice — for a VIEW, non-owned chat when the hook returns false', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, isOwner: false, kind: 'STANDARD', participation: 'VIEW'}];

        await renderComposer();

        expect(screen.getByLabelText('Message input')).toBeInTheDocument();
        expect(screen.queryByTestId('view-only-notice')).not.toBeInTheDocument();
    });

    it("leaves the input enabled — no others'-turn disable — when the hook returns false even though another user's turn is in flight", async () => {
        currentChatIdRef.current = 7;
        currentUserIdRef.current = 1;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];
        threadStatusRef.current = {
            'thread-7': {inFlight: true, runningUserId: 2, runningUserName: 'Ana'},
        };

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        expect(textarea).not.toBeDisabled();
        expect(textarea).toHaveAttribute('placeholder', 'Send a message...');
    });

    it('issues no presence request on typing when the hook returns false', async () => {
        currentChatIdRef.current = 7;
        chatsQueryRef.current = [{id: 7, kind: 'STANDARD', threadId: 'thread-7'}];

        await renderComposer();

        const textarea = screen.getByLabelText('Message input');

        fireEvent.change(textarea, {target: {value: 'hi'}});

        expect(sendPresenceMock).not.toHaveBeenCalled();
    });
});

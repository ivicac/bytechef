import {TooltipProvider} from '@/components/ui/tooltip';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowTestChatStore from '@/pages/platform/workflow-editor/stores/useWorkflowTestChatStore';
import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import WorkflowTestChatPanel from './WorkflowTestChatPanel';

vi.mock('@/components/assistant-ui/thread', () => ({Thread: () => <div data-testid="thread" />}));
vi.mock(
    '@/pages/platform/workflow-editor/components/workflow-test-chat/runtime-providers/WorkflowTestChatRuntimeProvider',
    () => ({WorkflowTestChatRuntimeProvider: ({children}: {children: React.ReactNode}) => <>{children}</>})
);
vi.mock('@/shared/components/ai-chat/messages/aiChatDataComponents', () => ({aiChatDataComponents: {}}));
vi.mock('@/shared/components/copilot/hooks/useCopilotLayoutShifted', () => ({default: () => false}));
vi.mock('@/shared/hooks/useWorkflowTestVoiceSession', () => ({
    useWorkflowTestVoiceSession: () => ({error: null, start: vi.fn(), status: 'idle', stop: vi.fn()}),
}));
vi.mock('@/shared/lib/browser-voice/BrowserVoiceSession', () => ({checkVoiceSupport: () => 'unsupported'}));
vi.mock('@/shared/lib/voice/ByteChefRealtimeVoiceAdapter', () => ({createWebhookVoiceAdapter: vi.fn()}));
vi.mock('@/shared/lib/voice/VoiceModeLayout', () => ({VoiceModeLayout: () => null}));

describe('WorkflowTestChatPanel', () => {
    beforeEach(() => {
        useWorkflowDataStore.setState({workflow: {definition: '{}', id: 'workflow-1'}} as Parameters<
            typeof useWorkflowDataStore.setState
        >[0]);
        useWorkflowTestChatStore.setState({
            conversationId: 'conversation-1',
            messages: [{content: [{text: 'hi', type: 'text'}], role: 'user'}],
            workflowTestChatPanelOpen: true,
        });
    });

    // The runtime provider reads `messages` and `conversationId` off the store, so a reset is those
    // two moving: the thread empties and the next message starts a new conversation.
    it('clears the thread and starts a new conversation on Reset conversation', () => {
        render(
            <TooltipProvider>
                <WorkflowTestChatPanel />
            </TooltipProvider>
        );

        fireEvent.click(screen.getByLabelText('Reset conversation'));

        const {conversationId, messages} = useWorkflowTestChatStore.getState();

        expect(messages).toEqual([]);
        expect(conversationId).toBeDefined();
        expect(conversationId).not.toBe('conversation-1');
    });
});

import useCopilotPostTurnRegistry from '@/shared/components/copilot/stores/useCopilotPostTurnRegistry';
import {MODE, Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
import {act, render} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {CopilotRuntimeProvider} from './CopilotRuntimeProvider';

interface FakeSubscriberI {
    onTextMessageContentEvent?: (params: {event: {delta: string}; textMessageBuffer: string}) => void;
}

interface FakeAgentI {
    abortRun: ReturnType<typeof vi.fn>;
    resolveRun?: () => void;
    subscriber?: FakeSubscriberI;
}

interface RuntimeOptionsI {
    onNew: (message: {content: {text: string; type: string}[]}) => Promise<void>;
}

const hoisted = vi.hoisted(() => ({
    agents: [] as FakeAgentI[],
    runtimeOptions: undefined as RuntimeOptionsI | undefined,
}));

vi.mock('@ag-ui/client', () => ({
    HttpAgent: class {
        abortRun = vi.fn();
        addMessage = vi.fn();
        messages = [];
        resolveRun?: () => void;
        setMessages = vi.fn();
        setState = vi.fn();
        subscriber?: FakeSubscriberI;

        constructor() {
            hoisted.agents.push(this as unknown as FakeAgentI);
        }

        runAgent(_input: unknown, subscriber: FakeSubscriberI) {
            this.subscriber = subscriber;

            return new Promise<void>((resolve) => {
                this.resolveRun = resolve;
            });
        }
    },
}));

vi.mock('@assistant-ui/react', () => ({
    AssistantRuntimeProvider: ({children}: {children: React.ReactNode}) => children,
    AuiConfig: () => ({}),
    Suggestions: () => ({}),
    useExternalStoreRuntime: (options: RuntimeOptionsI) => {
        hoisted.runtimeOptions = options;

        return {};
    },
}));

describe('CopilotRuntimeProvider', () => {
    beforeEach(() => {
        hoisted.agents.length = 0;
        hoisted.runtimeOptions = undefined;

        useCopilotPostTurnRegistry.setState({callbacks: {}});
        useCopilotStore.setState({
            context: {mode: MODE.BUILD, parameters: {}, source: Source.WORKFLOW_EDITOR},
            conversationId: 'conversation-1',
            messages: [],
        });
    });

    afterEach(() => {
        vi.clearAllMocks();
    });

    it('aborts the in-flight run when the conversation changes', () => {
        render(
            <CopilotRuntimeProvider>
                <div />
            </CopilotRuntimeProvider>
        );

        const firstAgent = hoisted.agents[0];

        act(() => useCopilotStore.setState({conversationId: 'conversation-2'}));

        expect(firstAgent.abortRun).toHaveBeenCalled();
    });

    it('aborts the in-flight run on unmount', () => {
        const {unmount} = render(
            <CopilotRuntimeProvider>
                <div />
            </CopilotRuntimeProvider>
        );

        unmount();

        expect(hoisted.agents[0].abortRun).toHaveBeenCalled();
    });

    it('drops streamed text and post-turn callbacks of a run whose conversation was replaced', async () => {
        const postTurnCallback = vi.fn();

        useCopilotPostTurnRegistry.getState().register(Source.WORKFLOW_EDITOR, postTurnCallback);

        render(
            <CopilotRuntimeProvider>
                <div />
            </CopilotRuntimeProvider>
        );

        const agent = hoisted.agents[0];

        let sendPromise: Promise<void> | undefined;

        act(() => {
            sendPromise = hoisted.runtimeOptions?.onNew({content: [{text: 'hello', type: 'text'}]});
        });

        const restoredMessages = [
            {content: 'earlier question', role: 'user' as const},
            {content: 'earlier answer', role: 'assistant' as const},
        ];

        act(() => useCopilotStore.setState({conversationId: 'restored-conversation', messages: restoredMessages}));

        act(() => agent.subscriber?.onTextMessageContentEvent?.({event: {delta: 'late reply'}, textMessageBuffer: ''}));

        await act(async () => {
            agent.resolveRun?.();

            await sendPromise;
        });

        expect(useCopilotStore.getState().messages).toEqual(restoredMessages);
        expect(postTurnCallback).not.toHaveBeenCalled();
    });

    it('streams text and runs post-turn callbacks for the current conversation', async () => {
        const postTurnCallback = vi.fn();

        useCopilotPostTurnRegistry.getState().register(Source.WORKFLOW_EDITOR, postTurnCallback);

        render(
            <CopilotRuntimeProvider>
                <div />
            </CopilotRuntimeProvider>
        );

        const agent = hoisted.agents[0];

        let sendPromise: Promise<void> | undefined;

        act(() => {
            sendPromise = hoisted.runtimeOptions?.onNew({content: [{text: 'hello', type: 'text'}]});
        });

        act(() => useCopilotStore.getState().addMessage({content: '', role: 'assistant'}));

        act(() => agent.subscriber?.onTextMessageContentEvent?.({event: {delta: 'reply'}, textMessageBuffer: ''}));

        await act(async () => {
            agent.resolveRun?.();

            await sendPromise;
        });

        expect(useCopilotStore.getState().messages.at(-1)).toMatchObject({content: 'reply', role: 'assistant'});
        expect(postTurnCallback).toHaveBeenCalled();
    });
});

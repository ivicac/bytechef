import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import useWorkflowTestChatStore from '@/pages/platform/workflow-editor/stores/useWorkflowTestChatStore';
import getChatTriggerName from '@/pages/platform/workflow-editor/utils/getChatTriggerName';
import {ApprovalResolutionContext, ResumeError} from '@/shared/components/ai-chat/approvalResolutionContext';
import {useWorkflowTestStream} from '@/shared/hooks/useWorkflowTestStream';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {hasLastAssistantMessageText} from '@/shared/util/assistant-message-utils';
import {getTestWorkflowStreamPostRequest} from '@/shared/util/testWorkflow-utils';
import {
    AppendMessage,
    AssistantRuntimeProvider,
    AuiConfig,
    CompositeAttachmentAdapter,
    RealtimeVoiceAdapter,
    SimpleImageAttachmentAdapter,
    SimpleTextAttachmentAdapter,
    type SuggestionConfig,
    Suggestions,
    ThreadMessageLike,
    WebSpeechDictationAdapter,
    useExternalStoreRuntime,
} from '@assistant-ui/react';
import {ReactNode, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useShallow} from 'zustand/react/shallow';

const convertMessage = (message: ThreadMessageLike): ThreadMessageLike => {
    return message;
};

const showErrorMessage = (errorMessage: string) => {
    const {appendToLastAssistantMessage, messages, setLastAssistantMessageContent} =
        useWorkflowTestChatStore.getState();

    if (hasLastAssistantMessageText(messages)) {
        appendToLastAssistantMessage(`\n\n${errorMessage}`);
    } else {
        setLastAssistantMessageContent(errorMessage);
    }
};

const WORKFLOW_TEST_CHAT_SUGGESTIONS: SuggestionConfig[] = [
    {label: '', prompt: 'Hello! 👋 How does this work?', title: 'Hello! 👋 How does this work?'},
    {label: '', prompt: 'What can you do?', title: 'What can you do?'},
    {label: '', prompt: 'Give me an example', title: 'Give me an example'},
    {label: '', prompt: 'Help me get started', title: 'Help me get started'},
];

export function WorkflowTestChatRuntimeProvider({
    children,
    voiceAdapter,
}: Readonly<{
    children: ReactNode;
    voiceAdapter?: RealtimeVoiceAdapter;
}>) {
    const [isRunning, setIsRunning] = useState(false);

    const awaitingStreamEventRef = useRef(false);
    const pendingResumeRef = useRef<{reject: (error: Error) => void; resolve: () => void} | null>(null);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const {setWorkflowIsRunning} = useWorkflowEditorStore(
        useShallow((state) => ({
            setWorkflowIsRunning: state.setWorkflowIsRunning,
        }))
    );
    const workflow = useWorkflowDataStore((state) => state.workflow!);
    const {conversationId, messages, setLastAssistantMessageContent, setMessage} = useWorkflowTestChatStore(
        useShallow((state) => ({
            conversationId: state.conversationId,
            messages: state.messages,
            setLastAssistantMessageContent: state.setLastAssistantMessageContent,
            setMessage: state.setMessage,
        }))
    );

    const {error: streamError, setStreamRequest} = useWorkflowTestStream({
        onClosed: () => setIsRunning(false),
        onError: (errorMessage) => {
            awaitingStreamEventRef.current = false;

            showErrorMessage(errorMessage || 'An unexpected error occurred');
            setIsRunning(false);
        },
        onRequestError: (status) => {
            const pending = pendingResumeRef.current;

            pendingResumeRef.current = null;

            pending?.reject(new ResumeError(status));
        },
        onRequestSuccess: () => {
            const pending = pendingResumeRef.current;

            pendingResumeRef.current = null;

            pending?.resolve();
        },
        onResult: () => {
            awaitingStreamEventRef.current = false;

            setIsRunning(false);
        },
        onStreamEnd: () => {
            if (
                awaitingStreamEventRef.current &&
                !hasLastAssistantMessageText(useWorkflowTestChatStore.getState().messages)
            ) {
                setLastAssistantMessageContent('The response ended unexpectedly.');
            }

            awaitingStreamEventRef.current = false;

            setIsRunning(false);
            setWorkflowIsRunning(false);
        },
        workflowId: workflow.id!,
    });

    // Continuation streaming for inline approval cards: resolve through the SSE-negotiated resume endpoint and pipe
    // the resumed run's output back into this conversation. The returned promise settles from the resume request's
    // HTTP outcome so the card only shows success on a 2xx.
    const resolveApproval = useCallback(
        (resumeId: string, payload: Record<string, unknown>) =>
            new Promise<void>((resolve, reject) => {
                pendingResumeRef.current = {reject, resolve};

                setMessage({content: '', role: 'assistant'});
                setIsRunning(true);
                setWorkflowIsRunning(true);
                setStreamRequest({
                    init: {
                        body: JSON.stringify(payload),
                        headers: {'Content-Type': 'application/json'},
                        method: 'POST',
                    },
                    url: `/job/resume/${resumeId}`,
                });
            }),
        [setMessage, setStreamRequest, setWorkflowIsRunning]
    );

    const approvalResolution = useMemo(() => ({resolveApproval}), [resolveApproval]);

    const onNew = async (message: AppendMessage) => {
        if (message.content[0]?.type !== 'text') {
            throw new Error('Only text messages are supported');
        }

        const input = message.content[0].text;

        setMessage({attachments: [...(message.attachments ?? [])], content: input, role: 'user'});
        setIsRunning(true);
        setWorkflowIsRunning(true);

        try {
            // Prepare an empty assistant message so streaming appears immediately
            setMessage({content: '', role: 'assistant'} as ThreadMessageLike);

            const request = getTestWorkflowStreamPostRequest({
                environmentId: currentEnvironmentId,
                id: workflow.id!,
                testWorkflowRequest: {
                    inputs: {
                        [getChatTriggerName(workflow)]: {
                            attachments: message.attachments,
                            conversationId,
                            message: input,
                        },
                    },
                },
            });

            awaitingStreamEventRef.current = true;

            setStreamRequest(request);
        } catch (error) {
            console.error('Failed to build test workflow stream request:', error);

            showErrorMessage('Failed to send your message. Please try again.');
            setIsRunning(false);
            setWorkflowIsRunning(false);
        }
    };

    const runtime = useExternalStoreRuntime({
        adapters: {
            attachments: new CompositeAttachmentAdapter([
                new SimpleImageAttachmentAdapter(),
                new SimpleTextAttachmentAdapter(),
            ]),
            // Browser-native dictation (Web Speech API); replaces the former push-to-talk /transcribe call.
            dictation: new WebSpeechDictationAdapter(),
            ...(voiceAdapter ? {voice: voiceAdapter} : {}),
        },
        convertMessage,
        isRunning,
        messages,
        onNew,
    });

    useEffect(() => {
        if (streamError) {
            showErrorMessage(`The request failed: ${streamError}`);

            setIsRunning(false);
            setWorkflowIsRunning(false);
        }
    }, [setWorkflowIsRunning, streamError]);

    return (
        <ApprovalResolutionContext.Provider value={approvalResolution}>
            <AssistantRuntimeProvider
                config={AuiConfig({suggestions: Suggestions(WORKFLOW_TEST_CHAT_SUGGESTIONS)})}
                runtime={runtime}
            >
                {children}
            </AssistantRuntimeProvider>
        </ApprovalResolutionContext.Provider>
    );
}

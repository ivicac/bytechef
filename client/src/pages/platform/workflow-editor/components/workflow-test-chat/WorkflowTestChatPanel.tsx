import Button from '@/components/Button/Button';
import {Thread} from '@/components/assistant-ui/thread';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {WorkflowTestChatRuntimeProvider} from '@/pages/platform/workflow-editor/components/workflow-test-chat/runtime-providers/WorkflowTestChatRuntimeProvider';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowTestChatStore from '@/pages/platform/workflow-editor/stores/useWorkflowTestChatStore';
import {aiChatDataComponents} from '@/shared/components/ai-chat/messages/aiChatDataComponents';
import useCopilotLayoutShifted from '@/shared/components/copilot/hooks/useCopilotLayoutShifted';
import {useWorkflowTestVoiceSession} from '@/shared/hooks/useWorkflowTestVoiceSession';
import {checkVoiceSupport} from '@/shared/lib/browser-voice/BrowserVoiceSession';
import {createWebhookVoiceAdapter} from '@/shared/lib/voice/ByteChefRealtimeVoiceAdapter';
import {VoiceModeLayout} from '@/shared/lib/voice/VoiceModeLayout';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {AudioLinesIcon, MessageSquareXIcon, SquareIcon, XIcon} from 'lucide-react';
import {useCallback, useEffect, useMemo} from 'react';
import {twMerge} from 'tailwind-merge';
import {useShallow} from 'zustand/react/shallow';

/** The `browser/v1/voiceSession` trigger's own default for `sessionLimitSeconds`. */
const DEFAULT_SESSION_LIMIT_SECONDS = 150;

/** What the server uses when a trigger sets `sessionLimitSeconds` to 0. */
const SERVER_MAX_SESSION_LIMIT_SECONDS = 30 * 60;

interface WorkflowTestVoiceModeButtonPropsI {
    active: boolean;
    disabled?: boolean;
    onStart: () => void;
    onStop: () => void;
    title?: string;
}

const WorkflowTestVoiceModeButton = ({active, disabled, onStart, onStop, title}: WorkflowTestVoiceModeButtonPropsI) => {
    return (
        <button
            aria-label={active ? 'Stop voice session' : 'Start voice session'}
            className="inline-flex size-9 items-center justify-center rounded-full hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40"
            disabled={disabled}
            onClick={active ? onStop : onStart}
            title={title}
            type="button"
        >
            {active ? (
                <SquareIcon aria-hidden="true" className="size-4 fill-red-500 text-red-500" />
            ) : (
                <AudioLinesIcon aria-hidden="true" className="size-4" />
            )}
        </button>
    );
};

const WorkflowTestChatPanel = () => {
    const {
        appendToLastAssistantMessage,
        generateConversationId,
        resetMessages,
        setMessage,
        setWorkflowTestChatPanelOpen,
        workflowTestChatPanelOpen,
    } = useWorkflowTestChatStore(
        useShallow((state) => ({
            appendToLastAssistantMessage: state.appendToLastAssistantMessage,
            generateConversationId: state.generateConversationId,
            resetMessages: state.resetMessages,
            setMessage: state.setMessage,
            setWorkflowTestChatPanelOpen: state.setWorkflowTestChatPanelOpen,
            workflowTestChatPanelOpen: state.workflowTestChatPanelOpen,
        }))
    );

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const workflow = useWorkflowDataStore((state) => state.workflow);

    const copilotLayoutShifted = useCopilotLayoutShifted();

    const voiceUnsupportedReason = useMemo(() => checkVoiceSupport(), []);
    const browserSupportsVoice = voiceUnsupportedReason === null;

    // A workflow is "voice-only" when its trigger is `browser/v1/voiceSession`. In that case the test panel
    // renders <VoiceModeLayout> through the assistant-ui RealtimeVoiceAdapter instead of the chat <Thread> —
    // but only once that trigger's Voice Agent cluster-element slot is actually filled in; an empty slot has
    // nothing to connect to.
    const voiceTrigger = useMemo(
        () => (workflow?.triggers ?? []).find((trigger) => trigger?.type === 'browser/v1/voiceSession'),
        [workflow?.triggers]
    );
    const hasVoiceAgent = !!(voiceTrigger?.clusterElements as {voiceAgent?: unknown} | undefined)?.voiceAgent;
    const isVoiceOnlyWorkflow = !!voiceTrigger;

    // The test panel's voice token endpoint lives at
    // `/api/platform/internal/workflow-tests/{workflowId}/voice-session-token`. `createWebhookVoiceAdapter`
    // appends `/voice-session-token` to the base URL, so we pass the workflow-test base. Only wired once a
    // Voice Agent is configured because non-voice-only workflows use `useWorkflowTestVoiceSession` for the
    // inline composer button instead of the assistant-ui adapter.
    const voiceAdapter = useMemo(() => {
        if (!hasVoiceAgent || !workflow?.id) {
            return undefined;
        }

        const sampleRate = Number((voiceTrigger?.parameters as {sampleRate?: unknown} | undefined)?.sampleRate);

        // The editor test socket resolves the trigger's connections in the environment the editor is showing; without
        // it the server falls back to the first environment.
        return createWebhookVoiceAdapter(
            `/api/platform/internal/workflow-tests/${workflow.id}`,
            sampleRate === 16000 || sampleRate === 24000 ? sampleRate : undefined,
            {environmentId: String(currentEnvironmentId)}
        );
    }, [currentEnvironmentId, hasVoiceAgent, voiceTrigger?.parameters, workflow?.id]);

    // Mirrors the server: a missing value is the trigger's default, 0 is the server's maximum session duration.
    const voiceSessionLimitSeconds = useMemo(() => {
        const sessionLimitSeconds = (voiceTrigger?.parameters as {sessionLimitSeconds?: unknown} | undefined)
            ?.sessionLimitSeconds;

        if (typeof sessionLimitSeconds !== 'number' || Number.isNaN(sessionLimitSeconds)) {
            return DEFAULT_SESSION_LIMIT_SECONDS;
        }

        return sessionLimitSeconds > 0 ? sessionLimitSeconds : SERVER_MAX_SESSION_LIMIT_SECONDS;
    }, [voiceTrigger?.parameters]);

    const {error, start, status, stop} = useWorkflowTestVoiceSession({
        onEvent: (event) => {
            if (event.type === 'transcript_final' && typeof event.text === 'string' && event.text.length > 0) {
                setMessage({content: event.text, role: 'user'});
            } else if (event.type === 'assistant_text' && typeof event.text === 'string') {
                if (event.done) {
                    return;
                }

                appendToLastAssistantMessage(event.text);
            }
        },
        workflowId: workflow?.id ?? '',
    });

    const voiceActive = status === 'active' || status === 'connecting';

    const handlePanelClose = () => {
        if (voiceActive) {
            stop();
        }

        setWorkflowTestChatPanelOpen(false);
    };

    const handleVoiceStart = useCallback(() => {
        if (!workflow?.id) {
            return;
        }

        setMessage({content: '', role: 'assistant'});

        void start();
    }, [setMessage, start, workflow?.id]);

    const composerActions = useMemo(() => {
        if (!workflow?.id || isVoiceOnlyWorkflow) {
            return undefined;
        }

        return (
            <WorkflowTestVoiceModeButton
                active={voiceActive}
                disabled={!browserSupportsVoice}
                onStart={handleVoiceStart}
                onStop={stop}
                title={!browserSupportsVoice ? (voiceUnsupportedReason ?? undefined) : undefined}
            />
        );
    }, [
        browserSupportsVoice,
        handleVoiceStart,
        isVoiceOnlyWorkflow,
        stop,
        voiceActive,
        voiceUnsupportedReason,
        workflow?.id,
    ]);

    useEffect(() => {
        // Only refresh conversationId when the panel transitions to open; firing this on every store change
        // (including the voice-mode flip) overwrites the conversationId mid-session, which severs chat memory.
        if (workflowTestChatPanelOpen) {
            generateConversationId();
        }
    }, [generateConversationId, workflowTestChatPanelOpen]);

    // The same pair the agent playground's reset uses: the runtime provider reads both `messages`
    // and `conversationId` off the store, so clearing one and rotating the other starts a fresh
    // thread in place.
    const handleReset = useCallback(() => {
        resetMessages();
        generateConversationId();
    }, [generateConversationId, resetMessages]);

    if (!workflowTestChatPanelOpen) {
        return <></>;
    }

    return (
        <div
            className={twMerge(
                'absolute inset-y-4 top-[15px] bottom-6 z-10 w-screen max-w-workflow-node-details-panel-width overflow-hidden rounded-lg border border-stroke-neutral-secondary bg-background',
                copilotLayoutShifted ? 'right-[57px]' : 'right-[69px]'
            )}
        >
            <div className="flex h-full flex-col divide-y divide-stroke-neutral-secondary bg-surface-main">
                <header className="flex items-center gap-2 p-4 text-lg font-medium">
                    <span>Playground</span>

                    <div className="ml-auto flex items-center gap-2">
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Button
                                    aria-label="Reset the conversation"
                                    icon={<MessageSquareXIcon />}
                                    onClick={handleReset}
                                    size="iconSm"
                                    variant="ghost"
                                />
                            </TooltipTrigger>

                            <TooltipContent>Reset conversation</TooltipContent>
                        </Tooltip>

                        <Button
                            aria-label="Close the playground panel"
                            icon={<XIcon />}
                            onClick={handlePanelClose}
                            size="iconSm"
                            variant="ghost"
                        />
                    </div>
                </header>

                {error && (
                    <div className="bg-surface-destructive-secondary px-4 py-2 text-xs text-content-destructive">
                        {error}
                    </div>
                )}

                <div className="absolute inset-x-0 top-16 bottom-0">
                    <WorkflowTestChatRuntimeProvider voiceAdapter={voiceAdapter}>
                        {isVoiceOnlyWorkflow ? (
                            hasVoiceAgent ? (
                                <VoiceModeLayout sessionLimitSeconds={voiceSessionLimitSeconds} />
                            ) : (
                                <div className="flex h-full items-center justify-center p-8 text-center text-sm text-muted-foreground">
                                    Add a Voice Agent to the trigger to test with voice
                                </div>
                            )
                        ) : (
                            <Thread composerActions={composerActions} dataComponents={aiChatDataComponents} />
                        )}
                    </WorkflowTestChatRuntimeProvider>
                </div>
            </div>
        </div>
    );
};

export default WorkflowTestChatPanel;

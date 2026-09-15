import {BrowserVoiceSession, VoiceEventI, VoiceSessionStatusType} from '@/shared/lib/browser-voice/BrowserVoiceSession';
import {WorkflowTestApi} from '@/shared/middleware/platform/workflow/test';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useCallback, useEffect, useRef, useState} from 'react';

interface UseWorkflowTestVoiceSessionOptionsI {
    workflowId: string;
    onEvent?: (event: VoiceEventI) => void;
}

interface UseWorkflowTestVoiceSessionResultI {
    status: VoiceSessionStatusType;
    error: string | null;
    isAssistantSpeaking: boolean;
    start: () => Promise<void>;
    stop: () => void;
}

const DEFAULT_SAMPLE_RATE = 16000;

async function fetchVoiceSessionToken(workflowId: string, environmentId: number): Promise<string> {
    // The server authorizes the mint against this environment and binds the token to it.
    const token = await new WorkflowTestApi().issueWorkflowTestVoiceSessionToken({environmentId, workflowId});

    if (!token?.token) {
        throw new Error('Voice session token response missing token field');
    }

    return token.token;
}

function buildWebSocketUrl(workflowId: string, environmentId: number): string {
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const path = `/api/platform/internal/workflow-tests/${encodeURIComponent(workflowId)}/wss`;
    const url = new URL(`${scheme}//${window.location.host}${path}`);

    url.searchParams.set('environmentId', String(environmentId));

    return url.toString();
}

/**
 * Lifecycle hook for an in-editor voice test session.
 *
 * Opens the WS to `/internal/workflow-tests/{workflowId}/wss`, minting a fresh one-shot session token lazily
 * (via `BrowserVoiceSession`'s `mintToken`, called on connect) rather than pre-fetching one, and forwards
 * incoming JSON events to the caller-supplied `onEvent` callback so the chat panel can splice transcripts and
 * assistant text into the same `Thread` it uses for SSE chat.
 *
 * `maxReconnectAttempts: 0` disables `BrowserVoiceSession`'s reconnect loop: unlike the deployed webhook
 * voice endpoint, `WorkflowTestWebSocketHandler` never resumes a dropped session — every close ends the test
 * run for good, so a reconnect attempt here would silently open a brand-new session under a fresh id instead
 * of continuing the interrupted one.
 */
export function useWorkflowTestVoiceSession({
    onEvent,
    workflowId,
}: UseWorkflowTestVoiceSessionOptionsI): UseWorkflowTestVoiceSessionResultI {
    const [status, setStatus] = useState<VoiceSessionStatusType>('idle');
    const [error, setError] = useState<string | null>(null);
    const [isAssistantSpeaking, setIsAssistantSpeaking] = useState(false);

    const sessionRef = useRef<BrowserVoiceSession | null>(null);
    const onEventRef = useRef(onEvent);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const start = useCallback(async () => {
        if (sessionRef.current) {
            return;
        }

        setError(null);

        try {
            const url = buildWebSocketUrl(workflowId, currentEnvironmentId);

            const session = new BrowserVoiceSession({
                maxReconnectAttempts: 0,
                mintToken: () => fetchVoiceSessionToken(workflowId, currentEnvironmentId),
                onEvent: (event) => {
                    if (event.type === 'error' && typeof event.message === 'string') {
                        setError(event.message);
                    }

                    onEventRef.current?.(event);
                },
                onSpeakingChange: setIsAssistantSpeaking,
                onStatusChange: setStatus,
                sampleRate: DEFAULT_SAMPLE_RATE,
                url,
            });

            sessionRef.current = session;

            await session.start();
        } catch (caught) {
            const message = caught instanceof Error ? caught.message : 'Failed to start voice session';

            setError(message);
            setStatus('error');
            sessionRef.current = null;
        }
    }, [currentEnvironmentId, workflowId]);

    const stop = useCallback(() => {
        const session = sessionRef.current;

        if (!session) {
            return;
        }

        session.stop();
        sessionRef.current = null;
    }, []);

    useEffect(() => {
        onEventRef.current = onEvent;
    }, [onEvent]);

    useEffect(() => {
        return () => {
            sessionRef.current?.stop();
            sessionRef.current = null;
        };
    }, []);

    return {error, isAssistantSpeaking, start, status, stop};
}

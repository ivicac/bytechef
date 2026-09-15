import {
    BrowserVoiceSession,
    VoiceEventI,
    VoiceSessionStatusType,
} from '@/lib/BrowserVoiceSession';
import {useCallback, useEffect, useRef, useState} from 'react';

interface UseAutomationChatVoiceSessionOptionsI {
    voiceWebhookUrl: string;
    onEvent?: (event: VoiceEventI) => void;
}

interface UseAutomationChatVoiceSessionResultI {
    status: VoiceSessionStatusType;
    error: string | null;
    isAssistantSpeaking: boolean;
    start: () => Promise<void>;
    stop: () => void;
}

const DEFAULT_SAMPLE_RATE = 16000;

// Normalises whatever URL form the customer passes in. Accepts any of:
//   https://host/webhooks/<id>
//   https://host/webhooks/<id>/
//   https://host/webhooks/<id>/wss            <- customer copy-pasted the WS upgrade URL by mistake
//   https://host/webhooks/<id>/voice-session-token
//   https://host/webhooks/<id>?foo=bar        <- random query strings get dropped
// Returns the canonical base URL (no trailing slash, no /wss or /voice-session-token suffix, no query).
function normaliseVoiceWebhookBase(voiceWebhookUrl: string): string {
    let url: URL;

    try {
        url = new URL(voiceWebhookUrl);
    } catch {
        // Not a valid URL — pass through; callers will surface the error when fetch fails.
        return voiceWebhookUrl;
    }

    url.search = '';
    url.hash = '';

    let pathname = url.pathname.replace(/\/+$/, '');

    if (pathname.endsWith('/wss')) {
        pathname = pathname.slice(0, -'/wss'.length);
    } else if (pathname.endsWith('/voice-session-token')) {
        pathname = pathname.slice(0, -'/voice-session-token'.length);
    }

    url.pathname = pathname;

    return url.toString();
}

function deriveTokenEndpoint(voiceWebhookUrl: string): string {
    return `${normaliseVoiceWebhookBase(voiceWebhookUrl)}/voice-session-token`;
}

// WITHOUT a sessionToken or any other session query param — BrowserVoiceSession mints its own token (lazily,
// via `mintToken`, on start and again on every reconnect attempt) and appends both `sessionToken` and
// `sampleRate` itself once it actually opens the socket.
function deriveWsUrl(voiceWebhookUrl: string): string {
    const base = new URL(normaliseVoiceWebhookBase(voiceWebhookUrl));
    const wsScheme = base.protocol === 'https:' ? 'wss:' : 'ws:';

    return `${wsScheme}//${base.host}${base.pathname}/wss`;
}

async function fetchVoiceSessionToken(tokenEndpoint: string): Promise<string> {
    const response = await fetch(tokenEndpoint, {method: 'POST'});

    if (!response.ok) {
        throw new Error(`Failed to mint voice session token (HTTP ${response.status})`);
    }

    const body = (await response.json()) as {token?: string};

    if (!body?.token) {
        throw new Error('Voice session token response missing token field');
    }

    return body.token;
}

export function useAutomationChatVoiceSession({
    onEvent,
    voiceWebhookUrl,
}: UseAutomationChatVoiceSessionOptionsI): UseAutomationChatVoiceSessionResultI {
    const [status, setStatus] = useState<VoiceSessionStatusType>('idle');
    const [error, setError] = useState<string | null>(null);
    const [isAssistantSpeaking, setIsAssistantSpeaking] = useState(false);

    const sessionRef = useRef<BrowserVoiceSession | null>(null);
    const onEventRef = useRef(onEvent);

    useEffect(() => {
        onEventRef.current = onEvent;
    }, [onEvent]);

    const start = useCallback(async () => {
        if (sessionRef.current) {
            return;
        }

        setError(null);

        try {
            const tokenEndpoint = deriveTokenEndpoint(voiceWebhookUrl);
            const wsUrl = deriveWsUrl(voiceWebhookUrl);

            const session = new BrowserVoiceSession({
                mintToken: () => fetchVoiceSessionToken(tokenEndpoint),
                onEvent: (event) => {
                    if (event.type === 'error' && typeof event.message === 'string') {
                        setError(event.message);
                    }

                    onEventRef.current?.(event);
                },
                onSpeakingChange: setIsAssistantSpeaking,
                onStatusChange: (nextStatus) => {
                    setStatus(nextStatus);

                    // Unlike a thrown error (still caught below, for a failure before start() is even
                    // called), a mint or socket failure inside start()/openSocket() resolves normally —
                    // BrowserVoiceSession reports it via this callback instead of rejecting. Without
                    // clearing the ref here, a dead session (status 'error'/'closed') would stay in
                    // sessionRef forever and the `if (sessionRef.current) return;` guard above would block
                    // every future start() call.
                    if (nextStatus === 'error' || nextStatus === 'closed') {
                        sessionRef.current = null;
                    }
                },
                sampleRate: DEFAULT_SAMPLE_RATE,
                url: wsUrl,
            });

            sessionRef.current = session;

            await session.start();
        } catch (caught) {
            const message = caught instanceof Error ? caught.message : 'Failed to start voice session';

            setError(message);
            setStatus('error');
            sessionRef.current = null;
        }
    }, [voiceWebhookUrl]);

    const stop = useCallback(() => {
        const session = sessionRef.current;

        if (!session) {
            return;
        }

        session.stop();
        sessionRef.current = null;
    }, []);

    useEffect(() => {
        return () => {
            sessionRef.current?.stop();
            sessionRef.current = null;
        };
    }, []);

    return {error, isAssistantSpeaking, start, status, stop};
}

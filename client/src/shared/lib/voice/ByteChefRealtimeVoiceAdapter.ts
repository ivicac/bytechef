import {BrowserVoiceSession} from '@/shared/lib/browser-voice/BrowserVoiceSession';
import {createVoiceSession} from '@assistant-ui/react';

import {voiceActivityStore} from './useVoiceActivityStore';

import type {RealtimeVoiceAdapter} from '@assistant-ui/react';

interface AdapterConfigI {
    /**
     * Extra query parameters both the token request and the socket URL carry, e.g. the editor test path's
     * `environmentId`, which the server authorizes the mint against and binds the token to.
     */
    queryParameters?: Record<string, string>;
    sampleRate?: 16000 | 24000;
    tokenUrl: string;
}

function withQueryParameters(url: URL, queryParameters: Record<string, string>): URL {
    for (const [name, value] of Object.entries(queryParameters)) {
        url.searchParams.set(name, value);
    }

    return url;
}

/**
 * Derives the WebSocket base URL from the token endpoint URL — WITHOUT a session token or any other session
 * query param; `BrowserVoiceSession` mints and appends those itself (on connect, and again on every
 * reconnect attempt) via `mintToken`.
 *
 * Given a tokenUrl like {@code http://host/webhooks/{id}/voice-session-token}, returns
 * {@code ws://host/webhooks/{id}/wss}.
 */
function deriveWsUrl(tokenUrl: string, queryParameters: Record<string, string> = {}): string {
    let baseUrl: URL;

    try {
        baseUrl = new URL(tokenUrl);
    } catch {
        // Relative URL — use current origin
        baseUrl = new URL(tokenUrl, window.location.origin);
    }

    let pathname = baseUrl.pathname.replace(/\/+$/, '');

    if (pathname.endsWith('/voice-session-token')) {
        pathname = pathname.slice(0, -'/voice-session-token'.length);
    }

    baseUrl.pathname = `${pathname}/wss`;
    baseUrl.protocol = baseUrl.protocol === 'https:' ? 'wss:' : 'ws:';

    return withQueryParameters(baseUrl, queryParameters).toString();
}

export class ByteChefRealtimeVoiceAdapter implements RealtimeVoiceAdapter {
    constructor(private readonly config: AdapterConfigI) {}

    /** The token endpoint, with any configured query parameters; a URL without them is passed through untouched. */
    private tokenRequestUrl(): string {
        const {queryParameters, tokenUrl} = this.config;

        if (!queryParameters || Object.keys(queryParameters).length === 0) {
            return tokenUrl;
        }

        let url: URL;

        try {
            url = new URL(tokenUrl);
        } catch {
            url = new URL(tokenUrl, window.location.origin);
        }

        return withQueryParameters(url, queryParameters).toString();
    }

    connect(options: {abortSignal?: AbortSignal}): RealtimeVoiceAdapter.Session {
        // A previous session's tool activity or end reason must never bleed into the next one — reset
        // synchronously here, before the async session setup below even runs.
        const generation = voiceActivityStore.getState().beginSession();

        return createVoiceSession(options, async (helpers) => {
            const mintToken = async (): Promise<string> => {
                const response = await fetch(this.tokenRequestUrl(), {method: 'POST'});

                if (!response.ok) throw new Error(`Voice token request failed: ${response.statusText}`);

                const {token} = (await response.json()) as {token: string};

                return token;
            };

            const session = new BrowserVoiceSession({
                mintToken,
                onEvent: (event) => {
                    if (event.type === 'transcript_interim') {
                        helpers.emitTranscript({isFinal: false, role: 'user', text: event.text ?? ''});
                    }

                    if (event.type === 'transcript_final') {
                        helpers.emitTranscript({isFinal: true, role: 'user', text: event.text ?? ''});
                    }

                    if (event.type === 'assistant_text') {
                        helpers.emitTranscript({
                            isFinal: !!event.done,
                            role: 'assistant',
                            text: event.text ?? '',
                        });
                    }

                    // assistant-ui's RealtimeVoiceAdapter has no dedicated status/tool-call channel (only
                    // setStatus/end/emitTranscript/emitMode/emitVolume), so tool activity goes through
                    // `voiceActivityStore` instead — `VoiceModeLayout`'s status line is the only place that
                    // displays it. Emitting it as an assistant transcript line too would just show the same
                    // information twice.
                    if (event.type === 'tool_call') {
                        voiceActivityStore.getState().setActivity({tool: String(event.name ?? '')}, generation);
                    }

                    if (event.type === 'tool_result') {
                        voiceActivityStore.getState().setActivity('idle', generation);
                    }

                    if (event.type === 'session_end') {
                        voiceActivityStore
                            .getState()
                            .setEndReason(typeof event.reason === 'string' ? event.reason : null, generation);

                        helpers.end('finished');
                    }
                },
                onSpeakingChange: (isAssistantSpeaking) =>
                    helpers.emitMode(isAssistantSpeaking ? 'speaking' : 'listening'),
                onStatusChange: (status) => {
                    if (status === 'active') {
                        helpers.setStatus({type: 'running'});
                        voiceActivityStore.getState().setActivity('idle', generation);
                    }

                    if (status === 'reconnecting') {
                        // Stay "running" from assistant-ui's perspective — the mic keeps capturing during a
                        // reconnect attempt, only the socket is down — but switch the mode back to listening
                        // so a reconnect can't get stuck showing the assistant as still speaking.
                        helpers.setStatus({type: 'running'});
                        helpers.emitMode('listening');
                        voiceActivityStore.getState().setActivity('reconnecting', generation);
                    }

                    if (status === 'error' || status === 'closed') {
                        // The session is over (a reconnect ran out of attempts, or a close landed mid tool
                        // call with no matching `tool_result`) — a leftover 'reconnecting'/{tool} activity
                        // would otherwise stick in the layout until the next connect(). `endReason` is left
                        // alone: a `session_end` event may have just set it, and the layout still needs to
                        // show it for 5 s.
                        voiceActivityStore.getState().setActivity('idle', generation);
                    }

                    if (status === 'error') helpers.end('error');
                },
                onVolume: (level) => helpers.emitVolume(level),
                sampleRate: this.config.sampleRate ?? 16000,
                url: deriveWsUrl(this.config.tokenUrl, this.config.queryParameters),
            });

            await session.start();

            return {
                disconnect: () => session.stop(),
                mute: () => session.setMuted(true),
                unmute: () => session.setMuted(false),
            };
        });
    }
}

export function createWebhookVoiceAdapter(
    webhookUrl: string,
    sampleRate?: 16000 | 24000,
    queryParameters?: Record<string, string>
): RealtimeVoiceAdapter {
    return new ByteChefRealtimeVoiceAdapter({
        queryParameters,
        sampleRate,
        tokenUrl: `${webhookUrl}/voice-session-token`,
    });
}

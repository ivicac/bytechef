import {act, renderHook} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {useAutomationChatVoiceSession} from './useAutomationChatVoiceSession';

/**
 * Mocks `BrowserVoiceSession` for the `sessionRef` retry regression suite near the bottom of this file — kept
 * at the top level of the module (rather than nested inside that `describe`) since `vi.hoisted`/`vi.mock`
 * calls are hoisted above every import regardless of where they're written, and Vitest warns (soon errors)
 * when the written position doesn't match that actual execution order.
 *
 * The mock's `start()` mirrors the real class's contract: it drives `onStatusChange` through 'connecting'
 * then either 'active' or 'error' depending on whether the hook's `mintToken` resolves or rejects, and never
 * throws — matching `BrowserVoiceSession.openSocket()`, which reports a mint failure via `onStatusChange`
 * instead of rejecting the `start()` promise. This exercises the hook's real `mintToken`/URL-derivation
 * wiring; only the WebSocket/audio internals are stubbed away.
 */
const {BrowserVoiceSessionMock} = vi.hoisted(() => ({
    BrowserVoiceSessionMock: vi.fn(),
}));

vi.mock('@/lib/BrowserVoiceSession', () => {
    class BrowserVoiceSession {
        private readonly options: {
            mintToken: () => Promise<string>;
            onStatusChange?: (status: string) => void;
        };

        readonly stop = vi.fn();
        readonly setMuted = vi.fn();

        constructor(options: {mintToken: () => Promise<string>; onStatusChange?: (status: string) => void}) {
            this.options = options;
            BrowserVoiceSessionMock(options);
        }

        start = vi.fn(async () => {
            this.options.onStatusChange?.('connecting');

            try {
                await this.options.mintToken();

                this.options.onStatusChange?.('active');
            } catch {
                // Mirrors the real class: a mint failure is reported through onStatusChange, never thrown
                // out of start().
                this.options.onStatusChange?.('error');
            }
        });
    }

    return {BrowserVoiceSession};
});

// The hook itself is hard to test in isolation (depends on getUserMedia, AudioContext, WebSocket). The URL
// normalisation logic lives inside the hook file as module-scoped functions; we re-derive the same canonical
// shape here against representative inputs to lock the behaviour in.
//
// The ws URL now carries neither a token nor a sampleRate: BrowserVoiceSession mints its own token (via
// `mintToken`, lazily, on start and on every reconnect attempt) and appends both `sessionToken` and
// `sampleRate` itself when it actually opens the socket — see BrowserVoiceSession.ts's `buildSocketUrl`.

const VARIANTS = [
    {input: 'https://bytechef.io/webhooks/abc123', token: 'https://bytechef.io/webhooks/abc123/voice-session-token', ws: 'wss://bytechef.io/webhooks/abc123/wss'},
    {input: 'https://bytechef.io/webhooks/abc123/', token: 'https://bytechef.io/webhooks/abc123/voice-session-token', ws: 'wss://bytechef.io/webhooks/abc123/wss'},
    {input: 'https://bytechef.io/webhooks/abc123/wss', token: 'https://bytechef.io/webhooks/abc123/voice-session-token', ws: 'wss://bytechef.io/webhooks/abc123/wss'},
    {input: 'https://bytechef.io/webhooks/abc123/voice-session-token', token: 'https://bytechef.io/webhooks/abc123/voice-session-token', ws: 'wss://bytechef.io/webhooks/abc123/wss'},
    {input: 'https://bytechef.io/webhooks/abc123?foo=bar', token: 'https://bytechef.io/webhooks/abc123/voice-session-token', ws: 'wss://bytechef.io/webhooks/abc123/wss'},
    {input: 'http://localhost:8080/webhooks/abc', token: 'http://localhost:8080/webhooks/abc/voice-session-token', ws: 'ws://localhost:8080/webhooks/abc/wss'},
];

function normaliseVoiceWebhookBase(voiceWebhookUrl: string): string {
    let url: URL;

    try {
        url = new URL(voiceWebhookUrl);
    } catch {
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

function deriveWsUrl(voiceWebhookUrl: string): string {
    const base = new URL(normaliseVoiceWebhookBase(voiceWebhookUrl));
    const wsScheme = base.protocol === 'https:' ? 'wss:' : 'ws:';

    return `${wsScheme}//${base.host}${base.pathname}/wss`;
}

describe('useAutomationChatVoiceSession URL derivation', () => {
    for (const variant of VARIANTS) {
        it(`derives token + ws URL from ${variant.input}`, () => {
            expect(deriveTokenEndpoint(variant.input)).toBe(variant.token);

            const wsUrl = deriveWsUrl(variant.input);

            // The base url carries neither a token nor a resume marker — BrowserVoiceSession appends both
            // itself once it actually opens the socket.
            expect(wsUrl).toBe(variant.ws);
            expect(wsUrl).not.toContain('sessionToken=');
        });
    }

    it('passes invalid URLs through to surface a fetch-time error rather than throwing here', () => {
        expect(() => deriveTokenEndpoint('not a url')).not.toThrow();
        expect(deriveTokenEndpoint('not a url')).toBe('not a url/voice-session-token');
    });
});

/**
 * Regression coverage for the `sessionRef` retry fix: `BrowserVoiceSession.start()` catches a mint,
 * getUserMedia, or addModule failure internally and resolves normally (reporting the outcome via
 * `onStatusChange('error')` instead of rejecting) — see BrowserVoiceSession.ts's `openSocket()`. Before the
 * fix, the hook only cleared `sessionRef.current` in its `catch` block, which a resolved `start()` never
 * reaches, so a failed session stuck around forever and `if (sessionRef.current) return;` silently blocked
 * every later `start()` call — the user could never retry.
 *
 * `BrowserVoiceSession` is mocked with `vi.hoisted` above (the same way the client's adapter/hook tests mock
 * it) — see the module-level comment near the top of this file for why it's declared up there instead of
 * nested in this `describe`.
 */
describe('useAutomationChatVoiceSession retry after a failed start', () => {
    beforeEach(() => {
        BrowserVoiceSessionMock.mockClear();
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
    });

    it('lets the user retry after mintToken fails, constructing a new session that reaches active', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn()
                .mockResolvedValueOnce({ok: false, status: 401, statusText: 'Unauthorized'})
                .mockResolvedValueOnce({json: () => Promise.resolve({token: 'tkn'}), ok: true})
        );

        const {result} = renderHook(() =>
            useAutomationChatVoiceSession({voiceWebhookUrl: 'https://bytechef.io/webhooks/abc123'})
        );

        await act(async () => {
            await result.current.start();
        });

        expect(result.current.status).toBe('error');
        expect(BrowserVoiceSessionMock).toHaveBeenCalledTimes(1);

        // The fix: a session that ended in 'error' must not stay stuck in sessionRef — retrying start()
        // constructs a brand-new BrowserVoiceSession instead of silently returning.
        await act(async () => {
            await result.current.start();
        });

        expect(BrowserVoiceSessionMock).toHaveBeenCalledTimes(2);
        expect(result.current.status).toBe('active');
        expect(result.current.error).toBeNull();
    });

    it('does not clear the session while it is reconnecting, so a concurrent start() is a no-op', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({json: () => Promise.resolve({token: 'tkn'}), ok: true}));

        const {result} = renderHook(() =>
            useAutomationChatVoiceSession({voiceWebhookUrl: 'https://bytechef.io/webhooks/abc123'})
        );

        await act(async () => {
            await result.current.start();
        });

        expect(result.current.status).toBe('active');
        expect(BrowserVoiceSessionMock).toHaveBeenCalledTimes(1);

        // Simulate BrowserVoiceSession itself moving to 'reconnecting' after the socket drops mid-session —
        // this happens well after start() has already resolved, via the same onStatusChange callback.
        const [[firstOptions]] = BrowserVoiceSessionMock.mock.calls as [
            [{onStatusChange?: (status: string) => void}],
        ];

        act(() => {
            firstOptions.onStatusChange?.('reconnecting');
        });

        expect(result.current.status).toBe('reconnecting');

        await act(async () => {
            await result.current.start();
        });

        // Negative case: 'reconnecting' must NOT clear sessionRef — a second start() call while reconnecting
        // stays a no-op (the guard at the top of start() returns early) instead of racing a second session
        // against the one already trying to reconnect.
        expect(BrowserVoiceSessionMock).toHaveBeenCalledTimes(1);
        expect(result.current.status).toBe('reconnecting');
    });
});

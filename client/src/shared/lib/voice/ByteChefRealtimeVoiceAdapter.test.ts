import {beforeEach, describe, expect, it, vi} from 'vitest';

import {ByteChefRealtimeVoiceAdapter} from './ByteChefRealtimeVoiceAdapter';
import {voiceActivityStore} from './useVoiceActivityStore';

import type {VoiceEventI, VoiceSessionStatusType} from '@/shared/lib/browser-voice/BrowserVoiceSession';
import type {RealtimeVoiceAdapter} from '@assistant-ui/react';

const {BrowserVoiceSessionMock} = vi.hoisted(() => ({
    BrowserVoiceSessionMock: vi.fn(),
}));

vi.mock('@/shared/lib/browser-voice/BrowserVoiceSession', () => {
    class BrowserVoiceSession {
        readonly start = vi.fn().mockResolvedValue(undefined);
        readonly stop = vi.fn();
        readonly setMuted = vi.fn();

        constructor(options: {onStatusChange?: (status: string) => void}) {
            BrowserVoiceSessionMock(options);

            setTimeout(() => options.onStatusChange?.('active'), 0);
        }
    }

    return {BrowserVoiceSession};
});

/**
 * Connects the adapter, stubs `fetch`, and waits for the mock `BrowserVoiceSession`'s auto-fired initial
 * `active` status to have actually reached the session — the same async chain (session construction ->
 * queued status emit) the pre-existing test below waits on. Without this, a test that manually invokes
 * `passedOptions.onStatusChange`/`onEvent` right after `connect()` races that queued `setTimeout(0)`
 * callback under full-suite load.
 */
async function connectAndAwaitActive(adapter: ByteChefRealtimeVoiceAdapter) {
    const session = adapter.connect({});
    const statuses: RealtimeVoiceAdapter.Status[] = [];

    session.onStatusChange((status) => statuses.push(status));

    await vi.waitFor(() => {
        expect(statuses).toContainEqual({type: 'running'});
    });

    const [passedOptions] = BrowserVoiceSessionMock.mock.calls.at(-1) as [
        {onEvent?: (event: VoiceEventI) => void; onStatusChange?: (status: VoiceSessionStatusType) => void},
    ];

    return {passedOptions, session};
}

describe('ByteChefRealtimeVoiceAdapter', () => {
    beforeEach(() => {
        voiceActivityStore.getState().reset();
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({json: () => Promise.resolve({token: 'tkn'}), ok: true}));
        BrowserVoiceSessionMock.mockClear();
    });

    it('opens the socket without the token in the base url and mints lazily', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({
            tokenUrl: 'http://host/webhooks/abc/voice-session-token',
        });

        const session = adapter.connect({});
        const statuses: RealtimeVoiceAdapter.Status[] = [];

        session.onStatusChange((status) => statuses.push(status));

        await vi.waitFor(() => {
            expect(BrowserVoiceSessionMock).toHaveBeenCalledWith(
                expect.objectContaining({mintToken: expect.any(Function), url: 'ws://host/webhooks/abc/wss'})
            );
        });

        const [[passedOptions]] = BrowserVoiceSessionMock.mock.calls;

        // The base url carries neither a token nor a resume marker — both are appended by BrowserVoiceSession
        // itself once it actually opens the socket (and, for resumeSessionId, only on a later reconnect).
        expect(passedOptions.url).not.toContain('sessionToken=');
        expect(passedOptions.url).not.toContain('resumeSessionId=');

        // Token minting is lazy: constructing the session (and the adapter's `connect()` call) must not have
        // fetched a token yet — only invoking `mintToken()` does.
        expect(global.fetch).not.toHaveBeenCalled();

        const token = await passedOptions.mintToken();

        expect(token).toBe('tkn');
        expect(global.fetch).toHaveBeenCalledWith(
            'http://host/webhooks/abc/voice-session-token',
            expect.objectContaining({method: 'POST'})
        );

        // The running status arrives through an async chain (session construction -> queued status emit); a
        // fixed sleep races it under full-suite load, so wait for the condition instead.
        await vi.waitFor(() => {
            expect(statuses).toContainEqual({type: 'running'});
        });
    });

    it('appends the configured query parameters to the socket url and the token request', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({
            queryParameters: {environmentId: '3'},
            tokenUrl: 'http://host/api/platform/internal/workflow-tests/wf-1/voice-session-token',
        });

        adapter.connect({});

        await vi.waitFor(() => {
            expect(BrowserVoiceSessionMock).toHaveBeenCalledWith(
                expect.objectContaining({
                    url: 'ws://host/api/platform/internal/workflow-tests/wf-1/wss?environmentId=3',
                })
            );
        });

        const [passedOptions] = BrowserVoiceSessionMock.mock.calls.at(-1) as [{mintToken: () => Promise<string>}];

        await passedOptions.mintToken();

        expect(global.fetch).toHaveBeenCalledWith(
            'http://host/api/platform/internal/workflow-tests/wf-1/voice-session-token?environmentId=3',
            expect.objectContaining({method: 'POST'})
        );
    });

    it('writes tool activity to the voice activity store instead of emitting an assistant transcript', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({tokenUrl: 'http://host/webhooks/abc/voice-session-token'});
        const {passedOptions, session} = await connectAndAwaitActive(adapter);
        const transcripts: RealtimeVoiceAdapter.TranscriptItem[] = [];

        session.onTranscript((item) => transcripts.push(item));

        passedOptions.onEvent?.({name: 'lookupOrder', type: 'tool_call'});

        expect(voiceActivityStore.getState().activity).toEqual({tool: 'lookupOrder'});
        expect(transcripts).toEqual([]);

        passedOptions.onEvent?.({name: 'lookupOrder', type: 'tool_result'});

        expect(voiceActivityStore.getState().activity).toBe('idle');
        expect(transcripts).toEqual([]);
    });

    it('resets a previous session end reason on connect, and records the reason on session_end', async () => {
        voiceActivityStore.getState().setEndReason('previous_reason');

        const adapter = new ByteChefRealtimeVoiceAdapter({tokenUrl: 'http://host/webhooks/abc/voice-session-token'});

        const session = adapter.connect({});

        // The reset happens synchronously in connect(), before the async session setup even runs.
        expect(voiceActivityStore.getState().endReason).toBeNull();

        const statuses: RealtimeVoiceAdapter.Status[] = [];

        session.onStatusChange((status) => statuses.push(status));

        await vi.waitFor(() => {
            expect(statuses).toContainEqual({type: 'running'});
        });

        const [[passedOptions]] = BrowserVoiceSessionMock.mock.calls as [[{onEvent?: (event: VoiceEventI) => void}]];

        passedOptions.onEvent?.({reason: 'silence_timeout', type: 'session_end'});

        expect(voiceActivityStore.getState().endReason).toBe('silence_timeout');
    });

    it('ignores a late tool event from a session that a newer connect replaced', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({tokenUrl: 'http://host/webhooks/abc/voice-session-token'});
        const {passedOptions: firstSessionOptions} = await connectAndAwaitActive(adapter);

        await connectAndAwaitActive(adapter);

        firstSessionOptions.onEvent?.({name: 'lookupOrder', type: 'tool_call'});
        firstSessionOptions.onEvent?.({reason: 'provider_closed', type: 'session_end'});

        expect(voiceActivityStore.getState().activity).toBe('idle');
        expect(voiceActivityStore.getState().endReason).toBeNull();
    });

    it('reflects reconnecting and active status changes in the activity store', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({tokenUrl: 'http://host/webhooks/abc/voice-session-token'});
        const {passedOptions} = await connectAndAwaitActive(adapter);

        passedOptions.onStatusChange?.('reconnecting');

        expect(voiceActivityStore.getState().activity).toBe('reconnecting');

        passedOptions.onStatusChange?.('active');

        expect(voiceActivityStore.getState().activity).toBe('idle');
    });

    it('clears a stuck "reconnecting" activity once a reconnect attempt ends in error', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({tokenUrl: 'http://host/webhooks/abc/voice-session-token'});
        const {passedOptions} = await connectAndAwaitActive(adapter);

        passedOptions.onStatusChange?.('reconnecting');

        expect(voiceActivityStore.getState().activity).toBe('reconnecting');

        passedOptions.onStatusChange?.('error');

        expect(voiceActivityStore.getState().activity).toBe('idle');
    });

    it('clears a stuck tool-call activity when the session closes, preserving the end reason', async () => {
        const adapter = new ByteChefRealtimeVoiceAdapter({tokenUrl: 'http://host/webhooks/abc/voice-session-token'});
        const {passedOptions} = await connectAndAwaitActive(adapter);

        passedOptions.onEvent?.({name: 'lookupOrder', type: 'tool_call'});

        expect(voiceActivityStore.getState().activity).toEqual({tool: 'lookupOrder'});

        // A close landing mid tool call (the socket drops while a tool is in flight, so no `tool_result`
        // ever arrives): `session_end` fires first (setting the end reason), then the status change to
        // `closed` follows.
        passedOptions.onEvent?.({reason: 'silence_timeout', type: 'session_end'});
        passedOptions.onStatusChange?.('closed');

        expect(voiceActivityStore.getState().activity).toBe('idle');
        expect(voiceActivityStore.getState().endReason).toBe('silence_timeout');
    });
});

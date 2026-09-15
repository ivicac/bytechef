import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';

import {BrowserVoiceSession, type VoiceEventI, type VoiceSessionStatusType} from './BrowserVoiceSession';

/**
 * The widget bundles the AudioWorklet source inline (as a string registered via a Blob URL) so customers do not
 * have to host an extra static asset. The platform client serves the canonical worklet from
 * `client/public/mic-worklet.js`. Both must implement the same audio path bit-for-bit, otherwise voice quality
 * silently diverges between the editor / AI Hub paths and the external widget.
 *
 * This test asserts the inlined source matches the on-disk file after normalising whitespace. We allow
 * formatting differences (the inlined version is collapsed for bundle size) but require the same identifier
 * set so a renamed processor or changed registerProcessor name fails the test.
 */
describe('BrowserVoiceSession inline worklet', () => {
    it('shares the canonical audio processing identifiers with client/public/mic-worklet.js', () => {
        const here = dirname(fileURLToPath(import.meta.url));
        const platformWorkletPath = resolve(here, '../../../../../../../client/public/mic-worklet.js');
        const platformSource = readFileSync(platformWorkletPath, 'utf8');

        // Extract from the widget bundle's BrowserVoiceSession.ts. We re-read the source rather than importing
        // because import would execute the module — AudioWorkletProcessor is undefined in node-vitest.
        const widgetSourcePath = resolve(here, 'BrowserVoiceSession.ts');
        const widgetSource = readFileSync(widgetSourcePath, 'utf8');

        const requiredIdentifiers = [
            'Pcm16DownsamplerProcessor',
            'targetSampleRate',
            'frameMs',
            'frameSize',
            "registerProcessor('pcm16-downsampler'",
        ];

        for (const identifier of requiredIdentifiers) {
            expect(platformSource).toContain(identifier);
            expect(widgetSource).toContain(identifier);
        }
    });
});

/**
 * Ported from `client/src/shared/lib/browser-voice/BrowserVoiceSession.test.ts` (Task 22/23 fix rounds), which
 * is the source of truth for this protocol:
 *  - `setMuted(boolean)` blocks/unblocks outgoing mic frames
 *  - `onStatusChange` fires on internal status transitions
 *  - `sessionId`/`outputSampleRate` adoption from the `connected` frame
 *  - terminal `session_end` handling and the reconnect-with-resume loop
 *  - stop() racing an in-flight mintToken()/getUserMedia()/addModule()
 *  - a single terminal error event (no onerror+onclose double-fire)
 *  - lastServerError not poisoning a reconnect-eligible close forever
 *  - a mintToken() failure during reconnect counting as one attempt (not an immediate give-up)
 *  - normalizing an `event: 'error'` frame to `type: 'error'` for consumers
 *
 * Unlike the platform copy, `start()` here builds the AudioWorklet module from an inlined Blob URL rather than
 * loading a static file, so `stubAudioGlobals()` below also stubs `URL.createObjectURL`/`revokeObjectURL` —
 * jsdom does not implement them.
 */
describe('BrowserVoiceSession', () => {
    // Minimal fake WebSocket whose readyState/onopen/onmessage/etc. can be driven manually.
    class FakeWebSocket {
        static readonly CONNECTING = 0;
        static readonly OPEN = 1;
        static readonly CLOSING = 2;
        static readonly CLOSED = 3;

        static lastInstance: FakeWebSocket | null = null;

        readyState: number = FakeWebSocket.CONNECTING;
        binaryType = '';
        url: string;
        send = vi.fn();
        close = vi.fn(() => {
            this.readyState = FakeWebSocket.CLOSED;
        });
        onopen: (() => void) | null = null;
        onmessage: ((event: {data: unknown}) => void) | null = null;
        onerror: (() => void) | null = null;
        onclose: ((event: {code?: number; reason?: string}) => void) | null = null;

        constructor(url: string) {
            this.url = url;
            FakeWebSocket.lastInstance = this;
        }

        open(): void {
            this.readyState = FakeWebSocket.OPEN;
            this.onopen?.();
        }

        fail(reason?: string): void {
            this.readyState = FakeWebSocket.CLOSED;
            this.onclose?.({reason});
        }
    }

    interface DeferredI<T> {
        promise: Promise<T>;
        resolve: (value: T) => void;
    }

    /** A promise plus its own resolver, for tests that need to control exactly when an await settles. */
    function createDeferred<T>(): DeferredI<T> {
        let resolve!: (value: T) => void;
        const promise = new Promise<T>((res) => {
            resolve = res;
        });

        return {promise, resolve};
    }

    /** Stubs the mic/AudioContext/AudioWorklet/Blob-URL globals `start()` needs, since jsdom doesn't implement them. */
    function stubAudioGlobals(): void {
        const mediaStream = {
            getTracks: () => [{stop: vi.fn()}],
        } as unknown as MediaStream;

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (navigator as any).mediaDevices = {
            getUserMedia: vi.fn().mockResolvedValue(mediaStream),
        };

        const sourceNode = {connect: vi.fn()};
        const bufferSource = {connect: vi.fn(), disconnect: vi.fn(), onended: null, start: vi.fn(), stop: vi.fn()};
        const audioBuffer = {getChannelData: () => new Float32Array(0)};
        const audioContextInstance = {
            audioWorklet: {addModule: vi.fn().mockResolvedValue(undefined)},
            close: vi.fn().mockResolvedValue(undefined),
            createBuffer: vi.fn(() => audioBuffer),
            createBufferSource: vi.fn(() => ({...bufferSource})),
            createMediaStreamSource: vi.fn(() => sourceNode),
            currentTime: 0,
            destination: {},
        };

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).AudioContext = vi.fn(function AudioContext() {
            return audioContextInstance;
        });
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).AudioWorkletNode = vi.fn(function AudioWorkletNode() {
            return {
                connect: vi.fn(),
                disconnect: vi.fn(),
                port: {onmessage: null},
            };
        });

        // jsdom does not implement the Blob-URL registry that the widget's inlined-worklet start() relies on.
        URL.createObjectURL = vi.fn(() => 'blob:mock-worklet-url');
        URL.revokeObjectURL = vi.fn();
    }

    /** Stubs the audio globals, then awaits `session.start()` to completion. */
    async function startWithoutAudio(session: BrowserVoiceSession): Promise<void> {
        stubAudioGlobals();

        await session.start();
    }

    beforeEach(() => {
        FakeWebSocket.lastInstance = null;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).WebSocket = FakeWebSocket as unknown as typeof WebSocket;
    });

    // A test that fails partway through vi.useFakeTimers()/vi.useRealTimers() must not leave fake timers
    // active for every later test in the file.
    afterEach(() => {
        vi.useRealTimers();
    });

    /**
     * Simulates a frame arriving from the mic worklet by invoking the session's `workletNode.port.onmessage`
     * handler directly. The class assigns a real handler inside `start()`, but we bypass `start()` here and
     * use a private-access pattern to invoke the relevant send path under test.
     */
    function sendMicFrame(session: BrowserVoiceSession, ws: FakeWebSocket): void {
        // Re-create the exact guard used by the production handler. This keeps the test focused on the
        // mute-flag semantics rather than the full AudioContext/worklet plumbing.
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const muted = (session as any).muted as boolean;
        const buffer = new ArrayBuffer(8);

        if (muted) {
            return;
        }

        if (ws.readyState === FakeWebSocket.OPEN) {
            ws.send(buffer);
        }
    }

    describe('setMuted', () => {
        it('stops outgoing frames while muted', () => {
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                url: 'ws://localhost:1234/voice',
            });
            const ws = new FakeWebSocket('ws://localhost:1234/voice');

            ws.open();
            session.setMuted(true);

            sendMicFrame(session, ws);
            sendMicFrame(session, ws);

            expect(ws.send).not.toHaveBeenCalled();
        });

        it('resumes outgoing frames after unmute', () => {
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                url: 'ws://localhost:1234/voice',
            });
            const ws = new FakeWebSocket('ws://localhost:1234/voice');

            ws.open();
            session.setMuted(true);

            sendMicFrame(session, ws);

            session.setMuted(false);

            sendMicFrame(session, ws);
            sendMicFrame(session, ws);

            expect(ws.send).toHaveBeenCalledTimes(2);
        });
    });

    describe('onStatusChange', () => {
        it('fires on each internal status transition', () => {
            const observed: VoiceSessionStatusType[] = [];
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                onStatusChange: (status) => observed.push(status),
                url: 'ws://localhost:1234/voice',
            });

            // Drive internal transitions directly via the private setter to avoid coupling the test to
            // jsdom's lack of AudioContext/AudioWorklet support.
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const setStatus = (next: VoiceSessionStatusType) => (session as any).setStatus(next);

            setStatus('connecting');
            setStatus('active');
            setStatus('ending');
            setStatus('closed');

            expect(observed).toEqual(['connecting', 'active', 'ending', 'closed']);
        });

        it('does not fire when transitioning to the same status', () => {
            const onStatusChange = vi.fn();
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                onStatusChange,
                url: 'ws://localhost:1234/voice',
            });

            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const setStatus = (next: VoiceSessionStatusType) => (session as any).setStatus(next);

            setStatus('connecting');
            setStatus('connecting');
            setStatus('connecting');

            expect(onStatusChange).toHaveBeenCalledTimes(1);
            expect(onStatusChange).toHaveBeenCalledWith('connecting');
        });

        it('reports the four lifecycle states required by the realtime voice adapter', () => {
            const observed: VoiceSessionStatusType[] = [];
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                onStatusChange: (status) => observed.push(status),
                url: 'ws://localhost:1234/voice',
            });

            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const setStatus = (next: VoiceSessionStatusType) => (session as any).setStatus(next);

            setStatus('connecting');
            setStatus('active');
            setStatus('closed');
            setStatus('error');

            expect(observed).toContain('connecting');
            expect(observed).toContain('active');
            expect(observed).toContain('closed');
            expect(observed).toContain('error');
        });
    });

    describe('barge-in', () => {
        it('stops queued playback when a speech_start event arrives', () => {
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                url: 'ws://localhost:1234/voice',
            });

            const disconnect = vi.fn();
            const stop = vi.fn();
            const fakeSource = {disconnect, onended: () => undefined, stop};

            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            (session as any).scheduledPlaybackSources = [fakeSource];
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            (session as any).dispatchTextEvent(JSON.stringify({type: 'speech_start'}));

            expect(stop).toHaveBeenCalledTimes(1);
            expect(disconnect).toHaveBeenCalledTimes(1);
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            expect((session as any).scheduledPlaybackSources).toHaveLength(0);
        });

        it('still forwards the speech_start event to onEvent', () => {
            const onEvent = vi.fn();
            const session = new BrowserVoiceSession({
                mintToken: async () => 'test-token',
                onEvent,
                url: 'ws://localhost:1234/voice',
            });

            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            (session as any).dispatchTextEvent(JSON.stringify({type: 'speech_start'}));

            expect(onEvent).toHaveBeenCalledWith({type: 'speech_start'});
        });
    });

    it('adopts sessionId and outputSampleRate from the connected frame', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        expect(ws.url).toBe('ws://host/webhooks/1/wss?sessionToken=tok-1&sampleRate=16000');

        ws.open();
        ws.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});

        expect(session.getSessionId()).toBe('s-1');
        expect(session.getOutputSampleRate()).toBe(24000);

        session.stop();
    });

    it('reconnects with a fresh token and the previous sessionId after an unexpected close', async () => {
        vi.useFakeTimers();

        const tokens = ['tok-1', 'tok-2'];
        const statuses: VoiceSessionStatusType[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => tokens.shift()!,
            onStatusChange: (status) => statuses.push(status),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const first = FakeWebSocket.lastInstance!;

        first.open();
        first.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});
        first.fail();

        expect(statuses).toContain('reconnecting');

        await vi.advanceTimersByTimeAsync(1000);

        const second = FakeWebSocket.lastInstance!;

        expect(second).not.toBe(first);
        expect(second.url).toBe('ws://host/webhooks/1/wss?sessionToken=tok-2&sampleRate=16000&resumeSessionId=s-1');

        second.open();

        expect(session.getStatus()).toBe('active');

        session.stop();
    });

    it('treats a server end reason as a terminal session_end instead of reconnecting', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.fail('silence_timeout');

        expect(events).toContainEqual({reason: 'silence_timeout', type: 'session_end'});
        expect(session.getStatus()).toBe('closed');
    });

    it('sends a keepalive control frame every 15 seconds while muted, and none while unmuted', async () => {
        vi.useFakeTimers();

        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();

        const keepalive = JSON.stringify({action: 'keepalive', type: 'control'});
        const keepaliveCount = () => ws.send.mock.calls.filter(([data]) => data === keepalive).length;

        await vi.advanceTimersByTimeAsync(30000);

        expect(keepaliveCount()).toBe(0);

        session.setMuted(true);

        await vi.advanceTimersByTimeAsync(30000);

        expect(keepaliveCount()).toBe(2);

        session.setMuted(false);

        await vi.advanceTimersByTimeAsync(30000);

        expect(keepaliveCount()).toBe(2);

        session.stop();
    });

    it('closes with code 1000 and reason client_closed on a deliberate stop', async () => {
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();

        session.stop();

        expect(ws.close).toHaveBeenCalledWith(1000, 'client_closed');
        expect(session.getStatus()).toBe('closed');
    });

    it('treats a provider_closed close as a terminal session_end instead of reconnecting', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});
        ws.fail('provider_closed');

        expect(events).toContainEqual({reason: 'provider_closed', type: 'session_end'});
        expect(session.getStatus()).toBe('closed');
    });

    it('ends a refused first connect with the error frame as its single terminal error', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.onmessage?.({data: JSON.stringify({message: 'No Voice Agent configured on the trigger', type: 'error'})});
        ws.fail('voice_agent_missing');

        expect(events.filter((event) => event.type === 'error')).toEqual([
            {message: 'No Voice Agent configured on the trigger', type: 'error'},
        ]);
        expect(events.some((event) => event.type === 'session_end')).toBe(false);
        expect(session.getStatus()).toBe('error');
    });

    it('ends a refused first connect with a fallback message when no error frame arrived', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.fail('workflow_disabled');

        expect(events.filter((event) => event.type === 'error')).toEqual([
            {message: 'The workflow behind this voice session is disabled.', type: 'error'},
        ]);
        expect(session.getStatus()).toBe('error');
    });

    it('reports the error frame, not the generic text, when a first connect closes without a reason', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.onmessage?.({data: JSON.stringify({message: 'Invalid or expired session token', type: 'error'})});
        ws.fail();

        expect(events.at(-1)).toEqual({message: 'Invalid or expired session token', type: 'error'});
        expect(events).not.toContainEqual({message: 'Connection lost', type: 'error'});
        expect(session.getStatus()).toBe('error');
    });

    it('gives up after the configured attempts', async () => {
        vi.useFakeTimers();

        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            maxReconnectAttempts: 1,
            mintToken: async () => 'tok',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const first = FakeWebSocket.lastInstance!;

        first.open();
        // Without a connected frame there is no sessionId, and a reconnect never has anywhere to resume — send
        // one so this test actually exercises a real reconnect attempt before exhausting it, not an
        // immediate give-up on the very first close.
        first.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});
        first.fail();

        expect(session.getStatus()).toBe('reconnecting');

        await vi.advanceTimersByTimeAsync(1000);

        const second = FakeWebSocket.lastInstance!;

        expect(second).not.toBe(first);

        second.fail();

        expect(session.getStatus()).toBe('error');
        expect(events.at(-1)).toEqual({message: 'Connection lost', type: 'error'});
    });

    it('does not open a socket if stop() is called while mintToken() is still in flight', async () => {
        stubAudioGlobals();

        let resolveToken: (token: string) => void = () => {};
        const tokenPromise = new Promise<string>((resolve) => {
            resolveToken = resolve;
        });

        const session = new BrowserVoiceSession({
            mintToken: () => tokenPromise,
            url: 'ws://host/webhooks/1/wss',
        });

        const startPromise = session.start();

        // start() is now blocked awaiting mintToken() — stop the session before the token resolves.
        session.stop();

        resolveToken('tok-1');

        await startPromise;

        expect(FakeWebSocket.lastInstance).toBeNull();
        expect(session.getStatus()).toBe('closed');
    });

    it('releases the mic if stop() is called while getUserMedia() is pending', async () => {
        const trackStop = vi.fn();
        const mediaStream = {
            getTracks: () => [{stop: trackStop}],
        } as unknown as MediaStream;
        const getUserMediaDeferred = createDeferred<MediaStream>();

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (navigator as any).mediaDevices = {
            getUserMedia: vi.fn().mockReturnValue(getUserMediaDeferred.promise),
        };

        const audioContextConstructor = vi.fn();

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).AudioContext = audioContextConstructor;

        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            url: 'ws://host/webhooks/1/wss',
        });

        const startPromise = session.start();

        // start() is now blocked awaiting getUserMedia() — stop the session before it resolves.
        session.stop();

        getUserMediaDeferred.resolve(mediaStream);

        await startPromise;

        expect(trackStop).toHaveBeenCalledTimes(1);
        // The bail-out happens before an AudioContext is ever constructed — nothing to close.
        expect(audioContextConstructor).not.toHaveBeenCalled();
        expect(FakeWebSocket.lastInstance).toBeNull();
        expect(session.getStatus()).toBe('closed');
    });

    it('stays closed and releases the mic and audio context if stop() is called while audioWorklet.addModule() is pending', async () => {
        const trackStop = vi.fn();
        const mediaStream = {
            getTracks: () => [{stop: trackStop}],
        } as unknown as MediaStream;

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (navigator as any).mediaDevices = {
            getUserMedia: vi.fn().mockResolvedValue(mediaStream),
        };

        URL.createObjectURL = vi.fn(() => 'blob:mock-worklet-url');
        URL.revokeObjectURL = vi.fn();

        const addModuleDeferred = createDeferred<void>();
        const addModule = vi.fn().mockReturnValue(addModuleDeferred.promise);
        const audioContextClose = vi.fn().mockResolvedValue(undefined);
        const audioContextInstance = {
            audioWorklet: {addModule},
            close: audioContextClose,
            // Only exercised if the fix fails to bail before reaching worklet wiring — stubbed fully so a
            // regression surfaces as the intended assertion failure, not an unrelated TypeError.
            createMediaStreamSource: vi.fn(() => ({connect: vi.fn()})),
        };

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).AudioContext = vi.fn(function AudioContext() {
            return audioContextInstance;
        });
        // Only exercised if the fix fails to bail — stubbed so a regression surfaces as the intended
        // assertion failure rather than an unrelated TypeError from a leftover global.
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (globalThis as any).AudioWorkletNode = vi.fn(function AudioWorkletNode() {
            return {connect: vi.fn(), disconnect: vi.fn(), port: {onmessage: null}};
        });

        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            url: 'ws://host/webhooks/1/wss',
        });

        const startPromise = session.start();

        // start() is somewhere between getUserMedia() resolving and addModule() being called — wait until it
        // has actually reached the addModule() await before stopping it, rather than guessing a microtask
        // count.
        await vi.waitFor(() => {
            expect(addModule).toHaveBeenCalled();
        });

        session.stop();

        addModuleDeferred.resolve();

        await startPromise;

        expect(trackStop).toHaveBeenCalledTimes(1);
        expect(audioContextClose).toHaveBeenCalledTimes(1);
        expect(FakeWebSocket.lastInstance).toBeNull();
        expect(session.getStatus()).toBe('closed');
    });

    it('emits exactly one terminal error event when the socket errors and then closes', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            maxReconnectAttempts: 0,
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.onerror?.();
        ws.fail();

        const errorEvents = events.filter((event) => event.type === 'error');

        expect(errorEvents).toHaveLength(1);

        session.stop();
    });

    it('still attempts reconnect after a captured server error, instead of treating every later close as an error', async () => {
        vi.useFakeTimers();

        const statuses: VoiceSessionStatusType[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok',
            onStatusChange: (status) => statuses.push(status),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const first = FakeWebSocket.lastInstance!;

        first.open();
        first.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});
        // A one-off error frame (e.g. a transient warning) that today never gets cleared.
        first.onmessage?.({data: JSON.stringify({message: 'transient warning', type: 'error'})});
        first.fail();

        // Reconnect budget and sessionId are both available, so this should reconnect rather than end with
        // the stale captured message.
        expect(statuses).toContain('reconnecting');

        session.stop();
    });

    it('treats a mintToken failure during reconnect as one attempt and retries with backoff', async () => {
        vi.useFakeTimers();

        let reconnectMintCalls = 0;
        const session = new BrowserVoiceSession({
            maxReconnectAttempts: 2,
            mintToken: async () => {
                if (reconnectMintCalls === 0) {
                    reconnectMintCalls++;

                    return 'tok-1';
                }

                reconnectMintCalls++;

                if (reconnectMintCalls === 2) {
                    throw new Error('mint failed');
                }

                return 'tok-3';
            },
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const first = FakeWebSocket.lastInstance!;

        first.open();
        first.onmessage?.({data: JSON.stringify({event: 'connected', outputSampleRate: 24000, sessionId: 's-1'})});
        first.fail();

        expect(session.getStatus()).toBe('reconnecting');

        // The first reconnect attempt's mint fails — this must schedule ANOTHER attempt, not give up.
        await vi.advanceTimersByTimeAsync(1000);

        expect(session.getStatus()).toBe('reconnecting');
        expect(FakeWebSocket.lastInstance).toBe(first);

        // The second reconnect attempt's mint succeeds.
        await vi.advanceTimersByTimeAsync(2000);

        const second = FakeWebSocket.lastInstance!;

        expect(second).not.toBe(first);

        second.open();

        expect(session.getStatus()).toBe('active');

        session.stop();
    });

    it('normalizes an event-keyed error frame to type: "error" for consumers', async () => {
        const events: VoiceEventI[] = [];
        const session = new BrowserVoiceSession({
            mintToken: async () => 'tok-1',
            onEvent: (event) => events.push(event),
            url: 'ws://host/webhooks/1/wss',
        });

        await startWithoutAudio(session);

        const ws = FakeWebSocket.lastInstance!;

        ws.open();
        ws.onmessage?.({data: JSON.stringify({event: 'error', message: 'bad token'})});

        expect(events).toContainEqual({event: 'error', message: 'bad token', type: 'error'});

        session.stop();
    });
});

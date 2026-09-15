/**
 * Sibling copy of `client/src/shared/lib/browser-voice/BrowserVoiceSession.ts` published inside the widget bundle.
 *
 * The widget cannot import from the platform's `@/shared` path because the widget is published as an external npm
 * package consumed by customer sites. Bytes here must stay in lockstep with the platform copy; future Tier 2
 * candidate: lift this into a shared `@bytechef/browser-voice` package consumed by both.
 *
 * Key difference from the platform copy: the AudioWorklet source is inlined as a string and registered via a
 * Blob URL (`workletUrl`/`WORKLET_SOURCE` below), so customers do not need to host an extra static asset. Every
 * other behaviour — the `sessionId`/`outputSampleRate` handshake, reconnect-with-backoff (a fresh `mintToken`
 * and `resumeSessionId` per attempt), the `stop()`-racing-every-await guards, and the single terminal error
 * event — matches the platform copy byte-for-byte.
 */

export type VoiceEventType =
    | 'connected'
    | 'transcript_interim'
    | 'transcript_final'
    | 'assistant_text'
    | 'speech_start'
    | 'tool_call'
    | 'tool_result'
    | 'session_end'
    | 'error';

export interface VoiceEventI {
    type: VoiceEventType | string;
    text?: string;
    turnId?: string;
    done?: boolean;
    message?: string;
    [k: string]: unknown;
}

export type VoiceSessionStatusType = 'idle' | 'connecting' | 'active' | 'reconnecting' | 'ending' | 'closed' | 'error';

export interface BrowserVoiceSessionOptionsI {
    /** WS URL WITHOUT the sessionToken (or any other session query param) — the session appends those itself. */
    url: string;
    /** Called on start and on every reconnect attempt to mint a fresh, single-use session token. */
    mintToken: () => Promise<string>;
    sampleRate?: number;
    /** Maximum number of reconnect attempts after an unexpected close. Default 3; 0 disables reconnect. */
    maxReconnectAttempts?: number;
    onEvent?: (event: VoiceEventI) => void;
    onStatusChange?: (status: VoiceSessionStatusType) => void;
    onSpeakingChange?: (speaking: boolean) => void;
    onVolume?: (level: number) => void;
}

const DEFAULT_SAMPLE_RATE = 16000;
const DEFAULT_MAX_RECONNECT_ATTEMPTS = 3;
/** How often a muted session tells the server the caller is still there; well inside the default silence timeout. */
const MUTED_KEEPALIVE_INTERVAL_MS = 15000;
const PLAYBACK_LEAD_AHEAD_SECONDS = 0.02;
const SPEAKING_HYSTERESIS_MS = 300;
const SPEAKING_CHECK_INTERVAL_MS = 100;
const VOLUME_EMIT_THROTTLE_MS = 50;

/** Close reasons (and synthesised `session_end` reasons) that end the session for good — never reconnect. */
const TERMINAL_REASONS = new Set([
    'client_closed',
    'session_limit',
    'silence_timeout',
    'provider_closed',
    'provider_error',
    'server_shutdown',
    'voice_agent_missing',
    'workflow_disabled',
]);

/**
 * Close reasons for a session the server refused before it started, with the text to show when no error frame
 * preceded the close. Terminal like the others, but the caller never had a session: it ends as a single error — the
 * server's own frame when one arrived, otherwise this text — never a second error and never "Connection lost".
 */
const REFUSAL_MESSAGES: Record<string, string> = {
    voice_agent_missing: 'No Voice Agent configured on the trigger',
    workflow_disabled: 'The workflow behind this voice session is disabled.',
};

/**
 * Returns null when voice is supported in this browser, or a human-readable reason when it is not.
 *
 * <p>
 * Voice requires:
 *   - {@code AudioContext} (Chrome 35+, Firefox 25+, Safari 14.1+)
 *   - {@code AudioWorklet} (Chrome 66+, Firefox 76+, Safari 14.1+)
 *   - {@code navigator.mediaDevices.getUserMedia} (all modern browsers but requires HTTPS / localhost)
 *   - {@code WebSocket} (universal in modern browsers)
 *
 * Without this check, calling {@code start()} on an unsupported browser fails silently at the worklet-load
 * step with a generic Promise rejection that's hard to surface to the user. UI consumers should call this at
 * mount time and disable the mic button with the returned reason when non-null.
 *
 * The platform-side equivalent is {@code client/src/shared/lib/browser-voice/BrowserVoiceSession.ts}; these two
 * implementations must stay in sync.
 */
export function checkVoiceSupport(): string | null {
    if (typeof window === 'undefined') {
        return 'Voice is not available outside a browser environment.';
    }

    if (
        typeof window.AudioContext === 'undefined' &&
        typeof (window as {webkitAudioContext?: unknown}).webkitAudioContext === 'undefined'
    ) {
        return 'Your browser does not support the Web Audio API. Please use Chrome 35+, Firefox 25+, or Safari 14.1+.';
    }

    if (typeof window.AudioWorklet === 'undefined') {
        return 'Your browser does not support AudioWorklet (required for voice). Please use Chrome 66+, Firefox 76+, or Safari 14.1+.';
    }

    if (
        typeof navigator === 'undefined' ||
        !navigator.mediaDevices ||
        typeof navigator.mediaDevices.getUserMedia !== 'function'
    ) {
        return 'Your browser does not support microphone access (getUserMedia). Please use Chrome, Firefox, or Safari.';
    }

    if (typeof WebSocket === 'undefined') {
        return 'Your browser does not support WebSocket (required for voice). Please use Chrome, Firefox, or Safari.';
    }

    // getUserMedia requires a secure context (HTTPS or localhost). Browsers don't expose this as a
    // first-class check, but window.isSecureContext is the canonical signal.
    if (typeof window.isSecureContext !== 'undefined' && !window.isSecureContext) {
        return 'Voice requires a secure (https://) context. Browsers block microphone access on insecure origins.';
    }

    return null;
}

// Keep this string byte-for-byte in sync with client/public/mic-worklet.js
const WORKLET_SOURCE = `class Pcm16DownsamplerProcessor extends AudioWorkletProcessor {
    constructor(options) {
        super();
        const params = (options && options.processorOptions) || {};
        this.targetSampleRate = params.targetSampleRate || 16000;
        this.frameMs = params.frameMs || 20;
        this.frameSize = Math.max(1, Math.floor((this.targetSampleRate * this.frameMs) / 1000));
        this.acc = [];
    }
    process(inputs) {
        const input = inputs[0];
        if (!input || input.length === 0) return true;
        const channel = input[0];
        if (!channel || channel.length === 0) return true;
        const ratio = sampleRate / this.targetSampleRate;
        const out = new Int16Array(Math.floor(channel.length / ratio));
        for (let i = 0; i < out.length; i++) {
            const sourceIndex = Math.floor(i * ratio);
            const sample = Math.max(-1, Math.min(1, channel[sourceIndex] || 0));
            out[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
        }
        for (let i = 0; i < out.length; i++) this.acc.push(out[i]);
        while (this.acc.length >= this.frameSize) {
            const frame = new Int16Array(this.frameSize);
            for (let i = 0; i < this.frameSize; i++) frame[i] = this.acc[i];
            this.acc.splice(0, this.frameSize);
            this.port.postMessage(frame.buffer, [frame.buffer]);
        }
        return true;
    }
}
registerProcessor('pcm16-downsampler', Pcm16DownsamplerProcessor);
`;

export class BrowserVoiceSession {
    private readonly url: string;
    private readonly sampleRate: number;
    private readonly mintToken: () => Promise<string>;
    private readonly maxReconnectAttempts: number;
    private readonly onEvent?: (event: VoiceEventI) => void;
    private readonly onStatusChange?: (status: VoiceSessionStatusType) => void;
    private readonly onSpeakingChange?: (speaking: boolean) => void;
    private readonly onVolume?: (level: number) => void;

    private ws: WebSocket | null = null;
    private audioContext: AudioContext | null = null;
    private mediaStream: MediaStream | null = null;
    private workletNode: AudioWorkletNode | null = null;
    private workletUrl: string | null = null;
    private playbackCursorSeconds = 0;
    private scheduledPlaybackSources: AudioBufferSourceNode[] = [];
    private speaking = false;
    private status: VoiceSessionStatusType = 'idle';
    private lastServerError: string | null = null;
    /** True once the current connection's server error frame has been emitted to the consumer. */
    private lastServerErrorEmitted = false;
    private lastAssistantFrameAtRef = 0;
    private speakingChangeIntervalId: ReturnType<typeof setInterval> | null = null;
    private muted = false;
    private mutedKeepaliveIntervalId: ReturnType<typeof setInterval> | null = null;
    private lastVolumeEmitAt = 0;
    private sessionId: string | null = null;
    private outputSampleRate: number;
    private reconnectAttempt = 0;
    private reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;

    constructor(options: BrowserVoiceSessionOptionsI) {
        this.url = options.url;
        this.sampleRate = options.sampleRate ?? DEFAULT_SAMPLE_RATE;
        this.outputSampleRate = this.sampleRate;
        this.mintToken = options.mintToken;
        this.maxReconnectAttempts = options.maxReconnectAttempts ?? DEFAULT_MAX_RECONNECT_ATTEMPTS;
        this.onEvent = options.onEvent;
        this.onStatusChange = options.onStatusChange;
        this.onSpeakingChange = options.onSpeakingChange;
        this.onVolume = options.onVolume;
    }

    getStatus(): VoiceSessionStatusType {
        return this.status;
    }

    getSessionId(): string | null {
        return this.sessionId;
    }

    getOutputSampleRate(): number {
        return this.outputSampleRate;
    }

    /**
     * Mute or unmute the outgoing mic stream.
     *
     * While muted, captured mic frames are dropped before being forwarded to the WebSocket. The session stays
     * fully open and the server keeps receiving silence (no frames), so assistant audio playback continues
     * uninterrupted. Toggling has no effect on incoming audio.
     *
     * A muted caller sends no audio, which the server's silence timeout would read as a caller who walked away, so
     * while muted the session sends a `keepalive` control frame every 15 seconds instead. The server counts it as
     * activity for the silence timer only; it never reaches the voice provider.
     */
    setMuted(muted: boolean): void {
        this.muted = muted;

        if (muted) {
            this.startMutedKeepalive();
        } else {
            this.stopMutedKeepalive();
        }
    }

    async start(): Promise<void> {
        if (this.status !== 'idle' && this.status !== 'closed' && this.status !== 'error') {
            return;
        }

        this.setStatus('connecting');

        try {
            this.mediaStream = await navigator.mediaDevices.getUserMedia({
                audio: {echoCancellation: true, noiseSuppression: true},
            });

            // stop() may have run while getUserMedia() was pending — it saw mediaStream/audioContext still
            // null and had nothing to release then, so a mic acquired after the fact must be released here
            // instead, or it stays hot with nothing left to ever stop it.
            if (this.bailIfStopped()) {
                return;
            }

            this.audioContext = new AudioContext();

            const blob = new Blob([WORKLET_SOURCE], {type: 'application/javascript'});

            this.workletUrl = URL.createObjectURL(blob);

            await this.audioContext.audioWorklet.addModule(this.workletUrl);

            // Same race, one step later: stop() may have run while addModule() was pending.
            if (this.bailIfStopped()) {
                return;
            }

            this.workletNode = new AudioWorkletNode(this.audioContext, 'pcm16-downsampler', {
                processorOptions: {frameMs: 20, targetSampleRate: this.sampleRate},
            });

            const sourceNode = this.audioContext.createMediaStreamSource(this.mediaStream);

            sourceNode.connect(this.workletNode);

            this.workletNode.port.onmessage = (event) => {
                const buffer = event.data as ArrayBuffer;

                this.emitVolumeFromFrame(buffer);

                if (this.muted) {
                    return;
                }

                if (this.ws?.readyState === WebSocket.OPEN) {
                    this.ws.send(buffer);
                }
            };

            await this.openSocket(false);
        } catch (error) {
            if (this.bailIfStopped()) {
                // stop() already decided the outcome (status closed) while this attempt was in flight —
                // don't override it with an error event or status change, just release whatever this
                // attempt had acquired.
                return;
            }

            this.onEvent?.({
                message: error instanceof Error ? error.message : 'Failed to start voice session',
                type: 'error',
            });

            this.setStatus('error');
            this.cleanup();
        }
    }

    stop(): void {
        if (this.status === 'idle' || this.status === 'closed') {
            return;
        }

        this.setStatus('ending');

        try {
            this.ws?.send(JSON.stringify({action: 'end', type: 'control'}));
        } catch {
            // ignore — closing anyway
        }

        // The reason tells the server this is a deliberate hang-up, not a network drop: it ends the session at once
        // instead of holding it open for a resume that will never come.
        this.ws?.close(1000, 'client_closed');
        this.cleanup();
        this.setStatus('closed');
    }

    /**
     * Mints a token and opens (or re-opens) the WebSocket. On `resume`, appends `resumeSessionId` so the
     * server can splice this connection back onto the in-flight provider session instead of starting a new
     * one — the server keeps a dropped session resumable for a short window after the close.
     */
    private async openSocket(resume: boolean): Promise<void> {
        let token: string;

        try {
            token = await this.mintToken();
        } catch (error) {
            if (this.bailIfStopped()) {
                // stop() ran while we were minting — release whatever start() had acquired and stop.
                return;
            }

            if (!resume) {
                // The initial start() still fails fast: there's nothing yet to reconnect to.
                this.onEvent?.({
                    message: error instanceof Error ? error.message : 'Failed to start voice session',
                    type: 'error',
                });

                this.setStatus('error');
                this.cleanup();

                return;
            }

            // A mint failure during a reconnect attempt counts as one attempt, same as a socket-level
            // failure would, and schedules the next backoff until attempts run out.
            this.scheduleReconnectOrGiveUp();

            return;
        }

        if (this.bailIfStopped()) {
            // stop() ran while we were minting — release whatever start() had acquired; don't open a socket
            // the session no longer wants.
            return;
        }

        this.ws = new WebSocket(this.buildSocketUrl(token, resume));
        this.ws.binaryType = 'arraybuffer';

        this.ws.onopen = () => {
            this.reconnectAttempt = 0;
            // A fresh, successfully-opened connection clears any error captured on a previous connection —
            // otherwise one transient error anywhere in the session's history would force every later close
            // down the error path forever, even after we've since reconnected cleanly.
            this.lastServerError = null;
            this.lastServerErrorEmitted = false;

            this.setStatus('active');
            this.startSpeakingChangeWatcher();
        };

        this.ws.onmessage = (event) => {
            if (typeof event.data === 'string') {
                this.dispatchTextEvent(event.data);
            } else if (event.data instanceof ArrayBuffer) {
                this.enqueuePlayback(event.data);
            }
        };

        this.ws.onerror = () => {
            // The browser always follows an error with a close event right after. onclose owns the terminal
            // outcome (session_end, reconnect, or a single error event) — record a fallback message here (a
            // real one from a JSON error frame wins, since dispatchTextEvent runs first for the common case
            // of a server-sent error immediately before a close) but never emit or change status here, or
            // the two would double-fire.
            this.lastServerError = this.lastServerError ?? 'WebSocket error';
        };

        this.ws.onclose = (event) => {
            if (this.status === 'ending') {
                this.cleanup();

                return;
            }

            if (event.reason && REFUSAL_MESSAGES[event.reason] !== undefined) {
                this.refuse(REFUSAL_MESSAGES[event.reason]);

                return;
            }

            if (event.reason && TERMINAL_REASONS.has(event.reason)) {
                this.finish(event.reason);

                return;
            }

            // Decide error-vs-reconnect from the close itself, not from a possibly-stale captured error: as
            // long as a reconnect is still possible, take it — a captured message only decides the wording
            // of the final "give up" event once attempts (or the ability to resume) actually run out.
            this.scheduleReconnectOrGiveUp();
        };
    }

    /**
     * True once `stop()` has run. `openSocket()` checks this after every await (its `mintToken()` call) and
     * bails out — without ever constructing a `WebSocket` — instead of opening a socket for a session the
     * caller already told to stop. Without this, `stop()` called while a mint is in flight has nothing to
     * close yet (`this.ws` is still null), so the socket that mint eventually produces leaks past the
     * session's lifetime and can flip status back to `active` after the caller believed it was closed.
     */
    private isStoppedOrSuperseded(): boolean {
        return this.status === 'ending' || this.status === 'closed';
    }

    /**
     * If `stop()` ran while the caller was awaiting something, releases whatever this `start()`/`openSocket()`
     * attempt had acquired so far and reports true so the caller can return without emitting an event or
     * changing status (`stop()` already decided the outcome). `cleanup()` is safe to call here even when
     * `stop()` already called it — every step inside only touches fields that are still non-null, so a
     * resource acquired *after* `stop()`'s own `cleanup()` call (the exact race this exists for) is released
     * on this second call instead of leaking past the session's lifetime.
     */
    private bailIfStopped(): boolean {
        if (!this.isStoppedOrSuperseded()) {
            return false;
        }

        this.cleanup();

        return true;
    }

    /**
     * Either schedules the next reconnect attempt (status `reconnecting`, keeping the mic/audio context
     * alive — only `this.ws` is cleared) or, once attempts are exhausted or there is no `sessionId` to
     * resume, ends the session with a single terminal error event.
     */
    private scheduleReconnectOrGiveUp(): void {
        if (this.reconnectAttempt < this.maxReconnectAttempts && this.sessionId) {
            this.setStatus('reconnecting');

            const delayMs = 1000 * 2 ** this.reconnectAttempt;

            this.reconnectAttempt++;
            this.ws = null;

            this.reconnectTimeoutId = setTimeout(() => {
                this.reconnectTimeoutId = null;

                void this.openSocket(true);
            }, delayMs);

            return;
        }

        this.onEvent?.({message: this.lastServerError ?? 'Connection lost', type: 'error'});
        this.setStatus('error');
        this.cleanup();
    }

    private buildSocketUrl(token: string, resume: boolean): string {
        const socketUrl = new URL(this.url);

        socketUrl.searchParams.set('sessionToken', token);
        socketUrl.searchParams.set('sampleRate', String(this.sampleRate));

        if (resume && this.sessionId) {
            socketUrl.searchParams.set('resumeSessionId', this.sessionId);
        }

        return socketUrl.toString();
    }

    /**
     * Ends a session the server refused before it started: a single terminal error. The server's error frame has
     * normally already reached the consumer, so the fallback text is emitted only when no frame arrived.
     */
    private refuse(fallbackMessage: string): void {
        if (!this.lastServerErrorEmitted) {
            this.onEvent?.({message: fallbackMessage, type: 'error'});
        }

        this.setStatus('error');
        this.cleanup();
    }

    /** Emits a terminal `session_end` event, moves to `closed`, and tears everything down. */
    private finish(reason?: string): void {
        this.onEvent?.({reason, type: 'session_end'});
        this.setStatus('closed');
        this.cleanup();
    }

    private startMutedKeepalive(): void {
        if (this.mutedKeepaliveIntervalId !== null) {
            return;
        }

        this.mutedKeepaliveIntervalId = setInterval(() => {
            if (this.ws?.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({action: 'keepalive', type: 'control'}));
            }
        }, MUTED_KEEPALIVE_INTERVAL_MS);
    }

    private stopMutedKeepalive(): void {
        if (this.mutedKeepaliveIntervalId !== null) {
            clearInterval(this.mutedKeepaliveIntervalId);

            this.mutedKeepaliveIntervalId = null;
        }
    }

    private cleanup(): void {
        this.stopMutedKeepalive();
        this.stopSpeakingChangeWatcher();

        if (this.reconnectTimeoutId !== null) {
            clearTimeout(this.reconnectTimeoutId);

            this.reconnectTimeoutId = null;
        }

        if (this.mediaStream) {
            for (const track of this.mediaStream.getTracks()) {
                track.stop();
            }

            this.mediaStream = null;
        }

        if (this.workletNode) {
            this.workletNode.port.onmessage = null;
            this.workletNode.disconnect();
            this.workletNode = null;
        }

        if (this.audioContext) {
            void this.audioContext.close();
            this.audioContext = null;
        }

        if (this.workletUrl) {
            URL.revokeObjectURL(this.workletUrl);
            this.workletUrl = null;
        }

        this.ws = null;
        this.scheduledPlaybackSources = [];
        this.playbackCursorSeconds = 0;
        this.lastAssistantFrameAtRef = 0;
        this.lastVolumeEmitAt = 0;
        this.setSpeaking(false);
    }

    private setStatus(next: VoiceSessionStatusType): void {
        if (this.status === next) {
            return;
        }

        this.status = next;

        this.onStatusChange?.(next);
    }

    private setSpeaking(next: boolean): void {
        if (this.speaking === next) {
            return;
        }

        this.speaking = next;

        this.onSpeakingChange?.(next);
    }

    private dispatchTextEvent(payload: string): void {
        try {
            const parsed = JSON.parse(payload) as VoiceEventI & {
                event?: string;
                outputSampleRate?: number;
                sessionId?: string;
            };

            const isErrorFrame =
                (parsed.type === 'error' || parsed.event === 'error') && typeof parsed.message === 'string';

            if (isErrorFrame) {
                this.lastServerError = parsed.message as string;
            }

            if (parsed.event === 'connected') {
                if (typeof parsed.sessionId === 'string') {
                    this.sessionId = parsed.sessionId;
                }

                if (typeof parsed.outputSampleRate === 'number') {
                    this.outputSampleRate = parsed.outputSampleRate;
                }

                this.onEvent?.({...parsed, type: 'connected'});

                return;
            }

            if (parsed.type === 'session_end') {
                this.finish(typeof parsed.reason === 'string' ? parsed.reason : undefined);

                return;
            }

            if (parsed.type === 'speech_start') {
                this.clearPlayback();
            }

            if (isErrorFrame) {
                // Normalize event: 'error' the same way connected is normalized — some server frames key
                // the error on `event` rather than `type` (e.g. the deployed webhook handler for a few of
                // its error paths), and a consumer checking `event.type === 'error'` must not silently drop
                // those.
                this.onEvent?.({...parsed, type: 'error'});

                this.lastServerErrorEmitted = true;

                return;
            }

            this.onEvent?.(parsed);
        } catch {
            this.onEvent?.({message: payload, type: 'error'});
        }
    }

    private enqueuePlayback(buffer: ArrayBuffer): void {
        // Update the assistant-frame timestamp regardless of whether the AudioContext exists — the
        // speaking-change watcher relies on this and should still fire even if audio playback is
        // unavailable for some reason.
        this.lastAssistantFrameAtRef = performance.now();

        if (!this.audioContext) {
            return;
        }

        const audioContext = this.audioContext;
        const int16 = new Int16Array(buffer);
        const audioBuffer = audioContext.createBuffer(1, int16.length, this.outputSampleRate);
        const channel = audioBuffer.getChannelData(0);

        for (let index = 0; index < int16.length; index++) {
            channel[index] = int16[index] / 0x8000;
        }

        const source = audioContext.createBufferSource();

        source.buffer = audioBuffer;
        source.connect(audioContext.destination);

        const now = audioContext.currentTime;
        const startAt = Math.max(now + PLAYBACK_LEAD_AHEAD_SECONDS, this.playbackCursorSeconds);

        source.onended = () => {
            this.scheduledPlaybackSources = this.scheduledPlaybackSources.filter((scheduled) => scheduled !== source);
        };

        source.start(startAt);

        this.scheduledPlaybackSources.push(source);

        this.playbackCursorSeconds = startAt + audioBuffer.duration;
    }

    /**
     * Stops and discards any queued or playing assistant audio. Invoked on a `speech_start` (barge-in) event so the
     * assistant stops talking the instant the user starts speaking.
     */
    private clearPlayback(): void {
        for (const source of this.scheduledPlaybackSources) {
            source.onended = null;

            try {
                source.stop();
            } catch {
                // Source already ended; nothing to stop.
            }

            source.disconnect();
        }

        this.scheduledPlaybackSources = [];
        this.playbackCursorSeconds = this.audioContext ? this.audioContext.currentTime : 0;
    }

    private emitVolumeFromFrame(buffer: ArrayBuffer): void {
        if (!this.onVolume) {
            return;
        }

        const now = performance.now();

        if (now - this.lastVolumeEmitAt < VOLUME_EMIT_THROTTLE_MS) {
            return;
        }

        this.lastVolumeEmitAt = now;

        const samples = new Int16Array(buffer);

        if (samples.length === 0) {
            this.onVolume(0);

            return;
        }

        let sumOfSquares = 0;

        for (let index = 0; index < samples.length; index++) {
            const normalized = samples[index] / 0x8000;

            sumOfSquares += normalized * normalized;
        }

        const rms = Math.sqrt(sumOfSquares / samples.length);

        this.onVolume(rms);
    }

    private startSpeakingChangeWatcher(): void {
        if (this.speakingChangeIntervalId !== null) {
            return;
        }

        this.speakingChangeIntervalId = setInterval(() => {
            // No incoming frames yet — never speaking.
            if (this.lastAssistantFrameAtRef === 0) {
                return;
            }

            const elapsed = performance.now() - this.lastAssistantFrameAtRef;

            if (elapsed <= SPEAKING_HYSTERESIS_MS) {
                if (!this.speaking) {
                    this.setSpeaking(true);
                }
            } else if (this.speaking) {
                this.setSpeaking(false);
            }
        }, SPEAKING_CHECK_INTERVAL_MS);
    }

    private stopSpeakingChangeWatcher(): void {
        if (this.speakingChangeIntervalId !== null) {
            clearInterval(this.speakingChangeIntervalId);

            this.speakingChangeIntervalId = null;
        }
    }
}

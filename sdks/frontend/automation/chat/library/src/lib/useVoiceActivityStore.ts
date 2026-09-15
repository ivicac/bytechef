/**
 * Sibling copy of `client/src/shared/lib/voice/useVoiceActivityStore.ts`, minus Zustand.
 *
 * The widget ships as an external npm package and must not gain a Zustand dependency just for this one piece
 * of state, so `voiceActivityStore` below is a small hand-rolled subscribable holder instead: a `getState()` +
 * `subscribe(listener)` pair. `ByteChefRealtimeVoiceAdapter` writes to it via `.setActivity(...)`/
 * `.setEndReason(...)`/`.reset()`; `VoiceModeLayout` reads it through React's `useSyncExternalStore`, selecting
 * one field at a time the same way the platform's `useVoiceActivityStore((state) => state.activity)` does.
 *
 * The state itself is the last non-transcript signal from a running voice session: an in-flight tool call, a
 * reconnect attempt, or the idle baseline in between. `ByteChefRealtimeVoiceAdapter` writes this from
 * `BrowserVoiceSession`'s `onEvent`/`onStatusChange` callbacks; `VoiceModeLayout` reads it to render a status
 * line under the orb instead of the adapter faking an assistant transcript line for a tool call.
 */
export type VoiceActivityType = 'idle' | 'reconnecting' | {tool: string};

export interface VoiceActivityStateI {
    activity: VoiceActivityType;
    endReason: string | null;
}

type ListenerType = () => void;

class VoiceActivityStore {
    private state: VoiceActivityStateI = {activity: 'idle', endReason: null};
    private generation = 0;
    private readonly listeners = new Set<ListenerType>();

    getState = (): VoiceActivityStateI => this.state;

    // Exported so tests can call `voiceActivityStore.setState({...})` directly, mirroring the platform's
    // Zustand testing convention. `ByteChefRealtimeVoiceAdapter` resets this store at the start of every
    // `connect()` call, so a previous session's `endReason` never bleeds into the next one.
    setState = (partial: Partial<VoiceActivityStateI>): void => {
        this.state = {...this.state, ...partial};

        for (const listener of this.listeners) {
            listener();
        }
    };

    subscribe = (listener: ListenerType): (() => void) => {
        this.listeners.add(listener);

        return () => {
            this.listeners.delete(listener);
        };
    };

    /**
     * Starts a new session: clears the activity and end reason, and returns the generation that session's writes
     * must carry. A write stamped with an older generation comes from a torn-down session and is ignored — the same
     * guard as the platform store.
     */
    beginSession = (): number => {
        this.generation += 1;

        this.setState({activity: 'idle', endReason: null});

        return this.generation;
    };

    reset = (): void => this.setState({activity: 'idle', endReason: null});

    setActivity = (activity: VoiceActivityType, generation?: number): void => {
        if (generation !== undefined && generation !== this.generation) {
            return;
        }

        this.setState({activity});
    };

    setEndReason = (endReason: string | null, generation?: number): void => {
        if (generation !== undefined && generation !== this.generation) {
            return;
        }

        this.setState({endReason});
    };
}

export const voiceActivityStore = new VoiceActivityStore();

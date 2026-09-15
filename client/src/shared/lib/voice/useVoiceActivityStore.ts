import {create} from 'zustand';
import {devtools} from 'zustand/middleware';

/**
 * The last non-transcript signal from a running voice session: an in-flight tool call, a reconnect
 * attempt, or the idle baseline in between. `ByteChefRealtimeVoiceAdapter` writes this from
 * `BrowserVoiceSession`'s `onEvent`/`onStatusChange` callbacks; `VoiceModeLayout` reads it to render a
 * status line under the orb instead of the adapter faking an assistant transcript line for a tool call.
 */
export type VoiceActivityType = 'idle' | 'reconnecting' | {tool: string};

export interface VoiceActivityStateI {
    activity: VoiceActivityType;
    /**
     * Starts a new session: clears the activity and end reason, and returns the generation that session's writes
     * must carry. A write stamped with an older generation comes from a torn-down session and is ignored.
     */
    beginSession: () => number;
    endReason: string | null;
    generation: number;
    reset: () => void;
    setActivity: (activity: VoiceActivityType, generation?: number) => void;
    setEndReason: (endReason: string | null, generation?: number) => void;
}

// Exported so tests can call `voiceActivityStore.setState({...})`/`.getState()` directly per the project's
// Zustand testing convention. `ByteChefRealtimeVoiceAdapter` begins a new session at the start of every
// `connect()` call, so a previous session's `endReason` never bleeds into the next one, and a late event from the
// previous session cannot overwrite the new one's state.
export const voiceActivityStore = create<VoiceActivityStateI>()(
    devtools(
        (set, get) => ({
            activity: 'idle',
            beginSession: () => {
                const generation = get().generation + 1;

                set({activity: 'idle', endReason: null, generation});

                return generation;
            },
            endReason: null,
            generation: 0,
            reset: () => set({activity: 'idle', endReason: null}),
            setActivity: (activity, generation) => {
                if (generation !== undefined && generation !== get().generation) {
                    return;
                }

                set({activity});
            },
            setEndReason: (endReason, generation) => {
                if (generation !== undefined && generation !== get().generation) {
                    return;
                }

                set({endReason});
            },
        }),
        {name: 'VoiceActivityStore'}
    )
);

export const useVoiceActivityStore = voiceActivityStore;

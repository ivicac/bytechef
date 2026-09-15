import {beforeEach, describe, expect, it} from 'vitest';

import {voiceActivityStore} from './useVoiceActivityStore';

/**
 * Ported from `client/src/shared/lib/voice/useVoiceActivityStore.test.ts`: the widget's hand-rolled store carries the
 * same session-generation guard as the platform's Zustand store.
 */
describe('voiceActivityStore', () => {
    beforeEach(() => {
        voiceActivityStore.reset();
    });

    it('ignores activity and end reasons from a session that has since been replaced', () => {
        const firstGeneration = voiceActivityStore.beginSession();
        const secondGeneration = voiceActivityStore.beginSession();

        voiceActivityStore.setActivity({tool: 'lookupOrder'}, firstGeneration);
        voiceActivityStore.setEndReason('silence_timeout', firstGeneration);

        expect(voiceActivityStore.getState().activity).toBe('idle');
        expect(voiceActivityStore.getState().endReason).toBeNull();

        voiceActivityStore.setActivity({tool: 'lookupOrder'}, secondGeneration);

        expect(voiceActivityStore.getState().activity).toEqual({tool: 'lookupOrder'});
    });

    it('starts every session from a clean activity and end reason', () => {
        const firstGeneration = voiceActivityStore.beginSession();

        voiceActivityStore.setActivity('reconnecting', firstGeneration);
        voiceActivityStore.setEndReason('provider_closed', firstGeneration);

        voiceActivityStore.beginSession();

        expect(voiceActivityStore.getState().activity).toBe('idle');
        expect(voiceActivityStore.getState().endReason).toBeNull();
    });
});

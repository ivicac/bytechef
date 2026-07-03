import {describe, expect, it} from 'vitest';

import {isAiHubSharingActiveForChat} from '../isAiHubSharingActiveForChat';

// Nothing in this codebase renders the real provider or mounts the composer against a live store, so
// these cases are the only thing pinning this function's behaviour — a caller that stopped calling it,
// or called it with the arguments swapped, would otherwise pass every other test that exercises it.
describe('isAiHubSharingActiveForChat', () => {
    it('returns false when chatId is undefined, even with sharing enabled', () => {
        expect(isAiHubSharingActiveForChat(undefined, true, 17)).toBe(false);
    });

    it('returns false when sharing is disabled, even with a chatId present', () => {
        expect(isAiHubSharingActiveForChat('thread-1', false, 17)).toBe(false);
    });

    it('returns false when both chatId is undefined and sharing is disabled', () => {
        expect(isAiHubSharingActiveForChat(undefined, false, 17)).toBe(false);
    });

    // The home page's "New Chat" state: useAiHubStore already holds a locally generated thread id, but
    // no row exists for it until the first turn calls createAiHubChat. Every presence write against that
    // thread id 404s server-side, so the gate must be closed until the row id arrives.
    it('returns false for a locally generated thread id that has no persisted chat row yet', () => {
        expect(isAiHubSharingActiveForChat('thread-1', true, undefined)).toBe(false);
    });

    it('returns true only when a chatId is present, sharing is enabled AND the chat is persisted', () => {
        expect(isAiHubSharingActiveForChat('thread-1', true, 17)).toBe(true);
    });
});

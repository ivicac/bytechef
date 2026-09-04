import {describe, expect, it} from 'vitest';

import {isAiHubSharingActiveForChat} from '../isAiHubSharingActiveForChat';

// Nothing in this codebase renders the real provider or mounts the composer against a live store, so
// these cases are the only thing pinning this function's behaviour — a caller that stopped calling it,
// or called it with the arguments swapped, would otherwise pass every other test that exercises it.
describe('isAiHubSharingActiveForChat', () => {
    it('returns false when chatId is undefined, even with sharing enabled', () => {
        expect(isAiHubSharingActiveForChat(undefined, true)).toBe(false);
    });

    it('returns false when sharing is disabled, even with a chatId present', () => {
        expect(isAiHubSharingActiveForChat('thread-1', false)).toBe(false);
    });

    it('returns false when both chatId is undefined and sharing is disabled', () => {
        expect(isAiHubSharingActiveForChat(undefined, false)).toBe(false);
    });

    it('returns true only when a chatId is present AND sharing is enabled', () => {
        expect(isAiHubSharingActiveForChat('thread-1', true)).toBe(true);
    });
});

import {ThreadStatusI} from '@/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient';
import {beforeEach, describe, expect, it} from 'vitest';

import {aiHubChatsStore} from '../useAiHubChatsStore';

function buildStatus(overrides: Partial<ThreadStatusI> = {}): ThreadStatusI {
    return {
        inFlight: false,
        messageCount: 0,
        presence: [],
        runningUserId: null,
        runningUserName: null,
        updatedAt: 0,
        ...overrides,
    };
}

describe('useAiHubChatsStore', () => {
    beforeEach(() => {
        aiHubChatsStore.setState({
            activeFilter: 'ACTIVE',
            chatActivity: {},
            currentChatId: undefined,
            searchTerm: '',
            threadStatus: {},
        });
    });

    it('setActiveFilter updates activeFilter to the given value', () => {
        aiHubChatsStore.getState().setActiveFilter('ARCHIVED');

        expect(aiHubChatsStore.getState().activeFilter).toBe('ARCHIVED');
    });

    it('setCurrentChatId updates currentChatId to the given number', () => {
        aiHubChatsStore.getState().setCurrentChatId(42);

        expect(aiHubChatsStore.getState().currentChatId).toBe(42);
    });

    it('setCurrentChatId accepts undefined to clear the selection', () => {
        aiHubChatsStore.getState().setCurrentChatId(7);
        aiHubChatsStore.getState().setCurrentChatId(undefined);

        expect(aiHubChatsStore.getState().currentChatId).toBeUndefined();
    });

    it('setSearchTerm updates searchTerm to the given string', () => {
        aiHubChatsStore.getState().setSearchTerm('my chat');

        expect(aiHubChatsStore.getState().searchTerm).toBe('my chat');
    });

    describe('setThreadStatus', () => {
        it('merges a new thread status by thread id, leaving existing entries for other threads untouched', () => {
            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus({messageCount: 1})});

            aiHubChatsStore.getState().setThreadStatus({'thread-b': buildStatus({messageCount: 2})});

            expect(aiHubChatsStore.getState().threadStatus).toEqual({
                'thread-a': buildStatus({messageCount: 1}),
                'thread-b': buildStatus({messageCount: 2}),
            });
        });

        it('replaces an existing thread status entirely rather than deep-merging its fields', () => {
            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus({inFlight: true, messageCount: 5})});

            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus({messageCount: 1})});

            expect(aiHubChatsStore.getState().threadStatus['thread-a']).toEqual(buildStatus({messageCount: 1}));
        });

        it('leaves chatActivity untouched', () => {
            aiHubChatsStore.getState().setActivityState('thread-a', 'running');

            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus({inFlight: true})});

            expect(aiHubChatsStore.getState().chatActivity).toEqual({'thread-a': 'running'});
        });

        // Every poll response is freshly parsed, so a naive spread handed each selector a new object on
        // every tick for a thread nothing had happened to. These pin the identity, not just the value —
        // toEqual would pass either way, which is exactly how this went unnoticed.
        it('keeps the previous entry and the previous map when a poll repeats an identical status', () => {
            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus({messageCount: 3})});

            const statusAfterFirstPoll = aiHubChatsStore.getState().threadStatus;

            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus({messageCount: 3})});

            expect(aiHubChatsStore.getState().threadStatus).toBe(statusAfterFirstPoll);
            expect(aiHubChatsStore.getState().threadStatus['thread-a']).toBe(statusAfterFirstPoll['thread-a']);
        });

        it('replaces only the thread whose status changed, keeping the others by identity', () => {
            aiHubChatsStore.getState().setThreadStatus({
                'thread-a': buildStatus({messageCount: 3}),
                'thread-b': buildStatus({messageCount: 7}),
            });

            const unchangedEntry = aiHubChatsStore.getState().threadStatus['thread-b'];

            aiHubChatsStore.getState().setThreadStatus({
                'thread-a': buildStatus({messageCount: 4}),
                'thread-b': buildStatus({messageCount: 7}),
            });

            expect(aiHubChatsStore.getState().threadStatus['thread-a'].messageCount).toBe(4);
            expect(aiHubChatsStore.getState().threadStatus['thread-b']).toBe(unchangedEntry);
        });

        it('treats a changed presence roster as a change', () => {
            aiHubChatsStore.getState().setThreadStatus({'thread-a': buildStatus()});

            const statusAfterFirstPoll = aiHubChatsStore.getState().threadStatus;

            aiHubChatsStore.getState().setThreadStatus({
                'thread-a': buildStatus({
                    presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
                }),
            });

            expect(aiHubChatsStore.getState().threadStatus).not.toBe(statusAfterFirstPoll);
            expect(aiHubChatsStore.getState().threadStatus['thread-a'].presence).toHaveLength(1);
        });
    });
});

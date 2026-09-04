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
    });
});

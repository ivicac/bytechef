import {beforeEach, describe, expect, it} from 'vitest';

import {aiHubRetryableErrorStore} from '../useAiHubRetryableErrorStore';

describe('useAiHubRetryableErrorStore', () => {
    beforeEach(() => {
        aiHubRetryableErrorStore.setState({currentError: undefined});
    });

    it('starts with currentError undefined', () => {
        expect(aiHubRetryableErrorStore.getState().currentError).toBeUndefined();
    });

    it('setError stores the retryable error', () => {
        aiHubRetryableErrorStore.getState().setError({
            errorMessage: 'File not found',
            lastUserMessage: 'Create the report',
            toolName: 'createFile',
        });

        const {currentError} = aiHubRetryableErrorStore.getState();

        expect(currentError).not.toBeUndefined();
        expect(currentError!.errorMessage).toBe('File not found');
        expect(currentError!.lastUserMessage).toBe('Create the report');
        expect(currentError!.toolName).toBe('createFile');
    });

    it('clearError removes the stored error', () => {
        aiHubRetryableErrorStore.getState().setError({
            errorMessage: 'Something went wrong',
            lastUserMessage: 'Do the thing',
            toolName: 'doThing',
        });

        aiHubRetryableErrorStore.getState().clearError();

        expect(aiHubRetryableErrorStore.getState().currentError).toBeUndefined();
    });
});

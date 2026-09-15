import {act, renderHook} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import {useWorkflowTestVoiceSession} from './useWorkflowTestVoiceSession';

const {BrowserVoiceSessionMock, issueWorkflowTestVoiceSessionTokenMock} = vi.hoisted(() => ({
    BrowserVoiceSessionMock: vi.fn(),
    issueWorkflowTestVoiceSessionTokenMock: vi.fn().mockResolvedValue({token: 'tok-1'}),
}));

vi.mock('@/shared/lib/browser-voice/BrowserVoiceSession', () => {
    class BrowserVoiceSession {
        readonly start = vi.fn().mockResolvedValue(undefined);
        readonly stop = vi.fn();
        readonly setMuted = vi.fn();

        constructor(options: unknown) {
            BrowserVoiceSessionMock(options);
        }
    }

    return {BrowserVoiceSession};
});

vi.mock('@/shared/middleware/platform/workflow/test', () => {
    class WorkflowTestApi {
        issueWorkflowTestVoiceSessionToken = issueWorkflowTestVoiceSessionTokenMock;
    }

    return {WorkflowTestApi};
});

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: vi.fn((selector) =>
        selector({
            currentEnvironmentId: 42,
        })
    ),
}));

describe('useWorkflowTestVoiceSession', () => {
    it('builds the WS url with the environment id and no token, and disables reconnect', async () => {
        const {result} = renderHook(() => useWorkflowTestVoiceSession({workflowId: 'workflow-123'}));

        await act(async () => {
            await result.current.start();
        });

        expect(BrowserVoiceSessionMock).toHaveBeenCalledWith(
            expect.objectContaining({
                maxReconnectAttempts: 0,
                mintToken: expect.any(Function),
                url: expect.stringContaining('environmentId=42'),
            })
        );

        const [[passedOptions]] = BrowserVoiceSessionMock.mock.calls;

        expect(passedOptions.url).not.toContain('sessionToken=');

        // mintToken is lazy: it isn't called until BrowserVoiceSession itself invokes it on connect — the
        // hook's own token fetch must not have run yet just from constructing the session.
        expect(issueWorkflowTestVoiceSessionTokenMock).not.toHaveBeenCalled();

        await passedOptions.mintToken();

        // The token is bound to the environment on the server, which authorizes the mint against it.
        expect(issueWorkflowTestVoiceSessionTokenMock).toHaveBeenCalledWith({
            environmentId: 42,
            workflowId: 'workflow-123',
        });
    });
});

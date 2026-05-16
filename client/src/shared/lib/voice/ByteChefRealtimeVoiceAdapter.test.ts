import {describe, expect, it, vi} from 'vitest';

import {ByteChefRealtimeVoiceAdapter} from './ByteChefRealtimeVoiceAdapter';

import type {RealtimeVoiceAdapter} from '@assistant-ui/react';

vi.mock('@/shared/lib/browser-voice/BrowserVoiceSession', () => {
    class BrowserVoiceSession {
        readonly start = vi.fn().mockResolvedValue(undefined);
        readonly stop = vi.fn();
        readonly setMuted = vi.fn();

        constructor(options: {onStatusChange?: (status: string) => void}) {
            setTimeout(() => options.onStatusChange?.('active'), 0);
        }
    }

    return {BrowserVoiceSession};
});

describe('ByteChefRealtimeVoiceAdapter', () => {
    it('mints a token, opens a session, and emits running status', async () => {
        const fetchMock = vi.fn().mockResolvedValue({
            json: () => Promise.resolve({token: 'tkn'}),
            ok: true,
        });

        vi.stubGlobal('fetch', fetchMock);

        const adapter = new ByteChefRealtimeVoiceAdapter({
            tokenUrl: 'http://server/webhooks/x/voice-session-token',
        });

        const session = adapter.connect({});
        const statuses: RealtimeVoiceAdapter.Status[] = [];

        session.onStatusChange((status) => statuses.push(status));

        await new Promise((resolve) => setTimeout(resolve, 10));

        expect(fetchMock).toHaveBeenCalledWith(
            'http://server/webhooks/x/voice-session-token',
            expect.objectContaining({method: 'POST'})
        );
        expect(statuses).toContainEqual({type: 'running'});
    });
});

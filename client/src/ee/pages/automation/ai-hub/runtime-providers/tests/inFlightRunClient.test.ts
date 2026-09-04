import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ThreadStatusI, probeInFlightStatus, probeThreadStatus, sendPresence} from '../inFlightRunClient';

/**
 * Focused coverage for the `/status` and `/presence` client adapters, both of which go through `fetch`
 * and so can be driven from jsdom directly. `attachToInFlightRun` is deliberately not covered here: it
 * constructs an `EventSource`, which jsdom does not implement, so exercising it would mean asserting
 * against a hand-rolled global stub rather than against the adapter's real behaviour.
 */

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

describe('probeThreadStatus', () => {
    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn());
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('returns an empty map without calling fetch when given no thread ids', async () => {
        const result = await probeThreadStatus([]);

        expect(result).toEqual({});
        expect(fetch).not.toHaveBeenCalled();
    });

    it('sends every thread id as a repeated threadIds query param in a single batch', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            json: async () => ({'thread-1': buildStatus({inFlight: true})}),
            ok: true,
        } as Response);

        await probeThreadStatus(['thread-1', 'thread-2']);

        expect(fetch).toHaveBeenCalledTimes(1);

        const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];

        expect(url).toBe('/api/platform/internal/ai/chat/ai_hub/status?threadIds=thread-1&threadIds=thread-2');
        expect(init).toEqual({credentials: 'include'});
    });

    it('splits more than 40 thread ids across multiple batched requests and merges the results', async () => {
        const threadIds = Array.from({length: 85}, (_, index) => `thread-${index}`);

        vi.mocked(fetch)
            .mockResolvedValueOnce({json: async () => ({'thread-0': buildStatus()}), ok: true} as Response)
            .mockResolvedValueOnce({json: async () => ({'thread-40': buildStatus()}), ok: true} as Response)
            .mockResolvedValueOnce({json: async () => ({'thread-80': buildStatus()}), ok: true} as Response);

        const result = await probeThreadStatus(threadIds);

        expect(fetch).toHaveBeenCalledTimes(3);
        expect(Object.keys(result)).toEqual(['thread-0', 'thread-40', 'thread-80']);
    });

    it('resolves to an empty map for a non-OK response without throwing', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({ok: false, status: 500} as Response);

        const result = await probeThreadStatus(['thread-1']);

        expect(result).toEqual({});
    });

    it('resolves to an empty map when fetch itself rejects (network error)', async () => {
        vi.mocked(fetch).mockRejectedValueOnce(new Error('network down'));

        const result = await probeThreadStatus(['thread-1']);

        expect(result).toEqual({});
    });

    it('omits a thread the caller cannot view from the result rather than reporting it with a default status', async () => {
        // The server drops threads the caller cannot view (or that no longer exist) from the response
        // map entirely. probeThreadStatus must pass that gap through unchanged rather than filling in a
        // default entry — a missing key means "not yours, or gone", never "idle".
        vi.mocked(fetch).mockResolvedValueOnce({
            json: async () => ({'thread-1': buildStatus({inFlight: true})}),
            ok: true,
        } as Response);

        const result = await probeThreadStatus(['thread-1', 'thread-hidden']);

        expect(result).toEqual({'thread-1': buildStatus({inFlight: true})});
        expect('thread-hidden' in result).toBe(false);
    });
});

describe('probeInFlightStatus', () => {
    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn());
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
    });

    it('projects each ThreadStatusI down to its inFlight boolean', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            json: async () => ({
                'thread-1': buildStatus({inFlight: true}),
                'thread-2': buildStatus({inFlight: false}),
            }),
            ok: true,
        } as Response);

        const result = await probeInFlightStatus(['thread-1', 'thread-2']);

        expect(result).toEqual({'thread-1': true, 'thread-2': false});
    });

    it('omits a thread missing from the status map, matching its pre-/status contract', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({
            json: async () => ({'thread-1': buildStatus({inFlight: true})}),
            ok: true,
        } as Response);

        const result = await probeInFlightStatus(['thread-1', 'thread-hidden']);

        expect(result).toEqual({'thread-1': true});
    });
});

describe('sendPresence', () => {
    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true} as Response));
        document.cookie = 'XSRF-TOKEN=test-xsrf-token';
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
        document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 UTC';
    });

    it('POSTs the state to the presence endpoint with credentials and the XSRF header', async () => {
        await sendPresence('thread-1', 'VIEWING');

        expect(fetch).toHaveBeenCalledWith('/api/platform/internal/ai/chat/ai_hub/thread-1/presence', {
            body: JSON.stringify({state: 'VIEWING'}),
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': 'test-xsrf-token',
            },
            method: 'POST',
        });
    });

    it('URL-encodes the thread id in the endpoint path', async () => {
        await sendPresence('thread/needs encoding', 'LEFT');

        const [url] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];

        expect(url).toBe('/api/platform/internal/ai/chat/ai_hub/thread%2Fneeds%20encoding/presence');
    });

    it('swallows a non-OK response without throwing', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({ok: false, status: 403} as Response);

        await expect(sendPresence('thread-1', 'TYPING')).resolves.toBeUndefined();
    });

    it('swallows a network error without throwing', async () => {
        vi.mocked(fetch).mockRejectedValueOnce(new Error('network down'));

        await expect(sendPresence('thread-1', 'TYPING')).resolves.toBeUndefined();
    });
});

import {useUpdateConnectionMutation} from '@/ee/shared/mutations/embedded/connections.mutations';
import {createTestQueryClientWrapper} from '@/shared/util/test-utils';
import {renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

// Exercises the real generated ConnectionApi/UpdateConnectionRequestToJSON pipeline by mocking only
// global.fetch -- unlike ConnectionDialog.test.tsx, which injects a fake useUpdateConnectionMutation and
// therefore never runs this module at all. This is the module where the original "shared" field was
// silently dropped from the request body before it was added.
describe('useUpdateConnectionMutation', () => {
    beforeEach(() => {
        global.fetch = vi.fn().mockResolvedValue({status: 200} as Response);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('forwards shared in the PATCH body when the connection carries a value', async () => {
        const {result} = renderHook(() => useUpdateConnectionMutation(), {
            wrapper: createTestQueryClientWrapper(),
        });

        await result.current.mutateAsync({
            componentName: 'acme',
            connectionVersion: 1,
            id: 5,
            name: 'House Slack',
            parameters: {},
            shared: true,
            tags: [],
            version: 1,
        });

        await waitFor(() => expect(global.fetch).toHaveBeenCalled());

        const [url, init] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];

        expect(url).toBe('/api/embedded/internal/connections/5');
        expect(init.method).toBe('PATCH');
        expect(JSON.parse(init.body as string)).toMatchObject({shared: true});
    });

    it('omits shared from the PATCH body when the connection carries no value', async () => {
        const {result} = renderHook(() => useUpdateConnectionMutation(), {
            wrapper: createTestQueryClientWrapper(),
        });

        await result.current.mutateAsync({
            componentName: 'acme',
            connectionVersion: 1,
            id: 5,
            name: 'House Slack',
            parameters: {},
            tags: [],
            version: 1,
        });

        await waitFor(() => expect(global.fetch).toHaveBeenCalled());

        const [, init] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0];

        // JSON.stringify drops a key whose value is undefined -- this is the tri-state contract the server
        // relies on: an absent "shared" key means "don't touch", never "unshare".
        expect(JSON.parse(init.body as string)).not.toHaveProperty('shared');
    });
});

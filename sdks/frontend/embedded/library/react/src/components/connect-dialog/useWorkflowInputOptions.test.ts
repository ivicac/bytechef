import {act, renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import useWorkflowInputOptions from './useWorkflowInputOptions';
import {optionsCacheKey} from './utils';

describe('useWorkflowInputOptions', () => {
    beforeEach(() => {
        vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    it('does not fetch when integrationInstanceId is missing', () => {
        const apiFetch = vi.fn().mockResolvedValue([]);

        const {result} = renderHook(() => useWorkflowInputOptions(apiFetch, undefined));

        act(() => result.current.loadOptions('wf-1', 'channel', 'channelId', {}));

        expect(apiFetch).not.toHaveBeenCalled();
    });

    it('posts the option request and stores the result under the cache key', async () => {
        const options = [
            {label: 'General', value: 'C1'},
            {label: 'Random', value: 'C2'},
        ];
        const apiFetch = vi.fn().mockResolvedValue(options);

        const {result} = renderHook(() => useWorkflowInputOptions(apiFetch, 7));

        await act(async () => {
            result.current.loadOptions('wf-1', 'channel', 'channelId', {workspace: 'W1'});
        });

        expect(apiFetch).toHaveBeenCalledWith('/api/embedded/v1/integration-instances/7/workflows/wf-1/options', {
            body: {inputName: 'channel', lookupDependsOnValues: {workspace: 'W1'}, propertyName: 'channelId'},
            method: 'POST',
        });

        const cacheKey = optionsCacheKey('wf-1', 'channel', 'channelId', {workspace: 'W1'});

        expect(result.current.optionsByKey[cacheKey]).toEqual(options);
    });

    it('does not fetch again for an already cached key', async () => {
        const apiFetch = vi.fn().mockResolvedValue([{label: 'General', value: 'C1'}]);

        const {result} = renderHook(() => useWorkflowInputOptions(apiFetch, 7));

        await act(async () => {
            result.current.loadOptions('wf-1', 'channel', 'channelId', {});
        });

        await act(async () => {
            result.current.loadOptions('wf-1', 'channel', 'channelId', {});
        });

        expect(apiFetch).toHaveBeenCalledTimes(1);
    });

    it('deduplicates concurrent in-flight requests for the same key', () => {
        let resolveFetch: (value: unknown) => void = () => {};
        const apiFetch = vi.fn().mockReturnValue(
            new Promise((resolve) => {
                resolveFetch = resolve;
            })
        );

        const {result} = renderHook(() => useWorkflowInputOptions(apiFetch, 7));

        act(() => {
            result.current.loadOptions('wf-1', 'channel', 'channelId', {});
            result.current.loadOptions('wf-1', 'channel', 'channelId', {});
        });

        expect(apiFetch).toHaveBeenCalledTimes(1);

        resolveFetch([]);
    });

    it('clears the cache on resetOptions', async () => {
        const apiFetch = vi.fn().mockResolvedValue([{label: 'General', value: 'C1'}]);

        const {result} = renderHook(() => useWorkflowInputOptions(apiFetch, 7));

        await act(async () => {
            result.current.loadOptions('wf-1', 'channel', 'channelId', {});
        });

        act(() => result.current.resetOptions());

        expect(result.current.optionsByKey).toEqual({});
    });
});

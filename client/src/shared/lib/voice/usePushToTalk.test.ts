import {act, renderHook, waitFor} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {usePushToTalk} from './usePushToTalk';

class MockMediaRecorder {
    static isTypeSupported = vi.fn().mockReturnValue(true);
    ondataavailable: ((event: {data: Blob}) => void) | null = null;
    onstop: (() => void) | null = null;
    state: 'inactive' | 'recording' = 'inactive';

    constructor(
        public stream: MediaStream,
        public options: MediaRecorderOptions
    ) {}

    start() {
        this.state = 'recording';
    }

    stop() {
        this.state = 'inactive';
        this.ondataavailable?.({data: new Blob(['mock-audio'], {type: 'audio/webm'})});
        this.onstop?.();
    }
}

describe('usePushToTalk', () => {
    let fetchMock: ReturnType<typeof vi.fn>;
    let getUserMediaMock: ReturnType<typeof vi.fn>;
    let onTranscript: (text: string) => void;

    beforeEach(() => {
        fetchMock = vi.fn().mockResolvedValue({
            json: () => Promise.resolve({durationMs: 1000, locale: 'en', text: 'hello world'}),
            ok: true,
        });
        onTranscript = vi.fn() as unknown as (text: string) => void;

        getUserMediaMock = vi.fn().mockResolvedValue({
            getTracks: () => [{stop: vi.fn()}],
        } as unknown as MediaStream);

        vi.stubGlobal('fetch', fetchMock);
        vi.stubGlobal('MediaRecorder', MockMediaRecorder);

        Object.defineProperty(globalThis.navigator, 'mediaDevices', {
            configurable: true,
            value: {getUserMedia: getUserMediaMock},
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('uploads the recorded blob and calls onTranscript with the returned text', async () => {
        const {result} = renderHook(() => usePushToTalk({onTranscript, transcribeUrl: 'http://test/transcribe'}));

        await act(async () => {
            await result.current.start();
        });

        expect(result.current.status).toBe('recording');

        await act(async () => {
            await result.current.stop();
        });

        await waitFor(() => expect(onTranscript).toHaveBeenCalledWith('hello world'));
        expect(fetchMock).toHaveBeenCalledWith(
            'http://test/transcribe',
            expect.objectContaining({body: expect.any(FormData), method: 'POST'})
        );
        expect(result.current.status).toBe('idle');
    });

    it('reports error when fetch fails', async () => {
        fetchMock.mockResolvedValueOnce({ok: false, statusText: 'Server Error'});
        const onError = vi.fn() as unknown as (message: string) => void;

        const {result} = renderHook(() =>
            usePushToTalk({onError, onTranscript, transcribeUrl: 'http://test/transcribe'})
        );

        await act(async () => {
            await result.current.start();
            await result.current.stop();
        });

        await waitFor(() => expect(result.current.status).toBe('error'));
        expect(onError).toHaveBeenCalledWith(expect.stringContaining('Server Error'));
    });
});

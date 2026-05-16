import {useCallback, useRef, useState} from 'react';

export type PushToTalkStatusType = 'idle' | 'recording' | 'transcribing' | 'error';

interface UsePushToTalkArgsI {
    locale?: string;
    onError?: (message: string) => void;
    onTranscript: (text: string) => void;
    transcribeUrl: string;
}

interface UsePushToTalkResultI {
    error: string | null;
    start: () => Promise<void>;
    status: PushToTalkStatusType;
    stop: () => Promise<void>;
}

function pickMimeType(): string {
    if (typeof MediaRecorder === 'undefined') return 'audio/webm';

    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus';

    if (MediaRecorder.isTypeSupported('audio/mp4')) return 'audio/mp4';

    return 'audio/webm';
}

export function usePushToTalk({
    locale,
    onError,
    onTranscript,
    transcribeUrl,
}: UsePushToTalkArgsI): UsePushToTalkResultI {
    const [status, setStatus] = useState<PushToTalkStatusType>('idle');
    const [error, setError] = useState<string | null>(null);
    const recorderRef = useRef<MediaRecorder | null>(null);
    const streamRef = useRef<MediaStream | null>(null);
    const chunksRef = useRef<Blob[]>([]);

    const reportError = useCallback(
        (message: string) => {
            setStatus('error');
            setError(message);
            onError?.(message);
        },
        [onError]
    );

    const start = useCallback(async () => {
        try {
            setError(null);
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {echoCancellation: true, noiseSuppression: true},
            });

            const mimeType = pickMimeType();
            const recorder = new MediaRecorder(stream, {mimeType});

            chunksRef.current = [];
            recorder.ondataavailable = (event) => {
                if (event.data && event.data.size > 0) chunksRef.current.push(event.data);
            };

            recorderRef.current = recorder;
            streamRef.current = stream;
            recorder.start();
            setStatus('recording');
        } catch (caught) {
            reportError(caught instanceof Error ? caught.message : 'Microphone unavailable');
        }
    }, [reportError]);

    const stop = useCallback(async () => {
        const recorder = recorderRef.current;
        const stream = streamRef.current;

        if (!recorder || !stream) return;

        const finished = new Promise<void>((resolve) => {
            recorder.onstop = () => resolve();
        });

        recorder.stop();
        await finished;

        for (const track of stream.getTracks()) track.stop();

        recorderRef.current = null;
        streamRef.current = null;

        const blob = new Blob(chunksRef.current, {type: recorder.mimeType || 'audio/webm'});

        chunksRef.current = [];
        setStatus('transcribing');

        try {
            const form = new FormData();

            form.append('audio', blob, 'utterance.webm');

            if (locale) form.append('locale', locale);

            const response = await fetch(transcribeUrl, {body: form, method: 'POST'});

            if (!response.ok) {
                reportError(`Transcribe failed: ${response.statusText}`);
                return;
            }

            const data = (await response.json()) as {text: string};

            onTranscript(data.text);
            setStatus('idle');
        } catch (caught) {
            reportError(caught instanceof Error ? caught.message : 'Transcribe failed');
        }
    }, [locale, onTranscript, reportError, transcribeUrl]);

    return {error, start, status, stop};
}

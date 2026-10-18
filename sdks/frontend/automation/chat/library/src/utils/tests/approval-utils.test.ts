import {describe, expect, it} from 'vitest';

import {isAcceptedResumeResponse, isEventStreamResponse} from '../approval-utils';

describe('isAcceptedResumeResponse', () => {
    it('accepts 204', () => {
        expect(isAcceptedResumeResponse(new Response(null, {status: 204}))).toBe(true);
    });

    it('accepts 200 with an event stream', () => {
        expect(
            isAcceptedResumeResponse(new Response('', {headers: {'Content-Type': 'text/event-stream'}, status: 200}))
        ).toBe(true);
    });

    it('accepts 200 with JSON', () => {
        expect(
            isAcceptedResumeResponse(
                new Response('{}', {headers: {'Content-Type': 'application/json;charset=UTF-8'}, status: 200})
            )
        ).toBe(true);
    });

    it('rejects 200 with an HTML page', () => {
        expect(
            isAcceptedResumeResponse(
                new Response('<html></html>', {headers: {'Content-Type': 'text/html'}, status: 200})
            )
        ).toBe(false);
    });

    it('rejects error statuses', () => {
        expect(isAcceptedResumeResponse(new Response(null, {status: 410}))).toBe(false);
        expect(isAcceptedResumeResponse(new Response(null, {status: 409}))).toBe(false);
        expect(isAcceptedResumeResponse(new Response(null, {status: 500}))).toBe(false);
    });

    it('rejects other success statuses', () => {
        expect(isAcceptedResumeResponse(new Response(null, {status: 202}))).toBe(false);
    });
});

describe('isEventStreamResponse', () => {
    it('detects an event stream', () => {
        expect(isEventStreamResponse(new Response('', {headers: {'Content-Type': 'text/event-stream'}}))).toBe(true);
        expect(isEventStreamResponse(new Response('{}', {headers: {'Content-Type': 'application/json'}}))).toBe(false);
    });
});

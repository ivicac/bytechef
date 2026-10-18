const EVENT_STREAM_CONTENT_TYPE = 'text/event-stream';
const JSON_CONTENT_TYPE = 'application/json';

export function isEventStreamResponse(response: Response): boolean {
    const contentType = response.headers.get('content-type') ?? '';

    return contentType.includes(EVENT_STREAM_CONTENT_TYPE);
}

/**
 * The resume endpoint answers an accepted decision with 204, or with 200 carrying an event stream or JSON. Anything
 * else, notably a 200 HTML page served by a proxy or SPA fallback, means the decision was not recorded.
 */
export function isAcceptedResumeResponse(response: Response): boolean {
    if (response.status === 204) {
        return true;
    }

    if (response.status !== 200) {
        return false;
    }

    const contentType = response.headers.get('content-type') ?? '';

    return contentType.includes(EVENT_STREAM_CONTENT_TYPE) || contentType.includes(JSON_CONTENT_TYPE);
}

import {createContext, useContext} from 'react';

export interface ApprovalResolutionContextI {
    /**
     * Resolves an approval through the surface's own SSE machinery: the provider POSTs the payload to the
     * SSE-negotiated resume endpoint and pipes the resumed run's output (stream deltas, nested ask-user-question or
     * approval events, errors) back into the conversation through its existing event handlers. Fire-and-stream —
     * returns once the stream request is dispatched, not when the resumed run finishes.
     */
    resolveApproval: (resumeId: string, payload: Record<string, unknown>) => void;
}

/**
 * Chat surfaces that can stream an approval resolution's continuation provide this context around their Thread; the
 * inline approval card (ApprovalRequestMessage) uses it when present and falls back to the plain (non-streaming)
 * resume mutation when absent.
 */
export const ApprovalResolutionContext = createContext<ApprovalResolutionContextI | null>(null);

export const useApprovalResolution = () => useContext(ApprovalResolutionContext);

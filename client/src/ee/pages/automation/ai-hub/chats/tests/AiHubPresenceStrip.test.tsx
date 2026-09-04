import {TooltipProvider} from '@/components/ui/tooltip';
import {ThreadStatusI} from '@/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient';
import {render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {sharingEnabledRef} = vi.hoisted(() => ({sharingEnabledRef: {current: true}}));

vi.mock('@/ee/pages/automation/ai-hub/chats/hooks/useAiHubSharingEnabled', () => ({
    useAiHubSharingEnabled: () => sharingEnabledRef.current,
}));

import AiHubPresenceStrip from '../AiHubPresenceStrip';

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

const renderStrip = (props: Parameters<typeof AiHubPresenceStrip>[0]) =>
    render(
        <TooltipProvider>
            <AiHubPresenceStrip {...props} />
        </TooltipProvider>
    );

describe('AiHubPresenceStrip', () => {
    beforeEach(() => {
        sharingEnabledRef.current = true;
    });

    it('renders nothing when threadStatus is undefined (not polled yet, or access lost)', () => {
        const {container} = renderStrip({currentUserId: 1, threadStatus: undefined});

        expect(container).toBeEmptyDOMElement();
    });

    it('renders nothing for an idle, unshared chat (empty presence, not in flight)', () => {
        const {container} = renderStrip({currentUserId: 1, threadStatus: buildStatus()});

        expect(container).toBeEmptyDOMElement();
    });

    it('renders one avatar per presence entry', () => {
        renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({
                presence: [
                    {lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'},
                    {lastSeen: '2026-09-02T10:00:01Z', state: 'VIEWING', userId: 3, userName: 'bob smith'},
                ],
            }),
        });

        expect(screen.getByText('A')).toBeInTheDocument();
        expect(screen.getByText('BS')).toBeInTheDocument();
    });

    it('shows typing dots for a TYPING presence entry', () => {
        renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'TYPING', userId: 2, userName: 'ana'}],
            }),
        });

        expect(screen.getByTestId('presence-typing-dots')).toBeInTheDocument();
    });

    it('omits typing dots for a VIEWING presence entry', () => {
        renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
            }),
        });

        expect(screen.queryByTestId('presence-typing-dots')).not.toBeInTheDocument();
    });

    it("shows the running-turn line when someone else's turn is in flight", () => {
        renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({inFlight: true, runningUserId: 2, runningUserName: 'Ana'}),
        });

        expect(screen.getByTestId('presence-running-line')).toHaveTextContent("Ana's turn is running");
    });

    it('omits the running-turn line when the in-flight turn belongs to the caller themselves', () => {
        const {container} = renderStrip({
            currentUserId: 2,
            threadStatus: buildStatus({inFlight: true, runningUserId: 2, runningUserName: 'Ana'}),
        });

        expect(screen.queryByTestId('presence-running-line')).not.toBeInTheDocument();
        expect(container).toBeEmptyDOMElement();
    });

    it('omits the running-turn line once inFlight is false, even if runningUserId is stale', () => {
        renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({
                inFlight: false,
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
                runningUserId: 2,
                runningUserName: 'Ana',
            }),
        });

        expect(screen.queryByTestId('presence-running-line')).not.toBeInTheDocument();
    });

    it('renders both the avatar strip and the running-turn line together', () => {
        renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({
                inFlight: true,
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
                runningUserId: 2,
                runningUserName: 'Ana',
            }),
        });

        expect(screen.getByText('A')).toBeInTheDocument();
        expect(screen.getByTestId('presence-running-line')).toHaveTextContent("Ana's turn is running");
    });

    it('renders nothing when useAiHubSharingEnabled returns false, even with presence and an in-flight turn to show', () => {
        sharingEnabledRef.current = false;

        const {container} = renderStrip({
            currentUserId: 1,
            threadStatus: buildStatus({
                inFlight: true,
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
                runningUserId: 2,
                runningUserName: 'Ana',
            }),
        });

        expect(container).toBeEmptyDOMElement();
        expect(screen.queryByTestId('ai-hub-presence-strip')).not.toBeInTheDocument();
    });
});

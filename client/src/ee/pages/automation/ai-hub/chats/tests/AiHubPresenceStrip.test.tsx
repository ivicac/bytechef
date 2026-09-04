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

type PresenceStripPropsType = Parameters<typeof AiHubPresenceStrip>[0];

// Every prop is defaulted to the "nothing to show" value so each test names only what it is about.
const renderStrip = (props: Partial<PresenceStripPropsType>) =>
    render(
        <TooltipProvider>
            <AiHubPresenceStrip
                currentUserId={undefined}
                pendingApprovalUserName={null}
                threadStatus={undefined}
                {...props}
            />
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

    it('names whose approval a gated tool call is waiting on, even with nothing else to show', () => {
        // The whole point: while an approval waits, presence is empty and inFlight is false, so without
        // this line the chat reads as "idle, nobody here" — the conflation this strip exists to prevent.
        renderStrip({pendingApprovalUserName: 'Ivica', threadStatus: buildStatus()});

        expect(screen.getByTestId('presence-pending-approval-line')).toHaveTextContent("Waiting for Ivica's approval");
    });

    it('renders the pending-approval line alongside the roster and the running-turn line', () => {
        renderStrip({
            currentUserId: 1,
            pendingApprovalUserName: 'Ivica',
            threadStatus: buildStatus({
                inFlight: true,
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
                runningUserId: 2,
                runningUserName: 'Ana',
            }),
        });

        expect(screen.getByText('A')).toBeInTheDocument();
        expect(screen.getByTestId('presence-running-line')).toBeInTheDocument();
        expect(screen.getByTestId('presence-pending-approval-line')).toBeInTheDocument();
    });

    it('renders no pending-approval line when nothing is pending', () => {
        renderStrip({
            pendingApprovalUserName: null,
            threadStatus: buildStatus({
                presence: [{lastSeen: '2026-09-02T10:00:00Z', state: 'VIEWING', userId: 2, userName: 'ana'}],
            }),
        });

        expect(screen.queryByTestId('presence-pending-approval-line')).not.toBeInTheDocument();
    });

    it('renders nothing at all when sharing is off, pending approval included', () => {
        sharingEnabledRef.current = false;

        const {container} = renderStrip({pendingApprovalUserName: 'Ivica', threadStatus: buildStatus()});

        expect(container).toBeEmptyDOMElement();
    });
});

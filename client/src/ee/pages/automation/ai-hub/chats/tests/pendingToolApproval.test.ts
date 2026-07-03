import {type AiHubChatI} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {type ThreadMessageLike} from '@assistant-ui/react';
import {describe, expect, it} from 'vitest';

import {canResolveToolApproval, getToolApprovalOwnerLabel, hasPendingToolApproval} from '../pendingToolApproval';

function buildChat(overrides: Partial<AiHubChatI> = {}): AiHubChatI {
    return {
        aiAgentId: null,
        autoTitled: true,
        createdAt: new Date().toISOString(),
        id: 5,
        isOwner: true,
        kind: 'STANDARD',
        lastPreview: null,
        messageCount: 0,
        ownerName: null,
        ownerUserId: 1,
        participation: 'VIEW',
        status: 'ACTIVE',
        threadId: 'thread-5',
        title: 'My chat',
        updatedAt: new Date().toISOString(),
        userId: 1,
        visibility: 'PRIVATE',
        workflowExecutionId: null,
        workspaceId: 7,
        ...overrides,
    };
}

function approvalMessage(data: Record<string, unknown>): ThreadMessageLike {
    return {
        content: [{data, type: 'data-tool-approval-request'}],
        role: 'assistant',
    } as ThreadMessageLike;
}

describe('hasPendingToolApproval', () => {
    it('is false for an empty transcript', () => {
        expect(hasPendingToolApproval([])).toBe(false);
    });

    it('is false for a transcript of ordinary text messages', () => {
        expect(
            hasPendingToolApproval([
                {content: 'hi', role: 'user'},
                {content: 'hello', role: 'assistant'},
            ])
        ).toBe(false);
    });

    it('is true for a card that is still awaiting a decision', () => {
        expect(hasPendingToolApproval([approvalMessage({approvalId: 1, awaitingApproval: true})])).toBe(true);
    });

    /*
     * useSwitchChat stitches resolvedStatus onto every restored card whose approval has since been decided,
     * and leaves PENDING ones untouched. Reading awaitingApproval alone would therefore report an approval
     * decided days ago as still blocking the chat, on every reload, forever.
     */
    it('is false once a decision has been stitched onto the card', () => {
        expect(
            hasPendingToolApproval([
                approvalMessage({approvalId: 1, awaitingApproval: true, resolvedStatus: 'APPROVED'}),
            ])
        ).toBe(false);
    });

    it('is false for a card that never awaited a decision', () => {
        expect(hasPendingToolApproval([approvalMessage({approvalId: 1, awaitingApproval: false})])).toBe(false);
    });

    it('finds a pending card several messages back, since the chat can keep going while it waits', () => {
        expect(
            hasPendingToolApproval([
                {content: 'hi', role: 'user'},
                approvalMessage({approvalId: 1, awaitingApproval: true}),
                {content: 'anything else?', role: 'assistant'},
            ])
        ).toBe(true);
    });

    it('ignores other interactive data parts', () => {
        const askUserQuestion = {
            content: [{data: {awaitingAnswer: true}, type: 'data-ask-user-question'}],
            role: 'assistant',
        } as ThreadMessageLike;

        expect(hasPendingToolApproval([askUserQuestion])).toBe(false);
    });
});

describe('canResolveToolApproval', () => {
    it('is true for the chat owner', () => {
        expect(canResolveToolApproval(buildChat({isOwner: true}), false)).toBe(true);
    });

    it('is true for an instance admin who does not own the chat', () => {
        expect(canResolveToolApproval(buildChat({isOwner: false}), true)).toBe(true);
    });

    // Deliberately NOT any participant: the server's canResolve routes through canManage, which is
    // owner-or-admin regardless of the chat's participation level.
    it('is false for a participant who is neither owner nor admin', () => {
        expect(canResolveToolApproval(buildChat({isOwner: false, participation: 'PARTICIPATE'}), false)).toBe(false);
    });

    it('is false when there is no chat', () => {
        expect(canResolveToolApproval(undefined, true)).toBe(false);
    });
});

describe('getToolApprovalOwnerLabel', () => {
    it('uses the owner name when it resolved', () => {
        expect(getToolApprovalOwnerLabel(buildChat({ownerName: 'Ivica'}))).toBe('Ivica');
    });

    it('falls back to a role rather than an empty name', () => {
        expect(getToolApprovalOwnerLabel(buildChat({ownerName: null}))).toBe('the owner');
        expect(getToolApprovalOwnerLabel(undefined)).toBe('the owner');
    });
});

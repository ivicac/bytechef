import {NodeDataType} from '@/shared/types';
import {describe, expect, it, vi} from 'vitest';

import {getWorkflowNodeMenuItems} from './getWorkflowNodeMenuItems';

const TRIGGER_DATA = {
    componentName: 'webhook',
    label: 'Webhook',
    name: 'webhook_1',
    operationName: 'autoRespondWithHTTPOK',
    trigger: true,
    workflowNodeName: 'webhook_1',
} as unknown as NodeDataType;

const TASK_DATA = {
    componentName: 'httpClient',
    label: 'HTTP Client',
    name: 'httpClient_1',
    operationName: 'get',
    workflowNodeName: 'httpClient_1',
} as unknown as NodeDataType;

function getMenuItemKeys(overrides: Partial<Parameters<typeof getWorkflowNodeMenuItems>[0]>): string[] {
    return getWorkflowNodeMenuItems({
        canPaste: false,
        copiedNode: undefined,
        data: TASK_DATA,
        hasSavedPosition: false,
        onCopy: vi.fn(),
        onCut: vi.fn(),
        onDelete: vi.fn(),
        onInfo: vi.fn(),
        onPaste: vi.fn(),
        onRename: vi.fn(),
        onResetPosition: vi.fn(),
        onSwitch: vi.fn(),
        showCopyAction: false,
        showCutAction: false,
        showDeleteAction: false,
        showDisableAction: false,
        showInfoAction: false,
        showRenameAction: false,
        showReplaceAction: false,
        ...overrides,
    }).map((menuItem) => menuItem.key);
}

describe('getWorkflowNodeMenuItems trigger', () => {
    it('offers copy and cut on a trigger when both are shown', () => {
        const menuItemKeys = getMenuItemKeys({data: TRIGGER_DATA, showCopyAction: true, showCutAction: true});

        expect(menuItemKeys).toContain('copy');
        expect(menuItemKeys).toContain('cut');
    });

    it('omits cut on a trigger when cut is not shown', () => {
        const menuItemKeys = getMenuItemKeys({data: TRIGGER_DATA, showCopyAction: true, showCutAction: false});

        expect(menuItemKeys).toContain('copy');
        expect(menuItemKeys).not.toContain('cut');
    });

    it('offers paste on a trigger when a copied trigger can be pasted', () => {
        const menuItemKeys = getMenuItemKeys({
            canPaste: true,
            copiedNode: TRIGGER_DATA,
            data: TRIGGER_DATA,
            showCopyAction: true,
        });

        expect(menuItemKeys).toContain('paste');
    });

    it('keeps replace, rename and info on a trigger', () => {
        const menuItemKeys = getMenuItemKeys({
            data: TRIGGER_DATA,
            showCopyAction: true,
            showCutAction: true,
            showInfoAction: true,
            showRenameAction: true,
        });

        expect(menuItemKeys).toEqual(expect.arrayContaining(['replace', 'rename', 'info']));
    });

    it('keeps delete last on a trigger', () => {
        const menuItemKeys = getMenuItemKeys({
            data: TRIGGER_DATA,
            showCopyAction: true,
            showCutAction: true,
            showDeleteAction: true,
            showInfoAction: true,
        });

        expect(menuItemKeys[menuItemKeys.length - 1]).toBe('delete');
    });
});

describe('getWorkflowNodeMenuItems task', () => {
    it('offers paste on a task when a copied task can be pasted', () => {
        const menuItemKeys = getMenuItemKeys({canPaste: true, copiedNode: TASK_DATA, showCopyAction: true});

        expect(menuItemKeys).toContain('paste');
    });
});

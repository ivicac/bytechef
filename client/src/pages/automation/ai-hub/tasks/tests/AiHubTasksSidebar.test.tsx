import {aiHubTabsStore} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {handleArtifactQuickOpen, openArtifactInTask} from '../AiHubTasksSidebar';
import {AiHubTaskArtifactI, AiHubTaskI} from '../api/tasks.api';

function buildTask(overrides: Partial<AiHubTaskI> = {}): AiHubTaskI {
    return {
        aiHubPersonalAgentId: null,
        autoTitled: true,
        createdAt: new Date().toISOString(),
        id: 1,
        kind: 'STANDARD',
        lastPreview: null,
        messageCount: 0,
        status: 'ACTIVE',
        threadId: 'thread-1',
        title: 'Task',
        updatedAt: new Date().toISOString(),
        userId: 1,
        workflowExecutionId: null,
        workspaceId: 1,
        ...overrides,
    };
}

function buildFileArtifact(overrides: Partial<AiHubTaskArtifactI> = {}): AiHubTaskArtifactI {
    return {
        artifactId: 'file-1',
        artifactName: 'a.txt',
        createdAt: new Date().toISOString(),
        id: 1,
        kind: 'FILE_CREATED',
        metadataJson: null,
        status: 'APPLIED',
        taskId: 1,
        ...overrides,
    };
}

describe('handleArtifactQuickOpen', () => {
    beforeEach(() => {
        aiHubTabsStore.setState({
            activeTabId: undefined,
            activeTaskId: undefined,
            openTabs: [],
            rightPanelOpen: false,
            snapshotsByTaskId: {},
            tasksSidebarCollapsed: true,
        });
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('opens a workflowExecution tab for a WORKFLOW_EXECUTION_STARTED artifact and does not call window.open', () => {
        const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);

        const workflowExecutionArtifact: AiHubTaskArtifactI = {
            artifactId: '777',
            artifactName: 'Run #777',
            createdAt: new Date().toISOString(),
            id: 1,
            kind: 'WORKFLOW_EXECUTION_STARTED',
            metadataJson: null,
            status: 'APPLIED',
            taskId: 42,
        };

        handleArtifactQuickOpen(workflowExecutionArtifact);

        const openTabs = aiHubTabsStore.getState().openTabs;

        expect(openTabs).toHaveLength(1);

        const openedTab = openTabs[0]!;

        expect(openedTab.kind).toBe('workflowExecution');

        if (openedTab.kind === 'workflowExecution') {
            expect(openedTab.workflowExecutionId).toBe(777);
            expect(openedTab.name).toBe('Run #777');
        }

        expect(aiHubTabsStore.getState().rightPanelOpen).toBe(true);

        expect(openSpy).not.toHaveBeenCalled();
    });
});

describe('openArtifactInTask', () => {
    beforeEach(() => {
        aiHubTabsStore.setState({
            activeTabId: undefined,
            activeTaskId: undefined,
            openTabs: [],
            rightPanelOpen: false,
            snapshotsByTaskId: {},
            tasksSidebarCollapsed: true,
        });
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('opens the resource without switching when the artifact belongs to the current task', async () => {
        const switchTask = vi.fn();
        const task = buildTask({id: 42, threadId: 'thread-42'});
        const artifact = buildFileArtifact({artifactId: 'file-42', taskId: 42});

        await openArtifactInTask(artifact, task, 42, switchTask);

        expect(switchTask).not.toHaveBeenCalled();

        const openTabs = aiHubTabsStore.getState().openTabs;

        expect(openTabs).toHaveLength(1);
        expect(openTabs[0]!.kind).toBe('file');
    });

    it('switches to the owning task first, then opens the resource on that task tab set', async () => {
        // Seed: task 1 is active with one tab already open.
        const existingTabId = aiHubTabsStore.getState().openFileTab('file-1', 'a.txt');

        aiHubTabsStore.setState({activeTaskId: 1});

        const switchTask = vi.fn().mockResolvedValue(true);
        const task = buildTask({id: 2, threadId: 'thread-2'});
        const artifact = buildFileArtifact({artifactId: 'file-2', artifactName: 'b.txt', taskId: 2});

        await openArtifactInTask(artifact, task, 1, switchTask);

        expect(switchTask).toHaveBeenCalledWith(task);

        const state = aiHubTabsStore.getState();

        // The tabs store is now mirroring task 2, and the resource opened there — not on task 1.
        expect(state.activeTaskId).toBe(2);
        expect(state.openTabs).toHaveLength(1);

        const openedTab = state.openTabs[0]!;

        expect(openedTab.kind).toBe('file');

        if (openedTab.kind === 'file') {
            expect(openedTab.fileId).toBe('file-2');
        }

        // Task 1's tab was snapshotted away, not lost.
        expect(state.snapshotsByTaskId[1]?.openTabs).toHaveLength(1);
        expect(state.snapshotsByTaskId[1]?.openTabs[0]!.id).toBe(existingTabId);
    });

    it('does not open the resource when the task switch fails', async () => {
        aiHubTabsStore.setState({activeTaskId: 1});

        const switchTask = vi.fn().mockResolvedValue(false);
        const task = buildTask({id: 2, threadId: 'thread-2'});
        const artifact = buildFileArtifact({artifactId: 'file-2', taskId: 2});

        await openArtifactInTask(artifact, task, 1, switchTask);

        expect(switchTask).toHaveBeenCalledWith(task);
        expect(aiHubTabsStore.getState().openTabs).toHaveLength(0);
    });
});

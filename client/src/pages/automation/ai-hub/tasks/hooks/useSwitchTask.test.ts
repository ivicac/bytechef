import {aiHubStore} from '@/pages/automation/ai-hub/stores/useAiHubStore';
import {aiHubTasksStore} from '@/pages/automation/ai-hub/tasks/stores/useAiHubTasksStore';
import {act, renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {AiHubTaskI, getTaskMessages} from '../api/tasks.api';

vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: vi.fn((selector: (state: {currentWorkspaceId: number}) => unknown) =>
        selector({currentWorkspaceId: 99})
    ),
}));

// useSwitchTask gained a useNavigate() call so the route flips when switching tasks from
// any CC page (not just /ai-hub which has its own URL sync). Stub it here so the hook resolves
// cleanly outside a Router context — the tests don't assert on the navigate target since the canonical
// /ai-hub route's URL-sync effect would also have produced the same end state.
vi.mock('react-router-dom', () => ({
    useNavigate: () => vi.fn(),
}));

vi.mock('../api/tasks.api', async () => {
    const actual = await vi.importActual<typeof import('../api/tasks.api')>('../api/tasks.api');

    return {
        ...actual,
        getTaskMessages: vi.fn(),
    };
});

vi.mock('./useTasks', () => ({
    reportMutationError: vi.fn(),
}));

const {useSwitchTask} = await import('./useSwitchTask');
const {reportMutationError} = await import('./useTasks');

const mockGetTaskMessages = vi.mocked(getTaskMessages);
const mockReportMutationError = vi.mocked(reportMutationError);

const buildTask = (overrides: Partial<AiHubTaskI> = {}): AiHubTaskI => ({
    aiHubPersonalAgentId: null,
    autoTitled: false,
    createdAt: '2026-04-01T00:00:00Z',
    id: 7,
    kind: 'STANDARD',
    lastPreview: null,
    messageCount: 0,
    status: 'ACTIVE',
    threadId: 'thread-target',
    title: 'Target',
    updatedAt: '2026-04-01T00:00:00Z',
    userId: 1,
    workflowExecutionId: null,
    workspaceId: 99,
    ...overrides,
});

describe('useSwitchTask', () => {
    beforeEach(() => {
        vi.clearAllMocks();

        aiHubStore.setState({messages: [], taskId: 'thread-source'});
        aiHubTasksStore.setState({currentTaskId: 1});
    });

    it('updates command center store before tasks store', async () => {
        // Ordering matters: if tasks-store flips first, a consumer keyed on currentTaskId
        // will render the new task header against the *previous* messages for one frame.
        const observedThreadIdAtTasksFlip: Array<string | undefined> = [];

        const originalSetId = aiHubTasksStore.getState().setCurrentTaskId;

        aiHubTasksStore.setState({
            setCurrentTaskId: (id) => {
                observedThreadIdAtTasksFlip.push(aiHubStore.getState().taskId);

                originalSetId(id);
            },
        });

        mockGetTaskMessages.mockResolvedValue([{content: 'hi', role: 'user', timestamp: '2026-04-01T00:00:00Z'}]);

        const {result} = renderHook(() => useSwitchTask());

        await act(async () => {
            await result.current(buildTask());
        });

        // When the tasks store flipped, the command center store had already been updated to the target
        // thread — that's the contract this test pins.
        expect(observedThreadIdAtTasksFlip).toEqual(['thread-target']);
        expect(aiHubTasksStore.getState().currentTaskId).toBe(7);
        expect(mockReportMutationError).not.toHaveBeenCalled();
    });

    it('maps user/assistant/system roles and filters tool', async () => {
        mockGetTaskMessages.mockResolvedValue([
            {content: 'hi', role: 'user', timestamp: '2026-04-01T00:00:00Z'},
            {content: 'hello', role: 'assistant', timestamp: '2026-04-01T00:00:01Z'},
            {content: 'sys', role: 'system', timestamp: '2026-04-01T00:00:02Z'},
            {content: 'tool-payload', role: 'tool', timestamp: '2026-04-01T00:00:03Z'},
        ]);

        const {result} = renderHook(() => useSwitchTask());

        await act(async () => {
            await result.current(buildTask());
        });

        expect(aiHubStore.getState().taskId).toBe('thread-target');
        expect(aiHubStore.getState().messages).toEqual([
            {content: 'hi', role: 'user'},
            {content: 'hello', role: 'assistant'},
            {content: 'sys', role: 'system'},
        ]);
    });

    it('drops messages with unknown server roles instead of routing them', async () => {
        const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

        mockGetTaskMessages.mockResolvedValue([
            {content: 'kept', role: 'user', timestamp: '2026-04-01T00:00:00Z'},
            // role-drift: server shipped an unknown role; the silent-drop branch must not let it through
            {content: 'dropped', role: 'TOOL_USE', timestamp: '2026-04-01T00:00:01Z'},
            {content: 'tool-payload', role: 'tool', timestamp: '2026-04-01T00:00:02Z'},
        ]);

        const {result} = renderHook(() => useSwitchTask());

        await act(async () => {
            await result.current(buildTask());
        });

        const stored = aiHubStore.getState().messages;

        expect(stored).toHaveLength(1);
        expect(stored[0]).toMatchObject({content: 'kept', role: 'user'});
        expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('Unknown message role'));

        consoleSpy.mockRestore();
    });

    it('reports the failure and leaves stores untouched when getTaskMessages rejects', async () => {
        const failure = new Error('network down');

        mockGetTaskMessages.mockRejectedValue(failure);

        // Snapshot full state so an over-eager partial setState (e.g. setting taskId before awaiting
        // the message fetch) is caught — without this, a regression that flipped taskId early would
        // pass the existing field-level assertions if the messages happened to be empty in both states.
        const aiHubSnapshot = JSON.stringify({
            messages: aiHubStore.getState().messages,
            taskId: aiHubStore.getState().taskId,
        });
        const tasksSnapshot = JSON.stringify({
            currentTaskId: aiHubTasksStore.getState().currentTaskId,
        });

        const {result} = renderHook(() => useSwitchTask());

        let returned: boolean | undefined;

        await act(async () => {
            returned = await result.current(buildTask());
        });

        expect(returned).toBe(false);
        expect(mockReportMutationError).toHaveBeenCalledWith('Switch task', failure);

        // Byte-identical state — neither store was touched, even partially.
        expect(
            JSON.stringify({
                messages: aiHubStore.getState().messages,
                taskId: aiHubStore.getState().taskId,
            })
        ).toBe(aiHubSnapshot);
        expect(
            JSON.stringify({
                currentTaskId: aiHubTasksStore.getState().currentTaskId,
            })
        ).toBe(tasksSnapshot);
    });

    it('returns true on success so the caller can keep the dialog open on failure', async () => {
        mockGetTaskMessages.mockResolvedValue([{content: 'hi', role: 'user', timestamp: '2026-04-01T00:00:00Z'}]);

        const {result} = renderHook(() => useSwitchTask());

        let returned: boolean | undefined;

        await act(async () => {
            returned = await result.current(buildTask());
        });

        expect(returned).toBe(true);
    });
});

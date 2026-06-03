import {aiHubStore} from '@/pages/automation/ai-hub/stores/useAiHubStore';
import {aiHubTasksStore} from '@/pages/automation/ai-hub/tasks/stores/useAiHubTasksStore';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {ThreadMessageLike} from '@assistant-ui/react';
import {useCallback} from 'react';
import {useNavigate} from 'react-router-dom';

import {AiHubTaskI, getTaskMessages} from '../api/tasks.api';
import {reportMutationError} from './useTasks';

function mapServerRoleToClient(role: string): 'user' | 'assistant' | 'system' | null {
    const normalized = role.toLowerCase();

    if (normalized === 'tool') {
        return null;
    }

    if (normalized === 'user' || normalized === 'assistant' || normalized === 'system') {
        return normalized;
    }

    // Server schema drift (a new role shipped without a client update) would otherwise drop the message
    // silently. Warn so it surfaces in the console instead of vanishing into the filter() call.
    console.warn(`[AiHubTasks] Unknown message role from server: "${role}"`);

    return null;
}

/**
 * Returns whether the switch succeeded so callers can keep their dialog open on failure instead of closing
 * mid-error. The hook intentionally does NOT mutate command center/task state on failure — a partial overwrite
 * would leave the user typing into a phantom thread; reportMutationError surfaces the failure as a toast and the
 * caller should show a banner if they want a non-toast indication.
 *
 * Memoised with `useCallback` keyed only on `currentWorkspaceId` so callers (notably the URL <-> store sync
 * effects in AiHub.tsx) can put it in their dependency array without re-running on every render. A
 * fresh closure each render pulled the URL->store effect into a feedback loop that flashed the task
 * view on click and then reset back to the home view.
 */
export function useSwitchTask() {
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const navigate = useNavigate();

    return useCallback(
        async (task: AiHubTaskI): Promise<boolean> => {
            try {
                const messages = await getTaskMessages({
                    taskId: task.id,
                    workspaceId: currentWorkspaceId,
                });

                const mappedMessages: ThreadMessageLike[] = messages
                    .map((serverMessage) => {
                        const clientRole = mapServerRoleToClient(serverMessage.role);

                        if (clientRole === null) {
                            return null;
                        }

                        return {content: serverMessage.content, role: clientRole} as ThreadMessageLike;
                    })
                    .filter((message): message is ThreadMessageLike => message !== null);

                aiHubStore.setState({
                    messages: mappedMessages,
                    taskId: task.threadId,
                });

                aiHubTasksStore.getState().setCurrentTaskId(task.id);

                // Navigate explicitly so the switch works from any CC page (not just /ai-hub
                // itself, which has its own store→URL sync effect). On the canonical /ai-hub
                // route this navigate is idempotent — AiHub.tsx's effect would fire next render
                // and find URL already matches the store, no-op. From /personal-agents or /workflow-chats
                // (which have no such effect), this is the only thing that flips the route to the
                // selected task. Without it, clicking a task row from those pages
                // updated the stores silently and left the user staring at the unchanged list.
                navigate(`/automation/ai-hub/tasks/${task.id}`);

                return true;
            } catch (error) {
                // Without surfacing this, the user keeps typing into what they think is the new task
                // but is in fact still the previous one. Routing through reportMutationError keeps the toast
                // wording consistent with the other task mutations in useTasks.
                reportMutationError('Switch task', error instanceof Error ? error : new Error(String(error)));

                return false;
            }
        },
        [currentWorkspaceId, navigate]
    );
}

import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useEffect, useRef} from 'react';

import {aiHubComposerStore} from '../composer/stores/useAiHubComposerStore';
import {aiHubAskedQuestionsStore} from '../messages/stores/useAiHubAskedQuestionsStore';
import {aiHubToolCallStore} from '../messages/stores/useAiHubToolCallStore';
import {aiHubProgressStore} from '../progress/stores/useAiHubProgressStore';
import {aiHubRetryableErrorStore} from '../retry/stores/useAiHubRetryableErrorStore';
import {aiHubStore} from '../stores/useAiHubStore';
import {aiHubTabsStore} from '../stores/useAiHubTabsStore';
import {aiHubTasksStore} from '../tasks/stores/useAiHubTasksStore';

/**
 * Resets every AI Hub Zustand store when the active workspace changes. The stores are module-scoped
 * singletons, so without this reset state
 * leaks across workspace switches: the composer would still reference resources from the previous workspace,
 * the tabs panel would still show files that don't exist in the new workspace, the retry banner could surface
 * a stale error, etc.
 *
 * Skips the reset on the initial mount (when there's no previous workspace to compare against) so first-time
 * panel open does not destroy any state the user just configured in another component subtree.
 */
export function useResetAiHubStoresOnWorkspaceChange(): void {
    const isFirstRunRef = useRef(true);
    const previousWorkspaceIdRef = useRef<number | undefined>(undefined);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    useEffect(() => {
        if (isFirstRunRef.current) {
            isFirstRunRef.current = false;
            previousWorkspaceIdRef.current = currentWorkspaceId;

            return;
        }

        const previousWorkspaceId = previousWorkspaceIdRef.current;

        previousWorkspaceIdRef.current = currentWorkspaceId;

        if (previousWorkspaceId === currentWorkspaceId) {
            return;
        }

        aiHubComposerStore.getState().clear();
        aiHubTasksStore.getState().reset();
        aiHubProgressStore.getState().clearProgress();
        aiHubRetryableErrorStore.getState().clearError();
        aiHubTabsStore.getState().reset();
        aiHubToolCallStore.getState().reset();
        aiHubAskedQuestionsStore.getState().reset();
        aiHubStore.getState().resetMessages();
        aiHubStore.getState().generateTaskId();
    }, [currentWorkspaceId]);
}

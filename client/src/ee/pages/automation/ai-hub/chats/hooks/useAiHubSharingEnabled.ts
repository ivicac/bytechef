import {useIsVisibilityEditionEnabled} from '@/shared/hooks/useVisibilityFeatureEnabled';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';

/**
 * Single gate for every AI Hub chat-sharing surface (Tasks 7 and 8): the share dialog and its header
 * menu item, the "Shared with me" sidebar list, the presence strip, the composer's view-only and
 * others'-turn states, and the presence heartbeat / focused-chat status poll that feed them. Both the
 * EE visibility edition and the {@code ff-ai-hub-shared-chats} flag must be on. Keeping the pair in one
 * hook means a future change to either check updates every surface at once, rather than the two drifting
 * across the components that each call it separately.
 *
 * A flagged-off or CE caller must see today's product exactly, not a degraded version of the new one:
 * every surface above renders nothing, and the heartbeat/poll effects that write or read presence data
 * for this feature do not fire at all. The pre-existing per-thread `/status` poll that drives the
 * sidebar's running/paused pulse (predating this feature) is deliberately NOT gated by this hook — see
 * AiHubChatsSidebar's own probe effect.
 */
export const useAiHubSharingEnabled = (): boolean => {
    const isVisibilityEditionEnabled = useIsVisibilityEditionEnabled();
    const isFeatureFlagEnabled = useFeatureFlagsStore();

    return isVisibilityEditionEnabled && isFeatureFlagEnabled('ff-ai-hub-shared-chats');
};

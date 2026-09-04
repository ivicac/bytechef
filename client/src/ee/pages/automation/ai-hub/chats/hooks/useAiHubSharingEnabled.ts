import {useIsVisibilityEditionEnabled} from '@/shared/hooks/useVisibilityFeatureEnabled';

/**
 * Single gate for every AI Hub chat-sharing surface: the share dialog and its header menu item, the
 * "Shared with me" sidebar list, the presence strip, the composer's view-only and others'-turn states,
 * and the presence heartbeat / focused-chat status poll that feed them. Sharing is on wherever the EE
 * visibility edition is — it is a property of the edition, not something a deployment opts into.
 *
 * A thin wrapper over {@link useIsVisibilityEditionEnabled} by design, and deliberately kept: every
 * surface above calls it, so this stays the one place the gate is expressed and a future change to the
 * check updates all of them at once rather than the condition drifting across the components.
 *
 * A CE caller must see today's product exactly, not a degraded version of the new one: every surface
 * above renders nothing, and the heartbeat/poll effects that write or read presence data for this
 * feature do not fire at all. The pre-existing per-thread `/status` poll that drives the sidebar's
 * running/paused pulse (predating this feature) is deliberately NOT gated by this hook — see
 * AiHubChatsSidebar's own probe effect.
 */
export const useAiHubSharingEnabled = (): boolean => useIsVisibilityEditionEnabled();

import {AutomationHubThemeI} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';
import {EmbedInitParamsI} from '@/ee/pages/embedded/shared/useEmbedHandshake';
import {create} from 'zustand';

interface IntegrationMarketplaceStateI {
    initialize: (params: EmbedInitParamsI) => void;
    initialized: boolean;
    theme: AutomationHubThemeI;
}

/**
 * The marketplace's slice of the EMBED_INIT handshake. It shares the hub's theme shape rather than
 * declaring its own, because a vendor embedding both surfaces themes them once — two independent
 * palettes for one product is a difference nobody asked for.
 */
export const useIntegrationMarketplaceStore = create<IntegrationMarketplaceStateI>()((set) => ({
    initialize: (params) =>
        set({
            initialized: true,
            theme: params.theme ?? {},
        }),
    initialized: false,
    theme: {},
}));

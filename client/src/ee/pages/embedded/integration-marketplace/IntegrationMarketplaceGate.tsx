import LoadingDots from '@/components/LoadingDots';
import {applyHubTheme} from '@/ee/pages/embedded/automation-hub/theme/applyHubTheme';
import IntegrationMarketplaceView from '@/ee/pages/embedded/integration-marketplace/IntegrationMarketplaceView';
import {useIntegrationMarketplaceStore} from '@/ee/pages/embedded/integration-marketplace/stores/useIntegrationMarketplaceStore';
import {useTheme} from '@/shared/providers/theme-provider';
import {useEffect} from 'react';
import {useShallow} from 'zustand/react/shallow';

/**
 * Holds the marketplace back until EMBED_INIT has landed. Rendering the catalog first would fire
 * its request before `useFetchInterceptor` has the handshake's JWT and environment in session
 * storage, so the first load would 401 and the user would see an error for a working install.
 */
const IntegrationMarketplaceGate = () => {
    const {initialized, theme} = useIntegrationMarketplaceStore(
        useShallow((state) => ({initialized: state.initialized, theme: state.theme}))
    );

    const {setTheme} = useTheme();

    useEffect(() => {
        if (initialized) {
            setTheme(applyHubTheme(theme));
        }
    }, [initialized, setTheme, theme]);

    if (!initialized) {
        return (
            <div className="flex size-full items-center justify-center" data-testid="marketplace-gate-loading">
                <LoadingDots />
            </div>
        );
    }

    return <IntegrationMarketplaceView />;
};

export default IntegrationMarketplaceGate;

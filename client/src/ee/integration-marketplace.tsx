import {createRoot} from 'react-dom/client';

import '../styles/index.css';

import {TooltipProvider} from '@/components/ui/tooltip';
import EmbeddedIntegrationMarketplaceApp from '@/ee/EmbeddedIntegrationMarketplaceApp';
import IntegrationMarketplaceGate from '@/ee/pages/embedded/integration-marketplace/IntegrationMarketplaceGate';
import I18n from '@/i18n';
import {ThemeProvider} from '@/shared/providers/theme-provider';
import {applicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {StrictMode} from 'react';
import {RouterProvider, createHashRouter} from 'react-router-dom';

const container = document.getElementById('root') as HTMLDivElement;
const root = createRoot(container);
const queryClient = new QueryClient();

// Deliberately NOT awaited, for the same first-paint reason the hub entry does it.
applicationInfoStore.getState().getApplicationInfo();

// Hash routing, like every other embedded entry: an extension-less `/embedded/...` URL falls back
// to index.html in dev, whose session check bounces the iframe to /login. See CLAUDE.md.
const router = createHashRouter([
    {
        children: [{element: <IntegrationMarketplaceGate />, path: 'marketplace'}],
        element: <EmbeddedIntegrationMarketplaceApp />,
        path: '/embedded',
    },
]);

root.render(
    <StrictMode>
        <QueryClientProvider client={queryClient}>
            <ThemeProvider persist={false}>
                <TooltipProvider>
                    <I18n>
                        <RouterProvider router={router} />
                    </I18n>
                </TooltipProvider>
            </ThemeProvider>
        </QueryClientProvider>
    </StrictMode>
);

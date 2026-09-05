import LoadingDots from '@/components/LoadingDots';
import {useAutomationHubStore} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';
import {applyHubTheme} from '@/ee/pages/embedded/automation-hub/theme/applyHubTheme';
import {useTheme} from '@/shared/providers/theme-provider';
import {useEffect, useMemo, useRef} from 'react';
import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';
import {useShallow} from 'zustand/react/shallow';

/**
 * The hub's chrome: the tab strip, omitted entirely when fewer than two sections are enabled. The
 * hub renders NO page title — that belongs to the application it is embedded in, whose page
 * already has a heading, and a second product name underneath it would announce the iframe rather
 * than describe the page.
 *
 * The tab strip is not configurable. It is the hub's own navigation between its two sections, and
 * a host that hid it would leave the user with no way to reach the other half.
 *
 * The builder route trims the top gutter: it brings its own header row, whose vertical padding
 * stands in for most of it, and the full `pt-4` under the host's page heading opened a gap the
 * catalog's tab strip never has.
 *
 * There is deliberately no header BAR. A bordered band across the top reads as the top of a
 * separate application, which is exactly wrong for something embedded inside the vendor's own
 * page: the title, the tabs and the views all sit on one surface and in one flow, so the hub joins
 * the host's layout instead of announcing itself as a frame within it.
 *
 * The `min-h-0 flex-1` chain from `main` down to the Outlet's wrapper is load-bearing, not
 * decoration. The builder route renders through that Outlet and sizes its canvas with `size-full`,
 * which resolves to nothing under a parent whose own height is only as tall as its content — drop
 * the chain and the canvas collapses to an empty page.
 */
export const HUB_ROUTE_STORAGE_KEY = 'automationHub.route';

/**
 * Session storage, not local: a refresh should land the viewer back where they were, but opening
 * the hub fresh tomorrow should start at the catalog. Both accessors are guarded because an
 * embedded iframe can be denied storage outright by third-party cookie blocking, and a hub that
 * throws on boot is far worse than one that forgets a route.
 */
const readStoredRoute = (): string | undefined => {
    try {
        const storedRoute = window.sessionStorage.getItem(HUB_ROUTE_STORAGE_KEY);

        return storedRoute?.startsWith('/embedded/hub') ? storedRoute : undefined;
    } catch {
        return undefined;
    }
};

const writeStoredRoute = (route: string) => {
    try {
        window.sessionStorage.setItem(HUB_ROUTE_STORAGE_KEY, route);
    } catch {
        // Storage denied; the hub simply forgets where it was.
    }
};

const AutomationHubLayout = () => {
    const {initialized, tabs, theme} = useAutomationHubStore(
        useShallow((state) => ({
            initialized: state.initialized,
            tabs: state.tabs,
            theme: state.theme,
        }))
    );
    const {setTheme} = useTheme();
    const location = useLocation();
    const navigate = useNavigate();

    const restoredRouteRef = useRef(false);

    // The builder is not a peer of the two sections — it is a workflow open for editing inside one
    // of them. Leaving the strip up would make "Connections" an unlabelled way out of a workflow
    // with unsaved canvas state, which the back control exists to be instead.
    const builderRoute = location.pathname.startsWith('/embedded/hub/builder');

    const visibleTabs = useMemo(
        () =>
            [
                {enabled: tabs.automations, label: 'Automations', to: '/embedded/hub'},
                {enabled: tabs.connections, label: 'Connections', to: '/embedded/hub/connections'},
            ].filter((tab) => tab.enabled),
        [tabs]
    );

    useEffect(() => {
        if (initialized) {
            setTheme(applyHubTheme(theme));
        }
    }, [initialized, setTheme, theme]);

    // Reloading the HOST page rebuilds the iframe from its `src`, which always carries the hub's
    // entry hash — so a viewer editing a workflow was thrown back to the catalog by any refresh.
    // Restoring a DEEP route is only safe because `HubBuilderView` sends the viewer back to the
    // catalog when its workflow will not load: a remembered route outlives the thing it points at,
    // and the tab strip is hidden in the builder, so without that fallback a stale entry would
    // strand them on a blank page.
    useEffect(() => {
        if (!initialized || restoredRouteRef.current) {
            return;
        }

        restoredRouteRef.current = true;

        const storedRoute = readStoredRoute();

        if (storedRoute && storedRoute !== location.pathname) {
            navigate(storedRoute, {replace: true});
        }
    }, [initialized, location.pathname, navigate]);

    useEffect(() => {
        // Gated on the restore having run: the hub paints its loading state before EMBED_INIT
        // lands, and an ungated write fired on that pass — overwriting the remembered route with
        // the entry route before the effect above could read it.
        if (!initialized || !restoredRouteRef.current) {
            return;
        }

        writeStoredRoute(location.pathname);
    }, [initialized, location.pathname]);

    if (!initialized) {
        return (
            <div className="flex size-full items-center justify-center" data-testid="automation-hub-loading">
                <LoadingDots />
            </div>
        );
    }

    return (
        <div className="flex size-full flex-col bg-(--hub-surface) text-foreground">
            <main className={twMerge('flex min-h-0 flex-1 flex-col overflow-auto p-4', builderRoute && 'p-1')}>
                {!builderRoute && visibleTabs.length > 1 && (
                    <nav className="flex gap-1" role="tablist">
                        {visibleTabs.map((tab) => (
                            <Link
                                aria-selected={location.pathname === tab.to}
                                className={twMerge(
                                    'rounded-md px-3 py-1.5 text-sm',
                                    location.pathname === tab.to
                                        ? 'bg-(--hub-card) font-medium'
                                        : 'text-muted-foreground hover:bg-(--hub-card)/60'
                                )}
                                key={tab.to}
                                role="tab"
                                to={tab.to}
                            >
                                {tab.label}
                            </Link>
                        ))}
                    </nav>
                )}

                <div className={twMerge('flex min-h-0 flex-1 flex-col', !builderRoute && 'mt-6')}>
                    <Outlet />
                </div>
            </main>
        </div>
    );
};

export default AutomationHubLayout;

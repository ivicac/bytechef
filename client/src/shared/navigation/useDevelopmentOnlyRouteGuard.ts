import {getDevelopmentOnlyFallbackHref} from '@/shared/navigation/developmentOnlyRoutes';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useEffect} from 'react';
import {useLocation, useNavigate} from 'react-router-dom';

/**
 * Sends the user to the deployed counterpart of a Development-only surface whenever the selected
 * environment leaves Development.
 *
 * Route loaders cannot carry this on their own: switching the environment updates a store and never
 * navigates, so no loader re-runs and the user is left standing on an authoring page whose sidebar
 * row has just disappeared. Running it as an effect covers both -- the environment changing under a
 * page, and a Staging or Production session arriving on one of these routes by deep link.
 *
 * The redirect replaces the history entry so Back leaves the guarded route rather than returning to
 * it and bouncing straight out again.
 */
export function useDevelopmentOnlyRouteGuard(): void {
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const {pathname} = useLocation();

    const navigate = useNavigate();

    useEffect(() => {
        const fallbackHref = getDevelopmentOnlyFallbackHref(pathname, currentEnvironmentId);

        if (fallbackHref) {
            navigate(fallbackHref, {replace: true});
        }
    }, [currentEnvironmentId, navigate, pathname]);
}

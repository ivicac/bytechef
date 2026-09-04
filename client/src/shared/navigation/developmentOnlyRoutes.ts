import {DEVELOPMENT_ENVIRONMENT} from '@/shared/constants';

interface DevelopmentOnlyRouteI {
    fallbackHref: string;
    href: string;
}

/**
 * Authoring surfaces that exist only in the Development environment, each paired with the deployed
 * surface that stands in for it everywhere else.
 *
 * One list drives two rules that must never disagree: the sidebar hides these rows outside
 * Development (App.tsx), and anything already standing on one of them is sent to its fallback the
 * moment the environment changes (useDevelopmentOnlyRouteGuard). A row the user cannot reach and a
 * page the user cannot leave are the same bug from opposite ends.
 *
 * `href` matches the route and everything nested under it, so detail pages -- a project workflow
 * editor, an agent, an integration workflow -- are covered by their list entry and need no entry of
 * their own.
 */
const DEVELOPMENT_ONLY_ROUTES: DevelopmentOnlyRouteI[] = [
    {fallbackHref: '/automation/deployments', href: '/automation/projects'},
    {fallbackHref: '/automation/agent-deployments', href: '/automation/agents'},
    {fallbackHref: '/embedded/configurations', href: '/embedded/integrations'},
    {fallbackHref: '/embedded/configurations', href: '/embedded/automation-workflows'},
];

/**
 * Whether a navigation item points at a Development-only surface, and so must be hidden in Staging
 * and Production. Matches the nav item's own href exactly -- nav items are always the list route.
 */
export function isDevelopmentOnlyHref(href: string): boolean {
    return DEVELOPMENT_ONLY_ROUTES.some((route) => route.href === href);
}

/**
 * The href to send the user to, or undefined when the current location is allowed to stay.
 *
 * The environment check lives here rather than at the call sites so that CE -- which has no
 * environment selector and never leaves DEVELOPMENT_ENVIRONMENT -- needs no edition test of its own.
 */
export function getDevelopmentOnlyFallbackHref(pathname: string, environmentId: number): string | undefined {
    if (environmentId === DEVELOPMENT_ENVIRONMENT) {
        return undefined;
    }

    const matchedRoute = DEVELOPMENT_ONLY_ROUTES.find(
        (route) => pathname === route.href || pathname.startsWith(`${route.href}/`)
    );

    return matchedRoute?.fallbackHref;
}

import {useCallback, useMemo} from 'react';
import {useLocation} from 'react-router-dom';

export interface SettingsSidebarNavItemI {
    /** Consecutive items sharing a group fold into one row that opens them in the submenu column. */
    group?: string;
    href?: string;
    subgroup?: boolean;
    title: string;
}

export interface SettingsSidebarSectionI {
    items: SettingsSidebarNavItemI[];
    label?: string;
}

// Matched on whole path segments rather than a bare substring: `users` is a substring of
// `workspace-users`, so on the workspace page both the workspace and the organization entry lit up
// at once. A nested route still counts as inside its nav item, which is what the substring match
// was giving us by accident.
export const isNavItemCurrent = (pathname: string, href: string): boolean => {
    const segmentPath = href.startsWith('/') ? href : `/${href}`;

    return pathname === segmentPath || pathname.endsWith(segmentPath) || pathname.includes(`${segmentPath}/`);
};

/**
 * Folds the flat settings nav into ordered sections and says which group's submenu column is showing.
 * Consecutive items sharing a `group` become one section at the position of their first item; ungrouped
 * runs stay bare rows. Feature-flag filtering happens before this, so a group renders with whatever
 * members survive their flags.
 *
 * The open column is derived from the route alone. A group row navigates into its section rather than
 * toggling it, so there is no such thing as a group the user opened without going there, and none of the
 * open/closed state the app sidebar's collapsible groups have to carry.
 */
export const useSettingsSidebarSections = (navItems: SettingsSidebarNavItemI[]) => {
    const {pathname} = useLocation();

    const isCurrent = useCallback((href: string) => isNavItemCurrent(pathname, href), [pathname]);

    const sections = useMemo(() => {
        const foldedSections: SettingsSidebarSectionI[] = [];

        for (const navItem of navItems) {
            const lastSection = foldedSections[foldedSections.length - 1];

            if (lastSection && lastSection.label === navItem.group) {
                lastSection.items.push(navItem);
            } else {
                foldedSections.push({items: [navItem], label: navItem.group});
            }
        }

        return foldedSections;
    }, [navItems]);

    const openSection =
        sections.find(
            (section) => section.label && section.items.some((navItem) => navItem.href && isCurrent(navItem.href))
        ) ?? null;

    return {isCurrent, openSection, sections};
};

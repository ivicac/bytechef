import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {
    SettingsSidebarNavItemI,
    SettingsSidebarSectionI,
} from '@/shared/layout/settings-sidebar/useSettingsSidebarSections';
import {ChevronRightIcon} from 'lucide-react';
import {Fragment, ReactNode} from 'react';
import {twMerge} from 'tailwind-merge';

interface SettingsSidebarProps {
    isCurrent: (href: string) => boolean;
    openSection: SettingsSidebarSectionI | null;
    sections: SettingsSidebarSectionI[];
}

/**
 * The settings navigation. Consecutive items sharing a group fold behind one row, and that row's pages
 * appear indented beneath it while the group is open — the same shape the app sidebar gives Build, Deploy
 * and the rest.
 *
 * A group is open exactly when the current page is one of its own, so a closed group's links leave the DOM
 * rather than lingering as invisible tab stops, and no row is ever open without the user being in it.
 */
const SettingsSidebar = ({isCurrent, openSection, sections}: SettingsSidebarProps) => {
    const renderNavItem = (navItem: SettingsSidebarNavItemI, className?: string): ReactNode =>
        navItem.href ? (
            <LeftSidebarNavItem
                className={className}
                item={{current: isCurrent(navItem.href), name: navItem.title}}
                key={navItem.href}
                toLink={navItem.href}
            />
        ) : (
            <h3
                className={twMerge(
                    'px-2 pb-1 text-sm font-semibold text-muted-foreground',
                    navItem.subgroup ? 'pt-3' : 'pt-4'
                )}
                key={navItem.title}
            >
                {navItem.title}
            </h3>
        );

    const navigationElements = sections.map((section) => {
        if (!section.label) {
            return section.items.map((navItem) => renderNavItem(navItem));
        }

        // Compared and keyed by identity, not by label: two sections may carry the SAME label — the
        // workspace and organization halves of settings each have an AI group — and matching on the label
        // would open both at once and hand React two children with one key.
        const open = openSection === section;

        const [firstItem] = section.items;

        return (
            // A group row is a link into its section, not a toggle: it goes to the first page it lists, so
            // reaching a grouped page takes one click rather than two. It carries no highlight of its own —
            // the group is open whenever the user is inside it, so the lit child directly beneath already
            // says where they are, and lighting the parent too would mark one page twice.
            <Fragment key={firstItem.href ?? section.label}>
                <LeftSidebarNavItem
                    item={{current: false, name: section.label}}
                    toLink={firstItem.href ?? ''}
                    trailing={
                        <ChevronRightIcon
                            aria-hidden="true"
                            className={twMerge(
                                'size-3.5 text-muted-foreground transition-transform duration-200',
                                open && 'rotate-90'
                            )}
                            strokeWidth={1.25}
                        />
                    }
                />

                {open && section.items.map((navItem) => renderNavItem(navItem, 'pl-6'))}
            </Fragment>
        );
    });

    return <LeftSidebarNav body={navigationElements} />;
};

export default SettingsSidebar;

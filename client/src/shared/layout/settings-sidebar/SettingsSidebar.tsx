import Header from '@/shared/layout/Header';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {
    SettingsSidebarNavItemI,
    SettingsSidebarSectionI,
} from '@/shared/layout/settings-sidebar/useSettingsSidebarSections';
import {ChevronRightIcon} from 'lucide-react';
import {ReactNode} from 'react';
import {twMerge} from 'tailwind-merge';

interface SettingsSidebarProps {
    isCurrent: (href: string) => boolean;
    openSection: SettingsSidebarSectionI | null;
    sections: SettingsSidebarSectionI[];
    title: string;
}

/** A full-height column with its own header, so a title stays put while its rows scroll under it. */
const SidebarColumn = ({
    body,
    bodyClassName,
    className,
    title,
}: {
    body: ReactNode;
    bodyClassName?: string;
    className?: string;
    title: string;
}) => (
    <div className={twMerge('flex h-full w-64 shrink-0 flex-col', className)}>
        <Header position="sidebar" title={title} />

        <div className={twMerge('min-h-0 flex-1 overflow-y-auto', bodyClassName)}>
            <LeftSidebarNav body={body} />
        </div>
    </div>
);

const SidebarHeading = ({subgroup, title}: {subgroup?: boolean; title: string}) => (
    <h3 className={twMerge('px-2 pb-1 text-sm font-semibold text-muted-foreground', subgroup ? 'pt-3' : 'pt-4')}>
        {title}
    </h3>
);

/**
 * The settings navigation: a primary column, plus a submenu column that opens beside it while a group is
 * selected, each headed by its own title the way every other second column in the app is. Both live INSIDE
 * the layout's single left aside rather than beside it — the aside is viewport-fixed and the content area
 * compensates with a matching padding, so a column rendered anywhere else would paint over the page. The
 * aside supplies no header of its own here, since a header spanning both columns could title neither.
 */
const SettingsSidebar = ({isCurrent, openSection, sections, title}: SettingsSidebarProps) => {
    const renderNavItem = (navItem: SettingsSidebarNavItemI): ReactNode =>
        navItem.href ? (
            <LeftSidebarNavItem
                item={{current: isCurrent(navItem.href), name: navItem.title}}
                key={navItem.href}
                toLink={navItem.href}
            />
        ) : (
            <SidebarHeading key={navItem.title} subgroup={navItem.subgroup} title={navItem.title} />
        );

    const primaryElements = sections.map((section) => {
        if (!section.label) {
            return section.items.map(renderNavItem);
        }

        // A group row is a link into its section, not a toggle: it goes to the first page in the column it
        // opens, so reaching a grouped page takes one click rather than two. It stays lit for every page in
        // the section, which is what tells you where you are once the column beside it names the page.
        const [firstItem] = section.items;

        return (
            <LeftSidebarNavItem
                item={{
                    current: openSection?.label === section.label,
                    name: section.label,
                }}
                key={section.label}
                toLink={firstItem.href ?? ''}
                trailing={
                    <ChevronRightIcon
                        aria-hidden="true"
                        className="size-3.5 text-muted-foreground"
                        strokeWidth={1.25}
                    />
                }
            />
        );
    });

    return (
        <div className="flex h-full">
            {/* Each column is the aside's own closed width, so the primary one neither reflows nor reveals
                a gap when the submenu opens beside it. */}

            <SidebarColumn body={primaryElements} title={title} />

            {openSection?.label && (
                // The primary column opens on a section heading, which carries its own space below the
                // header; the submenu opens straight onto rows and needs that space spelled out.
                <SidebarColumn
                    body={openSection.items.map(renderNavItem)}
                    bodyClassName="pt-2"
                    className="border-l border-l-border/50"
                    title={openSection.label}
                />
            )}
        </div>
    );
};

export default SettingsSidebar;

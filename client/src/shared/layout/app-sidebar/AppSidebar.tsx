import reactLogo from '@/assets/logo.svg';
import {
    Sidebar,
    SidebarContent,
    SidebarFooter,
    SidebarGroup,
    SidebarGroupContent,
    SidebarHeader,
    SidebarMenu,
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarRail,
    useSidebar,
} from '@/components/ui/sidebar';
import EnvironmentSelect from '@/shared/components/EnvironmentSelect';
import {ENVIRONMENT_CONFIGS} from '@/shared/constants/environmentConfigs';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {type LucideIcon} from 'lucide-react';
import {Fragment, useCallback, useEffect, useMemo, useState} from 'react';
import {Link, useLocation} from 'react-router-dom';

import AppSidebarCollapsedGroup from './AppSidebarCollapsedGroup';
import AppSidebarCollapsibleGroup from './AppSidebarCollapsibleGroup';
import {AppSidebarFooter} from './AppSidebarFooter';

export interface AppSidebarNavItemI {
    group?: string;
    href: string;
    icon: LucideIcon;
    name: string;
}

interface AppSidebarNavSectionI {
    items: AppSidebarNavItemI[];
    label?: string;
}

interface AppSidebarProps {
    navigation: AppSidebarNavItemI[];
}

export function AppSidebar({navigation}: AppSidebarProps) {
    const {pathname} = useLocation();

    const [openGroupLabel, setOpenGroupLabel] = useState<string | null>(null);
    // Three states, not two: `undefined` means the user has not touched a group, so the route decides.
    // Collapsing to a plain `string | null` would make closing the group you are standing in indist-
    // inguishable from having no preference, and the group would spring straight back open.
    const [manuallyOpenedGroup, setManuallyOpenedGroup] = useState<string | null | undefined>(undefined);

    const {isMobile, state} = useSidebar();

    const currentEnvironmentId = useEnvironmentStore((environmentState) => environmentState.currentEnvironmentId);

    // The mobile sidebar renders as a full sheet, never as an icon rail, so it keeps the expanded nav.
    const collapsed = state === 'collapsed' && !isMobile;

    // The longest matching href wins, not merely a matching one. Nav entries nest — Tool Invocations
    // lives under /automation/executions — so a plain prefix test lights up the parent as well as the
    // child and two rows claim to be where the user is. Deep routes that are not nav entries at all
    // (/automation/projects/123) still match their one parent, which is the behaviour this replaces.
    const activeHref = useMemo(() => {
        const matches = navigation.filter((item) => pathname === item.href || pathname.startsWith(`${item.href}/`));

        return matches.reduce((longest, item) => (item.href.length > longest.length ? item.href : longest), '');
    }, [navigation, pathname]);

    const isActive = (href: string) => href === activeHref;

    // One flyout at a time. A group's close arrives on a delay, so by the time it lands the pointer may
    // already have opened the next group — clear the label only if it is still the one showing, otherwise
    // the outgoing group would close the incoming one.
    const handleGroupOpenChange = useCallback((label: string, open: boolean) => {
        setOpenGroupLabel((currentLabel) => {
            if (open) {
                return label;
            }

            return currentLabel === label ? null : currentLabel;
        });
    }, []);

    const handleCollapsibleOpenChange = useCallback((label: string, open: boolean) => {
        setManuallyOpenedGroup(open ? label : null);
    }, []);

    // Fold the flat navigation into ordered sections: consecutive items sharing a `group` render inside
    // one section at the position of their first item; ungrouped runs render as bare rows.
    const sections = useMemo(() => {
        const foldedSections: AppSidebarNavSectionI[] = [];

        for (const item of navigation) {
            const lastSection = foldedSections[foldedSections.length - 1];

            if (lastSection && lastSection.label === item.group) {
                lastSection.items.push(item);
            } else {
                foldedSections.push({items: [item], label: item.group});
            }
        }

        return foldedSections;
    }, [navigation]);

    const activeGroup =
        sections.find((section) => section.label && section.items.some((item) => isActive(item.href)))?.label ?? null;

    const openGroup = manuallyOpenedGroup === undefined ? activeGroup : manuallyOpenedGroup;

    useEffect(() => {
        setManuallyOpenedGroup(undefined);
    }, [pathname]);

    useEffect(() => {
        const {documentElement} = document;
        const sidebarTheme = ENVIRONMENT_CONFIGS[currentEnvironmentId]?.sidebarTheme;

        if (!sidebarTheme) {
            documentElement.removeAttribute('data-environment');

            return;
        }

        documentElement.setAttribute('data-environment', sidebarTheme);

        return () => documentElement.removeAttribute('data-environment');
    }, [currentEnvironmentId]);

    return (
        <Sidebar className="h-full" collapsible="icon">
            <SidebarHeader>
                <div className="flex items-center justify-between gap-2 group-data-[collapsible=icon]:flex-col group-data-[collapsible=icon]:gap-1">
                    <Link className="flex items-center gap-2 py-1" to="/">
                        <span className="flex size-10 shrink-0 items-center justify-center">
                            <img alt="ByteChef" className="size-8 max-w-none shrink-0" src={reactLogo} />
                        </span>

                        <span className="text-lg font-semibold group-data-[collapsible=icon]:hidden">ByteChef</span>
                    </Link>

                    <EnvironmentSelect variant={collapsed ? 'icon' : 'compact'} />
                </div>
            </SidebarHeader>

            <SidebarContent>
                <nav aria-label="Main navigation">
                    {/* One group for the whole nav, not one per section. The sections used to carry a
                        visible label each and needed the padding to separate them; now that a group's
                        label is its own collapsible row, per-section padding only buys a 48px seam
                        between rows that sit 44px apart everywhere else. */}

                    <SidebarGroup className="py-1">
                        <SidebarGroupContent>
                            <SidebarMenu>
                                {sections.map((section, sectionIndex) => {
                                    // Expanded, a group is a group even with one member — the label is
                                    // what says where the item belongs. On the rail there is no label to
                                    // show, so a one-item group still flattens to its own icon: a flyout
                                    // holding a single link is pure friction.
                                    const isMenuGroup = collapsed
                                        ? !!section.label && section.items.length > 1
                                        : !!section.label;

                                    return (
                                        <Fragment key={`${section.label || 'main'}-${sectionIndex}`}>
                                            {isMenuGroup && collapsed && (
                                                <AppSidebarCollapsedGroup
                                                    isActive={isActive}
                                                    items={section.items}
                                                    label={section.label!}
                                                    onOpenChange={handleGroupOpenChange}
                                                    open={openGroupLabel === section.label}
                                                />
                                            )}

                                            {isMenuGroup && !collapsed && (
                                                <AppSidebarCollapsibleGroup
                                                    isActive={isActive}
                                                    items={section.items}
                                                    label={section.label!}
                                                    onOpenChange={handleCollapsibleOpenChange}
                                                    open={openGroup === section.label}
                                                />
                                            )}

                                            {!isMenuGroup &&
                                                section.items.map((item) => (
                                                    <SidebarMenuItem key={item.name}>
                                                        <SidebarMenuButton
                                                            asChild
                                                            className="h-9 gap-3 text-sm group-data-[collapsible=icon]:!size-10 data-[active=true]:font-medium data-[active=true]:text-content-brand-primary [&>svg]:size-5"
                                                            isActive={isActive(item.href)}
                                                            tooltip={item.name}
                                                        >
                                                            <Link to={item.href}>
                                                                <item.icon aria-hidden="true" />

                                                                <span>{item.name}</span>
                                                            </Link>
                                                        </SidebarMenuButton>
                                                    </SidebarMenuItem>
                                                ))}
                                        </Fragment>
                                    );
                                })}
                            </SidebarMenu>
                        </SidebarGroupContent>
                    </SidebarGroup>
                </nav>
            </SidebarContent>

            <SidebarFooter>
                <AppSidebarFooter />
            </SidebarFooter>

            <SidebarRail />
        </Sidebar>
    );
}

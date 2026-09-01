import {Collapsible, CollapsibleContent, CollapsibleTrigger} from '@/components/ui/collapsible';
import {
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarMenuSub,
    SidebarMenuSubButton,
    SidebarMenuSubItem,
} from '@/components/ui/sidebar';
import {NAVIGATION_GROUP_ICONS} from '@/shared/navigation/navigationItems';
import {ChevronRightIcon} from 'lucide-react';
import {Link} from 'react-router-dom';

import type {AppSidebarNavItemI} from './AppSidebar';

interface AppSidebarCollapsibleGroupProps {
    isActive: (href: string) => boolean;
    items: AppSidebarNavItemI[];
    label: string;
    onOpenChange: (label: string, open: boolean) => void;
    open: boolean;
}

/**
 * A nav group on the expanded sidebar: a parent row that toggles its members open and closed, with the
 * members indented beneath it. Only one group is open at a time and the group holding the current route
 * opens itself, so the nav stays roughly one screen tall as pages are added.
 *
 * Radix unmounts closed content, so a closed group's links leave the DOM entirely rather than lingering
 * as invisible tab stops.
 */
const AppSidebarCollapsibleGroup = ({isActive, items, label, onOpenChange, open}: AppSidebarCollapsibleGroupProps) => {
    const activeItem = items.find((item) => isActive(item.href));

    const GroupIcon = NAVIGATION_GROUP_ICONS[label] ?? (activeItem ?? items[0]).icon;

    const handleOpenChange = (nextOpen: boolean) => onOpenChange(label, nextOpen);

    return (
        <Collapsible asChild onOpenChange={handleOpenChange} open={open}>
            <SidebarMenuItem className="group/collapsible">
                <CollapsibleTrigger asChild>
                    {/* The active child carries its own highlight while the group is open; marking the
                        parent too would double it. Closed, the parent is the only thing left to say the
                        user is in this group. */}

                    <SidebarMenuButton
                        className="h-9 gap-3 text-sm data-[active=true]:font-medium data-[active=true]:text-content-brand-primary [&>svg]:size-5 [&>svg:last-child]:size-3.5"
                        isActive={!!activeItem && !open}
                    >
                        <GroupIcon aria-hidden="true" />

                        <span>{label}</span>

                        {/* The chevron is sized from the button, not from here: the config sets
                            `important: true`, so every utility already carries !important and a class
                            on this element (0,1,0) loses to the button's `[&>svg]` container rule
                            (0,1,1) no matter what it says. `[&>svg:last-child]` (0,2,1) is what wins. */}

                        <ChevronRightIcon
                            aria-hidden="true"
                            className="ml-auto shrink-0 text-muted-foreground transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90"
                            strokeWidth={1.25}
                        />
                    </SidebarMenuButton>
                </CollapsibleTrigger>

                <CollapsibleContent>
                    {/* The component insets the sub list 24px a side and hangs a rule off the left
                        edge. Both go: a selected child should occupy the same box as every other row
                        rather than a narrower one floating inside it. The nesting is carried by the
                        children's own indent below, so the rule was saying it twice. */}

                    <SidebarMenuSub className="mx-0 border-l-0 px-0">
                        {items.map((item) => (
                            <SidebarMenuSubItem key={item.name}>
                                <SidebarMenuSubButton
                                    asChild
                                    className="h-8 pl-8 data-[active=true]:font-medium data-[active=true]:text-content-brand-primary"
                                    isActive={isActive(item.href)}
                                >
                                    <Link to={item.href}>
                                        <item.icon aria-hidden="true" />

                                        <span>{item.name}</span>
                                    </Link>
                                </SidebarMenuSubButton>
                            </SidebarMenuSubItem>
                        ))}
                    </SidebarMenuSub>
                </CollapsibleContent>
            </SidebarMenuItem>
        </Collapsible>
    );
};

export default AppSidebarCollapsibleGroup;

import Button from '@/components/Button/Button';
import {
    Sidebar,
    SidebarContent,
    SidebarFooter,
    SidebarGroup,
    SidebarGroupContent,
    SidebarGroupLabel,
    SidebarHeader,
    SidebarMenu,
    SidebarMenuButton,
    SidebarMenuItem,
    SidebarRail,
    useSidebar,
} from '@/components/ui/sidebar';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import EnvironmentSelect from '@/shared/components/EnvironmentSelect';
import {DEVELOPMENT_ENVIRONMENT} from '@/shared/constants';
import {ENVIRONMENT_CONFIGS} from '@/shared/constants/environmentConfigs';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {type LucideIcon} from 'lucide-react';
import {useEffect, useMemo} from 'react';
import {Link, useLocation, useNavigate} from 'react-router-dom';

import {AppSidebarFooter} from './AppSidebarFooter';
import {AppSidebarWorkspaceSelect} from './AppSidebarWorkspaceSelect';

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

    const navigate = useNavigate();

    const {isMobile, state, toggleSidebar} = useSidebar();

    const currentEnvironmentId = useEnvironmentStore((environmentState) => environmentState.currentEnvironmentId);

    const collapsed = state === 'collapsed' && !isMobile;

    const isActive = (href: string) => pathname === href || pathname.startsWith(`${href}/`);

    // Projects and integrations (and their workflow editors) only exist in development, so leaving
    // development moves the user to the environment-scoped deployments/configurations page instead.
    const handleEnvironmentChange = (environmentId: number) => {
        if (environmentId === DEVELOPMENT_ENVIRONMENT) {
            return;
        }

        if (isActive('/automation/projects')) {
            navigate('/automation/deployments');
        } else if (isActive('/embedded/integrations')) {
            navigate('/embedded/configurations');
        }
    };

    // Fold the flat navigation into ordered sections: consecutive items sharing a `group` render inside
    // one labeled SidebarGroup at the position of their first item; ungrouped runs render unlabeled.
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
            <SidebarHeader className="py-[13px]">
                <div className="flex items-center justify-between gap-2 group-data-[collapsible=icon]:flex-col group-data-[collapsible=icon]:gap-3">
                    <AppSidebarWorkspaceSelect />

                    <EnvironmentSelect onChange={handleEnvironmentChange} variant="icon" />

                    {!collapsed && (
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Button
                                    aria-label="Close sidebar"
                                    className="size-auto shrink-0 p-0 text-muted-foreground hover:bg-transparent hover:text-foreground [&_svg]:size-5"
                                    icon={<PanelLeftIcon />}
                                    onClick={toggleSidebar}
                                    size="icon"
                                    variant="ghost"
                                />
                            </TooltipTrigger>

                            <TooltipContent side="right">Close sidebar</TooltipContent>
                        </Tooltip>
                    )}
                </div>
            </SidebarHeader>

            <SidebarContent>
                <nav aria-label="Main navigation">
                    {sections.map((section, sectionIndex) => (
                        <SidebarGroup key={`${section.label || 'main'}-${sectionIndex}`}>
                            {section.label && <SidebarGroupLabel>{section.label}</SidebarGroupLabel>}

                            <SidebarGroupContent>
                                <SidebarMenu>
                                    {section.items.map((item) => (
                                        <SidebarMenuItem key={item.name}>
                                            <SidebarMenuButton
                                                asChild
                                                className="h-10 gap-3 text-sm group-data-[collapsible=icon]:!size-10 data-[active=true]:font-medium data-[active=true]:text-sidebar-active-foreground [&>svg]:size-6"
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
                                </SidebarMenu>
                            </SidebarGroupContent>
                        </SidebarGroup>
                    ))}
                </nav>
            </SidebarContent>

            <SidebarFooter>
                <AppSidebarFooter />
            </SidebarFooter>

            <SidebarRail />
        </Sidebar>
    );
}

import {
    AppWindowIcon,
    BoxesIcon,
    DatabaseIcon,
    FileTextIcon,
    FolderIcon,
    GaugeIcon,
    HammerIcon,
    HistoryIcon,
    Layers3Icon,
    LayoutTemplateIcon,
    Link2Icon,
    type LucideIcon,
    MessageSquareIcon,
    MessagesSquareIcon,
    NetworkIcon,
    RocketIcon,
    RouterIcon,
    ServerIcon,
    Settings2Icon,
    SlidersHorizontalIcon,
    SquareIcon,
    Table2Icon,
    UnplugIcon,
    UsersIcon,
    VectorSquareIcon,
    Workflow,
    ZapIcon,
} from 'lucide-react';

export interface NavigationGroupI {
    icon: LucideIcon;
    name: string;
}

export interface NavigationItemI {
    group?: NavigationGroupI;
    href: string;
    icon: LucideIcon;
    name: string;
}

const buildNavigationGroup: NavigationGroupI = {icon: HammerIcon, name: 'Build'};
const configureNavigationGroup: NavigationGroupI = {icon: SlidersHorizontalIcon, name: 'Configure'};
const deployNavigationGroup: NavigationGroupI = {icon: RocketIcon, name: 'Deploy'};
const monitorNavigationGroup: NavigationGroupI = {icon: GaugeIcon, name: 'Monitor'};
const resourcesNavigationGroup: NavigationGroupI = {icon: DatabaseIcon, name: 'Resources'};

export const automationNavigation: NavigationItemI[] = [
    {
        href: '/automation/ai-hub',
        icon: MessagesSquareIcon,
        name: 'AI Hub',
    },
    {href: '/automation/chats', icon: MessageSquareIcon, name: 'Chats'},
    {
        href: '/automation/projects',
        icon: FolderIcon,
        name: 'Projects',
    },
    {href: '/automation/connections', icon: Link2Icon, name: 'Connections'},
    {
        group: deployNavigationGroup,
        href: '/automation/deployments',
        icon: Layers3Icon,
        name: 'Projects',
    },
    {
        group: deployNavigationGroup,
        href: '/automation/api-platform',
        icon: LayoutTemplateIcon,
        name: 'API Collections',
    },
    {
        group: deployNavigationGroup,
        href: '/automation/mcp-servers',
        icon: ServerIcon,
        name: 'MCP Servers',
    },
    {
        group: deployNavigationGroup,
        href: '/automation/a2a-servers',
        icon: NetworkIcon,
        name: 'A2A Servers',
    },
    {
        group: deployNavigationGroup,
        href: '/automation/apps',
        icon: AppWindowIcon,
        name: 'Apps',
    },
    {
        group: monitorNavigationGroup,
        href: '/automation/executions',
        icon: HistoryIcon,
        name: 'Workflow Executions',
    },
    {
        group: monitorNavigationGroup,
        href: '/automation/executions/tool-invocations',
        icon: HammerIcon,
        name: 'Tool Invocations',
    },
    {
        group: resourcesNavigationGroup,
        href: '/automation/datatables',
        icon: Table2Icon,
        name: 'Data Tables',
    },
    {
        group: resourcesNavigationGroup,
        href: '/automation/knowledge-bases',
        icon: VectorSquareIcon,
        name: 'Knowledge Base',
    },
    {
        group: resourcesNavigationGroup,
        href: '/automation/context-stores',
        icon: BoxesIcon,
        name: 'Context Store',
    },
    {
        group: resourcesNavigationGroup,
        href: '/automation/asset-files',
        icon: FileTextIcon,
        name: 'Files',
    },
    {href: '/automation/ai/gateway', icon: RouterIcon, name: 'AI Gateway'},
];

export const embeddedNavigation: NavigationItemI[] = [
    {
        group: buildNavigationGroup,
        href: '/embedded/integrations',
        icon: SquareIcon,
        name: 'Integrations',
    },
    {
        group: buildNavigationGroup,
        href: '/embedded/automation-workflows',
        icon: Workflow,
        name: 'Automations',
    },
    {href: '/embedded/connections', icon: Link2Icon, name: 'Connections'},
    {
        group: configureNavigationGroup,
        href: '/embedded/configurations',
        icon: Settings2Icon,
        name: 'Integration Instances',
    },
    {
        group: configureNavigationGroup,
        href: '/embedded/automation-configurations',
        icon: SlidersHorizontalIcon,
        name: 'Automation Configurations',
    },
    {group: configureNavigationGroup, href: '/embedded/app-events', icon: ZapIcon, name: 'App Events'},
    {group: configureNavigationGroup, href: '/embedded/mcp-servers', icon: ServerIcon, name: 'MCP Servers'},
    {
        group: monitorNavigationGroup,
        href: '/embedded/executions',
        icon: HistoryIcon,
        name: 'Workflow Executions',
    },
    {
        group: monitorNavigationGroup,
        href: '/embedded/executions/tool-invocations',
        icon: HammerIcon,
        name: 'Tool Invocations',
    },
    {
        group: monitorNavigationGroup,
        href: '/embedded/connected-users',
        icon: UsersIcon,
        name: 'Connected Users',
    },
];

export const platformNavigation: NavigationItemI[] = [
    {
        href: '/platform/connectors',
        icon: UnplugIcon,
        name: 'Connectors',
    },
];

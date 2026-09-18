import {
    ActivityIcon,
    BoxesIcon,
    FileTextIcon,
    FolderIcon,
    Layers3Icon,
    LayoutTemplateIcon,
    Link2Icon,
    type LucideIcon,
    MessageSquareIcon,
    MessagesSquareIcon,
    NetworkIcon,
    RouterIcon,
    ServerCogIcon,
    ServerIcon,
    Settings2Icon,
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

const serversNavigationGroup: NavigationGroupI = {icon: ServerCogIcon, name: 'Servers'};

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
    {
        href: '/automation/deployments',
        icon: Layers3Icon,
        name: 'Project Deployments',
    },
    {
        href: '/automation/api-platform',
        icon: LayoutTemplateIcon,
        name: 'API Collections',
    },
    {
        group: serversNavigationGroup,
        href: '/automation/mcp-servers',
        icon: ServerIcon,
        name: 'MCP Servers',
    },
    {
        group: serversNavigationGroup,
        href: '/automation/a2a-servers',
        icon: NetworkIcon,
        name: 'A2A Servers',
    },
    {
        href: '/automation/context-stores',
        icon: BoxesIcon,
        name: 'Context Store',
    },
    {
        href: '/automation/executions',
        icon: ActivityIcon,
        name: 'Executions',
    },
    {href: '/automation/connections', icon: Link2Icon, name: 'Connections'},
    {
        href: '/automation/datatables',
        icon: Table2Icon,
        name: 'Data Tables',
    },
    {
        href: '/automation/knowledge-bases',
        icon: VectorSquareIcon,
        name: 'Knowledge Base',
    },
    {
        href: '/automation/asset-files',
        icon: FileTextIcon,
        name: 'Files',
    },    {href: '/automation/ai/gateway', icon: RouterIcon, name: 'AI Gateway'},
];

export const embeddedNavigation: NavigationItemI[] = [
    {
        href: '/embedded/integrations',
        icon: SquareIcon,
        name: 'Integrations',
    },
    {
        href: '/embedded/configurations',
        icon: Settings2Icon,
        name: 'Integration Configurations',
    },
    {href: '/embedded/app-events', icon: ZapIcon, name: 'App Events'},
    {
        href: '/embedded/automation-workflows',
        icon: Workflow,
        name: 'Automations',
    },
    {href: '/embedded/mcp-servers', icon: ServerIcon, name: 'MCP Servers'},
    {
        href: '/embedded/connected-users',
        icon: UsersIcon,
        name: 'Connected Users',
    },
    {
        href: '/embedded/executions',
        icon: ActivityIcon,
        name: 'Executions',
    },
    {href: '/embedded/connections', icon: Link2Icon, name: 'Connections'},
];

export const platformNavigation: NavigationItemI[] = [
    {
        href: '/platform/connectors',
        icon: UnplugIcon,
        name: 'Connectors',
    },
];

import {
    ActivityIcon,
    BotIcon,
    BotMessageSquareIcon,
    BoxesIcon,
    CircleIcon,
    DatabaseIcon,
    FileTextIcon,
    FolderIcon,
    GaugeIcon,
    HammerIcon,
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
    SparklesIcon,
    SquareIcon,
    Table2Icon,
    UnplugIcon,
    UsersIcon,
    VectorSquareIcon,
    Workflow,
    WrenchIcon,
    ZapIcon,
} from 'lucide-react';

export interface NavigationItemI {
    group?: string;
    href: string;
    icon: LucideIcon;
    name: string;
}

export const automationNavigation: NavigationItemI[] = [
    {
        href: '/automation/ai-hub',
        icon: MessagesSquareIcon,
        name: 'AI Hub',
    },
    {href: '/automation/chats', icon: MessageSquareIcon, name: 'Chats'},
    {href: '/automation/approval-tasks', icon: CircleIcon, name: 'Approval Tasks'},
    {
        group: 'Build',
        href: '/automation/projects',
        icon: FolderIcon,
        name: 'Projects',
    },
    {group: 'Build', href: '/automation/agents', icon: BotIcon, name: 'Agents'},
    {href: '/automation/connections', icon: Link2Icon, name: 'Connect'},
    {
        group: 'Deploy',
        href: '/automation/deployments',
        icon: Layers3Icon,
        name: 'Projects',
    },
    {
        group: 'Deploy',
        href: '/automation/agent-deployments',
        icon: BotMessageSquareIcon,
        name: 'Agents',
    },
    {
        group: 'Deploy',
        href: '/automation/api-platform',
        icon: LayoutTemplateIcon,
        name: 'API Collections',
    },
    {
        group: 'Deploy',
        href: '/automation/mcp-servers',
        icon: ServerIcon,
        name: 'MCP Servers',
    },
    {
        group: 'Deploy',
        href: '/automation/a2a-servers',
        icon: NetworkIcon,
        name: 'A2A Servers',
    },
    {
        group: 'Monitor',
        href: '/automation/executions',
        icon: ActivityIcon,
        name: 'Workflow Executions',
    },
    {
        group: 'Monitor',
        href: '/automation/executions/tool-invocations',
        icon: WrenchIcon,
        name: 'Tool Invocations',
    },
    {group: 'AI', href: '/automation/ai/gateway', icon: RouterIcon, name: 'AI Gateway'},
    {
        group: 'Resources',
        href: '/automation/datatables',
        icon: Table2Icon,
        name: 'Data Tables',
    },
    {
        group: 'Resources',
        href: '/automation/knowledge-bases',
        icon: VectorSquareIcon,
        name: 'Knowledge Base',
    },
    {
        group: 'Resources',
        href: '/automation/context-stores',
        icon: BoxesIcon,
        name: 'Context Store',
    },
    {
        group: 'Resources',
        href: '/automation/asset-files',
        icon: FileTextIcon,
        name: 'Files',
    },
];

export const embeddedNavigation: NavigationItemI[] = [
    {
        group: 'Build',
        href: '/embedded/integrations',
        icon: SquareIcon,
        name: 'Integrations',
    },
    {
        group: 'Build',
        href: '/embedded/automation-workflows',
        icon: Workflow,
        name: 'Automations',
    },
    {href: '/embedded/connections', icon: Link2Icon, name: 'Connect'},
    {
        group: 'Configure',
        href: '/embedded/configurations',
        icon: Settings2Icon,
        name: 'Integration Instances',
    },
    {group: 'Configure', href: '/embedded/app-events', icon: ZapIcon, name: 'App Events'},
    {group: 'Configure', href: '/embedded/mcp-servers', icon: ServerIcon, name: 'MCP Servers'},
    {
        group: 'Monitor',
        href: '/embedded/executions',
        icon: ActivityIcon,
        name: 'Workflow Executions',
    },
    {
        group: 'Monitor',
        href: '/embedded/executions/tool-invocations',
        icon: WrenchIcon,
        name: 'Tool Invocations',
    },
    {
        group: 'Monitor',
        href: '/embedded/connected-users',
        icon: UsersIcon,
        name: 'Connected Users',
    },
    {
        group: 'Resources',
        href: '/embedded/data-tables',
        icon: Table2Icon,
        name: 'Data Tables',
    },
    {
        group: 'Resources',
        href: '/embedded/knowledge-bases',
        icon: VectorSquareIcon,
        name: 'Knowledge Base',
    },
];

export const platformNavigation: NavigationItemI[] = [
    {
        href: '/platform/connectors',
        icon: UnplugIcon,
        name: 'Connectors',
    },
];

/**
 * Icon shown on a nav group's collapsible parent row. The row stands in for its whole group once the
 * group is closed, so it needs a mark of its own rather than borrowing a member's icon.
 *
 * A group missing an entry here still renders — AppSidebarCollapsibleGroup falls back to a member's
 * icon — so adding a group to the arrays above never breaks the sidebar, it only looks unconsidered.
 */
export const NAVIGATION_GROUP_ICONS: Record<string, LucideIcon> = {
    AI: SparklesIcon,
    Build: HammerIcon,
    Configure: SlidersHorizontalIcon,
    Deploy: RocketIcon,
    Monitor: GaugeIcon,
    Resources: DatabaseIcon,
};

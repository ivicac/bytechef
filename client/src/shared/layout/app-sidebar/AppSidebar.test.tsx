import {SidebarProvider} from '@/components/ui/sidebar';
import {render, screen, userEvent} from '@/shared/util/test-utils';
import {FolderIcon, Layers3Icon, LayoutTemplateIcon, MessagesSquareIcon} from 'lucide-react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {AppSidebar} from './AppSidebar';

const hoisted = vi.hoisted(() => ({currentEnvironmentId: 0}));

// AppSidebarFooter pulls in stores/queries; stub it so this test stays focused on nav.
vi.mock('./AppSidebarFooter', () => ({
    AppSidebarFooter: () => null,
}));

vi.mock('@/shared/components/EnvironmentSelect', () => ({
    default: ({variant}: {variant?: string}) => <div data-testid="environment-select">{variant}</div>,
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: (selector: (state: Record<string, unknown>) => unknown) =>
        selector({currentEnvironmentId: hoisted.currentEnvironmentId}),
}));

const navigation = [
    {href: '/automation/ai-hub', icon: MessagesSquareIcon, name: 'AI Hub'},
    {href: '/automation/projects', icon: FolderIcon, name: 'Projects'},
];

const groupedNavigation = [
    {href: '/automation/projects', icon: FolderIcon, name: 'Projects'},
    {group: 'Deployments', href: '/automation/deployments', icon: Layers3Icon, name: 'Project Deployments'},
    {group: 'Deployments', href: '/automation/api-platform', icon: LayoutTemplateIcon, name: 'API Collections'},
    {group: 'Data', href: '/automation/data-tables', icon: FolderIcon, name: 'Data Tables'},
    {group: 'Data', href: '/automation/knowledge-base', icon: FolderIcon, name: 'Knowledge Base'},
    {group: 'Monitor', href: '/automation/executions', icon: Layers3Icon, name: 'Executions'},
    {href: '/automation/connections', icon: FolderIcon, name: 'Connections'},
];

const renderSidebar = (open = true) =>
    render(
        <MemoryRouter initialEntries={['/automation/projects']}>
            <SidebarProvider defaultOpen={open}>
                <AppSidebar navigation={navigation} />
            </SidebarProvider>
        </MemoryRouter>
    );

describe('AppSidebar', () => {
    beforeEach(() => {
        hoisted.currentEnvironmentId = 0;
    });

    it('renders a menu item for each navigation entry', () => {
        renderSidebar(true);

        expect(screen.getByRole('link', {name: 'AI Hub'})).toBeInTheDocument();
        expect(screen.getByRole('link', {name: 'Projects'})).toBeInTheDocument();
    });

    it('links each item to its href', () => {
        renderSidebar(true);

        expect(screen.getByRole('link', {name: 'AI Hub'})).toHaveAttribute('href', '/automation/ai-hub');
    });

    describe('environment', () => {
        it('renders the compact selector beside the wordmark when the rail is expanded', () => {
            renderSidebar(true);

            expect(screen.getByTestId('environment-select')).toHaveTextContent('compact');
        });

        it('falls back to the icon-only selector on the collapsed rail', () => {
            renderSidebar(false);

            expect(screen.getByTestId('environment-select')).toHaveTextContent('icon');
        });

        it('tints the document element with the selected environment', () => {
            renderSidebar(true);

            expect(document.documentElement).toHaveAttribute('data-environment', 'development');
        });

        it('retints when the selected environment changes', () => {
            const {rerender} = renderSidebar(true);

            hoisted.currentEnvironmentId = 2;

            rerender(
                <MemoryRouter initialEntries={['/automation/projects']}>
                    <SidebarProvider defaultOpen>
                        <AppSidebar navigation={navigation} />
                    </SidebarProvider>
                </MemoryRouter>
            );

            expect(document.documentElement).toHaveAttribute('data-environment', 'production');
        });

        it('clears the tint when the sidebar unmounts', () => {
            const {unmount} = renderSidebar(true);

            unmount();

            expect(document.documentElement).not.toHaveAttribute('data-environment');
        });
    });

    describe('expanded groups', () => {
        const renderGrouped = (initialEntry = '/automation/projects') =>
            render(
                <MemoryRouter initialEntries={[initialEntry]}>
                    <SidebarProvider defaultOpen>
                        <AppSidebar navigation={groupedNavigation} />
                    </SidebarProvider>
                </MemoryRouter>
            );

        it('folds consecutive items sharing a group behind one collapsible trigger', () => {
            renderGrouped();

            expect(screen.getAllByText('Deployments')).toHaveLength(1);
            expect(screen.getByRole('button', {name: 'Deployments'})).toBeInTheDocument();
        });

        // Radix unmounts closed content, so a closed group leaves no invisible tab stops behind.
        it("keeps a closed group's items out of the document", () => {
            renderGrouped();

            expect(screen.queryByRole('link', {name: 'Project Deployments'})).not.toBeInTheDocument();
            expect(screen.queryByRole('link', {name: 'API Collections'})).not.toBeInTheDocument();
        });

        it('opens the group holding the current route', () => {
            renderGrouped('/automation/deployments');

            expect(screen.getByRole('link', {name: 'Project Deployments'})).toBeInTheDocument();
            expect(screen.getByRole('link', {name: 'API Collections'})).toBeInTheDocument();
        });

        it('leaves every group closed on a route belonging to none of them', () => {
            renderGrouped();

            expect(screen.queryByRole('link', {name: 'Project Deployments'})).not.toBeInTheDocument();
            expect(screen.queryByRole('link', {name: 'Data Tables'})).not.toBeInTheDocument();
        });

        it("reveals a group's items when its trigger is clicked", async () => {
            const user = userEvent.setup();

            renderGrouped();

            await user.click(screen.getByRole('button', {name: 'Deployments'}));

            expect(await screen.findByRole('link', {name: 'Project Deployments'})).toBeInTheDocument();
        });

        // One open group at a time keeps the nav roughly a screen tall however many groups are added.
        it('closes the open group when another is opened', async () => {
            const user = userEvent.setup();

            renderGrouped();

            await user.click(screen.getByRole('button', {name: 'Deployments'}));

            expect(await screen.findByRole('link', {name: 'Project Deployments'})).toBeInTheDocument();

            await user.click(screen.getByRole('button', {name: 'Data'}));

            expect(await screen.findByRole('link', {name: 'Data Tables'})).toBeInTheDocument();
            expect(screen.queryByRole('link', {name: 'Project Deployments'})).not.toBeInTheDocument();
        });

        // Reopening on the next render would make the group uncloseable, which is what happens when
        // "user closed this" and "user has no preference" share one value.
        it('lets the user close the group holding the current route', async () => {
            const user = userEvent.setup();

            renderGrouped('/automation/deployments');

            await user.click(screen.getByRole('button', {name: 'Deployments'}));

            expect(screen.queryByRole('link', {name: 'Project Deployments'})).not.toBeInTheDocument();
        });

        // A one-member group still reads as a group here: the label is the only thing saying where
        // the item belongs, and dropping it would leave the row indistinguishable from an ungrouped one.
        it('renders a single-item group as a collapsible like any other', () => {
            renderGrouped();

            expect(screen.getByRole('button', {name: 'Monitor'})).toBeInTheDocument();
            expect(screen.queryByRole('link', {name: 'Executions'})).not.toBeInTheDocument();
        });

        it('opens a single-item group on its own route', () => {
            renderGrouped('/automation/executions');

            expect(screen.getByRole('link', {name: 'Executions'})).toBeInTheDocument();
        });

        it('keeps ungrouped items as direct links', () => {
            renderGrouped();

            expect(screen.getByRole('link', {name: 'Projects'})).toBeInTheDocument();
            expect(screen.getByRole('link', {name: 'Connections'})).toBeInTheDocument();
        });
    });

    it('renders ungrouped items without a group label', () => {
        renderSidebar(true);

        expect(screen.queryByText('Deployments')).not.toBeInTheDocument();
    });

    describe('collapsed rail', () => {
        const renderCollapsed = () =>
            render(
                <MemoryRouter initialEntries={['/automation/projects']}>
                    <SidebarProvider defaultOpen={false}>
                        <AppSidebar navigation={groupedNavigation} />
                    </SidebarProvider>
                </MemoryRouter>
            );

        it('folds a group into a single trigger instead of one icon per item', () => {
            renderCollapsed();

            // The group's items live in a hover flyout, so no link is rendered for them up front.
            expect(screen.queryByRole('link', {name: 'Project Deployments'})).not.toBeInTheDocument();
            expect(screen.queryByRole('link', {name: 'API Collections'})).not.toBeInTheDocument();

            expect(screen.getByRole('button', {name: 'Deployments'})).toBeInTheDocument();
        });

        it('keeps ungrouped items as their own rail links', () => {
            renderCollapsed();

            expect(screen.getByRole('link', {name: 'Projects'})).toBeInTheDocument();
            expect(screen.getByRole('link', {name: 'Connections'})).toBeInTheDocument();
        });

        it('renders a single-item group as a direct link rather than a flyout', () => {
            renderCollapsed();

            expect(screen.getByRole('link', {name: 'Executions'})).toBeInTheDocument();
            expect(screen.queryByRole('button', {name: 'Monitor'})).not.toBeInTheDocument();
        });

        // Each flyout closes on a delay so the pointer can wander on its way to the menu. Left uncontrolled,
        // that delay let the outgoing group stay on screen alongside the incoming one when the pointer swept
        // down the rail.
        it('shows only one flyout when the pointer moves between groups', async () => {
            const user = userEvent.setup();

            renderCollapsed();

            await user.hover(screen.getByRole('button', {name: 'Deployments'}));

            expect(await screen.findByRole('link', {name: 'Project Deployments'})).toBeInTheDocument();

            await user.hover(screen.getByRole('button', {name: 'Data'}));

            expect(await screen.findByRole('link', {name: 'Data Tables'})).toBeInTheDocument();
            expect(screen.queryByRole('link', {name: 'Project Deployments'})).not.toBeInTheDocument();
        });

        it('renders no group label element for the flyout to sit under', () => {
            const {container} = renderCollapsed();

            expect(container.querySelector('[data-sidebar="group-label"]')).not.toBeInTheDocument();
        });
    });
});

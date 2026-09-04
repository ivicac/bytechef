import SettingsSidebar from '@/shared/layout/settings-sidebar/SettingsSidebar';
import {
    SettingsSidebarNavItemI,
    isNavItemCurrent,
    useSettingsSidebarSections,
} from '@/shared/layout/settings-sidebar/useSettingsSidebarSections';
import {render, screen, userEvent} from '@/shared/util/test-utils';
import {MemoryRouter, Route, Routes, useLocation} from 'react-router-dom';
import {describe, expect, it} from 'vitest';

// Mirrors the real nav: an AI group in each half of settings, and an item after each group so the fold has
// something to stop at.
const navItems: SettingsSidebarNavItemI[] = [
    {title: 'Current Workspace'},
    {href: 'workspace-users', title: 'Users'},
    {group: 'AI', href: 'ai-hub/connectors', title: 'Hub Connectors'},
    {group: 'AI', href: 'ai-hub/tool-approvals', title: 'Tool Approvals'},
    {group: 'AI', href: 'ai/agents', title: 'Agents'},
    {group: 'AI', href: 'ai/memories', title: 'Memories'},
    {title: 'Organization'},
    {href: 'workspaces', title: 'Workspaces'},
    {group: 'AI', href: 'ai-providers', title: 'Providers'},
    {group: 'AI', href: 'ai/skills', title: 'Skills'},
];

const SettingsSidebarHarness = () => {
    const {isCurrent, openSection, sections} = useSettingsSidebarSections(navItems);

    return <SettingsSidebar isCurrent={isCurrent} openSection={openSection} sections={sections} />;
};

const CurrentPathname = () => <span data-testid="pathname">{useLocation().pathname}</span>;

const renderSidebar = (initialEntry = '/workspace-users') =>
    render(
        <MemoryRouter initialEntries={[initialEntry]}>
            <SettingsSidebarHarness />

            <Routes>
                <Route element={<CurrentPathname />} path="*" />
            </Routes>
        </MemoryRouter>
    );

describe('SettingsSidebar', () => {
    it('renders a heading for an item without an href', () => {
        renderSidebar();

        expect(screen.getByRole('heading', {name: 'Current Workspace'})).toBeInTheDocument();
        expect(screen.getByRole('heading', {name: 'Organization'})).toBeInTheDocument();
    });

    it('keeps ungrouped items as direct links', () => {
        renderSidebar();

        expect(screen.getByRole('link', {name: 'Users'})).toHaveAttribute('href', '/workspace-users');
        expect(screen.getByRole('link', {name: 'Workspaces'})).toHaveAttribute('href', '/workspaces');
    });

    it('folds consecutive items sharing a group behind one row', () => {
        renderSidebar();

        expect(screen.queryByRole('link', {name: 'Agents'})).not.toBeInTheDocument();
        expect(screen.queryByRole('link', {name: 'Skills'})).not.toBeInTheDocument();
    });

    // One click into a section, not two: the row goes where its own first page is.
    it('links each group row to the first page it lists', () => {
        renderSidebar();

        expect(screen.getAllByRole('link', {name: 'AI'}).map((link) => link.getAttribute('href'))).toEqual([
            '/ai-hub/connectors',
            '/ai-providers',
        ]);
    });

    it("keeps a closed group's items out of the document", () => {
        renderSidebar();

        expect(screen.queryByRole('link', {name: 'Hub Connectors'})).not.toBeInTheDocument();
        expect(screen.queryByRole('link', {name: 'Tool Approvals'})).not.toBeInTheDocument();
    });

    it('reveals the group below its row when the row is followed', async () => {
        const user = userEvent.setup();

        renderSidebar();

        await user.click(screen.getAllByRole('link', {name: 'AI'})[0]);

        expect(screen.getByTestId('pathname')).toHaveTextContent('/ai-hub/connectors');
        expect(await screen.findByRole('link', {name: 'Hub Connectors'})).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', {name: 'Memories'})).toBeInTheDocument();
    });

    it('opens the group holding the current route', () => {
        renderSidebar('/ai/agents/rules');

        expect(screen.getByRole('link', {name: 'Agents'})).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', {name: 'Memories'})).not.toHaveAttribute('aria-current');
    });

    // Two sections carrying one label stay two sections: entering one leaves the other closed.
    it('opens only the group whose own section holds the route', () => {
        renderSidebar('/ai/skills');

        expect(screen.getByRole('link', {name: 'Providers'})).toBeInTheDocument();
        expect(screen.getByRole('link', {name: 'Skills'})).toHaveAttribute('aria-current', 'page');
        expect(screen.queryByRole('link', {name: 'Hub Connectors'})).not.toBeInTheDocument();
    });

    // The group is open whenever the user is inside it, so the lit child directly beneath already says
    // where they are — marking the row too would light two rows for one page.
    it('leaves the group row itself unmarked', () => {
        renderSidebar('/ai/memories');

        screen.getAllByRole('link', {name: 'AI'}).forEach((groupLink) => {
            expect(groupLink).not.toHaveAttribute('aria-current');
        });
    });

    // Padding on the row rather than a wrapper, so a lit child fills the same box as every other row
    // instead of a narrower one floating inside it.
    it("indents a group's pages without insetting their highlight", () => {
        renderSidebar('/ai/memories');

        expect(screen.getByRole('link', {name: 'Memories'})).toHaveClass('pl-6');
        expect(screen.getByRole('link', {name: 'Users'})).not.toHaveClass('pl-6');
    });
});

describe('isNavItemCurrent', () => {
    it('matches the item whose segment the route ends with', () => {
        expect(isNavItemCurrent('/automation/settings/users', 'users')).toBe(true);
    });

    // The regression: `pathname.includes(href)` lit up both the workspace and the organization
    // entry at once, because `users` is a substring of `workspace-users`.
    it('does not match a longer segment that merely contains the item', () => {
        expect(isNavItemCurrent('/automation/settings/workspace-users', 'users')).toBe(false);
        expect(isNavItemCurrent('/automation/settings/global-custom-roles', 'custom-roles')).toBe(false);
    });

    it('still matches the longer item on its own route', () => {
        expect(isNavItemCurrent('/automation/settings/workspace-users', 'workspace-users')).toBe(true);
        expect(isNavItemCurrent('/automation/settings/global-custom-roles', 'global-custom-roles')).toBe(true);
    });

    it('treats a nested route as inside its nav item', () => {
        expect(isNavItemCurrent('/automation/settings/ai/guardrails/detail', 'ai/guardrails')).toBe(true);
    });

    it('matches an absolute href', () => {
        expect(isNavItemCurrent('/automation/settings/workspaces', '/automation/settings/workspaces')).toBe(true);
    });
});

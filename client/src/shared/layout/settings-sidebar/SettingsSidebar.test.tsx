import SettingsSidebar from '@/shared/layout/settings-sidebar/SettingsSidebar';
import {
    SettingsSidebarNavItemI,
    isNavItemCurrent,
    useSettingsSidebarSections,
} from '@/shared/layout/settings-sidebar/useSettingsSidebarSections';
import {render, screen, userEvent} from '@/shared/util/test-utils';
import {MemoryRouter, Route, Routes, useLocation} from 'react-router-dom';
import {describe, expect, it} from 'vitest';

const navItems: SettingsSidebarNavItemI[] = [
    {title: 'Current Workspace'},
    {href: 'workspace-users', title: 'Users'},
    {group: 'AI', href: 'ai-hub/connectors', title: 'Hub Connectors'},
    {group: 'AI', href: 'ai/agents', title: 'Agents'},
    {group: 'AI', href: 'ai/memories', title: 'Memories'},
    {href: 'ai-hub/tool-approvals', title: 'Tool Approvals'},
];

// The width lives on the layout, but everything else about the two columns is the composition below:
// the hook derives the open section from the route and the component renders it beside the primary column.
const SettingsSidebarHarness = () => {
    const {isCurrent, openSection, sections} = useSettingsSidebarSections(navItems);

    return <SettingsSidebar isCurrent={isCurrent} openSection={openSection} sections={sections} title="Settings" />;
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
    });

    it('keeps ungrouped items as direct links', () => {
        renderSidebar();

        expect(screen.getByRole('link', {name: 'Users'})).toHaveAttribute('href', '/workspace-users');
        expect(screen.getByRole('link', {name: 'Tool Approvals'})).toBeInTheDocument();
    });

    it('folds consecutive items sharing a group behind one row', () => {
        renderSidebar();

        expect(screen.getByRole('link', {name: 'AI'})).toBeInTheDocument();
        expect(screen.queryByRole('link', {name: 'Agents'})).not.toBeInTheDocument();
    });

    // One click into a section, not two: the row goes where its column's first page is.
    it('links the group row to the first page in its column', () => {
        renderSidebar();

        expect(screen.getByRole('link', {name: 'AI'})).toHaveAttribute('href', '/ai-hub/connectors');
    });

    it('opens the submenu column when the group row is followed', async () => {
        const user = userEvent.setup();

        renderSidebar();

        await user.click(screen.getByRole('link', {name: 'AI'}));

        expect(screen.getByTestId('pathname')).toHaveTextContent('/ai-hub/connectors');
        expect(await screen.findByRole('link', {name: 'Hub Connectors'})).toHaveAttribute('aria-current', 'page');
    });

    it('opens the submenu column for the group holding the current route', () => {
        renderSidebar('/ai/agents/rules');

        expect(screen.getByRole('link', {name: 'Agents'})).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', {name: 'Memories'})).not.toHaveAttribute('aria-current');
    });

    it("keeps a closed group's items out of the document", () => {
        renderSidebar();

        expect(screen.queryByRole('link', {name: 'Hub Connectors'})).not.toBeInTheDocument();
        expect(screen.queryByRole('link', {name: 'Memories'})).not.toBeInTheDocument();
    });

    // The submenu column names the page; the group row is the only thing naming the section it sits in.
    it('marks the group row on every route in its section', () => {
        renderSidebar('/ai/memories');

        expect(screen.getByRole('link', {name: 'AI'})).toHaveAttribute('aria-current', 'page');
    });

    it('leaves the group row unmarked on a route outside the section', () => {
        renderSidebar();

        expect(screen.getByRole('link', {name: 'AI'})).not.toHaveAttribute('aria-current');
    });

    // The label appears twice once the column is open: on the row that leads into it, and as the column's
    // own header — which is the only thing naming the column, since its rows carry no group name.
    it('heads the submenu column with the group label', () => {
        renderSidebar('/ai/agents');

        expect(screen.getAllByText('AI')).toHaveLength(2);
    });

    it('heads the primary column with the sidebar title', () => {
        renderSidebar();

        expect(screen.getByText('Settings')).toBeInTheDocument();
        expect(screen.queryAllByText('AI')).toHaveLength(1);
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

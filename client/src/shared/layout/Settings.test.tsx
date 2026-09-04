import {SettingsSidebarNavItemI} from '@/shared/layout/settings-sidebar/useSettingsSidebarSections';
import {render, screen} from '@/shared/util/test-utils';
import {ReactNode} from 'react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import Settings from './Settings';

const hoisted = vi.hoisted(() => ({
    enabledFeatureFlags: [] as string[],
}));

vi.mock('@/shared/layout/Header', () => ({
    default: () => null,
}));

vi.mock('@/shared/layout/LayoutContainer', () => ({
    default: ({leftSidebarBody}: {leftSidebarBody: ReactNode}) => <div>{leftSidebarBody}</div>,
}));

vi.mock('@/shared/stores/useApplicationInfoStore', () => ({
    useApplicationInfoStore: () => false,
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => (featureFlag: string) => hoisted.enabledFeatureFlags.includes(featureFlag),
}));

const renderSettings = (sidebarNavItems: SettingsSidebarNavItemI[], pathname = '/') =>
    render(
        <MemoryRouter initialEntries={[pathname]}>
            <Settings sidebarNavItems={sidebarNavItems} />
        </MemoryRouter>
    );

// What Settings still owns after the sidebar moved out: which rows a feature flag leaves standing, and
// the headings that lose their reason to exist along with them. The two columns themselves are covered
// by settings-sidebar/SettingsSidebar.test.tsx.
describe('Settings', () => {
    beforeEach(() => {
        hoisted.enabledFeatureFlags = [];
    });

    it('hides a section heading when every item below it is hidden by a feature flag', () => {
        renderSettings([
            {href: '/automation/settings/workspaces', title: 'Workspaces'},
            {title: 'Current Workspace'},
            {href: 'git-configuration', title: 'Git Configuration'},
            {href: 'workspace-api-keys', title: 'API Keys'},
            {title: 'Organization'},
            {href: 'users', title: 'Users'},
        ]);

        expect(screen.queryByText('Current Workspace')).not.toBeInTheDocument();
        expect(screen.getByText('Organization')).toBeInTheDocument();
        expect(screen.getByText('Users')).toBeInTheDocument();
    });

    it('keeps a section heading when an item below it is visible', () => {
        hoisted.enabledFeatureFlags = ['ff-1039'];

        renderSettings([
            {title: 'Current Workspace'},
            {href: 'git-configuration', title: 'Git Configuration'},
            {title: 'Organization'},
            {href: 'users', title: 'Users'},
        ]);

        expect(screen.getByText('Current Workspace')).toBeInTheDocument();
        expect(screen.getByText('Git Configuration')).toBeInTheDocument();
    });

    it('hides a trailing section heading that has no items at all', () => {
        renderSettings([{href: 'users', title: 'Users'}, {title: 'Organization'}]);

        expect(screen.queryByText('Organization')).not.toBeInTheDocument();
        expect(screen.getByText('Users')).toBeInTheDocument();
    });

    it('drops a group row whose every entry is hidden by a feature flag', () => {
        renderSettings([
            {title: 'Organization'},
            {href: 'users', title: 'Users'},
            {group: 'AI', href: 'components', title: 'Components'},
        ]);

        expect(screen.queryByText('AI')).not.toBeInTheDocument();
        expect(screen.queryByText('Components')).not.toBeInTheDocument();
        expect(screen.getByText('Users')).toBeInTheDocument();
    });

    it('hides a section heading whose only item is a group hidden by a feature flag', () => {
        renderSettings([
            {href: 'users', title: 'Users'},
            {title: 'Organization'},
            {group: 'AI', href: 'components', title: 'Components'},
        ]);

        expect(screen.queryByText('Organization')).not.toBeInTheDocument();
        expect(screen.getByText('Users')).toBeInTheDocument();
    });
});

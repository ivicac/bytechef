import {PlatformType, usePlatformTypeStore} from '@/pages/home/stores/usePlatformTypeStore';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import SettingsSidebar from '@/shared/layout/settings-sidebar/SettingsSidebar';
import {
    SettingsSidebarNavItemI,
    useSettingsSidebarSections,
} from '@/shared/layout/settings-sidebar/useSettingsSidebarSections';
import {EditionType, useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {Outlet} from 'react-router-dom';

interface SettingsProps {
    sidebarNavItems: SettingsSidebarNavItemI[];
    title?: string;
}

const Settings = ({sidebarNavItems, title = 'Settings'}: SettingsProps) => {
    const currentType = usePlatformTypeStore((state) => state.currentType);

    const billingEnabled = useApplicationInfoStore((state) => state.billing.enabled);
    const mcpServerEnabled = useApplicationInfoStore((state) => state.ai.mcp.server.enabled);
    const aiHubEnabled = useApplicationInfoStore((state) => state.ai.hub.enabled);
    const edition = useApplicationInfoStore((state) => state.application?.edition);
    const isFeatureFlagEnabled = useFeatureFlagsStore();

    // Flag filtering runs before grouping, so a group renders with whatever members survive their flags.
    sidebarNavItems = sidebarNavItems.filter((navItem) => {
        if (navItem.href === 'components') {
            return isFeatureFlagEnabled('ff-1024') || isFeatureFlagEnabled('ff-207');
        }

        if (navItem.href?.includes('/account/appearance')) {
            return isFeatureFlagEnabled('ff-445');
        }

        if (navItem.href === 'git-configuration') {
            return isFeatureFlagEnabled('ff-1039');
        }

        if (navItem.href === 'workspace-api-keys') {
            return (
                (currentType === PlatformType.AUTOMATION &&
                    (isFeatureFlagEnabled('ff-1025') ||
                        isFeatureFlagEnabled('ff-1039') ||
                        isFeatureFlagEnabled('ff-4814'))) ||
                currentType === PlatformType.EMBEDDED
            );
        }

        if (navItem.href === 'mcp-server') {
            return isFeatureFlagEnabled('ff-2197') && mcpServerEnabled;
        }

        if (navItem.href === 'admin-api-keys') {
            return isFeatureFlagEnabled('ff-1024');
        }

        if (navItem.href === 'identity-providers') {
            return isFeatureFlagEnabled('ff-1040');
        }

        if (navItem.href === 'billing') {
            return billingEnabled;
        }

        // The page is EE-only (its route is wrapped in EEVersion) and reads AI Hub GraphQL, which only
        // exists where the hub module is on — so both conditions are the row's real reachability, not a
        // rollout switch. Without them a CE or hub-off instance would offer a nav row leading to a page
        // with no server behind it.
        if (navItem.href === 'ai-hub/tool-approvals') {
            return edition === EditionType.EE && aiHubEnabled;
        }

        return true;
    });

    const {isCurrent, openSection, sections} = useSettingsSidebarSections(sidebarNavItems);

    return (
        <LayoutContainer
            leftSidebarBody={<SettingsSidebar isCurrent={isCurrent} openSection={openSection} sections={sections} />}
            leftSidebarHeader={<Header position="sidebar" title={title} />}
        >
            <div className="size-full">
                <Outlet />
            </div>
        </LayoutContainer>
    );
};

export default Settings;

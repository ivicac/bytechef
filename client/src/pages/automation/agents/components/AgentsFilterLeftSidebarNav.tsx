import LeftSidebarFilterNav from '@/shared/layout/LeftSidebarFilterNav';
import {useSearchParams} from 'react-router-dom';

export type AgentsFilterType = 'all' | 'scheduled';

const AGENTS_FILTER_ITEMS: {id: AgentsFilterType; name: string}[] = [
    {id: 'all', name: 'All Agents'},
    {id: 'scheduled', name: 'Scheduled'},
];

/** Reads the `?agents=all|scheduled` search param, ignoring any other value. */
export const getAgentsFilter = (searchParams: URLSearchParams): AgentsFilterType | undefined => {
    const agentsSearchParam = searchParams.get('agents');

    return agentsSearchParam === 'all' || agentsSearchParam === 'scheduled' ? agentsSearchParam : undefined;
};

interface AgentsFilterLeftSidebarNavProps {
    currentAgentsFilter?: AgentsFilterType;
}

/**
 * The Agents group of a list page's sidebar (Projects, Deployments): narrows the list to entries that contain an
 * agent, or a scheduled one. It combines with the page's other filters, so each link keeps the other search
 * params, and clicking the active item again clears it. It cancels the Data Syncs filter — the two decide which
 * tab every project row opens on, so only one can be active at a time.
 */
const AgentsFilterLeftSidebarNav = ({currentAgentsFilter}: AgentsFilterLeftSidebarNavProps) => {
    const [searchParams] = useSearchParams();

    const getToLink = (agentsFilter: AgentsFilterType) => {
        const nextSearchParams = new URLSearchParams(searchParams);

        nextSearchParams.delete('dataSyncs');

        if (agentsFilter === currentAgentsFilter) {
            nextSearchParams.delete('agents');
        } else {
            nextSearchParams.set('agents', agentsFilter);
        }

        const search = nextSearchParams.toString();

        return search ? `?${search}` : '';
    };

    return (
        <LeftSidebarFilterNav
            items={AGENTS_FILTER_ITEMS.map((item) => ({
                current: item.id === currentAgentsFilter,
                id: item.id,
                name: item.name,
                toLink: getToLink(item.id),
            }))}
            title="Agents"
        />
    );
};

export default AgentsFilterLeftSidebarNav;

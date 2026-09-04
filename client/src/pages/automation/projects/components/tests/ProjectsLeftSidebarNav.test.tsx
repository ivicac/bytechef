import {Type} from '@/pages/automation/projects/Projects';
import {render, resetAll, screen, windowResizeObserver} from '@/shared/util/test-utils';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import ProjectsLeftSidebarNav from '../ProjectsLeftSidebarNav';

const CATEGORIES = [
    {id: 1, name: 'AI'},
    {id: 2, name: 'Records'},
];

const TAGS = [
    {id: 10, name: 'api'},
    {id: 11, name: 'bank'},
];

const FILTER_DATA = {type: Type.Category};

beforeEach(() => {
    windowResizeObserver();
});

afterEach(() => {
    resetAll();
});

const renderNav = (props: Partial<Parameters<typeof ProjectsLeftSidebarNav>[0]> = {}) =>
    render(
        <MemoryRouter>
            <ProjectsLeftSidebarNav categories={CATEGORIES} filterData={FILTER_DATA} tags={TAGS} {...props} />
        </MemoryRouter>
    );

describe('ProjectsLeftSidebarNav', () => {
    it('renders both groups once loaded, with no placeholders left behind', () => {
        renderNav();

        expect(screen.getByText('AI')).toBeInTheDocument();
        expect(screen.getByText('api')).toBeInTheDocument();
        expect(screen.queryByTestId('left-sidebar-nav-skeleton')).not.toBeInTheDocument();
    });

    it('shows placeholders in each group while both queries are in flight', () => {
        renderNav({categories: undefined, categoriesIsLoading: true, tags: undefined, tagsIsLoading: true});

        expect(screen.getAllByTestId('left-sidebar-nav-skeleton')).toHaveLength(2);
        expect(screen.getByText('Categories')).toBeInTheDocument();
        expect(screen.getByText('Tags')).toBeInTheDocument();
    });

    it('places placeholders only in the group that is still loading', () => {
        renderNav({tags: undefined, tagsIsLoading: true});

        expect(screen.getByText('AI')).toBeInTheDocument();
        expect(screen.getAllByTestId('left-sidebar-nav-skeleton')).toHaveLength(1);
    });

    it('holds back the All Categories entry until the categories arrive', () => {
        renderNav({categories: undefined, categoriesIsLoading: true});

        expect(screen.queryByText('All Categories')).not.toBeInTheDocument();

        renderNav();

        expect(screen.getByText('All Categories')).toBeInTheDocument();
    });

    it('reports an empty tag list rather than a placeholder once the tags arrive', () => {
        renderNav({tags: []});

        expect(screen.getByText('No defined tags.')).toBeInTheDocument();
        expect(screen.queryByTestId('left-sidebar-nav-skeleton')).not.toBeInTheDocument();
    });
});

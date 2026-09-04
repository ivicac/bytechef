import {TooltipProvider} from '@/components/ui/tooltip';
import Header from '@/shared/layout/Header';
import LeftSidebarToggleContext from '@/shared/layout/LeftSidebarToggleContext';
import {render, screen} from '@/shared/util/test-utils';
import {ReactNode} from 'react';
import {describe, expect, it} from 'vitest';

const description = 'Reusable instructions any AI agent can load.';

const renderHeader = (ui: ReactNode, hasLeftSidebar = false) =>
    render(
        <TooltipProvider>
            <LeftSidebarToggleContext.Provider
                value={{hasLeftSidebar, leftSidebarOpen: true, toggleLeftSidebar: () => {}}}
            >
                {ui}
            </LeftSidebarToggleContext.Provider>
        </TooltipProvider>
    );

describe('Header', () => {
    it('renders the title and the description', () => {
        renderHeader(<Header description={description} title="AI Skills" />);

        expect(screen.getByText('AI Skills')).toBeInTheDocument();
        expect(screen.getByText(description)).toBeInTheDocument();
    });

    // Beside a search box and a button group the description gets a column a few words wide on a narrow
    // content area. Wrapping grew the header to three lines and pushed the whole page down, so it is cut
    // at one line instead.
    it('truncates the description rather than wrapping it', () => {
        renderHeader(<Header description={description} position="main" title="AI Skills" />);

        expect(screen.getByText(description)).toHaveClass('truncate');
    });

    // `truncate` is inert inside a flex row unless every box between the row and the text may shrink below
    // its content, so the min widths are what make the line actually cut.
    it('lets the title block shrink so the truncation engages', () => {
        renderHeader(<Header description={description} position="main" title="AI Skills" />);

        const titleBlock = screen.getByText(description).parentElement;

        expect(titleBlock).toHaveClass('min-w-0');
        expect(titleBlock?.parentElement).toHaveClass('min-w-0');
    });

    // The right slot is centred against the title AND the description, which is where it sat before the
    // description was ever given a row of its own.
    it('keeps the description in the block the right slot is centred against', () => {
        renderHeader(
            <Header
                description={description}
                position="main"
                right={<button type="button">Create Skill</button>}
                title="AI Skills"
            />
        );

        const headerRow = screen.getByRole('button', {name: 'Create Skill'}).closest('header > div');

        expect(headerRow).toContainElement(screen.getByText(description));
        expect(headerRow).toContainElement(screen.getByText('AI Skills'));
    });

    it('holds the toolbar at its own width so the description is what gives way', () => {
        renderHeader(<Header description={description} position="main" right={<span>Toolbar</span>} title="Skills" />);

        expect(screen.getByText('Toolbar').parentElement).toHaveClass('shrink-0');
    });

    it('renders no description element when a header has none', () => {
        renderHeader(<Header position="main" title="AI Skills" />);

        expect(screen.getByText('AI Skills').parentElement?.children).toHaveLength(1);
    });

    it('shows the sidebar toggle only on a main header of a page that has one', () => {
        renderHeader(<Header position="main" title="AI Skills" />, true);

        expect(screen.getByRole('button', {name: 'Toggle sidebar'})).toBeInTheDocument();
    });

    it('shows no sidebar toggle in a sidebar header', () => {
        renderHeader(<Header position="sidebar" title="Settings" />, true);

        expect(screen.queryByRole('button', {name: 'Toggle sidebar'})).not.toBeInTheDocument();
    });
});

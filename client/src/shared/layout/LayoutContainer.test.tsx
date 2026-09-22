import {TooltipProvider} from '@/components/ui/tooltip';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {act, fireEvent, render, screen, within} from '@/shared/util/test-utils';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const renderLayout = (props: {leftSidebarBody?: boolean; leftSidebarOpen?: boolean} = {}) => {
    const {leftSidebarBody = true, leftSidebarOpen} = props;

    return render(
        <TooltipProvider>
            <LayoutContainer
                header={<Header position="main" title="Projects" />}
                leftSidebarBody={leftSidebarBody ? <div>Categories</div> : undefined}
                leftSidebarOpen={leftSidebarOpen}
            >
                <div>Content</div>
            </LayoutContainer>
        </TooltipProvider>
    );
};

const renderSidebar = () =>
    render(
        <MemoryRouter>
            <LayoutContainer
                leftSidebarBody={
                    <LeftSidebarNav
                        body={<LeftSidebarNavItem item={{current: true, name: 'All Categories'}} toLink="/projects" />}
                        title="Categories"
                    />
                }
                leftSidebarHeader={<Header position="sidebar" title="Projects" />}
            >
                <div>Content</div>
            </LayoutContainer>
        </MemoryRouter>
    );

const desktopWidth = window.innerWidth;

const setViewportWidth = (width: number) => {
    Object.defineProperty(window, 'innerWidth', {configurable: true, value: width, writable: true});
};

describe('LayoutContainer', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();

        setViewportWidth(desktopWidth);
    });

    it('closes the left sidebar from the header toggle and reopens it', () => {
        renderLayout();

        const toggleButton = screen.getByRole('button', {name: 'Toggle sidebar'});

        fireEvent.click(toggleButton);

        expect(screen.getByText('Categories').closest('aside')).toHaveClass('lg:-translate-x-full');

        act(() => {
            vi.advanceTimersByTime(300);
        });

        expect(screen.queryByText('Categories')).not.toBeInTheDocument();

        fireEvent.click(toggleButton);

        expect(screen.getByText('Categories').closest('aside')).toHaveClass('lg:translate-x-0');
    });

    it('offers no toggle on a page without a left sidebar', () => {
        renderLayout({leftSidebarBody: false});

        expect(screen.queryByRole('button', {name: 'Toggle sidebar'})).not.toBeInTheDocument();
    });

    it('offers no toggle on a page that controls its own left sidebar', () => {
        renderLayout({leftSidebarOpen: true});

        expect(screen.queryByRole('button', {name: 'Toggle sidebar'})).not.toBeInTheDocument();
    });

    it('lets a nested sidebar-less container use the outer container toggle', () => {
        render(
            <TooltipProvider>
                <LayoutContainer leftSidebarBody={<div>Settings nav</div>}>
                    <LayoutContainer header={<Header position="main" title="Users" />} leftSidebarOpen={false}>
                        <div>Content</div>
                    </LayoutContainer>
                </LayoutContainer>
            </TooltipProvider>
        );

        fireEvent.click(screen.getByRole('button', {name: 'Toggle sidebar'}));

        expect(screen.getByText('Settings nav').closest('aside')).toHaveClass('lg:-translate-x-full');
    });

    it('opens the sidebar in a drawer when toggled on a small screen', () => {
        setViewportWidth(800);

        renderLayout();

        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', {name: 'Toggle sidebar'}));

        expect(within(screen.getByRole('dialog')).getByText('Categories')).toBeInTheDocument();
    });

    it('does not open a drawer when toggled on a large screen', () => {
        setViewportWidth(1280);

        renderLayout();

        fireEvent.click(screen.getByRole('button', {name: 'Toggle sidebar'}));

        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    // The body's px-2 is what keeps a row's highlight off the rail's edges; the row's own px-2 then lands its
    // label at 16px, level with the header's px-4 title.
    it('insets the sidebar body so row highlights clear both edges of the rail', () => {
        renderSidebar();

        const rowLink = screen.getByRole('link', {name: 'All Categories'});

        expect(rowLink).toHaveClass('w-full', 'px-2');
        expect(rowLink.closest('.overflow-y-auto')).toHaveClass('px-2');
    });

    it('pads the sidebar header title to the same 16px as the body rows', () => {
        renderSidebar();

        expect(screen.getByText('Projects').closest('header')).toHaveClass('px-4');
        expect(screen.getByText('Categories')).toHaveClass('px-2');
    });
});

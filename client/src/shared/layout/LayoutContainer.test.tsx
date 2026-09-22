import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {render, screen} from '@/shared/util/test-utils';
import {MemoryRouter} from 'react-router-dom';
import {describe, expect, it} from 'vitest';

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

describe('LayoutContainer', () => {
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

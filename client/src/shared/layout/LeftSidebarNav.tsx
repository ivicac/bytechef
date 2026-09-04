import {buttonVariants} from '@/components/ui/button';
import {Skeleton} from '@/components/ui/skeleton';
import {cn} from '@/shared/util/cn-utils';
import {ReactNode} from 'react';
import {Link} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';

const SidebarSubtitle = ({title}: {title: string}) => (
    <h4 className="px-2 py-1 pr-4 text-sm font-medium tracking-tight text-muted-foreground">{title}</h4>
);

const SKELETON_ROW_WIDTHS = ['w-3/4', 'w-1/2', 'w-2/3', 'w-3/5', 'w-4/5'];

const LeftSidebarNavSkeleton = ({rows}: {rows: number}) => (
    <div aria-busy="true" aria-label="Loading" data-testid="left-sidebar-nav-skeleton" role="status">
        {Array.from({length: rows}).map((_, rowIndex) => (
            <div className="flex h-9 items-center px-2" key={rowIndex}>
                <Skeleton className={twMerge('h-4', SKELETON_ROW_WIDTHS[rowIndex % SKELETON_ROW_WIDTHS.length])} />
            </div>
        ))}
    </div>
);

export interface LeftSidebarNavProps {
    body: ReactNode;
    title?: string;
    className?: string;
    loading?: boolean;
    loadingRows?: number;
}

const LeftSidebarNav = ({body, className, loading = false, loadingRows = 4, title}: LeftSidebarNavProps) => (
    <div className={twMerge('mb-4 px-2', className)}>
        <div aria-label={title} className="flex space-x-2 lg:flex-col lg:space-x-0">
            {title && <SidebarSubtitle title={title} />}

            {loading ? <LeftSidebarNavSkeleton rows={loadingRows} /> : body}
        </div>
    </div>
);

interface LeftSidebarNavItemProps {
    /** Indents a nested row's label. Padding rather than a wrapper, so the highlight box stays full width. */
    className?: string;
    disabled?: boolean;
    item: {
        current: boolean;
        name: string;
        onItemClick?: (id?: number | string) => void;
        id?: number | string;
    };
    toLink?: string;
    icon?: ReactNode;
    /**
     * Rendered after the label, outside the link — a row menu belongs beside the navigation target, not inside
     * it, where it would be a button nested in an anchor. The wrapper carries `group`, so a trailing control can
     * reveal itself on hover the way the data tables sidebar does.
     */
    trailing?: ReactNode;
}

const LeftSidebarNavItem = ({
    className,
    disabled = false,
    icon,
    item: {current, id, name, onItemClick},
    toLink = '',
    trailing,
}: LeftSidebarNavItemProps) => {
    const link = (
        <Link
            aria-current={current ? 'page' : undefined}
            aria-disabled={disabled || undefined}
            className={cn(
                buttonVariants({variant: 'ghost'}),
                current ? 'bg-accent hover:bg-accent' : 'hover:bg-accent',
                'w-full justify-start px-2 font-normal',
                trailing && 'pr-8',
                disabled && 'pointer-events-none opacity-50',
                className
            )}
            onClick={(event) => {
                if (disabled) {
                    event.preventDefault();

                    return;
                }

                if (onItemClick) {
                    onItemClick(id);
                }
            }}
            tabIndex={disabled ? -1 : undefined}
            to={toLink}
        >
            {icon}

            <span className={cn('truncate', current && 'font-semibold')}>{name}</span>
        </Link>
    );

    if (!trailing) {
        return link;
    }

    return (
        // The hover background lives on the wrapper, not the link: with a trailing control the pointer is often
        // over that control rather than the link, and the row would otherwise show no hover state at all.
        <div className="group relative flex items-center rounded-md hover:bg-accent">
            {link}

            <div className="absolute right-1">{trailing}</div>
        </div>
    );
};

export {LeftSidebarNav, LeftSidebarNavItem};

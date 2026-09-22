import LeftSidebarFilterNav from '@/shared/layout/LeftSidebarFilterNav';
import {TagIcon} from 'lucide-react';
import {ReactNode} from 'react';

interface CategoryTagLeftSidebarNavCategoryI {
    id?: number;
    name: string;
}

interface CategoryTagLeftSidebarNavTagI {
    id?: number;
    name: string;
}

interface CategoryTagLeftSidebarNavProps {
    categories: CategoryTagLeftSidebarNavCategoryI[] | undefined;
    categoriesIsLoading?: boolean;
    currentCategoryId?: number;
    currentTagId?: number;
    /** Groups appended after Tags, such as the embedded Unified API filters. */
    extraGroups?: ReactNode;
    /** Groups placed between Categories and Tags, such as the automation Projects agents filter. */
    middleGroups?: ReactNode;
    /**
     * True when a filter outside these two groups is active, which stops "All Categories" from claiming to be
     * the current one.
     */
    otherFilterActive?: boolean;
    /**
     * Search params (without the leading `?`) carried over by every category and tag link, so a filter outside
     * these two groups survives picking a category or a tag.
     */
    preservedSearchParams?: string;
    tags: CategoryTagLeftSidebarNavTagI[] | undefined;
    tagsClassName?: string;
    tagsEmptyMessage: string;
    tagsIsLoading?: boolean;
}

/**
 * The category-and-tag rail shared by the pages that filter a catalog that way — automation Projects and
 * embedded Integrations. They ask the same two questions of their data, so they ask them through one component
 * rather than each keeping a copy of the markup.
 */
const CategoryTagLeftSidebarNav = ({
    categories,
    categoriesIsLoading = false,
    currentCategoryId,
    currentTagId,
    extraGroups,
    middleGroups,
    otherFilterActive = false,
    preservedSearchParams,
    tags,
    tagsClassName,
    tagsEmptyMessage,
    tagsIsLoading = false,
}: CategoryTagLeftSidebarNavProps) => {
    const preservedSuffix = preservedSearchParams ? `&${preservedSearchParams}` : '';

    return (
        <>
            <LeftSidebarFilterNav
                items={(categories ?? []).map((category) => ({
                    current: currentCategoryId === category.id,
                    id: category.id!,
                    name: category.name,
                    toLink: `?categoryId=${category.id}${preservedSuffix}`,
                }))}
                leadItem={{
                    current: currentCategoryId === undefined && currentTagId === undefined && !otherFilterActive,
                    name: 'All Categories',
                    toLink: preservedSearchParams ? `?${preservedSearchParams}` : '',
                }}
                loading={categoriesIsLoading}
                title="Categories"
            />

            {middleGroups}

            <LeftSidebarFilterNav
                className={tagsClassName}
                emptyMessage={tagsEmptyMessage}
                icon={<TagIcon className="mr-1 size-4" />}
                items={(tags ?? []).map((tag) => ({
                    current: currentTagId === tag.id,
                    id: tag.id!,
                    name: tag.name,
                    toLink: `?tagId=${tag.id}${preservedSuffix}`,
                }))}
                loading={tagsIsLoading}
                title="Tags"
            />

            {extraGroups}
        </>
    );
};

export default CategoryTagLeftSidebarNav;

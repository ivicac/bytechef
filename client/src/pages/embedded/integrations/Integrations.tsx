import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {LeftSidebarNav, LeftSidebarNavItem} from '@/shared/layout/LeftSidebarNav';
import {useGetIntegrationCategoriesQuery} from '@/shared/queries/embedded/integrationCategories.queries';
import {useGetIntegrationTagsQuery} from '@/shared/queries/embedded/integrationTags.quries';
import {TagIcon} from 'lucide-react';
import {useState} from 'react';
import {Outlet, useLocation, useSearchParams} from 'react-router-dom';

export enum Type {
    Category,
    Tag,
    UnifiedAPI,
}

const Integrations = () => {
    const {pathname} = useLocation();
    const [searchParams] = useSearchParams();

    const defaultCurrentState = {
        id: pathname.includes('unified')
            ? searchParams.get('categoryId')!
            : searchParams.get('categoryId')
              ? parseInt(searchParams.get('categoryId')!)
              : searchParams.get('tagId')
                ? parseInt(searchParams.get('tagId')!)
                : undefined,
        type: pathname.includes('unified') ? Type.UnifiedAPI : searchParams.get('tagId') ? Type.Tag : Type.Category,
    };

    const [filterData, setFilterData] = useState<{id?: number | string; type: Type}>(defaultCurrentState);

    const {data: categories, isLoading: categoriesLoading} = useGetIntegrationCategoriesQuery();

    const {data: tags, isLoading: tagsLoading} = useGetIntegrationTagsQuery();

    return (
        <LayoutContainer
            leftSidebarBody={
                <>
                    <h3 className="px-4 py-2 font-semibold">Embedded iPaaS</h3>

                    <div className="pl-2">
                        <LeftSidebarNav
                            body={
                                <>
                                    <LeftSidebarNavItem
                                        item={{
                                            current: !filterData?.id && filterData.type === Type.Category,
                                            name: 'All Categories',
                                            onItemClick: (id?: number | string) => {
                                                setFilterData({
                                                    id: id as number,
                                                    type: Type.Category,
                                                });
                                            },
                                        }}
                                    />

                                    {!categoriesLoading &&
                                        categories?.map((item) => (
                                            <LeftSidebarNavItem
                                                item={{
                                                    current:
                                                        filterData?.id === item.id && filterData.type === Type.Category,
                                                    id: item.id,
                                                    name: item.name,
                                                    onItemClick: (id?: number | string) => {
                                                        setFilterData({
                                                            id: id as number,
                                                            type: Type.Category,
                                                        });
                                                    },
                                                }}
                                                key={item.name}
                                                toLink={`?categoryId=${item.id}`}
                                            />
                                        ))}
                                </>
                            }
                            title="Categories"
                        />

                        <LeftSidebarNav
                            body={
                                <>
                                    {!tagsLoading &&
                                        (tags?.length ? (
                                            tags?.map((item) => (
                                                <LeftSidebarNavItem
                                                    icon={<TagIcon className="mr-1 size-4" />}
                                                    item={{
                                                        current:
                                                            filterData?.id === item.id && filterData.type === Type.Tag,
                                                        id: item.id!,
                                                        name: item.name,
                                                        onItemClick: (id?: number | string) => {
                                                            setFilterData({
                                                                id: id as number,
                                                                type: Type.Tag,
                                                            });
                                                        },
                                                    }}
                                                    key={item.id}
                                                    toLink={`?tagId=${item.id}`}
                                                />
                                            ))
                                        ) : (
                                            <p className="px-3 text-xs">No tags.</p>
                                        ))}
                                </>
                            }
                            title="Tags"
                        />
                    </div>

                    <h3 className="px-4 py-2 pt-6 font-semibold">Unified API</h3>

                    <div className="pl-2">
                        <LeftSidebarNav
                            body={
                                <>
                                    <LeftSidebarNavItem
                                        item={{
                                            current:
                                                filterData?.id === 'accounting' && filterData.type === Type.UnifiedAPI,
                                            id: 'accounting',
                                            name: 'Accounting',
                                            onItemClick: (id?: number | string) => {
                                                setFilterData({
                                                    id: id as number,
                                                    type: Type.UnifiedAPI,
                                                });
                                            },
                                        }}
                                        toLink="unified?categoryId=accounting"
                                    />

                                    <LeftSidebarNavItem
                                        item={{
                                            current:
                                                filterData?.id === 'commerce' && filterData.type === Type.UnifiedAPI,
                                            id: 'commerce',
                                            name: 'Commerce',
                                            onItemClick: (id?: number | string) => {
                                                setFilterData({
                                                    id: id as number,
                                                    type: Type.UnifiedAPI,
                                                });
                                            },
                                        }}
                                        toLink="unified?categoryId=commerce"
                                    />

                                    <LeftSidebarNavItem
                                        item={{
                                            current: filterData?.id === 'crm' && filterData.type === Type.UnifiedAPI,
                                            id: 'crm',
                                            name: 'CRM',
                                            onItemClick: (id?: number | string) => {
                                                setFilterData({
                                                    id: id as string,
                                                    type: Type.UnifiedAPI,
                                                });
                                            },
                                        }}
                                        toLink="unified?categoryId=crm"
                                    />
                                </>
                            }
                        />
                    </div>
                </>
            }
            leftSidebarHeader={<Header position="sidebar" title="Integrations" />}
        >
            <Outlet />
        </LayoutContainer>
    );
};

export default Integrations;

import Button from '@/components/Button/Button';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import IntegrationDialog from '@/ee/pages/embedded/integrations/components/IntegrationDialog';
import IntegrationsFilterTitle from '@/ee/pages/embedded/integrations/components/IntegrationsFilterTitle';
import IntegrationsLeftSidebarNav from '@/ee/pages/embedded/integrations/components/IntegrationsLeftSidebarNav';
import NewIntegrationCodeWorkflowDialog from '@/ee/pages/embedded/integrations/components/NewIntegrationCodeWorkflowDialog';
import IntegrationList from '@/ee/pages/embedded/integrations/components/integration-list/IntegrationList';
import {useGetComponentDefinitionsQuery} from '@/ee/shared/queries/embedded/componentDefinitions.queries';
import {useGetIntegrationCategoriesQuery} from '@/ee/shared/queries/embedded/integrationCategories.queries';
import {useGetIntegrationTagsQuery} from '@/ee/shared/queries/embedded/integrationTags.quries';
import {useGetIntegrationsQuery} from '@/ee/shared/queries/embedded/integrations.queries';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {useGetTaskDispatcherDefinitionsQuery} from '@/shared/queries/platform/taskDispatcherDefinitions.queries';
import {ChevronDownIcon, SquareIcon} from 'lucide-react';
import {useState} from 'react';
import {useNavigate, useSearchParams} from 'react-router-dom';

export enum Type {
    Category,
    Tag,
    UnifiedAPI,
}

const Integrations = () => {
    const [showIntegrationDialog, setShowIntegrationDialog] = useState(false);
    const [showNewCodeWorkflowDialog, setShowNewCodeWorkflowDialog] = useState(false);

    const [searchParams] = useSearchParams();

    const categoryId = searchParams.get('categoryId');
    const tagId = searchParams.get('tagId');

    const filterData: {id: number | string | undefined; type: Type} = {
        id: categoryId ? parseInt(categoryId) : tagId ? parseInt(tagId) : undefined,
        type: tagId ? Type.Tag : Type.Category,
    };

    const navigate = useNavigate();

    const {
        data: integrations,
        error: integrationsError,
        isLoading: integrationsLoading,
    } = useGetIntegrationsQuery({
        categoryId: searchParams.get('categoryId') ? parseInt(searchParams.get('categoryId')!) : undefined,
        tagId: searchParams.get('tagId') ? parseInt(searchParams.get('tagId')!) : undefined,
    });

    const {data: componentDefinitions} = useGetComponentDefinitionsQuery({
        actionDefinitions: true,
        triggerDefinitions: true,
    });

    const {data: categories, error: categoriesError, isLoading: categoriesLoading} = useGetIntegrationCategoriesQuery();

    const {data: tags, error: tagsError, isLoading: tagsLoading} = useGetIntegrationTagsQuery();

    const {data: taskDispatcherDefinitions} = useGetTaskDispatcherDefinitionsQuery();

    return (
        <LayoutContainer
            header={
                integrations &&
                integrations?.length > 0 && (
                    <Header
                        centerTitle={true}
                        position="main"
                        right={
                            integrations &&
                            integrations.length > 0 && (
                                <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                        <Button>
                                            New Integration
                                            <ChevronDownIcon className="ml-1 size-4" />
                                        </Button>
                                    </DropdownMenuTrigger>

                                    <DropdownMenuContent align="end">
                                        <DropdownMenuItem onClick={() => setShowIntegrationDialog(true)}>
                                            New Integration
                                        </DropdownMenuItem>

                                        <DropdownMenuItem onClick={() => setShowNewCodeWorkflowDialog(true)}>
                                            New code workflow
                                        </DropdownMenuItem>
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            )
                        }
                        title={<IntegrationsFilterTitle categories={categories} filterData={filterData} tags={tags} />}
                    />
                )
            }
            leftSidebarBody={<IntegrationsLeftSidebarNav categories={categories} filterData={filterData} tags={tags} />}
            leftSidebarHeader={<Header position="sidebar" title="Integrations" />}
            leftSidebarWidth="64"
        >
            <PageLoader
                errors={[categoriesError, integrationsError, tagsError]}
                loading={categoriesLoading || integrationsLoading || tagsLoading}
            >
                {integrations && integrations?.length > 0 && tags ? (
                    <IntegrationList
                        componentDefinitions={componentDefinitions}
                        integrations={integrations}
                        tags={tags}
                        taskDispatcherDefinitions={taskDispatcherDefinitions}
                    />
                ) : (
                    <EmptyList
                        button={
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <Button>
                                        Create Integration
                                        <ChevronDownIcon className="ml-1 size-4" />
                                    </Button>
                                </DropdownMenuTrigger>

                                <DropdownMenuContent align="end">
                                    <DropdownMenuItem onClick={() => setShowIntegrationDialog(true)}>
                                        New Integration
                                    </DropdownMenuItem>

                                    <DropdownMenuItem onClick={() => setShowNewCodeWorkflowDialog(true)}>
                                        New code workflow
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        }
                        icon={<SquareIcon className="size-24 text-gray-300" />}
                        message="Get started by creating a new integrations."
                        title="No Integrations"
                    />
                )}
            </PageLoader>

            {showIntegrationDialog && (
                <IntegrationDialog
                    integration={undefined}
                    onClose={(integration) => {
                        setShowIntegrationDialog(false);

                        if (integration) {
                            navigate(
                                `/embedded/integrations/${integration?.id}/integration-workflows/${integration?.integrationWorkflowIds![0]}`
                            );
                        }
                    }}
                />
            )}

            {showNewCodeWorkflowDialog && (
                <NewIntegrationCodeWorkflowDialog onClose={() => setShowNewCodeWorkflowDialog(false)} />
            )}
        </LayoutContainer>
    );
};

export default Integrations;

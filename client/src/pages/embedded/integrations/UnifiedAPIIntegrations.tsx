import PageLoader from '@/components/PageLoader';
import {Type} from '@/pages/embedded/integrations/Integrations';
import UnifiedAPIIntegrationList from '@/pages/embedded/integrations/components/UnifiedAPIIntegrationList';
import Header from '@/shared/layout/Header';
import {useGetUnifiedIntegrationsQuery} from '@/shared/queries/embedded/unifiedIntegrations.queries';
import {useGetComponentDefinitionsQuery} from '@/shared/queries/platform/componentDefinitions.queries';
import {useLocation, useSearchParams} from 'react-router-dom';

const UnifiedAPIIntegrations = () => {
    const {pathname} = useLocation();
    const [searchParams] = useSearchParams();

    const filterData = {
        id: pathname.includes('unified')
            ? searchParams.get('categoryId')!
            : searchParams.get('categoryId')
              ? parseInt(searchParams.get('categoryId')!)
              : searchParams.get('tagId')
                ? parseInt(searchParams.get('tagId')!)
                : undefined,
        type: searchParams.get('tagId') ? Type.Tag : Type.Category,
    };

    const {
        data: componentDefinitions,
        error: componentDefinitionsError,
        isLoading: componentDefinitionsLoading,
    } = useGetComponentDefinitionsQuery({
        connectionDefinitions: true,
    });

    const {
        data: unifiedIntegrations,
        error: unifiedIntegrationsError,
        isLoading: unifiedIntegrationsLoading,
    } = useGetUnifiedIntegrationsQuery();

    return (
        <PageLoader
            errors={[componentDefinitionsError, unifiedIntegrationsError]}
            loading={componentDefinitionsLoading || unifiedIntegrationsLoading}
        >
            {componentDefinitions && unifiedIntegrations && (
                <div className="flex size-full flex-col">
                    <Header centerTitle={true} position="main" title={`Unified API Category: ${filterData.id}`} />

                    <div className="flex-1 overflow-y-auto">
                        <UnifiedAPIIntegrationList
                            componentDefinitions={componentDefinitions}
                            unifiedIntegrations={unifiedIntegrations}
                        />
                    </div>
                </div>
            )}
        </PageLoader>
    );
};

export default UnifiedAPIIntegrations;

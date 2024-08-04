import {UnifiedIntegrationApi, UnifiedIntegrationModel} from '@/shared/middleware/embedded/configuration';

/* eslint-disable sort-keys */
import {useQuery} from '@tanstack/react-query';

export const UnifiedIntegrationKeys = {
    unifiedIntegrations: ['unifiedIntegrations'] as const,
};

export const useGetUnifiedIntegrationsQuery = () =>
    useQuery<UnifiedIntegrationModel[], Error>({
        queryKey: UnifiedIntegrationKeys.unifiedIntegrations,
        queryFn: () => new UnifiedIntegrationApi().getUnifiedIntegrations(),
    });

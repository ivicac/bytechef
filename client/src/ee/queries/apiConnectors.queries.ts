import {ApiConnectorApi, ApiConnectorModel} from '@/middleware/platform/api-connector';

/* eslint-disable sort-keys */
import {useQuery} from '@tanstack/react-query';

export const ApiConnectorKeys = {
    apiConnectors: ['apiConnectors'] as const,
};

export const useGetApiConnectorsQuery = () =>
    useQuery<ApiConnectorModel[], Error>({
        queryKey: ApiConnectorKeys.apiConnectors,
        queryFn: () => new ApiConnectorApi().getApiConnectors(),
    });

import {CustomComponentApi, CustomComponentModel} from '@/middleware/platform/custom-component';

/* eslint-disable sort-keys */
import {useQuery} from '@tanstack/react-query';

export const CustomComponentKeys = {
    customComponents: ['customComponents'] as const,
};

export const useGetCustomComponentsQuery = () =>
    useQuery<CustomComponentModel[], Error>({
        queryKey: CustomComponentKeys.customComponents,
        queryFn: () => new CustomComponentApi().getCustomComponents(),
    });
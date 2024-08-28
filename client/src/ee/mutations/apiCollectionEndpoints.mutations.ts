import {ApiCollectionEndpointApi, ApiCollectionEndpointModel} from '@/middleware/automation/api-platform';
import {useMutation} from '@tanstack/react-query';

interface CreateApiCollectionEndpointMutationProps {
    onError?: (error: Error, variables: ApiCollectionEndpointModel) => void;
    onSuccess?: (result: ApiCollectionEndpointModel, variables: ApiCollectionEndpointModel) => void;
}

export const useCreateApiCollectionEndpointMutation = (mutationProps?: CreateApiCollectionEndpointMutationProps) =>
    useMutation<ApiCollectionEndpointModel, Error, ApiCollectionEndpointModel>({
        mutationFn: (apiCollectionEndpointModel: ApiCollectionEndpointModel) => {
            return new ApiCollectionEndpointApi().createApiCollectionEndpoint({
                apiCollectionEndpointModel,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

interface DeleteApiEndpointMutationProps {
    onError?: (error: Error, variables: number) => void;
    onSuccess?: (result: void, variables: number) => void;
}

export const useDeleteApiCollectionEndpointMutation = (mutationProps?: DeleteApiEndpointMutationProps) =>
    useMutation<void, Error, number>({
        mutationFn: (id: number) => {
            return new ApiCollectionEndpointApi().deleteApiCollectionEndpoint({
                id,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

interface UpdateApiCollectionEndpointMutationProps {
    onError?: (error: Error, variables: ApiCollectionEndpointModel) => void;
    onSuccess?: (result: ApiCollectionEndpointModel, variables: ApiCollectionEndpointModel) => void;
}

export const useUpdateApiCollectionEndpointMutation = (mutationProps?: UpdateApiCollectionEndpointMutationProps) =>
    useMutation<ApiCollectionEndpointModel, Error, ApiCollectionEndpointModel>({
        mutationFn: (apiCollectionEndpointModel: ApiCollectionEndpointModel) => {
            return new ApiCollectionEndpointApi().updateApiCollectionEndpoint({
                apiCollectionEndpointModel,
                id: apiCollectionEndpointModel.id!,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

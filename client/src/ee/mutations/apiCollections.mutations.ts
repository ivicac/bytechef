import {ApiCollectionApi, ApiCollectionModel} from '@/middleware/automation/api-platform';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useMutation} from '@tanstack/react-query';

interface CreateApiCollectionMutationProps {
    onError?: (error: Error, variables: ApiCollectionModel) => void;
    onSuccess?: (result: ApiCollectionModel, variables: ApiCollectionModel) => void;
}

export const useCreateApiCollectionMutation = (mutationProps?: CreateApiCollectionMutationProps) => {
    const {currentWorkspaceId} = useWorkspaceStore();

    return useMutation<ApiCollectionModel, Error, ApiCollectionModel>({
        mutationFn: (apiCollectionModel: ApiCollectionModel) => {
            return new ApiCollectionApi().createApiCollection({
                apiCollectionModel: {
                    ...apiCollectionModel,
                    workspaceId: currentWorkspaceId!,
                },
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });
};

interface DeleteApiCollectionMutationProps {
    onError?: (error: Error, variables: number) => void;
    onSuccess?: (result: void, variables: number) => void;
}

export const useDeleteApiCollectionMutation = (mutationProps?: DeleteApiCollectionMutationProps) =>
    useMutation<void, Error, number>({
        mutationFn: (id: number) => {
            return new ApiCollectionApi().deleteApiCollection({
                id,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

interface UpdateApiCollectionMutationProps {
    onError?: (error: Error, variables: ApiCollectionModel) => void;
    onSuccess?: (result: ApiCollectionModel, variables: ApiCollectionModel) => void;
}

export const useUpdateApiCollectionMutation = (mutationProps?: UpdateApiCollectionMutationProps) =>
    useMutation<ApiCollectionModel, Error, ApiCollectionModel>({
        mutationFn: (apiCollectionModel: ApiCollectionModel) => {
            return new ApiCollectionApi().updateApiCollection({
                apiCollectionModel,
                id: apiCollectionModel.id!,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

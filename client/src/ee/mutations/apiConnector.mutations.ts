import {
    ApiConnectorApi,
    ApiConnectorModel,
    EnableApiConnectorRequest,
    ImportOpenApiSpecificationRequestModel,
} from '@/middleware/platform/api-connector';
import {useMutation} from '@tanstack/react-query';

interface CreateApiConnectorMutationProps {
    onError?: (error: Error, variables: ApiConnectorModel) => void;
    onSuccess?: (result: ApiConnectorModel, variables: ApiConnectorModel) => void;
}

export const useCreateApiConnectorMutation = (mutationProps?: CreateApiConnectorMutationProps) =>
    useMutation<ApiConnectorModel, Error, ApiConnectorModel>({
        mutationFn: (apiConnectorModel: ApiConnectorModel) => {
            return new ApiConnectorApi().createApiConnector({
                apiConnectorModel,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

interface DeleteApiConnectorMutationProps {
    onError?: (error: Error, variables: number) => void;
    onSuccess?: (result: void, variables: number) => void;
}

export const useDeleteApiConnectorMutation = (mutationProps?: DeleteApiConnectorMutationProps) =>
    useMutation<void, Error, number>({
        mutationFn: (id: number) => {
            return new ApiConnectorApi().deleteApiConnector({
                id,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

interface ImportOpenApiSpecificationRequestApiConnectorMutationProps {
    onError?: (error: Error, variables: ImportOpenApiSpecificationRequestModel) => void;
    onSuccess?: (result: ApiConnectorModel, variables: ImportOpenApiSpecificationRequestModel) => void;
}

interface EnableApiConnectorMutationProps {
    onSuccess?: (result: void, variables: EnableApiConnectorRequest) => void;
    onError?: (error: Error, variables: EnableApiConnectorRequest) => void;
}

export const useEnableApiConnectorMutation = (mutationProps: EnableApiConnectorMutationProps) =>
    useMutation({
        mutationFn: (request: EnableApiConnectorRequest) => {
            return new ApiConnectorApi().enableApiConnector(request);
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

export const useImportOpenApiSpecificationMutation = (
    mutationProps?: ImportOpenApiSpecificationRequestApiConnectorMutationProps
) =>
    useMutation<ApiConnectorModel, Error, ImportOpenApiSpecificationRequestModel>({
        mutationFn: (importOpenApiSpecificationRequestModel: ImportOpenApiSpecificationRequestModel) => {
            return new ApiConnectorApi().importOpenApiSpecification({
                importOpenApiSpecificationRequestModel,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

interface UpdateApiConnectorMutationProps {
    onError?: (error: Error, variables: ApiConnectorModel) => void;
    onSuccess?: (result: ApiConnectorModel, variables: ApiConnectorModel) => void;
}

export const useUpdateApiConnectorMutation = (mutationProps?: UpdateApiConnectorMutationProps) =>
    useMutation<ApiConnectorModel, Error, ApiConnectorModel>({
        mutationFn: (apiConnectorModel: ApiConnectorModel) => {
            return new ApiConnectorApi().updateApiConnector({
                apiConnectorModel,
                id: apiConnectorModel.id!,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

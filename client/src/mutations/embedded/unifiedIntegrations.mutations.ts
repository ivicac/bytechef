import {UnifiedIntegrationApi} from '@/shared/middleware/embedded/configuration';
import {useMutation} from '@tanstack/react-query';

interface EnableUnifiedIntegrationMutationProps {
    onError?: (error: Error, variables: {componentName: string; enable: boolean}) => void;
    onSuccess?: (result: void, variables: {componentName: string; enable: boolean}) => void;
}

export const useEnableUnifiedIntegrationMutation = (mutationProps?: EnableUnifiedIntegrationMutationProps) =>
    useMutation<void, Error, {componentName: string; enable: boolean}>({
        mutationFn: ({componentName, enable}: {componentName: string; enable: boolean}) => {
            return new UnifiedIntegrationApi().enableUnifiedIntegration({
                componentName,
                enable,
            });
        },
        onError: mutationProps?.onError,
        onSuccess: mutationProps?.onSuccess,
    });

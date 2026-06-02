import {GenerateSampleOutputInput, useGenerateSampleOutputMutation} from '@/shared/middleware/graphql';

export function useGenerateSampleOutput() {
    const {isPending, mutateAsync} = useGenerateSampleOutputMutation();

    const generate = async (input: GenerateSampleOutputInput) => {
        const data = await mutateAsync({input});

        return data.generateSampleOutput;
    };

    return {generate, isPending};
}

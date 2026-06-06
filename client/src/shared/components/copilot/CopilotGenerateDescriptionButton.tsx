import Button from '@/components/Button/Button';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {Loader2Icon, SparklesIcon} from 'lucide-react';
import {toast} from 'sonner';

import {useGenerateWorkflowDescription} from './useGenerateWorkflowDescription';

interface CopilotGenerateDescriptionButtonPropsI {
    environmentId: number;
    onApply: (value: string) => void;
    workflowId?: string;
    workflowNodeName?: string;
}

const CopilotGenerateDescriptionButton = ({
    environmentId,
    onApply,
    workflowId,
    workflowNodeName,
}: CopilotGenerateDescriptionButtonPropsI) => {
    const ai = useApplicationInfoStore((state) => state.ai);

    const {generate, isPending} = useGenerateWorkflowDescription();

    if (!ai.copilot.enabled || !workflowId) {
        return null;
    }

    const handleGenerate = async () => {
        try {
            const result = await generate({environmentId, workflowId, workflowNodeName});

            onApply(result.value);
        } catch (error) {
            console.error('Failed to generate the description:', error);

            if (error instanceof TypeError) {
                toast.error(`Failed to generate the description: ${error.message}`);
            }
        }
    };

    return (
        <Button
            aria-label="Generate with AI"
            disabled={isPending}
            icon={isPending ? <Loader2Icon className="animate-spin" /> : <SparklesIcon />}
            onClick={handleGenerate}
            size="iconXs"
            variant="ghost"
        />
    );
};

export default CopilotGenerateDescriptionButton;

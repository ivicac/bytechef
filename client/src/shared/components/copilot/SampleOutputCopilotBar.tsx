import Button from '@/components/Button/Button';
import {Textarea} from '@/components/ui/textarea';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {Loader2Icon, SparklesIcon} from 'lucide-react';
import {useState} from 'react';

import {useGenerateSampleOutput} from './useGenerateSampleOutput';

interface SampleOutputCopilotBarPropsI {
    currentEditorIsEmpty: boolean;
    environmentId: number;
    onApply: (value: string) => void;
    workflowId?: string;
}

const SampleOutputCopilotBar = ({
    currentEditorIsEmpty,
    environmentId,
    onApply,
    workflowId,
}: SampleOutputCopilotBarPropsI) => {
    const ai = useApplicationInfoStore((state) => state.ai);
    const ff1570 = useFeatureFlagsStore()('ff-1570');

    const [error, setError] = useState<string | null>(null);
    const [prompt, setPrompt] = useState('');

    const {generate, isPending} = useGenerateSampleOutput();

    if (!ai.copilot.enabled || !ff1570 || !workflowId) {
        return null;
    }

    const handleGenerate = async () => {
        setError(null);

        try {
            const result = await generate({environmentId, prompt, workflowId});

            if (!result.valid) {
                setError(result.message ?? 'The generated sample output could not be validated.');

                return;
            }

            if (!currentEditorIsEmpty && !window.confirm('Replace the current sample data?')) {
                return;
            }

            onApply(result.value);
        } catch (generateError) {
            setError(generateError instanceof Error ? generateError.message : 'Generation failed.');
        }
    };

    return (
        <div className="flex flex-col gap-1 rounded-md border border-input bg-surface-neutral-primary p-2">
            <div className="flex items-center gap-1 text-sm font-medium text-content-neutral-primary">
                <SparklesIcon className="size-4" />

                <span>Generate with AI</span>
            </div>

            <Textarea
                className="min-h-14 resize-none text-sm"
                onChange={(event) => setPrompt(event.target.value)}
                placeholder="Describe the sample output you want…"
                value={prompt}
            />

            <div className="flex justify-end">
                <Button
                    disabled={isPending || prompt.trim().length === 0}
                    icon={isPending ? <Loader2Icon className="animate-spin" /> : undefined}
                    onClick={handleGenerate}
                    size="xs"
                >
                    {isPending ? 'Generating…' : 'Generate'}
                </Button>
            </div>

            {error && <span className="text-xs text-destructive">{error}</span>}
        </div>
    );
};

export default SampleOutputCopilotBar;

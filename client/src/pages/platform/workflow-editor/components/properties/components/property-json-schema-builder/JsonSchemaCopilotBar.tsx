import Button from '@/components/Button/Button';
import {SchemaRecordType} from '@/components/JsonSchemaBuilder/utils/types';
import {Textarea} from '@/components/ui/textarea';
import {PropertyCopilotMode} from '@/shared/middleware/graphql-types';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {Loader2Icon, SparklesIcon} from 'lucide-react';
import {useState} from 'react';

import {useGeneratePropertyValue} from '../property-copilot/useGeneratePropertyValue';

interface JsonSchemaCopilotBarPropsI {
    currentSchemaIsEmpty: boolean;
    environmentId: number;
    onApply: (schema: SchemaRecordType) => void;
    propertyPath: string;
    workflowId?: string;
    workflowNodeName?: string;
}

const JsonSchemaCopilotBar = ({
    currentSchemaIsEmpty,
    environmentId,
    onApply,
    propertyPath,
    workflowId,
    workflowNodeName,
}: JsonSchemaCopilotBarPropsI) => {
    const ai = useApplicationInfoStore((state) => state.ai);
    const ff1570 = useFeatureFlagsStore()('ff-1570');

    const [error, setError] = useState<string | null>(null);
    const [prompt, setPrompt] = useState('');

    const {generate, isPending} = useGeneratePropertyValue();

    if (!ai.copilot.enabled || !ff1570 || !workflowId || !workflowNodeName) {
        return null;
    }

    const handleGenerate = async () => {
        setError(null);

        try {
            const result = await generate({
                dynamic: false,
                environmentId,
                mode: PropertyCopilotMode.JsonSchema,
                prompt,
                propertyPath,
                propertyType: 'STRING',
                workflowId,
                workflowNodeName,
            });

            if (!result.valid) {
                setError(result.message ?? 'The generated schema could not be validated.');

                return;
            }

            let parsed: SchemaRecordType;

            try {
                parsed = JSON.parse(result.value);
            } catch {
                setError(result.message ?? 'The generated schema was not valid JSON.');

                return;
            }

            if (!currentSchemaIsEmpty && !window.confirm('Replace the current schema?')) {
                return;
            }

            onApply(parsed);
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
                placeholder="Describe the structure you want…"
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

export default JsonSchemaCopilotBar;

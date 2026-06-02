import Button from '@/components/Button/Button';
import {PropertyCopilotMode} from '@/shared/middleware/graphql-types';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useFeatureFlagsStore} from '@/shared/stores/useFeatureFlagsStore';
import {SparklesIcon} from 'lucide-react';
import {RefObject, useEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';

import PropertyCopilotPopover from './PropertyCopilotPopover';
import {useGeneratePropertyValue} from './useGeneratePropertyValue';

interface PropertyCopilotButtonPropsI {
    anchorRef?: RefObject<HTMLDivElement | null>;
    environmentId: number;
    getHasValue: () => boolean;
    mode: PropertyCopilotMode;
    onApply: (value: string) => void;
    propertyPath: string;
    propertyType?: string;
    workflowId: string;
    workflowNodeName: string;
}

const PropertyCopilotButton = ({
    anchorRef,
    environmentId,
    getHasValue,
    mode,
    onApply,
    propertyPath,
    propertyType,
    workflowId,
    workflowNodeName,
}: PropertyCopilotButtonPropsI) => {
    const ai = useApplicationInfoStore((state) => state.ai);
    const ff1570 = useFeatureFlagsStore()('ff-1570');

    const [error, setError] = useState<string | null>(null);
    const [generatedValue, setGeneratedValue] = useState<string | null>(null);
    const [hasValue, setHasValue] = useState(false);
    const [open, setOpen] = useState(false);

    const panelRef = useRef<HTMLDivElement>(null);
    const triggerRef = useRef<HTMLSpanElement>(null);

    const {generate, isPending} = useGeneratePropertyValue();

    const close = () => {
        setOpen(false);
        setError(null);
        setGeneratedValue(null);
    };

    useEffect(() => {
        if (!open) {
            return;
        }

        const handlePointerDown = (event: MouseEvent) => {
            const target = event.target as Node;

            if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) {
                return;
            }

            close();
        };

        document.addEventListener('mousedown', handlePointerDown);

        return () => document.removeEventListener('mousedown', handlePointerDown);
    }, [open]);

    if (!ai.copilot.enabled || !ff1570) {
        return null;
    }

    const handleGenerate = async (prompt: string) => {
        setError(null);

        try {
            const result = await generate({
                environmentId,
                mode,
                prompt,
                propertyPath,
                propertyType,
                workflowId,
                workflowNodeName,
            });

            setGeneratedValue(result.value);
            setHasValue(getHasValue());

            if (!result.valid) {
                setError(result.message ?? 'The generated value could not be validated.');
            }
        } catch (generateError) {
            setGeneratedValue(null);
            setError(generateError instanceof Error ? generateError.message : 'Generation failed.');
        }
    };

    const handleApply = (value: string) => {
        onApply(value);

        close();
    };

    const panel = (
        <div
            className="z-50 mt-1 w-full rounded-md border bg-popover p-2 text-popover-foreground shadow-md"
            ref={panelRef}
        >
            <PropertyCopilotPopover
                error={error}
                generatedValue={generatedValue}
                hasValue={hasValue}
                onApply={handleApply}
                onGenerate={handleGenerate}
                pending={isPending}
            />
        </div>
    );

    return (
        <>
            <span className="inline-flex" ref={triggerRef}>
                <Button
                    aria-label="Ask copilot"
                    icon={<SparklesIcon />}
                    onClick={() => (open ? close() : setOpen(true))}
                    size="icon"
                    variant="ghost"
                />
            </span>

            {open &&
                (anchorRef?.current
                    ? createPortal(
                          <div className="absolute top-full -left-px w-[calc(100%+2px)]">{panel}</div>,
                          anchorRef.current
                      )
                    : panel)}
        </>
    );
};

export default PropertyCopilotButton;

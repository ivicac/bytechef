import {
    Dialog,
    DialogCloseButton,
    DialogContent,
    DialogDescription,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import useTranscriptDialog from '@/pages/platform/cluster-element-editor/ai-agent-evals/components/runs/hooks/useTranscriptDialog';
import {AlertCircleIcon, BotIcon, Loader2Icon, UserIcon} from 'lucide-react';
import {twMerge} from 'tailwind-merge';

interface TranscriptDialogProps {
    onClose: () => void;
    resultId: string;
    scenarioName: string;
}

const TranscriptDialog = ({onClose, resultId, scenarioName}: TranscriptDialogProps) => {
    const {error, groupedTurns, isLoading, transcriptData} = useTranscriptDialog(resultId);

    return (
        <Dialog onOpenChange={(open) => !open && onClose()} open={true}>
            <DialogContent className="max-h-[80vh] max-w-2xl overflow-y-auto">
                <DialogHeader className="flex flex-row items-center justify-between">
                    <div>
                        <DialogTitle>Conversation Transcript - {scenarioName}</DialogTitle>

                        <DialogDescription>Conversation transcript for this scenario result.</DialogDescription>
                    </div>

                    <DialogCloseButton />
                </DialogHeader>

                {isLoading && (
                    <div className="flex items-center justify-center py-8">
                        <Loader2Icon className="size-5 animate-spin text-content-neutral-tertiary" />

                        <span className="ml-2 text-sm text-content-neutral-secondary">Loading transcript...</span>
                    </div>
                )}

                {!!error && (
                    <div className="flex items-center gap-2 rounded-md border border-stroke-destructive-secondary bg-surface-destructive-secondary px-4 py-3">
                        <AlertCircleIcon className="size-4 text-content-destructive" />

                        <span className="text-sm text-content-destructive">Failed to load transcript.</span>
                    </div>
                )}

                {!isLoading && !error && !transcriptData && (
                    <div className="rounded-md border border-border/50 bg-surface-neutral-secondary/50 px-4 py-3">
                        <div className="text-sm text-content-neutral-secondary">No transcript data available.</div>
                    </div>
                )}

                {transcriptData && (
                    <div className="space-y-4">
                        {groupedTurns.map((turn) => (
                            <div className="space-y-2" key={turn.turnIndex}>
                                {groupedTurns.length > 1 && (
                                    <div className="text-xs font-medium text-content-neutral-tertiary">
                                        Turn {turn.turnIndex}
                                    </div>
                                )}

                                {turn.userMessage && (
                                    <div className="rounded-lg border border-stroke-brand-secondary/50 bg-surface-brand-secondary/50 px-3 py-2.5">
                                        <div className="mb-1.5 flex items-center gap-1.5">
                                            <UserIcon className="size-3.5 text-content-brand-primary" />

                                            <span className="text-xs font-semibold text-content-brand-primary">
                                                User
                                            </span>
                                        </div>

                                        <div className="text-sm whitespace-pre-wrap text-content-neutral-primary">
                                            {turn.userMessage.content}
                                        </div>
                                    </div>
                                )}

                                {turn.assistantMessage && (
                                    <div className="rounded-lg border border-stroke-neutral-secondary bg-surface-neutral-secondary/50 px-3 py-2.5">
                                        <div className="mb-1.5 flex items-center gap-1.5">
                                            <BotIcon className="size-3.5 text-content-neutral-secondary" />

                                            <span className="text-xs font-semibold text-content-neutral-primary">
                                                Assistant
                                            </span>
                                        </div>

                                        <div className="text-sm whitespace-pre-wrap text-content-neutral-primary">
                                            {turn.assistantMessage.content}
                                        </div>

                                        {turn.assistantMessage.toolCalls &&
                                            turn.assistantMessage.toolCalls.length > 0 && (
                                                <div className="mt-2 space-y-1.5">
                                                    {turn.assistantMessage.toolCalls.map((toolCall, toolCallIndex) => (
                                                        <details
                                                            className="rounded border border-stroke-neutral-secondary bg-surface-neutral-primary"
                                                            key={toolCallIndex}
                                                        >
                                                            <summary
                                                                className={twMerge(
                                                                    'cursor-pointer px-2.5 py-1.5 text-xs font-medium text-content-neutral-secondary',
                                                                    'hover:text-content-neutral-primary'
                                                                )}
                                                            >
                                                                Tool: {toolCall.name}
                                                            </summary>

                                                            <div className="space-y-1 border-t border-stroke-neutral-secondary px-2.5 py-2">
                                                                {toolCall.input && (
                                                                    <div>
                                                                        <div className="text-[10px] font-medium tracking-wide text-content-neutral-tertiary uppercase">
                                                                            Input
                                                                        </div>

                                                                        <pre className="mt-0.5 overflow-x-auto rounded bg-surface-neutral-secondary p-1.5 font-mono text-xs break-all whitespace-pre-wrap text-content-neutral-primary">
                                                                            {toolCall.input}
                                                                        </pre>
                                                                    </div>
                                                                )}

                                                                {toolCall.output && (
                                                                    <div>
                                                                        <div className="text-[10px] font-medium tracking-wide text-content-neutral-tertiary uppercase">
                                                                            Output
                                                                        </div>

                                                                        <pre className="mt-0.5 overflow-x-auto rounded bg-surface-neutral-secondary p-1.5 font-mono text-xs break-all whitespace-pre-wrap text-content-neutral-primary">
                                                                            {toolCall.output}
                                                                        </pre>
                                                                    </div>
                                                                )}
                                                            </div>
                                                        </details>
                                                    ))}
                                                </div>
                                            )}
                                    </div>
                                )}
                            </div>
                        ))}

                        {transcriptData.expectedOutput && (
                            <div className="rounded-lg border border-stroke-warning-secondary bg-surface-warning-secondary/50 px-3 py-2.5">
                                <div className="mb-1 text-xs font-semibold text-content-warning-primary">
                                    Expected Output
                                </div>

                                <div className="text-sm whitespace-pre-wrap text-content-neutral-primary">
                                    {transcriptData.expectedOutput}
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </DialogContent>
        </Dialog>
    );
};

export default TranscriptDialog;

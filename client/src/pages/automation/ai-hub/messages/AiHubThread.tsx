import {AiHubMessageComponents} from '@/pages/automation/ai-hub/messages/AiHubMessage';
import {ThreadPrimitive} from '@assistant-ui/react';
import {FC} from 'react';

const ThreadEmptyState: FC = () => (
    <div className="mx-auto flex w-full max-w-[var(--thread-max-width)] flex-1 flex-col items-center justify-center px-6 py-10 text-center">
        <p className="text-2xl font-semibold text-foreground">What should we get done?</p>

        <p className="mt-2 text-sm text-muted-foreground">
            Ask anything, mention files / workflows / data tables, or try a builder command.
        </p>
    </div>
);

const AiHubThread: FC = () => {
    return (
        <ThreadPrimitive.Root
            className="aui-cc-thread-root @container flex h-full flex-col"
            style={{['--thread-max-width' as string]: '44rem'}}
        >
            <ThreadPrimitive.Viewport className="aui-cc-thread-viewport relative flex flex-1 flex-col overflow-x-hidden overflow-y-auto px-1">
                <ThreadPrimitive.If empty>
                    <ThreadEmptyState />
                </ThreadPrimitive.If>

                <ThreadPrimitive.Messages components={AiHubMessageComponents} />

                <ThreadPrimitive.If empty={false}>
                    <div className="min-h-4 grow" />
                </ThreadPrimitive.If>
            </ThreadPrimitive.Viewport>
        </ThreadPrimitive.Root>
    );
};

export default AiHubThread;

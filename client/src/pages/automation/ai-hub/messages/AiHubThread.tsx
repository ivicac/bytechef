import {TooltipIconButton} from '@/components/assistant-ui/tooltip-icon-button';
import {Skeleton} from '@/components/ui/skeleton';
import {AiHubMessageComponents} from '@/pages/automation/ai-hub/messages/AiHubMessage';
import {useAiHubStore} from '@/pages/automation/ai-hub/stores/useAiHubStore';
import {ThreadPrimitive} from '@assistant-ui/react';
import {ArrowDownIcon} from 'lucide-react';
import {FC} from 'react';

// Shown while a task switch is fetching that task's history (messages are momentarily empty). A few
// message-shaped skeletons keep the switch reading as load→content instead of flashing the welcome state
// or jumping when the real messages pop in — especially noticeable when clicking task rows in quick
// succession.
const ThreadLoadingState: FC = () => (
    <div className="mx-auto flex w-full max-w-[var(--thread-max-width)] flex-1 flex-col gap-4 px-6 py-8">
        <Skeleton className="h-5 w-2/5 self-end rounded-2xl" />

        <Skeleton className="h-20 w-3/4 rounded-2xl" />

        <Skeleton className="h-5 w-1/4 self-end rounded-2xl" />

        <Skeleton className="h-16 w-2/3 rounded-2xl" />
    </div>
);

// Decide what an empty thread shows. `ThreadPrimitive.If empty` gates this on the assistant-ui runtime's
// projected message list, which lags `aiHubStore.messages` by one commit: `useExternalStoreRuntime` ingests
// new messages via `runtime.setAdapter(store)` inside a `useEffect`, so the runtime still reports "empty" for
// one frame after the store has already been populated. At the tail of a task switch that leaves a window
// where `messagesLoading` has flipped false but the runtime hasn't caught up — which would flash the welcome
// copy (identical to the home page). Treating "the store already has messages" as still-loading keeps the
// skeleton on screen for that frame instead. `messagesLoading` alone covers the front of the switch (store
// cleared to [], history in flight); `hasStoreMessages` covers the tail (history landed, runtime syncing).
export function shouldShowThreadLoadingState(messagesLoading: boolean, hasStoreMessages: boolean): boolean {
    return messagesLoading || hasStoreMessages;
}

// Empty thread: a loading placeholder while history is being fetched (or landing), otherwise the welcome
// prompt. Both only render when the runtime has zero messages; see shouldShowThreadLoadingState for how the
// "fetching" vs "new task" disambiguation is made without flashing the welcome mid-switch.
const ThreadEmptyState: FC = () => {
    const messagesLoading = useAiHubStore((state) => state.messagesLoading);
    const hasStoreMessages = useAiHubStore((state) => state.messages.length > 0);

    if (shouldShowThreadLoadingState(messagesLoading, hasStoreMessages)) {
        return <ThreadLoadingState />;
    }

    return (
        <div className="mx-auto flex w-full max-w-[var(--thread-max-width)] flex-1 flex-col items-center justify-center px-6 py-10 text-center">
            <p className="text-2xl font-semibold text-foreground">What should we get done?</p>

            <p className="mt-2 text-sm text-muted-foreground">
                Ask anything, mention files / workflows / data tables, or ask me to build a workflow.
            </p>
        </div>
    );
};

// Pinned to the bottom-center of the scroll viewport. assistant-ui disables the button (→ `invisible`
// via `disabled:invisible`) whenever the viewport is already at the bottom, so it only appears once the
// user scrolls up. Ported from the shared `components/assistant-ui/thread.tsx` so AI Hub matches the
// Copilot's scroll affordance instead of leaving the user with no way back to the latest message.
const ThreadScrollToBottom: FC = () => (
    <ThreadPrimitive.ScrollToBottom asChild>
        <TooltipIconButton
            className="aui-cc-thread-scroll-to-bottom sticky bottom-2 z-10 size-9 self-center rounded-full bg-background p-2 shadow-sm disabled:invisible dark:border-border dark:bg-background dark:hover:bg-accent"
            tooltip="Scroll to bottom"
            variant="outline"
        >
            <ArrowDownIcon className="size-4" />
        </TooltipIconButton>
    </ThreadPrimitive.ScrollToBottom>
);

const AiHubThread: FC = () => {
    return (
        <ThreadPrimitive.Root
            className="aui-cc-thread-root @container flex h-full flex-col"
            style={{['--thread-max-width' as string]: '44rem'}}
        >
            <ThreadPrimitive.Viewport className="aui-cc-thread-viewport relative mx-1 flex flex-1 flex-col overflow-x-hidden overflow-y-auto">
                <ThreadPrimitive.If empty>
                    <ThreadEmptyState />
                </ThreadPrimitive.If>

                <ThreadPrimitive.Messages components={AiHubMessageComponents} />

                <ThreadPrimitive.If empty={false}>
                    <div className="min-h-4 grow" />

                    <ThreadScrollToBottom />
                </ThreadPrimitive.If>
            </ThreadPrimitive.Viewport>
        </ThreadPrimitive.Root>
    );
};

export default AiHubThread;

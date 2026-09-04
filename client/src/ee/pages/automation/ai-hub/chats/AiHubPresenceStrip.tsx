import {Avatar, AvatarFallback} from '@/components/ui/avatar';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {useAiHubSharingEnabled} from '@/ee/pages/automation/ai-hub/chats/hooks/useAiHubSharingEnabled';
import {PresenceEntryI, ThreadStatusI} from '@/ee/pages/automation/ai-hub/runtime-providers/inFlightRunClient';

/**
 * Up to two letters, uppercased, one per space-separated word — "ana" -> "A", "bob smith" -> "BS". Falls
 * back to "?" for a blank name so a resolution gap never renders an empty avatar.
 */
function getInitials(userName: string): string {
    const initials = userName
        .split(' ')
        .filter(Boolean)
        .map((namePart) => namePart[0])
        .join('')
        .toUpperCase();

    return initials.slice(0, 2) || '?';
}

interface AiHubPresenceStripPropsI {
    // Compared against threadStatus.runningUserId to decide whether the running-turn line names someone
    // ELSE — the caller's own in-flight turn is already covered by the composer's Stop button, so this
    // strip stays silent about it.
    currentUserId: number | undefined;
    threadStatus: ThreadStatusI | undefined;
}

interface PresenceAvatarPropsI {
    entry: PresenceEntryI;
}

const PresenceAvatar = ({entry}: PresenceAvatarPropsI) => (
    <Tooltip>
        <TooltipTrigger asChild>
            <div className="relative" data-testid="presence-avatar">
                <Avatar className="size-6 ring-2 ring-background">
                    <AvatarFallback className="text-[10px]">{getInitials(entry.userName)}</AvatarFallback>
                </Avatar>

                {entry.state === 'TYPING' && (
                    <span
                        className="absolute -bottom-1 left-1/2 flex -translate-x-1/2 gap-0.5 rounded-full bg-background px-1"
                        data-testid="presence-typing-dots"
                    >
                        <span className="size-1 animate-bounce rounded-full bg-foreground [animation-delay:0ms]" />

                        <span className="size-1 animate-bounce rounded-full bg-foreground [animation-delay:150ms]" />

                        <span className="size-1 animate-bounce rounded-full bg-foreground [animation-delay:300ms]" />
                    </span>
                )}
            </div>
        </TooltipTrigger>

        <TooltipContent>{entry.userName}</TooltipContent>
    </Tooltip>
);

/**
 * Who's currently on this shared chat: one avatar per present viewer (initials from their name, bouncing
 * dots while that viewer's state is TYPING), plus — while another participant's turn is running — a line
 * naming them. Self-hides when there is nothing to show, so a chat nobody else has ever opened looks
 * exactly as it always has.
 *
 * <p>
 * Fed by {@code useAiHubChatsStore.threadStatus}, hydrated by the sidebar's {@code /status} poll (own
 * chat) and the focused-chat poll in {@code AiHubRuntimeProvider}. A {@code threadStatus} of {@code
 * undefined} means "not polled yet" OR "access lost / chat gone" — see that store field's doc — and this
 * component treats both the same way (renders nothing); telling the two apart is the caller's job
 * (AiHubPanel), not this presentational component's.
 * </p>
 *
 * <p>
 * Self-gated on {@code useAiHubSharingEnabled} rather than relying only on the caller to withhold
 * {@code threadStatus} — a flagged-off or CE caller renders nothing here regardless of what the prop
 * carries.
 * </p>
 */
const AiHubPresenceStrip = ({currentUserId, threadStatus}: AiHubPresenceStripPropsI) => {
    const sharingEnabled = useAiHubSharingEnabled();

    if (!sharingEnabled || !threadStatus) {
        return null;
    }

    const {inFlight, presence, runningUserId, runningUserName} = threadStatus;

    const showRunningLine = inFlight && runningUserId != null && runningUserId !== currentUserId;

    if (presence.length === 0 && !showRunningLine) {
        return null;
    }

    return (
        <div className="flex items-center gap-2 text-xs text-muted-foreground" data-testid="ai-hub-presence-strip">
            {presence.length > 0 && (
                <div className="flex items-center -space-x-2">
                    {presence.map((entry) => (
                        <PresenceAvatar entry={entry} key={entry.userId} />
                    ))}
                </div>
            )}

            {showRunningLine && (
                <span data-testid="presence-running-line">{runningUserName}&apos;s turn is running</span>
            )}
        </div>
    );
};

export default AiHubPresenceStrip;

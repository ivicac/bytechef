import Button from '@/components/Button/Button';
import Switch from '@/components/Switch/Switch';
import {
    Dialog,
    DialogCloseButton,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {type AiHubChatI, type ChatVisibilityType} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {AiHubChatsKeys} from '@/ee/pages/automation/ai-hub/chats/hooks/useChats';
import ResourceVisibilityPicker, {
    type ResourceVisibilityValueType,
} from '@/shared/components/visibility/ResourceVisibilityPicker';
import {
    type AiHubChatParticipation,
    type AiHubChatVisibility,
    useAiHubChatGrantsQuery,
    useGrantAiHubChatAccessMutation,
    useRevokeAiHubChatAccessMutation,
    useSetAiHubChatVisibilityMutation,
    useWorkspaceUsersQuery,
} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {Loader2Icon} from 'lucide-react';
import {useEffect, useMemo, useState} from 'react';

interface AiHubChatShareDialogPropsI {
    chat: AiHubChatI;
    onClose: () => void;
    open: boolean;
    workspaceId: number;
}

/** Order-independent set equality for the two id lists this dialog diffs on Save. */
function areSameIds(a: number[], b: number[]): boolean {
    if (a.length !== b.length) {
        return false;
    }

    const sortedA = [...a].sort((left, right) => left - right);
    const sortedB = [...b].sort((left, right) => left - right);

    return sortedA.every((id, index) => id === sortedB[index]);
}

/**
 * Owner-or-admin dialog for sharing an AI Hub chat: who can reach it (private / workspace / specific
 * people) and, once shared, whether they may only follow it or also send messages.
 *
 * `ResourceVisibilityPicker` speaks the platform's three-value `ResourceVisibilityValueType`, which also
 * carries `ORGANIZATION`. A chat's own `AiHubChatVisibility` is deliberately narrower — see
 * {@link ChatVisibilityType} — because a chat belongs to at most one workspace, so `ORGANIZATION` is not a
 * rung it can ever legally carry, and publishing it would advertise a value the server always rejects. Not
 * passing `showOrganizationOption` keeps the radio off the UI, but the picker's `onVisibilityChange` prop
 * type still requires a handler that can accept `ORGANIZATION` (contravariance on a typed prop). {@link
 * handleVisibilityChange} satisfies that type exactly while refusing to ever store the value, so no path
 * through this dialog can produce it — belt-and-suspenders against a future picker change that widens what
 * the UI itself offers.
 */
const AiHubChatShareDialog = ({chat, onClose, open, workspaceId}: AiHubChatShareDialogPropsI) => {
    const [visibility, setVisibility] = useState<ChatVisibilityType>(chat.visibility);
    const [participation, setParticipation] = useState(chat.participation === 'PARTICIPATE');
    const [grantedUserIds, setGrantedUserIds] = useState<number[]>([]);
    // The grants query result at seed time, held separately from the editable `grantedUserIds` so Save can
    // diff "what changed" into grant/revoke calls instead of re-sending the whole audience every time.
    const [seededUserIds, setSeededUserIds] = useState<number[]>([]);

    const queryClient = useQueryClient();

    const grantsQuery = useAiHubChatGrantsQuery(
        {chatId: String(chat.id), workspaceId: String(workspaceId)},
        {enabled: open}
    );
    const membersQuery = useWorkspaceUsersQuery({workspaceId: String(workspaceId)}, {enabled: open});

    const setVisibilityMutation = useSetAiHubChatVisibilityMutation();
    const grantAccessMutation = useGrantAiHubChatAccessMutation();
    const revokeAccessMutation = useRevokeAiHubChatAccessMutation();

    // Memoized on the query result rather than rebuilt per render: ResourceVisibilityPicker takes this as a
    // prop, and a fresh array on every render would defeat any memoization on its side of the boundary.
    const members = useMemo(
        () =>
            (membersQuery.data?.workspaceUsers ?? []).map((workspaceUser) => ({
                label: workspaceUser.user?.email ?? `User ${workspaceUser.userId}`,
                userId: Number(workspaceUser.userId),
            })),
        [membersQuery.data]
    );

    // Both queries feed the SAME control (the picker's radio state is derived from the chat's visibility AND
    // the grant list, so a half-loaded dialog would show "Private" for a chat shared with three people and
    // then snap), so they gate the body together rather than each rendering its own spinner. Save is withheld
    // for the same reason: a Save issued before the grants land diffs against an empty seed list.
    const loadingAudience = grantsQuery.isPending || membersQuery.isPending;

    // Naming people is meaningless while the chat has no audience at all, matching
    // ResourceVisibilityPicker's own "Specific people" derivation.
    const participationDisabled = visibility === 'PRIVATE' && grantedUserIds.length === 0;

    const handleVisibilityChange = (nextVisibility: ResourceVisibilityValueType) => {
        if (nextVisibility === 'ORGANIZATION') {
            return;
        }

        setVisibility(nextVisibility);
    };

    const handleSave = () => {
        setVisibilityMutation.mutate({
            chatId: String(chat.id),
            // The picker/switch speak this dialog's own plain-string ChatVisibilityType /
            // ChatParticipationType; the generated enums' values are exactly those strings, so these are
            // representation casts rather than a claim about the value (mirrors useProjectVisibility's
            // ResourceVisibility cast).
            participation: (participation ? 'PARTICIPATE' : 'VIEW') as AiHubChatParticipation,
            visibility: visibility as AiHubChatVisibility,
            workspaceId: String(workspaceId),
        });

        grantedUserIds
            .filter((userId) => !seededUserIds.includes(userId))
            .forEach((userId) =>
                grantAccessMutation.mutate({
                    chatId: String(chat.id),
                    userId: String(userId),
                    workspaceId: String(workspaceId),
                })
            );

        seededUserIds
            .filter((userId) => !grantedUserIds.includes(userId))
            .forEach((userId) =>
                revokeAccessMutation.mutate({
                    chatId: String(chat.id),
                    userId: String(userId),
                    workspaceId: String(workspaceId),
                })
            );

        queryClient.invalidateQueries({queryKey: AiHubChatsKeys.all});
        queryClient.invalidateQueries({queryKey: AiHubChatsKeys.sharedAll});

        onClose();
    };

    // Seed from the chat the dialog was opened on. Keyed off `chat`/`open` rather than run once, so
    // reopening on a different chat (or reopening the same one after an earlier edit) re-seeds instead of
    // keeping whatever the previous session left behind.
    useEffect(() => {
        if (!open) {
            return;
        }

        setVisibility(chat.visibility);
        setParticipation(chat.participation === 'PARTICIPATE');
    }, [chat, open]);

    // Seed the editable grant list from the query separately, since it resolves asynchronously after the
    // dialog is already showing the visibility/participation state above.
    //
    // Guarded by content, not just presence: react-query normally hands back the SAME `data` reference
    // across renders until a real refetch changes it, so depending on `grantsQuery.data` alone is fine in
    // production. But nothing here can rely on that memoization holding forever — and a mocked hook in a
    // test that rebuilds its return object every call (a real bug this dialog's own test once had) breaks
    // it today. Without this guard, a `data` object that changes identity on every render re-runs the
    // effect every render, and `.map(Number)` builds a NEW array every time even when the underlying ids
    // are unchanged — so `setGrantedUserIds`/`setSeededUserIds` would "change" state on every render,
    // forcing another render, forever. The functional updater below only replaces state (and only then
    // triggers a re-render) when the ids actually differ; returning the previous array reference when they
    // don't is what breaks the loop, regardless of whether `grantsQuery.data` itself is stable.
    useEffect(() => {
        if (!grantsQuery.data) {
            return;
        }

        const ids = grantsQuery.data.aiHubChatGrants.map(Number);

        setSeededUserIds((previousIds) => (areSameIds(previousIds, ids) ? previousIds : ids));
        setGrantedUserIds((previousIds) => (areSameIds(previousIds, ids) ? previousIds : ids));
    }, [grantsQuery.data]);

    return (
        <Dialog onOpenChange={(nextOpen) => !nextOpen && onClose()} open={open}>
            <DialogContent>
                <DialogHeader className="flex flex-row items-center justify-between space-y-0">
                    <div className="flex flex-col space-y-1">
                        <DialogTitle>Share chat</DialogTitle>

                        <DialogDescription>
                            Decide who in the workspace can see this chat, and whether they can send messages.
                        </DialogDescription>
                    </div>

                    <DialogCloseButton />
                </DialogHeader>

                {loadingAudience ? (
                    <div
                        className="flex items-center gap-2 py-6 text-sm text-muted-foreground"
                        data-testid="share-dialog-loading"
                        role="status"
                    >
                        <Loader2Icon aria-hidden className="size-4 animate-spin" />

                        <span>Loading who has access…</span>
                    </div>
                ) : (
                    <>
                        <ResourceVisibilityPicker
                            grantedUserIds={grantedUserIds}
                            onGrantedUserIdsChange={setGrantedUserIds}
                            onVisibilityChange={handleVisibilityChange}
                            showSpecificPeopleOption
                            visibility={visibility}
                            workspaceMembers={members}
                        />

                        <Switch
                            checked={participation}
                            disabled={participationDisabled}
                            label="People with access can send messages"
                            onCheckedChange={setParticipation}
                        />
                    </>
                )}

                <DialogFooter>
                    <Button label="Cancel" onClick={onClose} variant="outline" />

                    <Button disabled={loadingAudience} label="Save" onClick={handleSave} />
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default AiHubChatShareDialog;

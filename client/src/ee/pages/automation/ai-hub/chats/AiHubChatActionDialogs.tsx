import AlertDialog from '@/components/AlertDialog';
import Button from '@/components/Button/Button';
import {
    Dialog,
    DialogBody,
    DialogCancelButton,
    DialogContent,
    DialogFooter,
    DialogHeader,
    DialogMain,
} from '@/components/Dialog';
import {Input} from '@/components/ui/input';
import AiHubChatShareDialog from '@/ee/pages/automation/ai-hub/chats/AiHubChatShareDialog';
import {getChatDisplayTitle} from '@/ee/pages/automation/ai-hub/chats/api/chats.api';
import {type AiHubChatActionsI} from '@/ee/pages/automation/ai-hub/chats/hooks/useAiHubChatActions';
import {useEffect, useState} from 'react';

type AiHubChatActionDialogsPropsType = Pick<
    AiHubChatActionsI,
    | 'cancelDelete'
    | 'cancelRename'
    | 'cancelShare'
    | 'confirmDelete'
    | 'deleteTarget'
    | 'renameTarget'
    | 'shareTarget'
    | 'submitRename'
> & {
    workspaceId: number;
};

/**
 * The confirm step for the two chat actions that need one. Rendered by whichever surface owns a
 * useAiHubChatActions instance — the chats sidebar and the chat header each render their own, so a menu
 * can open a dialog without the other surface having to be mounted.
 *
 * Rename is a dialog rather than the row-inline input it replaced: the header's menu has no row to edit
 * in place, and one confirm step that works from anywhere beats two rename UIs that can drift apart.
 */
const AiHubChatActionDialogs = ({
    cancelDelete,
    cancelRename,
    cancelShare,
    confirmDelete,
    deleteTarget,
    renameTarget,
    shareTarget,
    submitRename,
    workspaceId,
}: AiHubChatActionDialogsPropsType) => {
    const [titleValue, setTitleValue] = useState('');

    // Seed the field from whichever chat the menu just targeted. Keyed off the chat rather than the open
    // flag so reopening on a different chat re-seeds instead of keeping the previous chat's text.
    useEffect(() => {
        setTitleValue(renameTarget?.title ?? '');
    }, [renameTarget]);

    return (
        <>
            <Dialog onOpenChange={(nextOpen) => !nextOpen && cancelRename()} open={renameTarget !== null}>
                <DialogContent>
                    <DialogMain>
                        <DialogHeader title="Rename chat" />

                        <DialogBody>
                            <Input
                                autoFocus
                                onChange={(event) => setTitleValue(event.target.value)}
                                onKeyDown={(event) => {
                                    if (event.key === 'Enter') {
                                        submitRename(titleValue);
                                    }
                                }}
                                value={titleValue}
                            />
                        </DialogBody>

                        <DialogFooter>
                            <DialogCancelButton />

                            <Button label="Rename" onClick={() => submitRename(titleValue)} />
                        </DialogFooter>
                    </DialogMain>
                </DialogContent>
            </Dialog>

            <AlertDialog
                description={
                    deleteTarget &&
                    `"${getChatDisplayTitle(deleteTarget)}" will be permanently deleted. This cannot be undone.`
                }
                onCancel={cancelDelete}
                onConfirm={confirmDelete}
                open={deleteTarget !== null}
                title="Delete this chat?"
            />

            {/* Conditionally mounted rather than always-mounted-with-toggled-open like the two dialogs
             * above: AiHubChatShareDialog requires a non-null chat, and its own grants/members queries
             * only need to run for the lifetime of an actual share attempt. */}

            {shareTarget && (
                <AiHubChatShareDialog chat={shareTarget} onClose={cancelShare} open={true} workspaceId={workspaceId} />
            )}
        </>
    );
};

export default AiHubChatActionDialogs;

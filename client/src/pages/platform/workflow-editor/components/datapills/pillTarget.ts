import {Editor} from '@tiptap/react';

/**
 * Where a data pill lands when one is clicked in the Data Pill Panel: whichever property field registered
 * last. A field registers on focus and stays registered after blur, because clicking the panel itself blurs it.
 * `owner` identifies the registrant so an unmounting field clears only its own registration.
 */
export interface PillTargetI {
    acceptsPill: () => boolean;
    insertPill: (mentionId: string) => void;
    owner: unknown;
}

interface CreateEditorPillTargetPropsI {
    acceptsPill: () => boolean;
    editor: Editor;
    singlePill?: boolean;
}

export function createEditorPillTarget({
    acceptsPill,
    editor,
    singlePill = false,
}: CreateEditorPillTargetPropsI): PillTargetI {
    return {
        acceptsPill,
        insertPill: (mentionId) => {
            const mention = {attrs: {id: mentionId}, type: 'mention'};

            if (singlePill) {
                // One transaction: selecting everything and inserting over it replaces the pill without an
                // intermediate empty document, which would read as "pill deleted" and swap the native control back.
                editor.chain().focus().selectAll().insertContent(mention).run();

                return;
            }

            editor.chain().focus().insertContent(mention).run();
        },
        owner: editor,
    };
}

import {PillTargetI} from '@/pages/platform/workflow-editor/components/datapills/pillTarget';
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import {DataPillDragPayloadType} from '@/shared/types';
import {DragEvent, FocusEvent, SyntheticEvent, useCallback, useEffect, useRef} from 'react';
import {useShallow} from 'zustand/react/shallow';

interface UsePillTargetPropsI {
    acceptsPill: () => boolean;
    insertPill: (mentionId: string) => void;
}

const DATA_PILL_MIME_TYPE = 'application/bytechef-datapill';

/**
 * Marks an element that registers itself as a pill target. An ancestor target ignores focus and drops that originate
 * inside a nearer one, so a field nested in an object or array never has its registration overwritten by a container.
 */
export const PILL_TARGET_ATTRIBUTE = 'data-pill-target';

function originatesInOwnTarget(event: SyntheticEvent<HTMLElement>): boolean {
    const {currentTarget, target} = event;

    if (!(target instanceof Element)) {
        return true;
    }

    const nearestPillTarget = target.closest(`[${PILL_TARGET_ATTRIBUTE}]`);

    // No marked ancestor means the event came through a portal (a popover's input); React still routes it here.
    return !nearestPillTarget || nearestPillTarget === currentTarget;
}

/**
 * Makes a native property control a data pill target: a focus inside the wrapper registers it, so the pill panel
 * inserts here rather than into whichever editor was focused before, and a dropped pill replaces the value. Spread
 * `targetProps` onto the wrapper element.
 */
export default function usePillTarget({acceptsPill, insertPill}: UsePillTargetPropsI) {
    const acceptsPillRef = useRef(acceptsPill);
    const insertPillRef = useRef(insertPill);
    const ownerTokenRef = useRef<object>({});

    acceptsPillRef.current = acceptsPill;
    insertPillRef.current = insertPill;

    const {clearPillTarget, setPillTarget} = useWorkflowNodeDetailsPanelStore(
        useShallow((state) => ({
            clearPillTarget: state.clearPillTarget,
            setPillTarget: state.setPillTarget,
        }))
    );

    const onFocusCapture = useCallback(
        (event: FocusEvent<HTMLElement>) => {
            if (!originatesInOwnTarget(event)) {
                return;
            }

            const ownerToken = ownerTokenRef.current;

            // The registered callbacks read through refs, so an existing registration of this field is never stale.
            if (useWorkflowNodeDetailsPanelStore.getState().pillTarget?.owner === ownerToken) {
                return;
            }

            const pillTarget: PillTargetI = {
                acceptsPill: () => acceptsPillRef.current(),
                insertPill: (mentionId) => insertPillRef.current(mentionId),
                owner: ownerToken,
            };

            setPillTarget(pillTarget);
        },
        [setPillTarget]
    );

    const onDragOver = useCallback((event: DragEvent<HTMLElement>) => {
        if (event.defaultPrevented || !originatesInOwnTarget(event)) {
            return;
        }

        if (event.dataTransfer.types.includes(DATA_PILL_MIME_TYPE) && acceptsPillRef.current()) {
            event.preventDefault();

            event.dataTransfer.dropEffect = 'copy';
        }
    }, []);

    const onDrop = useCallback((event: DragEvent<HTMLElement>) => {
        if (event.defaultPrevented || !originatesInOwnTarget(event)) {
            return;
        }

        const rawPayload = event.dataTransfer.getData(DATA_PILL_MIME_TYPE);

        if (!rawPayload || !acceptsPillRef.current()) {
            return;
        }

        event.preventDefault();
        event.stopPropagation();

        try {
            const payload = JSON.parse(rawPayload) as DataPillDragPayloadType;

            if (payload?.mentionId) {
                insertPillRef.current(payload.mentionId);
            }
        } catch {
            // A malformed payload is not a data pill; the drop is simply ignored.
            return;
        }
    }, []);

    useEffect(() => {
        const ownerToken = ownerTokenRef.current;

        return () => clearPillTarget(ownerToken);
    }, [clearPillTarget]);

    return {
        targetProps: {
            [PILL_TARGET_ATTRIBUTE]: '',
            onDragOver,
            onDrop,
            onFocusCapture,
        },
    };
}

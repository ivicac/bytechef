import {useEffect, useRef} from 'react';

/**
 * Calls `save` `delayMs` after the latest change to `value`, skipping the initial mount and any value equal to
 * the last one saved. The comparison is by JSON, since every caller hands over a freshly built object.
 *
 * `lastSavedRef` is updated the moment a save is SCHEDULED, not the moment it actually FIRES. A value change
 * that gets superseded before its timeout runs still clears its own `setTimeout` (the cleanup below), but
 * would otherwise leave `lastSavedRef` holding a stale baseline — the value the hook was seeded with, or
 * whatever the last save that actually reached its timeout wrote. If the caller's value is then changed BACK
 * to that stale baseline within the debounce window (e.g. a toggle flipped and flipped back), the comparison
 * above would read "no change" and silently drop a save that never actually happened, leaving the caller's
 * real state disagreeing with the server indefinitely. Updating the ref at schedule time instead means every
 * value this hook has ever been asked to persist is immediately treated as "the current baseline", so a
 * later revert to an EARLIER value it never actually got to save is correctly seen as a change again and
 * re-schedules its own save. The cost is a save that is skipped-then-reintroduced can very occasionally
 * result in an extra, otherwise-redundant network call for a value the server already holds — harmless,
 * since these mutations are idempotent whole-value replaces, not increments.
 */
export default function useDebouncedSave<T>(value: T, save: (value: T) => void, delayMs = 600) {
    const lastSavedRef = useRef<string>(JSON.stringify(value));
    const saveRef = useRef(save);

    saveRef.current = save;

    useEffect(() => {
        const serialized = JSON.stringify(value);

        if (serialized === lastSavedRef.current) {
            return;
        }

        lastSavedRef.current = serialized;

        const timeout = setTimeout(() => {
            saveRef.current(value);
        }, delayMs);

        return () => clearTimeout(timeout);
    }, [delayMs, value]);
}

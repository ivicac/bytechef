import {useEffect, useRef} from 'react';

/**
 * Calls `save` `delayMs` after the latest change to `value`, skipping the initial mount and any value equal to
 * the last one saved. The comparison is by JSON, since every caller hands over a freshly built object.
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

        const timeout = setTimeout(() => {
            lastSavedRef.current = serialized;

            saveRef.current(value);
        }, delayMs);

        return () => clearTimeout(timeout);
    }, [delayMs, value]);
}

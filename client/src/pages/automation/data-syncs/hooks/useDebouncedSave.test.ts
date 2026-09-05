import {renderHook} from '@testing-library/react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import useDebouncedSave from './useDebouncedSave';

describe('useDebouncedSave', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('skips a value equal to the last one actually saved', () => {
        const save = vi.fn();

        const {rerender} = renderHook(({value}) => useDebouncedSave(value, save, 600), {
            initialProps: {value: {name: 'seeded'}},
        });

        // Same content, fresh reference — should not schedule anything, mount or otherwise.
        rerender({value: {name: 'seeded'}});

        vi.advanceTimersByTime(600);

        expect(save).not.toHaveBeenCalled();
    });

    it('still saves when an identical-JSON new object arrives before the timeout elapses', () => {
        // Regression test for the fix wave that briefly wrote this hook's baseline at SCHEDULE time instead of
        // FIRE time. A caller that rebuilds its value as a fresh object literal every render —
        // useDataSyncElementStep does this deliberately, see its own doc comment on `candidateValuesToSave` —
        // re-runs this hook's effect on every render, including one triggered by something unrelated (a
        // settling mutation flipping `isPending`, say) that hands over a brand-new object with the SAME JSON
        // content as the value already pending save. Advancing the baseline at schedule time made that re-run's
        // "no real change" comparison collapse against the not-yet-fired pending value; the effect's
        // unconditional cleanup then cleared the pending timeout, and the edit was lost with nothing left to
        // fire it. The fire-time baseline (this hook's current, reverted behaviour) never advances until a save
        // actually reaches its timeout, so the re-run's serialized value still differs from the still-unfired
        // baseline and the effect correctly reschedules instead of dropping the save.
        const save = vi.fn();

        const {rerender} = renderHook(({value}) => useDebouncedSave(value, save, 600), {
            initialProps: {value: {name: 'first'}},
        });

        // The user's real edit: a new value, distinct from the seeded baseline.
        rerender({value: {name: 'second'}});

        vi.advanceTimersByTime(300);

        // Well within the 600ms window, an unrelated re-render hands over a FRESH object (new reference) whose
        // JSON is identical to the value already pending — exactly what useDataSyncElementStep's own
        // deliberately-non-memoized `valuesToSave` produces when a settling mutation re-renders the step.
        rerender({value: {name: 'second'}});

        vi.advanceTimersByTime(600);

        expect(save).toHaveBeenCalledTimes(1);
        expect(save).toHaveBeenCalledWith({name: 'second'});
    });

    it('cancels a pending save when the value reverts before the timeout elapses', () => {
        const save = vi.fn();

        const {rerender} = renderHook(({value}) => useDebouncedSave(value, save, 600), {
            initialProps: {value: {name: 'first'}},
        });

        rerender({value: {name: 'second'}});

        vi.advanceTimersByTime(300);

        rerender({value: {name: 'first'}});

        vi.advanceTimersByTime(600);

        expect(save).not.toHaveBeenCalled();
    });
});

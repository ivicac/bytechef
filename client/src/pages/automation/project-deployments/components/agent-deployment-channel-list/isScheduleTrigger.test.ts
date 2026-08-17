import {describe, expect, it} from 'vitest';

import isScheduleTrigger from './isScheduleTrigger';

describe('isScheduleTrigger', () => {
    it('matches the schedule component trigger type', () => {
        expect(isScheduleTrigger({type: 'schedule/v1/cron'})).toBe(true);
    });

    it('does not match a non-schedule trigger type', () => {
        expect(isScheduleTrigger({type: 'chat/v1/newChatRequest'})).toBe(false);
    });

    // The predicate splits on the component segment, so a component whose name merely starts with or contains
    // "schedule" must never be mistaken for the schedule channel itself.
    it('does not match a trigger type whose component only resembles "schedule"', () => {
        expect(isScheduleTrigger({type: 'scheduler/v1/cron'})).toBe(false);
    });
});

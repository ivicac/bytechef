import {describe, expect, it} from 'vitest';

import computeBranchCaseLabelPosition from './computeBranchCaseLabelPosition';

describe('computeBranchCaseLabelPosition', () => {
    const defaultCoords = {sourceX: 100, sourceY: 200, targetX: 300, targetY: 400};

    describe('LR layout', () => {
        it('should anchor on the row line 44px past the split bar, before the edge add-button', () => {
            const result = computeBranchCaseLabelPosition({
                ...defaultCoords,
                layoutDirection: 'LR',
            });

            expect(result).toEqual({x: 144, y: 400});
        });

        it('should lift the chip above the icon band when the row shares the dispatcher axis', () => {
            const result = computeBranchCaseLabelPosition({
                layoutDirection: 'LR',
                sourceX: 100,
                sourceY: 200,
                targetX: 300,
                targetY: 210,
            });

            expect(result).toEqual({x: 144, y: 140});
        });
    });

    describe('TB layout', () => {
        it('should position at (targetX, sourceY + offset)', () => {
            const result = computeBranchCaseLabelPosition({
                ...defaultCoords,
                layoutDirection: 'TB',
            });

            expect(result).toEqual({x: 300, y: 210});
        });
    });
});

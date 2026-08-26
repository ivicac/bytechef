import {LR_NODE_LABEL_BLOCK_HEIGHT} from '@/shared/constants';
import {describe, expect, it} from 'vitest';

import {
    GRAPH_FRAME_HEADER_HEIGHT,
    GRAPH_FRAME_MIN_HEIGHT,
    GRAPH_FRAME_MIN_WIDTH,
    GRAPH_FRAME_PADDING,
    GRAPH_LOOP_GUTTER,
    GRAPH_MEMBER_BOX_WIDTH,
    GRAPH_MEMBER_LAYER_SPACING,
    GRAPH_MEMBER_SPACING,
    autoPlaceGraphMembers,
    computeGraphFrameSize,
    findFreeSpot,
    fromFrameChildPosition,
    getGraphStartPinnedBox,
    separateOverlappingGraphMembers,
    toFrameChildPosition,
} from './graphFrameGeometry';

describe('graphFrameGeometry', () => {
    describe('toFrameChildPosition / fromFrameChildPosition', () => {
        it('round-trips a position through the header offset', () => {
            const position = {x: 50, y: 20};

            expect(toFrameChildPosition(position)).toEqual({x: 50, y: 20 + GRAPH_FRAME_HEADER_HEIGHT});
            expect(fromFrameChildPosition(toFrameChildPosition(position))).toEqual(position);
        });
    });

    describe('computeGraphFrameSize', () => {
        it('pads the union of member boxes and honours minimums', () => {
            expect(computeGraphFrameSize([])).toEqual({height: GRAPH_FRAME_MIN_HEIGHT, width: GRAPH_FRAME_MIN_WIDTH});
            expect(computeGraphFrameSize([{height: 100, name: 'a', width: 200, x: 500, y: 300}])).toEqual({
                height: 300 + 100 + GRAPH_FRAME_HEADER_HEIGHT + GRAPH_FRAME_PADDING,
                // Mirrors the 500 inset on the right, so the member sits centred.
                width: 500 + 200 + 500,
            });
        });

        it('mirrors the leftmost inset on the right so the member block is centred', () => {
            const size = computeGraphFrameSize([
                {height: 50, name: 'a', width: 100, x: 200, y: 0},
                {height: 50, name: 'b', width: 100, x: 400, y: 0},
            ]);

            const leftGap = 200;
            const rightGap = size.width - 500;

            expect(rightGap).toBe(leftGap);
        });

        // The outer engine centres the whole frame, header included, on the chain — so in LR the
        // block's vertical centre has to be the frame's, or the chain enters above or below it.
        it('centres the member block on the frame height in LR', () => {
            const size = computeGraphFrameSize(
                [
                    {height: 72, name: 'a', width: 72, x: 120, y: GRAPH_LOOP_GUTTER},
                    {height: 72, name: 'b', width: 72, x: 272, y: GRAPH_LOOP_GUTTER + 40},
                ],
                'LR'
            );

            const blockCentre = GRAPH_FRAME_HEADER_HEIGHT + (GRAPH_LOOP_GUTTER + GRAPH_LOOP_GUTTER + 40 + 72) / 2;

            expect(size.height / 2).toBe(blockCentre);
        });

        it('falls back to the loop gutter when a member sits nearer the edge than that', () => {
            const size = computeGraphFrameSize([{height: 50, name: 'a', width: 100, x: 0, y: 0}]);

            expect(size.width).toBe(Math.max(GRAPH_FRAME_MIN_WIDTH, 100 + GRAPH_LOOP_GUTTER));
        });

        it('takes the union across several member boxes', () => {
            const size = computeGraphFrameSize([
                {height: 50, name: 'a', width: 100, x: 0, y: 0},
                {height: 50, name: 'b', width: 100, x: 400, y: 10},
            ]);

            expect(size).toEqual({
                height: Math.max(GRAPH_FRAME_MIN_HEIGHT, GRAPH_FRAME_HEADER_HEIGHT + 60 + GRAPH_FRAME_PADDING),
                // A member flush against the leading edge still gets the loop gutter mirrored.
                width: Math.max(GRAPH_FRAME_MIN_WIDTH, 500 + GRAPH_LOOP_GUTTER),
            });
        });
    });

    describe('findFreeSpot', () => {
        // The first member is the layout's to place: its auto-placement centres it on the chain.
        it('leaves an empty frame to auto-placement', () => {
            expect(findFreeSpot([], 'TB')).toBeUndefined();
            expect(findFreeSpot([], 'LR')).toBeUndefined();
        });

        it('places one layer below the bottom-most member, in its column, in TB', () => {
            const spot = findFreeSpot(
                [
                    {height: 72, name: 'a', width: 240, x: 100, y: 0},
                    {height: 72, name: 'b', width: 240, x: 100, y: 150},
                    {height: 72, name: 'c', width: 240, x: 400, y: 0},
                ],
                'TB'
            );

            expect(spot).toEqual({x: 100, y: 150 + 72 + GRAPH_MEMBER_LAYER_SPACING});
        });

        it('places one layer right of the right-most painted box, in its row, in LR', () => {
            const spot = findFreeSpot(
                [
                    {height: 72, name: 'a', paintedWidth: 72, width: 240, x: 120, y: 72},
                    {height: 72, name: 'b', paintedWidth: 72, width: 240, x: 272, y: 72},
                    {height: 72, name: 'c', paintedWidth: 72, width: 240, x: 120, y: 300},
                ],
                'LR'
            );

            expect(spot).toEqual({x: 272 + 72 + GRAPH_MEMBER_LAYER_SPACING, y: 72});
        });
    });

    describe('autoPlaceGraphMembers', () => {
        // In LR a plain task's three label lines hang BELOW its box, straight into whatever sibling
        // shares its layer — so the gap between two boxes in one column has to hold a label too.
        //
        // Both ways ELK stacks members: siblings sharing a layer, and members no transition joins
        // yet, which it packs as separate components under a spacing of their own.
        it.each([
            [
                'sharing a layer',
                [
                    {from: 'a', to: 'c'},
                    {from: 'b', to: 'c'},
                ],
            ],
            ['not connected yet', []],
        ])('leaves room for the label under each box between LR members %s', async (_case, transitions) => {
            const positions = await autoPlaceGraphMembers(
                [
                    {height: 72, name: 'a', width: 72},
                    {height: 72, name: 'b', width: 72},
                    {height: 72, name: 'c', width: 72},
                ],
                transitions,
                [],
                'LR',
                'a'
            );

            const [upper, lower] = [positions.a, positions.b].sort((first, second) => first.y - second.y);

            expect(upper.x).toBe(lower.x);
            expect(lower.y - (upper.y + 72)).toBeGreaterThanOrEqual(LR_NODE_LABEL_BLOCK_HEIGHT + GRAPH_MEMBER_SPACING);
        });

        it('lays a chain down the flow axis below pinned members in TB', async () => {
            const positions = await autoPlaceGraphMembers(
                [
                    {height: 100, name: 'a', width: 200},
                    {height: 100, name: 'b', width: 200},
                ],
                [{from: 'a', to: 'b'}],
                [{height: 100, name: 'p', width: 200, x: 0, y: 0}]
            );

            expect(positions.a.y).toBeLessThan(positions.b.y);
            expect(positions.a.y).toBeGreaterThanOrEqual(100 + GRAPH_FRAME_PADDING);
        });

        it('lays a chain left-to-right past pinned members in LR', async () => {
            const positions = await autoPlaceGraphMembers(
                [
                    {height: 100, name: 'a', width: 200},
                    {height: 100, name: 'b', width: 200},
                ],
                [{from: 'a', to: 'b'}],
                [{height: 100, name: 'p', width: 200, x: 0, y: 0}],
                'LR'
            );

            expect(positions.a.x).toBeLessThan(positions.b.x);
            expect(positions.a.x).toBeGreaterThanOrEqual(200 + GRAPH_FRAME_PADDING);
        });

        it('offsets results by exactly the pinned-bottom difference between pinned states', async () => {
            const memberSizes = [{height: 100, name: 'a', width: 200}];

            const unpinnedPositions = await autoPlaceGraphMembers(memberSizes, [], []);
            const pinnedPositions = await autoPlaceGraphMembers(
                memberSizes,
                [],
                [{height: 100, name: 'p', width: 200, x: 0, y: 0}]
            );

            // ELK may add its own internal padding to an unconnected single node's raw y, so
            // this compares the two calls rather than asserting an absolute y — only the
            // DIFFERENCE (driven by pinnedBottom) is this module's own contract.
            expect(pinnedPositions.a.y - unpinnedPositions.a.y).toBe(100);
        });

        it('skips edges with a dynamic target and edges to unknown members', async () => {
            const positions = await autoPlaceGraphMembers(
                [
                    {height: 100, name: 'a', width: 200},
                    {height: 100, name: 'b', width: 200},
                ],
                [
                    {from: 'a', to: '=nextNode'},
                    {from: 'a', to: 'missing'},
                ],
                []
            );

            expect(Object.keys(positions).sort()).toEqual(['a', 'b']);
        });

        // The bug this pins: a member's label is measured with its node but hangs off the right of
        // the box, so a block auto-placed at a fixed gutter made the frame grow to fit the labels and
        // left everything visible sitting well left of the chain edge entering the frame at its
        // centre. Auto-place and frame sizing are two halves of one contract, so this exercises both.
        it.each([
            ['a long label', 168],
            ['no label overhang', 0],
        ])('centres the painted block in the frame it produces (%s)', async (_case, labelOverhang) => {
            const memberSizes = [
                {height: 72, labelOverhang, name: 'a', width: GRAPH_MEMBER_BOX_WIDTH},
                {height: 72, labelOverhang, name: 'b', width: GRAPH_MEMBER_BOX_WIDTH},
                {height: 72, labelOverhang, name: 'c', width: GRAPH_MEMBER_BOX_WIDTH},
            ];

            const positions = await autoPlaceGraphMembers(
                memberSizes,
                [
                    {from: 'a', to: 'b'},
                    {from: 'a', to: 'c'},
                ],
                [getGraphStartPinnedBox()]
            );

            const memberBoxes = memberSizes.map((memberSize) => ({
                height: memberSize.height,
                name: memberSize.name,
                paintedWidth: memberSize.width,
                width: memberSize.width + memberSize.labelOverhang,
                x: positions[memberSize.name].x,
                y: positions[memberSize.name].y,
            }));

            const size = computeGraphFrameSize(memberBoxes);

            const paintedLeft = Math.min(...memberBoxes.map((memberBox) => memberBox.x));
            const paintedRight = Math.max(...memberBoxes.map((memberBox) => memberBox.x + memberBox.paintedWidth));

            expect((paintedLeft + paintedRight) / 2).toBe(size.width / 2);

            // And the labels still fit inside the frame rather than being cut off by its border.
            expect(Math.max(...memberBoxes.map((memberBox) => memberBox.x + memberBox.width))).toBeLessThanOrEqual(
                size.width
            );
        });
    });

    describe('separateOverlappingGraphMembers', () => {
        it('leaves members that do not overlap exactly where they are', () => {
            const memberBoxes = [
                {height: 100, name: 'first', width: 240, x: 0, y: 0},
                {height: 100, name: 'second', width: 240, x: 0, y: 300},
            ];

            expect(separateOverlappingGraphMembers(memberBoxes, 'TB')).toEqual(memberBoxes);
        });

        it('pushes a member out from under one that has grown over it', () => {
            const settled = separateOverlappingGraphMembers(
                [
                    {height: 800, name: 'aiAgent_2', width: 900, x: 100, y: 0},
                    {height: 100, name: 'activeCampaign_3', paintedWidth: 72, width: 240, x: 300, y: 340},
                    {height: 100, name: 'accelo_1', paintedWidth: 72, width: 240, x: 300, y: 560},
                ],
                'TB'
            );

            expect(settled.find((memberBox) => memberBox.name === 'aiAgent_2')).toMatchObject({x: 100, y: 0});
            expect(settled.find((memberBox) => memberBox.name === 'activeCampaign_3')).toMatchObject({
                x: 300,
                y: 800 + GRAPH_MEMBER_SPACING,
            });
            expect(settled.find((memberBox) => memberBox.name === 'accelo_1')).toMatchObject({
                x: 300,
                y: 800 + GRAPH_MEMBER_SPACING + 100 + GRAPH_MEMBER_SPACING,
            });
        });

        it('measures painted boxes, not the labels hanging off them', () => {
            const memberBoxes = [
                {height: 100, name: 'task_0', paintedWidth: 72, width: 240, x: 0, y: 0},
                {height: 400, name: 'loop_1', paintedWidth: 240, width: 240, x: 300, y: 0},
                {height: 100, name: 'task_2', paintedWidth: 72, width: 240, x: 150, y: 320},
            ];

            expect(separateOverlappingGraphMembers(memberBoxes, 'TB')).toEqual(memberBoxes);
        });

        it('pushes along the flow axis, which is rightwards in LR', () => {
            const settled = separateOverlappingGraphMembers(
                [
                    {height: 300, name: 'grown', width: 500, x: 0, y: 0},
                    {height: 100, name: 'covered', width: 240, x: 200, y: 100},
                ],
                'LR'
            );

            expect(settled.find((memberBox) => memberBox.name === 'grown')).toMatchObject({x: 0, y: 0});
            expect(settled.find((memberBox) => memberBox.name === 'covered')).toMatchObject({
                x: 500 + GRAPH_MEMBER_SPACING,
                y: 100,
            });
        });

        it('settles in one pass, so a second run moves nothing', () => {
            const memberBoxes = [
                {height: 800, name: 'aiAgent_2', width: 900, x: 100, y: 0},
                {height: 100, name: 'activeCampaign_3', paintedWidth: 72, width: 240, x: 300, y: 340},
                {height: 100, name: 'accelo_1', paintedWidth: 72, width: 240, x: 300, y: 560},
            ];

            const settled = separateOverlappingGraphMembers(memberBoxes, 'TB');

            expect(separateOverlappingGraphMembers(settled, 'TB')).toEqual(settled);
        });
    });
});

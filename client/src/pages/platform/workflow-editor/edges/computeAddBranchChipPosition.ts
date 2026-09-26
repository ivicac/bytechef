import {LayoutDirectionType} from '@/shared/constants';

interface ComputeAddBranchChipPositionProps {
    layoutDirection: LayoutDirectionType;
    sourceX: number;
    sourceY: number;
    targetX: number;
    targetY: number;
}

// How far before the last lane's corner the chip is centred: the edge's 10px corner radius, half of
// the ~36px chip, and a small gap, so the chip sits on the straight run right before it bends.
const CORNER_CLEARANCE = 36;

/**
 * Places the add-a-branch chip at the far end of the frame's top bar — horizontal in TB, vertical in
 * LR — right before the bar turns into the last lane. On a run too short for that the chip falls
 * back to the run's midpoint, so it never slides back past where the run starts.
 */
export default function computeAddBranchChipPosition({
    layoutDirection,
    sourceX,
    sourceY,
    targetX,
    targetY,
}: ComputeAddBranchChipPositionProps): {x: number; y: number} {
    if (layoutDirection === 'LR') {
        return {x: sourceX, y: Math.max((sourceY + targetY) / 2, targetY - CORNER_CLEARANCE)};
    }

    return {x: Math.max((sourceX + targetX) / 2, targetX - CORNER_CLEARANCE), y: sourceY};
}

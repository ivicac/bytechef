import {LayoutDirectionType} from '@/shared/constants';

interface ComputeBranchCaseLabelPositionProps {
    layoutDirection: LayoutDirectionType;
    sourceX: number;
    sourceY: number;
    targetX: number;
    targetY: number;
}

const EDGE_BUTTON_OFFSET = 10;

// In LR the run between the split bar and the row content is far narrower than
// the chip, so a bar-centered chip clips the row's first node. The corridor
// LEFT of the bar on a case row is empty (the dispatcher icon only occupies
// the entry-axis row), so the chip hangs outside the frame instead:
// right-anchored this much before the bar, centered on its row line.
const LR_BAR_CLEARANCE = 8;

// With an odd case count the middle row shares the dispatcher's axis, so its
// corridor holds the dispatcher icon (72px band around the axis) — lift that
// one chip above the icon band instead of overlapping it.
const LR_AXIS_ROW_LIFT = 60;

const LR_AXIS_ROW_TOLERANCE = 40;

export default function computeBranchCaseLabelPosition({
    layoutDirection,
    sourceX,
    sourceY,
    targetX,
    targetY,
}: ComputeBranchCaseLabelPositionProps): {x: number; y: number} {
    if (layoutDirection === 'LR') {
        const isDispatcherAxisRow = Math.abs(targetY - sourceY) < LR_AXIS_ROW_TOLERANCE;

        return {
            x: sourceX - LR_BAR_CLEARANCE,
            y: isDispatcherAxisRow ? sourceY - LR_AXIS_ROW_LIFT : targetY,
        };
    }

    return {
        x: targetX,
        y: sourceY + EDGE_BUTTON_OFFSET,
    };
}

import {LayoutDirectionType} from '@/shared/constants';

interface ComputeBranchCaseLabelPositionProps {
    layoutDirection: LayoutDirectionType;
    sourceX: number;
    sourceY: number;
    targetX: number;
    targetY: number;
}

const EDGE_BUTTON_OFFSET = 10;

// In LR the chip sits ON the row's entry line like TB chips sit on their
// column's entry edge: right-anchored just past the split bar so it straddles
// the frame's vertical side and the start of the row line. The 94px entry run
// holds the row's add-button at run-mid + 15 (left edge = bar + 46), so the
// chip ends at bar + 44 — clear of the button and far from the row's node.
const LR_ROW_LINE_OVERHANG = 44;

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
            x: sourceX + LR_ROW_LINE_OVERHANG,
            y: isDispatcherAxisRow ? sourceY - LR_AXIS_ROW_LIFT : targetY,
        };
    }

    return {
        x: targetX,
        y: sourceY + EDGE_BUTTON_OFFSET,
    };
}

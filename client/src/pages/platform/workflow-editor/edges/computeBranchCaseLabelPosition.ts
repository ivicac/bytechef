import {LayoutDirectionType} from '@/shared/constants';

interface ComputeBranchCaseLabelPositionProps {
    layoutDirection: LayoutDirectionType;
    sourceX: number;
    sourceY: number;
    targetX: number;
    targetY: number;
}

const EDGE_BUTTON_OFFSET = 10;

// In LR the chip hangs ABOVE its row's entry line (the line stays fully
// visible), right-anchored just past the split bar so it labels the row start.
// 44 = the 94px entry run minus the row's add-button, which sits on the line
// from bar + 46 — the chip's right edge stops before the button's column.
const LR_ROW_LINE_OVERHANG = 44;

// Gap between the chip's bottom edge and the row line it labels.
const LR_LINE_GAP = 12;

// With an odd case count the middle row shares the dispatcher's axis, so the
// space above its line holds the dispatcher icon (36px above the axis) —
// that row's chip bottom clears the icon band with breathing room. The lift
// also absorbs the bar's source-handle offset (up to 7px below the axis).
const LR_AXIS_ROW_LIFT = 56;

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
            y: isDispatcherAxisRow ? sourceY - LR_AXIS_ROW_LIFT : targetY - LR_LINE_GAP,
        };
    }

    return {
        x: targetX,
        y: sourceY + EDGE_BUTTON_OFFSET,
    };
}

import Badge from '@/components/Badge/Badge';

interface AutomationVersionProps {
    workflowVersion?: number;
}

/**
 * The automation's deployed version, which the server bumps on every publish. Absent until the
 * first publish, and rendered as nothing then: there is no deployed version to state yet.
 *
 * A badge rather than loose text, to match the version the builder's own header shows -- the two
 * state the same fact about the same automation, so they should not look like different kinds of
 * thing. `outline-outline` keeps it quiet next to the card title it sits beside.
 */
const AutomationVersion = ({workflowVersion}: AutomationVersionProps) => {
    if (!workflowVersion) {
        return null;
    }

    return <Badge className="shrink-0" label={`V${workflowVersion}`} styleType="outline-outline" weight="regular" />;
};

export default AutomationVersion;

import Badge from '@/components/Badge/Badge';
import {ProjectStatus} from '@/shared/middleware/automation/configuration';
import {useGetProjectQuery} from '@/shared/queries/automation/projects.queries';

interface WorkflowTabLabelProps {
    fallbackName: string;
    projectId: string;
}

/**
 * Tab-strip label for a workflow (project-scoped) tab. Shows the project name followed by a single
 * "V<version> <STATUS>" badge — the same outline badge used by ProjectTitle on the full project page —
 * falling back to the selected workflow's name while the project query is loading or if it errors. The
 * query is react-query-cached so multiple tabs of the same project share one fetch.
 */
const WorkflowTabLabel = ({fallbackName, projectId}: WorkflowTabLabelProps) => {
    const {data: project} = useGetProjectQuery(Number(projectId), undefined, Number(projectId) > 0);

    if (!project) {
        return <>{fallbackName}</>;
    }

    return (
        <span className="inline-flex items-center gap-2">
            <span>{project.name}</span>

            {project.lastProjectVersion != null && (
                <Badge
                    className="flex space-x-1 bg-surface-neutral-primary"
                    styleType={project.lastStatus === ProjectStatus.Published ? 'success-outline' : 'outline-outline'}
                    weight="semibold"
                >
                    <span>V{project.lastProjectVersion}</span>

                    <span>{project.lastStatus}</span>
                </Badge>
            )}
        </span>
    );
};

export default WorkflowTabLabel;

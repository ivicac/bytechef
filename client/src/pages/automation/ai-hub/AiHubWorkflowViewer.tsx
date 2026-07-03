import {aiHubTabsStore} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import EmbeddableWorkflowEditor from '@/pages/platform/workflow-editor/EmbeddableWorkflowEditor';
import {Workflow} from '@/shared/middleware/automation/configuration';
import {useGetProjectWorkflowsQuery} from '@/shared/queries/automation/projectWorkflows.queries';

interface AiHubWorkflowViewerProps {
    name: string;
    projectId: string;
    projectWorkflowId: number;
}

interface WorkflowTabSelectionI {
    name: string;
    projectId: string;
    projectWorkflowId: number;
    workflowId: string;
}

/**
 * Resolve the `openWorkflowTab` arguments for a workflow the user picked in the in-tab selector. The
 * selector only yields a `projectWorkflowId`; we look up the matching workflow to recover its UUID `id`
 * and `label`. Returns null when the list is missing or the id isn't found, so callers can no-op safely.
 */
export function resolveWorkflowTabSelection(
    projectWorkflows: Workflow[] | undefined,
    projectId: string,
    projectWorkflowId: number
): WorkflowTabSelectionI | null {
    const match = projectWorkflows?.find((workflow) => workflow.projectWorkflowId === projectWorkflowId);

    if (!match) {
        return null;
    }

    return {
        name: match.label ?? '',
        projectId,
        projectWorkflowId,
        workflowId: match.id ?? '',
    };
}

const AiHubWorkflowViewer = ({projectId, projectWorkflowId}: AiHubWorkflowViewerProps) => {
    const {data: projectWorkflows} = useGetProjectWorkflowsQuery(Number(projectId), Number(projectId) > 0);

    const handleWorkflowChange = (nextProjectWorkflowId: number) => {
        const selection = resolveWorkflowTabSelection(projectWorkflows, projectId, nextProjectWorkflowId);

        if (!selection) {
            return;
        }

        // Tab is project-scoped: openWorkflowTab dedups by projectId and re-points this tab's workflow.
        aiHubTabsStore
            .getState()
            .openWorkflowTab(selection.workflowId, selection.projectId, selection.projectWorkflowId, selection.name);
    };

    return (
        <div className="flex size-full flex-col">
            {projectWorkflowId > 0 ? (
                // Keyed by the workflow so the editor RE-MOUNTS when the tab re-points to a different
                // workflow (e.g. clicking a workflow artifact from another task into an already-open tab).
                // Without the re-mount, React Flow keeps the previous viewport and the new workflow opens
                // off-center. The embedded editor opts into fitViewOnWorkflowChange (see
                // EmbeddableWorkflowEditor), so each mount fits-to-view once nodes are measured.
                <EmbeddableWorkflowEditor
                    key={`${projectId}-${projectWorkflowId}`}
                    onWorkflowChange={handleWorkflowChange}
                    projectId={+projectId}
                    projectWorkflowId={projectWorkflowId}
                    showPublishDeploy
                    showWorkflowSelect
                />
            ) : (
                <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                    Workflow reference unavailable.
                </div>
            )}
        </div>
    );
};

export default AiHubWorkflowViewer;

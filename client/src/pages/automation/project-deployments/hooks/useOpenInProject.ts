import {ProjectApi, WorkflowApi} from '@/shared/middleware/automation/configuration';
import {getDevelopmentOnlyFallbackHref} from '@/shared/navigation/developmentOnlyRoutes';
import {ProjectWorkflowKeys} from '@/shared/queries/automation/projectWorkflows.queries';
import {ProjectKeys} from '@/shared/queries/automation/projects.queries';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useQueryClient} from '@tanstack/react-query';
import {useCallback} from 'react';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

const PROJECTS_HREF = '/automation/projects';

/**
 * Opens the project behind a deployment, or the editable counterpart of one of its deployed workflows.
 *
 * Both targets are resolved on click rather than rendered as hrefs: a deployment list can hold many rows, and
 * resolving eagerly would fetch every project's workflows just to draw the page.
 *
 * A deployed workflow belongs to the project version it was deployed from, so its own `projectWorkflowId` points at
 * that version's row, not the one the editor edits today. The editable workflow is found by `workflowUuid` in the
 * project's latest version instead — the same lookup the editor uses to follow a subflow.
 *
 * Projects are a Development-only surface, so `canOpenInProject` is false in every other environment; the route
 * guard would bounce the user straight back here otherwise.
 *
 * `onBeforeNavigate` runs only once a target has resolved, so a caller can close an overlay without losing it to a
 * lookup that ends in a toast instead.
 */
export default function useOpenInProject({onBeforeNavigate}: {onBeforeNavigate?: () => void} = {}) {
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    const canOpenInProject = getDevelopmentOnlyFallbackHref(PROJECTS_HREF, currentEnvironmentId) === undefined;

    const openProject = useCallback(
        async (projectId: number) => {
            try {
                const project = await queryClient.fetchQuery({
                    queryFn: () => new ProjectApi().getProject({id: projectId}),
                    queryKey: ProjectKeys.project(projectId),
                });

                const firstProjectWorkflowId = project.projectWorkflowIds?.[0];

                if (firstProjectWorkflowId == null) {
                    toast('The project has no workflows to open.');

                    return;
                }

                onBeforeNavigate?.();

                navigate(`${PROJECTS_HREF}/${projectId}/project-workflows/${firstProjectWorkflowId}`);
            } catch {
                // The fetch interceptor already reports the failed request.
                return;
            }
        },
        [navigate, onBeforeNavigate, queryClient]
    );

    const openProjectWorkflow = useCallback(
        async (projectId: number, workflowUuid?: string) => {
            try {
                const projectWorkflows = await queryClient.fetchQuery({
                    queryFn: () => new WorkflowApi().getProjectWorkflows({id: projectId}),
                    queryKey: ProjectWorkflowKeys.projectWorkflows(projectId),
                });

                const matchingWorkflow = projectWorkflows.find(
                    (projectWorkflow) => workflowUuid != null && projectWorkflow.workflowUuid === workflowUuid
                );

                if (matchingWorkflow?.projectWorkflowId == null) {
                    toast('This workflow no longer exists in the latest project version.');

                    return;
                }

                onBeforeNavigate?.();

                navigate(`${PROJECTS_HREF}/${projectId}/project-workflows/${matchingWorkflow.projectWorkflowId}`);
            } catch {
                // The fetch interceptor already reports the failed request.
                return;
            }
        },
        [navigate, onBeforeNavigate, queryClient]
    );

    return {canOpenInProject, openProject, openProjectWorkflow};
}

import {
    GetWorkflowExecutionRequest,
    GetWorkflowExecutionTaskExecutionRequest,
    GetWorkflowExecutionsPageRequest,
    Page,
    TaskExecution,
    WorkflowExecution,
    WorkflowExecutionApi,
} from '@/shared/middleware/automation/workflow/execution';

/* eslint-disable sort-keys */
import {useInfiniteQuery, useQuery} from '@tanstack/react-query';

/**
 * A row of the executions list is a job, or a trigger execution that failed before any job existed; the detail is
 * fetched from a different endpoint for each.
 */
export type WorkflowExecutionKindType = 'JOB' | 'TRIGGER_EXECUTION';

export const WorkflowExecutionKeys = {
    filteredWorkflowExecutions: (request: GetWorkflowExecutionsPageRequest) => [
        ...WorkflowExecutionKeys.workflowExecutions,
        request,
    ],
    workflowExecution: (id: number, kind: WorkflowExecutionKindType = 'JOB') =>
        kind === 'JOB'
            ? [...WorkflowExecutionKeys.workflowExecutions, id]
            : [...WorkflowExecutionKeys.workflowExecutions, kind, id],
    workflowExecutionTaskExecution: (id: number, taskExecutionId: number) => [
        ...WorkflowExecutionKeys.workflowExecutions,
        id,
        'taskExecution',
        taskExecutionId,
    ],
    workflowExecutions: ['automation_workflowExecutions'] as const,
};

const RUNNING_WORKFLOW_EXECUTIONS_POLLING_INTERVAL_MS = 2000;

const RUNNING_JOB_STATUSES = new Set(['CREATED', 'STARTED']);

export function getWorkflowExecutionsRefetchInterval(page: Page | undefined): number | false {
    const hasRunningJob = (page?.content ?? []).some((workflowExecution) =>
        RUNNING_JOB_STATUSES.has((workflowExecution as {job?: {status?: string}}).job?.status ?? '')
    );

    return hasRunningJob ? RUNNING_WORKFLOW_EXECUTIONS_POLLING_INTERVAL_MS : false;
}

export const useGetWorkspaceProjectWorkflowExecutionsQuery = (
    request: GetWorkflowExecutionsPageRequest,
    enabled?: boolean
) =>
    useQuery<Page, Error>({
        queryKey: WorkflowExecutionKeys.filteredWorkflowExecutions(request),
        queryFn: () =>
            new WorkflowExecutionApi().getWorkflowExecutionsPage({
                ...request,
                embedded: false,
            }),
        enabled: enabled === undefined ? true : enabled,
        refetchInterval: (query) => getWorkflowExecutionsRefetchInterval(query.state.data),
    });

/**
 * Compute the next page number for the executions infinite query, or `undefined` when the last page
 * has been loaded. The backend page size is fixed (20); paging is driven purely by page number.
 */
export function getNextWorkflowExecutionsPageParam(lastPage: Page): number | undefined {
    const current = lastPage.number ?? 0;
    const totalPages = lastPage.totalPages ?? 0;

    return current + 1 < totalPages ? current + 1 : undefined;
}

/**
 * Accumulating executions query: each `fetchNextPage()` loads the next server page (page size 20) and
 * appends it, so the picker can "Show more" across the full result set instead of seeing only page 0.
 * Keyed on the base request (sans pageNumber).
 */
export const useInfiniteWorkspaceProjectWorkflowExecutionsQuery = (
    request: GetWorkflowExecutionsPageRequest,
    enabled?: boolean
) =>
    useInfiniteQuery<Page, Error>({
        queryKey: [...WorkflowExecutionKeys.workflowExecutions, 'infinite', request],
        queryFn: ({pageParam}) =>
            new WorkflowExecutionApi().getWorkflowExecutionsPage({
                ...request,
                embedded: false,
                pageNumber: pageParam as number,
            }),
        initialPageParam: 0,
        getNextPageParam: getNextWorkflowExecutionsPageParam,
        enabled: enabled === undefined ? true : enabled,
    });

export const useGetProjectWorkflowExecutionQuery = (
    request: GetWorkflowExecutionRequest,
    enabled?: boolean,
    refetchInterval?: number | false,
    kind: WorkflowExecutionKindType = 'JOB'
) =>
    useQuery<WorkflowExecution, Error>({
        queryKey: WorkflowExecutionKeys.workflowExecution(request.id, kind),
        queryFn: () =>
            kind === 'TRIGGER_EXECUTION'
                ? new WorkflowExecutionApi().getTriggerExecutionWorkflowExecution({triggerExecutionId: request.id})
                : new WorkflowExecutionApi().getWorkflowExecution(request),
        enabled: enabled === undefined ? true : enabled,
        refetchInterval,
    });

export const useGetWorkflowExecutionTaskExecutionQuery = (
    request: GetWorkflowExecutionTaskExecutionRequest,
    enabled: boolean,
    refetchInterval?: number | false
) =>
    useQuery<TaskExecution, Error>({
        queryKey: WorkflowExecutionKeys.workflowExecutionTaskExecution(request.id, request.taskExecutionId),
        queryFn: () => new WorkflowExecutionApi().getWorkflowExecutionTaskExecution(request),
        enabled,
        refetchInterval,
    });

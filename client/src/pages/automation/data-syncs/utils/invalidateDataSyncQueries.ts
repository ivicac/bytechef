import {ProjectKeys} from '@/shared/queries/automation/projects.queries';
import {type QueryClient} from '@tanstack/react-query';

interface InvalidateDataSyncQueriesOptionsI {
    /**
     * Also invalidate the workspace project list. A data sync's create or delete changes which project it
     * belongs to — a new project created behind the scenes, or a project left holding one fewer workflow —
     * which a plain title/description edit never does.
     */
    projects?: boolean;
}

/* Invalidates the single-sync detail view and the workspace list after any mutation. */
export default function invalidateDataSyncQueries(
    queryClient: QueryClient,
    options: InvalidateDataSyncQueriesOptionsI = {}
) {
    queryClient.invalidateQueries({queryKey: ['dataSync']});
    queryClient.invalidateQueries({queryKey: ['dataSyncs']});

    if (options.projects) {
        queryClient.invalidateQueries({queryKey: ProjectKeys.projects});
    }
}

import {type QueryClient} from '@tanstack/react-query';

/* Invalidates the single-sync detail view and the workspace list after any mutation. */
export default function invalidateDataSyncQueries(queryClient: QueryClient) {
    queryClient.invalidateQueries({queryKey: ['dataSync']});
    queryClient.invalidateQueries({queryKey: ['dataSyncs']});
}

import {HubBuilderContext} from '@/ee/pages/embedded/automation-hub/hubBuilderContext';
import {AutomationHubKeys, useGetWorkflowQuery} from '@/ee/pages/embedded/automation-hub/queries/automationHub.queries';
import {useAutomationHubStore} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';
import WorkflowBuilder from '@/ee/pages/embedded/workflow-builder/WorkflowBuilder';
import {useQueryClient} from '@tanstack/react-query';
import {useCallback, useEffect, useMemo} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {useShallow} from 'zustand/react/shallow';

/**
 * Opens the workflow builder inside the hub's own iframe as an internal route (spec D3), so a
 * vendor embeds one component instead of wiring the builder separately. Deliberately a sibling of
 * `hub` in the route tree rather than nested under `AutomationHubLayout`/`RequireTab` — the
 * builder owns the whole surface and has no tab strip of its own.
 *
 * The hub already completed the EMBED_READY/EMBED_INIT handshake before this route was ever
 * reachable, so its three vendor-supplied settings are forwarded to the builder via
 * `HubBuilderContext` instead of the builder repeating the handshake for itself.
 */
const HubBuilderView = () => {
    const {connectionDialogAllowed, includeComponents, sharedConnectionIds} = useAutomationHubStore(
        useShallow((state) => ({
            connectionDialogAllowed: state.connectionDialogAllowed,
            includeComponents: state.includeComponents,
            sharedConnectionIds: state.sharedConnectionIds,
        }))
    );

    const {workflowUuid} = useParams();

    const navigate = useNavigate();

    const queryClient = useQueryClient();

    const {error: workflowError} = useGetWorkflowQuery(workflowUuid);

    // Declared BEFORE the context value that closes over it: a `const` referenced from the memo's
    // factory above its own initialisation throws `Cannot access 'handleBackClick' before
    // initialization`, because the factory runs during that render (see CLAUDE.md's temporal
    // dead zone note).
    const handleBackClick = useCallback(() => {
        queryClient.invalidateQueries({queryKey: AutomationHubKeys.automations});

        navigate('/embedded/hub');
    }, [navigate, queryClient]);

    // `useWorkflowBuilder`'s effect depends on this context value by identity, so a fresh object
    // per render would re-run it on every render of this component — inert today only because
    // `useShallow` keeps the selected values referentially stable and `handleBackClick` is
    // memoized.
    const hubBuilderContextValue = useMemo(
        () => ({connectionDialogAllowed, includeComponents, onBack: handleBackClick, sharedConnectionIds}),
        [connectionDialogAllowed, handleBackClick, includeComponents, sharedConnectionIds]
    );

    // The route can outlive the workflow: the hub restores the route it was on across a host
    // refresh, and the automation may have been deleted since. The builder has no tab strip to
    // navigate away with, so a workflow that will not load has to return the viewer itself rather
    // than leave them on an empty canvas with no way out.
    useEffect(() => {
        if (workflowError) {
            navigate('/embedded/hub', {replace: true});
        }
    }, [navigate, workflowError]);

    return (
        <HubBuilderContext.Provider value={hubBuilderContextValue}>
            <div className="relative size-full">
                <WorkflowBuilder />
            </div>
        </HubBuilderContext.Provider>
    );
};

export default HubBuilderView;

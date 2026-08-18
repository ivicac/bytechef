import {ConnectedUserProjectWorkflow} from '@/ee/shared/middleware/embedded/public';

export interface RemoveAutomationHandlersI {
    onDeleteAutomation: (workflowUuid: string) => Promise<unknown>;
    onDeprovisionReference: (workflowUuid: string) => Promise<unknown>;
}

/**
 * The single place the COPY/REFERENCE removal split lives. A COPY is the connected user's own
 * workflow and is deleted outright; a REFERENCE has no workflow of its own, so it is de-provisioned
 * by the catalog workflow uuid it points at.
 *
 * Both cards that can remove an automation — an activated template and one no template accounts
 * for — call this rather than branching themselves, so the two cannot drift into calling different
 * endpoints for the same kind.
 *
 * The chosen call's promise is returned, so the caller can keep its confirmation dialog on screen
 * until the removal actually settles rather than closing over an in-flight request.
 */
export const removeAutomation = (
    automation: ConnectedUserProjectWorkflow,
    {onDeleteAutomation, onDeprovisionReference}: RemoveAutomationHandlersI
): Promise<unknown> => {
    if (automation.kind === 'COPY') {
        return onDeleteAutomation(automation.workflowUuid!);
    }

    return onDeprovisionReference(automation.catalogWorkflowUuid ?? automation.workflowUuid!);
};

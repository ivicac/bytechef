import {createContext} from 'react';

/**
 * The three vendor-supplied settings the hub already learned from its own EMBED_READY/EMBED_INIT
 * handshake (spec D3), forwarded to the embedded workflow builder when it renders as an internal
 * hub route rather than as its own top-level embedded surface. `null` outside `HubBuilderView`'s
 * subtree — `useWorkflowBuilder` treats a `null` value as "run the handshake myself" (the
 * standalone-builder case).
 */
export interface HubBuilderContextValueI {
    connectionDialogAllowed: boolean;
    includeComponents?: string[];
    /**
     * Returns the user to the hub's automations. Present only when the builder is a hub route; the
     * standalone embedded builder has a host page to go back to and supplies none. The builder's
     * own header renders it, so leaving a workflow costs one control rather than a second header
     * row repeating the workflow's name above the one the builder already draws.
     */
    onBack?: () => void;
    sharedConnectionIds: number[];
}

export const HubBuilderContext = createContext<HubBuilderContextValueI | null>(null);

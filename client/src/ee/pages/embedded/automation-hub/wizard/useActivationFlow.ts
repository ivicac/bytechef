import {
    WireNodeConnectionRequestI,
    useCopyTemplateMutation,
    useDeleteAutomationMutation,
    useDeprovisionReferenceMutation,
    useProvisionReferenceMutation,
    usePublishAutomationMutation,
    useSetAutomationEnabledMutation,
    useUpdateAutomationInputsMutation,
    useWireNodeConnectionMutation,
} from '@/ee/pages/embedded/automation-hub/mutations/automationHub.mutations';
import {useFetchWorkflow} from '@/ee/pages/embedded/automation-hub/queries/automationHub.queries';
import {
    ActivationActionType,
    ActivationStateI,
    activationReducer,
    initialActivationState,
} from '@/ee/pages/embedded/automation-hub/wizard/activationReducer';
import {
    AutomationWorkflowProjectKindEnum,
    AutomationWorkflowProjectWorkflowTemplate,
    MissingConnectionError,
    ResponseError,
} from '@/ee/shared/middleware/embedded/public';
import {useGetComponentDefinitionsQuery} from '@/shared/queries/automation/componentDefinitions.queries';
import {Dispatch, useCallback, useMemo, useReducer, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';

const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.';
const UNREADABLE_DEFINITION_MESSAGE = 'The copied automation could not be read.';
const WIRING_ERROR_MESSAGE = 'Your accounts could not be connected to this automation. Please try again.';

interface WorkflowNodeI {
    connections?: unknown;
    name?: string;
    type?: string;
}

interface WorkflowDefinitionI {
    tasks?: WorkflowNodeI[];
    triggers?: WorkflowNodeI[];
}

interface WorkflowNodeConnectionI {
    componentName: string;
    workflowConnectionKey: string;
}

export interface ActivationFlowI {
    activate: () => Promise<void>;
    busy: boolean;
    dispatch: Dispatch<ActivationActionType>;
    editWorkflow: () => Promise<void>;
    openInBuilder: () => void;
    state: ActivationStateI;
}

/**
 * A `ResponseError` message is the SDK's own boilerplate ("Response returned an error code"), never
 * anything the connected user should read, so only a genuine `Error` message is surfaced as-is.
 */
const toErrorMessage = (error: unknown): string => {
    if (error instanceof ResponseError) {
        return GENERIC_ERROR_MESSAGE;
    }

    return error instanceof Error && error.message ? error.message : GENERIC_ERROR_MESSAGE;
};

/**
 * Both the provision and the enable endpoints report a connection they could not auto-wire as a
 * `MissingConnectionError` body on a 409; anything else is a plain failure.
 *
 * Call this AT MOST ONCE per error: reading the body consumes the response stream, so a second
 * call on the same error throws and silently degrades a missing-connection 409 into an opaque
 * failure. `activate` reads it once and passes the answer to everything that needs it.
 */
const readMissingConnectionComponentName = async (error: unknown): Promise<string | undefined> => {
    if (!(error instanceof ResponseError) || error.response.status !== 409) {
        return undefined;
    }

    try {
        const body = (await error.response.json()) as MissingConnectionError;

        return body?.missingConnectionComponentName;
    } catch {
        return undefined;
    }
};

/**
 * The connection keys a copied workflow node exposes, paired with the component each one belongs
 * to. A plain component task declares nothing about connections in the definition — the platform
 * derives a single connection whose key IS the component name
 * (`DefaultComponentConnectionFactory` via `ComponentConnection.of`) — which is why the fallback,
 * not the two branches above it, is what fires for every node of a visual template today.
 *
 * The list and map branches are defensive only: a task that DOES declare connections carries them
 * under `extensions.connections`, which this reader does not descend into, so neither branch
 * matches a definition the server writes. They are kept because they cost nothing and make a
 * hand-authored or future definition shape degrade into correct keys rather than into the
 * component-name fallback.
 */
const readWorkflowNodeConnections = (node: WorkflowNodeI, nodeComponentName: string): WorkflowNodeConnectionI[] => {
    const {connections} = node;

    if (Array.isArray(connections) && connections.length > 0) {
        return connections.map((connection) => {
            const componentName = (connection?.componentName as string) || nodeComponentName;

            return {componentName, workflowConnectionKey: (connection?.key as string) || componentName};
        });
    }

    if (connections && typeof connections === 'object') {
        const entries = Object.entries(connections as Record<string, {componentName?: string}>);

        if (entries.length > 0) {
            return entries.map(([workflowConnectionKey, connection]) => ({
                componentName: connection?.componentName || nodeComponentName,
                workflowConnectionKey,
            }));
        }
    }

    return [{componentName: nodeComponentName, workflowConnectionKey: nodeComponentName}];
};

const buildWiringRequests = (
    definition: WorkflowDefinitionI,
    selections: Record<string, number | undefined>,
    workflowUuid: string
): WireNodeConnectionRequestI[] => {
    const requests: WireNodeConnectionRequestI[] = [];

    for (const node of [...(definition.triggers || []), ...(definition.tasks || [])]) {
        const nodeComponentName = String(node.type || '').split('/')[0];

        for (const nodeConnection of readWorkflowNodeConnections(node, nodeComponentName)) {
            const connectionId = selections[nodeConnection.componentName];

            if (connectionId == null || !node.name) {
                continue;
            }

            requests.push({
                connectionId,
                workflowConnectionKey: nodeConnection.workflowConnectionKey,
                workflowNodeName: node.name,
                workflowUuid,
            });
        }
    }

    return requests;
};

/**
 * The template components the wizard has to ask for an account: the ones whose component
 * definition actually declares a connection. `connectionDefinitions: true` makes the shared
 * component-definition listing return exactly those, and the hub already loads it elsewhere, so
 * this costs no extra request. Components without a connection are skipped; when none remain the
 * reducer starts the wizard on the configure step.
 *
 * The query's failure is surfaced as `isError` rather than collapsing the list to empty on any
 * error, because an empty list is indistinguishable from "this template needs no accounts": the
 * wizard would skip the connect step, wire nothing, and activate a copy whose connections are
 * missing — a failure the user only meets at run time. The caller must refuse to start the flow
 * on it.
 *
 * `isError` is deliberately NOT just `!!error`: TanStack Query retains `data` from the last
 * successful fetch across a failed background refetch (this query has a 5-minute `staleTime` and
 * the default `refetchOnWindowFocus`, and `HubConnectionDialog` mounts a second observer of the
 * same query key), so `error` alone can be true while `connectionComponentDefinitions` is still
 * the last good list. Gating on plain `error` would unmount an already-open wizard mid-flight —
 * `isError` is true only when the lookup has NEVER produced data, which is the only case the
 * caller must actually refuse to start on.
 */
export const useRequiredComponents = (template: AutomationWorkflowProjectWorkflowTemplate) => {
    const {
        data: connectionComponentDefinitions,
        error,
        isLoading,
        refetch,
    } = useGetComponentDefinitionsQuery({
        connectionDefinitions: true,
    });

    const requiredComponents = useMemo(() => {
        const connectionComponentNames = new Set(
            (connectionComponentDefinitions || []).map((componentDefinition) => componentDefinition.name)
        );

        const componentNames: string[] = [];

        for (const component of template.components || []) {
            if (
                component.name &&
                connectionComponentNames.has(component.name) &&
                !componentNames.includes(component.name)
            ) {
                componentNames.push(component.name);
            }
        }

        return componentNames;
    }, [connectionComponentDefinitions, template.components]);

    const isError = !!error && connectionComponentDefinitions === undefined;

    return {isError, isLoading, refetch, requiredComponents};
};

/**
 * Owns the activation reducer plus every mutation the wizard drives.
 *
 * Walking the wizard writes NOTHING. The connect and configure steps only collect and show the
 * user's choices; the entire server-side sequence runs behind the Activate button, so closing the
 * dialog at any earlier point leaves the connected user's account exactly as it was. That is a
 * deliberate reversal of the original flow, which copied the template the moment the user left the
 * connect step and left a disabled automation behind on every abandoned wizard.
 *
 * The two catalog kinds branch inside that one click:
 *
 * - `COPY` copies the template into the connected user's own project, reads the copy back to learn
 *   which nodes to wire, wires the selected connections onto them, and must PUBLISH the copy
 *   before enabling it — `enableProjectWorkflow` refuses a copy that is not in the active
 *   deployment. Publishing snapshots the workflow's connections into the deployment, which is why
 *   the wiring PUTs are awaited in order rather than fired off in parallel: publishing while one
 *   is still in flight produces an enabled deployment with a partial connection list that only
 *   fails at run time.
 * - `REFERENCE` provisions a reference against the shared catalog workflow, which auto-wires by
 *   component match, and enables it directly.
 *
 * Because the chain can fail partway, every failure rolls back whatever that run created — a copy
 * is deleted, a reference row is de-provisioned — so a failed activation is indistinguishable from
 * one that never started. The single exception is a copy the user opened in the builder: they
 * asked for that workflow to exist and may have edited it, so it outlives the wizard.
 */
export const useActivationFlow = (
    template: AutomationWorkflowProjectWorkflowTemplate,
    kind: AutomationWorkflowProjectKindEnum,
    requiredComponents: string[]
): ActivationFlowI => {
    const [pending, setPending] = useState(false);

    const copiedWorkflowUuidRef = useRef<string>(undefined);

    // The generated model makes every field optional; an input without a name has no key to store a
    // value under, so it cannot be asked for and is dropped rather than rendered as a nameless box.
    const templateInputs = useMemo(
        () =>
            (template.inputs ?? [])
                .filter((input): input is typeof input & {name: string} => !!input.name)
                .map(({label, name, required, type}) => ({label, name, required, type})),
        [template.inputs]
    );

    const [state, dispatch] = useReducer(
        activationReducer,
        initialActivationState(kind, requiredComponents, templateInputs)
    );

    const navigate = useNavigate();

    const fetchWorkflow = useFetchWorkflow();

    const {mutateAsync: copyTemplate} = useCopyTemplateMutation();
    const {mutateAsync: deleteAutomation} = useDeleteAutomationMutation();
    const {mutateAsync: deprovisionReference} = useDeprovisionReferenceMutation();
    const {mutateAsync: provisionReference} = useProvisionReferenceMutation();
    const {mutateAsync: publishAutomation} = usePublishAutomationMutation();
    const {mutateAsync: updateAutomationInputs} = useUpdateAutomationInputsMutation();
    const {mutateAsync: setAutomationEnabled} = useSetAutomationEnabledMutation();
    const {mutateAsync: wireNodeConnection} = useWireNodeConnectionMutation();

    const templateUuid = template.id!;

    /**
     * Reads the copy back and puts the chosen accounts onto its nodes. Reading and wiring are one
     * unit as far as the user is concerned — both exist only to attach the accounts they picked,
     * and neither can produce a missing-connection 409, which comes from provision and enable — so
     * they collapse onto one message. An unreadable definition keeps its own, because "we could
     * not connect your accounts" would be actively misleading about a copy that is malformed.
     */
    const wireCopiedWorkflow = useCallback(
        async (workflowUuid: string) => {
            let definitionJson: string | undefined;

            try {
                const workflow = await fetchWorkflow(workflowUuid);

                definitionJson = workflow.definition;
            } catch {
                throw new Error(WIRING_ERROR_MESSAGE);
            }

            let definition: WorkflowDefinitionI;

            try {
                definition = JSON.parse(definitionJson || '') as WorkflowDefinitionI;
            } catch {
                throw new Error(UNREADABLE_DEFINITION_MESSAGE);
            }

            try {
                // Sequential rather than parallel: these all PUT onto the same workflow, and a
                // deterministic order makes a partial failure easy to reason about.
                for (const request of buildWiringRequests(definition, state.selections, workflowUuid)) {
                    await wireNodeConnection(request);
                }
            } catch {
                throw new Error(WIRING_ERROR_MESSAGE);
            }
        },
        [fetchWorkflow, state.selections, wireNodeConnection]
    );

    const activate = useCallback(async () => {
        setPending(true);

        // What this run has created so far, and therefore what it owes the account back if the
        // rest of the chain fails. A copy the user opened in the builder is already in
        // `copiedWorkflowUuidRef` and never lands in `createdWorkflowUuid`, which is exactly what
        // exempts it from the rollback.
        let createdWorkflowUuid: string | undefined;
        let provisionAttempted = false;
        let provisionSucceeded = false;

        const undoPartialActivation = async (missingConnection: boolean) => {
            try {
                if (kind === 'REFERENCE') {
                    // `getOrCreateReference` is
                    // `@Transactional(noRollbackFor = MissingConnectionException.class)`, so the
                    // 409 is the one provision failure that keeps its disabled row. Every other
                    // provision failure already rolled its row back, and de-provisioning then
                    // answers WORKFLOW_NOT_FOUND.
                    if (provisionSucceeded || (provisionAttempted && missingConnection)) {
                        await deprovisionReference(templateUuid);
                    }
                } else if (createdWorkflowUuid) {
                    await deleteAutomation(createdWorkflowUuid);

                    copiedWorkflowUuidRef.current = undefined;
                }
            } catch {
                // A rollback that fails leaves the same orphan every abandoned wizard used to
                // leave. There is nothing further to try, and the activation failure is the one
                // the user needs to read.
            }
        };

        try {
            let workflowUuid: string;

            if (kind === 'COPY') {
                if (copiedWorkflowUuidRef.current) {
                    workflowUuid = copiedWorkflowUuidRef.current;
                } else {
                    workflowUuid = await copyTemplate(templateUuid);

                    copiedWorkflowUuidRef.current = workflowUuid;
                    createdWorkflowUuid = workflowUuid;
                }

                await wireCopiedWorkflow(workflowUuid);

                await publishAutomation(workflowUuid);
            } else {
                provisionAttempted = true;

                await provisionReference(templateUuid);

                provisionSucceeded = true;
                workflowUuid = templateUuid;
            }

            // After publish, before enable: the inputs live on the project deployment publishing
            // creates, and the workflow should not start running before it has the values it was
            // asked for.
            if (state.inputs.length > 0) {
                await updateAutomationInputs({inputs: state.inputValues, workflowUuid});
            }

            await setAutomationEnabled({enabled: true, workflowUuid});

            dispatch({type: 'ACTIVATED', workflowUuid});
        } catch (error) {
            // Read once, before the rollback: consuming the response body twice would turn a
            // missing-connection 409 into an opaque failure, and the rollback needs the answer to
            // decide whether the server kept the reference row.
            const missingConnectionComponentName = await readMissingConnectionComponentName(error);

            await undoPartialActivation(missingConnectionComponentName !== undefined);

            // `doEnableProjectWorkflow` reports a connection it could not resolve with the same
            // 409 body the provision path uses, so an enable-time miss drives the same highlight
            // loop instead of an opaque failure message.
            if (missingConnectionComponentName) {
                dispatch({componentName: missingConnectionComponentName, type: 'MISSING_CONNECTION'});
            } else {
                dispatch({error: toErrorMessage(error), type: 'FAILED'});
            }
        } finally {
            setPending(false);
        }
    }, [
        copyTemplate,
        deleteAutomation,
        deprovisionReference,
        kind,
        provisionReference,
        publishAutomation,
        setAutomationEnabled,
        state.inputValues,
        state.inputs.length,
        updateAutomationInputs,
        templateUuid,
        wireCopiedWorkflow,
    ]);

    const openInBuilder = useCallback(() => {
        navigate(`/embedded/hub/builder/${state.workflowUuid}`);
    }, [navigate, state.workflowUuid]);

    /**
     * Opens the copy in the builder from any step, and so makes the copy first — the one place
     * where the wizard writes before Activate, because there is no way to open a builder on a
     * workflow that does not exist. It is an explicit request rather than a side effect of walking
     * the wizard, which is why the copy it makes survives a later failed activation.
     *
     * It reuses `copiedWorkflowUuidRef` because the server rejects a second copy of one template
     * for one connected user with a unique-constraint violation, so copying again would 500 rather
     * than produce a second automation.
     *
     * Offered for a COPY only. A REFERENCE points at the shared catalog workflow itself, which the
     * connected user must never edit.
     */
    const editWorkflow = useCallback(async () => {
        if (kind !== 'COPY') {
            return;
        }

        const existingWorkflowUuid = state.workflowUuid || copiedWorkflowUuidRef.current;

        if (existingWorkflowUuid) {
            navigate(`/embedded/hub/builder/${existingWorkflowUuid}`);

            return;
        }

        setPending(true);

        try {
            const workflowUuid = await copyTemplate(templateUuid);

            copiedWorkflowUuidRef.current = workflowUuid;

            dispatch({type: 'COPIED', workflowUuid});

            navigate(`/embedded/hub/builder/${workflowUuid}`);
        } catch (error) {
            dispatch({error: toErrorMessage(error), type: 'FAILED'});
        } finally {
            setPending(false);
        }
    }, [copyTemplate, kind, navigate, state.workflowUuid, templateUuid]);

    return {
        activate,
        busy: pending,
        dispatch,
        editWorkflow,
        openInBuilder,
        state,
    };
};

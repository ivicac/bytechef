export type ActivationStepType = 'activate' | 'configure' | 'connect' | 'done';

export interface ActivationStateI {
    error?: string;
    highlightedComponent?: string;
    // The values the connected user typed on the configure step, keyed by input name. Empty when the
    // template declares no inputs, in which case that step is never shown.
    inputValues: Record<string, unknown>;
    // The inputs the template declares. Held on the state so `canProceed` can enforce the required
    // ones without the reducer reaching back into the template.
    inputs: ActivationInputI[];
    kind: 'COPY' | 'REFERENCE';
    requiredComponents: string[]; // component names that need a connection
    selections: Record<string, number | undefined>;
    step: ActivationStepType;
    // A workflow that actually exists on the server: the copy Edit workflow made, or the one
    // activation just switched on. It stays undefined while the user walks the wizard, because
    // until Activate succeeds there is no workflow to point at.
    workflowUuid?: string;
}

export interface ActivationInputI {
    label?: string;
    name: string;
    required?: boolean;
    type?: string;
}

export type ActivationActionType =
    | {componentName: string; connectionId: number; type: 'SELECT_CONNECTION'}
    | {name: string; type: 'SET_INPUT_VALUE'; value: unknown}
    | {type: 'NEXT'}
    | {type: 'BACK'}
    | {type: 'COPIED'; workflowUuid: string}
    | {componentName: string; type: 'MISSING_CONNECTION'}
    | {type: 'ACTIVATED'; workflowUuid: string}
    | {error: string; type: 'FAILED'};

export function initialActivationState(
    kind: 'COPY' | 'REFERENCE',
    requiredComponents: string[],
    inputs: ActivationInputI[] = []
): ActivationStateI {
    return {
        inputValues: {},
        inputs,
        kind,
        requiredComponents,
        selections: {},
        // Each step is skipped when it has nothing to ask: no component needs a connection, or the
        // template declares no inputs. A template with neither opens straight on activate.
        step: requiredComponents.length > 0 ? 'connect' : inputs.length > 0 ? 'configure' : 'activate',
    };
}

export function canProceed(state: ActivationStateI): boolean {
    switch (state.step) {
        case 'activate':
            return true;
        case 'configure':
            // A required input with no value is the one thing that blocks leaving this step; the
            // values themselves are only written once Activate runs.
            return state.inputs.every(
                (input) => !input.required || `${state.inputValues[input.name] ?? ''}`.trim() !== ''
            );
        case 'connect':
            // A highlighted component (from a just-failed MISSING_CONNECTION) blocks proceeding
            // even though its stale selection is still on file — the user must pick again.
            return (
                state.highlightedComponent === undefined &&
                state.requiredComponents.every((componentName) => state.selections[componentName] != null)
            );
        case 'done':
            // Nothing to advance to once the wizard has finished.
            return false;
    }
}

export function activationReducer(state: ActivationStateI, action: ActivationActionType): ActivationStateI {
    switch (action.type) {
        case 'SELECT_CONNECTION': {
            const highlightedComponent =
                state.highlightedComponent === action.componentName ? undefined : state.highlightedComponent;

            return {
                ...state,
                highlightedComponent,
                selections: {...state.selections, [action.componentName]: action.connectionId},
            };
        }
        case 'NEXT': {
            if (!canProceed(state)) {
                return state;
            }

            if (state.step === 'connect') {
                return {...state, error: undefined, step: state.inputs.length > 0 ? 'configure' : 'activate'};
            }

            if (state.step === 'configure') {
                return {...state, error: undefined, step: 'activate'};
            }

            // 'activate' finishes via ACTIVATED, not NEXT; 'done' has nothing further.
            return state;
        }
        case 'BACK': {
            // Back walks the same skips forward took: a step that was never shown is not somewhere
            // to return to.
            if (state.step === 'activate') {
                if (state.inputs.length > 0) {
                    return {...state, error: undefined, step: 'configure'};
                }

                if (state.requiredComponents.length === 0) {
                    return state;
                }

                return {...state, error: undefined, step: 'connect'};
            }

            if (state.step === 'configure') {
                if (state.requiredComponents.length === 0) {
                    return state;
                }

                return {...state, error: undefined, step: 'connect'};
            }

            // 'connect' is the first step and 'done' is terminal.
            return state;
        }
        case 'SET_INPUT_VALUE':
            return {...state, inputValues: {...state.inputValues, [action.name]: action.value}};
        case 'COPIED':
            return {...state, error: undefined, workflowUuid: action.workflowUuid};
        case 'MISSING_CONNECTION':
            return {
                ...state,
                highlightedComponent: action.componentName,
                step: 'connect',
                workflowUuid: undefined,
            };
        case 'ACTIVATED':
            return {...state, error: undefined, step: 'done', workflowUuid: action.workflowUuid};
        case 'FAILED':
            return {...state, error: action.error};
    }
}

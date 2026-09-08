import {useComponentPropertyDisplayConditionsQuery} from '@/shared/middleware/graphql';
import {useEffect, useRef, useState} from 'react';

export type FormDisplayConditionsOperationType = 'ACTION' | 'CLUSTER_ELEMENT' | 'TRIGGER';

interface UseFormDisplayConditionsPropsI {
    componentName?: string;
    componentVersion?: number;
    enabled?: boolean;
    operationName?: string;
    operationType?: FormDisplayConditionsOperationType;
    parameters: Record<string, unknown>;
}

interface UseFormDisplayConditionsReturnI {
    displayConditions: Record<string, boolean> | undefined;
    isEvaluating: boolean;
}

/** Long enough that typing a value does not fire a request per keystroke, short enough to feel live. */
const DEBOUNCE_MS = 300;

/**
 * Evaluates an operation's display conditions against a standalone form's current values.
 *
 * The workflow editor reads conditions off the workflow node it is editing. A property form outside the editor —
 * a tool config dialog, an MCP tool popover — has no node, so it asks the server to evaluate them against the
 * values it holds. Conditions have to re-evaluate as the user types (choosing a body content type should hide the
 * other four body properties immediately), hence the debounce rather than a one-shot fetch.
 *
 * `displayConditions` is undefined until a result has actually arrived. Callers pass that straight to
 * `formDisplayConditions`, where undefined means "not evaluated" and leaves every conditional property visible.
 * `isEvaluating` reports that a first result is still outstanding.
 */
const useFormDisplayConditions = ({
    componentName,
    componentVersion,
    enabled = true,
    operationName,
    operationType,
    parameters,
}: UseFormDisplayConditionsPropsI): UseFormDisplayConditionsReturnI => {
    const [debouncedParameters, setDebouncedParameters] = useState(parameters);

    const lastDisplayConditionsRef = useRef<Record<string, boolean> | undefined>(undefined);

    // Serialized rather than compared by identity: the caller rebuilds this object on every render, so an
    // identity dependency would reschedule the timer forever and never settle.
    const serializedParameters = JSON.stringify(parameters);

    // An empty map is a form whose defaults have not been applied yet, not a verdict that nothing holds. Querying
    // on it would hide every conditional property until the debounce elapsed.
    const queryEnabled =
        enabled &&
        !!componentName &&
        componentVersion !== undefined &&
        !!operationName &&
        !!operationType &&
        Object.keys(parameters).length > 0;

    const {data} = useComponentPropertyDisplayConditionsQuery(
        {
            componentName: componentName ?? '',
            componentVersion: componentVersion ?? 1,
            operationName: operationName ?? '',
            operationType: operationType ?? 'ACTION',
            parameters: debouncedParameters,
        },
        {enabled: queryEnabled}
    );

    useEffect(() => {
        const timeout = setTimeout(() => setDebouncedParameters(JSON.parse(serializedParameters)), DEBOUNCE_MS);

        return () => clearTimeout(timeout);
    }, [serializedParameters]);

    const displayConditions = data?.componentPropertyDisplayConditions as Record<string, boolean> | undefined;

    if (displayConditions) {
        lastDisplayConditionsRef.current = displayConditions;
    }

    return {
        displayConditions: displayConditions ?? lastDisplayConditionsRef.current,
        isEvaluating: queryEnabled && lastDisplayConditionsRef.current === undefined,
    };
};

export default useFormDisplayConditions;

import {useCallback, useRef, useState} from 'react';
import {ApiFetch, OptionType} from './types';
import {optionsCacheKey} from './utils';

interface UseWorkflowInputOptionsReturnType {
    loadOptions: (
        workflowUuid: string,
        inputName: string,
        propertyName: string,
        lookupDependsOnValues: Record<string, unknown>
    ) => void;
    optionsByKey: Record<string, OptionType[]>;
    resetOptions: () => void;
}

export default function useWorkflowInputOptions(
    apiFetch: ApiFetch | undefined,
    integrationInstanceId: number | undefined
): UseWorkflowInputOptionsReturnType {
    const [optionsByKey, setOptionsByKey] = useState<Record<string, OptionType[]>>({});

    const optionsByKeyRef = useRef<Record<string, OptionType[]>>({});
    const inFlightKeysRef = useRef<Set<string>>(new Set());

    const loadOptions = useCallback(
        (
            workflowUuid: string,
            inputName: string,
            propertyName: string,
            lookupDependsOnValues: Record<string, unknown>
        ) => {
            if (!apiFetch || !integrationInstanceId) {
                return;
            }

            const cacheKey = optionsCacheKey(workflowUuid, inputName, propertyName, lookupDependsOnValues);

            if (optionsByKeyRef.current[cacheKey] !== undefined || inFlightKeysRef.current.has(cacheKey)) {
                return;
            }

            inFlightKeysRef.current.add(cacheKey);

            void apiFetch<OptionType[]>(
                `/api/embedded/v1/integration-instances/${integrationInstanceId}/workflows/${workflowUuid}/options`,
                {
                    body: {inputName, lookupDependsOnValues, propertyName},
                    method: 'POST',
                }
            )
                .then((options) => {
                    optionsByKeyRef.current = {...optionsByKeyRef.current, [cacheKey]: options ?? []};

                    setOptionsByKey(optionsByKeyRef.current);
                })
                .catch((error: unknown) => {
                    console.error('Failed to load workflow input options:', (error as Error).message);
                })
                .finally(() => {
                    inFlightKeysRef.current.delete(cacheKey);
                });
        },
        [apiFetch, integrationInstanceId]
    );

    const resetOptions = useCallback(() => {
        optionsByKeyRef.current = {};

        inFlightKeysRef.current.clear();

        setOptionsByKey({});
    }, []);

    return {
        loadOptions,
        optionsByKey,
        resetOptions,
    };
}

import useDebouncedSave from '@/pages/automation/data-syncs/hooks/useDebouncedSave';
import {
    DATA_SYNC_TASK_NODE_NAME,
    PROCESSOR_COMPONENT_NAME,
    PROCESSOR_COMPONENT_VERSION,
    PROCESSOR_OPERATION_NAME,
    elementNodeName,
    findElement,
} from '@/pages/automation/data-syncs/utils/dataSyncElements';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {
    DataSync,
    DataSyncElementKind,
    useSetDataSyncElementMutation,
    useUpdateDataSyncElementMutation,
} from '@/shared/middleware/graphql';
import {WorkflowNodeOptionApi} from '@/shared/middleware/platform/configuration';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useQueryClient} from '@tanstack/react-query';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {toast} from 'sonner';

export interface FieldMappingI {
    destinationField: string;
    sourceField: string;
}

interface FieldOptionI {
    label: string;
    value: string;
}

interface UseDataSyncMappingProps {
    dataSync: Pick<DataSync, 'draftWorkflowId' | 'elements' | 'id'>;
}

/**
 * Drives the Mapping wizard step. Deliberately NOT a property form: `sourceField`/`destinationField` options
 * come from the sibling source/destination nodes, resolvable only through the workflow-node options endpoint
 * (draft workflow id + the root task node name + the processor's own node name) — the same call the original
 * Data Stream editor's auto-map used. This hook is a purpose-built two-column table over those two option
 * lists, not a rendering of the processor's property tree.
 *
 * The processor row is created lazily: it does not exist until both a source and a destination do, and it is
 * created exactly once. `processorCreationRequestedRef` guards the creation effect below against firing twice
 * — a re-render, a refetch of `dataSync`, or a slow in-flight mutation could otherwise re-enter the effect
 * before the server's new row comes back and is reflected in `processor`, which would attempt a second create
 * and hit the server's one-row-per-kind unique constraint. The ref is set synchronously before the mutation is
 * even dispatched, so there is no window where a second effect run could slip through.
 */
export default function useDataSyncMapping({dataSync}: UseDataSyncMappingProps) {
    const processor = findElement(dataSync, DataSyncElementKind.Processor);

    const [mappings, setMappings] = useState<FieldMappingI[]>(
        ((processor?.parameters as {mappings?: FieldMappingI[]} | undefined)?.mappings ?? []).map((mapping) => ({
            destinationField: mapping.destinationField ?? '',
            sourceField: mapping.sourceField ?? '',
        }))
    );
    const [sourceOptions, setSourceOptions] = useState<FieldOptionI[]>([]);
    const [destinationOptions, setDestinationOptions] = useState<FieldOptionI[]>([]);
    const [autoMapping, setAutoMapping] = useState(false);

    const processorCreationRequestedRef = useRef(false);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const queryClient = useQueryClient();

    const hasSourceAndDestination =
        !!findElement(dataSync, DataSyncElementKind.Source) && !!findElement(dataSync, DataSyncElementKind.Destination);

    const setElementMutation = useSetDataSyncElementMutation({
        onSuccess: () => invalidateDataSyncQueries(queryClient),
    });

    const updateElementMutation = useUpdateDataSyncElementMutation({
        onSuccess: () => invalidateDataSyncQueries(queryClient),
    });

    const loadOptions = useCallback(
        async (propertyName: string): Promise<FieldOptionI[]> => {
            const options = await new WorkflowNodeOptionApi().getClusterElementNodeOptions({
                clusterElementType: 'PROCESSOR',
                clusterElementWorkflowNodeName: elementNodeName(DataSyncElementKind.Processor),
                environmentId: currentEnvironmentId,
                id: dataSync.draftWorkflowId,
                propertyName,
                workflowNodeName: DATA_SYNC_TASK_NODE_NAME,
            });

            return options
                .filter((option) => option.value != null && option.value !== '')
                .map((option) => ({label: String(option.label ?? option.value), value: String(option.value)}));
        },
        [currentEnvironmentId, dataSync.draftWorkflowId]
    );

    const handleAutoMap = useCallback(async () => {
        setAutoMapping(true);

        try {
            const [sourceFields, destinationFields] = await Promise.all([
                loadOptions('mappings[0].sourceField'),
                loadOptions('mappings[0].destinationField'),
            ]);

            const destinationValues = new Set(destinationFields.map((option) => option.value));

            const matched = sourceFields
                .filter((option) => destinationValues.has(option.value))
                .map((option) => ({destinationField: option.value, sourceField: option.value}));

            if (matched.length === 0) {
                toast.info('No matching fields found between source and destination');

                return;
            }

            setMappings(matched);
        } catch {
            toast.error('Auto-map failed');
        } finally {
            setAutoMapping(false);
        }
    }, [loadOptions]);

    const handleAddMapping = useCallback(
        () => setMappings((current) => [...current, {destinationField: '', sourceField: ''}]),
        []
    );

    const handleRemoveMapping = useCallback(
        (index: number) => setMappings((current) => current.filter((_, mappingIndex) => mappingIndex !== index)),
        []
    );

    const handleMappingChange = useCallback(
        (index: number, patch: Partial<FieldMappingI>) =>
            setMappings((current) =>
                current.map((mapping, mappingIndex) => (mappingIndex === index ? {...mapping, ...patch} : mapping))
            ),
        []
    );

    // Whole map on every change; incomplete rows are kept on screen but never sent.
    const valuesToSave = useMemo(
        () =>
            processor
                ? {
                      connectionId: null,
                      id: processor.id,
                      parameters: {
                          mappings: mappings.filter((mapping) => mapping.sourceField && mapping.destinationField),
                      },
                  }
                : null,
        [mappings, processor]
    );

    useDebouncedSave(valuesToSave, (values) => {
        if (values) {
            updateElementMutation.mutate({input: values});
        }
    });

    // The processor row exists only once there is something to map between; created here, once, and the
    // regenerated draft then gives the options endpoint a processor_1 node to resolve against.
    useEffect(() => {
        if (processor || !hasSourceAndDestination || processorCreationRequestedRef.current) {
            return;
        }

        processorCreationRequestedRef.current = true;

        setElementMutation.mutate({
            input: {
                componentName: PROCESSOR_COMPONENT_NAME,
                componentVersion: PROCESSOR_COMPONENT_VERSION,
                connectionId: null,
                dataSyncId: dataSync.id,
                kind: DataSyncElementKind.Processor,
                operationName: PROCESSOR_OPERATION_NAME,
                parameters: {mappings: []},
            },
        });
    }, [dataSync.id, hasSourceAndDestination, processor, setElementMutation]);

    useEffect(() => {
        if (!processor) {
            return;
        }

        let cancelled = false;

        Promise.all([loadOptions('mappings[0].sourceField'), loadOptions('mappings[0].destinationField')])
            .then(([sourceFields, destinationFields]) => {
                if (!cancelled) {
                    setSourceOptions(sourceFields);
                    setDestinationOptions(destinationFields);
                }
            })
            .catch(() => {
                if (!cancelled) {
                    toast.error('Could not load the source and destination fields');
                }
            });

        return () => {
            cancelled = true;
        };
        // Keyed on the processor's identity only: the options never depend on its parameters, and keying on
        // the whole object would re-fetch on every mapping edit this same hook just saved.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [loadOptions, processor?.id]);

    return {
        autoMapping,
        destinationOptions,
        handleAddMapping,
        handleAutoMap,
        handleMappingChange,
        handleRemoveMapping,
        hasSourceAndDestination,
        mappings,
        processor,
        sourceOptions,
    };
}

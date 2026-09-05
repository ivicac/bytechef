import useDebouncedSave from '@/pages/automation/data-syncs/hooks/useDebouncedSave';
import {elementKindKey, findElement} from '@/pages/automation/data-syncs/utils/dataSyncElements';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {convertNameToSnakeCase} from '@/pages/platform/cluster-element-editor/utils/clusterElementsUtils';
import {
    ClusterElementDefinitionDocument,
    type ClusterElementDefinitionQuery,
    type ClusterElementDefinitionQueryVariables,
    DataSync,
    DataSyncElementKind,
    useClusterElementDefinitionQuery,
    useSetDataSyncElementMutation,
    useUpdateDataSyncElementMutation,
} from '@/shared/middleware/graphql';
import {fetcher} from '@/shared/middleware/graphqlFetcher';
import {useGetComponentDefinitionsQuery} from '@/shared/queries/automation/componentDefinitions.queries';
import {useGetWorkspaceConnectionsQuery} from '@/shared/queries/automation/connections.queries';
import {useGetComponentDefinitionQuery} from '@/shared/queries/platform/componentDefinitions.queries';
import useFormDisplayConditions from '@/shared/queries/platform/useFormDisplayConditions';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {PropertyAllType} from '@/shared/types';
import {useQueryClient} from '@tanstack/react-query';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useForm} from 'react-hook-form';
import {toast} from 'sonner';

interface UseDataSyncElementStepProps {
    dataSync: Pick<DataSync, 'elements' | 'id'>;
    kind: DataSyncElementKind;
}

interface ElementFormValuesI {
    connectionId: string | null;
    parameters: Record<string, unknown>;
}

/** Schema defaults, resolved the way ComponentConfigDialog's form hook resolves them. */
export function defaultParametersOf(properties: PropertyAllType[]): Record<string, unknown> {
    const seeded: Record<string, unknown> = {};

    for (const property of properties) {
        const propertyRecord = property as unknown as Record<string, unknown>;

        const resolvedDefault =
            propertyRecord.defaultValue ??
            propertyRecord.integerDefaultValue ??
            propertyRecord.numberDefaultValue ??
            propertyRecord.booleanDefaultValue ??
            propertyRecord.arrayDefaultValue ??
            propertyRecord.objectDefaultValue;

        if (property.name && resolvedDefault !== undefined && resolvedDefault !== null) {
            seeded[property.name] = resolvedDefault;
        }
    }

    return seeded;
}

/**
 * Drives the Source/Destination wizard step for one side (`kind`). Two operations are kept strictly apart:
 * picking an OPERATION calls {@link useSetDataSyncElementMutation}, which replaces the element row on the
 * server (and drops any existing field mapping over it — correct, since a mapping over a different operation
 * is meaningless); editing a field or the connection debounces into {@link useUpdateDataSyncElementMutation},
 * which only ever replaces this row's own `parameters`/`connectionId`. Picking a COMPONENT mutates nothing by
 * itself: `handleComponentChange` only resets the local picker state (`selectedComponentName`/
 * `selectedOperationName`) so the operation list can repopulate — the server row is untouched until an
 * operation is actually picked. Routing a field edit through the set mutation would trigger that mapping-drop
 * for no reason, so the two paths never merge.
 *
 * The candidate component list is filtered by `clusterElementsCount`, keyed by the cluster element type's
 * SCREAMING_SNAKE_CASE name (`SOURCE` / `DESTINATION`) — the same casing `WorkflowNodesPopoverMenuComponentList`
 * and `useClusterElementStep` index by, converted via `convertNameToSnakeCase`. `elementKindKey` itself returns
 * the lowercase slot name used to build workflow node identifiers (`source_1`), not this count's key. The count
 * is also only populated when the components list request carries `clusterElementDefinitions: true` — the same
 * two flags `useWorkflowLayout` passes for the workflow editor's own node browser; `actionDefinitions` alone
 * would silently drop any component that offers a source/destination cluster element but no regular action.
 *
 * `selectedDefinitionVersion` prefers the ALREADY-PERSISTED element's own `componentVersion` over the catalog's
 * current version, whenever the picker is still sitting on that same component — matching how
 * `ClusterElementProvider` below is fed `element.componentVersion` directly. A component definition can ship a
 * new version after an element was saved against an older one; querying the catalog's version instead would
 * disagree with the provider and fetch (and briefly render) the wrong version's property tree on mount. The
 * catalog version is only right for a component that has no persisted element yet — a brand-new pick.
 *
 * The picker/form reset effect near the bottom re-seeds on every transition to "no element" too, not only when
 * one appears. `DataSyncWizard` renders this component for both `kind`s at the same JSX slot with no `key`, so
 * React reuses one instance across the Source/Destination steps — the `kind` prop changes but this hook's own
 * state doesn't, unless the effect actively clears it. The effect is keyed only on the element's identity
 * (`id`/`componentName`/`operationName`), which stays `undefined` for the whole time a user is picking a
 * component/operation that has no element yet, so this reset never fires mid-pick and never clobbers an
 * in-progress selection.
 */
export default function useDataSyncElementStep({dataSync, kind}: UseDataSyncElementStepProps) {
    const element = findElement(dataSync, kind);

    const [selectedComponentName, setSelectedComponentName] = useState<string>(element?.componentName ?? '');
    const [selectedOperationName, setSelectedOperationName] = useState<string>(element?.operationName ?? '');

    // Tracks the parameters/connectionId last seeded into the form from the server (on mount, and every time
    // the re-seed effect below runs) so the debounced autosave below can tell "just loaded/created" apart from
    // "the user actually changed something" — see the effect and `valuesToSave` for the full mechanism.
    const lastSeededValuesRef = useRef<string>('');

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const queryClient = useQueryClient();

    const clusterElementTypeKey = convertNameToSnakeCase(elementKindKey(kind));

    const {data: componentDefinitions} = useGetComponentDefinitionsQuery({
        actionDefinitions: true,
        clusterElementDefinitions: true,
    });

    const candidateDefinitions = useMemo(
        () =>
            (componentDefinitions ?? []).filter(
                (definition) => (definition.clusterElementsCount?.[clusterElementTypeKey] ?? 0) > 0
            ),
        [clusterElementTypeKey, componentDefinitions]
    );

    const selectedDefinitionVersion =
        element && element.componentName === selectedComponentName
            ? element.componentVersion
            : (candidateDefinitions.find((definition) => definition.name === selectedComponentName)?.version ?? 1);

    const {data: selectedComponentDefinition} = useGetComponentDefinitionQuery(
        {componentName: selectedComponentName, componentVersion: selectedDefinitionVersion},
        !!selectedComponentName
    );

    const hasConnection = selectedComponentDefinition?.connection !== undefined;

    const operations = useMemo(
        () =>
            (selectedComponentDefinition?.clusterElements ?? []).filter(
                (clusterElement) => clusterElement.type === clusterElementTypeKey
            ),
        [clusterElementTypeKey, selectedComponentDefinition?.clusterElements]
    );

    const {data: clusterElementDefinitionData} = useClusterElementDefinitionQuery(
        {
            clusterElementName: selectedOperationName,
            componentName: selectedComponentName,
            componentVersion: selectedDefinitionVersion,
        },
        {enabled: !!selectedComponentName && !!selectedOperationName}
    );

    const properties = useMemo(
        () =>
            (clusterElementDefinitionData?.clusterElementDefinition?.properties ?? []) as unknown as PropertyAllType[],
        [clusterElementDefinitionData]
    );

    const {data: connections} = useGetWorkspaceConnectionsQuery(
        {
            componentName: element?.componentName ?? '',
            environmentId: currentEnvironmentId ?? undefined,
            id: currentWorkspaceId!,
        },
        !!element && currentWorkspaceId != null
    );

    const form = useForm<ElementFormValuesI>({
        defaultValues: {
            connectionId: element?.connectionId != null ? String(element.connectionId) : null,
            parameters: (element?.parameters ?? {}) as Record<string, unknown>,
        },
    });

    const formValues = form.watch();

    const formDisplayConditions = useFormDisplayConditions({
        componentName: element?.componentName,
        componentVersion: element?.componentVersion,
        enabled: !!element,
        operationName: element?.operationName,
        operationType: 'CLUSTER_ELEMENT',
        parameters: formValues.parameters ?? {},
    });

    const setElementMutation = useSetDataSyncElementMutation({
        onSuccess: () => invalidateDataSyncQueries(queryClient),
    });

    const updateElementMutation = useUpdateDataSyncElementMutation({
        onSuccess: () => invalidateDataSyncQueries(queryClient),
    });

    const handleComponentChange = useCallback((componentName: string) => {
        setSelectedComponentName(componentName);
        setSelectedOperationName('');
    }, []);

    // Picking an operation IS the save: the row is created (or replaced) with the schema's defaults, and the
    // facade regenerates the draft so the property form below has a node to resolve options against.
    //
    // The schema defaults come from a fetch made right here, not from `clusterElementDefinitionData` above:
    // that query is keyed on `selectedOperationName`, which `setSelectedOperationName` above only schedules —
    // it is still the PREVIOUS operation's state (or empty) for the remainder of this call. Reading it here
    // would seed the new row with the wrong operation's defaults (or none) the first time it's ever picked.
    // `queryClient.fetchQuery` resolves the definition for the operation being selected instead, the same
    // technique `useClusterElementStep`'s own handleOperationChange uses (there via `ClusterElementDefinitionApi`
    // directly; here via the same GraphQL document the render-time query above already uses).
    const handleOperationChange = useCallback(
        (operationName: string) => {
            const previousOperationName = selectedOperationName;

            setSelectedOperationName(operationName);

            const clusterElementDefinitionVariables: ClusterElementDefinitionQueryVariables = {
                clusterElementName: operationName,
                componentName: selectedComponentName,
                componentVersion: selectedDefinitionVersion,
            };

            queryClient
                .fetchQuery({
                    queryFn: fetcher<ClusterElementDefinitionQuery, ClusterElementDefinitionQueryVariables>(
                        ClusterElementDefinitionDocument,
                        clusterElementDefinitionVariables
                    ),
                    queryKey: ['clusterElementDefinition', clusterElementDefinitionVariables],
                })
                .then((definition) => {
                    setElementMutation.mutate(
                        {
                            input: {
                                componentName: selectedComponentName,
                                componentVersion: selectedDefinitionVersion,
                                connectionId: null,
                                dataSyncId: dataSync.id,
                                kind,
                                operationName,
                                parameters: defaultParametersOf(
                                    (definition.clusterElementDefinition?.properties ??
                                        []) as unknown as PropertyAllType[]
                                ),
                            },
                        },
                        {
                            onSuccess: (data) => {
                                form.reset({
                                    connectionId: null,
                                    parameters: (data.setDataSyncElement.parameters ?? {}) as Record<string, unknown>,
                                });
                            },
                        }
                    );
                })
                .catch(() => {
                    setSelectedOperationName(previousOperationName);

                    toast.error('Failed to load the operation schema.');
                });
        },
        [
            dataSync.id,
            form,
            kind,
            queryClient,
            selectedComponentName,
            selectedDefinitionVersion,
            selectedOperationName,
            setElementMutation,
        ]
    );

    const handleConnectionChange = useCallback(
        (connectionId: string | null) => {
            form.setValue('connectionId', connectionId, {shouldDirty: true});
        },
        [form]
    );

    // Whole map, every time: updateDataSyncElement replaces the row's parameters rather than merging.
    //
    // Deliberately NOT memoized: react-hook-form's `formValues.parameters` is the same object reference across
    // renders (RHF mutates it in place), so a `useMemo` keyed on it would never see its dependency change and
    // would go on returning the object it cached on mount — `useDebouncedSave`'s own effect (keyed on this
    // value by reference) would then never re-run at all, silently freezing the autosave after the very first
    // render. Building a fresh object every render is exactly what useDebouncedSave's own doc comment expects
    // ("every caller hands over a freshly built object") and what makes its internal JSON comparison the only
    // thing deciding whether a save is actually new.
    const candidateValuesToSave = element
        ? {
              connectionId: formValues.connectionId,
              id: element.id,
              parameters: formValues.parameters ?? {},
          }
        : null;

    // Collapsed to null whenever it matches what was last seeded from the server (mount, or the re-seed effect
    // below) — otherwise the moment `useSetDataSyncElementMutation` creates a brand-new element, its own
    // freshly-written values would read back as "new" to `useDebouncedSave` (whose baseline was captured while
    // no element existed at all) and fire a redundant `updateDataSyncElement` a few hundred milliseconds later.
    // Passing null instead of the just-seeded object cancels that: `useDebouncedSave`'s effect re-runs on this
    // very next render (its dependency array sees the value change again) and its cleanup clears the
    // about-to-fire timeout before it ever reaches the mutation — see `useDebouncedSave`'s own cleanup.
    const valuesToSave =
        candidateValuesToSave && JSON.stringify(candidateValuesToSave) === lastSeededValuesRef.current
            ? null
            : candidateValuesToSave;

    useDebouncedSave(valuesToSave, (values) => {
        if (values) {
            updateElementMutation.mutate({input: values});
        }
    });

    // A row replaced from outside (component change, reload) — or the disappearance of one, which happens when
    // this same hook instance is reused for the OTHER kind (see the function doc) — re-seeds the pickers and
    // the form either way, instead of only handling the "element appeared" half.
    useEffect(() => {
        if (!element) {
            setSelectedComponentName('');
            setSelectedOperationName('');

            form.reset({connectionId: null, parameters: {}});

            lastSeededValuesRef.current = '';

            return;
        }

        setSelectedComponentName(element.componentName);
        setSelectedOperationName(element.operationName);

        const seededConnectionId = element.connectionId != null ? String(element.connectionId) : null;
        const seededParameters = (element.parameters ?? {}) as Record<string, unknown>;

        form.reset({
            connectionId: seededConnectionId,
            parameters: seededParameters,
        });

        lastSeededValuesRef.current = JSON.stringify({
            connectionId: seededConnectionId,
            id: element.id,
            parameters: seededParameters,
        });
        // Only the row identity matters here; re-running on every parameters change would fight the autosave.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [element?.id, element?.componentName, element?.operationName]);

    return {
        candidateDefinitions,
        connections: connections ?? [],
        element,
        form,
        formDisplayConditions,
        handleComponentChange,
        handleConnectionChange,
        handleOperationChange,
        hasConnection,
        operations,
        properties,
        selectedComponentName,
        selectedOperationName,
    };
}

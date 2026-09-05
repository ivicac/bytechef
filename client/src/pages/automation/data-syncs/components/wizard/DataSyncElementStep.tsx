import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/Select/Select';
import useDataSyncElementStep from '@/pages/automation/data-syncs/hooks/useDataSyncElementStep';
import {ClusterElementProvider} from '@/pages/platform/workflow-editor/components/properties/ClusterElementContext';
import Properties from '@/pages/platform/workflow-editor/components/properties/Properties';
import {WorkflowMockProvider} from '@/pages/platform/workflow-editor/providers/workflowEditorProvider';
import {DataSync, DataSyncElementKind} from '@/shared/middleware/graphql';

interface DataSyncElementStepProps {
    dataSync: DataSync;
    kind: DataSyncElementKind;
}

const COPY: Record<DataSyncElementKind, {description: string; title: string}> = {
    [DataSyncElementKind.Destination]: {
        description: 'Choose where the records go and configure its connection and parameters.',
        title: 'Select Destination',
    },
    [DataSyncElementKind.Processor]: {description: '', title: ''},
    [DataSyncElementKind.Source]: {
        description: 'Choose a data source component and configure its connection and parameters.',
        title: 'Select Source',
    },
};

/**
 * Source/Destination wizard step, parameterised by `kind`. Renders three pickers (component, operation,
 * connection) plus the operation's real property form once an element exists — the property form is the
 * same `Properties` renderer + `ClusterElementProvider` + `WorkflowMockProvider` arrangement
 * `ComponentConfigDialog` uses to host a component's dynamically-resolved inputs outside the workflow editor.
 * All state and persistence live in `useDataSyncElementStep`; this component only renders it.
 *
 * The connection picker is gated on `hasConnection` (the selected component definition actually declaring a
 * connection), the same condition the workflow node details panel checks
 * (`currentComponentDefinition?.connection !== undefined` in `WorkflowNodeDetailsPanel`) before showing its own
 * Connection tab — a connection-less component (`csvFile`, say) would otherwise show a dropdown that can never
 * hold anything. Its `onValueChange` is wired straight to `handleConnectionChange` rather than through a
 * `value === '' ? null : value` guard: a Radix `Select` only ever calls `onValueChange` with the value of an
 * actually-selected `SelectItem`, never `''` (empty-string item values are disallowed), so that branch was
 * unreachable dead code, not a real clearing path. Dropped rather than "fixed", since a data sync's connection
 * isn't a field the wizard exposes a way to unset once chosen — it isn't clear that it should be given a
 * component that declares a connection almost always needs one — and that's a UX call worth its own follow-up
 * rather than a silent addition here.
 *
 * `hasConnection` is derived from `selectedComponentName` (the component the user is CURRENTLY picking), while
 * `element` is the previously SAVED row — picking an operation is the only thing that replaces it. Between
 * choosing a new component and choosing its operation, those two disagree: the select would render gated only
 * on the OLD element's saved connection value while reflecting the NEW component's `hasConnection`. The extra
 * `element.componentName === selectedComponentName` guard below closes that window, so the picker never shows a
 * stale component's connection under a newly-picked one's label.
 */
export default function DataSyncElementStep({dataSync, kind}: DataSyncElementStepProps) {
    const {
        candidateDefinitions,
        connections,
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
    } = useDataSyncElementStep({dataSync, kind});

    const idPrefix = kind.toLowerCase();
    const formValues = form.watch();

    return (
        <div className="space-y-6 py-4">
            <div>
                <h2 className="text-lg font-semibold">{COPY[kind].title}</h2>

                <p className="mt-1 text-sm text-muted-foreground">{COPY[kind].description}</p>
            </div>

            <fieldset className="flex flex-col gap-2 border-0 p-0">
                <label className="text-sm font-medium" htmlFor={`${idPrefix}-component-select`}>
                    Component
                </label>

                <Select onValueChange={handleComponentChange} value={selectedComponentName}>
                    <SelectTrigger id={`${idPrefix}-component-select`}>
                        <SelectValue placeholder="Select a component..." />
                    </SelectTrigger>

                    <SelectContent>
                        {candidateDefinitions.map((definition) => (
                            <SelectItem key={definition.name} value={definition.name}>
                                {definition.title || definition.name}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            </fieldset>

            {selectedComponentName && operations.length > 0 && (
                <fieldset className="flex flex-col gap-2 border-0 p-0">
                    <label className="text-sm font-medium" htmlFor={`${idPrefix}-operation-select`}>
                        Operation
                    </label>

                    <Select onValueChange={handleOperationChange} value={selectedOperationName}>
                        <SelectTrigger id={`${idPrefix}-operation-select`}>
                            <SelectValue placeholder="Select an operation..." />
                        </SelectTrigger>

                        <SelectContent>
                            {operations.map((operation) => (
                                <SelectItem key={operation.name} value={operation.name}>
                                    {operation.title || operation.name}
                                </SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </fieldset>
            )}

            {element && element.componentName === selectedComponentName && hasConnection && (
                <fieldset className="flex flex-col gap-2 border-0 p-0">
                    <label className="text-sm font-medium" htmlFor={`${idPrefix}-connection-select`}>
                        Connection
                    </label>

                    <Select onValueChange={handleConnectionChange} value={formValues.connectionId ?? ''}>
                        <SelectTrigger id={`${idPrefix}-connection-select`}>
                            <SelectValue placeholder="Select a connection..." />
                        </SelectTrigger>

                        <SelectContent>
                            {connections.map((connection) => (
                                <SelectItem key={connection.id} value={String(connection.id)}>
                                    {connection.name}
                                </SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </fieldset>
            )}

            {element && properties.length > 0 && (
                <WorkflowMockProvider>
                    <ClusterElementProvider
                        value={{
                            clusterElementName: element.operationName,
                            componentName: element.componentName,
                            componentVersion: element.componentVersion,
                            connectionId: formValues.connectionId != null ? Number(formValues.connectionId) : undefined,
                            inputParameters: formValues.parameters ?? {},
                        }}
                    >
                        <Properties
                            control={form.control}
                            controlPath="parameters"
                            customClassName="p-0"
                            formDisplayConditions={formDisplayConditions}
                            formState={form.formState}
                            properties={properties}
                        />
                    </ClusterElementProvider>
                </WorkflowMockProvider>
            )}
        </div>
    );
}

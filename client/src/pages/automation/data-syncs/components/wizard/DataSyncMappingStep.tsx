import Button from '@/components/Button/Button';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/Select/Select';
import useDataSyncMapping from '@/pages/automation/data-syncs/hooks/useDataSyncMapping';
import {DataSync} from '@/shared/middleware/graphql';
import {PlusIcon, SparklesIcon, Trash2Icon} from 'lucide-react';
import {useMemo} from 'react';

interface DataSyncMappingStepProps {
    dataSync: DataSync;
}

/**
 * The Mapping wizard step. Deliberately not a rendering of the processor's property form — see
 * useDataSyncMapping's own doc comment for why the field options must come from the workflow-node options
 * endpoint instead. This component is a two-column table over the two option lists that hook resolves.
 *
 * Some source/destination component pairs never auto-detect any fields at all (the field mapper's own
 * `dynamicProperties` declaration exists for exactly this case). Rendering the usual pickers for such a pair
 * would show two permanently-empty dropdowns with no explanation, and auto-map's "no matching fields" toast
 * would read as if the fields merely failed to line up rather than never having been discoverable in the
 * first place — there is nowhere else in this step to fix that. `fieldsUnavailable` catches it: both option
 * lists finished loading (`!optionsLoading`), the fetch did not itself fail (`!optionsLoadFailed`), and both
 * came back empty. It intentionally does NOT fire on a PARTIAL result (one side populated, the other not) —
 * that is a different, narrower situation this fix does not attempt to characterize on its own. A REJECTED
 * fetch is a distinct, transient situation from a genuinely capability-less pair, and is worded as such —
 * saying "these fields aren't supported" about a network error would contradict the error toast firing right
 * beside it.
 */
export default function DataSyncMappingStep({dataSync}: DataSyncMappingStepProps) {
    const {
        autoMapping,
        destinationOptions,
        handleAddMapping,
        handleAutoMap,
        handleMappingChange,
        handleRemoveMapping,
        hasSourceAndDestination,
        mappings,
        optionsLoadFailed,
        optionsLoading,
        processor,
        sourceOptions,
    } = useDataSyncMapping({dataSync});

    const fieldsUnavailable = useMemo(
        () => !optionsLoading && !optionsLoadFailed && sourceOptions.length === 0 && destinationOptions.length === 0,
        [destinationOptions, optionsLoadFailed, optionsLoading, sourceOptions]
    );

    return (
        <div className="space-y-6 py-4">
            <div>
                <h2 className="text-lg font-semibold">Field Mapping</h2>

                <p className="mt-1 text-sm text-muted-foreground">
                    Map each source field to the destination field it should fill.
                </p>
            </div>

            {!hasSourceAndDestination && (
                <p className="text-sm text-muted-foreground">Configure a source and a destination first.</p>
            )}

            {processor && optionsLoading && <p className="text-sm text-muted-foreground">Loading available fields…</p>}

            {processor && !optionsLoading && optionsLoadFailed && (
                <p className="text-sm text-muted-foreground">
                    Couldn&apos;t load the source and destination fields. Try again in a moment.
                </p>
            )}

            {processor && !optionsLoading && !optionsLoadFailed && fieldsUnavailable && (
                <p className="text-sm text-muted-foreground">
                    This source and destination don&apos;t expose their fields automatically, so mapping them isn&apos;t
                    supported here yet.
                </p>
            )}

            {processor && !optionsLoading && !optionsLoadFailed && !fieldsUnavailable && (
                <>
                    <div className="flex items-center gap-2">
                        <Button
                            disabled={autoMapping}
                            icon={<SparklesIcon />}
                            label={autoMapping ? 'Mapping...' : 'Auto-map matching fields'}
                            onClick={handleAutoMap}
                            size="sm"
                            variant="outline"
                        />

                        <Button
                            icon={<PlusIcon />}
                            label="Add mapping"
                            onClick={handleAddMapping}
                            size="sm"
                            variant="ghost"
                        />
                    </div>

                    <fieldset className="space-y-2 border-0">
                        {mappings.map((mapping, index) => (
                            <div className="flex items-center gap-2" key={index}>
                                <Select
                                    onValueChange={(value) => handleMappingChange(index, {sourceField: value})}
                                    value={mapping.sourceField}
                                >
                                    <SelectTrigger aria-label={`Source field ${index + 1}`}>
                                        <SelectValue placeholder="Source field" />
                                    </SelectTrigger>

                                    <SelectContent>
                                        {sourceOptions.map((option) => (
                                            <SelectItem key={option.value} value={option.value}>
                                                {option.label}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>

                                <span className="text-sm text-muted-foreground">to</span>

                                <Select
                                    onValueChange={(value) => handleMappingChange(index, {destinationField: value})}
                                    value={mapping.destinationField}
                                >
                                    <SelectTrigger aria-label={`Destination field ${index + 1}`}>
                                        <SelectValue placeholder="Destination field" />
                                    </SelectTrigger>

                                    <SelectContent>
                                        {destinationOptions.map((option) => (
                                            <SelectItem key={option.value} value={option.value}>
                                                {option.label}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>

                                <Button
                                    aria-label={`Remove mapping ${index + 1}`}
                                    icon={<Trash2Icon />}
                                    onClick={() => handleRemoveMapping(index)}
                                    size="icon"
                                    variant="ghost"
                                />
                            </div>
                        ))}
                    </fieldset>
                </>
            )}
        </div>
    );
}

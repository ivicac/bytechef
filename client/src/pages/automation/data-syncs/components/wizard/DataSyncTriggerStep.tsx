import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/Select/Select';
import {Label} from '@/components/ui/label';
import {RadioGroup, RadioGroupItem} from '@/components/ui/radio-group';
import AgentScheduleFrequencyFields from '@/pages/automation/agents/components/detail/AgentScheduleFrequencyFields';
import {
    AgentScheduleCadenceI,
    SCHEDULE_FREQUENCY_KINDS,
    SCHEDULE_FREQUENCY_LABELS,
    ScheduleFrequencyKindType,
    fromCadenceParameters,
    toCadenceParameters,
    toCronExpression,
    validateAgentScheduleCadence,
} from '@/pages/automation/agents/utils/agentScheduleCron';
import useDebouncedSave from '@/pages/automation/data-syncs/hooks/useDebouncedSave';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {DataSync, DataSyncTriggerType, useUpdateDataSyncTriggerMutation} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {useMemo, useRef, useState} from 'react';

interface DataSyncTriggerStepProps {
    dataSync: Pick<DataSync, 'id' | 'triggerParameters' | 'triggerType'>;
}

const DEFAULT_CADENCE: AgentScheduleCadenceI = {frequencyKind: 'DAILY', timeOfDay: '09:00'};

/**
 * Step 1 of the wizard: run the sync on demand, or on a schedule.
 *
 * Switching to Manual saves immediately, since there is nothing further to configure. Switching to Scheduled
 * saves immediately too, mirroring that — as long as the current cadence already passes
 * validateAgentScheduleCadence — instead of relying on the debounced path below for that first write. Without
 * this, toggling Scheduled -> Manual -> Scheduled within the 600ms debounce window left the row MANUAL on the
 * server forever: useDebouncedSave's baseline is only ever updated when a scheduled save actually FIRES (see
 * its own doc comment), so cancelling the Manual save's own pending (no-op) timeout by switching back to
 * Scheduled restored a value equal to that still-stale baseline, which read as "no change" and silently
 * dropped the save. Writing the toggle-to-Scheduled case immediately sidesteps that comparison entirely
 * instead of trying to make it aware of every revert.
 *
 * `lastImmediateWriteRef` then collapses the debounced value to null for exactly the render right after that
 * immediate write (the same technique useDataSyncElementStep uses for its own post-mutation echo — see its
 * `candidateValuesToSave`/`valuesToSave` split), so useDebouncedSave does not also fire a redundant, otherwise
 * harmless duplicate save for the value that was just persisted. An invalid cadence still never saves at all:
 * scheduleToSave/candidateScheduleToSave are null in that case, and the debounced path is untouched for every
 * subsequent cadence edit.
 */
export default function DataSyncTriggerStep({dataSync}: DataSyncTriggerStepProps) {
    const storedParameters = (dataSync.triggerParameters ?? {}) as Record<string, unknown>;

    const [cadence, setCadence] = useState<AgentScheduleCadenceI>(
        dataSync.triggerType === DataSyncTriggerType.Schedule
            ? fromCadenceParameters(storedParameters)
            : DEFAULT_CADENCE
    );
    const [timezone, setTimezone] = useState<string>(String(storedParameters.timezone ?? 'UTC'));
    const [triggerType, setTriggerType] = useState<DataSyncTriggerType>(dataSync.triggerType);

    const lastImmediateWriteRef = useRef<string>('');

    const queryClient = useQueryClient();

    const updateTriggerMutation = useUpdateDataSyncTriggerMutation({
        onSuccess: () => invalidateDataSyncQueries(queryClient),
    });

    const timezones = useMemo(() => Intl.supportedValuesOf('timeZone'), []);

    const cadenceErrors = useMemo(() => validateAgentScheduleCadence(cadence), [cadence]);

    // Only a valid schedule is worth saving; an invalid one stays on screen with its errors until fixed.
    const candidateScheduleToSave = useMemo(() => {
        if (triggerType !== DataSyncTriggerType.Schedule || Object.keys(cadenceErrors).length > 0) {
            return null;
        }

        return {...toCadenceParameters(cadence), expression: toCronExpression(cadence), timezone};
    }, [cadence, cadenceErrors, timezone, triggerType]);

    // Collapsed to null whenever it matches the value an immediate toggle-to-Scheduled write (below) just
    // persisted — see this component's own doc comment for why that write exists and why this collapse keeps
    // it from fighting the debounced path.
    const scheduleToSave =
        candidateScheduleToSave && JSON.stringify(candidateScheduleToSave) === lastImmediateWriteRef.current
            ? null
            : candidateScheduleToSave;

    useDebouncedSave(scheduleToSave, (triggerParameters) => {
        if (triggerParameters) {
            updateTriggerMutation.mutate({
                input: {id: dataSync.id, triggerParameters, triggerType: DataSyncTriggerType.Schedule},
            });
        }
    });

    const handleTriggerTypeChange = (value: string) => {
        const nextTriggerType = value as DataSyncTriggerType;

        setTriggerType(nextTriggerType);

        if (nextTriggerType === DataSyncTriggerType.Manual) {
            updateTriggerMutation.mutate({
                input: {id: dataSync.id, triggerParameters: null, triggerType: DataSyncTriggerType.Manual},
            });

            return;
        }

        if (Object.keys(cadenceErrors).length > 0) {
            return;
        }

        const scheduleParameters = {...toCadenceParameters(cadence), expression: toCronExpression(cadence), timezone};

        lastImmediateWriteRef.current = JSON.stringify(scheduleParameters);

        updateTriggerMutation.mutate({
            input: {id: dataSync.id, triggerParameters: scheduleParameters, triggerType: DataSyncTriggerType.Schedule},
        });
    };

    return (
        <div className="space-y-6 py-4">
            <div>
                <h2 className="text-lg font-semibold">Trigger</h2>

                <p className="mt-1 text-sm text-muted-foreground">
                    Run the sync on demand from its deployment, or on a schedule.
                </p>
            </div>

            <RadioGroup onValueChange={handleTriggerTypeChange} value={triggerType}>
                <div className="flex items-center gap-2">
                    <RadioGroupItem id="data-sync-trigger-manual" value={DataSyncTriggerType.Manual} />

                    <Label htmlFor="data-sync-trigger-manual">Manual</Label>
                </div>

                <div className="flex items-center gap-2">
                    <RadioGroupItem id="data-sync-trigger-schedule" value={DataSyncTriggerType.Schedule} />

                    <Label htmlFor="data-sync-trigger-schedule">Scheduled</Label>
                </div>
            </RadioGroup>

            {triggerType === DataSyncTriggerType.Schedule && (
                <fieldset className="space-y-4 border-0 p-0">
                    <div className="flex flex-col gap-2">
                        <Label htmlFor="data-sync-frequency">Frequency</Label>

                        <Select
                            onValueChange={(value) =>
                                setCadence({frequencyKind: value as ScheduleFrequencyKindType, timeOfDay: '09:00'})
                            }
                            value={cadence.frequencyKind}
                        >
                            <SelectTrigger id="data-sync-frequency">
                                <SelectValue />
                            </SelectTrigger>

                            <SelectContent>
                                {SCHEDULE_FREQUENCY_KINDS.map((kind) => (
                                    <SelectItem key={kind} value={kind}>
                                        {SCHEDULE_FREQUENCY_LABELS[kind]}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <AgentScheduleFrequencyFields cadence={cadence} errors={cadenceErrors} onChange={setCadence} />

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="data-sync-timezone">Timezone</Label>

                        <Select onValueChange={setTimezone} value={timezone}>
                            <SelectTrigger id="data-sync-timezone">
                                <SelectValue placeholder="Choose a timezone…" />
                            </SelectTrigger>

                            <SelectContent>
                                {timezones.map((zone) => (
                                    <SelectItem key={zone} value={zone}>
                                        {zone}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>
                </fieldset>
            )}
        </div>
    );
}

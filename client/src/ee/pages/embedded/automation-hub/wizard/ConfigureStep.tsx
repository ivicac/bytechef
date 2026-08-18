import {Input} from '@/components/Input/Input';
import {Label} from '@/components/ui/label';
import {ActivationActionType, ActivationStateI} from '@/ee/pages/embedded/automation-hub/wizard/activationReducer';
import {Dispatch} from 'react';

interface ConfigureStepProps {
    dispatch: Dispatch<ActivationActionType>;
    state: ActivationStateI;
}

const INPUT_TYPE_ATTRIBUTES: Record<string, string> = {
    DATE: 'date',
    DATE_TIME: 'datetime-local',
    EMAIL: 'email',
    INTEGER: 'number',
    NUMBER: 'number',
    TIME: 'time',
    URL: 'url',
};

/**
 * The values the template asks its user for, one field per declared workflow input.
 *
 * Only reached when the template declares inputs — `initialActivationState` skips straight past it
 * otherwise, the same way the connect step is skipped when no component needs a connection. Nothing
 * is written here: the answers ride on the reducer until Activate publishes the automation and then
 * stores them on the project deployment that publishing created.
 */
const ConfigureStep = ({dispatch, state}: ConfigureStepProps) => (
    <div className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">This automation needs a few details before it can run.</p>

        {state.inputs.map((input) => (
            <div className="flex flex-col gap-1.5" key={input.name}>
                <Label htmlFor={`activation-input-${input.name}`}>
                    {input.label || input.name}

                    {input.required && <span className="ml-0.5 text-destructive">*</span>}
                </Label>

                <Input
                    id={`activation-input-${input.name}`}
                    onChange={(event) =>
                        dispatch({name: input.name, type: 'SET_INPUT_VALUE', value: event.target.value})
                    }
                    required={input.required}
                    type={INPUT_TYPE_ATTRIBUTES[input.type ?? ''] ?? 'text'}
                    value={`${state.inputValues[input.name] ?? ''}`}
                />
            </div>
        ))}
    </div>
);

export default ConfigureStep;

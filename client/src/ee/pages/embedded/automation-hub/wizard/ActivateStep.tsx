import SelectedConnectionsList from '@/ee/pages/embedded/automation-hub/wizard/SelectedConnectionsList';
import {ActivationStateI} from '@/ee/pages/embedded/automation-hub/wizard/activationReducer';
import {AutomationWorkflowProjectWorkflowTemplate} from '@/ee/shared/middleware/embedded/public';
import {CircleCheckIcon} from 'lucide-react';

interface ActivateStepProps {
    busy: boolean;
    state: ActivationStateI;
    template: AutomationWorkflowProjectWorkflowTemplate;
}

/**
 * The final step: what is about to be switched on, and — once the reducer reaches `done` — the
 * success screen. The Activate/Done/Open in builder buttons live in the wizard footer, not here.
 *
 * It also carries the template description and the note about fine-tuning in the builder, which
 * belonged to the removed Configure step (see `ActivationWizard`) — that step showed the same
 * review one click earlier and wrote nothing, so its content moved here rather than being lost.
 *
 * `busy` is worth a line of its own here because Activate is where every request this wizard makes
 * now happens — copy, read back, one wiring PUT per connected node, publish, enable — so the wait
 * is long enough that a merely disabled button reads as a click that did not land.
 */
const ActivateStep = ({busy, state, template}: ActivateStepProps) => {
    if (state.step === 'done') {
        return (
            <div className="flex flex-col items-center gap-2 py-8 text-center">
                <CircleCheckIcon className="size-8 text-(--hub-enable)" />

                <p className="text-base font-medium">Your automation is running</p>

                <p className="text-sm text-muted-foreground">{template.label} is now active.</p>
            </div>
        );
    }

    return (
        <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
                <h3 className="text-sm font-medium">Automation</h3>

                <p className="text-sm text-muted-foreground">{template.label}</p>

                {template.description && <p className="text-sm text-muted-foreground">{template.description}</p>}
            </div>

            {state.requiredComponents.length > 0 && (
                <div className="flex flex-col gap-2">
                    <h3 className="text-sm font-medium">Connected accounts</h3>

                    <SelectedConnectionsList state={state} template={template} />
                </div>
            )}

            {busy && <p className="text-sm text-muted-foreground">Setting up your automation…</p>}
        </div>
    );
};

export default ActivateStep;

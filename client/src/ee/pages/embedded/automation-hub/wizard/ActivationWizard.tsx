import Button from '@/components/Button/Button';
import LoadingDots from '@/components/LoadingDots';
import {Alert, AlertDescription} from '@/components/ui/alert';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {useAutomationHubStore} from '@/ee/pages/embedded/automation-hub/stores/useAutomationHubStore';
import ActivateStep from '@/ee/pages/embedded/automation-hub/wizard/ActivateStep';
import ConfigureStep from '@/ee/pages/embedded/automation-hub/wizard/ConfigureStep';
import ConnectAccountsStep from '@/ee/pages/embedded/automation-hub/wizard/ConnectAccountsStep';
import {ActivationStepType, canProceed} from '@/ee/pages/embedded/automation-hub/wizard/activationReducer';
import {useActivationFlow, useRequiredComponents} from '@/ee/pages/embedded/automation-hub/wizard/useActivationFlow';
import {
    AutomationWorkflowProjectKindEnum,
    AutomationWorkflowProjectWorkflowTemplate,
} from '@/ee/shared/middleware/embedded/public';
import {useMemo} from 'react';

const REQUIRED_COMPONENTS_ERROR_MESSAGE = 'This automation could not be set up. Please try again.';

const STEPS: {label: string; step: ActivationStepType}[] = [
    {label: 'Connect', step: 'connect'},
    {label: 'Configure', step: 'configure'},
    {label: 'Activate', step: 'activate'},
];

interface ActivationWizardContentProps {
    kind: AutomationWorkflowProjectKindEnum;
    onClose: () => void;
    requiredComponents: string[];
    template: AutomationWorkflowProjectWorkflowTemplate;
}

const ActivationWizardContent = ({kind, onClose, requiredComponents, template}: ActivationWizardContentProps) => {
    const {activate, busy, dispatch, editWorkflow, openInBuilder, state} = useActivationFlow(
        template,
        kind,
        requiredComponents
    );

    // Which steps this template actually has. A step nobody can answer is dropped: connect when no
    // component needs a connection, configure when the template declares no inputs. There is no
    // progress indicator -- the dialog is small enough that the footer's Back/Next/Activate say
    // where you are -- but the list still decides whether Back has anywhere to go.
    const steps = useMemo(
        () =>
            STEPS.filter(
                ({step}) =>
                    (step !== 'connect' || requiredComponents.length > 0) &&
                    (step !== 'configure' || state.inputs.length > 0)
            ),
        [requiredComponents.length, state.inputs.length]
    );

    // A fresh missing-connection highlight supersedes any earlier failure message: the reducer
    // deliberately leaves `error` in place on MISSING_CONNECTION, so rendering both would put a
    // stale banner next to the very row the user is being asked to fix. The message is likewise
    // hidden while a retry is in flight.
    const errorShown = !!state.error && !state.highlightedComponent && !busy;

    const editWorkflowAllowed = useAutomationHubStore((state) => state.editWorkflowAllowed);

    const backShown = state.step !== 'connect' && steps[0]?.step !== state.step;

    return (
        <>
            {errorShown && (
                <Alert variant="destructive">
                    <AlertDescription>{state.error}</AlertDescription>
                </Alert>
            )}

            {state.step === 'connect' && <ConnectAccountsStep dispatch={dispatch} state={state} template={template} />}

            {state.step === 'configure' && <ConfigureStep dispatch={dispatch} state={state} />}

            {(state.step === 'activate' || state.step === 'done') && (
                <ActivateStep busy={busy} state={state} template={template} />
            )}

            <DialogFooter>
                {state.step === 'done' ? (
                    <>
                        {kind === 'COPY' && editWorkflowAllowed && (
                            <Button label="Open in builder" onClick={openInBuilder} variant="outline" />
                        )}

                        <Button label="Done" onClick={onClose} />
                    </>
                ) : (
                    <>
                        {kind === 'COPY' && editWorkflowAllowed && (
                            <Button disabled={busy} label="Edit workflow" onClick={editWorkflow} variant="outline" />
                        )}

                        {backShown && (
                            <Button
                                disabled={busy}
                                label="Back"
                                onClick={() => dispatch({type: 'BACK'})}
                                variant="outline"
                            />
                        )}

                        {state.step === 'activate' ? (
                            <Button disabled={busy} label="Activate" onClick={activate} />
                        ) : (
                            <Button
                                disabled={busy || !canProceed(state)}
                                label="Next"
                                onClick={() => dispatch({type: 'NEXT'})}
                            />
                        )}
                    </>
                )}
            </DialogFooter>
        </>
    );
};

interface ActivationWizardProps {
    kind: AutomationWorkflowProjectKindEnum;
    onClose: () => void;
    template: AutomationWorkflowProjectWorkflowTemplate;
}

/**
 * The activation wizard: connect accounts → configure → activate, with either of the first two
 * dropped when the template gives it nothing to ask. A template that needs no connections and
 * declares no inputs opens straight on Activate.
 *
 * Configure earns its place only now that there is somewhere to put the answers: it used to render
 * the same review as the Activate step because the public API had no endpoint that persisted
 * per-user input values. `updateFrontendProjectWorkflowInputs` is that endpoint, and Activate calls
 * it after publishing — the values live on the project deployment publishing creates.
 *
 * The required-component lookup is resolved BEFORE the flow mounts, because the reducer's initial
 * step is derived from it exactly once: mounting the flow against a still-loading (and therefore
 * empty) list would skip the connect step for a template that needs it. A lookup that has NEVER
 * produced data is just as disqualifying and gets the same treatment for the same reason — an
 * empty list would skip the connect step, `buildWiringRequests` would return nothing, and Activate
 * would publish and enable a copy with no connections wired onto it.
 *
 * `isError` (see `useRequiredComponents`) is what gates this, not the query's raw `error`: a
 * background refetch failure on an already-open wizard (the query has a 5-minute `staleTime`, and
 * `HubConnectionDialog` mounts a second observer of the same key) must leave the open wizard
 * alone rather than unmount `ActivationWizardContent` mid-flight — that would drop the reducer
 * state and `copiedWorkflowUuidRef`, so a subsequent "Try again" would copy the template a second
 * time.
 *
 * Closing the dialog before Activate leaves nothing behind: the wizard writes only inside the
 * Activate click, and a failure there rolls back whatever it managed to create. The one exception
 * is a copy made by "Edit workflow", which the user asked for explicitly.
 */
const ActivationWizard = ({kind, onClose, template}: ActivationWizardProps) => {
    const {isError, isLoading, refetch, requiredComponents} = useRequiredComponents(template);

    return (
        <Dialog onOpenChange={(open) => !open && onClose()} open>
            <DialogContent className="sm:max-w-xl" showCloseButton>
                <DialogHeader>
                    <DialogTitle>{template.label}</DialogTitle>

                    <DialogDescription>Set this automation up and switch it on.</DialogDescription>
                </DialogHeader>

                {isLoading && (
                    <div className="flex justify-center py-8" data-testid="activation-wizard-loading">
                        <LoadingDots />
                    </div>
                )}

                {!isLoading && isError && (
                    <div className="flex flex-col gap-3" data-testid="activation-wizard-error">
                        <Alert variant="destructive">
                            <AlertDescription>{REQUIRED_COMPONENTS_ERROR_MESSAGE}</AlertDescription>
                        </Alert>

                        <div className="flex justify-end">
                            <Button label="Try again" onClick={() => refetch()} variant="outline" />
                        </div>
                    </div>
                )}

                {!isLoading && !isError && (
                    <ActivationWizardContent
                        kind={kind}
                        onClose={onClose}
                        requiredComponents={requiredComponents}
                        template={template}
                    />
                )}
            </DialogContent>
        </Dialog>
    );
};

export default ActivationWizard;

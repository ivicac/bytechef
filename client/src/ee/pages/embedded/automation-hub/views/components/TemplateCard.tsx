import Button from '@/components/Button/Button';
import DeleteAlertDialog from '@/components/DeleteAlertDialog';
import {Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle} from '@/components/ui/card';
import {SetAutomationEnabledRequestI} from '@/ee/pages/embedded/automation-hub/mutations/automationHub.mutations';
import {removeAutomation} from '@/ee/pages/embedded/automation-hub/utils/removeAutomation';
import AutomationCardMenu from '@/ee/pages/embedded/automation-hub/views/components/AutomationCardMenu';
import AutomationInputsDialog from '@/ee/pages/embedded/automation-hub/views/components/AutomationInputsDialog';
import AutomationStatusButton from '@/ee/pages/embedded/automation-hub/views/components/AutomationStatusButton';
import AutomationVersion from '@/ee/pages/embedded/automation-hub/views/components/AutomationVersion';
import {
    AutomationWorkflowProjectWorkflowTemplate,
    ConnectedUserProjectWorkflow,
} from '@/ee/shared/middleware/embedded/public';
import {useState} from 'react';
import InlineSVG from 'react-inlinesvg';
import {useNavigate} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';

interface TemplateCardProps {
    activationDisabled?: boolean;
    automation?: ConnectedUserProjectWorkflow;
    onDeleteAutomation: (workflowUuid: string) => Promise<unknown>;
    onDeprovisionReference: (workflowUuid: string) => Promise<unknown>;
    onSetEnabled: (request: SetAutomationEnabledRequestI) => void;
    onUseTemplate: () => void;
    template: AutomationWorkflowProjectWorkflowTemplate;
}

/**
 * One catalog template, carrying its own usage state: an unused template offers "Use", while one
 * the connected user has already activated shows its Active/Disabled status button and an overflow
 * menu in the card's top-right corner. An activated, running template is tinted green, so a glance
 * across the grid says which automations are live.
 *
 * Customize is offered for a COPY only — a REFERENCE points at a shared catalog workflow that the
 * user must never edit. Remove is offered for both, and branches through `removeAutomation`.
 *
 * `activationDisabled` blanks the activation affordance while the automations query is failing:
 * without knowing what the user already has, an activated template would render as unused and a
 * click would silently create a second copy.
 */
const TemplateCard = ({
    activationDisabled,
    automation,
    onDeleteAutomation,
    onDeprovisionReference,
    onSetEnabled,
    onUseTemplate,
    template,
}: TemplateCardProps) => {
    const [inputsDialogOpen, setInputsDialogOpen] = useState(false);
    const [removeDialogOpen, setRemoveDialogOpen] = useState(false);
    const [removing, setRemoving] = useState(false);

    const navigate = useNavigate();

    // Once the viewer has activated this template, the card is about THEIR automation, so it shows
    // the label and description they last saved -- renaming a copy in the builder and seeing the
    // catalog's original text on the card reads as the rename not having taken.
    const label = automation?.label || template.label || 'Untitled automation';
    const description = automation?.description || template.description;

    // The dialog stays open, with both of its buttons busy, until the removal settles: closing on
    // the click alone would report a delete that may still fail, and would let a second click fire
    // the same request twice.
    const handleRemove = async () => {
        setRemoving(true);

        try {
            await removeAutomation(automation!, {onDeleteAutomation, onDeprovisionReference});

            setRemoveDialogOpen(false);
        } finally {
            setRemoving(false);
        }
    };

    return (
        <Card
            className={twMerge(
                'gap-4 border-transparent bg-(--hub-card) py-3 shadow-none',
                automation?.enabled && 'border-(--hub-active-border)'
            )}
        >
            <CardHeader className="px-3">
                <CardTitle className="flex min-w-0 items-center gap-2 self-center text-base">
                    <h3 className="truncate text-base font-semibold" title={label}>
                        {label}
                    </h3>

                    {automation && <AutomationVersion workflowVersion={automation.workflowVersion} />}
                </CardTitle>

                {description && (
                    <CardDescription className="line-clamp-2" title={description}>
                        {description}
                    </CardDescription>
                )}

                {automation && (
                    <CardAction className="row-span-1 self-center">
                        <AutomationCardMenu
                            label={label}
                            onCustomize={
                                automation.kind === 'COPY'
                                    ? () => navigate(`/embedded/hub/builder/${automation.workflowUuid}`)
                                    : undefined
                            }
                            onEditInputs={
                                (automation.inputs ?? []).length > 0 ? () => setInputsDialogOpen(true) : undefined
                            }
                            onRemove={() => setRemoveDialogOpen(true)}
                        />
                    </CardAction>
                )}
            </CardHeader>

            {!!template.components?.length && (
                <CardContent className="flex items-center -space-x-2 px-3">
                    {template.components.map((component) => (
                        <div
                            className="flex size-9 shrink-0 items-center justify-center rounded-full border bg-background p-1.5"
                            key={`component-${component.name}`}
                            title={component.title || component.name}
                        >
                            {component.icon && <InlineSVG className="size-5 flex-none" src={component.icon} />}
                        </div>
                    ))}
                </CardContent>
            )}

            <CardFooter className="mt-auto justify-end px-3">
                {automation ? (
                    <>
                        <AutomationStatusButton
                            automation={automation}
                            label={label}
                            onEnabledChange={(enabled) =>
                                onSetEnabled({enabled, workflowUuid: automation.workflowUuid!})
                            }
                        />

                        {inputsDialogOpen && (
                            <AutomationInputsDialog
                                inputValues={(automation.inputValues ?? {}) as Record<string, unknown>}
                                inputs={automation.inputs ?? []}
                                label={label}
                                onClose={() => setInputsDialogOpen(false)}
                                workflowUuid={automation.workflowUuid!}
                            />
                        )}

                        <DeleteAlertDialog
                            confirmLabel="Remove"
                            description={`This will remove "${label}" from your automations. This action cannot be undone.`}
                            isPending={removing}
                            onCancel={() => setRemoveDialogOpen(false)}
                            onDelete={handleRemove}
                            open={removeDialogOpen}
                            title="Remove automation?"
                        />
                    </>
                ) : (
                    <Button
                        className="min-w-24"
                        disabled={activationDisabled}
                        label="Use"
                        onClick={onUseTemplate}
                        size="sm"
                        variant="secondary"
                    />
                )}
            </CardFooter>
        </Card>
    );
};

export default TemplateCard;

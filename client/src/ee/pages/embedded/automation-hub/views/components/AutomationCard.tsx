import DeleteAlertDialog from '@/components/DeleteAlertDialog';
import {Badge} from '@/components/ui/badge';
import {Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle} from '@/components/ui/card';
import {SetAutomationEnabledRequestI} from '@/ee/pages/embedded/automation-hub/mutations/automationHub.mutations';
import {removeAutomation} from '@/ee/pages/embedded/automation-hub/utils/removeAutomation';
import AutomationCardMenu from '@/ee/pages/embedded/automation-hub/views/components/AutomationCardMenu';
import AutomationInputsDialog from '@/ee/pages/embedded/automation-hub/views/components/AutomationInputsDialog';
import AutomationStatusButton from '@/ee/pages/embedded/automation-hub/views/components/AutomationStatusButton';
import AutomationVersion from '@/ee/pages/embedded/automation-hub/views/components/AutomationVersion';
import {ConnectedUserProjectWorkflow} from '@/ee/shared/middleware/embedded/public';
import {useState} from 'react';
import InlineSVG from 'react-inlinesvg';
import {useNavigate} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';

interface AutomationCardProps {
    automation: ConnectedUserProjectWorkflow;
    onDeleteAutomation: (workflowUuid: string) => Promise<unknown>;
    onDeprovisionReference: (workflowUuid: string) => Promise<unknown>;
    onSetEnabled: (request: SetAutomationEnabledRequestI) => void;
}

/**
 * One automation no published template accounts for: a workflow the connected user built from
 * scratch, a copy whose source template has been withdrawn, a dangling reference, or a second copy
 * of a template whose card is already taken. It carries the same affordances as an activated
 * template card — status button, top-right overflow menu — so every automation the user owns stays
 * enableable and removable from one grid.
 *
 * `kind` drives the divergence between a COPY (the user's own editable workflow, which offers
 * Customize) and a REFERENCE (a pointer at a shared catalog workflow, which never does). Remove
 * branches through `removeAutomation`, the same helper the template card uses.
 *
 * A `dangling` card keeps its Remove but its status button is DISABLED: the reference points at a
 * catalog workflow a redeploy withdrew, nothing ever clears the flag, and enabling it fails
 * server-side with nothing on screen to explain the failure.
 */
const AutomationCard = ({
    automation,
    onDeleteAutomation,
    onDeprovisionReference,
    onSetEnabled,
}: AutomationCardProps) => {
    const [inputsDialogOpen, setInputsDialogOpen] = useState(false);
    const [removeDialogOpen, setRemoveDialogOpen] = useState(false);
    const [removing, setRemoving] = useState(false);

    // Most workflows declare none, and a Settings item that opens an empty dialog is worse than no
    // item at all.
    const inputs = automation.inputs ?? [];

    const navigate = useNavigate();

    const label = automation.label || 'Untitled automation';

    // The dialog stays open, with both of its buttons busy, until the removal settles: closing on
    // the click alone would report a delete that may still fail, and would let a second click fire
    // the same request twice.
    const handleRemove = async () => {
        setRemoving(true);

        try {
            await removeAutomation(automation, {onDeleteAutomation, onDeprovisionReference});

            setRemoveDialogOpen(false);
        } finally {
            setRemoving(false);
        }
    };

    return (
        <Card
            className={twMerge(
                'gap-4 border-transparent bg-(--hub-card) py-3 shadow-none',
                automation.enabled && !automation.dangling && 'border-(--hub-active-border)'
            )}
        >
            <CardHeader className="px-3">
                <CardTitle className="flex min-w-0 items-center gap-2 self-center text-base">
                    <h3 className="truncate text-base font-semibold" title={label}>
                        {label}
                    </h3>

                    <AutomationVersion workflowVersion={automation.workflowVersion} />

                    {automation.dangling && <Badge variant="destructive">Needs attention</Badge>}
                </CardTitle>

                {automation.description && (
                    <CardDescription className="line-clamp-2" title={automation.description}>
                        {automation.description}
                    </CardDescription>
                )}

                <CardAction className="row-span-1 self-center">
                    <AutomationCardMenu
                        label={label}
                        onCustomize={
                            automation.kind === 'COPY' && !automation.dangling
                                ? () => navigate(`/embedded/hub/builder/${automation.workflowUuid}`)
                                : undefined
                        }
                        onEditInputs={inputs.length > 0 ? () => setInputsDialogOpen(true) : undefined}
                        onRemove={() => setRemoveDialogOpen(true)}
                    />
                </CardAction>
            </CardHeader>

            {!!automation.components?.length && (
                <CardContent className="flex items-center -space-x-2 px-3">
                    {automation.components.map((component) => (
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
                <AutomationStatusButton
                    automation={automation}
                    label={label}
                    onEnabledChange={(enabled) => onSetEnabled({enabled, workflowUuid: automation.workflowUuid!})}
                />

                {inputsDialogOpen && (
                    <AutomationInputsDialog
                        inputValues={(automation.inputValues ?? {}) as Record<string, unknown>}
                        inputs={inputs}
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
            </CardFooter>
        </Card>
    );
};

export default AutomationCard;

import Button from '@/components/Button/Button';
import {ConnectedUserProjectWorkflow} from '@/ee/shared/middleware/embedded/public';
import {twMerge} from 'tailwind-merge';

const UNPUBLISHED_TITLE = 'Publish this automation in the builder before enabling it';

interface AutomationStatusButtonProps {
    automation: ConnectedUserProjectWorkflow;
    label: string;
    onEnabledChange: (enabled: boolean) => void;
}

/**
 * An automation's on/off control, named and coloured after the action it performs rather than the
 * state it is in: a running automation offers a red "Disable", a stopped one a green "Enable". The
 * card's own green tint is what reports the current state, so the button never has to repeat it —
 * and a card that reads "Disable" is unambiguously one you can stop, which a status word next to a
 * clickable control never is.
 *
 * It is inert in two cases, both of which the server would refuse anyway:
 *
 * <ul>
 * <li>A COPY with no deployed version has never been published, and the enable endpoint rejects any
 * workflow that is not in the active deployment. The button carries the reason as its title rather
 * than letting the click fail silently. A REFERENCE is exempt: it is provisioned rather than
 * published, so it has no version of its own and enables perfectly well without one.</li>
 * <li>A dangling reference points at a catalog workflow a redeploy withdrew. Nothing clears that
 * flag, and enabling fails server-side.</li>
 * </ul>
 *
 * `label` names the automation for screen readers, which otherwise hear a grid of buttons all
 * called "Enable". The accessible name leads with the same verb the button shows, so speaking the
 * visible label is always a way to activate it.
 */
const AutomationStatusButton = ({automation, label, onEnabledChange}: AutomationStatusButtonProps) => {
    const enabled = !!automation.enabled;
    const unpublished = automation.kind === 'COPY' && !automation.workflowVersion;

    return (
        <Button
            aria-label={`${enabled ? 'Disable' : 'Enable'} ${label}`}
            className={twMerge(
                'min-w-24 text-(--hub-on-accent)',
                enabled
                    ? 'bg-(--hub-disable) hover:bg-(--hub-disable-hover) active:bg-(--hub-disable-hover)'
                    : 'bg-(--hub-enable) hover:bg-(--hub-enable-hover) active:bg-(--hub-enable-hover)'
            )}
            disabled={unpublished || automation.dangling}
            label={enabled ? 'Disable' : 'Enable'}
            onClick={() => onEnabledChange(!enabled)}
            size="sm"
            title={unpublished ? UNPUBLISHED_TITLE : undefined}
            variant="default"
        />
    );
};

export default AutomationStatusButton;

import Switch from '@/components/Switch/Switch';
import {twMerge} from 'tailwind-merge';

interface ModeSwitchPropsI {
    build: boolean;
    className?: string;
    onBuildChange: (build: boolean) => void;
}

/**
 * Ask/Build mode toggle used in the chat composers (AI Hub and Copilot). The switch toggles Build mode:
 * on (green) = Build, off = Ask. The label stays a static "Build" — toggling off implicitly means Ask, so
 * there's no need to relabel. Replaces the previous two-button segmented control in the panel header.
 */
const ModeSwitch = ({build, className, onBuildChange}: ModeSwitchPropsI) => (
    <div className={twMerge('flex items-center gap-1.5', className)}>
        <span className="text-xs font-medium text-muted-foreground">Build</span>

        <Switch
            aria-label={build ? 'Build mode on' : 'Build mode off (Ask)'}
            checked={build}
            className="data-[state=checked]:bg-surface-success-primary"
            onCheckedChange={onBuildChange}
        />
    </div>
);

export default ModeSwitch;

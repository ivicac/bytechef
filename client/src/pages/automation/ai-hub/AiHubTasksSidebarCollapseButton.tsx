import Button from '@/components/Button/Button';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {useAiHubTabsStore} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {PanelLeftCloseIcon} from 'lucide-react';

/**
 * Collapses the AI Hub Tasks sidebar down to its thin rail.
 *
 * Rendered in the "AI Hub" sidebar header's right slot so it sits in line with the title, and
 * only while the resource panel is open — collapsing has no meaning otherwise, since the full
 * sidebar is then the only form. Mirrors the expand control on {@link AiHubTasksSidebarRail}.
 */
const AiHubTasksSidebarCollapseButton = () => {
    const setTasksSidebarCollapsed = useAiHubTabsStore((state) => state.setTasksSidebarCollapsed);

    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <Button
                    aria-label="Collapse tasks sidebar"
                    // Negative right margin pulls the button toward the sidebar edge, counteracting
                    // the shared Header's px-4 padding so the icon sits closer to the right edge.
                    className="-mr-2"
                    icon={<PanelLeftCloseIcon />}
                    onClick={() => setTasksSidebarCollapsed(true)}
                    size="icon"
                    variant="ghost"
                />
            </TooltipTrigger>

            <TooltipContent>Collapse to rail</TooltipContent>
        </Tooltip>
    );
};

export default AiHubTasksSidebarCollapseButton;

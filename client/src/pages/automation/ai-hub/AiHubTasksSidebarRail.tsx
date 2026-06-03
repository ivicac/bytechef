import Button from '@/components/Button/Button';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {useAiHubTabsStore} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {PanelLeftOpenIcon} from 'lucide-react';

/**
 * Collapsed form of the AI Hub Tasks sidebar, shown only while the resource (right) panel is open.
 *
 * Without it, opening the resource panel hides the full Tasks sidebar entirely — the sidebar
 * vanishes without a trace. This thin rail keeps a single, visible affordance to bring it back,
 * so the chat + resource panels still reclaim most of the width while the user retains an obvious
 * path to their tasks. It deliberately carries nothing else (no nav, no task list): the rail is a
 * re-open handle, and the full sidebar is one click away.
 *
 * Border + background match `LayoutContainer`'s left `aside` so the rail reads as the same sidebar,
 * just collapsed.
 */
const AiHubTasksSidebarRail = () => {
    const setTasksSidebarCollapsed = useAiHubTabsStore((state) => state.setTasksSidebarCollapsed);

    return (
        <div className="flex w-12 shrink-0 flex-col items-center border-r border-r-border/50 bg-muted/50 py-2">
            <Tooltip>
                <TooltipTrigger asChild>
                    <Button
                        aria-label="Show tasks sidebar"
                        icon={<PanelLeftOpenIcon />}
                        onClick={() => setTasksSidebarCollapsed(false)}
                        size="icon"
                        variant="ghost"
                    />
                </TooltipTrigger>

                <TooltipContent side="right">Show tasks</TooltipContent>
            </Tooltip>
        </div>
    );
};

export default AiHubTasksSidebarRail;

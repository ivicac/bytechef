import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import AgentDialog from '@/pages/automation/agents/components/AgentDialog';
import DeleteAgentAlertDialog from '@/pages/automation/agents/components/DeleteAgentAlertDialog';
import useAgentActions from '@/pages/automation/agents/hooks/useAgentActions';
import {MoreVerticalIcon, PencilIcon, Trash2Icon} from 'lucide-react';
import {twMerge} from 'tailwind-merge';

interface AgentsLeftSidebarDropdownMenuPropsI {
    agent: {description?: string | null; id: string; title: string};
    /**
     * Keeps the trigger visible at rest instead of only on hover or while the menu is open. The sidebar list
     * relies on the row's own `group` hover state, but a row styled like the project workflow list (an
     * always-visible menu) has no such hover affordance to key off of. Defaults to the sidebar's hover-only
     * behaviour.
     */
    alwaysVisibleTrigger?: boolean;
    /** True when this row is the agent currently open, so deleting it has to navigate away. */
    current: boolean;
}

/**
 * Per-row Edit/Delete menu for the agents sidebar, mirroring the data tables sidebar: hidden until the row is
 * hovered (or the menu is open), so a list of agents stays a list of names.
 */
const AgentsLeftSidebarDropdownMenu = ({agent, alwaysVisibleTrigger, current}: AgentsLeftSidebarDropdownMenuPropsI) => {
    const {
        deleteAgentMutation,
        handleConfirmDelete,
        handleDeleteClick,
        setShowDeleteConfirmDialog,
        setShowEditDialog,
        showDeleteConfirmDialog,
        showEditDialog,
    } = useAgentActions({
        agentId: agent.id,
        navigateOnDelete: current,
    });

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button
                        aria-label={`${agent.title} menu`}
                        className={twMerge(
                            'w-6 transition-opacity data-[state=open]:opacity-100',
                            !alwaysVisibleTrigger && 'opacity-0 group-hover:opacity-100'
                        )}
                        icon={<MoreVerticalIcon className="h-4" />}
                        size="iconSm"
                        variant="ghost"
                    />
                </DropdownMenuTrigger>

                <DropdownMenuContent align="end">
                    <DropdownMenuItem onSelect={() => setShowEditDialog(true)}>
                        <PencilIcon className="mr-2 size-4" /> Edit
                    </DropdownMenuItem>

                    <DropdownMenuSeparator />

                    {/* variant rather than a colour class: the item's own muted-svg rule wins over one, leaving
                        the icon grey. */}

                    <DropdownMenuItem
                        disabled={deleteAgentMutation.isPending}
                        onSelect={handleDeleteClick}
                        variant="destructive"
                    >
                        <Trash2Icon className="mr-2 size-4" /> Delete
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            {/* Controlled: the menu item that opens it unmounts on select, so the dialog cannot hang off a
                trigger inside the menu. */}

            <AgentDialog agent={agent} onOpenChange={setShowEditDialog} open={showEditDialog} />

            {showDeleteConfirmDialog && (
                <DeleteAgentAlertDialog
                    agentTitle={agent.title}
                    onClose={() => setShowDeleteConfirmDialog(false)}
                    onDelete={handleConfirmDelete}
                />
            )}
        </>
    );
};

export default AgentsLeftSidebarDropdownMenu;

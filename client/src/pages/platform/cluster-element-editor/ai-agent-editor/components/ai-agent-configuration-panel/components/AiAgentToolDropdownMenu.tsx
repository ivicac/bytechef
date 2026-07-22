import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuCheckboxItem,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSub,
    DropdownMenuSubContent,
    DropdownMenuSubTrigger,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {ClockIcon, EllipsisVerticalIcon, SettingsIcon, TrashIcon} from 'lucide-react';

import useAiAgentToolDropdownMenu from './hooks/useAiAgentToolDropdownMenu';
import {ToolItemI} from './hooks/useAiAgentTools';

interface AiAgentToolDropdownMenuProps {
    tool: ToolItemI;
}

interface ApprovalExpiryPresetI {
    expiresIn: number;
    label: string;
    unit: string;
}

// expiresIn 0 clears the override: the gate treats values below 1 as unset and falls back to the 60-day default.
const APPROVAL_EXPIRY_PRESETS: ApprovalExpiryPresetI[] = [
    {expiresIn: 1, label: '1 hour', unit: 'HOURS'},
    {expiresIn: 8, label: '8 hours', unit: 'HOURS'},
    {expiresIn: 24, label: '24 hours', unit: 'HOURS'},
    {expiresIn: 3, label: '3 days', unit: 'DAYS'},
    {expiresIn: 7, label: '7 days', unit: 'DAYS'},
    {expiresIn: 30, label: '30 days', unit: 'DAYS'},
    {expiresIn: 0, label: 'Default (60 days)', unit: 'DAYS'},
];

const isPresetSelected = (tool: ToolItemI, preset: ApprovalExpiryPresetI): boolean => {
    if (preset.expiresIn === 0) {
        return !tool.approvalExpiresIn;
    }

    return tool.approvalExpiresIn === preset.expiresIn && (tool.approvalExpiresInUnit || 'DAYS') === preset.unit;
};

export default function AiAgentToolDropdownMenu({tool}: AiAgentToolDropdownMenuProps) {
    const {handleConfigureTool, handleRemoveTool, handleSetApprovalExpiry, handleToggleRequiresApproval} =
        useAiAgentToolDropdownMenu();

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button className="h-6" size="sm" variant="ghost">
                    <EllipsisVerticalIcon className="size-3 text-gray-400" />
                </Button>
            </DropdownMenuTrigger>

            <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => handleConfigureTool(tool)}>
                    <SettingsIcon />
                    Configure
                </DropdownMenuItem>

                <DropdownMenuCheckboxItem
                    checked={tool.requiresApproval}
                    onCheckedChange={() => handleToggleRequiresApproval(tool)}
                >
                    Requires approval
                </DropdownMenuCheckboxItem>

                {tool.requiresApproval && (
                    <DropdownMenuSub>
                        <DropdownMenuSubTrigger>
                            <ClockIcon />
                            Approval expires in
                        </DropdownMenuSubTrigger>

                        <DropdownMenuSubContent>
                            {APPROVAL_EXPIRY_PRESETS.map((preset) => (
                                <DropdownMenuCheckboxItem
                                    checked={isPresetSelected(tool, preset)}
                                    key={preset.label}
                                    onCheckedChange={() => handleSetApprovalExpiry(tool, preset.expiresIn, preset.unit)}
                                >
                                    {preset.label}
                                </DropdownMenuCheckboxItem>
                            ))}
                        </DropdownMenuSubContent>
                    </DropdownMenuSub>
                )}

                <DropdownMenuItem
                    className="text-destructive focus:text-destructive"
                    onClick={() => handleRemoveTool(tool)}
                >
                    <TrashIcon />
                    Remove
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}

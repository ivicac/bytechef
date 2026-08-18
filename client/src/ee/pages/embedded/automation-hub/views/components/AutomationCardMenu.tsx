import Button from '@/components/Button/Button';
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import {EllipsisVerticalIcon, ExternalLinkIcon, SlidersHorizontalIcon, Trash2Icon} from 'lucide-react';

interface AutomationCardMenuProps {
    label: string;
    onCustomize?: () => void;
    onEditInputs?: () => void;
    onRemove: () => void;
}

/**
 * A card's overflow menu, in the card's top-right corner. Customize is offered only when the
 * caller passes a handler: a REFERENCE points at a shared catalog workflow the connected user must
 * never edit, and a dangling one has nothing left to open. Settings likewise appears only for an
 * automation whose workflow actually declares inputs -- most declare none.
 */
const AutomationCardMenu = ({label, onCustomize, onEditInputs, onRemove}: AutomationCardMenuProps) => (
    <DropdownMenu>
        <DropdownMenuTrigger asChild>
            <Button aria-label={`${label} actions`} icon={<EllipsisVerticalIcon />} size="iconSm" variant="ghost" />
        </DropdownMenuTrigger>

        <DropdownMenuContent align="end">
            {onEditInputs && (
                <DropdownMenuItem onClick={onEditInputs}>
                    <SlidersHorizontalIcon /> Settings
                </DropdownMenuItem>
            )}

            {onCustomize && (
                <DropdownMenuItem onClick={onCustomize}>
                    <ExternalLinkIcon /> Customize
                </DropdownMenuItem>
            )}

            <DropdownMenuItem onClick={onRemove} variant="destructive">
                <Trash2Icon /> Remove
            </DropdownMenuItem>
        </DropdownMenuContent>
    </DropdownMenu>
);

export default AutomationCardMenu;

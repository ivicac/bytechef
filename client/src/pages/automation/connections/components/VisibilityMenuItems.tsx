import {DropdownMenuItem} from '@/components/ui/dropdown-menu';
import {GlobeIcon, LockIcon} from 'lucide-react';

import type {ConnectionVisibilityEnum} from '@/shared/middleware/automation/configuration';

interface VisibilityMenuItemsProps {
    connectionId: string;
    onDemoteRequest: (visibility: ConnectionVisibilityEnum) => void;
    onPromoteToWorkspace: (variables: {connectionId: string; workspaceId: string}) => void;
    visibility: ConnectionVisibilityEnum;
    workspaceId: string;
}

const VisibilityMenuItems = ({
    connectionId,
    onDemoteRequest,
    onPromoteToWorkspace,
    visibility,
    workspaceId,
}: VisibilityMenuItemsProps) => (
    <>
        {visibility === 'PRIVATE' && (
            <DropdownMenuItem
                className="dropdown-menu-item"
                onClick={() => onPromoteToWorkspace({connectionId, workspaceId})}
            >
                <GlobeIcon /> Share with workspace
            </DropdownMenuItem>
        )}

        {visibility !== 'PRIVATE' && (
            <DropdownMenuItem className="dropdown-menu-item" onClick={() => onDemoteRequest(visibility)}>
                <LockIcon /> Make private
            </DropdownMenuItem>
        )}
    </>
);

export default VisibilityMenuItems;

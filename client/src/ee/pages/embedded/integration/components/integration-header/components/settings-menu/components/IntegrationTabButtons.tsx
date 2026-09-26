import '@/shared/styles/dropdownMenu.css';
import Button from '@/components/Button/Button';
import {Separator} from '@/components/ui/separator';
import {EditIcon, HistoryIcon, PlusIcon, Trash2Icon, UploadIcon} from 'lucide-react';
import {MouseEvent} from 'react';

const IntegrationTabButtons = ({
    onCloseDropdownMenuClick,
    onDeleteIntegrationClick,
    onImportWorkflowClick,
    onNewWorkflowClick,
    onShowEditIntegrationDialogClick,
    onShowIntegrationVersionHistorySheet,
    workflowCreationEnabled,
}: {
    onCloseDropdownMenuClick: () => void;
    onDeleteIntegrationClick: () => void;
    onImportWorkflowClick: () => void;
    onNewWorkflowClick: () => void;
    onShowEditIntegrationDialogClick: () => void;
    onShowIntegrationVersionHistorySheet: () => void;
    workflowCreationEnabled: boolean;
}) => {
    const handleButtonClick = (event: MouseEvent<HTMLDivElement>) => {
        if ((event.target as HTMLElement).tagName === 'BUTTON') {
            onCloseDropdownMenuClick();
        }
    };

    return (
        <div className="flex flex-col" onClick={handleButtonClick}>
            <Button
                aria-label="Edit Integration Button"
                className="dropdown-menu-item"
                icon={<EditIcon />}
                label="Edit"
                onClick={() => onShowEditIntegrationDialogClick()}
                variant="ghost"
            />

            <Separator />

            {workflowCreationEnabled && (
                <>
                    <Button
                        aria-label="New Workflow"
                        className="dropdown-menu-item"
                        icon={<PlusIcon />}
                        label="New Workflow"
                        onClick={onNewWorkflowClick}
                        variant="ghost"
                    />

                    <Button
                        aria-label="Import Workflow Button"
                        className="dropdown-menu-item"
                        icon={<UploadIcon />}
                        label="Import Workflow"
                        onClick={onImportWorkflowClick}
                        variant="ghost"
                    />

                    <Separator />
                </>
            )}

            <Button
                aria-label="Integration History"
                className="dropdown-menu-item"
                icon={<HistoryIcon />}
                label="Integration History"
                onClick={onShowIntegrationVersionHistorySheet}
                variant="ghost"
            />

            <Separator />

            <Button
                aria-label="Delete Integration"
                className="dropdown-menu-item-destructive"
                icon={<Trash2Icon />}
                label="Delete"
                onClick={onDeleteIntegrationClick}
                variant="ghost"
            />
        </div>
    );
};

export default IntegrationTabButtons;

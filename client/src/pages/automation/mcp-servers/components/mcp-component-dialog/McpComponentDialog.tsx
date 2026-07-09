import Button from '@/components/Button/Button';
import {Input} from '@/components/ui/input';
import McpComponentDialogShell, {
    type McpComponentDialogProps,
} from '@/shared/components/mcp-server/McpComponentDialogShell';
import {XIcon} from 'lucide-react';

import McpComponentDialogComponentSelectionStep from './McpComponentDialogComponentSelectionStep';
import McpComponentDialogToolSelectionStep from './McpComponentDialogToolSelectionStep';
import useMcpComponentDialog from './hooks/useMcpComponentDialog';

const McpComponentDialog = ({mcpComponent, mcpServerId, onOpenChange, open, triggerNode}: McpComponentDialogProps) => {
    const {
        authoritiesLoading,
        authorityOptions,
        currentStep,
        existingTools,
        handleAddRequiredAuthority,
        handleBack,
        handleComponentSelect,
        handleOpenChange,
        handleRemoveRequiredAuthority,
        handleSave,
        requiredAuthorities,
        requiredAuthorityInput,
        selectedComponent,
        selectedConnection,
        selectedTools,
        setRequiredAuthorityInput,
        setSelectedConnection,
        setSelectedTools,
    } = useMcpComponentDialog({mcpComponent, mcpServerId, onOpenChange, open});

    return (
        <McpComponentDialogShell
            currentStep={currentStep}
            editing={!!mcpComponent}
            onBack={handleBack}
            onOpenChange={handleOpenChange}
            onSave={handleSave}
            open={open}
            selectedComponent={selectedComponent}
            selectedToolCount={selectedTools.length}
            triggerNode={triggerNode}
        >
            {currentStep === 'components' && (
                <McpComponentDialogComponentSelectionStep
                    onComponentSelect={handleComponentSelect}
                    open={open ?? true}
                />
            )}

            {currentStep === 'tools' && (
                <>
                    <McpComponentDialogToolSelectionStep
                        existingTools={existingTools}
                        mcpComponent={mcpComponent}
                        onConnectionChange={setSelectedConnection}
                        onToolsChange={setSelectedTools}
                        open={open ?? true}
                        selectedComponent={selectedComponent}
                        selectedConnection={selectedConnection}
                        selectedTools={selectedTools}
                    />

                    <fieldset className="mt-4 space-y-2 border-0 p-0 px-1">
                        <label className="text-sm font-medium">Required Authorities</label>

                        <div className="flex gap-2">
                            <Input
                                onChange={(event) => setRequiredAuthorityInput(event.target.value)}
                                onKeyDown={(event) => {
                                    if (event.key === 'Enter') {
                                        event.preventDefault();
                                        handleAddRequiredAuthority();
                                    }
                                }}
                                placeholder="ROLE_SALES"
                                value={requiredAuthorityInput}
                            />

                            <Button label="Add" onClick={handleAddRequiredAuthority} type="button" variant="outline" />
                        </div>

                        {requiredAuthorities.length > 0 && (
                            <div className="flex flex-wrap gap-1">
                                {requiredAuthorities.map((requiredAuthority) => (
                                    <span
                                        className="inline-flex items-center gap-1 rounded-md bg-secondary px-2 py-0.5 text-xs text-secondary-foreground"
                                        key={requiredAuthority}
                                    >
                                        {requiredAuthority}

                                        <button
                                            className="ml-1 rounded-full hover:bg-muted"
                                            onClick={() => handleRemoveRequiredAuthority(requiredAuthority)}
                                            type="button"
                                        >
                                            <XIcon className="size-3" />
                                        </button>
                                    </span>
                                ))}
                            </div>
                        )}

                        <p className="text-xs text-muted-foreground">
                            When the server enforces tool authorization, only callers holding one of these authorities
                            see this component&apos;s tools.
                        </p>
                    </fieldset>
                </>
            )}
        </McpComponentDialogShell>
    );
};

export default McpComponentDialog;

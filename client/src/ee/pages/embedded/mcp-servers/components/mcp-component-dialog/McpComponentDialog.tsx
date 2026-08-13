import {MultiSelect} from '@/components/MultiSelect/MultiSelect';
import McpComponentDialogShell, {
    type McpComponentDialogProps,
} from '@/shared/components/mcp-server/McpComponentDialogShell';

import McpComponentDialogComponentSelectionStep from './McpComponentDialogComponentSelectionStep';
import McpComponentDialogToolSelectionStep from './McpComponentDialogToolSelectionStep';
import useMcpComponentDialog from './hooks/useMcpComponentDialog';

const McpComponentDialog = ({mcpComponent, mcpServerId, onOpenChange, open, triggerNode}: McpComponentDialogProps) => {
    const {
        authoritiesLoading,
        authorityOptions,
        currentStep,
        existingTools,
        handleBack,
        handleComponentSelect,
        handleOpenChange,
        handleSave,
        requiredAuthorities,
        selectedComponent,
        selectedTools,
        setRequiredAuthorities,
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
                        onToolsChange={setSelectedTools}
                        selectedComponent={selectedComponent}
                        selectedTools={selectedTools}
                    />

                    <fieldset className="mt-4 space-y-2 border-0 p-0 px-1">
                        <label className="text-sm font-medium">Required Authorities</label>

                        <MultiSelect
                            onValueChange={setRequiredAuthorities}
                            options={authorityOptions}
                            optionsLoading={authoritiesLoading}
                            placeholder="Select authorities"
                            searchable
                            value={requiredAuthorities}
                        />

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

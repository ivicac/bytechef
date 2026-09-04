import Button from '@/components/Button/Button';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {Input} from '@/components/ui/input';
import {Label} from '@/components/ui/label';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select';
import {AiHubToolApprovalRuleMode, AiHubToolKind} from '@/shared/middleware/graphql';
import {useState} from 'react';

export interface NewToolApprovalRuleInputI {
    componentName: string | null;
    mode: AiHubToolApprovalRuleMode;
    toolKind: AiHubToolKind;
    toolName: string;
}

interface AddToolApprovalRuleDialogProps {
    onOpenChange: (open: boolean) => void;
    onSubmit: (input: NewToolApprovalRuleInputI) => void;
    open: boolean;
}

/**
 * "Add rule" dialog for the workspace's Tool Approvals settings: a manually-authored rule on top of
 * the built-in defaults. Kind picks whether the rule targets a catalog tool (agent-wide, no
 * component) or one component's tool(s); the Component input only appears for the latter. Mode picks
 * whether the rule adds a gate (Require approval) or lifts one (Exempt) for the given tool.
 */
const AddToolApprovalRuleDialog = ({onOpenChange, onSubmit, open}: AddToolApprovalRuleDialogProps) => {
    const [toolKind, setToolKind] = useState<AiHubToolKind>(AiHubToolKind.Component);
    const [componentName, setComponentName] = useState('');
    const [toolName, setToolName] = useState('');
    const [mode, setMode] = useState<AiHubToolApprovalRuleMode>(AiHubToolApprovalRuleMode.Require);

    const isComponentKind = toolKind === AiHubToolKind.Component;
    const saveDisabled = !toolName.trim() || (isComponentKind && !componentName.trim());

    const handleOpenChange = (nextOpen: boolean) => {
        if (!nextOpen) {
            setToolKind(AiHubToolKind.Component);
            setComponentName('');
            setToolName('');
            setMode(AiHubToolApprovalRuleMode.Require);
        }

        onOpenChange(nextOpen);
    };

    const handleSave = () => {
        onSubmit({
            componentName: isComponentKind ? componentName.trim() : null,
            mode,
            toolKind,
            toolName: toolName.trim(),
        });
    };

    return (
        <Dialog onOpenChange={handleOpenChange} open={open}>
            <DialogContent className="max-w-lg">
                <DialogHeader>
                    <DialogTitle>Add rule</DialogTitle>

                    <DialogDescription>
                        Gate an extra tool beyond the built-in defaults, or exempt one of this workspace's own component
                        tools from approval.
                    </DialogDescription>
                </DialogHeader>

                <fieldset className="flex flex-col gap-4 border-0">
                    <div className="flex flex-col gap-2">
                        <Label htmlFor="tool-approval-rule-kind">Kind</Label>

                        <Select onValueChange={(value) => setToolKind(value as AiHubToolKind)} value={toolKind}>
                            <SelectTrigger aria-label="Kind" id="tool-approval-rule-kind">
                                <SelectValue />
                            </SelectTrigger>

                            <SelectContent>
                                <SelectItem value={AiHubToolKind.Catalog}>Catalog</SelectItem>

                                <SelectItem value={AiHubToolKind.Component}>Component</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>

                    {isComponentKind && (
                        <div className="flex flex-col gap-2">
                            <Label htmlFor="tool-approval-rule-component">Component</Label>

                            <Input
                                id="tool-approval-rule-component"
                                onChange={(event) => setComponentName(event.target.value)}
                                placeholder="gmail"
                                value={componentName}
                            />
                        </div>
                    )}

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="tool-approval-rule-tool-name">Tool name</Label>

                        <Input
                            id="tool-approval-rule-tool-name"
                            onChange={(event) => setToolName(event.target.value)}
                            placeholder="sendEmail"
                            value={toolName}
                        />

                        <p className="text-xs text-muted-foreground">
                            Use <code>*</code> for every operation of the component.
                        </p>
                    </div>

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="tool-approval-rule-mode">Mode</Label>

                        <Select onValueChange={(value) => setMode(value as AiHubToolApprovalRuleMode)} value={mode}>
                            <SelectTrigger aria-label="Mode" id="tool-approval-rule-mode">
                                <SelectValue />
                            </SelectTrigger>

                            <SelectContent>
                                <SelectItem value={AiHubToolApprovalRuleMode.Require}>Require approval</SelectItem>

                                <SelectItem value={AiHubToolApprovalRuleMode.Exempt}>Exempt</SelectItem>
                            </SelectContent>
                        </Select>
                    </div>
                </fieldset>

                <DialogFooter>
                    <Button label="Cancel" onClick={() => handleOpenChange(false)} variant="outline" />

                    <Button disabled={saveDisabled} label="Save" onClick={handleSave} />
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default AddToolApprovalRuleDialog;

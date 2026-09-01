import {Button} from '@/components/ui/button';
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
import {RadioGroup, RadioGroupItem} from '@/components/ui/radio-group';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select';
import {Textarea} from '@/components/ui/textarea';
import {type ComponentRuleItemType} from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleList';
import useComponentRuleCopilot from '@/ee/pages/settings/platform/component-rules/hooks/useComponentRuleCopilot';
import {
    ComponentRuleActionType,
    ComponentRulePhase,
    useComponentPoliciesQuery,
    useSaveComponentRuleMutation,
} from '@/shared/middleware/graphql';
import {useGetComponentDefinitionQuery} from '@/shared/queries/platform/componentDefinitions.queries';
import {useQueryClient} from '@tanstack/react-query';
import {SparklesIcon} from 'lucide-react';
import {type ReactNode, useEffect, useMemo, useState} from 'react';

const ALL_ACTIONS_VALUE = '__all_actions__';

interface ComponentRuleDialogProps {
    componentRule?: ComponentRuleItemType;
    onOpenChange: (open: boolean) => void;
    open: boolean;
}

const ComponentRuleDialog = ({componentRule, onOpenChange, open}: ComponentRuleDialogProps) => {
    const [actionName, setActionName] = useState<string>(ALL_ACTIONS_VALUE);
    const [componentName, setComponentName] = useState<string>('');
    const [condition, setCondition] = useState<string>('');
    const [description, setDescription] = useState<string>('');
    const [phase, setPhase] = useState<ComponentRulePhase>(ComponentRulePhase.Before);
    const [ruleAction, setRuleAction] = useState<ComponentRuleActionType>(ComponentRuleActionType.Block);

    const queryClient = useQueryClient();

    const {data: componentPoliciesData} = useComponentPoliciesQuery();

    const selectedComponentPolicy = useMemo(
        () =>
            (componentPoliciesData?.componentPolicies ?? []).find(
                (componentPolicy) => componentPolicy.name === componentName
            ),
        [componentPoliciesData?.componentPolicies, componentName]
    );

    const {data: componentDefinition} = useGetComponentDefinitionQuery(
        {componentName, componentVersion: selectedComponentPolicy?.version ?? 1},
        !!componentName
    );

    const actionNames = useMemo(
        () => (componentDefinition?.actions ?? []).map((action) => action.name),
        [componentDefinition?.actions]
    );

    const saveComponentRuleMutation = useSaveComponentRuleMutation();

    const {handleCopilotClose, openCopilot} = useComponentRuleCopilot({
        actionName: actionName === ALL_ACTIONS_VALUE ? undefined : actionName,
        componentName,
        currentCondition: condition,
        onConditionGenerated: setCondition,
    });

    // Built as a plain array rather than inline JSX so the phase-dependent "and `output`" clause and the
    // built-in-function names are not sibling JSX elements in the returned markup — kept flat like this, they
    // read as prose to Prettier but as stacked block elements to the empty-line-between-elements lint rule, and
    // the two tools rewrite the paragraph back and forth forever.
    const conditionHelpText = useMemo<ReactNode[]>(() => {
        const helpText: ReactNode[] = [
            'A ByteChef formula expression over ',
            <code key="inputParameters">inputParameters</code>,
        ];

        if (phase === ComponentRulePhase.After) {
            helpText.push(' and ', <code key="output">output</code>);
        }

        helpText.push(
            '. Use the built-in functions (',
            <code key="contains">contains</code>,
            ', ',
            <code key="equalsIgnoreCase">equalsIgnoreCase</code>,
            ', ',
            <code key="size">size</code>,
            ') rather than Java method calls.'
        );

        return helpText;
    }, [phase]);

    const saveDisabled = !componentName || !condition.trim();

    const handleComponentNameChange = (value: string) => {
        setComponentName(value);
        setActionName(ALL_ACTIONS_VALUE);
    };

    // Any Copilot conversation open before "Generate with AI" was clicked must come back once the dialog closes,
    // whichever way it closes (Cancel, Save, Escape, outside click) — the panel is page-level and otherwise stays
    // parked in COMPONENT_RULE context.
    const handleDialogOpenChange = (nextOpen: boolean) => {
        if (!nextOpen) {
            handleCopilotClose();
        }

        onOpenChange(nextOpen);
    };

    const handleSave = () => {
        // SpelEvaluator's formula-expression check is not DOTALL, so a condition containing a newline — easy to type
        // into a multi-row textarea — fails to parse with a bare "Invalid formula expression". Collapsing any run of
        // whitespace (including newlines) to a single space keeps the condition on one line without changing what it
        // matches.
        const normalizedCondition = condition.replace(/\s+/g, ' ').trim();

        saveComponentRuleMutation.mutate(
            {
                actionName: actionName === ALL_ACTIONS_VALUE ? null : actionName,
                componentName,
                condition: normalizedCondition,
                description: description || null,
                enabled: componentRule ? componentRule.enabled : true,
                id: componentRule?.id,
                phase,
                ruleAction,
            },
            {
                onSuccess: () => {
                    queryClient.invalidateQueries({queryKey: ['ComponentRules']});

                    handleDialogOpenChange(false);
                },
            }
        );
    };

    // Reset the form whenever the dialog opens, so an Add after an Edit does not inherit the edited rule's values.
    useEffect(() => {
        if (!open) {
            return;
        }

        setActionName(componentRule?.actionName ?? ALL_ACTIONS_VALUE);
        setComponentName(componentRule?.componentName ?? '');
        setCondition(componentRule?.condition ?? '');
        setDescription(componentRule?.description ?? '');
        setPhase(componentRule?.phase ?? ComponentRulePhase.Before);
        setRuleAction(componentRule?.ruleAction ?? ComponentRuleActionType.Block);
    }, [componentRule, open]);

    // A block only means anything before the action runs; the server refuses BLOCK + AFTER outright, so the form
    // moves the selection to TAG rather than letting the admin submit a combination that cannot be saved.
    useEffect(() => {
        if (phase === ComponentRulePhase.After && ruleAction === ComponentRuleActionType.Block) {
            setRuleAction(ComponentRuleActionType.Tag);
        }
    }, [phase, ruleAction]);

    return (
        <Dialog onOpenChange={handleDialogOpenChange} open={open}>
            <DialogContent className="max-w-xl">
                <DialogHeader>
                    <DialogTitle>{componentRule ? 'Edit Rule' : 'Add Rule'}</DialogTitle>

                    <DialogDescription>
                        Conditionally block or tag a single action call based on the values it was invoked with.
                    </DialogDescription>
                </DialogHeader>

                <fieldset className="flex flex-col gap-4 border-0">
                    <div className="flex flex-col gap-2">
                        <Label htmlFor="component-rule-component">Component</Label>

                        <Select onValueChange={handleComponentNameChange} value={componentName}>
                            <SelectTrigger aria-label="Component" id="component-rule-component">
                                <SelectValue placeholder="Select a component" />
                            </SelectTrigger>

                            <SelectContent>
                                {(componentPoliciesData?.componentPolicies ?? []).map((componentPolicy) => (
                                    <SelectItem key={componentPolicy.name} value={componentPolicy.name}>
                                        {componentPolicy.title ?? componentPolicy.name}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="component-rule-action">Action</Label>

                        <Select disabled={!componentName} onValueChange={setActionName} value={actionName}>
                            <SelectTrigger aria-label="Action" id="component-rule-action">
                                <SelectValue />
                            </SelectTrigger>

                            <SelectContent>
                                <SelectItem value={ALL_ACTIONS_VALUE}>All actions</SelectItem>

                                {actionNames.map((name) => (
                                    <SelectItem key={name} value={name}>
                                        {name}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="flex gap-8">
                        <div className="flex flex-col gap-2">
                            <Label>Phase</Label>

                            <RadioGroup
                                className="flex gap-4"
                                onValueChange={(value) => setPhase(value as ComponentRulePhase)}
                                value={phase}
                            >
                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Before"
                                        id="component-rule-phase-before"
                                        value={ComponentRulePhase.Before}
                                    />

                                    <Label htmlFor="component-rule-phase-before">Before</Label>
                                </div>

                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="After"
                                        id="component-rule-phase-after"
                                        value={ComponentRulePhase.After}
                                    />

                                    <Label htmlFor="component-rule-phase-after">After</Label>
                                </div>
                            </RadioGroup>
                        </div>

                        <div className="flex flex-col gap-2">
                            <Label>Enforcement</Label>

                            <RadioGroup
                                className="flex gap-4"
                                onValueChange={(value) => setRuleAction(value as ComponentRuleActionType)}
                                value={ruleAction}
                            >
                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Block"
                                        disabled={phase === ComponentRulePhase.After}
                                        id="component-rule-action-block"
                                        value={ComponentRuleActionType.Block}
                                    />

                                    <Label htmlFor="component-rule-action-block">Block</Label>
                                </div>

                                <div className="flex items-center gap-2">
                                    <RadioGroupItem
                                        aria-label="Tag"
                                        id="component-rule-action-tag"
                                        value={ComponentRuleActionType.Tag}
                                    />

                                    <Label htmlFor="component-rule-action-tag">Tag</Label>
                                </div>
                            </RadioGroup>
                        </div>
                    </div>

                    {phase === ComponentRulePhase.After && (
                        <p className="text-xs text-muted-foreground">
                            An After rule can only tag — the action has already run, so a block would not undo its side
                            effects.
                        </p>
                    )}

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="component-rule-description">Description</Label>

                        <Input
                            id="component-rule-description"
                            onChange={(event) => setDescription(event.target.value)}
                            placeholder="Block deleting a record that still has an owner"
                            value={description}
                        />
                    </div>

                    <div className="flex flex-col gap-2">
                        <div className="flex items-center justify-between">
                            <Label htmlFor="component-rule-condition">Condition</Label>

                            <Button
                                disabled={!componentName}
                                onClick={openCopilot}
                                size="sm"
                                type="button"
                                variant="ghost"
                            >
                                <SparklesIcon className="mr-1 size-4" />
                                Generate with AI
                            </Button>
                        </div>

                        <Textarea
                            className="font-mono text-xs"
                            id="component-rule-condition"
                            onChange={(event) => setCondition(event.target.value)}
                            placeholder="inputParameters['ownerId'] != null"
                            rows={4}
                            value={condition}
                        />

                        <p className="text-xs text-muted-foreground">{conditionHelpText}</p>
                    </div>
                </fieldset>

                <DialogFooter>
                    <Button onClick={() => handleDialogOpenChange(false)} variant="outline">
                        Cancel
                    </Button>

                    <Button disabled={saveDisabled} onClick={handleSave}>
                        Save
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default ComponentRuleDialog;

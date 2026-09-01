import LazyLoadSVG from '@/components/LazyLoadSVG/LazyLoadSVG';
import Switch from '@/components/Switch/Switch';
import {Badge} from '@/components/ui/badge';
import {Button} from '@/components/ui/button';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {
    type ComponentRulesQuery,
    useDeleteComponentRuleMutation,
    useSaveComponentRuleMutation,
} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {PencilIcon, Trash2Icon} from 'lucide-react';

export type ComponentRuleItemType = ComponentRulesQuery['componentRules'][number];

interface ComponentRuleListProps {
    componentRules: ComponentRuleItemType[];
    onEdit: (componentRule: ComponentRuleItemType) => void;
}

const COMPONENT_RULES_QUERY_KEY = ['ComponentRules', {componentName: undefined}];

const ComponentRuleList = ({componentRules, onEdit}: ComponentRuleListProps) => {
    const queryClient = useQueryClient();

    const deleteComponentRuleMutation = useDeleteComponentRuleMutation({
        onSettled: () => {
            queryClient.invalidateQueries({queryKey: ['ComponentRules']});
        },
    });

    const saveComponentRuleMutation = useSaveComponentRuleMutation<unknown, {previous?: ComponentRulesQuery}>({
        onError: (_error, _variables, context) => {
            if (context?.previous) {
                queryClient.setQueryData(COMPONENT_RULES_QUERY_KEY, context.previous);
            }
        },
        onMutate: async ({enabled, id}) => {
            await queryClient.cancelQueries({queryKey: COMPONENT_RULES_QUERY_KEY});

            const previous = queryClient.getQueryData<ComponentRulesQuery>(COMPONENT_RULES_QUERY_KEY);

            queryClient.setQueryData<ComponentRulesQuery>(COMPONENT_RULES_QUERY_KEY, (current) =>
                current
                    ? {
                          componentRules: current.componentRules.map((componentRule) =>
                              componentRule.id === id ? {...componentRule, enabled} : componentRule
                          ),
                      }
                    : current
            );

            return {previous};
        },
        onSettled: () => {
            queryClient.invalidateQueries({queryKey: ['ComponentRules']});
        },
    });

    return (
        <ul className="divide-y rounded-md border">
            {componentRules.map((componentRule) => {
                const componentLabel = componentRule.componentTitle ?? componentRule.componentName;
                const actionLabel = componentRule.actionName ?? 'all actions';

                return (
                    <li className="flex items-center justify-between gap-3 px-4 py-3" key={componentRule.id}>
                        <div className="flex min-w-0 items-center gap-3">
                            {componentRule.componentIcon ? (
                                <LazyLoadSVG className="size-6 flex-none" src={componentRule.componentIcon} />
                            ) : (
                                <span className="size-6 flex-none rounded bg-muted" />
                            )}

                            <div className="flex min-w-0 flex-col">
                                <span className="text-sm font-semibold">
                                    {componentLabel}

                                    <span className="ml-2 font-normal text-muted-foreground">
                                        {componentRule.actionName ?? 'All actions'}
                                    </span>
                                </span>

                                <Tooltip>
                                    <TooltipTrigger asChild>
                                        <span className="truncate font-mono text-xs text-muted-foreground">
                                            {componentRule.condition}
                                        </span>
                                    </TooltipTrigger>

                                    <TooltipContent className="max-w-md font-mono break-all">
                                        {componentRule.condition}
                                    </TooltipContent>
                                </Tooltip>
                            </div>
                        </div>

                        <div className="flex flex-none items-center gap-2">
                            <Badge variant="secondary">{componentRule.phase === 'BEFORE' ? 'Before' : 'After'}</Badge>

                            <Badge variant={componentRule.ruleAction === 'BLOCK' ? 'destructive' : 'outline'}>
                                {componentRule.ruleAction === 'BLOCK' ? 'Block' : 'Tag'}
                            </Badge>

                            <Switch
                                aria-label={`${componentLabel} ${actionLabel}`}
                                checked={componentRule.enabled}
                                onCheckedChange={(checked) =>
                                    saveComponentRuleMutation.mutate({
                                        actionName: componentRule.actionName,
                                        componentName: componentRule.componentName,
                                        condition: componentRule.condition,
                                        description: componentRule.description,
                                        enabled: checked,
                                        id: componentRule.id,
                                        phase: componentRule.phase,
                                        ruleAction: componentRule.ruleAction,
                                    })
                                }
                            />

                            <Button
                                aria-label={`Edit ${componentLabel} ${actionLabel} rule`}
                                onClick={() => onEdit(componentRule)}
                                size="icon"
                                variant="ghost"
                            >
                                <PencilIcon className="size-4" />
                            </Button>

                            <Button
                                aria-label={`Delete ${componentLabel} ${actionLabel} rule`}
                                onClick={() => deleteComponentRuleMutation.mutate({id: componentRule.id})}
                                size="icon"
                                variant="ghost"
                            >
                                <Trash2Icon className="size-4" />
                            </Button>
                        </div>
                    </li>
                );
            })}
        </ul>
    );
};

export default ComponentRuleList;

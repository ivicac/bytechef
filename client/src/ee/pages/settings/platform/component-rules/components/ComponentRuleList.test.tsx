import {TooltipProvider} from '@/components/ui/tooltip';
import ComponentRuleList from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleList';
import {
    ComponentRuleActionType,
    ComponentRulePhase,
    type ComponentRulesQuery,
    useComponentRulesQuery,
} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {render, screen, waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeEach, describe, expect, it, vi} from 'vitest';

const {capturedSaveOptions, deleteMutateMock, saveMutateMock} = vi.hoisted(() => ({
    capturedSaveOptions: {
        current: undefined as
            | {
                  onError?: (error: unknown, variables: unknown, context: unknown) => void;
                  onMutate?: (variables: unknown) => Promise<unknown>;
              }
            | undefined,
    },
    deleteMutateMock: vi.fn(),
    saveMutateMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useDeleteComponentRuleMutation: () => ({mutate: deleteMutateMock}),
        useSaveComponentRuleMutation: (options?: {
            onError?: (error: unknown, variables: unknown, context: unknown) => void;
            onMutate?: (variables: unknown) => Promise<unknown>;
        }) => {
            capturedSaveOptions.current = options;

            return {mutate: saveMutateMock};
        },
    };
});

const blockRule = {
    actionName: 'deleteRecord',
    componentIcon: null,
    componentName: 'salesforce',
    componentTitle: 'Salesforce',
    condition: "inputParameters['ownerId'] != null",
    description: 'Block deleting an owned record',
    enabled: true,
    id: '1',
    phase: ComponentRulePhase.Before,
    ruleAction: ComponentRuleActionType.Block,
};

const tagRule = {
    actionName: null,
    componentIcon: null,
    componentName: 'slack',
    componentTitle: 'Slack',
    condition: "contains(inputParameters['channel'], 'C05')",
    description: null,
    enabled: false,
    id: '2',
    phase: ComponentRulePhase.After,
    ruleAction: ComponentRuleActionType.Tag,
};

const renderList = (onEdit = vi.fn()) =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <TooltipProvider>
                <ComponentRuleList componentRules={[blockRule, tagRule]} onEdit={onEdit} />
            </TooltipProvider>
        </QueryClientProvider>
    );

// The optimistic switch flip is only visible through a parent that is actually subscribed to the
// `ComponentRules` query cache (as `ComponentRulesTab` is in production) — `ComponentRuleList` itself
// only ever renders from props. This harness reproduces that subscription so the rollback test can
// assert against the rendered switch, not just against the cache.
const renderSubscribedList = (queryClient: QueryClient) => {
    const Harness = () => {
        const {data} = useComponentRulesQuery({componentName: undefined});

        return (
            <TooltipProvider>
                <ComponentRuleList componentRules={data?.componentRules ?? []} onEdit={vi.fn()} />
            </TooltipProvider>
        );
    };

    return render(
        <QueryClientProvider client={queryClient}>
            <Harness />
        </QueryClientProvider>
    );
};

describe('ComponentRuleList', () => {
    beforeEach(() => {
        capturedSaveOptions.current = undefined;
        deleteMutateMock.mockReset();
        saveMutateMock.mockReset();
    });

    it('renders the action name for a scoped rule and a placeholder for an all-actions rule', () => {
        renderList();

        expect(screen.getByText('deleteRecord')).toBeInTheDocument();
        expect(screen.getByText('All actions')).toBeInTheDocument();
    });

    it('renders the phase and enforcement badges', () => {
        renderList();

        expect(screen.getByText('Before')).toBeInTheDocument();
        expect(screen.getByText('After')).toBeInTheDocument();
        expect(screen.getByText('Block')).toBeInTheDocument();
        expect(screen.getByText('Tag')).toBeInTheDocument();
    });

    it('reflects the stored enabled flag on each switch', () => {
        renderList();

        expect(screen.getByRole('switch', {name: 'Salesforce deleteRecord'})).toBeChecked();
        expect(screen.getByRole('switch', {name: 'Slack all actions'})).not.toBeChecked();
    });

    it('saves the whole rule with the flipped enabled flag when a switch is toggled', async () => {
        renderList();

        await userEvent.click(screen.getByRole('switch', {name: 'Salesforce deleteRecord'}));

        expect(saveMutateMock).toHaveBeenCalledWith({
            actionName: 'deleteRecord',
            componentName: 'salesforce',
            condition: "inputParameters['ownerId'] != null",
            description: 'Block deleting an owned record',
            enabled: false,
            id: '1',
            phase: ComponentRulePhase.Before,
            ruleAction: ComponentRuleActionType.Block,
        });
    });

    it('calls onEdit with the clicked rule', async () => {
        const onEdit = vi.fn();

        renderList(onEdit);

        await userEvent.click(screen.getByRole('button', {name: 'Edit Salesforce deleteRecord rule'}));

        expect(onEdit).toHaveBeenCalledWith(blockRule);
    });

    it('deletes by id', async () => {
        renderList();

        await userEvent.click(screen.getByRole('button', {name: 'Delete Salesforce deleteRecord rule'}));

        expect(deleteMutateMock).toHaveBeenCalledWith({id: '1'});
    });

    it('reverts the switch to its prior enabled value when the save fails', async () => {
        const queryClient = new QueryClient({defaultOptions: {queries: {retry: false, staleTime: Infinity}}});

        const queryKey = ['ComponentRules', {componentName: undefined}];

        queryClient.setQueryData<ComponentRulesQuery>(queryKey, {componentRules: [blockRule, tagRule]});

        renderSubscribedList(queryClient);

        const switchElement = screen.getByRole('switch', {name: 'Salesforce deleteRecord'});

        expect(switchElement).toBeChecked();

        await userEvent.click(switchElement);

        const variables = {
            actionName: 'deleteRecord',
            componentName: 'salesforce',
            condition: "inputParameters['ownerId'] != null",
            description: 'Block deleting an owned record',
            enabled: false,
            id: '1',
            phase: ComponentRulePhase.Before,
            ruleAction: ComponentRuleActionType.Block,
        };

        const context = await capturedSaveOptions.current?.onMutate?.(variables);

        await waitFor(() => {
            expect(screen.getByRole('switch', {name: 'Salesforce deleteRecord'})).not.toBeChecked();
        });

        capturedSaveOptions.current?.onError?.(new Error('save failed'), variables, context);

        await waitFor(() => {
            expect(screen.getByRole('switch', {name: 'Salesforce deleteRecord'})).toBeChecked();
        });
    });
});

import ComponentRuleDialog from '@/ee/pages/settings/platform/component-rules/components/ComponentRuleDialog';
import {ComponentRuleActionType, ComponentRulePhase} from '@/shared/middleware/graphql';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {fireEvent, render, screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {beforeAll, beforeEach, describe, expect, it, vi} from 'vitest';

beforeAll(() => {
    // Radix relies on pointer-capture APIs that jsdom does not implement.
    Element.prototype.hasPointerCapture = vi.fn(() => false);
    Element.prototype.setPointerCapture = vi.fn();
    Element.prototype.releasePointerCapture = vi.fn();
});

const {saveMutateMock} = vi.hoisted(() => ({
    saveMutateMock: vi.fn(),
}));

vi.mock('@/shared/middleware/graphql', async (importOriginal) => {
    const actual = await importOriginal<typeof import('@/shared/middleware/graphql')>();

    return {
        ...actual,
        useComponentPoliciesQuery: () => ({
            data: {
                componentPolicies: [
                    {
                        description: null,
                        enabled: true,
                        icon: null,
                        name: 'salesforce',
                        title: 'Salesforce',
                        version: 1,
                    },
                    {description: null, enabled: true, icon: null, name: 'slack', title: 'Slack', version: 1},
                ],
            },
        }),
        useSaveComponentRuleMutation: () => ({isPending: false, mutate: saveMutateMock}),
    };
});

vi.mock('@/shared/queries/platform/componentDefinitions.queries', () => ({
    ComponentDefinitionKeys: {componentDefinition: () => ['componentDefinition']},
    useGetComponentDefinitionQuery: () => ({
        data: {actions: [{name: 'sendMessage', title: 'Send Message'}], name: 'slack'},
    }),
}));

const existingRule = {
    actionName: 'sendMessage',
    componentIcon: null,
    componentName: 'slack',
    componentTitle: 'Slack',
    condition: "contains(inputParameters['channel'], 'C05')",
    description: 'Flag messages to the incident channel',
    enabled: true,
    id: '3',
    phase: ComponentRulePhase.Before,
    ruleAction: ComponentRuleActionType.Tag,
};

const renderDialog = (componentRule?: typeof existingRule) =>
    render(
        <QueryClientProvider client={new QueryClient()}>
            <ComponentRuleDialog componentRule={componentRule} onOpenChange={vi.fn()} open={true} />
        </QueryClientProvider>
    );

describe('ComponentRuleDialog', () => {
    beforeEach(() => {
        saveMutateMock.mockReset();
    });

    it('disables the Block option when the After phase is selected', async () => {
        renderDialog();

        expect(screen.getByRole('radio', {name: 'Block'})).toBeEnabled();

        await userEvent.click(screen.getByRole('radio', {name: 'After'}));

        expect(screen.getByRole('radio', {name: 'Block'})).toBeDisabled();
    });

    it('falls back to Tag when After is selected while Block was chosen', async () => {
        renderDialog();

        await userEvent.click(screen.getByRole('radio', {name: 'Block'}));
        await userEvent.click(screen.getByRole('radio', {name: 'After'}));

        expect(screen.getByRole('radio', {name: 'Tag'})).toBeChecked();
    });

    it('prefills every field when editing an existing rule', () => {
        renderDialog(existingRule);

        expect(screen.getByLabelText('Condition')).toHaveValue("contains(inputParameters['channel'], 'C05')");
        expect(screen.getByLabelText('Description')).toHaveValue('Flag messages to the incident channel');
        expect(screen.getByRole('radio', {name: 'Before'})).toBeChecked();
        expect(screen.getByRole('radio', {name: 'Tag'})).toBeChecked();
    });

    it('submits the id when editing so the save updates in place', async () => {
        renderDialog(existingRule);

        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(saveMutateMock).toHaveBeenCalledWith(
            expect.objectContaining({
                actionName: 'sendMessage',
                componentName: 'slack',
                enabled: true,
                id: '3',
                phase: ComponentRulePhase.Before,
                ruleAction: ComponentRuleActionType.Tag,
            }),
            expect.anything()
        );
    });

    it('collapses newlines in the condition before submitting', async () => {
        renderDialog();

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Slack'}));

        // fireEvent.change (rather than userEvent.type) sets the multi-line value directly — userEvent's keyboard
        // parser treats the condition's literal `[`/`]` characters as special key syntax and rejects the string.
        fireEvent.change(screen.getByLabelText('Condition'), {
            target: {value: "contains(inputParameters['channel'],\n'C05')"},
        });

        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(saveMutateMock).toHaveBeenCalledWith(
            expect.objectContaining({condition: "contains(inputParameters['channel'], 'C05')"}),
            expect.anything()
        );
    });

    it('submits without an id when adding', async () => {
        renderDialog();

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Slack'}));
        await userEvent.type(screen.getByLabelText('Condition'), 'true');
        await userEvent.click(screen.getByRole('button', {name: 'Save'}));

        expect(saveMutateMock).toHaveBeenCalledWith(
            expect.objectContaining({actionName: null, componentName: 'slack', id: undefined}),
            expect.anything()
        );
    });

    it('keeps Save disabled until a component and a condition are supplied', async () => {
        renderDialog();

        expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled();

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Slack'}));

        expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled();

        await userEvent.type(screen.getByLabelText('Condition'), 'true');

        expect(screen.getByRole('button', {name: 'Save'})).toBeEnabled();
    });

    it('resets the action back to All actions when the component is changed', async () => {
        renderDialog();

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Slack'}));

        await userEvent.click(screen.getByRole('combobox', {name: 'Action'}));
        await userEvent.click(screen.getByRole('option', {name: 'sendMessage'}));

        expect(screen.getByRole('combobox', {name: 'Action'})).toHaveTextContent('sendMessage');

        await userEvent.click(screen.getByRole('combobox', {name: 'Component'}));
        await userEvent.click(screen.getByRole('option', {name: 'Salesforce'}));

        expect(screen.getByRole('combobox', {name: 'Action'})).toHaveTextContent('All actions');
    });
});

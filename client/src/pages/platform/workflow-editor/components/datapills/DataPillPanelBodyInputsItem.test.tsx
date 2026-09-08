import {Accordion, AccordionItem} from '@radix-ui/react-accordion';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import DataPillPanelBodyInputsItem from './DataPillPanelBodyInputsItem';

// Minimal DataPill mock: renders ONLY property.name, so a pill appears here only if the component itself renders it.
vi.mock('./DataPill', () => ({
    default: ({property}: {property?: {name?: string}}) => <div data-testid="data-pill">{property?.name}</div>,
}));

vi.mock('@/shared/queries/platform/workflowTestConfigurations.queries', () => ({
    useGetWorkflowTestConfigurationQuery: () => ({
        data: {
            inputs: {
                contactMapping: JSON.stringify({
                    Contacts: {applicationFields: {fields: [{label: 'Title', value: 'title'}]}},
                }),
            },
        },
    }),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: (selector: (state: {currentEnvironmentId: number}) => unknown) =>
        selector({currentEnvironmentId: 1}),
}));

vi.mock('../../stores/useWorkflowDataStore', () => ({
    default: (selector: (state: {workflow: unknown}) => unknown) =>
        selector({
            workflow: {
                id: 'w1',
                inputs: [
                    {name: 'contactMapping', type: 'field_mapping'},
                    {name: 'apiKey', type: 'string'},
                ],
            },
        }),
}));

describe('DataPillPanelBodyInputsItem', () => {
    it('renders a single root pill for a field_mapping input and no synthetic child pills', () => {
        render(
            <Accordion collapsible defaultValue="inputs" type="single">
                <AccordionItem value="inputs">
                    <DataPillPanelBodyInputsItem dataPillFilterQuery="" />
                </AccordionItem>
            </Accordion>
        );

        expect(screen.getByText('contactMapping')).toBeInTheDocument();

        // The runtime value of a field_mapping input is the mapping descriptor, not a mapped object, so the
        // per-application-field pills the panel used to synthesize from the test value would advertise paths that
        // never exist. Mapped-object pills now come from the field-mapping action node's test-run output instead.
        expect(screen.queryByText('title')).not.toBeInTheDocument();

        // Two pills total: contactMapping root + apiKey root.
        expect(screen.getAllByTestId('data-pill')).toHaveLength(2);
    });

    it('filters inputs by the data pill filter query', () => {
        render(
            <Accordion collapsible defaultValue="inputs" type="single">
                <AccordionItem value="inputs">
                    <DataPillPanelBodyInputsItem dataPillFilterQuery="api" />
                </AccordionItem>
            </Accordion>
        );

        expect(screen.getByText('apiKey')).toBeInTheDocument();
        expect(screen.queryByText('contactMapping')).not.toBeInTheDocument();
    });
});

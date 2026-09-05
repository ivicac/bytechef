import {NodeDataType, PropertyAllType} from '@/shared/types';
import {render, screen} from '@testing-library/react';
import {describe, expect, it, vi} from 'vitest';

import OutputSchemaDisplay from './OutputSchemaDisplay';

vi.mock('@/pages/platform/workflow-editor/components/PropertyField', () => ({
    default: () => <div data-testid="property-field" />,
}));

vi.mock('@/pages/platform/workflow-editor/components/SchemaProperties', () => ({
    default: () => <div data-testid="schema-properties" />,
}));

const currentNode = {componentName: 'callAiAgent', name: 'callAiAgent_1'} as NodeDataType;

const outputSchema = {
    properties: [{name: 'message', type: 'STRING'}],
    type: 'OBJECT',
} as PropertyAllType;

const variableOutputSchema = {
    properties: [{name: 'item', type: 'STRING'}],
    type: 'OBJECT',
} as PropertyAllType;

const renderDisplay = (props: Partial<Parameters<typeof OutputSchemaDisplay>[0]> = {}) =>
    render(
        <OutputSchemaDisplay
            connectionMissing={false}
            copiedValue={null}
            copyToClipboard={vi.fn()}
            currentNode={currentNode}
            handlePredefinedOutputSchemaClick={vi.fn()}
            handleTestOperationClick={vi.fn()}
            outputDefined
            outputSchema={outputSchema}
            sampleOutput={{message: 'sample message'}}
            saveWorkflowNodeTestOutputMutation={{isPending: false}}
            setShowUploadDialog={vi.fn()}
            {...props}
        />
    );

describe('OutputSchemaDisplay', () => {
    it('should not show the Item Schema heading when the dispatcher declares variable properties but has no schema for them', () => {
        renderDisplay({variablePropertiesDefined: true});

        expect(screen.queryByText('Item Schema')).not.toBeInTheDocument();
    });

    it('should show the Item Schema heading once there is a schema to put under it', () => {
        renderDisplay({variableOutputSchema, variablePropertiesDefined: true});

        expect(screen.getByText('Item Schema')).toBeInTheDocument();
    });

    it('should still show the Output Schema heading either way', () => {
        renderDisplay({variablePropertiesDefined: true});

        expect(screen.getByText('Output Schema')).toBeInTheDocument();
    });
});

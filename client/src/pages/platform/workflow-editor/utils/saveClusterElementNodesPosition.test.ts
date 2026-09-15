import {NodeDataType} from '@/shared/types';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useClusterElementsDataStore from '../../cluster-element-editor/stores/useClusterElementsDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import saveClusterElementNodesPosition from './saveClusterElementNodesPosition';

const {saveWorkflowDefinitionMock} = vi.hoisted(() => ({saveWorkflowDefinitionMock: vi.fn()}));

vi.mock('./saveWorkflowDefinition', () => ({default: saveWorkflowDefinitionMock}));

const VOICE_SESSION_DEFINITION = JSON.stringify({
    tasks: [],
    triggers: [
        {
            clusterElements: {
                tools: [],
                voiceAgent: {
                    metadata: {ui: {nodePosition: {x: 40, y: 20}}},
                    name: 'voiceAgent_1',
                    parameters: {},
                    type: 'deepgram/v1/voiceAgent',
                },
            },
            name: 'trigger_1',
            parameters: {},
            type: 'browser/v1/voiceSession',
        },
    ],
});

describe('saveClusterElementNodesPosition', () => {
    beforeEach(() => {
        saveWorkflowDefinitionMock.mockReset();
        saveWorkflowDefinitionMock.mockResolvedValue(undefined);

        useClusterElementsDataStore.setState({nodes: []});
        useWorkflowEditorStore.setState({
            rootClusterElementNodeData: {
                componentName: 'browser',
                workflowNodeName: 'trigger_1',
            } as NodeDataType,
        });
    });

    it('clears an element position inside a trigger cluster root', () => {
        saveClusterElementNodesPosition({
            clickedNodeName: 'voiceAgent_1',
            updateWorkflowMutation: {mutate: vi.fn()} as never,
            workflow: {definition: VOICE_SESSION_DEFINITION, id: 'workflow-1'},
        });

        expect(saveWorkflowDefinitionMock).toHaveBeenCalledTimes(1);

        const {nodeData} = saveWorkflowDefinitionMock.mock.calls[0][0];

        expect(nodeData.workflowNodeName).toBe('trigger_1');
        expect(nodeData.type).toBe('browser/v1/voiceSession');
        expect(nodeData.clusterElements.voiceAgent.metadata?.ui?.nodePosition).toBeUndefined();
    });
});

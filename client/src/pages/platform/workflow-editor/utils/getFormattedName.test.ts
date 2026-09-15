import {beforeEach, describe, expect, it} from 'vitest';

import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import getFormattedName from './getFormattedName';

function setWorkflowDefinition(definition: Record<string, unknown>) {
    useWorkflowDataStore.setState({
        nodes: [],
        workflow: {definition: JSON.stringify(definition), id: 'workflow-1'},
    } as unknown as Parameters<typeof useWorkflowDataStore.setState>[0]);
}

describe('getFormattedName', () => {
    beforeEach(() => {
        setWorkflowDefinition({tasks: [], triggers: []});
    });

    it('numbers the first node of a component 1', () => {
        expect(getFormattedName('httpClient')).toBe('httpClient_1');
    });

    it('counts the element names inside a task cluster root', () => {
        setWorkflowDefinition({
            tasks: [
                {
                    clusterElements: {tools: [{name: 'httpClient_1', type: 'httpClient/v1/get'}]},
                    name: 'aiAgent_1',
                    type: 'aiAgent/v1/chat',
                },
            ],
            triggers: [],
        });

        expect(getFormattedName('httpClient')).toBe('httpClient_2');
    });

    // Two tools of one component under the voice trigger both came out `httpClient_1`, and deleting
    // one then removed both, because element deletion matches by name.
    it('counts the element names inside a trigger cluster root, in single slots and lists alike', () => {
        setWorkflowDefinition({
            tasks: [],
            triggers: [
                {
                    clusterElements: {
                        tools: [
                            {
                                clusterElements: {model: {name: 'openAi_3', type: 'openAi/v1/model'}},
                                name: 'httpClient_1',
                                type: 'httpClient/v1/get',
                            },
                        ],
                        voiceAgent: {name: 'deepgram_2', type: 'deepgram/v1/voiceAgent'},
                    },
                    name: 'trigger_1',
                    type: 'browser/v1/voiceSession',
                },
            ],
        });

        expect(getFormattedName('httpClient')).toBe('httpClient_2');
        expect(getFormattedName('deepgram')).toBe('deepgram_3');
        expect(getFormattedName('openAi')).toBe('openAi_4');
    });
});

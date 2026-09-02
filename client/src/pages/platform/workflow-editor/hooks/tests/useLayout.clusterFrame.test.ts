import {WorkflowTask} from '@/shared/middleware/platform/configuration';
import {describe, expect, it} from 'vitest';

import {getTasksStructuralFingerprint} from '../useLayout';

const AGENT_TASK = {
    clusterElements: {
        model: {metadata: {ui: {nodePosition: {x: 100, y: 40}}}, name: 'model_1', type: 'openai/v1/model'},
    },
    name: 'aiAgent_1',
    type: 'aiAgent/v1/chat',
} as unknown as WorkflowTask;

describe('getTasksStructuralFingerprint with cluster elements', () => {
    it('changes when an element is added', () => {
        const withTool = {
            ...AGENT_TASK,
            clusterElements: {
                ...AGENT_TASK.clusterElements,
                tools: [{name: 'tool_1', type: 'slack/v1/sendMessage'}],
            },
        } as unknown as WorkflowTask;

        expect(getTasksStructuralFingerprint([AGENT_TASK])).not.toBe(getTasksStructuralFingerprint([withTool]));
    });

    // Both fixtures already carry a `metadata.ui.nodePosition` -- this pins the position VALUE
    // changing (x: 100 -> x: 900), not merely a position being added where there was none. A test
    // that only pins presence-vs-absence would pass even if collectClusterElementsSignature ignored
    // the actual x/y values, which is exactly the bug this test needs to catch.
    it('changes when an element position moves, because the box size follows it', () => {
        const moved = {
            ...AGENT_TASK,
            clusterElements: {
                model: {metadata: {ui: {nodePosition: {x: 900, y: 40}}}, name: 'model_1', type: 'openai/v1/model'},
            },
        } as unknown as WorkflowTask;

        expect(getTasksStructuralFingerprint([AGENT_TASK])).not.toBe(getTasksStructuralFingerprint([moved]));
    });

    // The invariant that matters most: this fingerprint is the equality function `storeTasks` is
    // subscribed with in useLayout.tsx, whose documented job is to skip a relayout on parameter-only
    // changes (typing) -- so a cluster element's OWN parameters/connections changing must leave the
    // fingerprint alone, exactly like a plain task's `parameters` already does elsewhere in this file.
    it('does not change when a cluster element parameter or connection changes', () => {
        const withParametersAndConnections = {
            ...AGENT_TASK,
            clusterElements: {
                model: {
                    connections: {conn_1: {componentName: 'openai', id: 123}},
                    metadata: {ui: {nodePosition: {x: 100, y: 40}}},
                    name: 'model_1',
                    parameters: {temperature: 0.9},
                    type: 'openai/v1/model',
                },
            },
        } as unknown as WorkflowTask;

        expect(getTasksStructuralFingerprint([AGENT_TASK])).toBe(
            getTasksStructuralFingerprint([withParametersAndConnections])
        );
    });
});

import {readFileSync} from 'node:fs';
import {describe, expect, it} from 'vitest';

import {buildOverview, flatToolName} from '../template/src/server/overview';

const fixture = JSON.parse(readFileSync(new URL('../../scripts/fixtures/app.json', import.meta.url), 'utf8'));
const tools = fixture.tools.map((tool: {parameters: string}) => ({...tool, parameters: JSON.parse(tool.parameters)}));

describe('buildOverview', () => {
    it('lists allowed actions even when the component is unavailable and attaches schemas where known', () => {
        const overview = buildOverview(fixture.connections, fixture.actions, true, tools, fixture.workflows);

        expect(overview.actionSchemas).toBe(true);
        expect(overview.bindings.map((binding) => binding.bindingKey)).toEqual(['crm', 'mail']);
        expect(overview.bindings[0]!.actions[0]).toMatchObject({
            inputSchema: {required: ['email']},
            name: 'createContact',
        });
        expect(overview.bindings[1]).toMatchObject({
            actions: [{inputSchema: null, name: 'send'}],
            componentVersion: null,
        });
        expect(overview.workflows.map((workflow) => workflow.workflowKey)).toEqual(['onboard-customer']);
    });

    it('nests listed tools with their parsed parameters', () => {
        const overview = buildOverview(fixture.connections, fixture.actions, true, tools, fixture.workflows);

        expect(overview.bindings[0]!.tools).toEqual([
            {
                description: 'Search contacts',
                name: 'CRM__SEARCH_CONTACTS',
                parameters: {properties: {q: {type: 'string'}}, type: 'object'},
                toolName: 'searchContacts',
            },
        ]);
    });

    it('keeps an allowed tool that the tool listing omits, with null parameters', () => {
        const overview = buildOverview(fixture.connections, fixture.actions, true, tools, fixture.workflows);

        expect(overview.bindings[1]!.tools).toEqual([
            {description: null, name: 'MAIL__SEND_MESSAGE', parameters: null, toolName: 'sendMessage'},
        ]);
    });

    it('marks actionSchemas false and keeps action names when the server has no action listing', () => {
        const overview = buildOverview(fixture.connections, [], false, [], []);

        expect(overview.actionSchemas).toBe(false);
        expect(overview.bindings[0]!.actions).toEqual([{description: null, inputSchema: null, name: 'createContact'}]);
    });
});

describe('flatToolName', () => {
    it('joins the binding key and the upper snake case tool name', () => {
        expect(flatToolName('crm', 'searchContacts')).toBe('CRM__SEARCH_CONTACTS');
        expect(flatToolName('my-crm.v2', 'send')).toBe('MY_CRM_V2__SEND');
    });
});

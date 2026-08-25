import {Workflow} from '@/shared/middleware/platform/configuration';
import {TYPE_ICONS} from '@/shared/typeIcons';
import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it} from 'vitest';

import {getDataPillIconSource} from './getDataPillIconSource';

const workflow = {} as Workflow;

const variableIconDataUri = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
    renderToStaticMarkup(TYPE_ICONS.VARIABLE)
)}`;
const defaultStringIconDataUri = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
    renderToStaticMarkup(TYPE_ICONS.STRING)
)}`;

const taskDispatcherDefinitions = [
    {icon: 'fork-join-icon', name: 'fork-join', outputDefined: true, version: 1},
    {icon: 'on-error-icon', name: 'on-error', outputDefined: true, version: 1},
];

describe('getDataPillIconSource', () => {
    it('returns the dispatcher icon for a camelCased dispatcher node name', () => {
        expect(
            getDataPillIconSource({
                mentionDisplay: '${forkJoin_1.branch_0.result}',
                taskDispatcherDefinitions,
                workflow,
            })
        ).toBe('fork-join-icon');
        expect(getDataPillIconSource({mentionDisplay: '${onError_2}', taskDispatcherDefinitions, workflow})).toBe(
            'on-error-icon'
        );
    });

    it('returns the dispatcher icon for a hyphenated dispatcher node name', () => {
        expect(
            getDataPillIconSource({mentionDisplay: '${fork-join_1.branch_0}', taskDispatcherDefinitions, workflow})
        ).toBe('fork-join-icon');
    });

    it('returns the component icon for a component node name', () => {
        expect(
            getDataPillIconSource({
                componentDefinitions: [{icon: 'http-client-icon', name: 'httpClient', version: 1}],
                mentionDisplay: '${httpClient_1.body}',
                workflow,
            })
        ).toBe('http-client-icon');
    });

    it('returns the variable icon for a raw vars.NAME mention display', () => {
        expect(getDataPillIconSource({mentionDisplay: 'vars.API_URL', workflow})).toBe(variableIconDataUri);
    });

    it('returns the variable icon for a wrapped ${vars.NAME} mention display', () => {
        expect(getDataPillIconSource({mentionDisplay: '${vars.API_URL}', workflow})).toBe(variableIconDataUri);
    });

    it('returns the variable icon for the bare vars node name', () => {
        expect(getDataPillIconSource({mentionDisplay: 'vars', workflow})).toBe(variableIconDataUri);
    });

    it('does not treat a component name merely starting with "vars" as the variables node', () => {
        // A hypothetical component pill 'varsomething_1_action' must fall through to the default icon, not be
        // misidentified as a variable.
        expect(getDataPillIconSource({mentionDisplay: 'varsomething_1_action', workflow})).toBe(
            defaultStringIconDataUri
        );
    });
});

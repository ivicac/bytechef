vi.mock('@/pages/platform/workflow-editor/utils/saveProperty', () => ({
    default: vi.fn(),
}));

import {workflowEditorProviderTestValue} from '@/pages/platform/workflow-editor/providers/tests/workflowEditorProviderTestValue';
import {WorkflowEditorProvider} from '@/pages/platform/workflow-editor/providers/workflowEditorProvider';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import saveProperty from '@/pages/platform/workflow-editor/utils/saveProperty';
import {PropertyAllType} from '@/shared/types';
import {act, renderHook} from '@testing-library/react';
import {ReactNode} from 'react';
import {type Mock, beforeEach, describe, expect, it, vi} from 'vitest';

import {useProperty} from '../useProperty';

const wrapper = ({children}: {children: ReactNode}) => (
    <WorkflowEditorProvider value={workflowEditorProviderTestValue as never}>{children}</WorkflowEditorProvider>
);

const countProperty = {
    controlType: 'INTEGER',
    expressionEnabled: true,
    name: 'count',
    type: 'INTEGER',
} as PropertyAllType;

const renderCount = (parameterValue?: unknown) =>
    renderHook(() => useProperty({parameterValue, path: 'parameters.count', property: countProperty}), {wrapper});

describe('uncontrolled Text/Formula', () => {
    beforeEach(() => {
        useWorkflowDataStore.setState({
            workflow: {id: 'wf-formula-test', nodeNames: []},
        } as unknown as Partial<ReturnType<typeof useWorkflowDataStore.getState>>);

        useWorkflowNodeDetailsPanelStore.setState({
            currentNode: {name: 'httpClient_1', parameters: {}, workflowNodeName: 'httpClient_1'},
            workflowNodeDetailsPanelOpen: true,
        } as unknown as Partial<ReturnType<typeof useWorkflowNodeDetailsPanelStore.getState>>);

        (saveProperty as unknown as Mock).mockReset();
    });

    it('renders a constant natively', () => {
        const {result} = renderCount(5);

        expect(result.current.inputMode).toMatchObject({mode: 'text', renderer: 'native'});
        expect(result.current.mentionInput).toBe(false);
    });

    it('renders a lone pill in the one-pill editor', () => {
        const {result} = renderCount('${trigger_1.count}');

        expect(result.current.inputMode).toMatchObject({renderer: 'mentions', singlePill: true});
    });

    it('converts a constant into a formula and saves it', () => {
        const {result} = renderCount(5);

        act(() => result.current.handleFormulaSwitch());

        expect(result.current.isFormulaMode).toBe(true);
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({includeInMetadata: true, value: '=5'}));
    });

    it('converts a formula literal back into a constant', () => {
        const {result} = renderCount('=7');

        act(() => result.current.handleFormulaSwitch());

        expect(result.current.isFormulaMode).toBe(false);
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: 7}));
    });

    it('clears a formula that cannot convert back', () => {
        const {result} = renderCount('=concat(a, b)');

        act(() => result.current.handleFormulaSwitch());

        expect(result.current.isFormulaMode).toBe(false);
        expect(result.current.propertyParameterValue).toBe('');
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: null}));
    });

    it('replaces a constant with a pill and saves the pill', () => {
        const {result} = renderCount(5);

        act(() => result.current.insertPillValue('trigger_1.count'));

        expect(result.current.propertyParameterValue).toBe('${trigger_1.count}');
        expect(result.current.inputMode.singlePill).toBe(true);
        expect(saveProperty).toHaveBeenLastCalledWith(expect.objectContaining({value: '${trigger_1.count}'}));
    });

    it('goes back to the native control when the pill is deleted', () => {
        const {result} = renderCount('${trigger_1.count}');

        act(() => result.current.handleMentionInputValueChange(''));

        expect(result.current.inputMode.renderer).toBe('native');
        expect(result.current.propertyParameterValue).toBe('');
    });

    it('$ on an empty number field opens pill entry and requests editor focus with $', () => {
        const {result} = renderCount('');

        const preventDefault = vi.fn();

        act(() => result.current.handleNativeKeyDown({key: '$', preventDefault} as never));

        expect(preventDefault).toHaveBeenCalled();
        expect(result.current.inputMode).toMatchObject({renderer: 'mentions', singlePill: true});
        expect(result.current.editorFocusRequest?.initialInput).toBe('$');
    });

    it('= on an empty number field enters formula mode', () => {
        const {result} = renderCount('');

        act(() => result.current.handleNativeKeyDown({key: '=', preventDefault: vi.fn()} as never));

        expect(result.current.isFormulaMode).toBe(true);
        expect(result.current.editorFocusRequest).toBeDefined();
    });

    it('ignores $ and = when the field already holds a constant', () => {
        const {result} = renderCount(5);

        const preventDefault = vi.fn();

        act(() => result.current.handleNativeKeyDown({key: '$', preventDefault} as never));

        expect(preventDefault).not.toHaveBeenCalled();
        expect(result.current.inputMode.renderer).toBe('native');
    });

    it('abandoning pill entry returns to the native control without saving', () => {
        const {result} = renderCount('');

        act(() => result.current.handleNativeKeyDown({key: '$', preventDefault: vi.fn()} as never));
        act(() => result.current.handleSinglePillAbandoned());

        expect(result.current.inputMode.renderer).toBe('native');
        expect(saveProperty).not.toHaveBeenCalled();
    });

    it('shows the Formula switch on a STRING property', () => {
        const {result} = renderHook(
            () =>
                useProperty({
                    path: 'parameters.uri',
                    property: {
                        controlType: 'TEXT',
                        expressionEnabled: true,
                        name: 'uri',
                        type: 'STRING',
                    } as PropertyAllType,
                }),
            {wrapper}
        );

        expect(result.current.showFormulaSwitch).toBe(true);
    });

    it('never treats a fromAi value as formula', () => {
        const {result} = renderCount("=fromAi('count', 'INTEGER', {'required': false})");

        expect(result.current.isFormulaMode).toBe(false);
        expect(result.current.showFormulaSwitch).toBe(false);
    });
});

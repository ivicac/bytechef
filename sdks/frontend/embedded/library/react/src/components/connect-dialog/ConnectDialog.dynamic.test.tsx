import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import ConnectDialog from './ConnectDialog';
import {MergedWorkflowType, OptionType} from './types';
import {optionsCacheKey} from './utils';

const baseProps = {
    closeDialog: vi.fn(),
    handleClick: vi.fn(),
    handleWorkflowToggle: vi.fn(),
    handleWorkflowInputChange: vi.fn(),
    handleWorkflowGroupInputChange: vi.fn(),
    integration: {id: 1, name: 'Test Integration'},
    isOpen: true,
    loadWorkflowInputOptions: vi.fn(),
    loading: false,
    mergedMcpTools: [],
    mergedMcpWorkflows: [],
    workflowInputOptions: {} as Record<string, OptionType[]>,
    workflowsView: true,
};

describe('ConnectDialog dynamic inputs', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders a select for a single-property group member with dynamic options and shows resolved labels', () => {
        const workflowUuid = 'wf-1';
        const mergedWorkflows: MergedWorkflowType[] = [
            {
                enabled: true,
                inputs: [
                    {
                        componentReference: {
                            componentName: 'slack',
                            componentVersion: 1,
                            group: {
                                name: 'channel',
                                properties: [{dynamicOptions: true, label: 'Channel', name: 'channelId'}],
                            },
                            groupName: 'channel',
                        },
                        label: 'Channel',
                        name: 'channel',
                        type: 'object',
                    },
                ],
                label: 'Workflow 1',
                workflowUuid,
            },
        ];

        const cacheKey = optionsCacheKey(workflowUuid, 'channel', 'channelId', {});

        render(
            <ConnectDialog
                {...baseProps}
                mergedWorkflows={mergedWorkflows}
                workflowInputOptions={{
                    [cacheKey]: [
                        {label: 'General', value: 'C1'},
                        {label: 'Random', value: 'C2'},
                    ],
                }}
            />
        );

        const select = screen.getByLabelText('Channel') as HTMLSelectElement;

        expect(select.tagName).toBe('SELECT');
        expect(select.disabled).toBe(false);
        expect(screen.getByText('General')).toBeTruthy();
        expect(screen.getByText('Random')).toBeTruthy();

        // The effect loads options for an input whose dependencies are satisfied (here, none).
        expect(baseProps.loadWorkflowInputOptions).toHaveBeenCalledWith(workflowUuid, 'channel', 'channelId', {});
    });

    it('renders the member fields of a property group and reports member changes', () => {
        const workflowUuid = 'wf-2';
        const mergedWorkflows: MergedWorkflowType[] = [
            {
                enabled: true,
                inputs: [
                    {
                        componentReference: {
                            componentName: 'googleSheets',
                            componentVersion: 1,
                            group: {
                                label: 'Spreadsheet location',
                                name: 'location',
                                properties: [
                                    {label: 'Spreadsheet', name: 'spreadsheetId'},
                                    {label: 'Sheet', name: 'sheetName'},
                                ],
                            },
                            groupName: 'location',
                        },
                        label: 'Location',
                        name: 'location',
                        type: 'object',
                    },
                ],
                label: 'Workflow Group',
                workflowUuid,
            },
        ];

        render(<ConnectDialog {...baseProps} mergedWorkflows={mergedWorkflows} />);

        expect(screen.getByText('Spreadsheet location')).toBeTruthy();
        expect(screen.getByLabelText('Spreadsheet')).toBeTruthy();
        expect(screen.getByLabelText('Sheet')).toBeTruthy();

        fireEvent.change(screen.getByLabelText('Spreadsheet'), {target: {value: 'spreadsheet-1'}});

        // A group member change is reported scoped to (workflowUuid, group name, member name).
        expect(baseProps.handleWorkflowGroupInputChange).toHaveBeenCalledWith(
            workflowUuid,
            'location',
            'spreadsheetId',
            'spreadsheet-1'
        );
    });

    it('disables a dependent group member select until its dependency value is present and does not load options', () => {
        const mergedWorkflows: MergedWorkflowType[] = [
            {
                enabled: true,
                inputs: [
                    {
                        componentReference: {
                            componentName: 'slack',
                            componentVersion: 1,
                            group: {
                                name: 'channel',
                                properties: [
                                    {label: 'Workspace', name: 'workspace'},
                                    {
                                        dynamicOptions: true,
                                        label: 'Channel',
                                        name: 'channelId',
                                        optionsLookupDependsOn: ['workspace'],
                                    },
                                ],
                            },
                            groupName: 'channel',
                        },
                        label: 'Channel',
                        name: 'channel',
                        type: 'object',
                    },
                ],
                label: 'Dependent Workflow',
                workflowUuid: 'wf-3',
            },
        ];

        render(<ConnectDialog {...baseProps} mergedWorkflows={mergedWorkflows} />);

        const select = screen.getByLabelText('Channel') as HTMLSelectElement;

        expect(select.disabled).toBe(true);
        expect(screen.getByText('Select dependencies first')).toBeTruthy();

        // Options must not be requested while a dependency is unsatisfied.
        expect(baseProps.loadWorkflowInputOptions).not.toHaveBeenCalled();
    });

    it('loads options once a previously unsatisfied group-member dependency becomes available', () => {
        const workflowUuid = 'wf-3';
        const buildWorkflows = (workspaceValue: string): MergedWorkflowType[] => [
            {
                enabled: true,
                inputs: [
                    {
                        componentReference: {
                            componentName: 'slack',
                            componentVersion: 1,
                            group: {
                                name: 'channel',
                                properties: [
                                    {label: 'Workspace', name: 'workspace'},
                                    {
                                        dynamicOptions: true,
                                        label: 'Channel',
                                        name: 'channelId',
                                        optionsLookupDependsOn: ['workspace'],
                                    },
                                ],
                            },
                            groupName: 'channel',
                        },
                        label: 'Channel',
                        name: 'channel',
                        type: 'object',
                        value: {workspace: workspaceValue},
                    },
                ],
                label: 'Dependent Workflow',
                workflowUuid,
            },
        ];

        const {rerender} = render(<ConnectDialog {...baseProps} mergedWorkflows={buildWorkflows('')} />);

        expect(baseProps.loadWorkflowInputOptions).not.toHaveBeenCalled();

        rerender(<ConnectDialog {...baseProps} mergedWorkflows={buildWorkflows('W1')} />);

        expect(baseProps.loadWorkflowInputOptions).toHaveBeenCalledWith(workflowUuid, 'channel', 'channelId', {
            workspace: 'W1',
        });
    });

    it('falls back to a plain text input when a component reference has no resolved group', () => {
        const mergedWorkflows: MergedWorkflowType[] = [
            {
                enabled: true,
                inputs: [
                    {
                        componentReference: {componentName: 'slack', componentVersion: 1, groupName: 'missing'},
                        label: 'Dangling',
                        name: 'dangling',
                        type: 'string',
                    },
                ],
                label: 'Dangling Workflow',
                workflowUuid: 'wf-4',
            },
        ];

        render(<ConnectDialog {...baseProps} mergedWorkflows={mergedWorkflows} />);

        const input = screen.getByLabelText('Dangling') as HTMLInputElement;

        expect(input.tagName).toBe('INPUT');
    });
});

describe('optionsCacheKey', () => {
    it('produces distinct keys for distinct dependency values and a stable key for equal values', () => {
        const first = optionsCacheKey('wf', 'channel', 'channelId', {teamId: 'T1'});
        const second = optionsCacheKey('wf', 'channel', 'channelId', {teamId: 'T2'});
        const repeated = optionsCacheKey('wf', 'channel', 'channelId', {teamId: 'T1'});

        expect(first).not.toBe(second);
        expect(first).toBe(repeated);
    });

    it('does not collide for distinct inputs that share a property name', () => {
        const topLevel = optionsCacheKey('wf', 'channel', 'channelId', {});
        const groupMember = optionsCacheKey('wf', 'location', 'channelId', {});

        expect(topLevel).not.toBe(groupMember);
    });
});

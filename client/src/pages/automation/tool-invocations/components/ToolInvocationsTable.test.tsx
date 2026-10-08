import {render, screen} from '@testing-library/react';
import {describe, expect, it} from 'vitest';

import ToolInvocationsTable from './ToolInvocationsTable';

const baseLog = {
    appId: null,
    componentName: 'slack',
    componentVersion: 1,
    connectedUserId: null,
    connectionId: null,
    createdDate: 1_000,
    durationMs: 42,
    environment: null,
    errorMessage: null,
    errorType: null,
    externalUserId: null,
    id: '1',
    integrationInstanceId: null,
    jobId: null,
    kind: 'COMPONENT',
    mcpServerId: null,
    operationName: 'sendMessage',
    outcome: 'SUCCESS',
    surface: 'MCP_AUTOMATION',
    toolName: 'slack_sendMessage',
    workspaceId: null,
};

describe('ToolInvocationsTable', () => {
    it('renders a row with the tool name, mapped surface label and outcome', () => {
        render(<ToolInvocationsTable toolInvocationLogs={[baseLog]} />);

        expect(screen.getByText('slack_sendMessage')).toBeInTheDocument();
        expect(screen.getByText('Automation MCP')).toBeInTheDocument();
        expect(screen.getByText('SUCCESS')).toBeInTheDocument();
        expect(screen.getByText('42 ms')).toBeInTheDocument();
        expect(screen.getByText('slack / sendMessage')).toBeInTheDocument();
    });

    it('renders the app surface labels', () => {
        render(
            <ToolInvocationsTable
                toolInvocationLogs={[
                    {...baseLog, appId: 5, id: '3', surface: 'AUTOMATION_APP_ACTION'},
                    {...baseLog, appId: 5, id: '4', surface: 'AUTOMATION_APP_TOOL'},
                    {...baseLog, appId: 5, id: '5', surface: 'AUTOMATION_APP_PROXY'},
                    {...baseLog, appId: 5, id: '6', surface: 'AUTOMATION_APP_WORKFLOW'},
                    {...baseLog, appId: 5, id: '7', surface: 'AUTOMATION_APP_API'},
                ]}
            />
        );

        expect(screen.getByText('App action')).toBeInTheDocument();
        expect(screen.getByText('App tool')).toBeInTheDocument();
        expect(screen.getByText('App proxy')).toBeInTheDocument();
        expect(screen.getByText('App workflow')).toBeInTheDocument();
        expect(screen.getByText('App API')).toBeInTheDocument();
    });

    it('renders an em dash when the tool name is missing', () => {
        render(<ToolInvocationsTable toolInvocationLogs={[{...baseLog, id: '2', toolName: null}]} />);

        expect(screen.getByText('—')).toBeInTheDocument();
    });
});

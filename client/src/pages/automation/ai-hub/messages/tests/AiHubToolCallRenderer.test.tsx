import AiHubToolCallRenderer from '@/pages/automation/ai-hub/messages/AiHubToolCallRenderer';
import {aiChatToolCallStore} from '@/shared/components/ai-chat/stores/useAiChatToolCallStore';
import {fireEvent, render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it} from 'vitest';

describe('AiHubToolCallRenderer', () => {
    beforeEach(() => {
        aiChatToolCallStore.setState({order: [], toolCalls: {}});
    });

    it('renders the tool name in the header', () => {
        render(<AiHubToolCallRenderer toolCallId="call-1" toolName="getFile" />);

        expect(screen.getByText('getFile')).toBeInTheDocument();
    });

    it('renders an args summary in the header when args are present', () => {
        render(
            <AiHubToolCallRenderer
                args={{name: 'spec.md'}}
                result={{ok: true}}
                toolCallId="call-1"
                toolName="openFile"
            />
        );

        expect(screen.getByText(/name: spec.md/i)).toBeInTheDocument();
    });

    it('starts collapsed for unknown tool names and expands on click', () => {
        render(
            <AiHubToolCallRenderer
                args={{path: 'README.md'}}
                result={{ok: true}}
                toolCallId="call-1"
                toolName="readFile"
            />
        );

        // The body label "Input" should not be visible while collapsed.
        expect(screen.queryByText('Input')).toBeNull();

        fireEvent.click(screen.getByRole('button', {expanded: false}));

        expect(screen.getByText('Input')).toBeInTheDocument();
    });

    it('renders the running spinner when no result is available', () => {
        const {container} = render(<AiHubToolCallRenderer toolCallId="call-1" toolName="research" />);

        expect(container.querySelector('.animate-spin')).not.toBeNull();
    });

    it('renders an error icon when isError=true', () => {
        const {container} = render(
            <AiHubToolCallRenderer isError result={{error: 'boom'}} toolCallId="call-1" toolName="createFile" />
        );

        // AlertCircleIcon uses the lucide alert-circle class fragment.
        expect(container.querySelector('.text-content-error-primary')).not.toBeNull();
    });

    it('renders runChatWorkflow with per-step sections from the store progressive output', () => {
        aiChatToolCallStore.getState().startToolCall('call-rcw', 'runChatWorkflow', 0);
        aiChatToolCallStore.getState().appendProgressiveOutput('call-rcw', 'Step 1 ran fine\n\nStep 2 also ran');

        render(<AiHubToolCallRenderer toolCallId="call-rcw" toolName="runChatWorkflow" />);

        // runChatWorkflow auto-expands.
        expect(screen.getByText(/Step 1$/)).toBeInTheDocument();
        expect(screen.getByText(/Step 2$/)).toBeInTheDocument();
        expect(screen.getByText(/Step 1 ran fine/)).toBeInTheDocument();
        expect(screen.getByText(/Step 2 also ran/)).toBeInTheDocument();
    });

    it('renders a subagent tool call as a progress breadcrumb', () => {
        aiChatToolCallStore.getState().startToolCall('call-r', 'research', 0);
        aiChatToolCallStore.getState().addProgress('call-r', 'Searching the web');
        aiChatToolCallStore.getState().addProgress('call-r', 'Synthesizing answer');

        render(<AiHubToolCallRenderer toolCallId="call-r" toolName="research" />);

        expect(screen.getByText('Searching the web')).toBeInTheDocument();
        expect(screen.getByText('Synthesizing answer')).toBeInTheDocument();
        expect(screen.getByText('subagent')).toBeInTheDocument();
    });

    it('renders memory tool call with name and type badge', () => {
        render(
            <AiHubToolCallRenderer
                args={{name: 'Standup notes', type: 'TEXT'}}
                result={{ok: true}}
                toolCallId="call-m"
                toolName="createMemory"
            />
        );

        // memory badge appears in header.
        expect(screen.getByText('memory')).toBeInTheDocument();

        // expand the card to see the name + type body.
        fireEvent.click(screen.getByRole('button', {expanded: false}));

        expect(screen.getByText('Standup notes')).toBeInTheDocument();
        expect(screen.getByText('TEXT')).toBeInTheDocument();
    });

    it('subagent renders a starting placeholder when no progress events have arrived yet', () => {
        aiChatToolCallStore.getState().startToolCall('call-r', 'workflowBuilder', 0);

        render(<AiHubToolCallRenderer toolCallId="call-r" toolName="workflowBuilder" />);

        expect(screen.getByText(/Subagent is starting/i)).toBeInTheDocument();
    });

    it('runChatWorkflow shows a waiting placeholder when no output has arrived', () => {
        aiChatToolCallStore.getState().startToolCall('call-rcw', 'runChatWorkflow', 0);

        render(<AiHubToolCallRenderer toolCallId="call-rcw" toolName="runChatWorkflow" />);

        expect(screen.getByText(/Waiting for workflow output/i)).toBeInTheDocument();
    });
});

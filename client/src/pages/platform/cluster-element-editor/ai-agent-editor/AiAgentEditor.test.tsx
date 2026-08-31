import {TooltipProvider} from '@/components/ui/tooltip';
import {useAiAgentEvalsStore} from '@/pages/platform/cluster-element-editor/ai-agent-evals/stores/useAiAgentEvalsStore';
import {render, screen} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import AiAgentEditor from './AiAgentEditor';

// Closes the gap two rounds of cluster-box-header fixes left open: a comment in
// ClusterElementsCanvasDialogEvals.test.tsx claimed "whether AiAgentEditor renders evals correctly is
// AiAgentEditor's own contract, covered by AiAgentEditor.test.tsx" -- but that file did not exist, so
// the actual behaviour (clicking Evals shows AiAgentHeader chrome + AiAgentEvals, not the configuration
// panel) was verified by nothing in the repository. That untested branch is exactly what a prior
// review round's misdiagnosis ("Evals never renders") hinged on: it read the dialog's own standalone
// evals overlay as the only path to Evals, missed this early return at AiAgentEditor.tsx:49-64, and
// prescribed a hoist that broke dialog mode three ways before being reverted. A test here makes that
// claim falsifiable instead of surviving on a comment's word.
//
// useAiAgentEditor is mocked rather than left real: it pulls useWorkflowEditor() (a React context) and
// a live react-query component-definition fetch, neither of which this test needs -- the evals branch
// in AiAgentEditor.tsx returns before either of that hook's return values are used in JSX. Real
// AiAgentEditor.tsx calls the hook unconditionally either way (hooks can't be called conditionally), so
// it still has to return SOMETHING even on the evals path. AiAgentConfigurationPanel and
// AiAgentTestingPanel (the non-evals branch's own heavy children) are stubbed to plain markers for the
// same reason -- neither is under test here. AiAgentHeader and AiAgentEvals are the two components this
// file exists to prove are reached, so neither is mocked: AiAgentHeader renders for real (it is a plain
// presentational component with no data dependencies), and AiAgentEvals is asserted present as itself
// so a future accidental substitution would be caught -- its OWN internals (the judges/runs/tests tabs)
// are that component's separate contract, not this file's.

vi.mock('@/pages/platform/cluster-element-editor/ai-agent-editor/hooks/useAiAgentEditor', () => ({
    default: () => ({
        handleNodeDetailsPanelClose: vi.fn(),
        showNodeDetailsPanel: false,
        updateWorkflowMutation: undefined,
    }),
}));

vi.mock(
    '@/pages/platform/cluster-element-editor/ai-agent-editor/components/ai-agent-configuration-panel/AiAgentConfigurationPanel',
    () => ({AiAgentConfigurationPanel: () => <div>ai agent configuration panel</div>})
);

vi.mock(
    '@/pages/platform/cluster-element-editor/ai-agent-editor/components/ai-agent-testing-panel/AiAgentTestingPanel',
    () => ({default: () => null})
);

vi.mock('@/pages/platform/cluster-element-editor/ai-agent-evals/AiAgentEvals', () => ({
    default: () => <div>ai agent evals</div>,
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => () => true,
}));

vi.mock('@/pages/platform/workflow-editor/stores/useDataPillPanelStore', () => ({
    default: (selector: (state: unknown) => unknown) => selector({dataPillPanelOpen: false}),
}));

const renderAiAgentEditor = () =>
    render(
        <TooltipProvider>
            <AiAgentEditor copilotEnabled onCopilotClick={vi.fn()} />
        </TooltipProvider>
    );

describe('AiAgentEditor - evals branch selection', () => {
    beforeEach(() => {
        useAiAgentEvalsStore.setState({
            evalsPanelOpen: false,
            evalsTab: 'tests',
            selectedRunId: null,
            selectedTestId: null,
        });
    });

    it('renders the evals surface (AiAgentHeader chrome + AiAgentEvals), not the configuration panel, when evalsPanelOpen is true', () => {
        useAiAgentEvalsStore.setState({evalsPanelOpen: true});

        const {container} = renderAiAgentEditor();

        expect(screen.getByText('Evals')).toBeInTheDocument();
        expect(screen.getByText('ai agent evals')).toBeInTheDocument();
        // Round 1's hoist silenced exactly this control -- present here proves it survives. The
        // rendered Button carries no accessible name of its own (AiAgentHeader relies on a Radix
        // tooltip, which needs to be open to expose one), so the sparkles icon is the reliable marker.
        expect(container.querySelector('svg.lucide-sparkles')).toBeInTheDocument();
        expect(screen.queryByText('ai agent configuration panel')).not.toBeInTheDocument();
    });

    it('renders the configuration panel, not the evals surface, when evalsPanelOpen is false', () => {
        renderAiAgentEditor();

        expect(screen.getByText('ai agent configuration panel')).toBeInTheDocument();
        expect(screen.queryByText('ai agent evals')).not.toBeInTheDocument();
        expect(screen.queryByText('Evals')).not.toBeInTheDocument();
    });
});

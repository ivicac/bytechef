import {TooltipProvider} from '@/components/ui/tooltip';
import {
    type ReferencedResourceI,
    aiHubComposerStore,
} from '@/pages/automation/ai-hub/composer/stores/useAiHubComposerStore';
import {fireEvent, render, screen} from '@testing-library/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

/**
 * Focused coverage for AiHubChatComposer's presentational layer — the referenced-resource chip row,
 * each chip's remove (X) control, and the empty-state behaviour. The component previously had no test
 * at all: a test-suite revival deleted the old (skipped) AiHubComposer chip/remove/empty-state cases
 * on the basis the behaviour "moved to AiHubChatComposer" — but AiHubChatComposer was never given a
 * test. This file closes that gap.
 *
 * The upload pipeline is NOT exercised here (it is already covered by useAiHubAttachmentUpload.test.ts);
 * the upload hook is mocked to return no uploads so only the chip layer is under test. The assistant-ui
 * runtime primitives, voice input, GraphQL mutations, and sibling composer subtrees are stubbed so the
 * component renders in isolation.
 */

// useAiHubAttachmentUpload owns the upload pipeline (covered by its own test). Stub it so the composer
// renders with no uploads and the chip row reflects only referencedResources.
vi.mock('@/pages/automation/ai-hub/composer/hooks/useAiHubAttachmentUpload', () => ({
    ALLOWED_MIME_TYPES: ['image/png'],
    useAiHubAttachmentUpload: () => ({
        dismiss: vi.fn(),
        retry: vi.fn(),
        upload: vi.fn(),
        uploads: [],
    }),
}));

// AiHubComposerDropZone is a sibling (covered by its own test) — render its children directly so the
// composer tree mounts without the drag-and-drop machinery.
vi.mock('@/pages/automation/ai-hub/composer/AiHubComposerDropZone', () => ({
    default: ({children}: {children: ReactNode}) => <div data-testid="drop-zone">{children}</div>,
}));

// AiHubComposer (the ResourcePickerMenu trigger) and TaskToolChips are sibling subtrees, not under
// test here — stub them to keep the render lightweight.
vi.mock('@/pages/automation/ai-hub/composer/AiHubComposer', () => ({
    default: () => <div data-testid="ai-hub-composer" />,
}));

vi.mock('@/pages/automation/ai-hub/tools/TaskToolChips', () => ({
    default: () => <div data-testid="task-tool-chips" />,
}));

// MicButton pulls in browser media APIs — stub it to a plain button.
vi.mock('@/shared/lib/voice/MicButton', () => ({
    MicButton: () => <button aria-label="Mic" type="button" />,
}));

vi.mock('@/shared/lib/voice/usePushToTalk', () => ({
    usePushToTalk: () => ({error: null, start: vi.fn(), status: 'idle', stop: vi.fn()}),
}));

// The assistant-ui runtime primitives require an AssistantRuntimeProvider context. Stub the pieces the
// composer uses so it mounts standalone: Root/Input render plain elements, Send/If render their
// children, and the runtime hooks return inert objects.
vi.mock('@assistant-ui/react', () => ({
    ComposerPrimitive: {
        Input: (props: Record<string, unknown>) => <textarea {...props} />,
        Root: ({children}: {children: ReactNode}) => <div data-testid="composer-root">{children}</div>,
        Send: ({children}: {children: ReactNode}) => <>{children}</>,
    },
    ThreadPrimitive: {
        If: ({children, running}: {children: ReactNode; running: boolean}) => (running ? null : <>{children}</>),
    },
    useComposerRuntime: () => ({send: vi.fn(), setText: vi.fn()}),
    useThreadRuntime: () => ({cancelRun: vi.fn()}),
}));

// GraphQL cancel mutations are only invoked on a Stop click — stub them so the component mounts.
vi.mock('@/shared/middleware/graphql', () => ({
    useCancelAiHubRunMutation: () => ({mutate: vi.fn()}),
    useCancelWorkflowChatTurnMutation: () => ({mutate: vi.fn()}),
}));

// The active-task lookup query is not under test — return no tasks.
vi.mock('@/pages/automation/ai-hub/tasks/hooks/useTasks', () => ({
    useAiHubTasksQuery: () => ({data: undefined}),
}));

// Workspace / environment stores are read via selectors; constant returns are enough here.
vi.mock('@/pages/automation/stores/useWorkspaceStore', () => ({
    useWorkspaceStore: vi.fn(() => 1),
}));

vi.mock('@/shared/stores/useEnvironmentStore', () => ({
    useEnvironmentStore: vi.fn(() => 0),
}));

const dataTableResource: ReferencedResourceI = {id: 'dt-1', kind: 'dataTable', name: 'Customers'};
const knowledgeBaseResource: ReferencedResourceI = {id: 'kb-2', kind: 'knowledgeBase', name: 'Product Docs'};

const renderComposer = async () => {
    const {default: AiHubChatComposer} = await import('../AiHubChatComposer');

    return render(
        <TooltipProvider>
            <AiHubChatComposer />
        </TooltipProvider>
    );
};

beforeEach(() => {
    aiHubComposerStore.setState({referencedResources: []});
});

describe('AiHubChatComposer referenced-resource chips', () => {
    it('renders a chip for each referenced resource in the composer store', async () => {
        aiHubComposerStore.setState({referencedResources: [dataTableResource, knowledgeBaseResource]});

        await renderComposer();

        expect(screen.getByTestId('reference-chips')).toBeInTheDocument();
        expect(screen.getByText('Customers')).toBeInTheDocument();
        expect(screen.getByText('Product Docs')).toBeInTheDocument();
    });

    it('removes a referenced resource from the store when its X control is clicked', async () => {
        aiHubComposerStore.setState({referencedResources: [dataTableResource, knowledgeBaseResource]});

        await renderComposer();

        fireEvent.click(screen.getByLabelText('Remove Customers'));

        const {referencedResources} = aiHubComposerStore.getState();

        expect(referencedResources).toEqual([knowledgeBaseResource]);
        expect(screen.queryByText('Customers')).not.toBeInTheDocument();
        expect(screen.getByText('Product Docs')).toBeInTheDocument();
    });

    it('omits the chip row entirely when there are no referenced resources or uploads', async () => {
        await renderComposer();

        expect(screen.queryByTestId('reference-chips')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', {name: /^Remove /})).not.toBeInTheDocument();
    });
});

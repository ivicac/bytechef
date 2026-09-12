import {KnowledgeBaseScopeType} from '@/shared/components/knowledge-bases/types';
import {render, resetAll, screen, userEvent, windowResizeObserver} from '@/shared/util/test-utils';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import CreateKnowledgeBaseDialog from '../CreateKnowledgeBaseDialog';

const hoisted = vi.hoisted(() => {
    return {
        handleFileChange: vi.fn(),
        handleOpenChange: vi.fn(),
        handleSubmit: vi.fn(),
        mockUseCreateKnowledgeBaseDialog: vi.fn(),
        removeFile: vi.fn(),
        setDescription: vi.fn(),
        setMaxChunkSize: vi.fn(),
        setMinChunkSizeChars: vi.fn(),
        setName: vi.fn(),
        setOpen: vi.fn(),
        setOverlapSize: vi.fn(),
    };
});

vi.mock('../hooks/useCreateKnowledgeBaseDialog', () => ({
    default: hoisted.mockUseCreateKnowledgeBaseDialog,
}));

vi.mock('@/components/Button/Button', () => ({
    default: ({
        children,
        disabled,
        onClick,
    }: {
        children?: React.ReactNode;
        disabled?: boolean;
        onClick?: () => void;
        variant?: string;
    }) => (
        <button data-testid="button" disabled={disabled} onClick={onClick}>
            {children}
        </button>
    ),
}));

vi.mock('@/components/ui/dialog', () => ({
    Dialog: ({children, open}: {children: React.ReactNode; onOpenChange?: (open: boolean) => void; open?: boolean}) =>
        open ? <div data-testid="dialog">{children}</div> : null,
    DialogCloseButton: () => <button data-testid="dialog-close">Close</button>,
    DialogContent: ({children}: {children: React.ReactNode; className?: string}) => (
        <div data-testid="dialog-content">{children}</div>
    ),
    DialogDescription: ({children}: {children: React.ReactNode}) => <p data-testid="dialog-description">{children}</p>,
    DialogFooter: ({children}: {children: React.ReactNode}) => <div data-testid="dialog-footer">{children}</div>,
    DialogHeader: ({children}: {children: React.ReactNode; className?: string}) => (
        <div data-testid="dialog-header">{children}</div>
    ),
    DialogTitle: ({children}: {children: React.ReactNode}) => <h2 data-testid="dialog-title">{children}</h2>,
    DialogTrigger: ({children}: {asChild?: boolean; children: React.ReactNode}) => (
        <div data-testid="dialog-trigger">{children}</div>
    ),
}));

const defaultMockReturn = {
    canSubmit: true,
    description: '',
    formatFileSize: (bytes: number) => `${bytes} bytes`,
    handleFileChange: hoisted.handleFileChange,
    handleOpenChange: hoisted.handleOpenChange,
    handleSubmit: hoisted.handleSubmit,
    isPending: false,
    maxChunkSize: '',
    minChunkSizeChars: '',
    name: '',
    open: true,
    overlapSize: '',
    removeFile: hoisted.removeFile,
    selectedFiles: [] as {file: File; status: 'completed' | 'error' | 'pending' | 'processing' | 'uploading'}[],
    setDescription: hoisted.setDescription,
    setMaxChunkSize: hoisted.setMaxChunkSize,
    setMinChunkSizeChars: hoisted.setMinChunkSizeChars,
    setName: hoisted.setName,
    setOpen: hoisted.setOpen,
    setOverlapSize: hoisted.setOverlapSize,
    uploading: false,
};

const WORKSPACE_SCOPE: KnowledgeBaseScopeType = {type: 'WORKSPACE', workspaceId: 1049};
const EMBEDDED_SCOPE: KnowledgeBaseScopeType = {type: 'EMBEDDED'};

beforeEach(() => {
    windowResizeObserver();
    hoisted.mockUseCreateKnowledgeBaseDialog.mockReturnValue({...defaultMockReturn});
});

afterEach(() => {
    resetAll();
    vi.clearAllMocks();
});

describe('CreateKnowledgeBaseDialog', () => {
    it('renders dialog when open', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByTestId('dialog')).toBeInTheDocument();
    });

    it('renders dialog title', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByTestId('dialog-title')).toHaveTextContent('Create Knowledge Base');
    });

    it('renders name input', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByPlaceholderText('New KB')).toBeInTheDocument();
    });

    // An empty chunking box is not a blank the user has to fill in: it is the dialog declining to name a default the
    // entity already carries, so the placeholder has to say so rather than leave three empty boxes looking broken.
    it('renders the three chunking boxes empty, each offering the platform default', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        const chunkingInputs = screen.getAllByPlaceholderText('Platform default');

        expect(chunkingInputs).toHaveLength(3);

        chunkingInputs.forEach((chunkingInput) => {
            expect(chunkingInput).toHaveValue(null);
        });
    });

    it('renders description textarea', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByPlaceholderText('Describe this knowledge base (optional)')).toBeInTheDocument();
    });

    it('renders min chunk size input', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('Min Chunk Size (characters)')).toBeInTheDocument();
    });

    it('renders max chunk size input', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('Max Chunk Size (tokens)')).toBeInTheDocument();
    });

    it('renders overlap size input', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('Overlap Size (tokens)')).toBeInTheDocument();
    });

    it('renders file upload area', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('Drop files here or click to browse')).toBeInTheDocument();
    });

    it('calls setName when name input changes', async () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        const nameInput = screen.getByPlaceholderText('New KB');
        await userEvent.type(nameInput, 'Test');

        expect(hoisted.setName).toHaveBeenCalled();
    });

    it('calls setDescription when description changes', async () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        const descriptionInput = screen.getByPlaceholderText('Describe this knowledge base (optional)');
        await userEvent.type(descriptionInput, 'Test');

        expect(hoisted.setDescription).toHaveBeenCalled();
    });

    it('disables Create button when canSubmit is false', () => {
        hoisted.mockUseCreateKnowledgeBaseDialog.mockReturnValue({
            ...defaultMockReturn,
            canSubmit: false,
        });

        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        const createButton = screen.getByText('Create').closest('button');

        expect(createButton).toBeDisabled();
    });

    it('shows Creating... when mutation is pending', () => {
        hoisted.mockUseCreateKnowledgeBaseDialog.mockReturnValue({
            ...defaultMockReturn,
            isPending: true,
        });

        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('Creating...')).toBeInTheDocument();
    });

    it('shows upload progress when uploading', () => {
        hoisted.mockUseCreateKnowledgeBaseDialog.mockReturnValue({
            ...defaultMockReturn,
            selectedFiles: [
                {file: new File([''], 'test.pdf'), status: 'completed'},
                {file: new File([''], 'test2.pdf'), status: 'uploading'},
            ],
            uploading: true,
        });

        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('Uploading 1/2...')).toBeInTheDocument();
    });

    it('renders selected files', () => {
        hoisted.mockUseCreateKnowledgeBaseDialog.mockReturnValue({
            ...defaultMockReturn,
            selectedFiles: [{file: new File([''], 'test.pdf'), status: 'pending'}],
        });

        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(screen.getByText('test.pdf')).toBeInTheDocument();
    });

    it('calls handleSubmit when Create is clicked', async () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        const createButton = screen.getByText('Create');
        await userEvent.click(createButton);

        expect(hoisted.handleSubmit).toHaveBeenCalled();
    });

    it('passes the scope to the hook', () => {
        render(<CreateKnowledgeBaseDialog scope={WORKSPACE_SCOPE} />);

        expect(hoisted.mockUseCreateKnowledgeBaseDialog).toHaveBeenCalledWith(WORKSPACE_SCOPE);
    });
});

describe('CreateKnowledgeBaseDialog embedded scope', () => {
    it('offers no owner picker in embedded scope', () => {
        render(<CreateKnowledgeBaseDialog scope={EMBEDDED_SCOPE} />);

        expect(screen.queryByRole('combobox', {name: 'Owner'})).not.toBeInTheDocument();
    });

    // Chunking decides how a document is split before embedding, and the embedded surface has no other place to set
    // it: hidden here, a knowledge base created from the console was stuck at the default chunking for its whole life.
    it('offers the chunking settings in embedded scope', () => {
        render(<CreateKnowledgeBaseDialog scope={EMBEDDED_SCOPE} />);

        expect(screen.getByText('Min Chunk Size (characters)')).toBeInTheDocument();
        expect(screen.getByText('Max Chunk Size (tokens)')).toBeInTheDocument();
        expect(screen.getByText('Overlap Size (tokens)')).toBeInTheDocument();
    });

    // The upload area stays workspace-only, for the reason the chunking controls no longer are: the embedded create
    // mutation returns a boolean, so there is no knowledge base id to POST the documents against.
    it('hides the upload area in embedded scope', () => {
        render(<CreateKnowledgeBaseDialog scope={EMBEDDED_SCOPE} />);

        expect(screen.queryByText('Drop files here or click to browse')).not.toBeInTheDocument();
    });
});

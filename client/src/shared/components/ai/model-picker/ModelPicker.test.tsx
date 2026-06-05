import {render, screen, userEvent, windowResizeObserver} from '@/shared/util/test-utils';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import ModelPicker from './ModelPicker';

const {navigateMock, useAiProviderCatalogQueryMock} = vi.hoisted(() => ({
    navigateMock: vi.fn(),
    useAiProviderCatalogQueryMock: vi.fn(),
}));

vi.mock('react-router-dom', async (importOriginal) => ({
    ...(await importOriginal<typeof import('react-router-dom')>()),
    useNavigate: () => navigateMock,
}));

vi.mock('@/shared/middleware/graphql', () => ({
    useAiProviderCatalogQuery: useAiProviderCatalogQueryMock,
}));

const catalog = [
    {
        enabled: true,
        icon: '<svg>openai</svg>',
        key: 'ai.provider.openAi',
        models: [{label: 'GPT-4o', name: 'gpt-4o'}],
        name: 'Open AI',
        supportsModelById: false,
    },
    {
        enabled: false,
        icon: '<svg>anthropic</svg>',
        key: 'ai.provider.anthropic',
        models: [],
        name: 'Anthropic',
        supportsModelById: true,
    },
];

describe('ModelPicker', () => {
    beforeEach(() => {
        windowResizeObserver();
        navigateMock.mockReset();
        useAiProviderCatalogQueryMock.mockReturnValue({data: {aiProviderCatalog: catalog}});
        localStorage.clear();
    });

    it('lists active and inactive providers', async () => {
        render(
            <ModelPicker
                environment={1}
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={5}
            />
        );

        await userEvent.click(screen.getByLabelText('Select LLM provider and model'));

        expect(screen.getByText('Open AI')).toBeInTheDocument();
        expect(screen.getByText('Anthropic')).toBeInTheDocument();
    });

    it('shows exact selected provider+model in the trigger, not a default label', () => {
        render(
            <ModelPicker
                environment={1}
                onChange={vi.fn()}
                selectedModel="gpt-4o"
                selectedProvider="ai.provider.openAi"
                workspaceId={5}
            />
        );

        expect(screen.getByText('GPT-4o')).toBeInTheDocument();
        expect(screen.queryByText('Workspace default')).not.toBeInTheDocument();
    });

    it('navigates to AI Providers settings for an inactive provider', async () => {
        render(
            <ModelPicker
                environment={1}
                onChange={vi.fn()}
                selectedModel={null}
                selectedProvider={null}
                workspaceId={5}
            />
        );

        await userEvent.click(screen.getByLabelText('Select LLM provider and model'));
        await userEvent.click(screen.getByText('Anthropic'));
        await userEvent.click(screen.getByText('Configure credentials'));

        expect(navigateMock).toHaveBeenCalledWith('/automation/settings/ai-providers');
    });
});

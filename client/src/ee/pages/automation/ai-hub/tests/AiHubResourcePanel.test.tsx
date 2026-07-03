import {TooltipProvider} from '@/components/ui/tooltip';
import AiHubResourcePanel from '@/ee/pages/automation/ai-hub/AiHubResourcePanel';
import {aiHubTabsStore} from '@/ee/pages/automation/ai-hub/stores/useAiHubTabsStore';
import {render} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {beforeEach, describe, expect, it, vi} from 'vitest';

/**
 * Coverage for the tab strip's scroll behaviour. The strip is `overflow-x-auto`, so once the tabs outgrow
 * the panel width the active one can sit outside the visible range — clipped mid-label with its close
 * button cut off. Nothing used to scroll it back, which read as a mysteriously truncated tab.
 *
 * Only the strip is under test; the tab bodies are heavy viewer subtrees and are stubbed out.
 */
vi.mock('@/ee/pages/automation/ai-hub/AiHubFileViewer', () => ({default: () => <div data-testid="file-viewer" />}));

vi.mock('@/ee/pages/automation/ai-hub/AiHubFilePicker', () => ({default: () => <div data-testid="file-picker" />}));

const fileTab = (id: string, name: string) => ({
    fileId: id,
    id: `tab-${id}`,
    kind: 'file' as const,
    name,
    viewMode: 'editor' as const,
});

const renderPanel = () =>
    render(
        <MemoryRouter>
            <TooltipProvider>
                <AiHubResourcePanel />
            </TooltipProvider>
        </MemoryRouter>
    );

describe('AiHubResourcePanel tab strip', () => {
    beforeEach(() => {
        aiHubTabsStore.setState({
            activeChatId: undefined,
            activeTabId: undefined,
            attachedTabIds: [],
            openTabs: [],
            rightPanelOpen: true,
            snapshotsByChatId: {},
        });
    });

    it('scrolls the active tab into view so it is never left clipped', () => {
        // Capture the element the call landed on: asserting the call happened is not enough, since
        // scrolling the WRONG tab into view would leave the active one just as clipped.
        const scrolledElements: Element[] = [];

        const scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(function (
            this: Element
        ) {
            scrolledElements.push(this);
        });

        aiHubTabsStore.setState({
            activeTabId: 'tab-2',
            openTabs: [fileTab('1', 'invoices.md'), fileTab('2', 'knowledgebase1.md')],
        });

        renderPanel();

        expect(scrollIntoViewSpy).toHaveBeenCalledWith({block: 'nearest', inline: 'nearest'});
        expect(scrolledElements).toHaveLength(1);
        expect(scrolledElements[0]!.textContent).toContain('knowledgebase1.md');

        scrollIntoViewSpy.mockRestore();
    });

    /*
     * `block: 'nearest'` rather than the default 'start': the default scrolls every scrollable ancestor,
     * which would yank the whole page vertically just to reveal a tab.
     */
    it('asks for the minimum movement on both axes', () => {
        const scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {});

        aiHubTabsStore.setState({activeTabId: 'tab-1', openTabs: [fileTab('1', 'invoices.md')]});

        renderPanel();

        expect(scrollIntoViewSpy).toHaveBeenCalledWith({block: 'nearest', inline: 'nearest'});
        expect(scrollIntoViewSpy).not.toHaveBeenCalledWith(expect.objectContaining({block: 'start'}));

        scrollIntoViewSpy.mockRestore();
    });

    it('does not scroll when there is no active tab', () => {
        const scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {});

        renderPanel();

        expect(scrollIntoViewSpy).not.toHaveBeenCalled();

        scrollIntoViewSpy.mockRestore();
    });
});

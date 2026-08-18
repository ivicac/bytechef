import Button from '@/components/Button/Button';
import {Input} from '@/components/Input/Input';
import {ToggleGroup, ToggleGroupItem} from '@/components/ui/toggle-group';
import {
    CatalogFilterType,
    CatalogLayoutType,
} from '@/ee/pages/embedded/automation-hub/views/components/TemplateGridSection';
import {LayoutGridIcon, ListIcon, PlusIcon, SearchIcon} from 'lucide-react';

// Both segmented controls run borderless: on the hub's grey ground a white group is already its own
// edge, and shadcn's outline variant would draw a second one around it. That leaves the selected
// segment to carry the whole state — `data-[state=on]:bg-accent`, the shadcn default, is all but
// invisible against the group's white surface, so it is replaced here. Shared between the two so
// the filter and the layout switcher stay indistinguishable in style.
const SELECTED_SEGMENT_CLASS = 'bg-(--hub-card) data-[state=on]:bg-(--hub-segment-selected)';

export const CATALOG_LAYOUT_STORAGE_KEY = 'automationHub.catalogLayout';

interface CatalogToolbarProps {
    filter: CatalogFilterType;
    layout: CatalogLayoutType;
    layoutSwitcherAllowed: boolean;
    newWorkflowEnabled: boolean;
    onCreateBlankAutomation: () => void;
    onFilterChange: (filter: CatalogFilterType) => void;
    onLayoutChange: (layout: CatalogLayoutType) => void;
    onSearchChange: (search: string) => void;
    search: string;
}

/**
 * The catalog's controls, all on one row: the All/Active/Enabled filter on the left, then the search box,
 * the grid/list toggle and "New Automation" together on the right. The view owns every piece of
 * state, so this stays presentational.
 *
 * Both the filter and the layout switcher are single-select `ToggleGroup`s, whose items are radios:
 * a viewer can never end up with neither option selected the way a pair of independent toggle
 * buttons would allow. They also read as one segmented control each, which is what keeps the two
 * ends of the row speaking the same visual language.
 */
const CatalogToolbar = ({
    filter,
    layout,
    layoutSwitcherAllowed,
    newWorkflowEnabled,
    onCreateBlankAutomation,
    onFilterChange,
    onLayoutChange,
    onSearchChange,
    search,
}: CatalogToolbarProps) => (
    <div className="flex items-center gap-4" data-testid="automations-toolbar">
        <ToggleGroup
            className="shrink-0"
            onValueChange={(value) => value && onFilterChange(value as CatalogFilterType)}
            type="single"
            value={filter}
        >
            <ToggleGroupItem className={SELECTED_SEGMENT_CLASS} value="all">
                All
            </ToggleGroupItem>

            <ToggleGroupItem className={SELECTED_SEGMENT_CLASS} value="active">
                Active
            </ToggleGroupItem>

            <ToggleGroupItem className={SELECTED_SEGMENT_CLASS} value="enabled">
                Enabled
            </ToggleGroupItem>
        </ToggleGroup>

        <div className="relative ml-auto w-full max-w-sm">
            <SearchIcon className="absolute top-2.5 left-3 size-4 text-muted-foreground" />

            <Input
                className="pl-8"
                onChange={(event) => onSearchChange(event.target.value)}
                placeholder="Search templates"
                value={search}
            />
        </div>

        {layoutSwitcherAllowed && (
            <ToggleGroup
                onValueChange={(value) => value && onLayoutChange(value as CatalogLayoutType)}
                type="single"
                value={layout}
            >
                <ToggleGroupItem aria-label="Grid view" className={SELECTED_SEGMENT_CLASS} value="grid">
                    <LayoutGridIcon />
                </ToggleGroupItem>

                <ToggleGroupItem aria-label="List view" className={SELECTED_SEGMENT_CLASS} value="list">
                    <ListIcon />
                </ToggleGroupItem>
            </ToggleGroup>
        )}

        {newWorkflowEnabled && (
            <Button icon={<PlusIcon className="size-4" />} label="New Automation" onClick={onCreateBlankAutomation} />
        )}
    </div>
);

export default CatalogToolbar;

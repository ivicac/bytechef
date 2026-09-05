import Badge from '@/components/Badge/Badge';

interface DataSyncsFilterTitleProps {
    dataSyncName?: string;
    tagName?: string;
}

/**
 * "FILTER BY DATA SYNC: <name>" above the data syncs list, matching AgentsFilterTitle and the other list
 * pages.
 *
 * Data syncs filter by tag OR by a single sync, never both at once — picking one clears the other (see
 * DataSyncs.tsx), so the label names whichever is active rather than combining them. It says "tag" only
 * when a tag is actually selected: the unfiltered state reads "All Data Syncs", so calling that a tag
 * filter describes a filter that is not applied.
 */
const DataSyncsFilterTitle = ({dataSyncName, tagName}: DataSyncsFilterTitleProps) => (
    <div className="space-x-1">
        <span className="text-sm text-muted-foreground uppercase">{`Filter by ${tagName ? 'tag' : 'data sync'}:`}</span>

        <Badge label={dataSyncName ?? tagName ?? 'All Data Syncs'} styleType="secondary-filled" weight="semibold" />
    </div>
);

export default DataSyncsFilterTitle;

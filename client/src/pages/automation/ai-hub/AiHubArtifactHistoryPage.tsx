/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import {Label} from '@/components/ui/label';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select';
import {
    AiHubArtifactKindType,
    AiHubArtifactStatusType,
    AiHubTaskArtifactI,
} from '@/pages/automation/ai-hub/tasks/api/tasks.api';
import {useArtifactAuditQuery} from '@/pages/automation/ai-hub/tasks/hooks/useTasks';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {formatDistanceToNow} from 'date-fns';
import {DatabaseIcon, FileTextIcon, ImageIcon, PlayIcon, WrenchIcon} from 'lucide-react';
import {useCallback, useMemo, useState} from 'react';

const PAGE_SIZE = 50;

const KIND_LABELS: Record<AiHubArtifactKindType, string> = {
    API_COLLECTION_REFERENCED: 'API collection referenced',
    BINARY_FILE_CREATED: 'Binary file created',
    DATA_TABLE_COLUMN_ADDED: 'Data table column added',
    DATA_TABLE_REFERENCED: 'Data table referenced',
    DATA_TABLE_ROW_ADDED: 'Data table row added',
    DATA_TABLE_ROW_DELETED: 'Data table row deleted',
    DATA_TABLE_ROW_UPDATED: 'Data table row updated',
    FILE_CREATED: 'File created',
    FILE_REFERENCED: 'File referenced',
    KB_DOCUMENT_ADDED: 'KB document added',
    KB_DOCUMENT_DELETED: 'KB document deleted',
    KB_REFERENCED: 'KB referenced',
    MCP_SERVER_REFERENCED: 'MCP server referenced',
    MEMORY_CREATED: 'Memory created',
    MEMORY_DELETED: 'Memory deleted',
    MEMORY_RENAMED: 'Memory renamed',
    MEMORY_UPDATED: 'Memory updated',
    TASK_REFERENCED: 'Task referenced',
    WORKFLOW_CREATED: 'Workflow created',
    WORKFLOW_EXECUTION_REFERENCED: 'Workflow execution referenced',
    WORKFLOW_EXECUTION_STARTED: 'Workflow execution started',
    WORKFLOW_REFERENCED: 'Workflow referenced',
    WORKFLOW_UPDATED: 'Workflow updated',
};

const STATUS_LABELS: Record<AiHubArtifactStatusType, string> = {
    APPLIED: 'Applied',
    EXPIRED: 'Expired',
    IRREVERSIBLE: 'Irreversible',
};

function getArtifactIcon(kind: AiHubArtifactKindType) {
    if (kind === 'FILE_CREATED') {
        return <FileTextIcon className="size-4 shrink-0 text-muted-foreground" />;
    }

    if (kind === 'BINARY_FILE_CREATED') {
        return <ImageIcon className="size-4 shrink-0 text-muted-foreground" />;
    }

    if (kind === 'WORKFLOW_EXECUTION_STARTED') {
        return <PlayIcon className="size-4 shrink-0 text-muted-foreground" />;
    }

    if (
        kind === 'DATA_TABLE_ROW_ADDED' ||
        kind === 'DATA_TABLE_ROW_UPDATED' ||
        kind === 'DATA_TABLE_ROW_DELETED' ||
        kind === 'DATA_TABLE_COLUMN_ADDED'
    ) {
        return <DatabaseIcon className="size-4 shrink-0 text-muted-foreground" />;
    }

    return <WrenchIcon className="size-4 shrink-0 text-muted-foreground" />;
}

interface FiltersI {
    from?: string;
    kind?: AiHubArtifactKindType;
    to?: string;
}

interface ArtifactRowPropsI {
    artifact: AiHubTaskArtifactI;
}

const ArtifactRow = ({artifact}: ArtifactRowPropsI) => {
    const relativeTime = useMemo(() => {
        try {
            return formatDistanceToNow(new Date(artifact.createdAt), {addSuffix: true});
        } catch (formatError) {
            console.warn('AiHubArtifactHistoryPage: failed to format relative time', {
                artifactId: artifact.id,
                createdAt: artifact.createdAt,
                message: formatError instanceof Error ? formatError.message : String(formatError),
            });

            return '';
        }
    }, [artifact.createdAt, artifact.id]);

    return (
        <tr className="border-b last:border-0 hover:bg-muted/40">
            <td className="px-4 py-2">
                <div className="flex items-center gap-2">
                    {getArtifactIcon(artifact.kind)}

                    <span className="text-sm">{artifact.artifactName}</span>
                </div>
            </td>

            <td className="px-4 py-2 text-sm text-muted-foreground">{KIND_LABELS[artifact.kind]}</td>

            <td className="px-4 py-2">
                <span
                    className={
                        artifact.status === 'APPLIED'
                            ? 'rounded bg-green-100 px-1.5 py-0.5 text-xs font-medium text-green-800 dark:bg-green-900/30 dark:text-green-400'
                            : 'rounded bg-muted px-1.5 py-0.5 text-xs font-medium text-muted-foreground'
                    }
                >
                    {STATUS_LABELS[artifact.status]}
                </span>
            </td>

            <td className="px-4 py-2 text-sm text-muted-foreground">{relativeTime}</td>
        </tr>
    );
};

const AiHubArtifactHistoryPage = () => {
    const [filters, setFilters] = useState<FiltersI>({});
    const [page, setPage] = useState(0);

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const {data, isLoading} = useArtifactAuditQuery(currentWorkspaceId, {
        from: filters.from ? `${filters.from}T00:00:00` : undefined,
        kind: filters.kind,
        page,
        size: PAGE_SIZE,
        to: filters.to ? `${filters.to}T23:59:59` : undefined,
    });

    const handleFilterChange = useCallback((next: FiltersI) => {
        setFilters(next);
        setPage(0);
    }, []);

    const items = data?.items ?? [];
    const totalCount = data?.totalCount ?? 0;
    const hasMore = data?.hasMore ?? false;
    const totalPages = Math.ceil(totalCount / PAGE_SIZE);

    const filterSidebar = (
        <div className="flex flex-col gap-4 p-4">
            <fieldset className="border-0">
                <Label className="mb-1.5 block text-xs font-medium text-muted-foreground">Kind</Label>

                <Select
                    onValueChange={(value) =>
                        handleFilterChange({
                            ...filters,
                            kind: value === 'ALL' ? undefined : (value as AiHubArtifactKindType),
                        })
                    }
                    value={filters.kind || 'ALL'}
                >
                    <SelectTrigger className="w-full">
                        <SelectValue placeholder="All kinds" />
                    </SelectTrigger>

                    <SelectContent>
                        <SelectItem value="ALL">All kinds</SelectItem>

                        {(Object.keys(KIND_LABELS) as AiHubArtifactKindType[]).map((kind) => (
                            <SelectItem key={kind} value={kind}>
                                {KIND_LABELS[kind]}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            </fieldset>

            <fieldset className="border-0">
                <Label className="mb-1.5 block text-xs font-medium text-muted-foreground">From date</Label>

                <input
                    className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm"
                    onChange={(event) => handleFilterChange({...filters, from: event.target.value || undefined})}
                    type="date"
                    value={filters.from || ''}
                />
            </fieldset>

            <fieldset className="border-0">
                <Label className="mb-1.5 block text-xs font-medium text-muted-foreground">To date</Label>

                <input
                    className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm"
                    onChange={(event) => handleFilterChange({...filters, to: event.target.value || undefined})}
                    type="date"
                    value={filters.to || ''}
                />
            </fieldset>

            <Button
                label="Clear filters"
                onClick={() => {
                    setFilters({});
                    setPage(0);
                }}
                size="sm"
                variant="ghost"
            />
        </div>
    );

    // Filter-context title for the main header — mirrors ProjectsFilterTitle's "Filter by category: <name>" shape
    // so the page's chrome is consistent with the rest of the automation surface (Projects, Templates, etc.).
    // Shows the active kind filter when set; falls back to "All kinds" so the row never collapses to empty space.
    // Total count rides alongside as a secondary badge so the user can still see the result-set size at a glance —
    // moving the count out of the page title (where it lived as "Artifact History (27)") doesn't lose that signal.
    const filterTitle = (
        <div className="space-x-1">
            <span className="text-sm text-muted-foreground uppercase">Filter by kind:</span>

            <Badge
                label={filters.kind ? KIND_LABELS[filters.kind] : 'All kinds'}
                styleType="secondary-filled"
                weight="semibold"
            />

            {totalCount > 0 && (
                <Badge
                    label={`${totalCount} ${totalCount === 1 ? 'artifact' : 'artifacts'}`}
                    styleType="secondary-outline"
                />
            )}
        </div>
    );

    return (
        <LayoutContainer
            header={<Header centerTitle position="main" title={filterTitle} />}
            leftSidebarBody={filterSidebar}
            leftSidebarHeader={<Header position="sidebar" title="Artifact History" />}
            leftSidebarWidth="64"
        >
            <div className="flex w-full flex-1 flex-col gap-4 p-6">
                {isLoading ? (
                    <p className="p-8 text-center text-sm text-muted-foreground">Loading...</p>
                ) : items.length === 0 ? (
                    <p className="p-8 text-center text-sm text-muted-foreground">No artifacts found.</p>
                ) : (
                    <div className="overflow-x-auto rounded-md border">
                        <table className="w-full">
                            <thead>
                                <tr className="border-b bg-muted/50 text-left">
                                    <th className="px-4 py-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                                        Artifact
                                    </th>

                                    <th className="px-4 py-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                                        Kind
                                    </th>

                                    <th className="px-4 py-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                                        Status
                                    </th>

                                    <th className="px-4 py-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                                        Created
                                    </th>
                                </tr>
                            </thead>

                            <tbody>
                                {items.map((artifact) => (
                                    <ArtifactRow artifact={artifact} key={artifact.id} />
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}

                {totalPages > 1 && (
                    <div className="flex items-center justify-end gap-2">
                        <Button
                            disabled={page === 0}
                            label="Previous"
                            onClick={() => setPage((current) => current - 1)}
                            variant="outline"
                        />

                        <span className="text-sm text-muted-foreground">
                            Page {page + 1} of {totalPages}
                        </span>

                        <Button
                            disabled={!hasMore}
                            label="Next"
                            onClick={() => setPage((current) => current + 1)}
                            variant="outline"
                        />
                    </div>
                )}
            </div>
        </LayoutContainer>
    );
};

export default AiHubArtifactHistoryPage;

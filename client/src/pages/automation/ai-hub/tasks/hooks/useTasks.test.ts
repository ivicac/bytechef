import {toast} from 'sonner';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {type AiHubTaskArtifactI} from '../api/tasks.api';
import {AiHubTasksKeys, collapseNetZeroDataTableRowArtifacts, reportMutationError} from './useTasks';

vi.mock('sonner', () => ({
    toast: {
        error: vi.fn(),
    },
}));

describe('useTasks: query keys', () => {
    it('produces stable, namespaced keys per (workspace, environment, status) triple', () => {
        // Pin the cache-key shape so a downstream rename in AiHubTasksKeys (e.g. dropping the
        // workspaceId or environment from `list`) does not silently drop the cache scoping and cause cross-workspace
        // or cross-environment bleed. The environment ordinal (DEVELOPMENT=0, STAGING=1, PRODUCTION=2) sits between
        // workspaceId and status so prefix invalidations against [...all, 'list', workspaceId] still catch every
        // env+status combination.
        expect(AiHubTasksKeys.list(1, 0, 'ACTIVE')).toEqual(['aiHubTasks', 'list', 1, 0, 'ACTIVE']);

        expect(AiHubTasksKeys.list(2, 2, 'ARCHIVED')).toEqual(['aiHubTasks', 'list', 2, 2, 'ARCHIVED']);
    });

    it('messages, artifacts, and audit keys all start with the shared root', () => {
        expect(AiHubTasksKeys.messages(7, 1)[0]).toBe('aiHubTasks');
        expect(AiHubTasksKeys.artifacts(7, 1)[0]).toBe('aiHubTasks');
        expect(AiHubTasksKeys.artifactAudit(1, {kind: 'FILE_CREATED'})[0]).toBe('aiHubTasks');
    });

    it('artifactAudit embeds the filter object so different filters get distinct cache entries', () => {
        const a = AiHubTasksKeys.artifactAudit(1, {kind: 'FILE_CREATED'});
        const b = AiHubTasksKeys.artifactAudit(1, {kind: 'WORKFLOW_CREATED'});

        expect(a).not.toEqual(b);
    });
});

describe('useTasks: reportMutationError', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('toasts the error message verbatim when present', () => {
        reportMutationError('Delete task', new Error('Forbidden'));

        expect(toast.error).toHaveBeenCalledWith('Forbidden');
    });

    it('falls back to "${action} failed" when the error has no message', () => {
        reportMutationError('Delete task', new Error(''));

        expect(toast.error).toHaveBeenCalledWith('Delete task failed');
    });
});

describe('useTasks: collapseNetZeroDataTableRowArtifacts', () => {
    let nextArtifactId = 1;

    function rowArtifact(
        kind: AiHubTaskArtifactI['kind'],
        rowId: string,
        dataTableId: string | null
    ): AiHubTaskArtifactI {
        return {
            artifactId: rowId,
            artifactName: `row ${rowId}`,
            createdAt: '2026-07-16T00:00:00Z',
            id: nextArtifactId++,
            kind,
            metadataJson: dataTableId === null ? null : JSON.stringify({dataTableId}),
            status: 'APPLIED',
            taskId: 7,
        } as AiHubTaskArtifactI;
    }

    it('hides an added row that the same task later deleted, including its updates', () => {
        // The seed-row pattern: the agent adds a sample row to establish a table schema, then deletes it.
        // The pair nets to nothing, so neither entry (nor an update in between) belongs in the sidebar.
        const artifacts = [
            rowArtifact('DATA_TABLE_ROW_DELETED', 'row-1', 'table-9'),
            rowArtifact('DATA_TABLE_ROW_UPDATED', 'row-1', 'table-9'),
            rowArtifact('DATA_TABLE_ROW_ADDED', 'row-1', 'table-9'),
            rowArtifact('DATA_TABLE_ROW_ADDED', 'row-2', 'table-9'),
        ];

        const visible = collapseNetZeroDataTableRowArtifacts(artifacts);

        expect(visible.map((artifact) => artifact.artifactId)).toEqual(['row-2']);
    });

    it('keeps a delete of a row the task did not add', () => {
        // Deleting pre-existing data is exactly what the artifact trail must surface — only pairs collapse.
        const artifacts = [rowArtifact('DATA_TABLE_ROW_DELETED', 'row-1', 'table-9')];

        expect(collapseNetZeroDataTableRowArtifacts(artifacts)).toEqual(artifacts);
    });

    it('does not pair rows with the same row id across different tables', () => {
        const artifacts = [
            rowArtifact('DATA_TABLE_ROW_DELETED', 'row-1', 'table-other'),
            rowArtifact('DATA_TABLE_ROW_ADDED', 'row-1', 'table-9'),
        ];

        expect(collapseNetZeroDataTableRowArtifacts(artifacts)).toEqual(artifacts);
    });

    it('leaves non-row artifacts untouched', () => {
        const tableReference = {
            artifactId: 'table-9',
            artifactName: 'invoices',
            createdAt: '2026-07-16T00:00:00Z',
            id: nextArtifactId++,
            kind: 'DATA_TABLE_REFERENCED',
            metadataJson: null,
            status: 'APPLIED',
            taskId: 7,
        } as AiHubTaskArtifactI;

        const artifacts = [
            tableReference,
            rowArtifact('DATA_TABLE_ROW_DELETED', 'row-1', 'table-9'),
            rowArtifact('DATA_TABLE_ROW_ADDED', 'row-1', 'table-9'),
        ];

        expect(collapseNetZeroDataTableRowArtifacts(artifacts)).toEqual([tableReference]);
    });
});

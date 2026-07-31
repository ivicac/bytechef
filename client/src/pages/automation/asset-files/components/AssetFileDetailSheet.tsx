import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import {Sheet, SheetCloseButton, SheetContent, SheetTitle} from '@/components/ui/sheet';
import {useAssetFilesStore} from '@/pages/automation/asset-files/stores/useAssetFilesStore';
import MonacoEditorLoader from '@/shared/components/MonacoEditorLoader';
import {
    useGetAssetFileQuery,
    useGetAssetFileTextContentQuery,
    useGetAssetFileVersionsQuery,
    useRestoreAssetFileVersionMutation,
    useUpdateAssetFileTextContentMutation,
} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useQueryClient} from '@tanstack/react-query';
import {DownloadIcon, FileTextIcon, HistoryIcon, MaximizeIcon, SaveIcon} from 'lucide-react';
import {VisuallyHidden} from 'radix-ui';
import {Suspense, lazy, useEffect, useMemo, useRef, useState} from 'react';
import Markdown from 'react-markdown';
import {toast} from 'sonner';

/**
 * Resolves the {@code environmentId} ordinal stored on an {@code AssetFile} to a short label suitable for the badge in
 * the detail-sheet header. Mirrors the {@code Environment} enum's ordinal pinning on the server (DEVELOPMENT=0,
 * STAGING=1, PRODUCTION=2). The fallback "ENV {n}" guards against a future server-side append the client hasn't been
 * rebuilt against, so a brand-new ordinal renders as e.g. {@code "ENV 3"} instead of crashing the sheet.
 */
const environmentLabel = (environmentId: number): string => {
    switch (environmentId) {
        case 0:
            return 'DEV';
        case 1:
            return 'STAGING';
        case 2:
            return 'PROD';
        default:
            return `ENV ${environmentId}`;
    }
};

const MonacoEditor = lazy(() => import('@/shared/components/MonacoEditorWrapper'));

const isTextMime = (mimeType: string): boolean => mimeType.startsWith('text/') || mimeType === 'application/json';

const isImageMime = (mimeType: string): boolean => mimeType.startsWith('image/');

const isPdfMime = (mimeType: string): boolean => mimeType === 'application/pdf';

const isMarkdownFile = (fileName: string, mimeType: string): boolean =>
    mimeType === 'text/markdown' || fileName.endsWith('.md') || fileName.endsWith('.markdown');

const isCsvFile = (fileName: string, mimeType: string): boolean => mimeType === 'text/csv' || fileName.endsWith('.csv');

const isHtmlFile = (fileName: string, mimeType: string): boolean =>
    mimeType === 'text/html' || fileName.endsWith('.html') || fileName.endsWith('.htm');

const CSV_PREVIEW_MAX_ROWS = 500;

/**
 * Minimal RFC-4180-ish CSV parser for the preview table: handles quoted fields (including escaped quotes and embedded
 * commas/newlines) and caps the row count so a huge file cannot lock up the sheet. Preview-only — the editor tab
 * always shows the raw text.
 */
const parseCsvPreview = (content: string): string[][] => {
    const rows: string[][] = [];

    let currentField = '';
    let currentRow: string[] = [];
    let insideQuotes = false;

    for (let index = 0; index < content.length; index++) {
        const character = content[index];

        if (insideQuotes) {
            if (character === '"') {
                if (content[index + 1] === '"') {
                    currentField += '"';
                    index++;
                } else {
                    insideQuotes = false;
                }
            } else {
                currentField += character;
            }
        } else if (character === '"') {
            insideQuotes = true;
        } else if (character === ',') {
            currentRow.push(currentField);
            currentField = '';
        } else if (character === '\n' || character === '\r') {
            if (character === '\r' && content[index + 1] === '\n') {
                index++;
            }

            currentRow.push(currentField);
            currentField = '';
            rows.push(currentRow);
            currentRow = [];

            if (rows.length >= CSV_PREVIEW_MAX_ROWS) {
                return rows;
            }
        } else {
            currentField += character;
        }
    }

    if (currentField !== '' || currentRow.length > 0) {
        currentRow.push(currentField);
        rows.push(currentRow);
    }

    return rows;
};

const formatBytes = (bytes: number): string => {
    if (!bytes) {
        return '0 B';
    }

    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    const value = bytes / Math.pow(1024, exponent);

    return `${value.toFixed(value >= 10 || exponent === 0 ? 0 : 1)} ${units[exponent]}`;
};

const formatDate = (value: number | string | null | undefined): string => {
    if (value == null) {
        return '';
    }

    const millis = typeof value === 'number' ? value : Number(value);

    if (Number.isNaN(millis)) {
        return '';
    }

    return new Date(millis).toLocaleString();
};

const inferLanguage = (fileName: string, mimeType: string): string => {
    const extension = fileName.includes('.') ? fileName.split('.').pop()?.toLowerCase() : undefined;

    switch (extension) {
        case 'js':
        case 'mjs':
        case 'cjs':
            return 'javascript';
        case 'ts':
        case 'tsx':
            return 'typescript';
        case 'json':
            return 'json';
        case 'md':
        case 'markdown':
            return 'markdown';
        case 'py':
            return 'python';
        case 'rb':
            return 'ruby';
        case 'yml':
        case 'yaml':
            return 'yaml';
        case 'html':
        case 'htm':
            return 'html';
        case 'css':
            return 'css';
        case 'sql':
            return 'sql';
        case 'xml':
            return 'xml';
        case 'sh':
        case 'bash':
            return 'shell';
        default:
            if (mimeType === 'application/json') {
                return 'json';
            }

            if (mimeType === 'text/markdown') {
                return 'markdown';
            }

            return 'plaintext';
    }
};

const AssetFileDetailSheet = () => {
    const [editorValue, setEditorValue] = useState<string>('');
    const [showVersions, setShowVersions] = useState(false);
    const [viewMode, setViewMode] = useState<'edit' | 'preview'>('preview');

    const htmlPreviewContainerRef = useRef<HTMLDivElement>(null);

    const selectedFileId = useAssetFilesStore((state) => state.selectedFileId);
    const setSelectedFileId = useAssetFilesStore((state) => state.setSelectedFileId);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const queryClient = useQueryClient();

    const fileIdAsString = selectedFileId != null ? String(selectedFileId) : '';
    const enabled = selectedFileId != null;

    const {data: fileData} = useGetAssetFileQuery({id: fileIdAsString}, {enabled});

    const file = fileData?.assetFile ?? null;

    const isText = file ? isTextMime(file.mimeType) : false;
    const isImage = file ? isImageMime(file.mimeType) : false;
    const isPdf = file ? isPdfMime(file.mimeType) : false;

    const isMarkdown = file ? isMarkdownFile(file.name, file.mimeType) : false;
    const isCsv = file ? isCsvFile(file.name, file.mimeType) : false;
    const isHtml = file ? isHtmlFile(file.name, file.mimeType) : false;
    const isPreviewableText = isText && (isMarkdown || isCsv || isHtml);

    const {data: textContentData} = useGetAssetFileTextContentQuery({id: fileIdAsString}, {enabled: enabled && isText});

    const {data: versionsData} = useGetAssetFileVersionsQuery({id: fileIdAsString}, {enabled: enabled && showVersions});

    const invalidateFileQueries = () => {
        void queryClient.invalidateQueries({queryKey: ['GetAssetFiles']});
        void queryClient.invalidateQueries({queryKey: ['GetAssetFile', {id: fileIdAsString}]});
        void queryClient.invalidateQueries({queryKey: ['GetAssetFileTextContent', {id: fileIdAsString}]});
        void queryClient.invalidateQueries({queryKey: ['GetAssetFileVersions', {id: fileIdAsString}]});
    };

    const updateTextContentMutation = useUpdateAssetFileTextContentMutation({
        onSuccess: () => {
            invalidateFileQueries();

            toast.success('File saved');
        },
    });

    const restoreVersionMutation = useRestoreAssetFileVersionMutation({
        onSuccess: () => {
            invalidateFileQueries();

            toast.success('Version restored');
        },
    });

    const language = useMemo(() => (file ? inferLanguage(file.name, file.mimeType) : 'plaintext'), [file]);

    const csvRows = useMemo(
        () => (isCsv && viewMode === 'preview' ? parseCsvPreview(editorValue) : []),
        [editorValue, isCsv, viewMode]
    );

    const versions = versionsData?.assetFileVersions ?? [];

    const showEditor = isText && (!isPreviewableText || viewMode === 'edit');
    const showPreviewPane = isPreviewableText && viewMode === 'preview';

    const handleOpenChange = (open: boolean) => {
        if (!open) {
            setSelectedFileId(null);
            setEditorValue('');
            setShowVersions(false);
            setViewMode('preview');
        }
    };

    const handleSaveClick = () => {
        if (!file) {
            return;
        }

        updateTextContentMutation.mutate({content: editorValue, id: file.id});
    };

    const handleRestoreVersionClick = (versionId: string) => {
        if (!file) {
            return;
        }

        restoreVersionMutation.mutate({id: file.id, versionId});
    };

    const handleHtmlFullscreenClick = () => {
        void htmlPreviewContainerRef.current?.requestFullscreen();
    };

    useEffect(() => {
        if (textContentData?.assetFileTextContent != null) {
            setEditorValue(textContentData.assetFileTextContent);
        }
    }, [textContentData?.assetFileTextContent]);

    useEffect(() => {
        setShowVersions(false);
        setViewMode('preview');
    }, [selectedFileId]);

    return (
        <Sheet onOpenChange={handleOpenChange} open={selectedFileId !== null}>
            <VisuallyHidden.Root>
                <SheetTitle>{file?.name ?? 'File'}</SheetTitle>
            </VisuallyHidden.Root>

            <SheetContent
                className="top-3 right-4 bottom-4 flex h-auto w-[90%] flex-col gap-0 rounded-md bg-surface-neutral-secondary p-0 sm:max-w-[900px]"
                data-testid="asset-file-detail-sheet"
                onFocusOutside={(event) => event.preventDefault()}
                onPointerDownOutside={(event) => event.preventDefault()}
            >
                {file ? (
                    <>
                        <header className="flex w-full shrink-0 items-center justify-between gap-x-3 rounded-t-md border-b border-b-border/50 bg-surface-neutral-primary p-3">
                            <div className="flex items-center gap-x-2">
                                <FileTextIcon className="size-5 text-content-neutral-secondary" />

                                <span className="flex flex-col">
                                    <span className="flex items-center gap-x-2">
                                        <strong className="text-base text-content-neutral-primary">{file.name}</strong>

                                        {/*
                                         * Env badge: makes the file's environment visible at a glance, and the
                                         * destructive style makes a deep-linked file from a foreign environment
                                         * obvious without forcing the user to read the body hint below. The label
                                         * uses short forms (DEV/STAGING/PROD) to fit the header without truncation.
                                         */}

                                        <Badge
                                            data-testid="asset-file-environment-badge"
                                            label={environmentLabel(Number(file.environmentId))}
                                            styleType={
                                                Number(file.environmentId) === currentEnvironmentId
                                                    ? 'secondary-outline'
                                                    : 'destructive-outline'
                                            }
                                        />
                                    </span>

                                    <span className="text-xs text-content-neutral-secondary">
                                        {file.mimeType} &middot; {file.source}
                                    </span>
                                </span>
                            </div>

                            <div className="flex items-center gap-x-2">
                                {isPreviewableText && (
                                    <div className="flex items-center rounded-md border border-border/50">
                                        <Button
                                            data-testid="asset-file-preview-toggle"
                                            label="Preview"
                                            onClick={() => setViewMode('preview')}
                                            size="sm"
                                            variant={viewMode === 'preview' ? 'secondary' : 'ghost'}
                                        />

                                        <Button
                                            data-testid="asset-file-edit-toggle"
                                            label="Edit"
                                            onClick={() => setViewMode('edit')}
                                            size="sm"
                                            variant={viewMode === 'edit' ? 'secondary' : 'ghost'}
                                        />
                                    </div>
                                )}

                                <Button
                                    data-testid="asset-file-history-toggle"
                                    icon={<HistoryIcon />}
                                    onClick={() => setShowVersions((previousShowVersions) => !previousShowVersions)}
                                    size="iconSm"
                                    title="Version history"
                                    variant={showVersions ? 'secondary' : 'ghost'}
                                />

                                {showEditor && (
                                    <Button
                                        disabled={updateTextContentMutation.isPending}
                                        icon={<SaveIcon />}
                                        onClick={handleSaveClick}
                                        size="iconSm"
                                        title="Save"
                                    />
                                )}

                                <SheetCloseButton />
                            </div>
                        </header>

                        {Number(file.environmentId) !== currentEnvironmentId && (
                            // Cross-env deep-link explanation. Without this banner a user who clicks a deep-link
                            // (e.g. from an audit log) into a file from another environment sees the detail sheet but
                            // an empty list behind it, with no obvious reason — the list is correctly filtering by the
                            // current env while the deep-link forced the sheet open. The hint explains the mismatch
                            // and leaves env-switching to the existing EnvironmentSelect on the page header rather
                            // than auto-mutating the user's selection.
                            <div
                                className="shrink-0 border-b border-b-border/50 bg-amber-50 p-3 text-xs text-amber-900"
                                data-testid="asset-file-env-mismatch"
                            >
                                {`This file lives in ${environmentLabel(Number(file.environmentId))}, but you are currently viewing the ${environmentLabel(currentEnvironmentId)} file list. Switch environments via the toolbar selector to see it alongside its peers.`}
                            </div>
                        )}

                        {showVersions && (
                            <div
                                className="max-h-48 shrink-0 overflow-auto border-b border-b-border/50 bg-surface-neutral-primary"
                                data-testid="asset-file-versions"
                            >
                                {versions.length === 0 ? (
                                    <p className="p-3 text-xs text-muted-foreground">
                                        No previous versions. A version is captured every time the file&apos;s content
                                        changes.
                                    </p>
                                ) : (
                                    <ul>
                                        {versions.map((version) => (
                                            <li
                                                className="flex items-center justify-between border-b border-b-border/30 px-3 py-2 text-xs"
                                                data-testid={`asset-file-version-${version.id}`}
                                                key={version.id}
                                            >
                                                <span className="flex items-center gap-3">
                                                    <span className="font-semibold">v{version.versionNumber}</span>

                                                    <span className="text-muted-foreground">
                                                        {formatBytes(Number(version.sizeBytes))}
                                                    </span>

                                                    <span className="text-muted-foreground">
                                                        {formatDate(version.createdDate)}
                                                    </span>

                                                    {version.createdBy && (
                                                        <span className="text-muted-foreground">
                                                            {version.createdBy}
                                                        </span>
                                                    )}
                                                </span>

                                                <Button
                                                    disabled={restoreVersionMutation.isPending}
                                                    label="Restore"
                                                    onClick={() => handleRestoreVersionClick(version.id)}
                                                    size="sm"
                                                    variant="outline"
                                                />
                                            </li>
                                        ))}
                                    </ul>
                                )}
                            </div>
                        )}

                        <div className="flex min-h-0 flex-1 p-3">
                            <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-md bg-surface-neutral-primary">
                                {showEditor && (
                                    <div className="flex-1" data-testid="asset-file-monaco">
                                        <Suspense fallback={<MonacoEditorLoader />}>
                                            <MonacoEditor
                                                defaultLanguage={language}
                                                onChange={(value) => setEditorValue(value ?? '')}
                                                onMount={() => {}}
                                                options={{
                                                    automaticLayout: true,
                                                    fontSize: 12,
                                                    minimap: {enabled: false},
                                                    scrollBeyondLastLine: false,
                                                    wordWrap: 'on',
                                                }}
                                                value={editorValue}
                                            />
                                        </Suspense>
                                    </div>
                                )}

                                {showPreviewPane && isMarkdown && (
                                    <div
                                        className="prose max-w-none flex-1 overflow-auto p-4"
                                        data-testid="asset-file-markdown-preview"
                                    >
                                        <Markdown>{editorValue}</Markdown>
                                    </div>
                                )}

                                {showPreviewPane && isCsv && (
                                    <div className="flex-1 overflow-auto p-2" data-testid="asset-file-csv-preview">
                                        <table className="w-full border-collapse text-xs">
                                            <tbody>
                                                {csvRows.map((row, rowIndex) => (
                                                    <tr key={rowIndex}>
                                                        {row.map((cell, cellIndex) => (
                                                            <td
                                                                className="border border-border/50 px-2 py-1"
                                                                key={cellIndex}
                                                            >
                                                                {cell}
                                                            </td>
                                                        ))}
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>

                                        {csvRows.length >= CSV_PREVIEW_MAX_ROWS && (
                                            <p className="p-2 text-xs text-muted-foreground">
                                                Preview truncated to the first {CSV_PREVIEW_MAX_ROWS} rows. Download the
                                                file for the full data.
                                            </p>
                                        )}
                                    </div>
                                )}

                                {showPreviewPane && isHtml && !isMarkdown && !isCsv && (
                                    <div
                                        className="relative flex flex-1 flex-col"
                                        data-testid="asset-file-html-preview"
                                        ref={htmlPreviewContainerRef}
                                    >
                                        <Button
                                            className="absolute top-2 right-2 z-10"
                                            icon={<MaximizeIcon />}
                                            onClick={handleHtmlFullscreenClick}
                                            size="iconSm"
                                            title="Fullscreen"
                                            variant="secondary"
                                        />

                                        {/* sandbox="" blocks scripts and same-origin access: the HTML is user or
                                            AI supplied content and must not run with application-origin powers. */}

                                        <iframe
                                            className="size-full flex-1 border-0 bg-white"
                                            sandbox=""
                                            srcDoc={editorValue}
                                            title={file.name}
                                        />
                                    </div>
                                )}

                                {isImage && (
                                    <div className="flex flex-1 items-center justify-center overflow-auto p-4">
                                        <img
                                            alt={file.name}
                                            className="max-h-full max-w-full"
                                            data-testid="asset-file-image"
                                            src={file.downloadUrl}
                                        />
                                    </div>
                                )}

                                {isPdf && (
                                    <iframe
                                        className="flex-1 rounded-md"
                                        data-testid="asset-file-iframe"
                                        src={`${file.downloadUrl}?disposition=inline`}
                                        title={file.name}
                                    />
                                )}

                                {!isText && !isImage && !isPdf && (
                                    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8">
                                        <p className="text-sm text-muted-foreground">Preview not available</p>

                                        <a
                                            className="inline-flex h-9 items-center justify-center gap-2 rounded-md bg-surface-brand-primary px-4 py-2 text-sm font-medium text-content-onsurface-primary hover:bg-surface-brand-primary-hover"
                                            data-testid="asset-file-download"
                                            download={file.name}
                                            href={file.downloadUrl}
                                            rel="noreferrer"
                                        >
                                            <DownloadIcon className="size-4" /> Download
                                        </a>
                                    </div>
                                )}
                            </div>
                        </div>
                    </>
                ) : (
                    <div className="flex size-full items-center justify-center">
                        <span className="text-sm text-muted-foreground">Loading...</span>
                    </div>
                )}
            </SheetContent>
        </Sheet>
    );
};

export default AssetFileDetailSheet;

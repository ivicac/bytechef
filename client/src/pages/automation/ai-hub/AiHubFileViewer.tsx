import {AiHubViewModeType} from '@/pages/automation/ai-hub/stores/useAiHubTabsStore';
import MonacoEditorWrapper from '@/shared/components/MonacoEditorWrapper';
import {useGetAssetFileQuery} from '@/shared/middleware/graphql';
import {DownloadIcon, FileTextIcon} from 'lucide-react';
import {useMemo} from 'react';
import Markdown from 'react-markdown';

import AiHubChartPane from './AiHubChartPane';
import AiHubHtmlInteractivePane from './AiHubHtmlInteractivePane';
import useFileContent from './hooks/useFileContent';

interface AiHubFileViewerProps {
    fileId: string;
    name: string;
    viewMode: AiHubViewModeType;
}

const isTextualMimeType = (mimeType: string): boolean =>
    mimeType.startsWith('text/') || mimeType === 'application/json';

const languageForMimeType = (mimeType: string, name: string): string => {
    if (mimeType === 'application/json') return 'json';
    if (mimeType === 'text/markdown') return 'markdown';
    if (mimeType === 'text/html') return 'html';
    if (mimeType === 'text/css') return 'css';
    if (mimeType === 'text/javascript') return 'javascript';
    if (mimeType === 'text/x-python') return 'python';
    if (mimeType === 'text/x-java') return 'java';
    if (mimeType === 'text/yaml') return 'yaml';

    const dotIndex = name.lastIndexOf('.');

    if (dotIndex < 0) return 'plaintext';

    const extension = name.slice(dotIndex + 1).toLowerCase();

    return (
        (
            {
                css: 'css',
                html: 'html',
                java: 'java',
                js: 'javascript',
                json: 'json',
                md: 'markdown',
                py: 'python',
                sql: 'sql',
                ts: 'typescript',
                tsx: 'typescript',
                yaml: 'yaml',
                yml: 'yaml',
            } as Record<string, string>
        )[extension] ?? 'plaintext'
    );
};

const noop = () => {};

const IMAGE_MIME_TYPES = new Set(['image/gif', 'image/jpeg', 'image/png', 'image/svg+xml', 'image/webp']);

const PPTX_MIME_TYPE = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';

interface PaneProps {
    content: string;
    fileId: string;
    mimeType: string;
    name: string;
}

const EditorPane = ({content, mimeType, name}: PaneProps) => {
    const language = useMemo(() => languageForMimeType(mimeType, name), [mimeType, name]);

    return (
        <div className="size-full">
            <MonacoEditorWrapper defaultLanguage={language} onChange={noop} onMount={noop} value={content} />
        </div>
    );
};

const PreviewPane = ({content, fileId, mimeType, name}: PaneProps) => {
    if (IMAGE_MIME_TYPES.has(mimeType)) {
        return (
            <div className="flex size-full items-center justify-center overflow-auto bg-surface-neutral-primary p-4">
                <img
                    alt={name}
                    className="max-h-full max-w-full object-contain"
                    src={`/api/automation/internal/asset-files/${fileId}/content`}
                />
            </div>
        );
    }

    if (mimeType === PPTX_MIME_TYPE) {
        const downloadUrl = `/api/automation/internal/asset-files/${fileId}/content`;

        return (
            <div className="flex size-full flex-col items-center justify-center gap-3 p-8 text-center">
                <FileTextIcon className="size-12 text-muted-foreground" />

                <div>
                    <p className="text-sm font-medium">{name}</p>

                    <p className="text-xs text-muted-foreground">PowerPoint presentation</p>
                </div>

                <a
                    className="inline-flex items-center gap-1 rounded-md border border-stroke-neutral-secondary bg-surface-main px-3 py-1.5 text-xs font-medium text-content-neutral-primary hover:bg-accent"
                    download={name}
                    href={downloadUrl}
                >
                    <DownloadIcon className="size-3.5" />
                    Download / Open externally
                </a>
            </div>
        );
    }

    // PDFs and other browser-renderable binaries: hand off to an <iframe> so the browser's built-in PDF
    // viewer (Chromium PDF.js fork / Firefox PDF.js / Safari preview) renders the file inline. The same
    // content endpoint feeds it. `sandbox=""` would block the PDF viewer's scripts in Chromium, so it's
    // intentionally omitted here — the asset-files endpoint is same-origin, served with the file's real
    // Content-Type, and the user already trusts content they uploaded into their workspace.
    if (mimeType === 'application/pdf') {
        return (
            <iframe
                className="size-full border-0 bg-surface-neutral-primary"
                // `?disposition=inline` makes the asset-files endpoint send `Content-Disposition: inline`
                // (the server only honors this for PDFs and images). Without it the server defaults to
                // `attachment`, which an <iframe> honors by triggering a download instead of rendering the
                // PDF in the browser's built-in viewer. (<img> ignores the header, so images need no param.)
                src={`/api/automation/internal/asset-files/${fileId}/content?disposition=inline`}
                title={name}
            />
        );
    }

    if (!isTextualMimeType(mimeType)) {
        // Last-resort fallback for binaries we don't have a tailored renderer for (archives, executables,
        // some MS-Office formats). Try an iframe first — for types the browser knows how to render
        // (e.g. audio/video MP4) it shows the player; for unknown types it usually goes blank, so the
        // download link below the iframe is the actual recovery path.
        const downloadUrl = `/api/automation/internal/asset-files/${fileId}/content`;

        return (
            <div className="flex size-full flex-col">
                <iframe className="min-h-0 flex-1 border-0 bg-surface-neutral-primary" src={downloadUrl} title={name} />

                <div className="flex shrink-0 items-center justify-between gap-3 border-t border-stroke-neutral-secondary px-4 py-2 text-xs text-muted-foreground">
                    <span className="truncate">
                        {name}

                        <span className="ml-2 text-content-neutral-tertiary">{mimeType || 'unknown type'}</span>
                    </span>

                    <a
                        className="inline-flex shrink-0 items-center gap-1 rounded-md border border-stroke-neutral-secondary bg-surface-main px-2 py-1 font-medium text-content-neutral-primary hover:bg-accent"
                        download={name}
                        href={downloadUrl}
                    >
                        <DownloadIcon className="size-3" />
                        Download
                    </a>
                </div>
            </div>
        );
    }

    if (mimeType === 'text/markdown') {
        return (
            <div className="prose prose-sm size-full overflow-auto p-4 dark:prose-invert">
                <Markdown>{content}</Markdown>
            </div>
        );
    }

    if (mimeType === 'text/html') {
        return <iframe className="size-full border-0" sandbox="" srcDoc={content} title={name} />;
    }

    return <pre className="size-full overflow-auto p-4 text-xs">{content}</pre>;
};

const AiHubFileViewer = ({fileId, name, viewMode}: AiHubFileViewerProps) => {
    const {content, error, loading, mimeType} = useFileContent(fileId);

    // Pulled from the GraphQL surface (already exposed by the assetFile query) to drive format-aware viewer
    // dispatch. format is the artifact's logical role (CHART vs JSON) which the mime type alone cannot
    // distinguish — a CSV emitted as a chart staging file is `format=CHART, mime=text/csv`, and a chart spec
    // is `format=CHART, mime=application/json`. Using the format column instead of sniffing JSON shape keeps
    // the viewer routing predictable.
    const {data: metadataData} = useGetAssetFileQuery({id: fileId});
    const format = metadataData?.assetFile?.format ?? null;
    const metadataJson = metadataData?.assetFile?.metadataJson ?? null;

    if (loading) {
        return <div className="flex size-full items-center justify-center text-sm text-muted-foreground">Loading…</div>;
    }

    if (error) {
        return <div className="flex size-full items-center justify-center p-4 text-sm text-destructive">{error}</div>;
    }

    if (format === 'CHART') {
        // Prefer metadataJson (the chart generator's authoritative spec slot); fall back to file content for any
        // legacy chart row whose metadata column was not populated. Editor mode still drops the user into the
        // raw JSON via the existing Monaco pane so they can hand-edit the spec; preview/split show the chart.
        const spec = metadataJson || content;

        if (viewMode === 'editor') {
            return <EditorPane content={content} fileId={fileId} mimeType={mimeType} name={name} />;
        }

        if (viewMode === 'preview') {
            return <AiHubChartPane spec={spec} />;
        }

        return (
            <div className="flex size-full">
                <div className="size-full flex-1 border-r">
                    <EditorPane content={content} fileId={fileId} mimeType={mimeType} name={name} />
                </div>

                <div className="size-full flex-1">
                    <AiHubChartPane spec={spec} />
                </div>
            </div>
        );
    }

    if (format === 'HTML') {
        if (viewMode === 'editor') {
            return <EditorPane content={content} fileId={fileId} mimeType={mimeType} name={name} />;
        }

        if (viewMode === 'preview') {
            return <AiHubHtmlInteractivePane content={content} name={name} />;
        }

        return (
            <div className="flex size-full">
                <div className="size-full flex-1 border-r">
                    <EditorPane content={content} fileId={fileId} mimeType={mimeType} name={name} />
                </div>

                <div className="size-full flex-1">
                    <AiHubHtmlInteractivePane content={content} name={name} />
                </div>
            </div>
        );
    }

    // Force Preview for any non-textual file — Editor mode renders Monaco against empty content for
    // binaries, which looks broken. The iframe/img branches inside PreviewPane all key on mimeType, so
    // routing every binary through PreviewPane gives the right viewer regardless of the user's last
    // toggle-group selection.
    if (IMAGE_MIME_TYPES.has(mimeType) || mimeType === PPTX_MIME_TYPE || !isTextualMimeType(mimeType)) {
        return <PreviewPane content={content} fileId={fileId} mimeType={mimeType} name={name} />;
    }

    if (viewMode === 'editor') {
        return <EditorPane content={content} fileId={fileId} mimeType={mimeType} name={name} />;
    }

    if (viewMode === 'preview') {
        return <PreviewPane content={content} fileId={fileId} mimeType={mimeType} name={name} />;
    }

    return (
        <div className="flex size-full">
            <div className="size-full flex-1 border-r">
                <EditorPane content={content} fileId={fileId} mimeType={mimeType} name={name} />
            </div>

            <div className="size-full flex-1">
                <PreviewPane content={content} fileId={fileId} mimeType={mimeType} name={name} />
            </div>
        </div>
    );
};

export default AiHubFileViewer;

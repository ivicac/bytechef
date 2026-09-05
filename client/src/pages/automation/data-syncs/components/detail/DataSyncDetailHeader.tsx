import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import {ButtonGroup} from '@/components/ui/button-group';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import DataSyncDialog from '@/pages/automation/data-syncs/components/DataSyncDialog';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import ProjectVersionHistorySheet from '@/pages/automation/project/components/ProjectVersionHistorySheet';
import PublishPopover from '@/pages/automation/project/components/project-header/components/PublishPopover';
import Header from '@/shared/layout/Header';
import {ProjectDeployment, ProjectStatus} from '@/shared/middleware/automation/configuration';
import {
    useDataSyncVersionsQuery,
    useDeleteDataSyncMutation,
    usePublishDataSyncMutation,
} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useQueryClient} from '@tanstack/react-query';
import {EllipsisVerticalIcon, HistoryIcon, PencilIcon, RocketIcon, Trash2Icon} from 'lucide-react';
import {useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface DataSyncDetailHeaderProps {
    description?: string | null;
    id: string;
    lastPublishedVersion: number;
    projectId: string;
    title: string;
}

const DataSyncDetailHeader = ({description, id, lastPublishedVersion, projectId, title}: DataSyncDetailHeaderProps) => {
    const [showDeployDialog, setShowDeployDialog] = useState(false);
    const [showEditDialog, setShowEditDialog] = useState(false);
    const [showVersionHistorySheet, setShowVersionHistorySheet] = useState(false);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    // Only fetched once the sheet is opened: the history is a rarely-used view, and every publish invalidates it
    // through invalidateDataSyncQueries anyway.
    const {data: dataSyncVersionsData} = useDataSyncVersionsQuery({id}, {enabled: showVersionHistorySheet});

    const publishDataSyncMutation = usePublishDataSyncMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to publish the data sync.');
        },
        onSuccess: () => {
            invalidateDataSyncQueries(queryClient);

            toast.success('Data sync published.');
        },
    });

    const deleteDataSyncMutation = useDeleteDataSyncMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to delete the data sync.');
        },
        onSuccess: () => {
            invalidateDataSyncQueries(queryClient);

            // The page being viewed is gone, so returning to the list is the only sensible destination.
            navigate('/automation/data-syncs');
        },
    });

    // Mirrors the agent/project header's pill: the draft is always the version above the last published one
    // (Project.publish() stamps the current version PUBLISHED and appends a fresh draft in the same call), so
    // a never-published data sync is V1, not V0. The published version is what Deploy reports; this pill does
    // not.

    // Only a published version has a workflow a ProjectDeployment can reference.
    const deployable = lastPublishedVersion > 0;

    const handlePublishSubmit = ({description, onSuccess}: {description?: string; onSuccess: () => void}) => {
        publishDataSyncMutation.mutate({description, id}, {onSuccess});
    };

    // The sheet is the project one: a data sync version IS a ProjectVersion of the hidden backing project, so
    // the GraphQL rows are mapped into that shape rather than the sheet being duplicated for a second date
    // format.
    const dataSyncVersions = useMemo(
        () =>
            (dataSyncVersionsData?.dataSyncVersions ?? []).map((dataSyncVersion) => ({
                description: dataSyncVersion.description ?? undefined,
                publishedDate: dataSyncVersion.publishedDate ? new Date(dataSyncVersion.publishedDate) : undefined,
                status: dataSyncVersion.status === 'PUBLISHED' ? ProjectStatus.Published : ProjectStatus.Draft,
                version: dataSyncVersion.version,
            })),
        [dataSyncVersionsData?.dataSyncVersions]
    );

    return (
        <>
            <Header
                centerTitle
                description={description || undefined}
                position="main"
                right={
                    <div className="flex items-center gap-1">
                        {/* Publish and Deploy as one segmented group of outline buttons, the same arrangement
                            and hierarchy the agent and project headers use. Publish takes a description
                            through the project's own popover, which is what fills the version history the
                            button beside it opens. */}

                        <ButtonGroup>
                            <PublishPopover
                                isPending={publishDataSyncMutation.isPending}
                                onPublishProjectSubmit={handlePublishSubmit}
                                title="Publish Data Sync"
                                tooltip="Publish the data sync"
                            />

                            <Button
                                disabled={!deployable}
                                icon={<RocketIcon />}
                                label="Deploy"
                                onClick={() => setShowDeployDialog(true)}
                                variant="outline"
                            />
                        </ButtonGroup>

                        <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                                <Button
                                    aria-label="Data Sync menu"
                                    icon={<EllipsisVerticalIcon />}
                                    size="icon"
                                    variant="ghost"
                                />
                            </DropdownMenuTrigger>

                            <DropdownMenuContent align="end">
                                {/* In the menu rather than as a header button of its own, the way the project
                                    header keeps Project History in its settings menu. */}

                                <DropdownMenuItem onClick={() => setShowVersionHistorySheet(true)}>
                                    <HistoryIcon /> Data Sync History
                                </DropdownMenuItem>

                                <DropdownMenuItem onClick={() => setShowEditDialog(true)}>
                                    <PencilIcon /> Edit
                                </DropdownMenuItem>

                                <DropdownMenuSeparator />

                                <DropdownMenuItem
                                    disabled={deleteDataSyncMutation.isPending}
                                    onClick={() => deleteDataSyncMutation.mutate({id})}
                                    variant="destructive"
                                >
                                    <Trash2Icon /> Delete
                                </DropdownMenuItem>
                            </DropdownMenuContent>
                        </DropdownMenu>
                    </div>
                }
                title={
                    <div className="flex items-center space-x-2">
                        <span>{title}</span>

                        <Badge
                            className="flex space-x-1 bg-surface-neutral-primary"
                            styleType="outline-outline"
                            weight="semibold"
                        >
                            <span>V{lastPublishedVersion + 1}</span>

                            <span>DRAFT</span>
                        </Badge>
                    </div>
                }
            />

            {showDeployDialog && (
                <ProjectDeploymentDialog
                    onClose={() => setShowDeployDialog(false)}
                    projectDeployment={
                        {
                            environmentId: currentEnvironmentId,
                            projectId: +projectId,
                            projectVersion: lastPublishedVersion,
                        } as ProjectDeployment
                    }
                    redirectOnSubmit={false}
                />
            )}

            {/* Controlled: the menu item that opens it unmounts on select, so the dialog cannot hang off a
                trigger inside the menu. */}

            <DataSyncDialog
                dataSync={{description, id, title}}
                onOpenChange={setShowEditDialog}
                open={showEditDialog}
            />

            {showVersionHistorySheet && (
                <ProjectVersionHistorySheet
                    onSheetOpenChange={setShowVersionHistorySheet}
                    projectVersions={dataSyncVersions}
                    sheetOpen={showVersionHistorySheet}
                    title="Data Sync History"
                />
            )}
        </>
    );
};

export default DataSyncDetailHeader;

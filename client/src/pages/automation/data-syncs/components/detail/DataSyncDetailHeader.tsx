import Button from '@/components/Button/Button';
import {ButtonGroup} from '@/components/ui/button-group';
import {Separator} from '@/components/ui/separator';
import DataSyncDialog from '@/pages/automation/data-syncs/components/DataSyncDialog';
import DeleteDataSyncAlertDialog from '@/pages/automation/data-syncs/components/DeleteDataSyncAlertDialog';
import useDataSyncActions from '@/pages/automation/data-syncs/hooks/useDataSyncActions';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import ProjectVersionHistorySheet from '@/pages/automation/project/components/ProjectVersionHistorySheet';
import PublishPopover from '@/pages/automation/project/components/project-header/components/PublishPopover';
import SettingsMenu from '@/pages/automation/project/components/project-header/components/settings-menu/SettingsMenu';
import LoadingIndicator from '@/shared/components/LoadingIndicator';
import Header from '@/shared/layout/Header';
import {Project, ProjectDeployment, ProjectStatus} from '@/shared/middleware/automation/configuration';
import {useDataSyncVersionsQuery} from '@/shared/middleware/graphql';
import {usePublishProjectMutation} from '@/shared/mutations/automation/projects.mutations';
import {useGetProjectWorkflowsQuery} from '@/shared/queries/automation/projectWorkflows.queries';
import {ProjectKeys} from '@/shared/queries/automation/projects.queries';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {onlineManager, useIsFetching, useQueryClient} from '@tanstack/react-query';
import {EditIcon, HistoryIcon, RocketIcon, Trash2Icon} from 'lucide-react';
import {MouseEvent, ReactNode, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface DataSyncDetailHeaderProps {
    description?: string | null;
    id: string;
    lastPublishedVersion: number;
    /** Rendered as the whole title row — the project page puts its sidebar toggle, project breadcrumb (name
     *  and project version badge), and data sync switcher here. Versions are project-level, so this header no
     *  longer renders a data-sync-only version badge of its own. */
    leading?: ReactNode;
    /** The data sync's project — used to show the same settings menu the workflow and agent headers show.
     *  Undefined only while the project is still loading. */
    project?: Project;
    projectId: string;
    title: string;
}

const DataSyncDetailHeader = ({
    description,
    id,
    lastPublishedVersion,
    leading,
    project,
    projectId,
    title,
}: DataSyncDetailHeaderProps) => {
    const [showDeployDialog, setShowDeployDialog] = useState(false);
    const [showVersionHistorySheet, setShowVersionHistorySheet] = useState(false);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const isFetching = useIsFetching();
    const isOnline = onlineManager.isOnline();

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    // Already fetched by the page for its item switcher, so this only reads the cache: it is here to answer
    // where the user goes once the page they are standing on is deleted.
    const {data: projectWorkflows} = useGetProjectWorkflowsQuery(+projectId);

    // Shared with the sidebar row menu (DataSyncsLeftSidebarDropdownMenu) so the mutation and its toast/
    // invalidation side effects are defined once.
    const {
        deleteDataSync,
        handleDeleteClick,
        isDeleting,
        openEditDialog,
        setShowDeleteConfirmDialog,
        setShowEditDialog,
        showDeleteConfirmDialog,
        showEditDialog,
    } = useDataSyncActions({
        dataSync: {id},
        onDeleted: () => {
            const firstProjectWorkflow = projectWorkflows?.[0];

            // This page is gone, so the user lands back inside the project the sync lived in — or on the
            // projects list when the project has no workflow left to show.
            if (firstProjectWorkflow) {
                navigate(
                    `/automation/projects/${projectId}/project-workflows/${firstProjectWorkflow.projectWorkflowId}`
                );
            } else {
                navigate('/automation/projects');
            }
        },
    });

    // Only fetched once the sheet is opened: the history is a rarely-used view, and every publish invalidates it
    // through invalidateDataSyncQueries anyway.
    const {data: dataSyncVersionsData} = useDataSyncVersionsQuery({id}, {enabled: showVersionHistorySheet});

    const publishProjectMutation = usePublishProjectMutation({
        onSuccess: () => {
            invalidateDataSyncQueries(queryClient);

            queryClient.invalidateQueries({queryKey: ProjectKeys.project(+projectId)});

            toast.success('The project has been published.');
        },
    });

    // Only a published version has a workflow a ProjectDeployment can reference.
    const deployable = lastPublishedVersion > 0;

    const handlePublishSubmit = ({description, onSuccess}: {description?: string; onSuccess: () => void}) => {
        publishProjectMutation.mutate({id: +projectId, publishProjectRequest: {description}}, {onSuccess});
    };

    // The sheet is the project one: a data sync version IS a ProjectVersion of the project the sync lives in,
    // so the GraphQL rows are mapped into that shape rather than the sheet being duplicated for a second date
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

    // The settings menu's Data Sync tab, in place of the Workflow tab — the sync's generated workflow is not
    // the user's to edit, and this tab carries the actions the header's own ⋮ menu used to (Edit, Data Sync
    // History, Delete), which is why that menu is gone. Purely presentational, like WorkflowTabButtons: the
    // dialogs it opens stay mounted outside the dropdown, which unmounts its content on close.
    const renderDataSyncTab = (onCloseDropdownMenu: () => void) => {
        const handleButtonClick = (event: MouseEvent<HTMLDivElement>) => {
            if ((event.target as HTMLElement).tagName === 'BUTTON') {
                onCloseDropdownMenu();
            }
        };

        return (
            <div className="flex flex-col" onClick={handleButtonClick}>
                <Button
                    className="dropdown-menu-item"
                    icon={<EditIcon />}
                    label="Edit"
                    onClick={openEditDialog}
                    variant="ghost"
                />

                <Button
                    className="dropdown-menu-item"
                    icon={<HistoryIcon />}
                    label="Data Sync History"
                    onClick={() => setShowVersionHistorySheet(true)}
                    variant="ghost"
                />

                <Separator />

                <Button
                    className="dropdown-menu-item-destructive"
                    disabled={isDeleting}
                    icon={<Trash2Icon />}
                    label="Delete"
                    onClick={handleDeleteClick}
                    variant="ghost"
                />
            </div>
        );
    };

    return (
        <>
            <Header
                centerTitle
                description={description || undefined}
                position="main"
                right={
                    <div className="flex items-center gap-1">
                        <LoadingIndicator isFetching={isFetching} isOnline={isOnline} />

                        {project && (
                            <SettingsMenu
                                firstTab={{
                                    ariaLabel: 'Data Sync tab',
                                    content: renderDataSyncTab,
                                    label: 'Data Sync',
                                    value: 'dataSync',
                                }}
                                project={project}
                            />
                        )}

                        {/* Publish and Deploy as one segmented group of outline buttons, the same arrangement
                            and hierarchy the agent and project headers use. Publish publishes the whole project
                            the data sync lives in, taking a description through the project's own popover. */}

                        <ButtonGroup>
                            <PublishPopover
                                isPending={publishProjectMutation.isPending}
                                onPublishProjectSubmit={handlePublishSubmit}
                                title="Publish Project"
                                tooltip="Publishes every workflow, agent and data sync in this project."
                            />

                            <Button
                                disabled={!deployable}
                                icon={<RocketIcon />}
                                label="Deploy Project"
                                onClick={() => setShowDeployDialog(true)}
                                variant="outline"
                            />
                        </ButtonGroup>
                    </div>
                }
                title={leading || title}
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

            {showDeleteConfirmDialog && (
                <DeleteDataSyncAlertDialog
                    dataSyncTitle={title}
                    onClose={() => setShowDeleteConfirmDialog(false)}
                    onDelete={deleteDataSync}
                />
            )}

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

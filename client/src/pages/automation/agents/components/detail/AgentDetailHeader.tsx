import Button from '@/components/Button/Button';
import {ButtonGroup} from '@/components/ui/button-group';
import AgentDialog from '@/pages/automation/agents/components/AgentDialog';
import DeleteAgentAlertDialog from '@/pages/automation/agents/components/DeleteAgentAlertDialog';
import AgentTabButtons from '@/pages/automation/agents/components/detail/AgentTabButtons';
import useAgentActions from '@/pages/automation/agents/hooks/useAgentActions';
import exportAgent from '@/pages/automation/agents/utils/agentImportExport';
import invalidateAgentQueries from '@/pages/automation/agents/utils/invalidateAgentQueries';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import ProjectVersionHistorySheet from '@/pages/automation/project/components/ProjectVersionHistorySheet';
import PublishPopover from '@/pages/automation/project/components/project-header/components/PublishPopover';
import SettingsMenu from '@/pages/automation/project/components/project-header/components/settings-menu/SettingsMenu';
import LoadingIndicator from '@/shared/components/LoadingIndicator';
import Header from '@/shared/layout/Header';
import {Project, ProjectDeployment, ProjectStatus} from '@/shared/middleware/automation/configuration';
import {useAiAgentVersionsQuery} from '@/shared/middleware/graphql';
import {usePublishProjectMutation} from '@/shared/mutations/automation/projects.mutations';
import {ProjectKeys} from '@/shared/queries/automation/projects.queries';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {onlineManager, useIsFetching, useQueryClient} from '@tanstack/react-query';
import {PlayIcon, RocketIcon, SparklesIcon} from 'lucide-react';
import {ReactNode, useMemo, useState} from 'react';
import {toast} from 'sonner';

interface AgentDetailHeaderProps {
    description?: string | null;
    id: string;
    lastPublishedVersion: number;
    /** Rendered as the whole title row — the project page puts its sidebar toggle, project breadcrumb (name
     *  and project version badge), and agent switcher here. Versions are project-level, so this header no
     *  longer renders an agent-only version badge of its own. */
    leading?: ReactNode;
    onAskCopilot?: () => void;
    onToggleTestPanel: () => void;
    /** The agent's backing project — used to show the same status dot and settings menu the workflow header
     *  shows. Undefined only while the project is still loading. */
    project?: Project;
    projectId: string;
    testPanelOpen: boolean;
    title: string;
}

const AgentDetailHeader = ({
    description,
    id,
    lastPublishedVersion,
    leading,
    onAskCopilot,
    onToggleTestPanel,
    project,
    projectId,
    testPanelOpen,
    title,
}: AgentDetailHeaderProps) => {
    const [showDeployDialog, setShowDeployDialog] = useState(false);
    const [showVersionHistorySheet, setShowVersionHistorySheet] = useState(false);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const isFetching = useIsFetching();
    const isOnline = onlineManager.isOnline();

    const queryClient = useQueryClient();

    // Shared with the sidebar row menu (AgentsLeftSidebarDropdownMenu) so the mutation and its toast/
    // invalidation/navigation side effects are defined once.
    const {
        deleteAgentMutation,
        handleConfirmDelete,
        handleDeleteClick,
        setShowDeleteConfirmDialog,
        setShowEditDialog,
        showDeleteConfirmDialog,
        showEditDialog,
    } = useAgentActions({
        agentId: id,
        navigateOnDelete: true,
    });

    // Only fetched once the sheet is opened: the history is a rarely-used view, and every publish invalidates it
    // through invalidateAgentQueries anyway.
    const {data: agentVersionsData} = useAiAgentVersionsQuery({id}, {enabled: showVersionHistorySheet});

    const publishProjectMutation = usePublishProjectMutation({
        onSuccess: () => {
            invalidateAgentQueries(queryClient);

            queryClient.invalidateQueries({queryKey: ProjectKeys.project(+projectId)});

            toast.success('The project has been published.');
        },
    });

    // Only a published version has a workflow a ProjectDeployment can reference.
    const deployable = lastPublishedVersion > 0;

    const handlePublishSubmit = ({description, onSuccess}: {description?: string; onSuccess: () => void}) => {
        publishProjectMutation.mutate({id: +projectId, publishProjectRequest: {description}}, {onSuccess});
    };

    const handleExportClick = async () => {
        try {
            await exportAgent(id, title);
        } catch (error) {
            toast.error(error instanceof Error ? error.message : 'Failed to export the agent.');
        }
    };

    // The sheet is the project one: an agent version IS a ProjectVersion of the agent's project, so the
    // GraphQL rows are mapped into that shape rather than the sheet being duplicated for a second date format.
    const agentVersions = useMemo(
        () =>
            (agentVersionsData?.aiAgentVersions ?? []).map((agentVersion) => ({
                description: agentVersion.description ?? undefined,
                publishedDate: agentVersion.publishedDate ? new Date(agentVersion.publishedDate) : undefined,
                status: agentVersion.status === 'PUBLISHED' ? ProjectStatus.Published : ProjectStatus.Draft,
                version: agentVersion.version,
            })),
        [agentVersionsData?.aiAgentVersions]
    );

    return (
        <>
            <Header
                centerTitle
                description={description || undefined}
                position="main"
                right={
                    <div className="flex items-center gap-1">
                        <LoadingIndicator isFetching={isFetching} isOnline={isOnline} />

                        {/* An Agent tab in place of the Workflow tab — the agent page has no workflow of its
                            own, and this tab carries the actions the header's own ⋮ menu used to (Edit,
                            Agent History, Export, Delete), which is why that menu is gone. */}

                        {project && (
                            <SettingsMenu
                                firstTab={{
                                    ariaLabel: 'Agent tab',
                                    content: (onCloseDropdownMenu) => (
                                        <AgentTabButtons
                                            isDeletePending={deleteAgentMutation.isPending}
                                            onCloseDropdownMenu={onCloseDropdownMenu}
                                            onDeleteClick={handleDeleteClick}
                                            onExportClick={handleExportClick}
                                            onShowEditDialog={() => setShowEditDialog(true)}
                                            onShowVersionHistorySheet={() => setShowVersionHistorySheet(true)}
                                        />
                                    ),
                                    label: 'Agent',
                                    value: 'agent',
                                }}
                                project={project}
                            />
                        )}

                        {/* The test panel starts closed and shares the right rail with the copilot, so the
                            two never compete for it — and opening the copilot no longer has to tear a
                            mounted panel out from under the layout.

                            The icon avoids two collisions. Not a flask — the simple editor's header uses
                            FlaskConicalIcon for the evals panel, which is a different thing. And not a bot —
                            the sidebar and every agent row already use one to mean "an agent", so a bot here
                            read as another agent rather than as an action. A play icon matches the panel's own
                            "Test Agent" button. */}

                        {/* A labelled Test button, the same shape the project header's own run control has —
                            testing an agent is its primary action, not an icon-sized afterthought. */}

                        <Button
                            aria-label={testPanelOpen ? 'Hide Test Agent panel' : 'Test Agent'}
                            icon={<PlayIcon />}
                            label="Test"
                            onClick={onToggleTestPanel}
                            variant={testPanelOpen ? 'secondary' : 'default'}
                        />

                        {/* Publish and Deploy as one segmented group of outline buttons beside the primary Test
                            button, the same arrangement and hierarchy the project header uses. Publish publishes the
                            whole project the agent lives in, taking a description through the project's own
                            popover. */}

                        <ButtonGroup>
                            <PublishPopover
                                isPending={publishProjectMutation.isPending}
                                onPublishProjectSubmit={handlePublishSubmit}
                                title="Publish Project"
                                tooltip="Publishes every workflow and agent in this project."
                            />

                            <Button
                                disabled={!deployable}
                                icon={<RocketIcon />}
                                label="Deploy Project"
                                onClick={() => setShowDeployDialog(true)}
                                variant="outline"
                            />
                        </ButtonGroup>

                        {onAskCopilot && (
                            <Button
                                aria-label="Ask Copilot"
                                icon={<SparklesIcon className="size-4" />}
                                onClick={onAskCopilot}
                                size="icon"
                                variant="ghost"
                            />
                        )}
                    </div>
                }
                title={leading}
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

            <AgentDialog agent={{description, id, title}} onOpenChange={setShowEditDialog} open={showEditDialog} />

            {showDeleteConfirmDialog && (
                <DeleteAgentAlertDialog
                    agentTitle={title}
                    onClose={() => setShowDeleteConfirmDialog(false)}
                    onDelete={handleConfirmDelete}
                />
            )}

            {showVersionHistorySheet && (
                <ProjectVersionHistorySheet
                    onSheetOpenChange={setShowVersionHistorySheet}
                    projectVersions={agentVersions}
                    sheetOpen={showVersionHistorySheet}
                    title="Agent History"
                />
            )}
        </>
    );
};

export default AgentDetailHeader;

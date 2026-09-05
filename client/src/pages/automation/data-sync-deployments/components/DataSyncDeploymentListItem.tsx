import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import LoadingIcon from '@/components/LoadingIcon';
import Switch from '@/components/Switch/Switch';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {describeTrigger} from '@/pages/automation/data-syncs/utils/dataSyncTrigger';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import ProjectDeploymentListItemAlertDialog from '@/pages/automation/project-deployments/components/project-deployment-list/ProjectDeploymentListItemAlertDialog';
import ProjectDeploymentListItemDropdownMenu from '@/pages/automation/project-deployments/components/project-deployment-list/ProjectDeploymentListItemDropdownMenu';
import TagList from '@/shared/components/TagList';
import {
    DataSyncDeployment,
    DataSyncTriggerType,
    useRunDataSyncDeploymentMutation,
    useUpdateDataSyncDeploymentTagsMutation,
} from '@/shared/middleware/graphql';
import {Tag} from '@/shared/middleware/platform/configuration';
import {
    useDeleteProjectDeploymentMutation,
    useEnableProjectDeploymentMutation,
} from '@/shared/mutations/automation/projectDeployments.mutations';
import {
    ProjectDeploymentKeys,
    useGetProjectDeploymentQuery,
} from '@/shared/queries/automation/projectDeployments.queries';
import {useQueryClient} from '@tanstack/react-query';
import {PlayIcon} from 'lucide-react';
import {useMemo, useState} from 'react';
import {toast} from 'sonner';

interface DataSyncDeploymentListItemProps {
    deployment: DataSyncDeployment;
    /** Every tag used by a data sync deployment in the workspace, for the add-tag picker. */
    remainingTags?: Tag[];
}

const DataSyncDeploymentListItem = ({deployment, remainingTags}: DataSyncDeploymentListItemProps) => {
    const [showEditDialog, setShowEditDialog] = useState(false);
    const [showDeleteDialog, setShowDeleteDialog] = useState(false);

    // Fetched (rather than assembled from `deployment`) exactly like AgentDeploymentListItem does for its own
    // change-project-version dialog: ProjectDeploymentFacadeImpl.getProjectDeployment(long) is a plain unfiltered
    // findById, so it returns the full REST shape — name, tags, and each workflow's existing enabled/connection
    // state — that ProjectDeploymentDialog's "change version" step needs to pre-populate correctly, none of which
    // the slimmer dataSyncDeployments GraphQL query carries.
    const {data: projectDeployment} = useGetProjectDeploymentQuery(+deployment.id, showEditDialog);

    const queryClient = useQueryClient();

    const enableDataSyncDeploymentMutation = useEnableProjectDeploymentMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ProjectDeploymentKeys.projectDeployments});
        },
    });

    const deleteDataSyncDeploymentMutation = useDeleteProjectDeploymentMutation({
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ProjectDeploymentKeys.projectDeployments});

            // This list is the dataSyncDeployments GraphQL query, which the REST keys above do not reach.
            queryClient.invalidateQueries({queryKey: ['dataSyncDeployments']});
        },
    });

    // The run mutation takes the DATA SYNC's id first and the PROJECT DEPLOYMENT's id second — the server
    // refuses a deployment that does not belong to the named sync, so passing these backwards fails loudly
    // rather than silently running the wrong thing.
    const runDeploymentMutation = useRunDataSyncDeploymentMutation({
        onSuccess: () => {
            toast('Sync run requested.');

            queryClient.invalidateQueries({queryKey: ['dataSyncDeployments']});
        },
    });

    const isSwitchDisabled = enableDataSyncDeploymentMutation.isPending;

    const handleOnCheckedChange = (value: boolean) => {
        enableDataSyncDeploymentMutation.mutate(
            {enable: value, id: +deployment.id},
            {
                onSuccess: () => {
                    deployment.enabled = !deployment.enabled;
                },
            }
        );
    };

    // TagList works in numeric ids (it is shared with the REST-backed pages); GraphQL serializes every id as a
    // string, so both directions convert at this boundary rather than loosening TagList's own types.
    const deploymentTags = useMemo(
        () => (deployment.tags ?? []).map((tag) => ({id: Number(tag.id), name: tag.name})),
        [deployment.tags]
    );

    const updateDataSyncDeploymentTagsMutation = useUpdateDataSyncDeploymentTagsMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to update the tags.');
        },
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ['dataSyncDeployments']});
            queryClient.invalidateQueries({queryKey: ['dataSyncDeploymentTags']});
        },
    });

    // The deployment row does not carry cadence parameters (those live on the DataSync itself), so a scheduled
    // deployment is described with the plain word "Scheduled" rather than calling describeTrigger with no
    // parameters, which would misreport a cadence that isn't there.
    const triggerLabel =
        deployment.triggerType === DataSyncTriggerType.Manual
            ? describeTrigger(deployment.triggerType, undefined)
            : 'Scheduled';

    // Serialized as a string over GraphQL (the schema has no date scalar), so it is parsed here rather than
    // trusted to arrive as a Date.
    const lastExecutionDate = deployment.lastExecutionDate ? new Date(deployment.lastExecutionDate) : undefined;

    return (
        <div className="group mb-2 rounded border border-border/50">
            {/* Mirrors AgentDeploymentListItem's rhythm exactly: a min-h-8 title row, an 8px gap, then a
                min-h-7 second row — a data sync deployment has no channels to collapse, so this row is a
                plain card rather than a Collapsible. */}

            <div className="flex w-full items-center justify-between rounded-md px-3">
                <div className="flex flex-1 items-center py-3">
                    <div className="flex-1">
                        <span className="flex min-h-8 items-center text-base font-semibold">
                            {deployment.dataSyncTitle}
                        </span>

                        <div className="mt-2 flex min-h-7 items-center">
                            <span className="mr-4 flex text-xs font-semibold text-muted-foreground">
                                {triggerLabel}
                            </span>

                            {/* The deployment's OWN tags, not the owning data sync's: a data sync deployment is a
                                ProjectDeployment, so these are its project-deployment tags, edited through the
                                data-sync-deployment mutation pair that wraps them. */}

                            <TagList
                                getRequest={(id, updatedTags) => ({
                                    input: {
                                        id: deployment.dataSyncId,
                                        projectDeploymentId: String(id),
                                        tags: updatedTags.map((tag) => ({
                                            id: tag.id == null ? null : String(tag.id),
                                            name: tag.name,
                                        })),
                                    },
                                })}
                                id={+deployment.id}
                                remainingTags={remainingTags?.filter(
                                    (tag) => !deploymentTags.some((deploymentTag) => deploymentTag.id === tag.id)
                                )}
                                tags={deploymentTags}
                                updateTagsMutation={updateDataSyncDeploymentTagsMutation}
                            />
                        </div>
                    </div>

                    <div className="flex items-center justify-end gap-x-6">
                        <Badge label={`V${deployment.projectVersion}`} styleType="secondary-filled" weight="semibold" />

                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Button
                                    aria-label="Run now"
                                    disabled={!deployment.enabled || runDeploymentMutation.isPending}
                                    icon={<PlayIcon className="text-success" />}
                                    onClick={() =>
                                        runDeploymentMutation.mutate({
                                            id: deployment.dataSyncId,
                                            projectDeploymentId: deployment.id,
                                        })
                                    }
                                    size="icon"
                                    variant="ghost"
                                />
                            </TooltipTrigger>

                            <TooltipContent>Run the sync now</TooltipContent>
                        </Tooltip>

                        {/* The min-w column is what puts the toggle above its caption and keeps both right-aligned,
                            matching AgentDeploymentListItem/ProjectDeploymentListItem. */}

                        <div className="flex min-w-52 flex-col items-end gap-y-2">
                            <div className="flex min-h-8 items-center">
                                {enableDataSyncDeploymentMutation.isPending && <LoadingIcon />}

                                <Switch
                                    checked={deployment.enabled}
                                    disabled={isSwitchDisabled}
                                    onCheckedChange={handleOnCheckedChange}
                                />
                            </div>

                            <Tooltip>
                                <TooltipTrigger className="flex min-h-7 items-center text-sm text-content-neutral-secondary">
                                    <span className="text-xs">
                                        {lastExecutionDate
                                            ? `Executed at ${lastExecutionDate.toLocaleDateString()} ${lastExecutionDate.toLocaleTimeString()}`
                                            : 'No executions'}
                                    </span>
                                </TooltipTrigger>

                                <TooltipContent>Last Execution Date</TooltipContent>
                            </Tooltip>
                        </div>

                        {/* The same menu the project deployment rows use, so both lists offer the same actions
                            in the same place. */}

                        <ProjectDeploymentListItemDropdownMenu
                            changeVersionLabel="Change Data Sync Version"
                            onChangeProjectVersionClick={() => setShowEditDialog(true)}
                            onDeleteClick={() => setShowDeleteDialog(true)}
                            onEditClick={() => setShowEditDialog(true)}
                        />
                    </div>
                </div>
            </div>

            {showDeleteDialog && (
                <ProjectDeploymentListItemAlertDialog
                    onCancelClick={() => setShowDeleteDialog(false)}
                    onDeleteClick={() => {
                        deleteDataSyncDeploymentMutation.mutate(+deployment.id);

                        setShowDeleteDialog(false);
                    }}
                />
            )}

            {showEditDialog && projectDeployment && (
                <ProjectDeploymentDialog
                    changeProjectVersion
                    onClose={() => setShowEditDialog(false)}
                    projectDeployment={projectDeployment}
                    redirectOnSubmit={false}
                />
            )}
        </div>
    );
};

export default DataSyncDeploymentListItem;

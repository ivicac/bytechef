import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {findElement} from '@/pages/automation/data-syncs/utils/dataSyncElements';
import {describeTrigger} from '@/pages/automation/data-syncs/utils/dataSyncTrigger';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import ProjectDeploymentDialog from '@/pages/automation/project-deployments/components/project-deployment-dialog/ProjectDeploymentDialog';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import TagList from '@/shared/components/TagList';
import {ProjectDeployment} from '@/shared/middleware/automation/configuration';
import {
    DataSync,
    DataSyncElementKind,
    DataSyncTriggerType,
    useDataSyncTagsQuery,
    useDeleteDataSyncMutation,
    usePublishDataSyncMutation,
    useUpdateDataSyncTagsMutation,
} from '@/shared/middleware/graphql';
import {useGetComponentDefinitionsQuery} from '@/shared/queries/automation/componentDefinitions.queries';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import isInteractiveElementClick from '@/shared/util/interactive-element-utils';
import {useQueryClient} from '@tanstack/react-query';
import {
    ArrowRightIcon,
    CalendarClockIcon,
    EllipsisVerticalIcon,
    HandIcon,
    PencilIcon,
    RocketIcon,
    SendIcon,
    Trash2Icon,
} from 'lucide-react';
import {useMemo, useState} from 'react';
import InlineSVG from 'react-inlinesvg';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface DataSyncListItemProps {
    dataSync: DataSync;
}

const DataSyncListItem = ({dataSync}: DataSyncListItemProps) => {
    const [showDeployDialog, setShowDeployDialog] = useState(false);

    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    const {data: dataSyncTagsData} = useDataSyncTagsQuery(
        {workspaceId: String(currentWorkspaceId)},
        {enabled: currentWorkspaceId != null}
    );

    const {data: componentDefinitions} = useGetComponentDefinitionsQuery({
        actionDefinitions: true,
        clusterElementDefinitions: true,
    });

    const deleteDataSyncMutation = useDeleteDataSyncMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to delete the data sync.');
        },
        onSuccess: () => queryClient.invalidateQueries({queryKey: ['dataSyncs']}),
    });

    const publishDataSyncMutation = usePublishDataSyncMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to publish the data sync.');
        },
        onSuccess: () => {
            invalidateDataSyncQueries(queryClient);

            toast.success('Data sync published.');
        },
    });

    const updateDataSyncTagsMutation = useUpdateDataSyncTagsMutation({
        onError: (error) => {
            toast.error(error instanceof Error ? error.message : 'Failed to update the tags.');
        },
        onSuccess: () => {
            queryClient.invalidateQueries({queryKey: ['dataSyncs']});
            queryClient.invalidateQueries({queryKey: ['dataSyncTags']});
        },
    });

    // TagList works in numeric ids (it is shared with the REST-backed pages); GraphQL serializes every id as a
    // string, so both directions convert at this boundary rather than loosening TagList's own types.
    const dataSyncTags = useMemo(
        () => (dataSync.tags ?? []).map((tag) => ({id: Number(tag.id), name: tag.name})),
        [dataSync.tags]
    );

    const remainingTags = useMemo(() => {
        const attachedTagIds = new Set(dataSyncTags.map((tag) => tag.id));

        return (dataSyncTagsData?.dataSyncTags ?? [])
            .map((tag) => ({id: Number(tag.id), name: tag.name}))
            .filter((tag) => !attachedTagIds.has(tag.id));
    }, [dataSyncTagsData?.dataSyncTags, dataSyncTags]);

    const definitionsByName = useMemo(
        () => new Map((componentDefinitions ?? []).map((definition) => [definition.name, definition])),
        [componentDefinitions]
    );

    const source = findElement(dataSync, DataSyncElementKind.Source);
    const destination = findElement(dataSync, DataSyncElementKind.Destination);

    const sourceDefinition = source ? definitionsByName.get(source.componentName) : undefined;
    const destinationDefinition = destination ? definitionsByName.get(destination.componentName) : undefined;

    const triggerSummary = describeTrigger(
        dataSync.triggerType,
        (dataSync.triggerParameters ?? undefined) as Record<string, unknown> | undefined
    );

    // The published version wins whenever there is one, even with unpublished edits pending: what the pill
    // reports is what is deployed and running. Only a never-published data sync reads as a draft, and it reads
    // V1 rather than V0 because its draft is the version that publishing would mint.
    const isDraft = dataSync.lastPublishedVersion === 0;
    const displayVersion = isDraft ? 1 : dataSync.lastPublishedVersion;

    const deployable = dataSync.lastPublishedVersion > 0;

    const handleDeleteClick = () => {
        deleteDataSyncMutation.mutate({id: dataSync.id});
    };

    // The row opens the data sync unless the click reached a control that means something else. Guarding the
    // controls instead — stopPropagation on the column that holds them — also swallowed every click on the
    // empty space around them, so most of the row's right-hand side did nothing.
    const handleClick = (event: React.MouseEvent) => {
        if (isInteractiveElementClick(event.target)) {
            return;
        }

        navigate(`/automation/data-syncs/${dataSync.id}`);
    };

    return (
        <div
            className="flex cursor-pointer items-center justify-between rounded-md border border-border/50 bg-background p-3"
            onClick={handleClick}
        >
            <div className="flex min-w-0 flex-1 flex-col gap-2">
                <span className="flex min-h-8 items-center gap-1.5 font-semibold">{dataSync.title}</span>

                {dataSync.description && <span className="text-sm text-muted-foreground">{dataSync.description}</span>}

                {/* The source→destination summary and the tags share one row rather than each getting their
                    own: unlike the description this row copies from, the summary is unconditional, so a row
                    each would leave this column three rows tall against the version/date column's two —
                    the version badge, Deploy button, and published date would centre lower than the title on
                    every row. min-h-7 matches that column's own second row so the two stay the same height
                    for a sync with no description.

                    TagList centers itself, so a plain block wrapper would stretch and centre it — the extra
                    flex box keeps it sized to its content and left-aligned. w-fit is load bearing: without it
                    the wrapper stretches across the remaining row width and its stopPropagation swallows
                    every click in the empty space beside the tags. */}

                <div className="flex min-h-7 items-center gap-3">
                    <span className="flex items-center gap-2 text-sm text-muted-foreground">
                        {sourceDefinition?.icon && <InlineSVG className="size-4" src={sourceDefinition.icon} />}

                        <span>{sourceDefinition?.title ?? source?.componentName ?? 'No source'}</span>

                        <ArrowRightIcon className="size-4" />

                        {destinationDefinition?.icon && (
                            <InlineSVG className="size-4" src={destinationDefinition.icon} />
                        )}

                        <span>{destinationDefinition?.title ?? destination?.componentName ?? 'No destination'}</span>
                    </span>

                    <div className="flex w-fit items-center" onClick={(event) => event.stopPropagation()}>
                        <TagList
                            getRequest={(id, tags) => ({
                                input: {
                                    id: String(id),
                                    tags: tags.map((tag) => ({
                                        id: tag.id == null ? null : String(tag.id),
                                        name: tag.name,
                                    })),
                                },
                            })}
                            id={+dataSync.id}
                            remainingTags={remainingTags}
                            tags={dataSyncTags}
                            updateTagsMutation={updateDataSyncTagsMutation}
                        />
                    </div>
                </div>
            </div>

            <div className="flex shrink-0 items-center gap-12">
                {/* Its own column, ahead of the version badge: the cadence belongs with the row's other
                    at-a-glance facts rather than beside the title, where it would move the title around by
                    whatever the sync happens to be scheduled for.

                    The column keeps its width when the sync has no schedule. Rendering nothing collapsed it,
                    and every unscheduled row then pulled the version badge and Deploy button left by the
                    width of whatever its neighbours happened to be scheduled for — so no two rows in a mixed
                    list agreed on where the right-hand columns start. */}

                <span className="flex w-44 items-center justify-end gap-1 text-sm text-muted-foreground">
                    {dataSync.triggerType === DataSyncTriggerType.Schedule ? (
                        <CalendarClockIcon className="size-4 shrink-0" />
                    ) : (
                        <HandIcon className="size-4 shrink-0" />
                    )}

                    <span className="truncate">{triggerSummary}</span>
                </span>

                {/* Both columns keep one rhythm — a 32px first row, an 8px gap, a 28px second row — so the
                    two columns come out the same height and the row's items-center lands the published date
                    level with the tags opposite it. Every other *ListItem carries the same three numbers.

                    Fixed width, because this column's content is not a fixed width: a PUBLISHED badge is
                    wider than a DRAFT one and a published date is wider than "Not yet published", so a
                    content-sized column shoves the schedule beside it left by a different amount on every
                    row. Nothing in a list of rows should move because of what one row happens to say. */}

                <div className="flex w-64 flex-col items-end gap-y-2">
                    <div className="flex min-h-8 items-center gap-2">
                        <Badge
                            className="flex space-x-1 bg-surface-neutral-primary"
                            styleType={isDraft ? 'outline-outline' : 'success-outline'}
                            weight="semibold"
                        >
                            <span>V{displayVersion}</span>

                            <span>{isDraft ? 'DRAFT' : 'PUBLISHED'}</span>
                        </Badge>

                        <Button
                            disabled={!deployable}
                            icon={<RocketIcon />}
                            label="Deploy"
                            onClick={() => setShowDeployDialog(true)}
                            size="sm"
                            variant="outline"
                        />
                    </div>

                    <span className="flex min-h-7 items-center text-xs text-muted-foreground">
                        {dataSync.publishedDate
                            ? `Published at ${new Date(dataSync.publishedDate).toLocaleString()}`
                            : 'Not yet published'}
                    </span>
                </div>

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button icon={<EllipsisVerticalIcon />} size="icon" variant="ghost" />
                    </DropdownMenuTrigger>

                    <DropdownMenuContent align="end">
                        <DropdownMenuItem
                            disabled={publishDataSyncMutation.isPending}
                            onClick={() => publishDataSyncMutation.mutate({id: dataSync.id})}
                        >
                            <SendIcon /> Publish
                        </DropdownMenuItem>

                        <DropdownMenuItem onClick={() => navigate(`/automation/data-syncs/${dataSync.id}`)}>
                            <PencilIcon /> Edit
                        </DropdownMenuItem>

                        <DropdownMenuSeparator />

                        <DropdownMenuItem
                            disabled={deleteDataSyncMutation.isPending}
                            onClick={handleDeleteClick}
                            variant="destructive"
                        >
                            <Trash2Icon /> Delete
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>

            {showDeployDialog && (
                <ProjectDeploymentDialog
                    onClose={() => setShowDeployDialog(false)}
                    projectDeployment={
                        {
                            environmentId: currentEnvironmentId,
                            projectId: +dataSync.projectId,
                            projectVersion: dataSync.lastPublishedVersion,
                        } as ProjectDeployment
                    }
                    redirectOnSubmit={false}
                />
            )}
        </div>
    );
};

export default DataSyncListItem;

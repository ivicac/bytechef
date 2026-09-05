import EmptyList from '@/components/EmptyList';
import useDataSyncs from '@/pages/automation/data-syncs/hooks/useDataSyncs';
import {ProjectDeploymentWorkflow} from '@/shared/middleware/automation/configuration';
import {ArrowLeftRightIcon} from 'lucide-react';
import {useMemo} from 'react';

import ProjectDeploymentDataSyncListItem, {ProjectDeploymentDataSyncType} from './ProjectDeploymentDataSyncListItem';

interface ProjectDeploymentDataSyncListProps {
    /** The data syncs this deployment carries, one entry per sync. */
    dataSyncDeployments: ProjectDeploymentDataSyncType[];
    projectDeploymentWorkflows: ProjectDeploymentWorkflow[];
}

const ProjectDeploymentDataSyncList = ({
    dataSyncDeployments,
    projectDeploymentWorkflows,
}: ProjectDeploymentDataSyncListProps) => {
    const {dataSyncs} = useDataSyncs();

    // The deployment's own dataSyncDeployments row does not carry the sync's project workflow uuid, so it is
    // looked up from the current (draft or published) DataSync -- the same stable identity the deployment's
    // projectDeploymentWorkflow rows carry as `workflowUuid`.
    const projectWorkflowUuidByDataSyncId = useMemo(
        () => new Map(dataSyncs.map((dataSync) => [dataSync.id, dataSync.projectWorkflowUuid])),
        [dataSyncs]
    );

    if (dataSyncDeployments.length === 0) {
        return (
            <div className="flex justify-center py-8">
                <EmptyList
                    icon={<ArrowLeftRightIcon className="size-24 text-stroke-neutral-tertiary" />}
                    message="This deployment has no data syncs."
                    title="No Data Syncs"
                />
            </div>
        );
    }

    return (
        <ul className="divide-y divide-stroke-neutral-primary pt-3">
            {dataSyncDeployments.map((dataSyncDeployment) => {
                const projectWorkflowUuid = projectWorkflowUuidByDataSyncId.get(dataSyncDeployment.dataSyncId);

                return (
                    <ProjectDeploymentDataSyncListItem
                        dataSyncDeployment={dataSyncDeployment}
                        key={dataSyncDeployment.dataSyncId}
                        projectDeploymentWorkflow={projectDeploymentWorkflows.find(
                            (projectDeploymentWorkflow) =>
                                projectDeploymentWorkflow.workflowUuid != null &&
                                projectDeploymentWorkflow.workflowUuid === projectWorkflowUuid
                        )}
                    />
                );
            })}
        </ul>
    );
};

export default ProjectDeploymentDataSyncList;

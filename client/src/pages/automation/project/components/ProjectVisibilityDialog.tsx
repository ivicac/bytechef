import {Dialog, DialogBody, DialogContent, DialogHeader, DialogMain} from '@/components/Dialog';
import ResourceVisibilityPicker, {
    ResourceVisibilityValueType,
} from '@/shared/components/visibility/ResourceVisibilityPicker';
import {useProjectVisibility} from '@/shared/hooks/useProjectVisibility';

interface ProjectVisibilityDialogProps {
    onClose: () => void;
    projectId: number;
    visibility?: ResourceVisibilityValueType;
}

const ProjectVisibilityDialog = ({onClose, projectId, visibility}: ProjectVisibilityDialogProps) => {
    const projectVisibility = useProjectVisibility({projectId, visibility});

    return (
        <Dialog onOpenChange={onClose} open={true}>
            <DialogContent>
                <DialogMain>
                    <DialogHeader
                        description="Decide who in the workspace can see this project, its workflows, deployments and executions."
                        title="Project Visibility"
                    />

                    <DialogBody>
                        {projectVisibility.enabled && (
                            <ResourceVisibilityPicker
                                grantedUserIds={projectVisibility.grantedUserIds}
                                onGrantedUserIdsChange={projectVisibility.onGrantedUserIdsChange}
                                onVisibilityChange={projectVisibility.onVisibilityChange}
                                visibility={visibility || 'WORKSPACE'}
                                workspaceMembers={projectVisibility.workspaceMembers}
                            />
                        )}
                    </DialogBody>
                </DialogMain>
            </DialogContent>
        </Dialog>
    );
};

export default ProjectVisibilityDialog;

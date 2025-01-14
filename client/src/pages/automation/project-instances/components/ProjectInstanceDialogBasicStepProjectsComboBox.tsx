import ComboBox, {ComboBoxItemType} from '@/components/ComboBox/ComboBox';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {ProjectModel, ProjectStatusModel} from '@/shared/middleware/automation/configuration';
import {useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import {FocusEventHandler} from 'react';

const ProjectLabel = ({project}: {project: ProjectModel}) => (
    <div className="flex items-center">
        <span className="mr-1 ">{project.name}</span>

        <span className="text-xs text-gray-500">{project?.tags?.map((tag) => tag.name).join(', ')}</span>
    </div>
);

const ProjectInstanceDialogBasicStepProjectsComboBox = ({
    onBlur,
    onChange,
    value,
}: {
    onBlur: FocusEventHandler;
    onChange: (item?: ComboBoxItemType) => void;
    value?: number;
}) => {
    const {currentWorkspaceId} = useWorkspaceStore();

    const {data: projects} = useGetWorkspaceProjectsQuery({
        id: currentWorkspaceId!,
        status: ProjectStatusModel.Published,
    });

    return projects ? (
        <ComboBox
            items={projects.map(
                (project) =>
                    ({
                        label: <ProjectLabel project={project} />,
                        name: project.name,
                        value: project.id,
                    }) as ComboBoxItemType
            )}
            name="projectId"
            onBlur={onBlur}
            onChange={onChange}
            value={value}
        />
    ) : (
        <>Loading...</>
    );
};

export default ProjectInstanceDialogBasicStepProjectsComboBox;

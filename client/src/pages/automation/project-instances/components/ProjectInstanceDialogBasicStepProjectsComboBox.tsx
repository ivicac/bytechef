import ComboBox, {ComboBoxItemType} from '@/components/ComboBox';
import {ProjectStatusModel} from '@/middleware/automation/configuration';
import ProjectInstanceDialogBasicStepProjectLabel from '@/pages/automation/project-instances/components/ProjectInstanceDialogBasicStepProjectLabel';
import {useGetProjectsQuery} from '@/queries/automation/projects.queries';
import {FocusEventHandler} from 'react';

const ProjectInstanceDialogBasicStepProjectsComboBox = ({
    onBlur,
    onChange,
    value,
}: {
    onBlur: FocusEventHandler;
    onChange: (item?: ComboBoxItemType) => void;
    value?: number;
}) => {
    const {data: projects} = useGetProjectsQuery({status: ProjectStatusModel.Published});

    return projects ? (
        <ComboBox
            items={projects.map(
                (project) =>
                    ({
                        label: <ProjectInstanceDialogBasicStepProjectLabel project={project} />,
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

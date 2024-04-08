import {UpdateWorkflowRequest, WorkflowModel} from '@/middleware/automation/configuration';
import {ComponentDataType} from '@/types/types';
import {UseMutationResult} from '@tanstack/react-query';

import {WorkflowTaskDataType} from '../stores/useWorkflowDataStore';
import saveWorkflowDefinition from './saveWorkflowDefinition';

export default function saveProperty(
    parameters: object,
    setComponentData: (componentData: ComponentDataType[]) => void,
    currentComponentData: ComponentDataType,
    otherComponentData: Array<ComponentDataType>,
    updateWorkflowMutation: UseMutationResult<WorkflowModel, Error, UpdateWorkflowRequest, unknown>,
    name: string,
    workflow: WorkflowModel & WorkflowTaskDataType
) {
    if (!currentComponentData || !updateWorkflowMutation || !name) {
        return;
    }

    const {actionName, componentName, workflowNodeName} = currentComponentData;

    setComponentData([
        ...otherComponentData,
        {
            ...currentComponentData,
            parameters,
        },
    ]);

    saveWorkflowDefinition(
        {
            actionName,
            componentName,
            name: workflowNodeName,
            parameters,
        },
        workflow,
        updateWorkflowMutation
    );
}
import {ComponentDefinition} from '@/shared/middleware/platform/configuration';
import {ClusterElementsType, NodeDataType, PropertyAllType, UpdateWorkflowMutationType} from '@/shared/types';

import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import useWorkflowNodeDetailsPanelStore from '../stores/useWorkflowNodeDetailsPanelStore';
import {updateClusterRootElementField, updateNestedClusterElementField} from './clusterElementsFieldChangeUtils';
import getParametersWithDefaultValues from './getParametersWithDefaultValues';
import {getTask} from './getTask';
import {resolveMainClusterRootName} from './resolveClusterRootId';
import saveWorkflowDefinition from './saveWorkflowDefinition';

type FieldUpdateType = {
    field: 'operation' | 'label' | 'description';
    value: string;
};

interface SaveClusterElementFieldChangeProps {
    currentComponentDefinition: ComponentDefinition;
    currentOperationProperties?: Array<PropertyAllType>;
    fieldUpdate: FieldUpdateType;
    updateWorkflowMutation: UpdateWorkflowMutationType;
}

export default function saveClusterElementFieldChange({
    currentComponentDefinition,
    currentOperationProperties,
    fieldUpdate,
    updateWorkflowMutation,
}: SaveClusterElementFieldChangeProps): void {
    const {currentNode, setCurrentNode} = useWorkflowNodeDetailsPanelStore.getState();
    const {rootClusterElementNodeData, setRootClusterElementNodeData} = useWorkflowEditorStore.getState();
    const {workflow} = useWorkflowDataStore.getState();

    if (!currentNode || !workflow.definition) {
        return;
    }

    const {componentName, name, workflowNodeName} = currentNode;

    // The root comes from the node first: on the main canvas rootClusterElementNodeData is seeded
    // only by the box header's destinations and never cleared, so it is empty or names another box.
    const mainClusterRootName = resolveMainClusterRootName(currentNode, rootClusterElementNodeData);

    if (!mainClusterRootName) {
        console.error('Cluster root could not be resolved for the current node');

        return;
    }

    const workflowDefinitionTasks = JSON.parse(workflow.definition).tasks;

    const mainClusterRootTask = getTask({
        tasks: workflowDefinitionTasks,
        workflowNodeName: mainClusterRootName,
    });

    if (!mainClusterRootTask) {
        return;
    }

    // The root's component name is the first segment of its task type; the store carries the same
    // value for the dialog and is kept only as the fallback.
    const mainClusterRootComponentName =
        mainClusterRootTask.type?.split('/')[0] || rootClusterElementNodeData?.componentName;

    if (!mainClusterRootComponentName) {
        return;
    }

    let updatedMainRootData: NodeDataType;
    let updatedClusterElements: ClusterElementsType;

    if (
        currentNode.clusterRoot &&
        currentNode.workflowNodeName === mainClusterRootName &&
        !currentNode.isNestedClusterRoot
    ) {
        updatedMainRootData = updateClusterRootElementField({
            currentComponentDefinition,
            currentOperationProperties,
            fieldUpdate,
            mainRootElement: {
                ...mainClusterRootTask,
                componentName: mainClusterRootComponentName,
                workflowNodeName: mainClusterRootName,
            },
        });
    } else if (currentNode.clusterElementType && currentNode.workflowNodeName !== mainClusterRootName) {
        const clusterElements = mainClusterRootTask.clusterElements;

        if (!clusterElements || Object.keys(clusterElements).length === 0) {
            return;
        }

        updatedClusterElements = updateNestedClusterElementField({
            clusterElements,
            currentComponentDefinition,
            currentOperationProperties,
            elementName: workflowNodeName,
            fieldUpdate,
        });

        updatedMainRootData = {
            ...mainClusterRootTask,
            clusterElements: updatedClusterElements,
            componentName: mainClusterRootComponentName,
            workflowNodeName: mainClusterRootName,
        };
    } else {
        console.error('Unknown cluster element type or root element mismatch');

        return;
    }

    saveWorkflowDefinition({
        decorative: true,
        nodeData: updatedMainRootData,
        onSuccess: () => {
            let commonUpdates: NodeDataType = {
                componentName,
                name,
                workflowNodeName,
            };

            if (fieldUpdate.field === 'operation') {
                commonUpdates = {
                    ...commonUpdates,
                    clusterElementName: fieldUpdate.value,
                    metadata: {
                        ui: {
                            nodePosition: currentNode.metadata?.ui?.nodePosition
                                ? currentNode.metadata?.ui?.nodePosition
                                : undefined,
                        },
                    },
                    operationName: fieldUpdate.value,
                    parameters: getParametersWithDefaultValues({
                        properties: currentOperationProperties as Array<PropertyAllType>,
                    }),
                    type: `${currentComponentDefinition.name}/v${currentComponentDefinition.version}/${fieldUpdate.value}`,
                };
            } else {
                commonUpdates[fieldUpdate.field] = fieldUpdate.value;
            }

            setCurrentNode({
                ...currentNode,
                ...commonUpdates,
            });

            if (rootClusterElementNodeData) {
                if (currentNode.clusterRoot && !currentNode.isNestedClusterRoot) {
                    setRootClusterElementNodeData({
                        ...rootClusterElementNodeData,
                        ...commonUpdates,
                    });
                } else {
                    setRootClusterElementNodeData({
                        ...rootClusterElementNodeData,
                        clusterElements: updatedClusterElements,
                    });
                }
            }

            useWorkflowNodeDetailsPanelStore.getState().setOperationChangeInProgress(false);
        },
        updateWorkflowMutation,
    });
}

import {getClusterElementByName} from '@/pages/platform/cluster-element-editor/utils/clusterElementsUtils';
import {useWorkflowEditor} from '@/pages/platform/workflow-editor/providers/workflowEditorProvider';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import useWorkflowNodeDetailsPanelStore from '@/pages/platform/workflow-editor/stores/useWorkflowNodeDetailsPanelStore';
import {getTask} from '@/pages/platform/workflow-editor/utils/getTask';
import handleDeleteTask from '@/pages/platform/workflow-editor/utils/handleDeleteTask';
import {useUpdateClusterElementParameterMutation} from '@/shared/mutations/platform/workflowNodeParameters.mutations';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {ClusterElementItemType, NodeDataType} from '@/shared/types';
import {useQueryClient} from '@tanstack/react-query';
import {useCallback} from 'react';
import {useShallow} from 'zustand/shallow';

import {ToolItemI} from './useAiAgentTools';

interface UseAiAgentToolDropdownMenuI {
    handleConfigureTool: (tool: ToolItemI) => Promise<void>;
    handleRemoveTool: (tool: ToolItemI) => void;
    handleSetApprovalExpiry: (tool: ToolItemI, expiresIn: number, expiresInUnit: string) => void;
    handleToggleRequiresApproval: (tool: ToolItemI) => void;
}

export default function useAiAgentToolDropdownMenu(): UseAiAgentToolDropdownMenuI {
    const rootClusterElementNodeData = useWorkflowEditorStore((state) => state.rootClusterElementNodeData);
    const setRootClusterElementNodeData = useWorkflowEditorStore((state) => state.setRootClusterElementNodeData);
    const workflow = useWorkflowDataStore((state) => state.workflow);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const updateClusterElementParameterMutation = useUpdateClusterElementParameterMutation();

    const {setActiveTab, setAiAgentNodeDetailsPanelOpen, setCurrentNode} = useWorkflowNodeDetailsPanelStore(
        useShallow((state) => ({
            setActiveTab: state.setActiveTab,
            setAiAgentNodeDetailsPanelOpen: state.setAiAgentNodeDetailsPanelOpen,
            setCurrentNode: state.setCurrentNode,
        }))
    );

    const {cancelWorkflowQueries, invalidateWorkflowQueries, updateWorkflowMutation} = useWorkflowEditor();
    const queryClient = useQueryClient();

    const handleConfigureTool = useCallback(
        async (tool: ToolItemI) => {
            let toolMetadata: NodeDataType['metadata'] | undefined;
            let toolParameters: NodeDataType['parameters'] | undefined;

            if (rootClusterElementNodeData?.workflowNodeName && workflow.definition) {
                const workflowDefinitionTasks = JSON.parse(workflow.definition).tasks;

                const mainClusterRootTask = getTask({
                    tasks: workflowDefinitionTasks,
                    workflowNodeName: rootClusterElementNodeData.workflowNodeName,
                });

                if (mainClusterRootTask?.clusterElements) {
                    const clusterElement = getClusterElementByName(mainClusterRootTask.clusterElements, tool.name);

                    if (clusterElement) {
                        toolMetadata = clusterElement.metadata;
                        toolParameters = clusterElement.parameters;
                    }
                }
            }

            const toolNodeData: NodeDataType = {
                clusterElementName: tool.operationName,
                clusterElementType: 'tools',
                componentName: tool.componentName,
                label: tool.label,
                metadata: toolMetadata,
                name: tool.name,
                operationName: tool.operationName,
                parameters: toolParameters,
                parentClusterRootId: rootClusterElementNodeData?.name,
                type: tool.type,
                version: tool.componentVersion,
                workflowNodeName: tool.name,
            };

            setActiveTab('description');
            setCurrentNode((previousCurrentNode) => ({
                ...toolNodeData,
                description: '',
                displayConditions: previousCurrentNode?.displayConditions,
            }));
            setAiAgentNodeDetailsPanelOpen(true);
        },
        [
            rootClusterElementNodeData?.name,
            rootClusterElementNodeData?.workflowNodeName,
            setActiveTab,
            setAiAgentNodeDetailsPanelOpen,
            setCurrentNode,
            workflow.definition,
        ]
    );

    const handleRemoveTool = useCallback(
        (tool: ToolItemI) => {
            if (!rootClusterElementNodeData) {
                return;
            }

            const toolNodeData: NodeDataType = {
                clusterElementType: 'tools',
                componentName: tool.componentName,
                name: tool.name,
                workflowNodeName: tool.name,
            };

            // Close the simple-mode node details panel if it is currently showing
            // the tool being removed — otherwise it keeps rendering stale data
            // for a tool that no longer exists.
            const {currentNode: activeNode} = useWorkflowNodeDetailsPanelStore.getState();

            if (activeNode?.workflowNodeName === tool.name) {
                setAiAgentNodeDetailsPanelOpen(false);
                setCurrentNode(undefined);
            }

            handleDeleteTask({
                cancelWorkflowQueries: cancelWorkflowQueries!,
                clusterElementsCanvasOpen: true,
                data: toolNodeData,
                invalidateWorkflowQueries: invalidateWorkflowQueries!,
                queryClient,
                rootClusterElementNodeData,
                setCurrentNode,
                setRootClusterElementNodeData,
                updateWorkflowMutation,
                workflow,
            });
        },
        [
            cancelWorkflowQueries,
            invalidateWorkflowQueries,
            queryClient,
            rootClusterElementNodeData,
            setAiAgentNodeDetailsPanelOpen,
            setCurrentNode,
            setRootClusterElementNodeData,
            updateWorkflowMutation,
            workflow,
        ]
    );

    const handleToggleRequiresApproval = useCallback(
        (tool: ToolItemI) => {
            if (!workflow.id || !rootClusterElementNodeData?.workflowNodeName) {
                return;
            }

            updateClusterElementParameterMutation.mutate(
                {
                    clusterElementType: 'tools',
                    clusterElementWorkflowNodeName: tool.name,
                    environmentId: currentEnvironmentId,
                    id: workflow.id,
                    updateClusterElementParameterRequest: {
                        includeInMetadata: false,
                        path: 'requiresApproval',
                        type: 'BOOLEAN',
                        // The generated request model types `value` as object, but the endpoint accepts any JSON
                        // scalar — booleans included — the same way saveProperty submits primitive values.
                        value: !tool.requiresApproval as unknown as object,
                    },
                    workflowNodeName: rootClusterElementNodeData.workflowNodeName,
                },
                {
                    onSuccess: (response) => {
                        // Mirror the returned parameters into the editor store copy so the tools list (badge +
                        // checkbox) re-renders without a full workflow refetch.
                        const clusterElements = rootClusterElementNodeData.clusterElements;

                        if (clusterElements && !Array.isArray(clusterElements)) {
                            const toolElements = clusterElements['tools'];

                            if (Array.isArray(toolElements)) {
                                const updatedToolElements = toolElements.map((toolElement) => {
                                    const element = toolElement as ClusterElementItemType &
                                        NodeDataType & {name?: string};
                                    const elementName = element.workflowNodeName || element.name;

                                    return elementName === tool.name
                                        ? {...element, parameters: response.parameters}
                                        : toolElement;
                                });

                                setRootClusterElementNodeData({
                                    ...rootClusterElementNodeData,
                                    clusterElements: {...clusterElements, tools: updatedToolElements},
                                });
                            }
                        }
                    },
                }
            );
        },
        [
            currentEnvironmentId,
            rootClusterElementNodeData,
            setRootClusterElementNodeData,
            updateClusterElementParameterMutation,
            workflow.id,
        ]
    );

    const handleSetApprovalExpiry = useCallback(
        (tool: ToolItemI, expiresIn: number, expiresInUnit: string) => {
            if (!workflow.id || !rootClusterElementNodeData?.workflowNodeName) {
                return;
            }

            const workflowNodeName = rootClusterElementNodeData.workflowNodeName;

            // Two parameters describe the expiry (value + unit); write them sequentially and mirror the editor
            // store copy from the second response so the submenu check mark re-renders without a workflow refetch.
            updateClusterElementParameterMutation.mutate(
                {
                    clusterElementType: 'tools',
                    clusterElementWorkflowNodeName: tool.name,
                    environmentId: currentEnvironmentId,
                    id: workflow.id,
                    updateClusterElementParameterRequest: {
                        includeInMetadata: false,
                        path: 'approvalExpiresIn',
                        type: 'INTEGER',
                        // The generated request model types `value` as object, but the endpoint accepts any JSON
                        // scalar — numbers included — the same way saveProperty submits primitive values.
                        value: expiresIn as unknown as object,
                    },
                    workflowNodeName,
                },
                {
                    onSuccess: () => {
                        if (!workflow.id) {
                            return;
                        }

                        updateClusterElementParameterMutation.mutate(
                            {
                                clusterElementType: 'tools',
                                clusterElementWorkflowNodeName: tool.name,
                                environmentId: currentEnvironmentId,
                                id: workflow.id,
                                updateClusterElementParameterRequest: {
                                    includeInMetadata: false,
                                    path: 'approvalExpiresInUnit',
                                    type: 'STRING',
                                    value: expiresInUnit as unknown as object,
                                },
                                workflowNodeName,
                            },
                            {
                                onSuccess: (response) => {
                                    const clusterElements = rootClusterElementNodeData.clusterElements;

                                    if (clusterElements && !Array.isArray(clusterElements)) {
                                        const toolElements = clusterElements['tools'];

                                        if (Array.isArray(toolElements)) {
                                            const updatedToolElements = toolElements.map((toolElement) => {
                                                const element = toolElement as ClusterElementItemType &
                                                    NodeDataType & {name?: string};
                                                const elementName = element.workflowNodeName || element.name;

                                                return elementName === tool.name
                                                    ? {...element, parameters: response.parameters}
                                                    : toolElement;
                                            });

                                            setRootClusterElementNodeData({
                                                ...rootClusterElementNodeData,
                                                clusterElements: {...clusterElements, tools: updatedToolElements},
                                            });
                                        }
                                    }
                                },
                            }
                        );
                    },
                }
            );
        },
        [
            currentEnvironmentId,
            rootClusterElementNodeData,
            setRootClusterElementNodeData,
            updateClusterElementParameterMutation,
            workflow.id,
        ]
    );

    return {
        handleConfigureTool,
        handleRemoveTool,
        handleSetApprovalExpiry,
        handleToggleRequiresApproval,
    };
}

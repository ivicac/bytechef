import {getNestedClusterElements} from '@/pages/platform/cluster-element-editor/utils/clusterElementsUtils';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {useGetWorkflowTestConfigurationConnectionsQuery} from '@/shared/queries/platform/workflowTestConfigurations.queries';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {NodeDataType} from '@/shared/types';
import {useMemo} from 'react';

const APPROVAL_GATE = 'approvalGate';

export interface ToolItemI {
    approvalExpiresIn?: number;
    approvalExpiresInUnit?: string;
    componentName: string;
    componentVersion: number;
    icon?: string;
    label: string;
    name: string;
    operationName: string;
    requiresApproval: boolean;
    title: string;
    type: string;
}

export interface ToolGroupI {
    channels: ToolItemI[];
    label: string;
    name: string;
    tool: ToolItemI;
    tools: ToolItemI[];
}

interface UseAiAgentToolsI {
    configuredConnectionKeys: Set<string>;
    rootWorkflowNodeName?: string;
    toolGroups: ToolGroupI[];
    tools: ToolItemI[];
}

type ToolElementType = NodeDataType & {
    clusterElements?: Record<string, unknown>;
    name?: string;
};

type ComponentDefinitionMapType = Map<string, {icon?: string; title?: string}>;

function mapToolElement(
    toolElement: ToolElementType,
    definitionsMap: ComponentDefinitionMapType,
    clusterElementType = 'tools'
): ToolItemI {
    const typeSegments = toolElement.type?.split('/') || [];
    const componentName = toolElement.componentName || typeSegments[0] || '';
    const componentVersion = parseInt(typeSegments[1]?.replace(/^v/, '')) || 1;
    const operationName = toolElement.operationName || typeSegments[2] || '';
    const componentDefinition = definitionsMap.get(componentName);
    const toolName = toolElement.workflowNodeName || toolElement.name || '';

    return {
        clusterElementType,
        componentName,
        componentVersion,
        icon: componentDefinition?.icon,
        label: toolElement.label || toolName,
        name: toolName,
        operationName,
        title: componentDefinition?.title || componentName,
        type: toolElement.type || '',
    };
}

export default function useAiAgentTools(): UseAiAgentToolsI {
    const rootClusterElementNodeData = useWorkflowEditorStore((state) => state.rootClusterElementNodeData);
    const componentDefinitions = useWorkflowDataStore((state) => state.componentDefinitions);
    const workflow = useWorkflowDataStore((state) => state.workflow);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    const {toolGroups, tools} = useMemo<{toolGroups: ToolGroupI[]; tools: ToolItemI[]}>(() => {
        const clusterElements = rootClusterElementNodeData?.clusterElements;

        if (!clusterElements || Array.isArray(clusterElements)) {
            return {toolGroups: [], tools: []};
        }

        const toolElements = clusterElements['tools'];

        if (!Array.isArray(toolElements)) {
            return {toolGroups: [], tools: []};
        }

        const definitionsMap = new Map(componentDefinitions.map((definition) => [definition.name, definition]));

        return toolElements.map((toolElement) => {
            // Tool elements can appear in either shape: NodeDataType (workflowNodeName)
            // when synced in-memory, or ClusterElementItemType (name) when loaded from
            // the workflow definition. Fall through both so the first load after an
            // Add also produces a non-empty identifier.
            const tool = toolElement as unknown as NodeDataType & {name?: string};
            const typeSegments = tool.type?.split('/') || [];
            const componentName = tool.componentName || typeSegments[0] || '';
            const componentVersion = parseInt(typeSegments[1]?.replace(/^v/, '')) || 1;
            const operationName = tool.operationName || typeSegments[2] || '';
            const componentDefinition = definitionsMap.get(componentName);
            const toolName = tool.workflowNodeName || tool.name || '';

            const toolParameters = tool.parameters as
                | {approvalExpiresIn?: number; approvalExpiresInUnit?: string; requiresApproval?: boolean}
                | undefined;

            return {
                approvalExpiresIn:
                    typeof toolParameters?.approvalExpiresIn === 'number'
                        ? toolParameters.approvalExpiresIn
                        : undefined,
                approvalExpiresInUnit: toolParameters?.approvalExpiresInUnit,
                componentName,
                componentVersion,
                icon: componentDefinition?.icon,
                label: tool.label || toolName,
                name: toolName,
                operationName,
                requiresApproval: toolParameters?.requiresApproval === true,
                title: componentDefinition?.title || componentName,
                type: tool.type || '',
            };
        });

        return {toolGroups: groupedTools, tools: ungatedTools};
    }, [rootClusterElementNodeData?.clusterElements, componentDefinitions]);

    const {data: testConnections} = useGetWorkflowTestConfigurationConnectionsQuery(
        {
            environmentId: currentEnvironmentId,
            workflowId: workflow.id!,
            workflowNodeName: rootClusterElementNodeData?.workflowNodeName || '',
        },
        !!workflow.id && !!rootClusterElementNodeData?.workflowNodeName
    );

    const configuredConnectionKeys = useMemo(
        () =>
            new Set(
                testConnections
                    ?.map((connection) => connection.workflowConnectionKey)
                    .filter((key): key is string => key !== undefined)
            ),
        [testConnections]
    );

    return {
        configuredConnectionKeys,
        rootWorkflowNodeName: rootClusterElementNodeData?.workflowNodeName,
        toolGroups,
        tools,
    };
}

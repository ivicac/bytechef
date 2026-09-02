import useDataPillPanelStore from '@/pages/platform/workflow-editor/stores/useDataPillPanelStore';
import useRightSidebarStore from '@/pages/platform/workflow-editor/stores/useRightSidebarStore';
import useWorkflowIssuesStore from '@/pages/platform/workflow-editor/stores/useWorkflowIssuesStore';
import useWorkflowTestChatStore from '@/pages/platform/workflow-editor/stores/useWorkflowTestChatStore';
import {NodeDataType, TabNameType} from '@/shared/types';

import useClusterElementsViewModeStore from '../stores/useClusterElementsViewModeStore';
import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../stores/useWorkflowEditorStore';
import useWorkflowNodeDetailsPanelStore from '../stores/useWorkflowNodeDetailsPanelStore';
import {getNodeLabel} from './getNodeLabel';

export default function openNodeDetails(data: NodeDataType, activeTab: TabNameType = 'description'): void {
    const {
        currentNode: existingCurrentNode,
        setActiveTab,
        setCurrentNode,
        setWorkflowNodeDetailsPanelOpen,
        workflowNodeDetailsPanelOpen: isPanelOpen,
    } = useWorkflowNodeDetailsPanelStore.getState();
    const {clusterElementsCanvasOpen, setClusterElementsCanvasOpen} = useWorkflowEditorStore.getState();

    const isNodeAlreadyOpen = isPanelOpen && existingCurrentNode?.workflowNodeName === data.workflowNodeName;

    const {issuesSidebarOpen, setIssuesSidebarOpen} = useWorkflowIssuesStore.getState();

    useWorkflowNodeDetailsPanelStore.getState().setPanelOpenedFromIssuesSidebar(issuesSidebarOpen && !isPanelOpen);

    useRightSidebarStore.getState().setRightSidebarOpen(false);
    useWorkflowTestChatStore.getState().setWorkflowTestChatPanelOpen(false);
    setIssuesSidebarOpen(false);
    setActiveTab(activeTab);

    if (!isNodeAlreadyOpen) {
        useDataPillPanelStore.getState().setDataPillPanelOpen(false);

        const {workflow} = useWorkflowDataStore.getState();

        setCurrentNode((previousCurrentNode) => ({
            ...data,
            description: '',
            displayConditions: previousCurrentNode?.displayConditions,
            label: getNodeLabel({fallbackLabel: data.label, workflow, workflowNodeName: data.workflowNodeName}),
        }));

        // In box mode the elements are already on the canvas, so a click means "show me this
        // node's details", not "take me to another editor".
        if (
            !!data.clusterRoot &&
            !clusterElementsCanvasOpen &&
            useClusterElementsViewModeStore.getState().clusterElementsViewMode === 'dialog'
        ) {
            setClusterElementsCanvasOpen(true);
        }
    }

    setWorkflowNodeDetailsPanelOpen(true);
}

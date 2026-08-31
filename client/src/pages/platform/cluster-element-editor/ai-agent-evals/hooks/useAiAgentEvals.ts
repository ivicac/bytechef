import {useAiAgentEvalsStore} from '@/pages/platform/cluster-element-editor/ai-agent-evals/stores/useAiAgentEvalsStore';
import useClusterElementsViewModeStore from '@/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {useCallback} from 'react';

export default function useAiAgentEvals() {
    const {evalsTab, setEvalsPanelOpen, setEvalsTab} = useAiAgentEvalsStore();
    const clusterElementsViewMode = useClusterElementsViewModeStore((state) => state.clusterElementsViewMode);
    const setClusterElementsCanvasOpen = useWorkflowEditorStore((state) => state.setClusterElementsCanvasOpen);

    // In box mode there is no dialog canvas view underneath the evals overlay to fall back to —
    // closing evals closes the whole destination and returns to the box on the main canvas.
    const handleClose = useCallback(() => {
        setEvalsPanelOpen(false);

        if (clusterElementsViewMode === 'box') {
            setClusterElementsCanvasOpen(false);
        }
    }, [clusterElementsViewMode, setClusterElementsCanvasOpen, setEvalsPanelOpen]);

    return {evalsTab, handleClose, setEvalsTab};
}

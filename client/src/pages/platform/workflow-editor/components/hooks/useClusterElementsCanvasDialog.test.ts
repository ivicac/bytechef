import {useClusterElementsCanvasDialogStore} from '@/pages/platform/workflow-editor/components/stores/useClusterElementsCanvasDialogStore';
import useClusterElementsViewModeStore from '@/pages/platform/workflow-editor/stores/useClusterElementsViewModeStore';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {applicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {NodeDataType} from '@/shared/types';
import {renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it} from 'vitest';

import useClusterElementsCanvasDialog from './useClusterElementsCanvasDialog';

// The view-mode toggle is behind ff-5470, and `useClusterElementsViewMode` reports 'dialog'
// regardless of the stored mode while the flag is off -- that is the kill switch. Box-mode tests
// therefore have to turn the flag on as well as set the store.
function enableClusterElementsBoxModeFlag() {
    applicationInfoStore.setState((state) => ({
        featureFlags: {...state.featureFlags, 'ff-5470': true},
    }));
}

// Regression coverage for the second half of the "editor buttons" bug: useClusterElementsCanvasDialog
// carries two effect pairs that restore showAiAgentEditor/showDataStreamEditor from the persisted
// editorPreferences map whenever the root changes. That restoration is correct for DIALOG mode's own
// click-to-open flow (which never decides the editor explicitly, so the effect is the only thing that
// does). But box mode's header buttons (ClusterFrameShell's handleOpenAiAgentEditor etc.) DO decide
// explicitly on every click -- if these effects still ran in box mode, any node with a stored `false`
// preference from an earlier dialog-mode session would have its "Switch to AI Agent editor" click
// silently reverted back to the canvas view the moment the dialog mounted. The fix gates all four
// restore effects on clusterElementsViewMode === 'dialog'.

const AI_AGENT_ROOT_DATA = {
    componentName: 'aiAgent',
    name: 'aiAgent_1',
    type: 'aiAgent/v1/chat',
    workflowNodeName: 'aiAgent_1',
} as unknown as NodeDataType;

describe('useClusterElementsCanvasDialog - editor preference restoration', () => {
    beforeEach(() => {
        useWorkflowDataStore.setState({workflow: {definition: undefined, id: 'workflow-1', nodeNames: [], version: 1}});
        useWorkflowEditorStore.setState({rootClusterElementNodeData: AI_AGENT_ROOT_DATA});
        useClusterElementsCanvasDialogStore.setState({
            editorPreferences: {'workflow-1:aiAgent_1': false},
            showAiAgentEditor: true,
        });
    });

    it('does not revert an explicitly-set showAiAgentEditor back to the stored preference in box mode', () => {
        enableClusterElementsBoxModeFlag();
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'box'});

        renderHook(() => useClusterElementsCanvasDialog({onOpenChange: () => {}}));

        expect(useClusterElementsCanvasDialogStore.getState().showAiAgentEditor).toBe(true);
    });

    it('does restore showAiAgentEditor from the stored preference in dialog mode', () => {
        useClusterElementsViewModeStore.setState({clusterElementsViewMode: 'dialog'});

        renderHook(() => useClusterElementsCanvasDialog({onOpenChange: () => {}}));

        expect(useClusterElementsCanvasDialogStore.getState().showAiAgentEditor).toBe(false);
    });
});

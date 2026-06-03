import {MODE, Source} from '@/shared/components/copilot/stores/useCopilotStore';

/**
 * Whether the CopilotPanel "Apply to editor" action should be available.
 *
 * Only BUILD-mode results in the Workflow Code editor are appliable — ASK-mode replies are prose,
 * not a workflow definition, so applying them into the editor would corrupt the buffer.
 *
 * @param source the copilot source for the current panel
 * @param mode the live ASK/BUILD selection from the conversation context
 * @param hasApplyTarget true when an apply handler and an assistant message both exist
 */
export function canApplyToEditor(source: Source | undefined, mode: MODE | undefined, hasApplyTarget: boolean): boolean {
    return source === Source.WORKFLOW_CODE_EDITOR && mode === MODE.BUILD && hasApplyTarget;
}

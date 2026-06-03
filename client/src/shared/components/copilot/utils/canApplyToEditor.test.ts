import {MODE, Source} from '@/shared/components/copilot/stores/useCopilotStore';
import {describe, expect, it} from 'vitest';

import {canApplyToEditor} from './canApplyToEditor';

describe('canApplyToEditor', () => {
    it('allows applying a BUILD-mode result in the workflow code editor', () => {
        expect(canApplyToEditor(Source.WORKFLOW_CODE_EDITOR, MODE.BUILD, true)).toBe(true);
    });

    it('blocks ASK-mode results (the bug: ASK prose must not be appliable)', () => {
        expect(canApplyToEditor(Source.WORKFLOW_CODE_EDITOR, MODE.ASK, true)).toBe(false);
    });

    it('blocks other sources even in BUILD mode', () => {
        expect(canApplyToEditor(Source.CODE_EDITOR, MODE.BUILD, true)).toBe(false);
    });

    it('blocks when there is no apply target (no handler or no assistant message)', () => {
        expect(canApplyToEditor(Source.WORKFLOW_CODE_EDITOR, MODE.BUILD, false)).toBe(false);
    });

    it('blocks when mode is undefined', () => {
        expect(canApplyToEditor(Source.WORKFLOW_CODE_EDITOR, undefined, true)).toBe(false);
    });
});

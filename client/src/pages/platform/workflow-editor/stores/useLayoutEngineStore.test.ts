import {beforeEach, describe, expect, it, vi} from 'vitest';

describe('useLayoutEngineStore persistence', () => {
    beforeEach(() => {
        vi.resetModules();

        localStorage.clear();
    });

    it('defaults to dagre with no persisted state', async () => {
        const {default: useLayoutEngineStore} = await import('./useLayoutEngineStore');

        expect(useLayoutEngineStore.getState().layoutEngine).toBe('dagre');
    });

    it('hydrates a persisted elk selection on load', async () => {
        localStorage.setItem('bytechef.layout-engine', JSON.stringify({state: {layoutEngine: 'elk'}, version: 0}));

        const {default: useLayoutEngineStore} = await import('./useLayoutEngineStore');

        expect(useLayoutEngineStore.getState().layoutEngine).toBe('elk');
    });

    it('writes the selection back to localStorage on change', async () => {
        const {default: useLayoutEngineStore} = await import('./useLayoutEngineStore');

        useLayoutEngineStore.getState().setLayoutEngine('elk');

        const persisted = JSON.parse(localStorage.getItem('bytechef.layout-engine') || '{}');

        expect(persisted.state?.layoutEngine).toBe('elk');
    });
});

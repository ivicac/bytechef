import {renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

// vi.hoisted ensures this ref exists before the vi.mock factory evaluates — Vitest hoists mocks
// above imports, so a factory closing over a plain module-scope const would crash with
// "Cannot access X before initialization".
const {visibilityEditionEnabledRef} = vi.hoisted(() => ({
    visibilityEditionEnabledRef: {current: false},
}));

vi.mock('@/shared/hooks/useVisibilityFeatureEnabled', () => ({
    useIsVisibilityEditionEnabled: () => visibilityEditionEnabledRef.current,
}));

import {useAiHubSharingEnabled} from '../useAiHubSharingEnabled';

describe('useAiHubSharingEnabled', () => {
    beforeEach(() => {
        visibilityEditionEnabledRef.current = false;
    });

    it('returns false for a CE caller', () => {
        const {result} = renderHook(() => useAiHubSharingEnabled());

        expect(result.current).toBe(false);
    });

    it('returns true when the EE visibility edition is on', () => {
        visibilityEditionEnabledRef.current = true;

        const {result} = renderHook(() => useAiHubSharingEnabled());

        expect(result.current).toBe(true);
    });
});

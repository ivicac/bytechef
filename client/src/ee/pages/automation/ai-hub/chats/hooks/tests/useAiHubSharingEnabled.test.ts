import {renderHook} from '@testing-library/react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

// vi.hoisted ensures these refs exist before the vi.mock factories evaluate — Vitest hoists mocks
// above imports, so a factory closing over a plain module-scope const would crash with
// "Cannot access X before initialization".
const {featureFlagEnabledRef, visibilityEditionEnabledRef} = vi.hoisted(() => ({
    featureFlagEnabledRef: {current: false},
    visibilityEditionEnabledRef: {current: false},
}));

vi.mock('@/shared/hooks/useVisibilityFeatureEnabled', () => ({
    useIsVisibilityEditionEnabled: () => visibilityEditionEnabledRef.current,
}));

vi.mock('@/shared/stores/useFeatureFlagsStore', () => ({
    useFeatureFlagsStore: () => (featureFlag: string) =>
        featureFlag === 'ff-ai-hub-shared-chats' && featureFlagEnabledRef.current,
}));

import {useAiHubSharingEnabled} from '../useAiHubSharingEnabled';

describe('useAiHubSharingEnabled', () => {
    beforeEach(() => {
        featureFlagEnabledRef.current = false;
        visibilityEditionEnabledRef.current = false;
    });

    it('returns false when the EE visibility edition is off and the flag is off', () => {
        const {result} = renderHook(() => useAiHubSharingEnabled());

        expect(result.current).toBe(false);
    });

    it('returns false when the EE visibility edition is on but the flag is off', () => {
        visibilityEditionEnabledRef.current = true;

        const {result} = renderHook(() => useAiHubSharingEnabled());

        expect(result.current).toBe(false);
    });

    it('returns false when the flag is on but the EE visibility edition is off', () => {
        featureFlagEnabledRef.current = true;

        const {result} = renderHook(() => useAiHubSharingEnabled());

        expect(result.current).toBe(false);
    });

    it('returns true only when both the EE visibility edition and the flag are on', () => {
        visibilityEditionEnabledRef.current = true;
        featureFlagEnabledRef.current = true;

        const {result} = renderHook(() => useAiHubSharingEnabled());

        expect(result.current).toBe(true);
    });
});

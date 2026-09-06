/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bytechef.platform.constant.PlatformType;
import org.junit.jupiter.api.Test;

/**
 * Pins the scope-invariant of {@link AiGuardrailsSettingsTarget}: a {@code WORKSPACE} target must carry a non-null
 * {@code workspaceId}, and vice versa. Both conditions are enforced in the compact constructor, so this test class
 * verifies them by attempting all four invalid combinations and asserting that each throws
 * {@code IllegalArgumentException}. The three factory methods ({@link #workspace(long)}, {@link #platform()},
 * {@link #embedded()}) are tested indirectly through the {@code resolve} methods, which follow the platform type
 * (automation/embedded) and workspace context to produce the appropriate target scope.
 *
 * @version ee
 */
class AiGuardrailsSettingsTargetTest {

    @Test
    void testWorkspaceCarriesItsId() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.workspace(7L);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.WORKSPACE);
        assertThat(target.workspaceId()).isEqualTo(7L);
    }

    @Test
    void testPlatformAndEmbeddedCarryNoWorkspace() {
        assertThat(AiGuardrailsSettingsTarget.platform()
            .workspaceId()).isNull();
        assertThat(AiGuardrailsSettingsTarget.embedded()
            .workspaceId()).isNull();
    }

    @Test
    void testAWorkspaceScopeWithoutAnIdIsRefused() {
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.WORKSPACE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testANonWorkspaceScopeWithAnIdIsRefused() {
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.EMBEDDED, 7L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.PLATFORM, 7L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testANullScopeIsRefused() {
        assertThatThrownBy(() -> new AiGuardrailsSettingsTarget(null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testResolveMapsAnEmbeddedRunToTheEmbeddedScope() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(PlatformType.EMBEDDED, null);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
    }

    @Test
    void testResolveIgnoresAWorkspaceIdOnAnEmbeddedRun() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(PlatformType.EMBEDDED, 7L);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.EMBEDDED);
        assertThat(target.workspaceId()).isNull();
    }

    @Test
    void testResolveMapsAResolvedWorkspaceToTheWorkspaceScope() {
        AiGuardrailsSettingsTarget target = AiGuardrailsSettingsTarget.resolve(PlatformType.AUTOMATION, 7L);

        assertThat(target.scope()).isEqualTo(AiGuardrailsSettingsScope.WORKSPACE);
        assertThat(target.workspaceId()).isEqualTo(7L);
    }

    @Test
    void testResolveMapsAnUnresolvedWorkspaceToThePlatformScope() {
        assertThat(AiGuardrailsSettingsTarget.resolve(PlatformType.AUTOMATION, null)
            .scope())
                .isEqualTo(AiGuardrailsSettingsScope.PLATFORM);
        assertThat(AiGuardrailsSettingsTarget.resolve(null, null)
            .scope())
                .isEqualTo(AiGuardrailsSettingsScope.PLATFORM);
    }
}

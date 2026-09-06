/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.platform.ai.guardrails.domain;

import com.bytechef.platform.constant.PlatformType;
import org.jspecify.annotations.Nullable;

/**
 * Which settings row a guardrail decision should read: the tenant default, one workspace's, or the embedded
 * deployment's.
 *
 * <p>
 * Replaces the bare {@code @Nullable Long workspaceId} that every settings-reading method here used to take. That
 * parameter could not express the third case: {@code null} meant both "an automation run with no resolvable workspace"
 * and "an embedded run", and both resolved the tenant-default {@code PLATFORM} row. The embedded settings page has
 * written an {@code EMBEDDED} row since it shipped, and nothing on the agent path could ever read it -- seven of that
 * page's eight controls did nothing at all.
 * </p>
 *
 * <p>
 * {@link #resolve(PlatformType, Long)} is the only place that interpretation lives. It deliberately ignores a non-null
 * {@code workspaceId} on an embedded run: embedded has no workspaces, so honouring one would resolve some automation
 * tenant's row for an embedded caller.
 * </p>
 *
 * @version ee
 */
public record AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope scope, @Nullable Long workspaceId) {

    public AiGuardrailsSettingsTarget {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }

        if ((scope == AiGuardrailsSettingsScope.WORKSPACE) != (workspaceId != null)) {
            throw new IllegalArgumentException(
                "workspaceId must be non-null exactly when scope is WORKSPACE, got scope=" + scope +
                    ", workspaceId=" + workspaceId);
        }
    }

    public static AiGuardrailsSettingsTarget workspace(long workspaceId) {
        return new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.WORKSPACE, workspaceId);
    }

    public static AiGuardrailsSettingsTarget platform() {
        return new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.PLATFORM, null);
    }

    public static AiGuardrailsSettingsTarget embedded() {
        return new AiGuardrailsSettingsTarget(AiGuardrailsSettingsScope.EMBEDDED, null);
    }

    public static AiGuardrailsSettingsTarget resolve(
        @Nullable PlatformType platformType, @Nullable Long workspaceId) {

        if (platformType == PlatformType.EMBEDDED) {
            return embedded();
        }

        return workspaceId == null ? platform() : workspace(workspaceId);
    }
}
